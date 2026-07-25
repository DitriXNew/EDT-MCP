/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.Repository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
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
        "--ext-diff", "--output", "--help", "--no-index", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Options that take an ARBITRARY FILE as their operand on an otherwise allowlisted
        // subcommand: 'blame --contents <file>' prints that file's lines, and 'commit/tag/merge
        // --file <file>' copies it into the message, where 'log' reads it straight back out. The
        // diff-operand guard cannot see these - the path is an option VALUE, not an operand.
        "--contents", "--file", "--template", "--pathspec-from-file", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "--ignore-revs-file", "--exclude-from", //$NON-NLS-1$ //$NON-NLS-2$
        // A merge strategy is a PROGRAM: git runs 'git-<strategy>' from PATH, so '-s pwn' is an
        // arbitrary-program option in the same class as --upload-pack. The default strategy needs no
        // flag, so refusing the option costs nothing here. ('-X'/--strategy-option only configures
        // the built-in strategy and stays allowed.)
        "--strategy"); //$NON-NLS-1$

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
        Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*+://[^/?#@\\s]*+@"); //$NON-NLS-1$

    /**
     * The sentinel "signing program": an ABSOLUTE path that cannot exist, so git fails to sign
     * immediately instead of opening a pinentry dialog. Absolute (not a bare name) so it is never
     * resolved through {@code PATH}, where an executable of that name could in principle exist.
     */
    private static final String SIGNING_DISABLED_PROGRAM =
        "/nonexistent/edt-mcp-signing-disabled"; //$NON-NLS-1$

    /** The {@code ://} that separates a URL scheme from its authority. */
    private static final String SCHEME_SEPARATOR = "://"; //$NON-NLS-1$

    /**
     * The short options that consume the REST of their cluster as a value, PER SUBCOMMAND - the same
     * letter differs: {@code -c} takes a commit for {@code commit} but is the value-less
     * {@code --cached} for {@code ls-files} and {@code blame}, and {@code -n} is a line count for
     * {@code tag} but {@code --no-stat} for {@code merge}. A cluster scan stops at one of these,
     * because everything after it is that option's value rather than another flag.
     * <p>
     * Only the subcommands whose clusters are scanned appear here; an unlisted one stops at nothing,
     * which errs toward refusing a cluster rather than letting a file/strategy option through.
     */
    private static final Map<String, String> VALUE_TAKING_SHORT_OPTIONS = Map.of(
        "commit", "mcCuSt", //$NON-NLS-1$ //$NON-NLS-2$
        "tag", "mnu", //$NON-NLS-1$ //$NON-NLS-2$
        "merge", "mXS", //$NON-NLS-1$ //$NON-NLS-2$
        "pull", "mXSjor", //$NON-NLS-1$ //$NON-NLS-2$
        "blame", "LCM", //$NON-NLS-1$ //$NON-NLS-2$
        "ls-files", "x"); //$NON-NLS-1$ //$NON-NLS-2$

    /** How long the MCP call waits for the post-command workspace refresh before returning. */
    private static final long REFRESH_WAIT_SECONDS = 30;

    /** How often the run loop looks for new child processes while git is alive. */
    private static final long DESCENDANT_POLL_MILLIS = 250;

    /** Seconds the {@code git config --get core.sshCommand} probe may take before it is killed. */
    private static final int CONFIG_PROBE_TIMEOUT_SECONDS = 5;

    /** Milliseconds to wait for the probe's drain thread after the process itself has ended. */
    private static final long DRAIN_JOIN_MILLIS = 1000;

    /**
     * Subcommands whose {@code -F} is the short spelling of {@code --file} (read the message from a
     * file). Scoped, because {@code -F} means {@code --fixed-strings} for {@code log}, which is
     * legitimate.
     */
    /**
     * Subcommands whose {@code -s} is the short spelling of {@code --strategy} (a PROGRAM name).
     * NOT {@code cherry-pick} / {@code revert}: there {@code -s} is {@code --signoff}, which is
     * harmless - their {@code --strategy} is blocked by the long-option list like everywhere else.
     */
    private static final Set<String> STRATEGY_SUBCOMMANDS =
        Set.of("merge", "pull"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Set<String> MESSAGE_FILE_SUBCOMMANDS =
        Set.of("commit", "tag", "merge"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * The subcommands that only READ. Everything else in {@link #ALLOWED_SUBCOMMANDS} is
     * write-capable and therefore asks for consent - including a read-only FORM of one, such as
     * {@code remote -v} - see {@link #destructiveForm}.
     */
    private static final Set<String> READ_ONLY_SUBCOMMANDS = Set.of(
        "status", "diff", "log", "show", "blame", "ls-files", "rev-parse", "describe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$

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

        long startedAt = System.nanoTime();
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
            String outsideOperand = outsideRepositoryOperand(argv, workTree);
            if (outsideOperand != null)
            {
                return ToolResult.error(outsideOperand).toJson();
            }
            // Consent LAST, after every read-only check has passed: a stale project name or a command
            // this tool would refuse anyway must fail on its own error, not sit in front of a human
            // (or burn the consent timeout) for a call that could never run.
            String consentError = requireConsentFor(argv);
            if (consentError != null)
            {
                return consentError;
            }
            String output = runGit(argv, workTree);
            // A command that rewrites the working tree changed files behind Eclipse's back: refresh the
            // project so the workspace - and the EDT model built on it - sees them. Refreshed on ANY
            // outcome, not just success: a merge/cherry-pick/revert that hits conflicts, fails late
            // (e.g. while signing) or times out has usually ALREADY updated the index and worktree, and
            // leaving EDT stale after that is worse than a redundant refresh. Best-effort: a refresh
            // failure is logged, never turned into a failed git result.
            if (changesWorkTree(argv))
            {
                refreshWorkTree(workTree, startedAt);
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
        // The remote-URL guards (credential URL, transport helper, unsafe scheme) run only where a
        // token can actually BECOME a remote. Elsewhere such a string is ordinary text - a commit
        // message may legitimately carry 'vscode://file/...' or an 'ext::' prefix, and rejecting it
        // would be a false alarm about a value git never resolves.
        boolean scanUrls = REMOTE_SUBCOMMANDS.contains(tokens.get(0));
        boolean scanMessageFile = MESSAGE_FILE_SUBCOMMANDS.contains(tokens.get(0));
        // The SHORT spellings of the file-reading options, each meaningful only for one subcommand:
        // 'blame -S <revs-file>' and 'ls-files -X <exclude-file>'. Both letters mean something else
        // elsewhere ('log -S' is the pickaxe, 'merge -X' a strategy option), so they are scoped.
        // '-s' is the short --strategy for the merge-like subcommands; elsewhere ('log -s',
        // 'show -s') it means --no-patch, which is harmless.
        boolean scanStrategy = STRATEGY_SUBCOMMANDS.contains(tokens.get(0));
        String shortFileFlag = null;
        if ("blame".equals(tokens.get(0))) //$NON-NLS-1$
        {
            shortFileFlag = "-S"; //$NON-NLS-1$
        }
        else if ("ls-files".equals(tokens.get(0))) //$NON-NLS-1$
        {
            shortFileFlag = "-X"; //$NON-NLS-1$
        }
        for (String token : tokens)
        {
            if (isBlockedFlag(token))
            {
                throw new CommandRejectedException("The option '" + token + "' is not allowed: it could make " //$NON-NLS-1$ //$NON-NLS-2$
                    + "git run an arbitrary program, read/write files outside the repository, or operate on " //$NON-NLS-1$
                    + "a different repository. Remove it and retry."); //$NON-NLS-1$
            }
            if (scanUrls && TRANSPORT_HELPER.matcher(token).find())
            {
                throw new CommandRejectedException("A transport-helper URL ('<helper>::...', e.g. 'ext::' / " //$NON-NLS-1$
                    + "'fd::') is not allowed: it runs an arbitrary command, and 'remote add'/'set-url' would " //$NON-NLS-1$
                    + "even persist it. Use a normal https:// or ssh remote."); //$NON-NLS-1$
            }
            java.util.regex.Matcher scheme = URL_SCHEME.matcher(token);
            if (scanUrls && scheme.find() && !SAFE_URL_SCHEMES.contains(scheme.group(1)))
            {
                throw new CommandRejectedException("The URL scheme '" + scheme.group(1) + "://' is not " //$NON-NLS-1$ //$NON-NLS-2$
                    + "allowed: only lowercase http(s), ssh, git, ftp(s) and file remotes are accepted (git " //$NON-NLS-1$
                    + "treats any other/uppercase scheme as a remote-helper program). Use a normal remote URL."); //$NON-NLS-1$
            }
            if (scanMessageFile && clusterCarries(token, 'F', tokens.get(0)))
            {
                throw new CommandRejectedException("'" + token + "' reads the message from a FILE, which " //$NON-NLS-1$
                    + "would " //$NON-NLS-1$
                    + "copy a file this tool does not govern into the repository. Pass the message " //$NON-NLS-1$
                    + "inline with -m instead."); //$NON-NLS-1$
            }
            if (scanStrategy && selectsStrategy(token, tokens.get(0)))
            {
                // Any single-dash token carrying 's', not just a leading '-s': git reads '-nspwn' as
                // '-n -s pwn'. Telling a clustered FLAG from a letter inside an attached value would
                // mean reimplementing git's per-subcommand option arity, so the whole cluster is
                // refused - pass the message separately (-m "...") if that is what carried the 's'.
                throw new CommandRejectedException("'" + token + "' can select a merge STRATEGY, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "which git runs as the program 'git-<strategy>' from PATH - this tool does not " //$NON-NLS-1$
                    + "run arbitrary programs. Drop '-s' (git's default strategy needs no flag) and " //$NON-NLS-1$
                    + "pass any message as a separate -m \"...\" argument."); //$NON-NLS-1$
            }
            if (shortFileFlag != null && clusterCarries(token, shortFileFlag.charAt(1), tokens.get(0)))
            {
                throw new CommandRejectedException("'" + token + "' takes a FILE whose contents git " //$NON-NLS-1$ //$NON-NLS-2$
                    + "reports back, so this option is refused whatever the path - drop it, or read " //$NON-NLS-1$
                    + "the file with read_module_source if it belongs to the project."); //$NON-NLS-1$
            }
            if (scanUrls && scheme.find(0) && token.indexOf('?') > 0)
            {
                throw new CommandRejectedException("A remote URL with a query string is not accepted: " //$NON-NLS-1$
                    + "a credential is commonly passed that way ('...repo.git?access_token=<secret>') " //$NON-NLS-1$
                    + "and 'remote add' / 'set-url' would persist it in the repository config. Use a " //$NON-NLS-1$
                    + "credential helper or an ssh remote."); //$NON-NLS-1$
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
     * Refreshes every workspace project inside {@code workTree} so Eclipse - and the EDT model built
     * on it - picks up the files git changed on disk. Runs on ANY outcome, not just a successful one:
     * a merge or cherry-pick that hit conflicts, failed late or timed out has usually already updated
     * the worktree. Best-effort: a failure is logged and never turned into a failed git result.
     *
     * @param workTree the repository work tree whose projects must be refreshed
     * @param startedAt the call's start timestamp, so the wait shares the command's own budget
     */
    private static void refreshWorkTree(File workTree, long startedAt)
    {
        List<IProject> projects = projectsInside(workTree);
        if (projects.isEmpty())
        {
            return;
        }
        Job refresh = new Job("Refresh projects after a git command") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                for (IProject project : projects)
                {
                    if (monitor.isCanceled())
                    {
                        return Status.CANCEL_STATUS;
                    }
                    refreshProject(project, monitor);
                }
                return Status.OK_STATUS;
            }
        };
        refresh.setUser(false);
        refresh.schedule();
        try
        {
            // Bounded by what is LEFT of the call's own budget, so a slow refresh cannot push the
            // response past the timeout the git process already respects. The Job keeps running
            // after the wait expires, so the workspace still catches up - the caller simply is not
            // made to wait for it.
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            long budgetMillis = TIMEOUT_SECONDS * 1000L - elapsedMillis;
            long waitMillis = Math.min(REFRESH_WAIT_SECONDS * 1000L, budgetMillis);
            if (waitMillis > 0)
            {
                refresh.join(waitMillis, null);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (OperationCanceledException e) // NOSONAR the refresh is best-effort
        {
            Activator.logError("git: the post-command refresh was cancelled", e); //$NON-NLS-1$
        }
    }

    /**
     * The OPEN workspace projects whose location lies inside {@code workTree}.
     * <p>
     * Several Eclipse projects can share one git worktree, and a command runs from its ROOT: a
     * checkout or pull changes the siblings too, so refreshing only the addressed project would leave
     * their models stale for the next MCP call.
     *
     * @param workTree the repository work tree
     * @return the projects to refresh (never {@code null})
     */
    private static List<IProject> projectsInside(File workTree)
    {
        List<IProject> inside = new ArrayList<>();
        Path root;
        try
        {
            root = workTree.toPath().toRealPath();
        }
        catch (IOException e)
        {
            root = workTree.toPath().toAbsolutePath().normalize();
        }
        for (IProject project : ProjectContext.allProjects())
        {
            IPath location = project.getLocation();
            if (!project.isOpen() || location == null)
            {
                continue;
            }
            try
            {
                Path projectPath = location.toFile().toPath().toRealPath();
                if (projectPath.startsWith(root))
                {
                    inside.add(project);
                }
            }
            catch (IOException e) // NOSONAR a project we cannot canonicalize is simply not refreshed
            {
                Activator.logError("git: resolving the location of '" + project.getName() //$NON-NLS-1$
                    + "' failed", e); //$NON-NLS-1$
            }
        }
        return inside;
    }

    private static void refreshProject(IProject project, IProgressMonitor monitor)
    {
        if (project == null || !project.exists())
        {
            return;
        }
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
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
        hardenEnv(builder.environment(), workTree);

        String shellForm = String.join(" ", argv); //$NON-NLS-1$
        Process process = null;
        Thread drain = null;
        // Snapshotted WHILE git runs: once the parent exits its children are re-parented and
        // process.descendants() no longer reports them, so a late capture would find nothing.
        List<ProcessHandle> descendants = new ArrayList<>();
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

            if (!awaitExit(process, descendants))
            {
                killTree(process); // destroyForcibly + wait, and the child's own descendants
                drain.join(2000);
                ToolResult timeout = ToolResult.error("'" + shellForm + "' timed out after " + TIMEOUT_SECONDS //$NON-NLS-1$ //$NON-NLS-2$
                    + " seconds and was killed. Check network connectivity / the remote, or run a smaller " //$NON-NLS-1$
                    + "command.") //$NON-NLS-1$
                    .put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out, truncated));
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
            result.put(KEY_EXIT_CODE, exitCode).put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out, truncated));
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
                .put(KEY_COMMAND, shellForm).put(KEY_OUTPUT, snapshot(out, truncated)).toJson();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt(); // restore the interrupt flag
            return ToolResult.error("'" + shellForm + "' was interrupted.") //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_COMMAND, shellForm).toJson();
        }
        finally
        {
            if (process != null)
            {
                captureDescendants(process, descendants);
                // Kill the parent when it is still running (an early return / exception), and in any
                // case the descendants captured while it ran: a hook, filter, credential helper or ssh
                // wrapper can BACKGROUND a child that inherits the output pipe and outlives git.
                if (process.isAlive())
                {
                    killTree(process);
                }
                // Always the captured handles too: a child that DETACHED before this point is no
                // longer a descendant of the parent, so killTree alone would leave it running.
                killAll(descendants);
                if (drain != null && drain.isAlive())
                {
                    // Something still holds the write end, so the drain would block forever. Closing
                    // our read end ends that thread; a re-parented grandchild we can no longer
                    // identify is out of reach, but it costs us no thread and no pipe from here on.
                    closeQuietly(process.getInputStream());
                }
            }
        }
    }

    /**
     * Force-kills every handle that is still alive. Best-effort: a process that is already gone, or
     * that we may not signal, is skipped.
     *
     * @param handles the process handles to kill
     */
    private static void killAll(List<ProcessHandle> handles)
    {
        for (ProcessHandle handle : handles)
        {
            try
            {
                if (handle.isAlive())
                {
                    handle.destroyForcibly();
                }
            }
            catch (RuntimeException e) // NOSONAR cleanup must never replace the command's result
            {
                Activator.logError("git: killing a child process failed", e); //$NON-NLS-1$
            }
        }
    }

    /** Closes a stream, ignoring any failure - used only to unblock a reader. */
    private static void closeQuietly(java.io.Closeable stream)
    {
        try
        {
            stream.close();
        }
        catch (IOException e) // NOSONAR closing is best-effort: the reader is what matters
        {
            Activator.logError("git: closing the output pipe failed", e); //$NON-NLS-1$
        }
    }

    /**
     * Returns the ssh command the repository's configuration asks for, or {@code null} when none is
     * configured. Read with a plain {@code git config --get} in the work tree, so it honours the same
     * repo / global / system precedence git itself would apply.
     * <p>
     * Only consulted to decide whether we may install our own non-interactive ssh command - never
     * executed here.
     *
     * @param workTree the repository work tree to read the configuration in
     * @return the configured command, or {@code null} when unset, empty or unreadable
     */
    private static String configuredSshCommand(File workTree)
    {
        Process process = null;
        try
        {
            ProcessBuilder probe =
                new ProcessBuilder("git", "config", "--get", "core.sshCommand"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            probe.directory(workTree);
            probe.redirectErrorStream(true);
            scrubInheritedGitEnv(probe.environment());
            process = probe.start();
            // Drain CONCURRENTLY with the timed wait. Reading first would hang forever on a git that
            // never returns (a config include on a stalled network path); waiting first would hang
            // git itself once an oversized value fills the pipe - and that false timeout would make
            // us install our own ssh command over the user's.
            AtomicReference<String> firstLine = new AtomicReference<>();
            Process reading = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(reading.getInputStream(), StandardCharsets.UTF_8)))
                {
                    firstLine.set(reader.readLine());
                    while (reader.readLine() != null)
                    {
                        // keep draining so git can finish writing
                    }
                }
                catch (IOException e) // NOSONAR the probe result is best-effort by design
                {
                    firstLine.set(null);
                }
            }, "git-config-probe"); //$NON-NLS-1$
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(CONFIG_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                killTree(process); // closes the pipe, so the drain ends on its own
                joinDrain(drain);
                return null;
            }
            if (!joinDrain(drain))
            {
                // Still stuck on the pipe: there is no value here we could trust.
                return null;
            }
            String value = firstLine.get();
            if (process.exitValue() != 0 || value == null || value.isBlank())
            {
                return null;
            }
            return value.trim();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (IOException | RuntimeException e) // NOSONAR the probe must never fail the git call
        {
            Activator.logError("git: reading core.sshCommand failed", e); //$NON-NLS-1$
            return null;
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                killTree(process);
            }
        }
    }

    /**
     * Waits briefly for the probe's drain thread to finish.
     *
     * @param drain the drain thread
     * @return {@code true} when it finished, so its captured value is complete and visible here
     */
    private static boolean joinDrain(Thread drain)
    {
        try
        {
            drain.join(DRAIN_JOIN_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        return !drain.isAlive();
    }

    /**
     * Asks the destructive-consent gate when the command is write-capable.
     *
     * @param argv the validated argument vector
     * @return an error JSON when consent was refused, or {@code null} when the command may run
     */
    private static String requireConsentFor(List<String> argv)
    {
        String destructiveForm = destructiveForm(argv);
        if (destructiveForm == null)
        {
            return null;
        }
        ConsentPreview preview = new ConsentPreview("git " + destructiveForm, //$NON-NLS-1$
            "'git " + destructiveForm + "' is a write-capable subcommand.", 1, //$NON-NLS-1$ //$NON-NLS-2$
            List.of(String.join(" ", argv.subList(1, argv.size())))); //$NON-NLS-1$
        DestructiveConsentGate.ConsentDecision decision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (decision == DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return null;
        }
        return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
    }

    /**
     * Names the subcommand when it is WRITE-CAPABLE, or {@code null} when it only reads.
     * <p>
     * The rule is deliberately coarse: every WRITE-CAPABLE subcommand asks, only the read-only ones
     * are silent - so a read-only FORM of a write-capable subcommand ({@code remote -v},
     * {@code branch --list}, {@code stash list}) prompts too. Whether a
     * given {@code push}, {@code checkout} or {@code merge} actually destroys work depends on options
     * git resolves per subcommand, with bundles ({@code -fq}), attached values ({@code -bfeature})
     * and accepted abbreviations ({@code --forc}); every attempt to classify at that level both
     * missed real forms ({@code push +main:main}, {@code merge --abort} discarding conflict
     * resolutions) and prompted for safe ones. Under-asking loses work that git cannot bring back;
     * over-asking costs one click - or none, once the operator sets the destructive-consent level
     * (the preference, or {@code EDT_MCP_DESTRUCTIVE_CONSENT} at launch) for unattended use.
     * <p>
     * Reading never asks, so an agent can inspect the repository freely; for listing branches there
     * is the dedicated {@code list_git_branches} tool.
     *
     * @param argv the validated argument vector ({@code argv[0]} is git, {@code argv[1]} the subcommand)
     * @return the subcommand when it writes, or {@code null} when it only reads
     */
    static String destructiveForm(List<String> argv)
    {
        if (argv.size() < 2)
        {
            return null;
        }
        String subcommand = argv.get(1);
        return READ_ONLY_SUBCOMMANDS.contains(subcommand) ? null : subcommand;
    }

    /**
     * Whether a single-dash token can carry {@code -s} (the strategy PROGRAM) for a merge-like
     * subcommand. The cluster is read left to right and STOPS at the first letter that takes a value
     * ({@code -m} a message, {@code -X} a strategy option): everything after it is that value, not
     * more flags, so {@code merge -Xours} and {@code merge -mfixes} stay accepted while
     * {@code merge -nspwn} (git reads {@code -n -s pwn}) does not.
     *
     * @param token the token to inspect
     * @return {@code true} when the token can select a strategy
     */
    private static boolean selectsStrategy(String token, String subcommand)
    {
        return clusterCarries(token, 's', subcommand);
    }

    /**
     * Whether a single-dash token carries {@code flag} as an OPTION - including inside a cluster
     * ({@code -qF<file>} is {@code -q -F <file>}).
     * <p>
     * The cluster is read left to right and STOPS at the first letter that takes a value
     * ({@link #VALUE_TAKING_SHORT_OPTIONS}): everything after such a letter is that value, not more
     * flags, so {@code merge -Xours}, {@code commit -mfixes} and {@code tag -mFine} are not mistaken
     * for it. Telling every value-taking letter of every subcommand apart would mean reimplementing
     * git's option arity; this short list covers the ones that can precede a file/strategy flag in
     * the subcommands where those flags exist, and an unexpected cluster is refused rather than
     * silently allowed.
     *
     * @param token the token to inspect
     * @param flag the option letter to look for
     * @param subcommand the git subcommand, which decides which letters take a value
     * @return {@code true} when the token can carry that option
     */
    private static boolean clusterCarries(String token, char flag, String subcommand)
    {
        if (token.length() < 2 || token.charAt(0) != '-' || token.charAt(1) == '-')
        {
            return false;
        }
        String valueTaking = VALUE_TAKING_SHORT_OPTIONS.getOrDefault(subcommand, ""); //$NON-NLS-1$
        for (int i = 1; i < token.length(); i++)
        {
            char c = token.charAt(i);
            if (c == flag)
            {
                return true;
            }
            if (valueTaking.indexOf(c) >= 0)
            {
                return false;
            }
        }
        return false;
    }

    /**
     * Rejects a token that would make git read a file OUTSIDE the repository.
     * <p>
     * Two spellings do that. {@code diff} applies its filesystem-compare form IMPLICITLY when a path
     * lies outside the work tree ({@code --no-index} is refused, but not needed), and several options
     * take a FILE as their value - {@code blame --contents}/{@code -S}, {@code commit -F},
     * {@code ls-files -X}. The blocked-flag list names the ones known today; this guard is the
     * structural backstop, and it works WITHOUT knowing git's option grammar: every token is examined,
     * together with the value attached to it ({@code --opt=value}, {@code -Xvalue}).
     * <p>
     * A token is judged by what it could actually READ - refused only when an EXISTING file or
     * directory outside the work tree sits at that path - so revisions ({@code HEAD~1},
     * {@code main..feature}), messages and search strings pass untouched. Past the pathspec
     * {@code --} every token is a path, so one that leaves the work tree is refused outright.
     * <p>
     * Two limits are deliberate. A value that happens to NAME a real outside file ({@code -m ../x})
     * is refused: telling a value from an operand would mean reimplementing git's per-option arity,
     * and the error says how to proceed. And an existence check is not atomic with git's open, so a
     * path created or re-pointed in between is not covered - a strict guarantee would need OS-level
     * containment, which is out of scope for a tool the operator enables deliberately.
     *
     * @param argv the validated argument vector ({@code argv[0]} is git, {@code argv[1]} the subcommand)
     * @param workTree the repository work tree
     * @return an actionable error message, or {@code null} when every token stays inside
     */
    private static String outsideRepositoryOperand(List<String> argv, File workTree)
    {
        if (argv.size() < 2)
        {
            return null;
        }
        Path root;
        try
        {
            root = workTree.toPath().toRealPath();
        }
        catch (IOException e)
        {
            root = workTree.toPath().toAbsolutePath().normalize();
        }
        boolean afterPathspecSeparator = false;
        for (int i = 2; i < argv.size(); i++)
        {
            String token = argv.get(i);
            if (!afterPathspecSeparator && "--".equals(token)) //$NON-NLS-1$
            {
                afterPathspecSeparator = true;
                continue;
            }
            String candidate = afterPathspecSeparator ? token : attachedValueOf(token);
            if (!escapesRepository(candidate, root, afterPathspecSeparator))
            {
                continue;
            }
            token = candidate;
            return "'" + token + "' points outside the repository '" + root //$NON-NLS-1$ //$NON-NLS-2$
                + "'. git would read that file - 'diff' compares it on disk, and options such as " //$NON-NLS-1$
                + "'blame --contents' or 'commit -F' take it as their value - and this tool governs " //$NON-NLS-1$
                + "only the project. Pass a path inside the project."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The part of a token git would treat as a path: the token itself for an operand, or the value
     * attached to an option ({@code --contents=<file>}, {@code -F<file>}). A bare option carries no
     * path, so it maps to the empty string, which never escapes. For a SHORT option {@code =} is part
     * of the value, not a separator, so only {@code --long=value} is split there.
     *
     * @param token the raw token
     * @return the candidate path text (never {@code null})
     */
    private static String attachedValueOf(String token)
    {
        if (!token.startsWith("-")) //$NON-NLS-1$
        {
            return token;
        }
        if (token.length() > 2 && token.charAt(1) != '-')
        {
            // A SHORT option with its value attached ('-F/etc/passwd'). Checked BEFORE any '=' split:
            // the value may itself contain one ('-FC:\\out\\a=b.txt'), and splitting there would hide
            // the real path. A leading '=' ('-F=/etc/passwd') is part of the separator, not the path.
            // No '=' stripping here: for a SHORT option git treats '=' as part of the value, so
            // '-S=/etc/passwd' opens the repo-relative '=/etc/passwd', not the host path.
            String tail = token.substring(2);
            // Only a tail that LOOKS like a path is treated as one: a bundle ('-sb') or a count
            // ('-10') is not a file operand, and testing it could reject an ordinary command over a
            // same-named symlink inside the repository.
            return looksLikePath(tail) ? tail : ""; //$NON-NLS-1$
        }
        int equals = token.indexOf('=');
        if (equals >= 0)
        {
            // A LONG option's value ('--contents=<file>').
            return token.substring(equals + 1);
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Whether a short option's attached value is shaped like a filesystem path - it carries a
     * separator, a drive letter or a leading dot. A bare word is not: relative to the work tree it
     * cannot name anything outside it.
     *
     * @param value the attached value
     * @return {@code true} when the value is worth testing as a path
     */
    private static boolean looksLikePath(String value)
    {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
            || value.startsWith("."); //$NON-NLS-1$
    }

    /**
     * Whether a candidate path resolves outside {@code root}.
     *
     * @param token the operand to test
     * @param root the canonical repository work tree
     * @param lexicalToo whether a path that leaves the repository is refused even when nothing
     *            exists there yet - true past the pathspec {@code --}, where every token IS a path
     * @return {@code true} when the token is a path that leaves the repository
     */
    private static boolean escapesRepository(String token, Path root, boolean lexicalToo)
    {
        if (token.isEmpty())
        {
            return false;
        }
        char first = token.charAt(0);
        boolean rootRelative = first == '/' || first == '\\';
        try
        {
            Path candidate = Paths.get(token);
            if (rootRelative && !candidate.isAbsolute())
            {
                // Windows: '/etc/passwd' is not absolute for Java, but git resolves it against a root
                // of its own (the MSYS prefix) - a location outside this repository either way, and
                // one this JVM cannot test for existence.
                return true;
            }
            Path resolved = candidate.isAbsolute() ? candidate : root.resolve(candidate);
            if (lexicalToo && !resolved.normalize().startsWith(root))
            {
                // After the pathspec '--' every token IS a path, so a path that leaves the repository
                // is refused even when nothing is there yet - closing the window in which the file
                // could appear between this check and git opening it.
                return true;
            }
            if (!Files.exists(resolved))
            {
                // Nothing to read: git's implicit --no-index would fail on its own, so this is a
                // revision or a search string, not an escape.
                return false;
            }
            // toRealPath, not normalize: an in-repository symlink or junction ('external' -> /etc)
            // is lexically inside the root while its content lives outside it.
            return !resolved.toRealPath().startsWith(root);
        }
        catch (InvalidPathException e)
        {
            // Not a filesystem path at all (a revision such as 'main..feature'): nothing to escape.
            return rootRelative;
        }
        catch (IOException e)
        {
            // The path exists but cannot be canonicalized - refuse rather than guess.
            return true;
        }
    }

    /**
     * Replaces the {@code userinfo@} of every URL in git's own output with {@code ***@}.
     * <p>
     * A repository can already hold a credential-bearing remote - {@code https://<token>@host/repo},
     * or {@code https://host/repo.git?access_token=<token>}, which carries no {@code @} at all -
     * and {@code remote -v} / {@code push} print it verbatim - handing an agent a token that was
     * merely stored on disk. The whole userinfo is redacted, not just a password - and the whole
     * query with it, harmless parameters included, since telling a secret one from the rest would
     * mean keeping a list of every service's parameter names: a bare
     * {@code https://ghp_xxx@host} carries the secret AS the user name. A plain ssh user name
     * ({@code ssh://git@host}) is redacted too - over-redacting a public name is the safe side of
     * that trade, and the host and path stay readable.
     *
     * Scanned by hand rather than with {@link #CREDENTIAL_URL}: a regex is restarted at every
     * position, so output that merely LOOKS like a scheme ("aaa...a:@") costs O(n^2) - measured at
     * 85 s for 100k characters. This walk visits each character a bounded number of times.
     *
     * @param text git's captured output (may be {@code null})
     * @return the output with every URL userinfo replaced
     */
    static String redactCredentialUrls(String text)
    {
        if (text == null || (text.indexOf('@') < 0 && text.indexOf('?') < 0))
        {
            return text;
        }
        StringBuilder redacted = null;
        int copiedUpTo = 0;
        int marker = text.indexOf(SCHEME_SEPARATOR);
        while (marker >= 0)
        {
            if (!hasSchemeBefore(text, marker))
            {
                marker = text.indexOf(SCHEME_SEPARATOR, marker + SCHEME_SEPARATOR.length());
                continue;
            }
            int authorityStart = marker + SCHEME_SEPARATOR.length();
            // Computed ONCE per URL and handed to every scanner below: recomputing it inside each of
            // them would rescan the rest of the output for every URL, which is quadratic on a long
            // one. The loop then jumps straight to this boundary, so each character is visited a
            // bounded number of times.
            int limit = urlLimit(text, authorityStart);
            int userinfoEnd = userinfoEnd(text, authorityStart, limit);
            if (userinfoEnd >= 0)
            {
                if (redacted == null)
                {
                    redacted = new StringBuilder(text.length());
                }
                redacted.append(text, copiedUpTo, authorityStart).append("***@"); //$NON-NLS-1$
                copiedUpTo = userinfoEnd + 1;
            }
            // A credential can also ride in the QUERY ('...repo.git?access_token=<secret>'), where
            // there is no '@' at all. The whole query is replaced: telling a secret parameter from a
            // harmless one would mean keeping a list of every service's parameter names.
            int queryStart = queryStart(text, Math.max(copiedUpTo, authorityStart), limit);
            if (queryStart >= 0)
            {
                if (redacted == null)
                {
                    redacted = new StringBuilder(text.length());
                }
                redacted.append(text, copiedUpTo, queryStart + 1).append("***"); //$NON-NLS-1$
                copiedUpTo = queryEnd(text, queryStart + 1, limit);
            }
            marker = text.indexOf(SCHEME_SEPARATOR, Math.max(copiedUpTo, limit));
        }
        if (redacted == null)
        {
            return text;
        }
        return redacted.append(text, copiedUpTo, text.length()).toString();
    }

    /**
     * ASCII whitespace, exactly what a regex {@code \s} matches without {@code UNICODE_CHARACTER_CLASS}.
     * Deliberately NOT {@link Character#isWhitespace}: a Unicode space inside a URL's userinfo must not
     * end the scan, or {@code https://secret<U+2003>name@host} would keep its secret.
     *
     * @param c the character to test
     * @return {@code true} for space, tab, newline, vertical tab, form feed and carriage return
     */
    private static boolean isAsciiWhitespace(char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /**
     * Whether the characters immediately before {@code marker} form a URL scheme.
     *
     * @param text the output being scanned
     * @param marker the index of {@value #SCHEME_SEPARATOR}
     * @return {@code true} when a scheme run ending at {@code marker} contains a letter
     */
    private static boolean hasSchemeBefore(String text, int marker)
    {
        boolean sawLetter = false;
        for (int i = marker - 1; i >= 0; i--)
        {
            char c = text.charAt(i);
            if (!isSchemeChar(c))
            {
                break;
            }
            sawLetter = sawLetter || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }
        return sawLetter;
    }

    /**
     * Finds the {@code ?} that opens this URL's query, or {@code -1} when it has none. The search
     * stops at whitespace and at the next URL: a query belongs to the URL being scanned.
     *
     * @param text the output being scanned
     * @param from the index to start at (inside the URL)
     * @param limit where this URL stops (see {@link #urlLimit})
     * @return the index of the {@code ?}, or {@code -1}
     */
    private static int queryStart(String text, int from, int limit)
    {
        for (int i = from; i < limit; i++)
        {
            char c = text.charAt(i);
            if (c == '?')
            {
                return i;
            }
            if (isAsciiWhitespace(c))
            {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Where the URL that starts at {@code from} must stop being scanned: at the scheme of the NEXT
     * URL, if one follows without whitespace between them ({@code https://a/x,https://b/y}), else at
     * the end of the text. Without this bound a query search would run into the next URL and swallow
     * it - along with the credential it may carry.
     *
     * @param text the output being scanned
     * @param from the index inside the current URL
     * @return the exclusive upper bound for this URL
     */
    private static int urlLimit(String text, int from)
    {
        for (int marker = text.indexOf(SCHEME_SEPARATOR, from); marker >= 0;
            marker = text.indexOf(SCHEME_SEPARATOR, marker + SCHEME_SEPARATOR.length()))
        {
            int schemeStart = marker;
            while (schemeStart > 0 && isSchemeChar(text.charAt(schemeStart - 1)))
            {
                schemeStart--;
            }
            // A NEW url only starts after something that can separate two of them. A '://' preceded
            // by '=' or '&' lives INSIDE the current URL's query ('?token=secret://tail'), and
            // treating it as a boundary would end the redaction right before the secret.
            if (schemeStart > from && isUrlSeparator(text.charAt(schemeStart - 1)))
            {
                return schemeStart;
            }
        }
        return text.length();
    }

    /** Whether a character can separate two URLs in git's output. */
    private static boolean isUrlSeparator(char c)
    {
        return isAsciiWhitespace(c) || c == ',' || c == ';' || c == '(' || c == ')' || c == '<'
            || c == '>' || c == '"' || c == '\'' || c == '[' || c == ']' || c == '|';
    }

    /** Whether a character may appear in a URL scheme. */
    private static boolean isSchemeChar(char c)
    {
        return (Character.isLetterOrDigit(c) && c < 128) || c == '+' || c == '.' || c == '-';
    }

    /**
     * Finds where a URL's query ends - at whitespace, at the start of the NEXT URL, or at the end of
     * the text.
     *
     * @param text the output being scanned
     * @param from the index just after the {@code ?}
     * @param limit where this URL stops (see {@link #urlLimit})
     * @return the index of the first character that is no longer part of the query
     */
    private static int queryEnd(String text, int from, int limit)
    {
        for (int i = from; i < limit; i++)
        {
            if (isAsciiWhitespace(text.charAt(i)))
            {
                return i;
            }
        }
        return limit;
    }

    /**
     * Finds the {@code @} that closes a URL's userinfo.
     *
     * @param text the output being scanned
     * @param from the index just after {@value #SCHEME_SEPARATOR}
     * @param limit where this URL stops (see {@link #urlLimit})
     * @param limit where this URL stops (see {@link #urlLimit})
     * @param limit where this URL stops (see {@link #urlLimit})
     * @return the index of the LAST {@code @} before the authority ends, or {@code -1} when this URL
     *         carries no userinfo
     */
    private static int userinfoEnd(String text, int from, int limit)
    {
        int lastAt = -1;
        for (int i = from; i < limit; i++)
        {
            char c = text.charAt(i);
            if (c == '@')
            {
                // Keep going: git accepts an email-style user name, so
                // 'https://user@example.com:token@host/r.git' has its REAL separator at the last '@'.
                // Stopping at the first would leave ':token' in the output.
                lastAt = i;
                continue;
            }
            if (c == '/' || c == '?' || c == '#' || isAsciiWhitespace(c))
            {
                return lastAt;
            }
        }
        return lastAt;
    }

    /** @return a thread-safe snapshot of the drained output so far. */
    private static String snapshot(StringBuilder out, boolean[] truncated)
    {
        synchronized (out)
        {
            // The flag is read under the SAME lock the drain thread appends (and sets it) with: a
            // plain read could still see 'false' for a drain that is alive past join(), and the
            // half-written URL that flag guards would then be returned unredacted.
            String text = out.toString();
            return redactCredentialUrls(truncated[0] ? dropTruncatedUrlTail(text) : text);
        }
    }

    /**
     * Cuts a URL that the output cap split in half.
     * <p>
     * The cap can fall between a credential and the {@code @} that ends it, leaving
     * {@code https://ghp_secret} with no separator behind - which the redaction scan cannot recognise
     * as userinfo, so the secret would be returned verbatim. When the output WAS truncated, any
     * trailing {@code scheme://...} whose authority never ended is therefore dropped before redaction
     * runs. An {@code @} does NOT count as an end here: a split
     * {@code https://user@example.com:ghp_secret} carries one and still hides a secret behind it.
     *
     * @param text the captured output
     * @return the output without a dangling, possibly half-written URL
     */
    private static String dropTruncatedUrlTail(String text)
    {
        int marker = text.lastIndexOf(SCHEME_SEPARATOR);
        if (marker < 0)
        {
            return text;
        }
        for (int i = marker + SCHEME_SEPARATOR.length(); i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '/' || c == '?' || c == '#' || isAsciiWhitespace(c))
            {
                // The authority ended, so the URL is complete whatever follows.
                return text;
            }
        }
        return text.substring(0, marker) + "[... url truncated]"; //$NON-NLS-1$
    }

    /**
     * Waits for git to exit within {@link #TIMEOUT_SECONDS}, collecting its descendants as it runs.
     * <p>
     * The wait is POLLED rather than a single blocking one because a descendant can only be observed
     * while the parent is alive: once git exits, its children are re-parented and
     * {@link Process#descendants()} no longer reports them - and a hook, filter, credential helper or
     * ssh wrapper may background exactly such a child, which would then hold the output pipe forever.
     *
     * @param process the git process
     * @param descendants the collected handles, appended to as they appear
     * @return {@code true} when git exited within the timeout
     */
    private static boolean awaitExit(Process process, List<ProcessHandle> descendants)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        for (long remaining = deadline - System.nanoTime(); remaining > 0;
            remaining = deadline - System.nanoTime())
        {
            captureDescendants(process, descendants);
            // The last slice is clamped to what is LEFT of the budget, so polling can never stretch
            // the timeout past TIMEOUT_SECONDS.
            long slice = Math.min(DESCENDANT_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            if (process.waitFor(slice, TimeUnit.MILLISECONDS))
            {
                return true;
            }
        }
        return !process.isAlive();
    }

    /**
     * Adds the process' current descendants to {@code sink}, ignoring the ones already there.
     * Best-effort: on a platform (or under a security manager) where the JVM refuses to enumerate
     * them, the command's own result must not be lost over it.
     *
     * @param process the git process
     * @param sink the collected handles
     */
    private static void captureDescendants(Process process, List<ProcessHandle> sink)
    {
        try
        {
            process.descendants().forEach(handle -> {
                if (!sink.contains(handle))
                {
                    sink.add(handle);
                }
            });
        }
        catch (RuntimeException e) // NOSONAR cleanup is best-effort by contract
        {
            Activator.logError("git: listing child processes failed", e); //$NON-NLS-1$
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
        // GIT_TERMINAL_PROMPT / core.askPass cover git's own prompts, NOT a credential HELPER: a
        // GUI-capable one (Git Credential Manager) pops its own window when no credential is cached.
        // credential.interactive=false is the documented knob that keeps such a helper silent, so a
        // missing credential fails fast instead of blocking an unattended call on a dialog. Helpers
        // that do not know the key ignore it, so a cached credential still works as before.
        command.add("-c"); //$NON-NLS-1$
        command.add("credential.interactive=false"); //$NON-NLS-1$
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

    private static void hardenEnv(Map<String, String> env, File workTree)
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
        // OpenSSH >= 8.4: never fall back to a GUI askpass, whatever ssh command ends up running.
        env.put("SSH_ASKPASS_REQUIRE", "never"); //$NON-NLS-1$ //$NON-NLS-2$
        // GIT_TERMINAL_PROMPT=0 silences git's OWN prompt but not the ssh child: OpenSSH defaults to
        // BatchMode=no, so a key with a passphrase (or an unknown host) would block a push/fetch on an
        // interactive prompt. Force ssh non-interactive too - unattended safety, the project rule.
        // BUT only when the user has NOT configured an ssh command: GIT_SSH_COMMAND overrides
        // core.sshCommand, so setting it unconditionally would discard the custom identity / port /
        // wrapper that makes the remote work in the user's own terminal. When they configured one we
        // leave it alone and rely on the askpass settings above plus the call timeout.
        if (env.get("GIT_SSH_COMMAND") == null && configuredSshCommand(workTree) == null) //$NON-NLS-1$
        {
            env.put("GIT_SSH_COMMAND", "ssh -oBatchMode=yes"); //$NON-NLS-1$ //$NON-NLS-2$
            // An inherited GIT_SSH_VARIANT (e.g. 'plink') would make git format the arguments for
            // another client than the OpenSSH one forced just above.
            env.put("GIT_SSH_VARIANT", "ssh"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        scrubInheritedGitEnv(env);
    }

    /**
     * Removes the inherited variables that would redirect git to another repository, feed it inline
     * configuration, or open an interactive credential dialog.
     * <p>
     * Applied to the {@link #configuredSshCommand} probe as well as the real call: the probe decides
     * whether an ssh command is configured, so it has to read the configuration the real call will
     * actually use - a {@code GIT_CONFIG_*} the real call drops must not steer that answer.
     *
     * @param env the process environment to scrub in place
     */
    private static void scrubInheritedGitEnv(Map<String, String> env)
    {
        for (String redirect : new String[]{
            "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_NAMESPACE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_EXEC_PATH", "GIT_SHALLOW_FILE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "GIT_CONFIG", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "GIT_EXTERNAL_DIFF", "GIT_PROXY_COMMAND", //$NON-NLS-1$ //$NON-NLS-2$
            // An askpass helper would pop a GUI credential dialog in a desktop EDT session (git tries
            // GIT_ASKPASS, then core.askPass, then SSH_ASKPASS) - drop the env ones here; the config
            // one is neutralized per-call with '-c core.askPass=' (see buildCommand).
            "GIT_ASKPASS", "SSH_ASKPASS"}) // interactive credential dialogs //$NON-NLS-1$ //$NON-NLS-2$
        {
            env.remove(redirect);
        }
        // The whole GIT_TRACE* family, not just the legacy variable: a Trace2 target
        // (GIT_TRACE2 / GIT_TRACE2_EVENT / GIT_TRACE2_PERF) may be an absolute path or a socket, so
        // an inherited one would write every command's argv outside the repository.
        // Case-insensitively: Windows environment lookup ignores case, so an inherited
        // 'Git_Trace2_Event' would survive an exact-prefix test and still write outside the repo.
        env.keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_TRACE")); //$NON-NLS-1$
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
