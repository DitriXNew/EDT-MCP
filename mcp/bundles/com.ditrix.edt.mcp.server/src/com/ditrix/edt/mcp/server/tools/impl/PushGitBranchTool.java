/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.egit.core.EclipseGitProgressTransformer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.git.GitRemoteSupport;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Pushes a project's git branch (or an explicit refspec) to a remote via a headless
 * JGit {@code PushCommand} - the non-UI sibling of EDT's own "Push" command. Reaches
 * an external git remote (network), so it runs on the shared bounded background
 * {@link GitRemoteSupport} Job (up to {@link GitRemoteSupport#REMOTE_TIMEOUT_SECONDS}
 * seconds), never on the UI thread, and its result carries {@code openWorldHint=true}.
 * <p>
 * <b>The "no autonomous push" guarantee (stated once here):</b> both the target
 * {@code remote} AND the {@code refspec} are REQUIRED, with NO defaulting whatsoever,
 * and {@code force} is opt-in ({@code false} by default). There is therefore no path
 * on which this tool pushes to an inferred/tracking remote or force-overwrites a
 * remote branch without the caller having spelled out exactly that intent. This is
 * enforced purely in the tool contract (required params fail closed on every path) -
 * the tool is deliberately NOT registered in
 * {@link com.ditrix.edt.mcp.server.utils.DestructiveConsentGate}: that gate returns
 * ALLOW on the headless/unattended path, so it would not actually block anything here.
 * <p>
 * <b>Authentication (Option A - no secret storage, no dialog):</b> for an SSH remote
 * authentication is transparent - EGit's process-global {@code SshSessionFactory}
 * (ssh-agent + {@code ~/.ssh}) handles it with zero auth code here. For an HTTPS remote
 * the push installs an EXPLICIT non-interactive
 * {@link CredentialsProvider} from
 * {@link GitRemoteSupport#credentialsProvider(String, String)}: a
 * {@code UsernamePasswordCredentialsProvider} built from the optional {@code username}
 * /{@code token} params when supplied, else a fail-fast provider that answers no
 * credential item. Either way NO EGit login/passphrase modal can open - a missing or
 * invalid HTTPS credential surfaces as an actionable transport error, never a hung
 * dialog.
 * <p>
 * <b>Result correctness:</b> a push that JGit reports back as rejected (any
 * {@link RemoteRefUpdate} whose status is not {@code OK}/{@code UP_TO_DATE} - most
 * notably {@link RemoteRefUpdate.Status#REJECTED_NONFASTFORWARD}) is mapped to an
 * actionable error, never swallowed as success. Only when every ref update succeeded is
 * the result {@code success:true}. A push changes no local working-tree file, so no
 * workspace refresh is performed.
 */
public class PushGitBranchTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "push_git_branch"; //$NON-NLS-1$

    /** Input param: the target remote (required) - a configured remote name or a URL. */
    private static final String KEY_REMOTE = "remote"; //$NON-NLS-1$

    /** Input param: the refspec (required) - a short branch name or an explicit {@code src:dst}. */
    private static final String KEY_REFSPEC = "refspec"; //$NON-NLS-1$

    /** Input param: opt-in force push (default false). */
    private static final String KEY_FORCE = "force"; //$NON-NLS-1$

    /** Input param: optional HTTPS username. */
    private static final String KEY_USERNAME = "username"; //$NON-NLS-1$

    /** Input param: optional HTTPS password/token. */
    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    /** Output key: the resolved refspec actually pushed. */
    private static final String KEY_RESOLVED_REFSPEC = "resolvedRefspec"; //$NON-NLS-1$

    /** Output key: whether the push was forced. */
    private static final String KEY_FORCED = "forced"; //$NON-NLS-1$

    /** Output key: whether every ref update succeeded. */
    private static final String KEY_PUSHED = "pushed"; //$NON-NLS-1$

    /** Output key: per-ref update records ({remoteName, status, message?}). */
    private static final String KEY_UPDATES = "updates"; //$NON-NLS-1$

    /** Refspec record key: the remote ref name. */
    private static final String KEY_REMOTE_NAME = "remoteName"; //$NON-NLS-1$

    /** Refspec record key: the JGit {@link RemoteRefUpdate.Status} name. */
    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    private static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    private static final String REFS_PREFIX = "refs/"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Push a project's git branch (or an explicit refspec) to a remote (headless JGit push). " //$NON-NLS-1$
            + "remote AND refspec are BOTH required with no defaulting (the no-autonomous-push guard); " //$NON-NLS-1$
            + "force is opt-in (default false). refspec accepts a short branch name (pushed to the " //$NON-NLS-1$
            + "same-named remote branch) or an explicit 'src:dst'. SSH auth is transparent (ssh-agent/" //$NON-NLS-1$
            + "~/.ssh); for HTTPS pass username/token or the push fails fast with an actionable error " //$NON-NLS-1$
            + "(never a login dialog). A non-fast-forward rejection is reported as an error, not success. " //$NON-NLS-1$
            + "Runs in a background Job (up to 120 s). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('push_git_branch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to push from (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_REMOTE,
                "Target remote (required): a configured remote name (e.g. 'origin') or a URL. No " //$NON-NLS-1$
                + "defaulting - you must name it explicitly (the no-autonomous-push guard).", //$NON-NLS-1$
                true)
            .stringProperty(KEY_REFSPEC,
                "Refspec to push (required): a short branch name (e.g. 'feature/x', pushed to the " //$NON-NLS-1$
                + "same-named remote branch) or an explicit 'src:dst' (e.g. " //$NON-NLS-1$
                + "'refs/heads/feature/x:refs/heads/feature/x'). No defaulting - you must name it " //$NON-NLS-1$
                + "explicitly.", //$NON-NLS-1$
                true)
            .booleanProperty(KEY_FORCE,
                "Force-push, overwriting the remote branch even on a non-fast-forward. Opt-in; " //$NON-NLS-1$
                + "default false.") //$NON-NLS-1$
            .stringProperty(KEY_USERNAME,
                "Optional git username for an HTTPS remote. Omit for an SSH remote (ssh-agent/~/.ssh " //$NON-NLS-1$
                + "authenticate transparently).") //$NON-NLS-1$
            .stringProperty(KEY_TOKEN,
                "Optional git password or personal-access-token for an HTTPS remote. Without it an " //$NON-NLS-1$
                + "HTTPS remote that needs credentials fails fast (never a login dialog).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the push fully succeeded (every ref update OK).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REMOTE, "The remote pushed to.") //$NON-NLS-1$
            .stringProperty(KEY_RESOLVED_REFSPEC, "The refspec actually pushed (a short branch name is " //$NON-NLS-1$
                + "expanded to 'refs/heads/<b>:refs/heads/<b>').") //$NON-NLS-1$
            .booleanProperty(KEY_FORCED, "Whether the push was forced.") //$NON-NLS-1$
            .booleanProperty(KEY_PUSHED, "Always true on a non-error result (every ref update succeeded).") //$NON-NLS-1$
            .objectArrayProperty(KEY_UPDATES,
                "Per-ref update records: {remoteName, status, message?}. status is the JGit " //$NON-NLS-1$
                + "RemoteRefUpdate.Status (OK / UP_TO_DATE on success).") //$NON-NLS-1$
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
        // A push reaches an external git remote (network), so openWorldHint=true. The other hints
        // match the central classifier's "non-destructive write" verdict: not read-only, and NOT
        // destructive (see the class javadoc - the no-autonomous-push guard is the required explicit
        // remote+refspec params + opt-in force, not the destructive-consent gate).
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, Boolean.TRUE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_REMOTE, KEY_REFSPEC);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String remote = JsonUtils.extractStringArgument(params, KEY_REMOTE);
        String refspec = JsonUtils.extractStringArgument(params, KEY_REFSPEC);
        boolean force = JsonUtils.extractBooleanArgument(params, KEY_FORCE, false);
        String username = JsonUtils.extractStringArgument(params, KEY_USERNAME);
        String token = JsonUtils.extractStringArgument(params, KEY_TOKEN);

        String refspecError = validateRefspec(refspec);
        if (refspecError != null)
        {
            return refspecError;
        }

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        // Do NOT close the owned repository from a finally here: on a bounded-Job timeout the push
        // may still be in flight, so closing it is delegated to GitRemoteSupport, which fires it once
        // the Job's thread genuinely ends (see push()). The catch only covers a failure BEFORE the Job
        // is scheduled (the Job was never registered, so nothing else will close the repository).
        try
        {
            return push(resolution, remote, refspec, force, username, token);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            resolution.closeIfOwned();
            Activator.logError("push_git_branch: failed for project '" + projectName //$NON-NLS-1$
                + "', remote '" + remote + "', refspec '" + refspec + "'", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return ToolResult.error("Failed to push: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String push(GitRepositoryResolver.Resolution resolution, String remote, String refspec, boolean force,
        String username, String token)
    {
        Repository repo = resolution.repository();
        String resolvedRefspec = buildRefSpec(refspec);
        CredentialsProvider credentials = GitRemoteSupport.credentialsProvider(username, token);

        // Capture the push result INSIDE the op (GitRemoteSupport.RemoteOp returns void; each caller
        // owns its own result holder). No workspace refresh: a push changes no local working-tree file.
        // The owned repository is closed once the Job thread ends (resolution::closeIfOwned), so a
        // timed-out push can never have the object database closed out from under it.
        AtomicReference<List<PushResult>> resultsHolder = new AtomicReference<>();
        GitRemoteSupport.RemoteOutcome outcome = GitRemoteSupport.run(
            "Push git '" + resolvedRefspec + "' to " + remote, //$NON-NLS-1$ //$NON-NLS-2$
            null,
            monitor -> resultsHolder.set(doPush(repo, remote, resolvedRefspec, force, credentials, monitor)),
            resolution::closeIfOwned);

        if (outcome.timedOut())
        {
            return ToolResult.error("Push to '" + remote + "' timed out after " //$NON-NLS-1$ //$NON-NLS-2$
                + GitRemoteSupport.REMOTE_TIMEOUT_SECONDS + " seconds. Check network connectivity and the " //$NON-NLS-1$
                + "remote before retrying.").toJson(); //$NON-NLS-1$
        }
        if (outcome.interrupted())
        {
            return ToolResult.error("Push to '" + remote + "' was interrupted.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (outcome.jobError() != null)
        {
            return mapJobError(outcome.jobError(), remote, username, token);
        }

        return mapPushResults(resultsHolder.get(), remote, resolvedRefspec, force);
    }

    /**
     * Performs the JGit push and materializes JGit's {@link PushResult} iterable into a list.
     * Runs inside the bounded background Job. The {@link Git} wrapper is deliberately NOT closed:
     * closing it would close the underlying {@link Repository}, which on the EGit-mapped resolution
     * path is EGit's own borrowed instance (mirrors {@code SwitchGitBranchTool}'s {@code Git.wrap}
     * usage). An explicit non-interactive {@code credentials} provider overrides EGit's process-global
     * default so no login dialog can open. The Job's {@code monitor} is wired through so the push
     * observes Job cancellation (e.g. on the {@link GitRemoteSupport} timeout).
     */
    private static List<PushResult> doPush(Repository repo, String remote, String refspec, boolean force,
        CredentialsProvider credentials, IProgressMonitor monitor) throws GitAPIException
    {
        Iterable<PushResult> results = Git.wrap(repo).push()
            .setRemote(remote)
            .setRefSpecs(new RefSpec(refspec))
            .setForce(force)
            .setCredentialsProvider(credentials)
            // Bound the transport itself, not just the Job join: with JGit's default (0 = infinite) a
            // stalled socket would keep the background Job alive forever after run() reports a timeout.
            .setTimeout((int)GitRemoteSupport.REMOTE_TIMEOUT_SECONDS)
            .setProgressMonitor(new EclipseGitProgressTransformer(monitor))
            .call();
        List<PushResult> list = new ArrayList<>();
        results.forEach(list::add);
        return list;
    }

    /**
     * Maps an exception thrown by the push op into an actionable error. An
     * {@link InvalidRemoteException} means the named remote could not be resolved; a
     * {@link TransportException} typically means an authentication or connectivity failure - and,
     * critically, it is what the non-interactive credentials provider produces INSTEAD of opening a
     * login dialog, so the message explicitly steers the caller to supply username/token for HTTPS
     * (SSH uses ssh-agent). Any other {@link GitAPIException} surfaces generically.
     */
    private String mapJobError(Exception jobError, String remote, String username, String token)
    {
        if (jobError instanceof InvalidRemoteException)
        {
            return ToolResult.error("Remote not found: '" + remote + "'. Name a configured remote (e.g. " //$NON-NLS-1$ //$NON-NLS-2$
                + "'origin') or a valid URL.").toJson(); //$NON-NLS-1$
        }
        if (jobError instanceof TransportException)
        {
            boolean hadCredentials = (username != null && !username.isBlank())
                || (token != null && !token.isBlank());
            String hint = hadCredentials
                ? " The supplied username/token were rejected, or the SSH key/agent is not authorized " //$NON-NLS-1$
                    + "for this remote."
                : " No credentials were supplied: for an HTTPS remote pass username and token; for an SSH " //$NON-NLS-1$
                    + "remote ensure ssh-agent holds an authorized key. No login dialog was opened.";
            return ToolResult.error("Push to '" + remote + "' failed to connect/authenticate: " //$NON-NLS-1$ //$NON-NLS-2$
                + jobError.getMessage() + "." + hint).toJson(); //$NON-NLS-1$
        }
        Activator.logError("push_git_branch: push failed for remote '" + remote + "'", jobError); //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("Push to '" + remote + "' failed: " + jobError.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Maps JGit's {@link PushResult}s to the tool result. Every {@link RemoteRefUpdate} across every
     * {@link PushResult} is inspected: any status that is not {@code OK}/{@code UP_TO_DATE} (a
     * rejection - most notably {@link RemoteRefUpdate.Status#REJECTED_NONFASTFORWARD}) makes the whole
     * push a FAILURE with an actionable error, never a swallowed success.
     */
    private String mapPushResults(List<PushResult> results, String remote, String refspec, boolean force)
    {
        if (results == null || results.isEmpty())
        {
            return ToolResult.error("Push to '" + remote + "' returned no result. The remote may be " //$NON-NLS-1$ //$NON-NLS-2$
                + "unreachable, or the refspec '" + refspec + "' matched nothing to push.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<Map<String, Object>> updates = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        boolean nonFastForward = false;

        for (PushResult pushResult : results)
        {
            Collection<RemoteRefUpdate> refUpdates = pushResult.getRemoteUpdates();
            if (refUpdates == null)
            {
                continue;
            }
            for (RemoteRefUpdate update : refUpdates)
            {
                RemoteRefUpdate.Status status = update.getStatus();
                Map<String, Object> record = new LinkedHashMap<>();
                record.put(KEY_REMOTE_NAME, update.getRemoteName());
                record.put(KEY_STATUS, status == null ? null : status.name());
                if (update.getMessage() != null && !update.getMessage().isEmpty())
                {
                    record.put(McpKeys.MESSAGE, update.getMessage());
                }
                updates.add(record);

                if (!isSuccessStatus(status))
                {
                    rejections.add(describeUpdate(status, update.getRemoteName(), update.getMessage()));
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD)
                    {
                        nonFastForward = true;
                    }
                }
            }
        }

        if (!rejections.isEmpty())
        {
            String hint = nonFastForward
                ? " Integrate the remote changes first (pull_git_branch), then push again; or set " //$NON-NLS-1$
                    + "force=true to intentionally overwrite the remote branch."
                : " Resolve the reported condition on the remote and retry."; //$NON-NLS-1$
            return ToolResult.error("Push to '" + remote + "' was rejected: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("; ", rejections) + "." + hint).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return ToolResult.success()
            .put(KEY_REMOTE, remote)
            .put(KEY_RESOLVED_REFSPEC, refspec)
            .put(KEY_FORCED, force)
            .put(KEY_PUSHED, true)
            .put(KEY_UPDATES, updates)
            .toJson();
    }

    /**
     * Rejects refspec forms that would defeat the tool's explicit-intent safety contract, returning
     * an actionable {@link ToolResult#error error} JSON (or {@code null} when the refspec is safe):
     * <ul>
     * <li>a <b>wildcard</b> ({@code *}): a wildcard that matches nothing resolves to zero updates, and
     * JGit's {@code Transport.push} then falls back to the remote's configured {@code push} refspec -
     * silently pushing something the caller never asked for. The tool only pushes an EXPLICIT ref;</li>
     * <li>a leading <b>{@code +}</b> force-marker: JGit encodes it as {@code forceUpdate} on the
     * {@link RefSpec} and {@code PushCommand.setForce(false)} does NOT clear it, so a {@code +} would
     * force-overwrite remote history while {@code force} is false and the result reports
     * {@code forced=false}. A force push must be requested explicitly via {@code force=true}.</li>
     * </ul>
     * Package-visible for direct unit testing.
     *
     * @param refspec the user-supplied refspec; non-blank (already required)
     * @return an error JSON to return from {@code execute}, or {@code null} when the refspec is accepted
     */
    static String validateRefspec(String refspec)
    {
        String value = refspec == null ? "" : refspec.trim(); //$NON-NLS-1$
        // A raw leading '+' is a force marker before buildRefSpec can bury it inside an expanded ref.
        if (value.startsWith("+")) //$NON-NLS-1$
        {
            return forceRefspecError();
        }
        // Validate against the EFFECTIVE (expanded) refspec, parsed by JGit itself, so semantic
        // wildcard forms are caught structurally - notably the matching form ':' (empty src+dst, which
        // has no literal '*' yet pushes every matching branch) as well as any 'refs/*' wildcard.
        RefSpec parsed;
        try
        {
            parsed = new RefSpec(buildRefSpec(value));
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error("Invalid refspec '" + refspec + "': " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Give a branch name (e.g. 'main') or an explicit 'src:dst' refspec.").toJson(); //$NON-NLS-1$
        }
        if (parsed.isForceUpdate())
        {
            return forceRefspecError();
        }
        if (parsed.isWildcard() || isBlankRef(parsed.getSource()) || isBlankRef(parsed.getDestination()))
        {
            return ToolResult.error("push_git_branch requires an explicit single ref: give a branch name " //$NON-NLS-1$
                + "or a concrete 'src:dst' refspec. A wildcard ('*') or the matching form (':') could push " //$NON-NLS-1$
                + "refs the caller never named, or silently fall back to the remote's configured push " //$NON-NLS-1$
                + "refspec.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /** The actionable error steering a caller off a '+' force-marker to the explicit force parameter. */
    private static String forceRefspecError()
    {
        return ToolResult.error("A '+' force-marker in the refspec is not accepted: request a force push " //$NON-NLS-1$
            + "explicitly with " + KEY_FORCE + "=true (which is reported back as 'forced'), and pass the " //$NON-NLS-1$ //$NON-NLS-2$
            + "refspec without the '+'.").toJson(); //$NON-NLS-1$
    }

    /** @return {@code true} when a parsed refspec side is null or blank (the matching ':' form). */
    private static boolean isBlankRef(String side)
    {
        return side == null || side.isBlank();
    }

    /**
     * Expands a user-supplied refspec to a concrete {@code src:dst} refspec.
     * <ul>
     * <li>an explicit {@code src:dst} (containing a colon) is passed through verbatim;</li>
     * <li>a full ref ({@code refs/...}) with no colon is mirrored to the same name on the remote;</li>
     * <li>a short branch name is expanded to {@code refs/heads/<b>:refs/heads/<b>}.</li>
     * </ul>
     * Force is applied separately via {@code PushCommand.setForce}, never by mutating the refspec here
     * (a {@code +} force-marker is rejected up front by {@link #validateRefspec}).
     * Package-visible for direct unit testing.
     *
     * @param refspec the user-supplied refspec (branch name or explicit {@code src:dst}); non-blank
     * @return the concrete refspec to push
     */
    static String buildRefSpec(String refspec)
    {
        String value = refspec.trim();
        if (value.indexOf(':') >= 0)
        {
            return value;
        }
        if (value.startsWith(REFS_PREFIX))
        {
            return value + ":" + value; //$NON-NLS-1$
        }
        return REFS_HEADS + value + ":" + REFS_HEADS + value; //$NON-NLS-1$
    }

    /**
     * Whether a {@link RemoteRefUpdate.Status} is a success: only {@code OK} (the ref moved) and
     * {@code UP_TO_DATE} (nothing to do) count. Every other status - including a rejection, a
     * not-attempted, or an unreported update - is a failure the tool must surface, never swallow.
     * Package-visible for direct unit testing.
     *
     * @param status the JGit push status (may be {@code null})
     * @return {@code true} only for {@code OK}/{@code UP_TO_DATE}
     */
    static boolean isSuccessStatus(RemoteRefUpdate.Status status)
    {
        return status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE;
    }

    /**
     * Renders a single non-successful ref update into an actionable phrase (the bad ref name, a plain
     * explanation of the status, and any remote-supplied message). Package-visible for direct unit
     * testing of the rejected-push mapping.
     *
     * @param status the (non-success) status
     * @param remoteName the remote ref name (may be {@code null})
     * @param message the remote-supplied message (may be {@code null})
     * @return a human-readable description of the failed update
     */
    static String describeUpdate(RemoteRefUpdate.Status status, String remoteName, String message)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(remoteName == null ? "(unknown ref)" : remoteName).append("' "); //$NON-NLS-1$ //$NON-NLS-2$
        if (status == null)
        {
            sb.append("has no reported status"); //$NON-NLS-1$
        }
        else
        {
            switch (status)
            {
                case REJECTED_NONFASTFORWARD:
                    sb.append("rejected (non-fast-forward)"); //$NON-NLS-1$
                    break;
                case REJECTED_NODELETE:
                    sb.append("rejected (the remote refused to delete the ref)"); //$NON-NLS-1$
                    break;
                case REJECTED_REMOTE_CHANGED:
                    sb.append("rejected (the remote ref changed from its expected value)"); //$NON-NLS-1$
                    break;
                case REJECTED_OTHER_REASON:
                    sb.append("rejected"); //$NON-NLS-1$
                    break;
                case NON_EXISTING:
                    sb.append("rejected (the remote ref does not exist and could not be created)"); //$NON-NLS-1$
                    break;
                case AWAITING_REPORT:
                    sb.append("did not complete (no report received from the remote)"); //$NON-NLS-1$
                    break;
                case NOT_ATTEMPTED:
                    sb.append("was not attempted"); //$NON-NLS-1$
                    break;
                default:
                    sb.append("status ").append(status.name()); //$NON-NLS-1$
            }
        }
        if (message != null && !message.isEmpty())
        {
            sb.append(" - ").append(message); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
