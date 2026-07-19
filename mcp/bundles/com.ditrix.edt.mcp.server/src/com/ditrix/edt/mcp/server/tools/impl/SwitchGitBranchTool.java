/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.git.GitCheckoutSupport;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Switches a project's git repository to another branch via the headless EGit
 * checkout ({@code BranchOperation}) - the non-UI sibling of EDT's own "Switch to"
 * command (issue #281). The application/infobase context that
 * {@code list_git_branches} reports follows the checkout automatically (it is
 * derived live from the repository's checked-out branch), so a successful switch
 * reads back the new context's bindings as proof.
 * <p>
 * <b>Two deliberate safety choices, stated once here:</b>
 * <ul>
 * <li>This tool does NOT participate in the destructive-consent gate
 * ({@code DestructiveConsentGate.GATED_TOOLS} does not list it): a branch switch is
 * reversible (switch back), and the mandatory clean-working-tree pre-check below is
 * the actual safety net, not a human prompt.</li>
 * <li>This tool never opens or authenticates against a 1C infobase - it only reads
 * the association manager's already-resolved bindings - so it needs no
 * infobase-connection bookkeeping (no auth-dialog activity marking).</li>
 * </ul>
 * The checkout itself runs via {@link GitCheckoutSupport#checkout(IProject, Repository, String)}
 * - a bounded (120 s) background {@code Job}, never on the UI thread (issue #281
 * phase 2 extracted the Job/refresh mechanics there so {@code create_git_branch}'s
 * optional checkout can share it). After a successful checkout, the SAME Job
 * unconditionally refreshes the project
 * ({@link IResource#refreshLocal(int, org.eclipse.core.runtime.IProgressMonitor)},
 * {@code DEPTH_INFINITE}) so the Eclipse workspace - and the EDT model built on it -
 * picks up the checkout's file changes: on the EGit-mapped resolution path
 * {@code BranchOperation} already refreshes affected projects itself, so this is a
 * cheap near no-op there; on the {@link GitRepositoryResolver} discovery-fallback
 * path (project not EGit-shared) there is NO other guarantee the workspace notices
 * the checkout at all - native-hook workspace auto-refresh defaults OFF - so this
 * refresh is what keeps the model from going stale. A refresh failure is caught,
 * logged, and surfaced as a short note in the result {@code message}; it never
 * fails the already-successful switch.
 */
public class SwitchGitBranchTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "switch_git_branch"; //$NON-NLS-1$

    /** Input param: the branch to switch to (short local name or a full {@code refs/heads/...} ref). */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Output key: the branch that was checked out before the switch. */
    private static final String KEY_PREVIOUS_BRANCH = "previousBranch"; //$NON-NLS-1$

    /** Output key: best-effort application-binding read-back for the new branch context. */
    private static final String KEY_BINDINGS = "bindings"; //$NON-NLS-1$

    private static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    private static final String REFS_REMOTES = "refs/remotes/"; //$NON-NLS-1$

    /** Cap on how many conflicting/undeleted paths are echoed in an error. */
    private static final int MAX_LISTED_PATHS = 20;

    /** Common prefix of every {@link #mapCheckoutResult} error message. */
    private static final String CHECKOUT_OF = "Checkout of '"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Switch a project's git repository to another branch (headless EGit checkout). " //$NON-NLS-1$
            + "branch may be a short local name (e.g. 'feature/x') or a full ref " //$NON-NLS-1$
            + "('refs/heads/feature/x'); a remote-only branch is rejected (create a local branch " //$NON-NLS-1$
            + "first). Refuses to switch when the working tree has uncommitted changes (untracked " //$NON-NLS-1$
            + "files alone do not block) or when already on the target branch. The 1C " //$NON-NLS-1$
            + "application/infobase binding follows the checkout automatically; the result reports it. " //$NON-NLS-1$
            + "Runs in a background Job (up to 120 s). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('switch_git_branch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to switch (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_BRANCH,
                "Branch to switch to (required): a short local name (e.g. 'feature/x') or a full " //$NON-NLS-1$
                + "ref ('refs/heads/feature/x'). A remote-tracking ref (e.g. 'origin/feature/x') is " //$NON-NLS-1$
                + "rejected - create a local branch first.", //$NON-NLS-1$
                true)
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the switch succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_PREVIOUS_BRANCH, "The branch (or detached-HEAD commit) checked out BEFORE the switch.") //$NON-NLS-1$
            .stringProperty(KEY_BRANCH, "The branch now checked out.") //$NON-NLS-1$
            .objectProperty(KEY_BINDINGS,
                "Best-effort application-binding read-back for the new branch context: " //$NON-NLS-1$
                + "{infobases: [...], defaultInfobase}. Absent when unavailable.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present only when the post-checkout workspace refresh failed: a short warning " //$NON-NLS-1$
                + "noting the EDT model may be stale until a manual refresh. The switch itself still " //$NON-NLS-1$
                + "succeeded (success stays true).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_BRANCH);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String branch = JsonUtils.extractStringArgument(params, KEY_BRANCH);

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        try
        {
            return switchBranch(resolution.project(), resolution.repository(), branch);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("switch_git_branch: failed for project '" + projectName //$NON-NLS-1$
                + "', branch '" + branch + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to switch branch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private String switchBranch(IProject project, Repository repo, String branch) throws Exception
    {
        // --- pre-checks (before ANY mutation) ---

        Ref targetRef = repo.findRef(branch);
        if (targetRef == null)
        {
            String current = safeCurrentBranch(repo);
            return ToolResult.error("Branch not found: '" + branch + "' (currently on '" + current //$NON-NLS-1$ //$NON-NLS-2$
                + "'). Use list_git_branches to see available branches.").toJson(); //$NON-NLS-1$
        }

        String refName = targetRef.getName();
        if (refName.startsWith(REFS_REMOTES))
        {
            return ToolResult.error("'" + branch + "' resolves to the remote-tracking ref '" + refName //$NON-NLS-1$ //$NON-NLS-2$
                + "', which is not checked out directly in v1. Create a local branch tracking it first " //$NON-NLS-1$
                + "(e.g. via EDT's Team menu), then switch to that local branch.").toJson(); //$NON-NLS-1$
        }
        if (!refName.startsWith(REFS_HEADS))
        {
            return ToolResult.error("'" + branch + "' resolves to '" + refName //$NON-NLS-1$ //$NON-NLS-2$
                + "', which is not a branch (e.g. a tag). Use list_git_branches to see available " //$NON-NLS-1$
                + "branches.").toJson(); //$NON-NLS-1$
        }

        String previousFullBranch = repo.getFullBranch();
        String previousShort = safeCurrentBranch(repo);
        if (refName.equals(previousFullBranch))
        {
            return ToolResult.error("Already on branch '" + previousShort + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        boolean uncommitted;
        try
        {
            uncommitted = Git.wrap(repo).status().call().hasUncommittedChanges();
        }
        catch (Exception e)
        {
            return ToolResult.error("Could not read the working-tree status: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (uncommitted)
        {
            return ToolResult.error("The working tree has uncommitted changes; commit or stash them " //$NON-NLS-1$
                + "before switching branches. (Untracked files alone do not block a switch.)").toJson(); //$NON-NLS-1$
        }

        // --- execute the checkout via the shared bounded-Job helper (never on the UI thread) ---

        String targetShort = refName.substring(REFS_HEADS.length());
        GitCheckoutSupport.CheckoutOutcome outcome = GitCheckoutSupport.checkout(project, repo, refName);

        if (outcome.timedOut())
        {
            return ToolResult.error("Switching to branch '" + targetShort + "' timed out after " //$NON-NLS-1$ //$NON-NLS-2$
                + GitCheckoutSupport.CHECKOUT_TIMEOUT_SECONDS
                + " seconds. Check the repository state before retrying.").toJson(); //$NON-NLS-1$
        }
        if (outcome.interrupted())
        {
            return ToolResult.error("Switching branch was interrupted.").toJson(); //$NON-NLS-1$
        }
        if (outcome.jobError() != null)
        {
            Activator.logError("switch_git_branch: checkout failed for branch '" + targetShort + "'", //$NON-NLS-1$ //$NON-NLS-2$
                outcome.jobError());
            return ToolResult.error("Checkout failed: " + outcome.jobError().getMessage()).toJson(); //$NON-NLS-1$
        }

        return mapCheckoutResult(outcome.result(), project, previousShort, targetShort, outcome.refreshWarning());
    }

    private String mapCheckoutResult(CheckoutResult result, IProject project, String previousShort,
        String targetShort, String refreshWarning)
    {
        if (result == null)
        {
            return ToolResult.error(CHECKOUT_OF + targetShort + "' failed (no checkout result). The " //$NON-NLS-1$
                + "working tree may be left on the ORIGINAL branch '" + previousShort + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        switch (result.getStatus())
        {
            case OK:
            {
                ToolResult ok = ToolResult.success()
                    .put(KEY_PREVIOUS_BRANCH, previousShort)
                    .put(KEY_BRANCH, targetShort);
                Map<String, Object> bindings = readBackBindings(project);
                if (!bindings.isEmpty())
                {
                    ok.put(KEY_BINDINGS, bindings);
                }
                if (refreshWarning != null)
                {
                    ok.put(McpKeys.MESSAGE, refreshWarning);
                }
                return ok.toJson();
            }
            case CONFLICTS:
                return ToolResult.error(CHECKOUT_OF + targetShort + "' left uncommitted-change " //$NON-NLS-1$
                    + "conflicts; the working tree was left on the ORIGINAL branch '" + previousShort //$NON-NLS-1$
                    + "'. Conflicting paths: " + joinBounded(result.getConflictList())).toJson(); //$NON-NLS-1$
            case NONDELETED:
                return ToolResult.error(CHECKOUT_OF + targetShort + "' succeeded, but some files " //$NON-NLS-1$
                    + "could not be deleted (likely locked or dirty): " //$NON-NLS-1$
                    + joinBounded(result.getUndeletedList())).toJson(); //$NON-NLS-1$
            default:
                return ToolResult.error(CHECKOUT_OF + targetShort + "' failed (status: " + result.getStatus() //$NON-NLS-1$
                    + "). The working tree may be left on the ORIGINAL branch '" + previousShort //$NON-NLS-1$
                    + "'.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Best-effort read-back of the CURRENT application/infobase binding after a
     * successful switch - the proof that the context followed the checkout. Any
     * failure (manager absent, {@link InfobaseAssociationException}, no binding
     * recorded) yields an empty map (the caller then omits the {@code bindings}
     * key) rather than failing the already-successful switch.
     */
    private static Map<String, Object> readBackBindings(IProject project)
    {
        IInfobaseAssociationManager assocManager = Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return Collections.emptyMap();
        }
        try
        {
            Optional<IInfobaseAssociation> assoc = assocManager.getAssociation(project);
            if (assoc.isEmpty())
            {
                return Collections.emptyMap();
            }
            Collection<InfobaseReference> infobases = assoc.get().getInfobases();
            InfobaseReference def = assoc.get().getDefaultInfobase();
            List<String> names = new ArrayList<>();
            if (infobases != null)
            {
                for (InfobaseReference ib : infobases)
                {
                    names.add(ib.getName());
                }
            }
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("infobases", names); //$NON-NLS-1$
            bindings.put("defaultInfobase", def != null ? def.getName() : null); //$NON-NLS-1$
            return bindings;
        }
        catch (RuntimeException e)
        {
            Activator.logError("switch_git_branch: bindings read-back failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    private static String safeCurrentBranch(Repository repo)
    {
        try
        {
            String branch = repo.getBranch();
            return branch != null ? branch : "(unknown)"; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "(unknown)"; //$NON-NLS-1$
        }
    }

    private static String joinBounded(List<String> paths)
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
