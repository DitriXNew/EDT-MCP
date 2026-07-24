/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.Repository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Runs a git command in a project's repository via the real {@code git} CLI - the non-UI equivalent of
 * typing it in a terminal. The agent sends a shell-style command STRING (e.g. {@code status},
 * {@code commit -m "fix"}, {@code push origin main}); the tool PARSES it, accepts only a safe
 * {@link #ALLOWED_SUBCOMMANDS whitelist} of subcommands, and executes {@code git} with a clean argument
 * vector - never through a shell, so there is no command-injection surface.
 * <p>
 * Authentication and configuration are the MACHINE's ({@code ssh-agent} / a git credential helper / the
 * user's {@code ~/.gitconfig}) - exactly like the terminal the developer already uses; the tool never
 * stores a secret and never prompts ({@code GIT_TERMINAL_PROMPT=0} makes a missing credential fail fast
 * instead of hanging). It runs equally against an EGit-shared project and a plain {@code .git} checkout,
 * so it does not require EGit.
 * <p>
 * Powerful (it can run push / checkout / stash / ...), so it is placed in its own {@code git} toolset and
 * <b>disabled by default</b> - the operator opts in by checking it in the MCP Server Tools preference tab
 * (it is disabled at the TOOL level, so {@code enable_toolset}, which only affects progressive-disclosure
 * visibility, does not turn it on). Runs on a bounded ({@link #TIMEOUT_SECONDS}s) external process off the
 * UI thread; a timeout kills the process tree. It hardens against command-string injection but TRUSTS the
 * repository's own git config (hooks/filters/aliases) exactly like the developer's terminal.
 * <p>
 * GPG signing is neutralized in the executed COMMAND (the signing config is off and no usable
 * {@code gpg.*program} remains), not by inspecting tokens: git accepts too many spellings of a signing
 * flag for a scan to be reliable, and every attempt at one produced false rejections of legitimate
 * values. A signing request therefore fails fast with a git error instead of opening pinentry - and,
 * because git verifies with the same programs, signature VERIFICATION is unavailable here too (stated
 * in the tool guide).
 */
public class GitTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "git"; //$NON-NLS-1$

    private static final String KEY_COMMAND = "command"; //$NON-NLS-1$
    private static final String KEY_EXIT_CODE = "exitCode"; //$NON-NLS-1$
    private static final String KEY_OUTPUT = "output"; //$NON-NLS-1$
    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    /** Wall-clock bound for the git process; a stalled network op is killed at this point. */
    static final long TIMEOUT_SECONDS = 120;

    /** Upper bound on the returned combined stdout+stderr, so a huge log/diff cannot flood the wire. */
    static final int MAX_OUTPUT_CHARS = 100_000;

    /**
     * The git subcommands the parser accepts - a minimal dev-loop + inspection set. Anything else is
     * rejected with an actionable error (extend deliberately). Deliberately EXCLUDED: {@code config}
     * (sets {@code core.sshCommand} / aliases = arbitrary exec), {@code clean} / {@code gc} /
     * {@code reset} (irrecoverable data loss), {@code filter-branch} / {@code submodule} /
     * {@code worktree} / {@code daemon} / {@code credential} / {@code init} / {@code clone}.
     */
    static final Set<String> ALLOWED_SUBCOMMANDS = Set.of(
        "status", "diff", "log", "show", "blame", "ls-files", "rev-parse", "describe", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "add", "restore", "commit", "stash", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "fetch", "pull", "push", "merge", "cherry-pick", "revert", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "branch", "checkout", "switch", "tag", "remote"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Long options that could make git execute an arbitrary program or operate on a different
     * repository, rejected wherever they appear (by exact value or as a {@code <flag>=<value>} prefix):
     * the {@code --upload-pack} / {@code --receive-pack} / {@code --exec} remote/step-program options and
     * the {@code --config} / {@code --config-env} inline-config (can set {@code core.sshCommand}) and the
     * {@code --git-dir} / {@code --work-tree} / {@code --exec-path} / {@code --namespace} repository
     * redirections; plus {@code --ext-diff} (runs the configured external diff driver), {@code --output}
     * ({@code git diff --output=<file>} writes an arbitrary file) and {@code --help} (spawns the man
     * viewer). These are all long flags that are never a legitimate flag of a whitelisted SUBcommand in
     * a way we want to allow, so blocking them everywhere has no false positives. The short {@code -c} /
     * {@code -C}
     * globals are NOT in this set (they are legitimate subcommand flags, e.g. {@code commit -c} /
     * {@code branch -C}); their dangerous global form is instead rejected by the rule that the first
     * token must be a bare subcommand, so no global option can precede it. {@code rebase} (whose
     * {@code --exec}/{@code -x} runs a command per step) is deliberately omitted from the whitelist.
     */
    static final Set<String> BLOCKED_FLAGS = Set.of(
        "--upload-pack", "--receive-pack", "--exec", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "--config", "--config-env", //$NON-NLS-1$ //$NON-NLS-2$
        "--git-dir", "--work-tree", "--exec-path", "--namespace", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "--ext-diff", "--output", "--help", "--no-index"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * A URL carrying USERINFO ({@code scheme://user@host}, with or without a {@code :password}) -
     * rejected so a secret is never written into the repository. The bare {@code token@} form matters
     * too: a token is commonly passed that way ({@code https://<token>@host/...}) and
     * {@code remote add} / {@code set-url} would PERSIST it in the repo config. Matched ANYWHERE in a
     * token (not only at its start), so an option-attached URL such as
     * {@code --repo=https://t@host/r.git} is caught too; the authority match stops at {@code /},
     * {@code ?} and {@code #} so an {@code @} inside a path or query is not a false positive.
     * <p>
     * NOTE: this keeps the secret out of git CONFIG; it does not scrub the rejected command from the
     * MCP call history, which records the raw request body.
     */
    private static final Pattern CREDENTIAL_URL =
        Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^/?#@\\s]*@"); //$NON-NLS-1$

    /**
     * The sentinel "signing program": an ABSOLUTE path that cannot exist, so git fails to sign
     * immediately instead of opening a pinentry dialog. Absolute (not a bare name) so it is never
     * resolved through {@code PATH}, where an executable of that name could in principle exist.
     */
    private static final String SIGNING_DISABLED_PROGRAM =
        "/nonexistent/edt-mcp-signing-disabled"; //$NON-NLS-1$

    /** Subcommands that can turn a token into a REMOTE, i.e. where a credential URL is refused. */
    private static final Set<String> REMOTE_SUBCOMMANDS =
        Set.of("remote", "push", "fetch", "pull"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Subcommands that rewrite the WORKING TREE, after which the Eclipse workspace must be refreshed
     * so the model sees the new file state (the branch-switch path refreshes for the same reason).
     */
    private static final Set<String> WORKTREE_CHANGING =
        Set.of("checkout", "switch", "pull", "merge", "restore", "rebase", "reset", "stash", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "clean", "apply", "cherry-pick", "revert"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * A transport-helper remote ({@code <helper>::<address>}, e.g. {@code ext::sh -c ...} / {@code fd::})
     * - rejected: the {@code ext} helper runs an arbitrary command, and {@code remote add}/{@code set-url}
     * would PERSIST it (beyond what {@code GIT_ALLOW_PROTOCOL} blocks at use time). The two-colon form
     * distinguishes it from a normal {@code scheme://} URL and a Windows {@code C:\} path.
     */
    // The scheme may start with a digit: git dispatches '9foo::'/'9foo://' as git-remote-9foo too.
    private static final Pattern TRANSPORT_HELPER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9+.\\-]*::"); //$NON-NLS-1$

    /** Scheme of a {@code scheme://...} URL operand (group 1), used to reject unknown-scheme remotes. */
    private static final Pattern URL_SCHEME = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9+.\\-]*)://"); //$NON-NLS-1$

    /**
     * URL schemes accepted for a remote, in git's canonical LOWERCASE. Git treats a URL with any other
     * scheme as a remote-helper ({@code git-remote-<scheme>}) invocation - and it preserves the scheme's
     * case, so {@code HTTPS://} dispatches {@code git-remote-HTTPS}, NOT normal https. We therefore match
     * the scheme case-SENSITIVELY: an unknown or non-canonical-case scheme (e.g. {@code ext://},
     * {@code 9foo://}, {@code HTTPS://}) is rejected, even though {@link #TRANSPORT_HELPER} (the
     * {@code scheme::} form) does not match it. {@code remote add}/{@code set-url} would otherwise persist it.
     */
    private static final Set<String> SAFE_URL_SCHEMES = Set.of(
        "http", "https", "ssh", "git", "ftp", "ftps", "file", "git+ssh", "ssh+git"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run a git command in a project's repository - the non-UI equivalent of typing it in a " //$NON-NLS-1$
            + "terminal. Send it as a shell-style string (e.g. 'status', 'diff HEAD~1', 'commit -m " //$NON-NLS-1$
            + "\"message\"', 'push origin main', 'pull origin main'); it is parsed and only a safe whitelist " //$NON-NLS-1$
            + "of subcommands is executed via the real git CLI (auth/config are the machine's - ssh-agent / " //$NON-NLS-1$
            + "credential helper / ~/.gitconfig - exactly like your terminal). DISABLED by default: enable it " //$NON-NLS-1$
            + "in Preferences -> MCP Server -> Tools first. Full parameters, the whitelist and examples: " //$NON-NLS-1$
            + "get_tool_guide('git')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to run in (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_COMMAND,
                "The git command to run, shell-style (required). A leading 'git' is optional. Examples: " //$NON-NLS-1$
                + "'status', 'diff HEAD~1', 'commit -m \"fix\"', 'push origin main'. Quotes group " //$NON-NLS-1$
                + "arguments (e.g. a commit message); the command is NOT run through a shell. Only a " //$NON-NLS-1$
                + "whitelist of subcommands is accepted - anything else is rejected with an actionable error.", //$NON-NLS-1$
                true)
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether git exited with code 0", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("error", "Actionable message when success is false (rejected command, git " //$NON-NLS-1$ //$NON-NLS-2$
                + "non-zero exit, timeout, or a run failure)") //$NON-NLS-1$
            .integerProperty(KEY_EXIT_CODE, "The git process exit code (0 = success); absent on a rejected " //$NON-NLS-1$
                + "command or a run/timeout failure") //$NON-NLS-1$
            .stringProperty(KEY_COMMAND, "Display form of the command that was run ('git ...', arguments " //$NON-NLS-1$
                + "joined by spaces - not an exact re-quoting)") //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT, "Combined stdout+stderr from git (bounded); also present on timeout") //$NON-NLS-1$
            .booleanProperty(KEY_TRUNCATED, "Present and true when 'output' was truncated to the size cap") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Runs whitelisted git subcommands that can DESTROY work (force/deleting push, branch/tag delete,
        // restore, stash drop/clear) and reach a remote (push/pull/fetch): destructiveHint=true,
        // openWorldHint=true, not read-only.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, null, Boolean.TRUE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_COMMAND);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String command = JsonUtils.extractStringArgument(params, KEY_COMMAND);

        // Parse + whitelist-validate BEFORE touching the repository, so a rejected command never
        // resolves or opens anything.
        List<String> argv;
        try
        {
            argv = parseCommand(command);
        }
        catch (CommandRejectedException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        GitRepositoryResolver.Resolution resolution = null;
        try
        {
            resolution = GitRepositoryResolver.resolve(projectName);
            if (!resolution.ok())
            {
                return resolution.errorJson();
            }
            Repository repo = resolution.repository();
            File workTree = repo.getWorkTree(); // NoWorkTreeException for a bare repo -> caught below
            String output = runGit(argv, workTree);
            // A command that rewrites the working tree changed files behind Eclipse's back: refresh the
            // project so the workspace - and the EDT model built on it - sees them. Refreshed on ANY
            // outcome, not just success: a merge/cherry-pick/revert that hits conflicts, fails late
            // (e.g. while signing) or times out has usually ALREADY updated the index and worktree, and
            // leaving EDT stale after that is worse than a redundant refresh. Best-effort: a refresh
            // failure is logged, never turned into a failed git result.
            if (changesWorkTree(argv))
            {
                refreshProject(resolution.project());
            }
            return output;
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("git: failed for project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to run git for '" + projectName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            if (resolution != null)
            {
                try
                {
                    resolution.closeIfOwned();
                }
                catch (RuntimeException e) // NOSONAR closing must never turn a good result into a thrown error
                {
                    Activator.logError("git: closing repository failed", e); //$NON-NLS-1$
                }
            }
        }
    }

    // ==================== parser (security-critical) ====================

    /**
     * Parses a shell-style git command string into a clean argument vector {@code [git, <subcommand>,
     * ...]}, rejecting anything outside the safe contract. Package-visible for direct unit testing.
     *
     * @param command the user-supplied command string
     * @return the argv to execute (always starts with {@code git})
     * @throws CommandRejectedException when the command is empty, unbalanced-quoted, uses a blocked
     *             exec-flag, leads with an option instead of a subcommand, or names a non-whitelisted
     *             subcommand
     */
    static List<String> parseCommand(String command) throws CommandRejectedException
    {
        List<String> tokens = tokenize(command);
        if (!tokens.isEmpty() && "git".equals(tokens.get(0))) //$NON-NLS-1$
        {
            tokens = tokens.subList(1, tokens.size());
        }
        if (tokens.isEmpty())
        {
            throw new CommandRejectedException("Empty git command. Provide a subcommand, e.g. 'status', " //$NON-NLS-1$
                + "'diff', 'commit -m \"msg\"', 'push origin main'."); //$NON-NLS-1$
        }
        // Fail-closed: scan EVERY token for a denied flag - NOT stopping at a "--", because git may
        // consume a standalone "--" as the value of a preceding option (e.g. 'fetch --server-option --'),
        // leaving a later "--<blocked>" still parsed as an option. Over-rejecting a positional operand
        // that merely looks like a denied flag is the safe trade.
        // A credential URL only matters where git can turn the token into a REMOTE. Restricting the
        // scan to those subcommands is what keeps a legitimate value - a commit message or a
        // 'log -S<text>' / '--grep=' search string that merely contains a URL - from being refused.
        boolean scanUrls = REMOTE_SUBCOMMANDS.contains(tokens.get(0));
        for (String token : tokens)
        {
            if (isBlockedFlag(token))
            {
                throw new CommandRejectedException("The option '" + token + "' is not allowed: it could make " //$NON-NLS-1$ //$NON-NLS-2$
                    + "git run an arbitrary program, read/write files outside the repository, or operate on " //$NON-NLS-1$
                    + "a different repository. Remove it and retry."); //$NON-NLS-1$
            }
            if (TRANSPORT_HELPER.matcher(token).find())
            {
                throw new CommandRejectedException("A transport-helper URL ('<helper>::...', e.g. 'ext::' / " //$NON-NLS-1$
                    + "'fd::') is not allowed: it runs an arbitrary command, and 'remote add'/'set-url' would " //$NON-NLS-1$
                    + "even persist it. Use a normal https:// or ssh remote."); //$NON-NLS-1$
            }
            java.util.regex.Matcher scheme = URL_SCHEME.matcher(token);
            if (scheme.find() && !SAFE_URL_SCHEMES.contains(scheme.group(1)))
            {
                throw new CommandRejectedException("The URL scheme '" + scheme.group(1) + "://' is not " //$NON-NLS-1$ //$NON-NLS-2$
                    + "allowed: only lowercase http(s), ssh, git, ftp(s) and file remotes are accepted (git " //$NON-NLS-1$
                    + "treats any other/uppercase scheme as a remote-helper program). Use a normal remote URL."); //$NON-NLS-1$
            }
            if (scanUrls && hasCredentialUrl(token))
            {
                throw new CommandRejectedException("A URL with an embedded 'username:password@' is not " //$NON-NLS-1$
                    + "accepted: git would persist it in the repository config and it would appear in the MCP " //$NON-NLS-1$
                    + "request history. Use your git credential helper or an ssh key instead."); //$NON-NLS-1$
            }
        }
        String subcommand = tokens.get(0);
        if (subcommand.startsWith("-")) //$NON-NLS-1$
        {
            throw new CommandRejectedException("Expected a git subcommand first, but got the option '" //$NON-NLS-1$
                + subcommand + "'. Global options (e.g. -c / -C) are not accepted; start with a subcommand " //$NON-NLS-1$
                + "such as 'status' or 'commit'."); //$NON-NLS-1$
        }
        if (!ALLOWED_SUBCOMMANDS.contains(subcommand))
        {
            throw new CommandRejectedException("git subcommand '" + subcommand + "' is not supported. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Supported: " + String.join(", ", new TreeSet<>(ALLOWED_SUBCOMMANDS)) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> argv = new ArrayList<>(tokens.size() + 1);
        argv.add("git"); //$NON-NLS-1$
        argv.addAll(tokens);
        return argv;
    }

    /**
     * @return {@code true} when {@code token} is a blocked long flag - by exact name, its
     *         {@code --flag=value} form, OR an <b>abbreviation</b> of one. Git resolves any unambiguous
     *         prefix of a long option (so {@code --upload-pa} means {@code --upload-pack}); we therefore
     *         reject any {@code --<opt>} whose {@code <opt>} is a prefix of a blocked flag's name. Only
     *         {@code --} long options are inspected (the dangerous global {@code -c}/{@code -C} shorts are
     *         already rejected by the rule that the first token must be a bare subcommand).
     */

    /**
     * Whether {@code token} IS a URL carrying userinfo, or carries one as an option VALUE
     * ({@code --repo=https://token@host/...}). Deliberately not a free substring search: a URL quoted
     * inside ordinary text - e.g. {@code commit -m "see https://user@example.com"} - is not a remote
     * this tool would ever persist, and rejecting it would block a legitimate commit message.
     *
     * @param token one parsed token
     * @return {@code true} when the token is (or directly carries) a userinfo URL
     */
    private static boolean hasCredentialUrl(String token)
    {
        // Leading/trailing whitespace must not hide the URL: git would still persist the value.
        String value = token.trim();
        if (CREDENTIAL_URL.matcher(value).lookingAt())
        {
            return true;
        }
        // An option-attached URL: everything after the FIRST '=' of a '-'/'--' option.
        if (value.startsWith("-")) //$NON-NLS-1$
        {
            int eq = value.indexOf('=');
            if (eq >= 0 && eq + 1 < value.length())
            {
                return CREDENTIAL_URL.matcher(value.substring(eq + 1).trim()).lookingAt();
            }
        }
        return false;
    }


    private static boolean isBlockedFlag(String token)
    {
        if (!token.startsWith("--") || token.length() <= 2) //$NON-NLS-1$
        {
            return false;
        }
        int eq = token.indexOf('=');
        String opt = (eq >= 0 ? token.substring(2, eq) : token.substring(2)); // option name without "--"/"=value"
        if (opt.isEmpty())
        {
            return false;
        }
        for (String flag : BLOCKED_FLAGS)
        {
            if (flag.substring(2).startsWith(opt)) // opt is a (possibly full) prefix of this blocked long flag
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a command string into tokens, honouring single and double quotes (which group whitespace,
     * e.g. a commit message) and are then stripped. NOT a shell: no variable expansion, no metacharacter
     * handling, no backslash escapes - metacharacters are ordinary literals (the command is executed via
     * an argument vector, never a shell). Package-visible for direct unit testing.
     *
     * @param command the command string
     * @return the tokens (never {@code null})
     * @throws CommandRejectedException on an unbalanced quote
     */
    static List<String> tokenize(String command) throws CommandRejectedException
    {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++)
        {
            char c = command.charAt(i);
            if (quote != 0)
            {
                if (c == quote)
                {
                    quote = 0; // close quote; token stays open (an empty "" is a real empty argument)
                }
                else
                {
                    current.append(c);
                }
            }
            else if (c == '\'' || c == '"')
            {
                quote = c;
                inToken = true;
            }
            else if (Character.isWhitespace(c))
            {
                if (inToken)
                {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            }
            else
            {
                current.append(c);
                inToken = true;
            }
        }
        if (quote != 0)
        {
            throw new CommandRejectedException("Unbalanced quote in the git command."); //$NON-NLS-1$
        }
        if (inToken)
        {
            tokens.add(current.toString());
        }
        return tokens;
    }

    // ==================== exec ====================

    /**
     * Runs {@code argv} as a bounded external process in {@code workTree}, combining stdout+stderr,
     * capping the output and killing the process on a {@link #TIMEOUT_SECONDS} timeout. Never prompts
     * (auth failures fail fast). The output stream is drained on a separate thread so a large output can
     * never deadlock the wait.
     */
    /**
     * Whether this command can rewrite the working tree, so the Eclipse workspace must be refreshed
     * afterwards. Keyed on the SUBCOMMAND (the first token after {@code git}), matching the set the
     * branch tools already treat as checkout-like.
     *
     * @param argv the parsed command ({@code git} first)
     * @return {@code true} for a worktree-changing subcommand
     */

    private static boolean changesWorkTree(List<String> argv)
    {
        if (argv.size() < 2)
        {
            return false;
        }
        return WORKTREE_CHANGING.contains(argv.get(1));
    }

    /**
     * Refreshes {@code project} so the workspace picks up files git changed on disk. Best-effort: a
     * failure is logged and never propagated - the git command itself already succeeded.
     *
     * @param project the project to refresh (may be {@code null} for a non-workspace repository)
     */
    private static void refreshProject(IProject project)
    {
        if (project == null || !project.exists())
        {
            return;
        }
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
        catch (CoreException e)
        {
            Activator.logError("git: refreshing project '" + project.getName() //$NON-NLS-1$
                + "' after a worktree-changing command failed", e); //$NON-NLS-1$
        }
    }

    private String runGit(List<String> argv, File workTree)
    {
        ProcessBuilder builder = new ProcessBuilder(withNonInteractiveConfig(argv));
        builder.directory(workTree);
        builder.redirectErrorStream(true);
        hardenEnv(builder.environment());

        String shellForm = String.join(" ", argv); //$NON-NLS-1$
        Process process = null;
        Thread drain = null;
        StringBuilder out = new StringBuilder();
        boolean[] truncated = {false};
        try
        {
            process = builder.start();
            process.getOutputStream().close(); // no stdin
            final Process started = process;
            drain = new Thread(() -> drainBounded(started, out, truncated), "git-output-drain"); //$NON-NLS-1$
            drain.setDaemon(true);
            drain.start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                killTree(process); // destroyForcibly + wait, and the child's own descendants
                drain.join(2000);
                ToolResult timeout = ToolResult.error("'" + shellForm + "' timed out after " + TIMEOUT_SECONDS //$NON-NLS-1$ //$NON-NLS-2$
                    + " seconds and was killed. Check network connectivity / the remote, or run a smaller " //$NON-NLS-1$
                    + "command.") //$NON-NLS-1$
                    .put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out));
                if (truncated[0])
                {
                    timeout.put(KEY_TRUNCATED, true);
                }
                return timeout.toJson();
            }
            drain.join(2000);
            int exitCode = process.exitValue();
            ToolResult result = exitCode == 0
                ? ToolResult.success()
                : ToolResult.error("git exited with code " + exitCode + " for '" + shellForm //$NON-NLS-1$ //$NON-NLS-2$
                    + "'. See 'output' for git's own message."); //$NON-NLS-1$
            result.put(KEY_EXIT_CODE, exitCode).put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out));
            if (truncated[0])
            {
                result.put(KEY_TRUNCATED, true);
            }
            return result.toJson();
        }
        catch (IOException e)
        {
            Activator.logError("git: failed to run '" + shellForm + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to run '" + shellForm + "': " + e.getMessage()) //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out)).toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt(); // restore the interrupt flag
            return ToolResult.error("'" + shellForm + "' was interrupted.") //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_COMMAND, shellForm).toJson();
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                killTree(process); // never leak the process on an early return / exception
            }
        }
    }

    /** @return a thread-safe snapshot of the drained output so far. */
    private static String snapshot(StringBuilder out)
    {
        synchronized (out)
        {
            return out.toString();
        }
    }

    /**
     * Force-kills {@code process} AND every descendant captured at call time (a hook / ssh / helper child),
     * then awaits each against a shared deadline so the kill is real - and no orphan keeps holding the
     * output pipe - before we return. Best-effort and platform-dependent, but never throws.
     */
    private static void killTree(Process process)
    {
        List<ProcessHandle> descendants = new ArrayList<>();
        try
        {
            process.descendants().forEach(descendants::add);
        }
        catch (RuntimeException e) // NOSONAR descendants() is best-effort and platform-dependent
        {
            Activator.logError("git: enumerating process descendants failed", e); //$NON-NLS-1$
        }
        // Acquire the parent handle ONCE inside a guard - toHandle() can throw
        // UnsupportedOperationException/SecurityException, which must not skip the parent kill.
        ProcessHandle parent = null;
        try
        {
            parent = process.toHandle();
        }
        catch (RuntimeException e) // NOSONAR fall back to the Process API below
        {
            Activator.logError("git: obtaining the process handle failed; using the Process API", e); //$NON-NLS-1$
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // Destroy the parent INDEPENDENTLY of any descendant failure, and guard each descendant kill so
        // one platform hiccup cannot stop the loop before the rest (and the parent) are killed.
        if (parent != null)
        {
            destroyQuietly(parent);
        }
        else
        {
            destroyProcessQuietly(process);
        }
        for (ProcessHandle handle : descendants)
        {
            destroyQuietly(handle);
        }
        if (parent != null)
        {
            awaitExitQuietly(parent, deadlineNanos);
        }
        else
        {
            try
            {
                process.waitFor(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        for (ProcessHandle handle : descendants)
        {
            awaitExitQuietly(handle, deadlineNanos);
        }
    }

    /** {@code Process.destroyForcibly()} fallback that never propagates a failure (handle-less path). */
    private static void destroyProcessQuietly(Process process)
    {
        try
        {
            process.destroyForcibly();
        }
        catch (RuntimeException e) // NOSONAR best-effort: killing must not throw out of cleanup
        {
            Activator.logError("git: destroying the process failed", e); //$NON-NLS-1$
        }
    }

    /** {@code destroyForcibly()} that never propagates a platform/permission failure. */
    private static void destroyQuietly(ProcessHandle handle)
    {
        try
        {
            handle.destroyForcibly();
        }
        catch (RuntimeException e) // NOSONAR best-effort: killing must not throw out of cleanup
        {
            Activator.logError("git: destroying a process handle failed", e); //$NON-NLS-1$
        }
    }

    /** Waits for {@code handle} to exit, bounded by the shared {@code deadlineNanos}; never throws. */
    private static void awaitExitQuietly(ProcessHandle handle, long deadlineNanos)
    {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0)
        {
            return;
        }
        try
        {
            handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e) // NOSONAR timed out or already gone; best-effort cleanup
        {
            // cannot wait longer than the shared deadline
        }
    }

    /**
     * Sets the safe, non-interactive git environment and drops inherited {@code GIT_*} variables that
     * could redirect git to another repository, config, object store, exec-path or proxy program than the
     * resolved one. Auth-related variables (SSH, credential helpers, {@code HOME}, {@code PATH}) and the
     * machine's own {@code ~/.gitconfig} are deliberately KEPT: authentication and repository config are
     * the machine's, exactly like the developer's terminal.
     */
    /**
     * Inserts {@code -c core.askPass=} right after the {@code git} executable, so a {@code core.askPass}
     * configured in the machine's gitconfig cannot pop a GUI credential dialog for this call. The
     * caller-supplied tokens are untouched (and {@code --config}/{@code --config-env} stay blocked for
     * them), so this adds no new injection surface.
     *
     * @param argv the parsed command ({@code git} first)
     * @return a new argv with the non-interactive config option applied
     */
    /**
     * The hardening options this tool prepends to every git call, exposed so a unit test can assert
     * the non-interactive guarantees without spawning git. Package-private on purpose.
     *
     * @return the config tokens applied to a call
     */
    static List<String> nonInteractiveConfigForTest()
    {
        return withNonInteractiveConfig(List.of("git")); //$NON-NLS-1$
    }

    private static List<String> withNonInteractiveConfig(List<String> argv)
    {
        List<String> command = new ArrayList<>(argv.size() + 2);
        command.add(argv.get(0));
        command.add("-c"); //$NON-NLS-1$
        command.add("core.askPass="); //$NON-NLS-1$
        // A configured commit.gpgSign / tag.gpgSign would launch gpg-agent's pinentry - a GUI prompt
        // none of the other settings cover. Signing is work an unattended agent cannot complete, so it
        // is off for this call; the caller cannot force it back on (-S / --gpg-sign are blocked).
        command.add("-c"); //$NON-NLS-1$
        command.add("commit.gpgSign=false"); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("tag.gpgSign=false"); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("push.gpgSign=false"); //$NON-NLS-1$
        // tag.forceSignAnnotated=true signs an annotated tag even with tag.gpgSign=false.
        command.add("-c"); //$NON-NLS-1$
        command.add("tag.forceSignAnnotated=false"); //$NON-NLS-1$
        // GIT_SSH_COMMAND below forces OpenSSH; a configured ssh.variant (e.g. plink) would make git
        // pass Plink-style arguments to it, breaking every ssh remote - pin the variant to match.
        command.add("-c"); //$NON-NLS-1$
        command.add("ssh.variant=ssh"); //$NON-NLS-1$
        // THE signing guarantee: with no usable gpg program, any signing request - however it was
        // spelled, and whatever the repository config says - fails immediately with a git error
        // instead of opening the gpg-agent pinentry dialog an unattended session cannot answer.
        command.add("-c"); //$NON-NLS-1$
        command.add("gpg.program=" + SIGNING_DISABLED_PROGRAM); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("gpg.openpgp.program=" + SIGNING_DISABLED_PROGRAM); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("gpg.x509.program=" + SIGNING_DISABLED_PROGRAM); //$NON-NLS-1$
        command.add("-c"); //$NON-NLS-1$
        command.add("gpg.ssh.program=" + SIGNING_DISABLED_PROGRAM); //$NON-NLS-1$
        // With gpg.format=ssh and no user.signingKey, git runs this helper BEFORE the signing program
        // - a configured one could prompt. Empty it so key discovery cannot become interactive.
        command.add("-c"); //$NON-NLS-1$
        command.add("gpg.ssh.defaultKeyCommand="); //$NON-NLS-1$
        command.addAll(argv.subList(1, argv.size()));
        return command;
    }

    private static void hardenEnv(Map<String, String> env)
    {
        env.put("GIT_TERMINAL_PROMPT", "0"); // a missing credential fails fast, never a hanging prompt //$NON-NLS-1$ //$NON-NLS-2$
        env.put("GIT_PAGER", "cat"); // never invoke a pager (would block) //$NON-NLS-1$ //$NON-NLS-2$
        env.put("GIT_OPTIONAL_LOCKS", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        // Restrict transports to the safe, well-known set so a remote like 'ext::sh -c <cmd>' / 'fd::'
        // (transport-helper protocols that run an arbitrary command) is refused regardless of config.
        env.put("GIT_ALLOW_PROTOCOL", "file:git:ssh:http:https:ftp:ftps"); //$NON-NLS-1$ //$NON-NLS-2$
        // A command that needs an editor (e.g. 'commit' with no -m) fails fast instead of hanging on one.
        env.put("GIT_EDITOR", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        env.put("GIT_SEQUENCE_EDITOR", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        // GIT_TERMINAL_PROMPT=0 silences git's OWN prompt but not the ssh child: OpenSSH defaults to
        // BatchMode=no, so a key with a passphrase (or an unknown host) would block a push/fetch on an
        // interactive prompt. Force ssh non-interactive too - unattended safety, the project rule.
        env.put("GIT_SSH_COMMAND", "ssh -oBatchMode=yes"); //$NON-NLS-1$ //$NON-NLS-2$
        // An inherited GIT_SSH_VARIANT (e.g. 'plink') would make git format the arguments for another
        // client than the OpenSSH one forced above.
        env.put("GIT_SSH_VARIANT", "ssh"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String redirect : new String[]{
            "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_NAMESPACE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_EXEC_PATH", "GIT_SHALLOW_FILE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "GIT_CONFIG", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "GIT_EXTERNAL_DIFF", "GIT_PROXY_COMMAND", "GIT_TRACE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // An askpass helper would pop a GUI credential dialog in a desktop EDT session (git tries
            // GIT_ASKPASS, then core.askPass, then SSH_ASKPASS) - drop the env ones here; the config
            // one is neutralized per-call with '-c core.askPass=' (see buildCommand).
            "GIT_ASKPASS", "SSH_ASKPASS"}) // interactive credential dialogs //$NON-NLS-1$ //$NON-NLS-2$
        {
            env.remove(redirect);
        }
    }

    /** Drains the process' combined output into {@code out}, stopping appends at {@link #MAX_OUTPUT_CHARS}. */
    private static void drainBounded(Process process, StringBuilder out, boolean[] truncated)
    {
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1)
            {
                synchronized (out)
                {
                    int room = MAX_OUTPUT_CHARS - out.length();
                    if (room <= 0)
                    {
                        truncated[0] = true;
                        continue; // keep draining (so the process does not block on a full pipe), but stop storing
                    }
                    out.append(buffer, 0, Math.min(read, room));
                    if (read > room)
                    {
                        truncated[0] = true;
                    }
                }
            }
        }
        catch (IOException e)
        {
            // The process was killed or the stream closed; whatever was captured is returned as-is.
            Activator.logError("git: reading process output failed", e); //$NON-NLS-1$
        }
    }

    /** Thrown by the parser when a command is outside the safe contract; its message is the tool error. */
    static final class CommandRejectedException extends Exception
    {
        private static final long serialVersionUID = 1L;

        CommandRejectedException(String message)
        {
            super(message);
        }
    }

    /** @return the sorted allowed-subcommand list (for the guide / tests). */
    static List<String> allowedSubcommands()
    {
        return new ArrayList<>(new TreeSet<>(ALLOWED_SUBCOMMANDS));
    }
}
