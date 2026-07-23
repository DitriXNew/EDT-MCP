/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.egit.core.EclipseGitProgressTransformer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;

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
 * Fetches a branch from a remote and integrates it into the project's currently
 * checked-out branch (merge, or {@code rebase} when requested) - the non-UI
 * sibling of EDT's own "Pull" command, and the read-side half of the git
 * dev-loop (its push counterpart is {@code push_git_branch}).
 * <p>
 * <b>Both the remote and the branch are REQUIRED and never defaulted.</b> A pull
 * mutates the working tree; requiring the caller to name exactly what is being
 * integrated (rather than silently inferring an upstream) keeps the operation
 * intentional on every code path, unattended included.
 * <p>
 * <b>Authentication is non-interactive (never opens a dialog).</b> SSH remotes
 * authenticate transparently through EGit's process-global SSH session factory
 * (ssh-agent + {@code ~/.ssh}); HTTPS remotes use an EXPLICIT credentials
 * provider built by {@link GitRemoteSupport#credentialsProvider(String, String)}
 * from the optional {@code username}/{@code token} params - and when those are
 * absent, a fail-fast provider that returns an actionable error instead of
 * blocking on a modal login prompt. No secret is stored.
 * <p>
 * The fetch+integrate runs on {@link GitRemoteSupport}'s bounded (120 s)
 * background {@link org.eclipse.core.runtime.jobs.Job Job}, never on the UI
 * thread, and the SAME Job refreshes the project
 * ({@link IResource#DEPTH_INFINITE}) afterwards so the EDT model reflects the
 * updated working tree - including when the merge/rebase left conflict markers.
 * <p>
 * <b>A non-clean pull is a mapped error, never a swallowed success.</b> When
 * {@link PullResult#isSuccessful()} is {@code false} (a merge that produced
 * conflicts, or a rebase that stopped on them) the result is a
 * {@link ToolResult#error(String) ToolResult.error} that echoes a bounded list
 * of the conflicting paths and tells the caller how to recover - it never
 * reports {@code success:true}. This tool reaches an external world
 * ({@code openWorldHint=true}) but never opens a 1C infobase connection.
 */
public class PullGitBranchTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "pull_git_branch"; //$NON-NLS-1$

    /** Input param: the remote to pull from (required, never defaulted). */
    private static final String KEY_REMOTE = "remote"; //$NON-NLS-1$

    /** Input param: the remote branch name to fetch and integrate (required, never defaulted). */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Input param: integrate by rebase instead of merge. Default false. */
    private static final String KEY_REBASE = "rebase"; //$NON-NLS-1$

    /** Input param: optional HTTPS username (paired with {@link #KEY_TOKEN}). */
    private static final String KEY_USERNAME = "username"; //$NON-NLS-1$

    /** Input param: optional HTTPS token/password (paired with {@link #KEY_USERNAME}). */
    private static final String KEY_TOKEN = "token"; //$NON-NLS-1$

    /** Output key: the remote name/URI actually fetched from. */
    private static final String KEY_FETCHED_FROM = "fetchedFrom"; //$NON-NLS-1$

    /** Output key: the merge status (merge pull) or rebase status (rebase pull). */
    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    /** Cap on how many conflicting paths are echoed in an error message. */
    private static final int MAX_LISTED_PATHS = 20;

    /** Common prefix of the pull error messages. */
    private static final String PULL_FROM = "Pull from '"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Fetch a branch from a remote and integrate it into the project's current branch " //$NON-NLS-1$
            + "(merge, or rebase when requested) - the non-UI 'Pull'. remote and branch are BOTH " //$NON-NLS-1$
            + "required and never defaulted. SSH auth is transparent (ssh-agent); HTTPS uses the " //$NON-NLS-1$
            + "optional username/token, and never opens a login dialog. A merge/rebase conflict is " //$NON-NLS-1$
            + "returned as an actionable error (with the conflicting paths), not a false success. " //$NON-NLS-1$
            + "Runs in a background Job (up to 120 s) and refreshes the project. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('pull_git_branch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to pull into (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_REMOTE,
                "Remote to pull from (required): a configured remote name (e.g. 'origin') or a URL. " //$NON-NLS-1$
                + "Never defaulted.", //$NON-NLS-1$
                true)
            .stringProperty(KEY_BRANCH,
                "Remote branch name to fetch and integrate (required, e.g. 'main'). Never defaulted.", //$NON-NLS-1$
                true)
            .booleanProperty(KEY_REBASE,
                "Integrate by rebasing the current branch onto the fetched commits instead of " //$NON-NLS-1$
                + "merging. Default false (merge).") //$NON-NLS-1$
            .stringProperty(KEY_USERNAME,
                "Optional HTTPS username. Only used for an HTTPS remote; SSH remotes authenticate " //$NON-NLS-1$
                + "via ssh-agent and ignore it. Omit for SSH.") //$NON-NLS-1$
            .stringProperty(KEY_TOKEN,
                "Optional HTTPS token or password paired with username. When both are omitted for an " //$NON-NLS-1$
                + "HTTPS remote, the pull fails fast with an actionable error instead of prompting.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the pull integrated cleanly", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REMOTE, "The remote that was pulled from (echoed input).") //$NON-NLS-1$
            .stringProperty(KEY_BRANCH, "The remote branch that was integrated (echoed input).") //$NON-NLS-1$
            .booleanProperty(KEY_REBASE, "Whether the integration used rebase (echoed input).") //$NON-NLS-1$
            .stringProperty(KEY_FETCHED_FROM, "The remote name/URI actually fetched from.") //$NON-NLS-1$
            .stringProperty(KEY_STATUS,
                "The merge status (merge pull, e.g. FAST_FORWARD/ALREADY_UP_TO_DATE/MERGED) or the " //$NON-NLS-1$
                + "rebase status (rebase pull, e.g. UP_TO_DATE/FAST_FORWARD/OK).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present only when the post-pull workspace refresh failed: a short warning noting the " //$NON-NLS-1$
                + "EDT model may be stale until a manual refresh. The pull itself still succeeded.") //$NON-NLS-1$
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
        // A pull reaches an external world (a remote) -> openWorldHint=true. It mutates the working
        // tree but is recoverable (reset/abort), so it is a non-destructive write, mirroring the
        // central classifier's default for a write tool; it never opens a 1C infobase connection.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, Boolean.TRUE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_REMOTE, KEY_BRANCH);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String remote = JsonUtils.extractStringArgument(params, KEY_REMOTE);
        String branch = JsonUtils.extractStringArgument(params, KEY_BRANCH);
        boolean rebase = JsonUtils.extractBooleanArgument(params, KEY_REBASE, false);
        String username = JsonUtils.extractStringArgument(params, KEY_USERNAME);
        String token = JsonUtils.extractStringArgument(params, KEY_TOKEN);

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        // Do NOT close the owned repository from a finally here (mirrors push_git_branch): on a
        // bounded-Job timeout the pull may still be in flight, so closing is delegated to
        // GitRemoteSupport, which fires it once the Job's thread genuinely ends. The catch only covers
        // a failure BEFORE the Job is scheduled (nothing else will close the repository then).
        try
        {
            return pull(resolution, remote, branch, rebase, username, token);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            resolution.closeIfOwned();
            Activator.logError("pull_git_branch: failed for project '" + projectName + "', remote '" + remote //$NON-NLS-1$ //$NON-NLS-2$
                + "', branch '" + branch + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to pull: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private String pull(GitRepositoryResolver.Resolution resolution, String remote, String branch, boolean rebase,
        String username, String token)
    {
        IProject project = resolution.project();
        Repository repo = resolution.repository();
        // Explicit, non-interactive credentials (Option A auth): a real provider from username/token
        // for HTTPS, otherwise a fail-fast provider that errors instead of raising a modal prompt.
        // SSH remotes ignore it and authenticate through EGit's process-global SSH session factory.
        CredentialsProvider credentials = GitRemoteSupport.credentialsProvider(username, token);

        // Capture the pull result INSIDE the op (GitRemoteSupport.RemoteOp returns void; each caller
        // owns its own result holder, exactly like PushGitBranchTool). The fetch + integrate runs on
        // the shared bounded background Job; the SAME Job refreshes the project afterwards (working
        // tree changed) so the EDT model is not left stale.
        AtomicReference<PullResult> resultHolder = new AtomicReference<>();
        GitRemoteSupport.RemoteOutcome outcome = GitRemoteSupport.run(
            "Pull git branch: " + remote + "/" + branch, project, //$NON-NLS-1$ //$NON-NLS-2$
            monitor -> resultHolder.set(Git.wrap(repo).pull()
                .setRemote(remote)
                .setRemoteBranchName(branch)
                .setRebase(rebase)
                .setCredentialsProvider(credentials)
                // Bound the fetch transport itself (JGit default 0 = infinite), so a stalled socket
                // cannot keep the background Job alive after run() has already reported a timeout.
                .setTimeout((int)GitRemoteSupport.REMOTE_TIMEOUT_SECONDS)
                .setProgressMonitor(new EclipseGitProgressTransformer(monitor))
                .call()),
            resolution::closeIfOwned);

        if (outcome.timedOut())
        {
            return ToolResult.error(PULL_FROM + remote + "/" + branch + "' timed out after " //$NON-NLS-1$ //$NON-NLS-2$
                + GitRemoteSupport.REMOTE_TIMEOUT_SECONDS
                + " seconds. Check the remote and network connectivity before retrying.").toJson(); //$NON-NLS-1$
        }
        if (outcome.interrupted())
        {
            return ToolResult.error(PULL_FROM + remote + "/" + branch + "' was interrupted.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (outcome.jobError() != null)
        {
            Activator.logError("pull_git_branch: pull failed for '" + remote + "/" + branch + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                outcome.jobError());
            return ToolResult.error(PULL_FROM + remote + "/" + branch + "' failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + outcome.jobError().getMessage()
                + ". For an HTTPS remote, supply username/token; for SSH, ensure the ssh-agent key is " //$NON-NLS-1$
                + "loaded. Verify the remote name and that the branch exists on it.").toJson(); //$NON-NLS-1$
        }

        return mapPullResult(resultHolder.get(), remote, branch, rebase, outcome.refreshWarning());
    }

    /**
     * Maps a completed {@link PullResult} to the tool result: a clean integration to a
     * {@code success} envelope, and a non-clean one ({@link PullResult#isSuccessful()}
     * {@code == false} - a merge that produced conflicts or a rebase that stopped on
     * them) to an actionable {@link ToolResult#error error} echoing the conflicting
     * paths. Never reports a conflicted pull as success.
     */
    private String mapPullResult(PullResult result, String remote, String branch, boolean rebase,
        String refreshWarning)
    {
        if (result == null)
        {
            return ToolResult.error(PULL_FROM + remote + "/" + branch + "' produced no result. Check the " //$NON-NLS-1$ //$NON-NLS-2$
                + "remote configuration and retry.").toJson(); //$NON-NLS-1$
        }
        // A rebase with autostash that rebased fine but hit conflicts RE-APPLYING the autostashed local
        // changes reports STASH_APPLY_CONFLICTS - and JGit's PullResult.isSuccessful() returns TRUE for it.
        // Guard it BEFORE the isSuccessful() gate, or the tool would report success with unresolved
        // conflicts in the working tree.
        RebaseResult rebaseResult = result.getRebaseResult();
        if (rebaseResult != null && rebaseResult.getStatus() == RebaseResult.Status.STASH_APPLY_CONFLICTS)
        {
            return stashApplyConflictError(remote, branch);
        }
        if (!result.isSuccessful())
        {
            return classifyFailure(remote, branch, result);
        }
        // Some "successful" merge statuses leave the integration INCOMPLETE - squashed and/or not
        // committed (a branch configured with merge --squash / --no-commit): the changes are staged but
        // the branch tip did not advance. JGit's isSuccessful() is TRUE for them, so reporting success
        // would let a caller push the OLD tip. Surface them as an actionable error instead.
        MergeResult mergeResult = result.getMergeResult();
        if (mergeResult != null && isIncompleteMerge(mergeResult.getMergeStatus()))
        {
            return incompleteMergeError(remote, branch, mergeResult.getMergeStatus().name());
        }

        ToolResult ok = ToolResult.success()
            .put(KEY_REMOTE, remote)
            .put(KEY_BRANCH, branch)
            .put(KEY_REBASE, rebase);
        String fetchedFrom = result.getFetchedFrom();
        if (fetchedFrom != null && !fetchedFrom.isEmpty())
        {
            ok.put(KEY_FETCHED_FROM, fetchedFrom);
        }
        String status = successStatus(result);
        if (status != null)
        {
            ok.put(KEY_STATUS, status);
        }
        if (refreshWarning != null)
        {
            ok.put(McpKeys.MESSAGE, refreshWarning);
        }
        return ok.toJson();
    }

    /**
     * The success status for a clean pull: the rebase status when the pull rebased,
     * otherwise the merge status. {@code null} when neither result is present (an
     * empty pull).
     */
    private static String successStatus(PullResult result)
    {
        RebaseResult rebaseResult = result.getRebaseResult();
        if (rebaseResult != null && rebaseResult.getStatus() != null)
        {
            return rebaseResult.getStatus().name();
        }
        MergeResult mergeResult = result.getMergeResult();
        if (mergeResult != null && mergeResult.getMergeStatus() != null)
        {
            return mergeResult.getMergeStatus().name();
        }
        return null;
    }

    /**
     * Classifies a non-successful {@link PullResult} by JGit's ACTUAL rebase/merge status - not merely
     * by whether some paths were extractable - and routes it to the matching actionable message:
     * <ul>
     * <li>a genuine conflict (rebase {@code STOPPED}/{@code CONFLICTS}, merge {@code CONFLICTING}) to
     * {@link #conflictError};</li>
     * <li>a rebase refused for uncommitted changes ({@code UNCOMMITTED_CHANGES}) - commit/stash remedy;</li>
     * <li>a checkout that would overwrite local changes (merge {@code CHECKOUT_CONFLICT}) - stash remedy;</li>
     * <li>a failed op (merge/rebase {@code FAILED}) - the failing paths;</li>
     * <li>anything else - the raw status plus a {@code get_git_status} pointer.</li>
     * </ul>
     * Thin glue over JGit's result objects; the message wording it feeds is unit-tested via
     * {@link #conflictError} / {@link #failureMessage} / {@link #joinBounded}.
     */
    static String classifyFailure(String remote, String branch, PullResult result)
    {
        RebaseResult rebaseResult = result.getRebaseResult();
        if (rebaseResult != null && rebaseResult.getStatus() != null)
        {
            return rebaseFailure(remote, branch, rebaseResult.getStatus(), rebaseResult.getConflicts(),
                rebaseResult.getUncommittedChanges(), keysOf(rebaseResult.getFailingPaths()));
        }
        MergeResult mergeResult = result.getMergeResult();
        if (mergeResult != null && mergeResult.getMergeStatus() != null)
        {
            return mergeFailure(remote, branch, mergeResult.getMergeStatus(), keysOf(mergeResult.getConflicts()),
                mergeResult.getCheckoutConflicts(), keysOf(mergeResult.getFailingPaths()));
        }
        return failureMessage(remote, branch, null, REMEDY_OTHER, null, null);
    }

    /**
     * Maps a non-successful REBASE status to the actionable message: {@code STOPPED}/{@code CONFLICTS}
     * are genuine conflicts, {@code UNCOMMITTED_CHANGES} needs a commit/stash, {@code FAILED} lists the
     * failing paths, anything else points to {@code get_git_status}. Package-visible for direct testing.
     */
    static String rebaseFailure(String remote, String branch, RebaseResult.Status status,
        List<String> conflicts, List<String> uncommitted, List<String> failing)
    {
        switch (status)
        {
            case STOPPED:
            case CONFLICTS:
                return conflictError(remote, branch, true, conflicts);
            case UNCOMMITTED_CHANGES:
                return failureMessage(remote, branch, status.name(), REMEDY_UNCOMMITTED, "Uncommitted paths", //$NON-NLS-1$
                    uncommitted);
            case FAILED:
                return failureMessage(remote, branch, status.name(), REMEDY_FAILED, "Failing paths", failing); //$NON-NLS-1$
            default:
                return failureMessage(remote, branch, status.name(), REMEDY_OTHER, null, null);
        }
    }

    /**
     * Maps a non-successful MERGE status to the actionable message: {@code CONFLICTING} is a genuine
     * conflict, {@code CHECKOUT_CONFLICT} means local changes would be overwritten (stash), {@code FAILED}
     * lists the failing paths, anything else points to {@code get_git_status}. Package-visible for testing.
     */
    static String mergeFailure(String remote, String branch, MergeResult.MergeStatus status,
        List<String> conflicts, List<String> checkoutConflicts, List<String> failing)
    {
        switch (status)
        {
            case CONFLICTING:
                return conflictError(remote, branch, false, conflicts);
            case CHECKOUT_CONFLICT:
                return failureMessage(remote, branch, status.name(), REMEDY_CHECKOUT_CONFLICT, "Blocking paths", //$NON-NLS-1$
                    checkoutConflicts);
            case FAILED:
                return failureMessage(remote, branch, status.name(), REMEDY_FAILED, "Failing paths", failing); //$NON-NLS-1$
            default:
                return failureMessage(remote, branch, status.name(), REMEDY_OTHER, null, null);
        }
    }

    /** @return the key set of {@code map} as a list, or {@code null} when {@code map} is null. */
    private static List<String> keysOf(Map<String, ?> map)
    {
        return map == null ? null : new ArrayList<>(map.keySet());
    }

    /**
     * The actionable error for a rebase whose autostash re-application conflicted
     * ({@code STASH_APPLY_CONFLICTS}). The rebase itself completed, so the paused-rebase wording would be
     * wrong: the conflicts are in the re-applied local changes. Package-visible for direct testing.
     */
    /**
     * @return {@code true} for a merge status that JGit reports as successful but that did NOT complete
     *         an integration commit (squashed and/or not committed - a {@code merge --squash}/{@code
     *         --no-commit} configuration), so the branch tip is unchanged. Package-visible for testing.
     */
    static boolean isIncompleteMerge(MergeResult.MergeStatus status)
    {
        return status == MergeResult.MergeStatus.MERGED_NOT_COMMITTED
            || status == MergeResult.MergeStatus.MERGED_SQUASHED
            || status == MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED
            || status == MergeResult.MergeStatus.FAST_FORWARD_SQUASHED;
    }

    /**
     * The actionable error for an {@link #isIncompleteMerge incomplete} but "successful" merge: the
     * changes are staged/squashed and the branch tip did not advance, so a caller must commit before
     * pushing (else it would push the old tip). Package-visible for direct testing.
     */
    static String incompleteMergeError(String remote, String branch, String status)
    {
        return ToolResult.error(PULL_FROM + remote + "/" + branch + "' fetched and merged, but the merge did " //$NON-NLS-1$ //$NON-NLS-2$
            + "NOT complete an integration commit (" + status + "): the changes are staged/squashed and the " //$NON-NLS-1$ //$NON-NLS-2$
            + "branch tip is unchanged (a 'merge --squash' / '--no-commit' configuration). Commit the pending " //$NON-NLS-1$
            + "merge to finish the pull before pushing: commit_git_changes records the staged changes; a rare " //$NON-NLS-1$
            + "identical-tree merge may leave nothing staged, in which case complete it with a manual " //$NON-NLS-1$
            + "'git commit' (add '--allow-empty' for a squash merge, which keeps no MERGE_HEAD).").toJson(); //$NON-NLS-1$
    }

    static String stashApplyConflictError(String remote, String branch)
    {
        return ToolResult.error(PULL_FROM + remote + "/" + branch + "' rebased successfully, but re-applying " //$NON-NLS-1$ //$NON-NLS-2$
            + "the autostashed local changes produced conflicts (STASH_APPLY_CONFLICTS). The rebase itself " //$NON-NLS-1$
            + "completed - resolve the conflict markers in the working tree and commit. JGit saved your " //$NON-NLS-1$
            + "autostashed changes to the git stash (refs/stash); they are NOT dropped automatically, so " //$NON-NLS-1$
            + "drop them manually (git stash drop) once verified, or they may be re-applied later.").toJson(); //$NON-NLS-1$
    }

    /**
     * Builds the actionable conflict error for a non-clean pull, with rebase- vs
     * merge-specific recovery wording and a bounded ({@link #MAX_LISTED_PATHS}) echo of
     * the conflicting paths. Package-visible so it is directly unit-testable without a
     * live {@link PullResult} (whose constructors are not public).
     */
    static String conflictError(String remote, String branch, boolean rebase, List<String> conflicts)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(PULL_FROM).append(remote).append('/').append(branch)
            .append("' did not complete cleanly ("); //$NON-NLS-1$
        if (rebase)
        {
            sb.append("rebase): the rebase stopped on conflicts and is left PAUSED. Resolve the " //$NON-NLS-1$
                + "conflicts and continue the rebase, or abort it, before retrying."); //$NON-NLS-1$
        }
        else
        {
            sb.append("merge): the merge produced conflicts and the working tree now has conflict " //$NON-NLS-1$
                + "markers. Resolve them (or reset the working tree) before retrying."); //$NON-NLS-1$
        }
        sb.append(" Conflicting paths: ").append(joinBounded(conflicts)).append('.'); //$NON-NLS-1$
        return ToolResult.error(sb.toString()).toJson();
    }

    /** Remedy for a rebase refused because the working tree has uncommitted changes. */
    private static final String REMEDY_UNCOMMITTED = "The rebase was refused because the working tree has " //$NON-NLS-1$
        + "uncommitted changes: commit or stash them (or use rebase=false to merge) and retry."; //$NON-NLS-1$

    /** Remedy for a merge checkout blocked because local changes would be overwritten. */
    private static final String REMEDY_CHECKOUT_CONFLICT = "The checkout was blocked because local changes " //$NON-NLS-1$
        + "would be overwritten: commit or stash them and retry."; //$NON-NLS-1$

    /** Remedy for a failed merge/rebase (dirty or locked paths). */
    private static final String REMEDY_FAILED = "The pull failed for some paths (e.g. dirty or locked " //$NON-NLS-1$
        + "files): resolve them and retry."; //$NON-NLS-1$

    /** Remedy for any other non-success status. */
    private static final String REMEDY_OTHER = "Inspect the local state with get_git_status (uncommitted " //$NON-NLS-1$
        + "changes, an in-progress merge/rebase, or a detached HEAD can all block a pull), resolve it, " //$NON-NLS-1$
        + "then retry."; //$NON-NLS-1$

    /**
     * Pure message builder for a non-conflict pull failure: reports the real JGit {@code status}, a
     * targeted {@code remedy}, and (when present) a bounded list of the offending paths under
     * {@code pathsLabel}. Never claims a conflict. Package-visible so it is directly unit-testable
     * without a live {@link PullResult} (whose constructors are not public).
     *
     * @param status the JGit status name, or {@code null}
     * @param remedy the actionable remedy sentence (one of the {@code REMEDY_*} constants)
     * @param pathsLabel a label for the path list (e.g. "Uncommitted paths"), or {@code null} for none
     * @param paths the offending paths, or {@code null}/empty for none
     */
    static String failureMessage(String remote, String branch, String status, String remedy,
        String pathsLabel, List<String> paths)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(PULL_FROM).append(remote).append('/').append(branch).append("' did not complete"); //$NON-NLS-1$
        if (status != null)
        {
            sb.append(" (").append(status).append(')'); //$NON-NLS-1$
        }
        sb.append(". ").append(remedy); //$NON-NLS-1$
        if (pathsLabel != null && paths != null && !paths.isEmpty())
        {
            sb.append(' ').append(pathsLabel).append(": ").append(joinBounded(paths)).append('.'); //$NON-NLS-1$
        }
        return ToolResult.error(sb.toString()).toJson();
    }

    /**
     * Joins {@code paths} into a comma-separated string bounded to
     * {@link #MAX_LISTED_PATHS} entries, appending a {@code "...and N more"} remainder.
     * Package-visible (mirrors {@code SwitchGitBranchTool#joinBounded}) so it is directly
     * unit-testable.
     */
    static String joinBounded(List<String> paths)
    {
        if (paths == null || paths.isEmpty())
        {
            return "(none)"; //$NON-NLS-1$
        }
        int shown = Math.min(paths.size(), MAX_LISTED_PATHS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(paths.get(i));
        }
        if (paths.size() > shown)
        {
            sb.append(" ...and ").append(paths.size() - shown).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
