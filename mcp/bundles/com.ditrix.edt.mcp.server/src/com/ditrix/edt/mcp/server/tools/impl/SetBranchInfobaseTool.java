/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.git.AssociationStoreFile;
import com.ditrix.edt.mcp.server.utils.git.BranchBindingLock;
import com.ditrix.edt.mcp.server.utils.git.BranchContexts;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Attaches or detaches an EXISTING infobase (application) to/from a specific git
 * branch <em>context</em>, so the binding {@code list_git_branches} reports and
 * {@code switch_git_branch} follows automatically is recorded for that branch
 * (issue #281 phase 2). The binding is stored under {@code refs/heads/<branch>}, the
 * context EDT itself reads for the checked-out branch (#684, {@link BranchContexts}).
 * <p>
 * This is a pure EDT workspace-metadata write via
 * {@link IInfobaseAssociationManager} - it never opens or authenticates against a
 * 1C infobase, so it is NOT gated by the destructive-consent dialog
 * ({@code DestructiveConsentGate.GATED_TOOLS} does not list it; the binding is
 * reversible - detach or re-attach with another call) and does not mark infobase-
 * connection activity ({@link #connectsToInfobase()} stays the default
 * {@code false}).
 * <p>
 * The attach settings use {@link InfobaseAssociationSettings#alreadySynchronized}:
 * the caller is registering a base it (or a human) already built/synchronized for
 * this branch, not asking EDT to run a synchronization flow - the unattended-safe
 * choice (no heavy sync {@code Job}, no modal).
 * <p>
 * A detach is guarded against EDT's whole-file deletions (#656): EDT removes a binding from a
 * context other than the one it reads now
 * ({@link BranchContexts#current(IInfobaseAssociationContextProvider, IProject)}) by deleting that
 * context's whole binding file, and the same-named file of each dependent extension project whose
 * own current context differs - the latter on a detach from the checked-out branch too; it removes
 * a binding from a legacy short key the same way. A detach runs only when those deletions can take
 * nothing but the binding being removed - judged from EDT's read AND from the persisted files, since
 * EDT's read hides stale entries ({@link AssociationStoreFile}) - and this tool's binding changes
 * and this server's checkouts are serialized ({@link BranchBindingLock}); see {@link #applyResolved}.
 */
public class SetBranchInfobaseTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "set_branch_infobase"; //$NON-NLS-1$

    /**
     * Input param: the branch whose context to bind/unbind - its short name or its
     * {@code refs/heads/...} ref; the binding is stored under the full ref ({@link BranchContexts}).
     */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Input param: {@code attach} (default) or {@code detach}. */
    private static final String KEY_ACTION = "action"; //$NON-NLS-1$

    /** Input param: attach only - also make the application the branch context's default infobase. */
    private static final String KEY_SET_DEFAULT = "setDefault"; //$NON-NLS-1$

    /** Enum value: bind the application to the branch context. */
    private static final String ACTION_ATTACH = "attach"; //$NON-NLS-1$

    /** Enum value: remove an existing binding. */
    private static final String ACTION_DETACH = "detach"; //$NON-NLS-1$

    /** Output key: read-back of the branch context's bindings after the change. */
    private static final String KEY_BOUND = "bound"; //$NON-NLS-1$

    /** Output/read-back key: the infobase names bound to the context. */
    private static final String KEY_INFOBASES = "infobases"; //$NON-NLS-1$

    /** Output/read-back key: the context's default infobase name (may be {@code null}). */
    private static final String KEY_DEFAULT_INFOBASE = "defaultInfobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Associate an existing infobase with a Git branch of an EDT project. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('set_branch_infobase')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose branch-infobase binding to change (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_BRANCH,
                "Short branch name (e.g. 'feature/x') whose context to bind/unbind (required).", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id from get_applications - the infobase to attach/detach (required).", true) //$NON-NLS-1$
            .enumProperty(KEY_ACTION,
                "'attach' (default) binds the application to the branch context; 'detach' removes " //$NON-NLS-1$
                + "an existing binding.", //$NON-NLS-1$
                ACTION_ATTACH, ACTION_DETACH)
            .booleanProperty(KEY_SET_DEFAULT,
                "attach only: also make this the DEFAULT infobase for the branch context. Ignored " //$NON-NLS-1$
                + "(and documented as such) for detach. Default false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the binding change succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_ACTION, "The action performed: 'attach' or 'detach'.") //$NON-NLS-1$
            .stringProperty(KEY_BRANCH, "The branch context that was changed.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "The target application id.") //$NON-NLS-1$
            .objectProperty(KEY_BOUND,
                "Read-back of the branch context after the change: {infobases: [...], " //$NON-NLS-1$
                + "defaultInfobase}. Proves the base is now bound (attach) or gone (detach).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present only when the change needs a note: a detach removed a legacy short-key binding " //$NON-NLS-1$
                + "or the only binding of a branch that is not checked out, or an attach succeeded but EDT " //$NON-NLS-1$
                + "did not record the requested default.") //$NON-NLS-1$
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
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_BRANCH, McpKeys.APPLICATION_ID);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String branch = JsonUtils.extractStringArgument(params, KEY_BRANCH);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String action = JsonUtils.extractStringArgument(params, KEY_ACTION);
        boolean setDefault = JsonUtils.extractBooleanArgument(params, KEY_SET_DEFAULT, false);

        String actionError = validateAction(action);
        if (actionError != null)
        {
            return actionError;
        }
        boolean attach = action == null || action.isEmpty() || ACTION_ATTACH.equalsIgnoreCase(action);

        // A pure input check, before any repository access: a remote ref, a tag or a blank name can
        // never be a branch context.
        BranchContexts.Resolution branchTarget = BranchContexts.resolve(branch);
        if (!branchTarget.ok())
        {
            return ToolResult.error(branchTarget.error()).toJson();
        }

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        try
        {
            return apply(resolution.project(), branch, applicationId, attach, setDefault);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("set_branch_infobase: failed for project '" + projectName //$NON-NLS-1$
                + "', branch '" + branch + "', application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return ToolResult.error("Failed to change branch-infobase binding: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private static String validateAction(String action)
    {
        if (action == null || action.isEmpty() || ACTION_ATTACH.equalsIgnoreCase(action)
            || ACTION_DETACH.equalsIgnoreCase(action))
        {
            return null;
        }
        return ToolResult.error("Invalid action: '" + action + "'. Allowed values: 'attach', 'detach'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String apply(IProject project, String branch, String applicationId, boolean attach, boolean setDefault)
    {
        IInfobaseAssociationManager assocManager = Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return ToolResult.error("IInfobaseAssociationManager service is not available.").toJson(); //$NON-NLS-1$
        }

        ApplicationReferenceResolution appRef = resolveApplicationReference(project, applicationId);
        if (appRef.error() != null)
        {
            return appRef.error();
        }
        return applyResolved(assocManager, Activator.getDefault().getInfobaseAssociationContextProvider(),
            () -> dependentExtensionProjects(project), project, branch, applicationId, appRef.reference(), attach,
            setDefault);
    }

    /**
     * The extension projects EDT's association handler also acts on when a binding of {@code project} is
     * removed: {@code IDependentProject.getDependent(project, getProjects(IExtensionProject.class))}, the
     * same collection EDT builds.
     *
     * @throws IllegalStateException when the project manager service is not available
     */
    private static Collection<IProject> dependentExtensionProjects(IProject project)
    {
        IV8ProjectManager projectManager = Activator.getDefault().getV8ProjectManager();
        if (projectManager == null)
        {
            throw new IllegalStateException("IV8ProjectManager service is not available"); //$NON-NLS-1$
        }
        return IDependentProject.getDependent(project, projectManager.getProjects(IExtensionProject.class));
    }

    /**
     * The binding change itself, with the association manager and the application's infobase already
     * resolved (unit-testable without a live workspace).
     * <p>
     * Attach, set-default and the read-back use the context EDT itself keys the branch by,
     * {@code refs/heads/<branch>} ({@link BranchContexts}) - a binding under the short name would
     * never become the current context (#684). Detach removes the binding under that context first;
     * when there is none, it removes one an older version of this tool wrote under the bare short name,
     * and says so, so such entries can be cleaned up. Nothing is migrated automatically. A default EDT
     * refuses after a successful attach (it records one only for an infobase also bound to the
     * checked-out branch) leaves the attach in place and is reported in the answer's message, not as
     * a failure; a platform failure storing the default is reported there too, and logged.
     * <p>
     * A detach is planned before anything changes ({@link #planDetach}): bound means bound by UUID, the
     * key EDT's own dissociation matches by, and any failure to read what the detach would touch refuses
     * it. A detach from the branch EDT reads now rewrites this project's file; a detach that EDT carries out
     * by deleting this project's whole binding file - from a branch that is not checked out, or from a legacy
     * short key, which EDT never reads - runs only when that deletion can take nothing but the binding being
     * removed. Either way EDT also deletes the same-named file of each dependent extension project that does
     * not read the context now, and the detach runs only when those files hold nothing; otherwise it is
     * refused, naming what would be lost.
     * <p>
     * The whole change - plan, re-check, EDT's write and the read-back - runs under {@link BranchBindingLock}, the
     * lock this server's checkouts take too, and the plan is re-checked right before EDT's write
     * ({@link DetachPlan#recheck()}). A detach is refused while a branch change of this server that outlived its
     * bounded wait is still running for the project or a dependent ({@link #runningBranchChange}).
     *
     * @param provider EDT's current-context provider (may be {@code null}: a detach that needs it refuses)
     * @param dependents the project's dependent extension projects; may throw, which refuses a detach
     *            that needs them
     */
    static String applyResolved(IInfobaseAssociationManager assocManager, IInfobaseAssociationContextProvider provider,
        Supplier<Collection<IProject>> dependents, IProject project, String branch, String applicationId,
        InfobaseReference ref, boolean attach, boolean setDefault)
    {
        BranchContexts.Resolution resolution = BranchContexts.resolve(branch);
        if (!resolution.ok())
        {
            return ToolResult.error(resolution.error()).toJson();
        }
        if (!BranchBindingLock.tryLock())
        {
            return ToolResult.error("Did not change the binding: " + BranchBindingLock.busyReason() //$NON-NLS-1$
                + ". Nothing was changed; retry.").toJson(); //$NON-NLS-1$
        }
        try
        {
            return applyLocked(assocManager, provider, dependents, project, branch, applicationId, ref, attach,
                setDefault, resolution.target());
        }
        finally
        {
            BranchBindingLock.unlock();
        }
    }

    /** {@link #applyResolved} under {@link BranchBindingLock}. */
    private static String applyLocked(IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
        String branch, String applicationId, InfobaseReference ref, boolean attach, boolean setDefault,
        BranchContexts.Target target)
    {
        InfobaseAssociationContext ctx = target.context();
        String note = null;

        if (!attach)
        {
            String running = runningBranchChange(project, dependents);
            if (running != null)
            {
                return ToolResult.error(running).toJson();
            }
            DetachPlan plan = planDetach(assocManager, provider, dependents, project, applicationId, target, ref);
            if (plan.refusal() != null)
            {
                return ToolResult.error(plan.refusal()).toJson();
            }
            running = runningBranchChange(project, dependents);
            if (running != null)
            {
                return ToolResult.error(running).toJson();
            }
            String changed = plan.recheck();
            if (changed != null)
            {
                return ToolResult.error(changed).toJson();
            }
            ctx = plan.context();
            note = plan.note();
        }

        try
        {
            if (attach)
            {
                assocManager.associate(project, ref, InfobaseAssociationSettings.alreadySynchronized(ctx));
                if (setDefault)
                {
                    note = setDefaultAfterAttach(assocManager, project, applicationId, ref, target);
                }
            }
            else
            {
                assocManager.dissociate(project, ref, ctx);
            }
        }
        catch (InfobaseAssociationException e)
        {
            String verb = attach ? ACTION_ATTACH : ACTION_DETACH;
            Activator.logError("set_branch_infobase: " + verb + " failed for branch '" + branch //$NON-NLS-1$ //$NON-NLS-2$
                + "', application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to " + verb + " application '" + applicationId + "' " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (attach ? "to" : "from") + " branch '" + branch + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        ToolResult ok = ToolResult.success()
            .put(KEY_ACTION, attach ? ACTION_ATTACH : ACTION_DETACH)
            .put(KEY_BRANCH, branch)
            .put(McpKeys.APPLICATION_ID, applicationId);
        Map<String, Object> bound = readBack(assocManager, project, ctx);
        if (!bound.isEmpty())
        {
            ok.put(KEY_BOUND, bound);
        }
        if (note != null)
        {
            ok.put(McpKeys.MESSAGE, note);
        }
        return ok.toJson();
    }

    /**
     * The refusal of a detach while a branch change of this server is still running for the project or one of its
     * dependent extension projects ({@link BranchBindingLock#awaitOrTrack}: a checkout Job, or the refresh after a
     * {@code git} checkout, that outlived its bounded wait), or {@code null}. When it lands it changes the branch
     * EDT reads, and with it what EDT's write deletes, so neither the plan nor the re-check could rely on what
     * they read. Checked before the plan and again before the re-check. The second check cannot fire today: a change
     * is recorded only under {@link BranchBindingLock}, which the detach holds across the plan and the re-check; it
     * guards against a change recorded reentrantly from the planning thread (the lock is reentrant), which is what
     * its pin exercises. The dependents are listed only while such
     * a change is recorded; when they cannot be listed then, any recorded change refuses.
     */
    private static String runningBranchChange(IProject project, Supplier<Collection<IProject>> dependents)
    {
        if (!BranchBindingLock.anyInFlight())
        {
            return null;
        }
        List<String> names = new ArrayList<>();
        names.add(project.getName());
        try
        {
            for (IProject dependent : dependents.get())
            {
                names.add(dependent.getName());
            }
        }
        catch (RuntimeException e) // NOSONAR fail closed: a dependent that cannot be listed cannot be ruled out
        {
            String running = BranchBindingLock.inFlightAmong(names);
            return BranchBindingLock.inFlightRefusal(running);
        }
        String running = BranchBindingLock.inFlightAmong(names);
        return running == null ? null : BranchBindingLock.inFlightRefusal(running);
    }

    /**
     * Records the requested default after a successful attach. EDT refuses a default for a branch that
     * is not checked out, unless the checked-out branch already has this base bound
     * ({@link BranchContexts#defaultNotSetNote}); the attach then stands, and the
     * refusal is returned as the answer's note instead of turning a done attach into a failure.
     *
     * @return {@code null} when the default was recorded, otherwise the note for the answer
     */
    private static String setDefaultAfterAttach(IInfobaseAssociationManager assocManager, IProject project,
        String applicationId, InfobaseReference ref, BranchContexts.Target target)
    {
        try
        {
            assocManager.setDefaultInfobase(project, ref, target.context());
            return null;
        }
        catch (IllegalArgumentException e)
        {
            // EDT's refusal (the base is not bound to the checked-out branch): a remedy, not a fault.
            return BranchContexts.defaultNotSetNote(applicationId, target, e);
        }
        catch (InfobaseAssociationException e)
        {
            Activator.logError("set_branch_infobase: setDefault failed for branch '" + target.shortName() //$NON-NLS-1$
                + "', application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return BranchContexts.defaultNotSetNote(applicationId, target, e);
        }
    }

    /**
     * Decides, before anything changes, where a detach dissociates and whether it may (#656).
     * <ul>
     * <li>"Bound" is decided by UUID, the key EDT's {@code dissociate} matches by; a same-named base with
     * another UUID is not bound. A base without a UUID cannot be matched and is refused.</li>
     * <li>The branch's own context {@code refs/heads/<branch>} wins; the legacy short key is the
     * fallback, as before.</li>
     * <li>When the branch's context is the one EDT reads now, EDT rewrites the file without this binding,
     * but it still deletes the same-named file of every dependent extension project whose own current
     * context differs (one in another repository, or one EDT sees no repository for); the detach runs only
     * when those files hold nothing ({@link #planCurrentBranchDetach}).</li>
     * <li>Otherwise - a branch that is not checked out, and always a legacy short key, which is never the
     * current context - EDT deletes the context's whole binding file, and the same-named file of every
     * dependent extension project whose own current context differs, when it holds a synchronization for
     * the project, which on a running EDT it practically always does. The detach then runs only when that
     * deletion can take nothing else ({@link #checkWholeFile}).</li>
     * <li>Any failure to read one of these facts refuses the detach: nothing after the call shows a loss
     * (EDT logs none), so an unread fact cannot be reported later.</li>
     * </ul>
     * What the plan relied on is re-checked right before EDT's write ({@link DetachPlan#recheck()}); the
     * window that remains is named at {@link BranchBindingLock}.
     */
    private static DetachPlan planDetach(IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
        String applicationId, BranchContexts.Target target, InfobaseReference ref)
    {
        UUID uuid = ref.getUuid();
        if (uuid == null)
        {
            return DetachPlan.refused("Application '" + applicationId + "' has an infobase without a UUID; EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "matches bindings by UUID, so it cannot be told which binding to remove. Nothing was changed."); //$NON-NLS-1$
        }
        InfobaseAssociationContext ctx = target.context();
        List<InfobaseReference> inBranch;
        try
        {
            inBranch = boundInfobases(assocManager, project, ctx);
        }
        catch (RuntimeException e)
        {
            return DetachPlan.refused(readFailure(project, target.fullRef(), e));
        }
        if (containsUuid(inBranch, uuid))
        {
            return planBranchDetach(assocManager, provider, dependents, project, applicationId, target, uuid,
                inBranch);
        }

        InfobaseAssociationContext legacy = target.legacyContext();
        if (legacy != null)
        {
            List<InfobaseReference> inLegacy;
            try
            {
                inLegacy = boundInfobases(assocManager, project, legacy);
            }
            catch (RuntimeException e)
            {
                return DetachPlan.refused(readFailure(project, target.shortName(), e));
            }
            if (containsUuid(inLegacy, uuid))
            {
                return planLegacyDetach(assocManager, dependents, project, applicationId, target, uuid, inLegacy);
            }
        }
        return DetachPlan.refused("Application '" + applicationId + "' is not bound to branch '" //$NON-NLS-1$ //$NON-NLS-2$
            + target.shortName() + "' (context '" + target.fullRef() + "'), nor under the legacy " //$NON-NLS-1$ //$NON-NLS-2$
            + "short key '" + target.shortName() + "'; nothing to detach. Use list_git_branches " //$NON-NLS-1$ //$NON-NLS-2$
            + "to see current bindings."); //$NON-NLS-1$
    }

    /** The detach of a binding stored under the branch's own context; see {@link #planDetach}. */
    private static DetachPlan planBranchDetach(IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
        String applicationId, BranchContexts.Target target, UUID uuid, List<InfobaseReference> inBranch)
    {
        InfobaseAssociationContext ctx = target.context();
        BranchContexts.Current current = BranchContexts.current(provider, project);
        if (!current.ok())
        {
            return DetachPlan.refused(current.error());
        }
        InfobaseAssociationContext readNow = current.context();
        if (ctx.equals(readNow))
        {
            return planCurrentBranchDetach(assocManager, provider, dependents, project, applicationId, target,
                readNow);
        }

        WholeFileCheck check = checkWholeFile(assocManager, provider, dependents, project, applicationId, target,
            ctx, uuid, inBranch, false, readNow);
        if (check.refusal != null)
        {
            return DetachPlan.refused(check.refusal);
        }
        if (!check.lost.isEmpty())
        {
            return DetachPlan.refused("Branch '" + target.shortName() + "' is not checked out. EDT may remove a " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding from a branch that is not checked out by deleting the branch's whole binding file, " //$NON-NLS-1$
                + "so detaching application '" + applicationId + "' here may also remove: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("; ", check.lost) + ". Nothing was changed. " //$NON-NLS-1$ //$NON-NLS-2$
                + notCheckedOutRemedy(target, readNow, check.dependentLost) + check.hiddenEntriesNote());
        }
        if (check.ownProblem != null)
        {
            return DetachPlan.refused(check.ownProblem);
        }
        return DetachPlan.of(ctx, "Branch '" + target.shortName() + "' is not checked out, and EDT may remove a " //$NON-NLS-1$ //$NON-NLS-2$
            + "binding from such a branch by deleting its whole binding file; that file held no other " //$NON-NLS-1$
            + "binding, so no other binding was removed.", //$NON-NLS-1$
            () -> check.recheck(assocManager, provider, dependents, project, ctx, target.fullRef(), uuid));
    }

    /**
     * The detach of a binding from the branch EDT reads now; see {@link #planDetach}. EDT rewrites this
     * project's file without the binding, but it still deletes the same-named file of each dependent extension
     * project whose own current context differs (one in another repository, or one EDT sees no repository for),
     * so those files are checked as every whole-file deletion is ({@link #checkDependents}). The re-check right
     * before EDT's write re-checks the dependents ({@link WholeFileCheck#recheckDependents}) and then, as its last
     * read, requires that EDT still reads this branch for the project - after a checkout the write would delete
     * this project's whole file.
     */
    private static DetachPlan planCurrentBranchDetach(IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
        String applicationId, BranchContexts.Target target, InfobaseAssociationContext readNow)
    {
        InfobaseAssociationContext ctx = target.context();
        WholeFileCheck check = new WholeFileCheck();
        try
        {
            checkDependents(check, assocManager, provider, dependents, ctx, false, "to this branch"); //$NON-NLS-1$
        }
        catch (RuntimeException | IOException e)
        {
            return DetachPlan.refused(readFailure(project, target.fullRef(), e));
        }
        if (check.refusal != null)
        {
            return DetachPlan.refused(check.refusal);
        }
        if (!check.lost.isEmpty())
        {
            return DetachPlan.refused("Branch '" + target.shortName() + "' is the branch EDT reads now, so EDT keeps " //$NON-NLS-1$ //$NON-NLS-2$
                + "this project's other bindings to it, but it deletes the same-named binding file of each dependent " //$NON-NLS-1$
                + "extension project that does not read this branch now (one in another repository, or one EDT sees " //$NON-NLS-1$
                + "no git repository for), so detaching application '" + applicationId + "' here would also remove: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("; ", check.lost) + ". Nothing was changed. EDT keeps such a file only while the " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project reads this same branch (the branch checked out in its own repository)." //$NON-NLS-1$
                + check.hiddenEntriesNote());
        }
        return DetachPlan.of(ctx, null, () -> {
            String dependentsChanged = check.recheckDependents(assocManager, provider, dependents, project, ctx,
                target.fullRef());
            return dependentsChanged != null ? dependentsChanged
                : checkoutUnchanged(provider, project, target, readNow);
        });
    }

    /**
     * The way out of a refused detach from a branch that is not checked out.
     * <ul>
     * <li>A checkout helps only when EDT maps the project to a repository. When the context EDT reads now is
     * {@code empty()}, EDT reads the project-level bindings and no checkout makes the branch the one it
     * reads (a repository EGit does not map, even one {@code GitRepositoryResolver} finds), so checkout
     * advice there could never work.</li>
     * <li>After a checkout EDT rewrites this project's file without the binding, but it still deletes the
     * same-named file of a dependent extension project whose own current context does not follow the
     * checkout (another repository), so that detach is refused too ({@link #planCurrentBranchDetach}). So
     * "only this binding" is claimed only when no dependent binding was listed; otherwise the advice says
     * the detach from the checked-out branch goes through only once such an extension reads that branch
     * too.</li>
     * </ul>
     */
    private static String notCheckedOutRemedy(BranchContexts.Target target, InfobaseAssociationContext current,
        boolean dependentLost)
    {
        if (current.getContext().isEmpty())
        {
            return "EDT does not see a git repository for this project (it reads the project's own bindings, " //$NON-NLS-1$
                + "not a branch's), so no checkout makes branch '" + target.shortName() + "' the one it reads, " //$NON-NLS-1$ //$NON-NLS-2$
                + "and this binding cannot be removed alone until EDT sees the repository."; //$NON-NLS-1$
        }
        String remedy = "Check the branch out (switch_git_branch '" + target.shortName() //$NON-NLS-1$
            + "') and detach again: from the checked-out branch EDT "; //$NON-NLS-1$
        if (!dependentLost)
        {
            return remedy + "removes only this binding."; //$NON-NLS-1$
        }
        return remedy + "keeps this project's other bindings, but it still deletes the binding file of a dependent " //$NON-NLS-1$
            + "extension project listed above that does not follow the checkout (one in another repository), so that " //$NON-NLS-1$
            + "detach is refused as well unless the extension reads this branch too."; //$NON-NLS-1$
    }

    /**
     * The detach of a binding stored under the legacy short key; see {@link #planDetach}. EDT never reads a
     * legacy key, so it is never the current context and EDT always removes a binding from it by deleting
     * its whole file. A legacy entry is dead and harmless where it is, so the detach goes through only when
     * it deletes nothing but this binding, and is refused otherwise: no call can remove one binding of such
     * a file without the others.
     */
    private static DetachPlan planLegacyDetach(IInfobaseAssociationManager assocManager,
        Supplier<Collection<IProject>> dependents, IProject project, String applicationId,
        BranchContexts.Target target, UUID uuid, List<InfobaseReference> inLegacy)
    {
        InfobaseAssociationContext legacy = target.legacyContext();
        WholeFileCheck check = checkWholeFile(assocManager, null, dependents, project, applicationId, target, legacy,
            uuid, inLegacy, true, null);
        if (check.refusal != null)
        {
            return DetachPlan.refused(check.refusal);
        }
        if (!check.lost.isEmpty())
        {
            return DetachPlan.refused("Application '" + applicationId + "' is bound only under the legacy short " //$NON-NLS-1$ //$NON-NLS-2$
                + "key '" + target.shortName() + "' (written by an older version of this tool), not under '" //$NON-NLS-1$ //$NON-NLS-2$
                + target.fullRef() + "'. EDT never reads legacy entries: they are dead and harmless where they " //$NON-NLS-1$
                + "are. But EDT removes a binding from such a key by deleting the key's whole binding file, so " //$NON-NLS-1$
                + "detaching it would also remove: " + String.join("; ", check.lost) //$NON-NLS-1$ //$NON-NLS-2$
                + ". There is no way to remove one binding of such a file without the others, so nothing was " //$NON-NLS-1$
                + "changed; leave the legacy entry in place." + check.hiddenEntriesNote()); //$NON-NLS-1$
        }
        if (check.ownProblem != null)
        {
            return DetachPlan.refused(check.ownProblem);
        }
        return DetachPlan.of(legacy, "Removed the binding stored under the legacy short key '" + target.shortName() //$NON-NLS-1$
            + "' (written by an older version of this tool; EDT never read it as a branch " //$NON-NLS-1$
            + "context). No binding existed under '" + target.fullRef() + "'. That key's binding file held no " //$NON-NLS-1$ //$NON-NLS-2$
            + "other binding, so no other binding was removed.", //$NON-NLS-1$
            () -> check.recheck(assocManager, null, dependents, project, legacy, target.shortName(), uuid));
    }

    /**
     * What deleting the whole binding file of {@code ctx} would take besides the binding being removed - read
     * from both sources, because neither alone shows it all:
     * <ul>
     * <li>EDT's read ({@code getAssociation}, {@code getAssociationContexts}): the other infobases bound
     * there, the branches that keep their binding as a reference to this one, and the dependent extension
     * projects bound there. It shows only infobases EDT can resolve.</li>
     * <li>The persisted files, located as EDT locates them ({@link AssociationStoreFile}): every entry EDT
     * does not show - a UUID no registered infobase has (stale: EDT drops it on every read, but it is still
     * a record, and it binds again once a base with that UUID is registered), a value it cannot parse, a
     * key it does not write. The project's own file must be found where EDT keeps it, since EDT shows the
     * binding there; a dependent without a working location has no file EDT could delete.</li>
     * </ul>
     * A dependent extension project counts only when its own current context differs from {@code ctx}
     * (EDT deletes its file only then); a legacy key is never anyone's current context.
     *
     * @param current the context EDT reads now for the project ({@code null} for a legacy key: only used to
     *            word the referencing-branch refusal)
     */
    private static WholeFileCheck checkWholeFile(IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
        String applicationId, BranchContexts.Target target, InfobaseAssociationContext ctx, UUID uuid,
        List<InfobaseReference> inContext, boolean legacyKey, InfobaseAssociationContext current)
    {
        WholeFileCheck check = new WholeFileCheck();
        String label = legacyKey ? target.shortName() : target.fullRef();
        String ownFile = legacyKey ? "this key's binding file" : "this branch's binding file"; //$NON-NLS-1$ //$NON-NLS-2$
        String under = legacyKey ? "under this key" : "to this branch"; //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Collection<InfobaseAssociationContext> contexts = assocManager.getAssociationContexts(project);
            if (contexts != null)
            {
                for (InfobaseAssociationContext stored : contexts)
                {
                    if (stored == null || stored.getReference().isEmpty())
                    {
                        continue;
                    }
                    InfobaseAssociationContext referenced = stored.getReference().get();
                    if (ctx.getContext().equals(stored.getContext()))
                    {
                        check.refusal = sharesAnotherBranchRefusal(applicationId, target, referenced, current);
                        return check;
                    }
                    if (ctx.getContext().equals(referenced.getContext()))
                    {
                        check.lost.add("the binding of branch '" + referencingLabel(stored) //$NON-NLS-1$
                            + "', which shares " + ownFile); //$NON-NLS-1$
                    }
                }
            }
            Set<UUID> known = new HashSet<>();
            known.add(uuid);
            for (InfobaseReference sibling : inContext)
            {
                if (!uuid.equals(sibling.getUuid()))
                {
                    check.lost.add("infobase '" + sibling.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (sibling.getUuid() != null)
                {
                    known.add(sibling.getUuid());
                }
            }
            Path own = AssociationStoreFile.locate(project, ctx);
            if (own == null)
            {
                check.ownProblem = ownFileProblem(applicationId, label, "the project has no EDT working location"); //$NON-NLS-1$
            }
            else if (!Files.isRegularFile(own))
            {
                check.ownProblem = ownFileProblem(applicationId, label,
                    "its binding file is not where EDT keeps it (" + own + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                check.ownFile = own;
                for (String entry : AssociationStoreFile.entriesBesides(own, known))
                {
                    check.lost.add("an entry EDT does not show in " + ownFile + ": " + entry); //$NON-NLS-1$ //$NON-NLS-2$
                    check.hiddenListed = true;
                }
            }
            checkDependents(check, assocManager, provider, dependents, ctx, legacyKey, under);
        }
        catch (RuntimeException | IOException e)
        {
            check.refusal = readFailure(project, label, e);
        }
        return check;
    }

    /**
     * The dependent extension projects' part of a detach: EDT deletes the same-named file of each one whose own
     * current context differs from {@code ctx}, whatever the project's own context. Records every dependent,
     * each empty file and each dependent spared because it reads {@code ctx} now, for the re-check
     * ({@link WholeFileCheck#recheckDependents}); stops at the first refusal.
     *
     * @param legacyKey whether {@code ctx} is a legacy short key, which is never anyone's current context
     */
    private static void checkDependents(WholeFileCheck check, IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents,
        InfobaseAssociationContext ctx, boolean legacyKey, String under) throws IOException
    {
        check.legacyKey = legacyKey;
        for (IProject dependent : dependents.get())
        {
            check.plannedDependents.add(dependent);
            checkDependent(check, assocManager, provider, dependent, ctx, legacyKey, under);
            if (check.refusal != null)
            {
                return;
            }
        }
    }

    /** One dependent extension project's part of {@link #checkDependents}. */
    private static void checkDependent(WholeFileCheck check, IInfobaseAssociationManager assocManager,
        IInfobaseAssociationContextProvider provider, IProject dependent, InfobaseAssociationContext ctx,
        boolean legacyKey, String under) throws IOException
    {
        List<InfobaseReference> inDependent = boundInfobases(assocManager, dependent, ctx);
        Set<UUID> known = new HashSet<>();
        for (InfobaseReference ib : inDependent)
        {
            if (ib.getUuid() != null)
            {
                known.add(ib.getUuid());
            }
        }
        Path file = AssociationStoreFile.locate(dependent, ctx);
        List<String> hidden = file == null ? List.of() : AssociationStoreFile.entriesBesides(file, known);
        InfobaseAssociationContext reference = referenceOf(assocManager, dependent, ctx);
        if (inDependent.isEmpty() && hidden.isEmpty() && reference == null)
        {
            if (file != null)
            {
                check.emptyDependentFiles.put(file, dependent);
            }
            return;
        }
        if (!legacyKey)
        {
            BranchContexts.Current dependentCurrent = BranchContexts.current(provider, dependent);
            if (!dependentCurrent.ok())
            {
                check.refusal = dependentCurrent.error();
                return;
            }
            if (ctx.equals(dependentCurrent.context()))
            {
                // EDT keeps this file only while the extension still reads ctx: re-checked right before the write.
                check.currentDependents.add(dependent);
                return;
            }
        }
        String extension = "extension project '" + dependent.getName() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        if (!inDependent.isEmpty())
        {
            check.lost.add("the binding of " + extension + " " + under + " (" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + String.join(", ", names(inDependent)) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (reference != null)
        {
            check.lost.add("the binding of " + extension + " " + under + ", which EDT keeps as a reference to " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "branch '" + BranchContexts.displayName(reference) + "' (EDT would delete that branch's binding " //$NON-NLS-1$ //$NON-NLS-2$
                + "file of the extension)"); //$NON-NLS-1$
        }
        for (String entry : hidden)
        {
            check.lost.add("an entry EDT does not show in the binding file of " + extension + " " + under + ": " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + entry);
            check.hiddenListed = true;
        }
        check.dependentLost = true;
    }

    /** The context {@code project} keeps {@code ctx} as a reference to, or {@code null} when it keeps none. */
    private static InfobaseAssociationContext referenceOf(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        Collection<InfobaseAssociationContext> contexts = assocManager.getAssociationContexts(project);
        if (contexts != null)
        {
            for (InfobaseAssociationContext stored : contexts)
            {
                if (stored != null && stored.getReference().isPresent()
                    && ctx.getContext().equals(stored.getContext()))
                {
                    return stored.getReference().get();
                }
            }
        }
        return null;
    }

    /**
     * A stored branch reference of {@code project} that {@link #checkWholeFile} would have refused for
     * {@code ctx} - {@code ctx} kept as a reference to another branch, or another branch kept as a reference to
     * {@code ctx} - described for the re-check, or {@code null} when there is none. The plan refused on any, so
     * one found by the re-check appeared after the plan.
     */
    private static String referenceChange(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        Collection<InfobaseAssociationContext> contexts = assocManager.getAssociationContexts(project);
        if (contexts != null)
        {
            for (InfobaseAssociationContext stored : contexts)
            {
                if (stored == null || stored.getReference().isEmpty())
                {
                    continue;
                }
                InfobaseAssociationContext referenced = stored.getReference().get();
                if (ctx.getContext().equals(stored.getContext()))
                {
                    return "its binding is now kept as a reference to branch '" //$NON-NLS-1$
                        + BranchContexts.displayName(referenced) + "'"; //$NON-NLS-1$
                }
                if (ctx.getContext().equals(referenced.getContext()))
                {
                    return "branch '" + referencingLabel(stored) //$NON-NLS-1$
                        + "' now keeps its binding as a reference to this binding"; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /** The refusal when the project's own binding file cannot be found or read where EDT keeps it. */
    private static String ownFileProblem(String applicationId, String label, String reason)
    {
        return "Could not check what else a detach of application '" + applicationId + "' from '" + label //$NON-NLS-1$ //$NON-NLS-2$
            + "' would delete: EDT shows the binding there, but " + reason + ". EDT removes it by deleting " //$NON-NLS-1$ //$NON-NLS-2$
            + "that whole file, and what else the file holds cannot be shown, so the detach is refused. " //$NON-NLS-1$
            + "Nothing was changed."; //$NON-NLS-1$
    }

    /** A detach whose plan no longer holds when it is re-checked right before EDT's write. */
    private static String changedRefusal(String label, String what)
    {
        return "The bindings changed while this detach from '" + label + "' was being checked: " + what //$NON-NLS-1$ //$NON-NLS-2$
            + ". EDT or another tool changed them in the meantime. Nothing was changed; check list_git_branches " //$NON-NLS-1$
            + "and detach again."; //$NON-NLS-1$
    }

    /** The re-check of a detach from the branch EDT reads now: EDT must still read it. */
    private static String checkoutUnchanged(IInfobaseAssociationContextProvider provider, IProject project,
        BranchContexts.Target target, InfobaseAssociationContext readNow)
    {
        BranchContexts.Current now = BranchContexts.current(provider, project);
        if (!now.ok())
        {
            return now.error();
        }
        if (!readNow.equals(now.context()))
        {
            return changedRefusal(target.fullRef(), "the branch EDT reads for this project changed from '" //$NON-NLS-1$
                + BranchContexts.displayName(readNow) + "' to '" + BranchContexts.displayName(now.context()) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /** The refusal for a branch whose binding is a reference to another branch's binding (C5). */
    private static String sharesAnotherBranchRefusal(String applicationId, BranchContexts.Target target,
        InfobaseAssociationContext referenced, InfobaseAssociationContext current)
    {
        String referencedName = BranchContexts.displayName(referenced);
        boolean readNow = current != null && referenced.getContext().equals(current.getContext());
        return "Branch '" + target.shortName() + "' has no binding file of its own: EDT keeps its binding as a " //$NON-NLS-1$ //$NON-NLS-2$
            + "reference to branch '" + referencedName + "' (EDT's source-branch infobase option), so detaching " //$NON-NLS-1$ //$NON-NLS-2$
            + "application '" + applicationId + "' here would change the binding of '" + referencedName //$NON-NLS-1$ //$NON-NLS-2$
            + "' and of every branch that shares it. Nothing was changed. Detach it from branch '" //$NON-NLS-1$
            + referencedName + "' instead" //$NON-NLS-1$
            + (readNow ? ", the binding EDT reads now." : "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A referencing context's own name, shown like a branch: {@code refs/heads/X} as {@code X}. */
    private static String referencingLabel(InfobaseAssociationContext referencing)
    {
        String raw = referencing.getContext().orElse(BranchContexts.DEFAULT_CONTEXT_LABEL);
        return raw.startsWith(BranchContexts.REFS_HEADS) ? raw.substring(BranchContexts.REFS_HEADS.length()) : raw;
    }

    private static String readFailure(IProject project, String context, Exception failure)
    {
        Activator.logError("set_branch_infobase: reading the bindings of project '" + project.getName() //$NON-NLS-1$
            + "' for context '" + context + "' failed", failure); //$NON-NLS-1$ //$NON-NLS-2$
        return "Could not read the infobase bindings that a detach from '" + context + "' would touch: " //$NON-NLS-1$ //$NON-NLS-2$
            + PlatformFailures.describe(failure) + ". Nothing was changed."; //$NON-NLS-1$
    }

    /**
     * The infobases bound to {@code project} under {@code ctx}, resolved by EDT (stale entries dropped);
     * empty when none. A read failure propagates.
     */
    private static List<InfobaseReference> boundInfobases(IInfobaseAssociationManager assocManager,
        IProject project, InfobaseAssociationContext ctx)
    {
        Optional<IInfobaseAssociation> association =
            Optional.ofNullable(assocManager.getAssociation(project, ctx)).flatMap(value -> value);
        List<InfobaseReference> bound = new ArrayList<>();
        if (association.isPresent() && association.get().getInfobases() != null)
        {
            for (InfobaseReference ib : association.get().getInfobases())
            {
                if (ib != null)
                {
                    bound.add(ib);
                }
            }
        }
        return bound;
    }

    private static boolean containsUuid(List<InfobaseReference> bound, UUID uuid)
    {
        for (InfobaseReference ib : bound)
        {
            if (uuid.equals(ib.getUuid()))
            {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(List<InfobaseReference> bound)
    {
        List<String> names = new ArrayList<>();
        for (InfobaseReference ib : bound)
        {
            names.add(ib.getName());
        }
        return names;
    }

    /** What {@link #checkWholeFile} found; the plan built from it re-checks what it relied on. */
    private static final class WholeFileCheck
    {
        /** A refusal decided outright (a read failure, a referencing branch), or {@code null}. */
        String refusal;

        /** What the deletion would take besides the binding being removed. */
        final List<String> lost = new ArrayList<>();

        /** Whether a dependent extension project's binding is among {@link #lost}. */
        boolean dependentLost;

        /** Whether a persisted entry EDT does not show is among {@link #lost}. */
        boolean hiddenListed;

        /** Why the project's own file could not be checked, or {@code null}. */
        String ownProblem;

        /** The project's own file, checked to hold nothing but the binding being removed. */
        Path ownFile;

        /** Dependents' files EDT would delete, checked to hold nothing, with the project they belong to. */
        final Map<Path, IProject> emptyDependentFiles = new LinkedHashMap<>();

        /** Every dependent extension project the plan checked. */
        final List<IProject> plannedDependents = new ArrayList<>();

        /** Whether the context is a legacy short key, which is never anyone's current context. */
        boolean legacyKey;

        /**
         * Dependents whose file holds something but EDT keeps it because they read the context now: they must
         * still read it right before the write.
         */
        final List<IProject> currentDependents = new ArrayList<>();

        /** The note explaining the entries EDT does not show, when any is listed. */
        String hiddenEntriesNote()
        {
            if (!hiddenListed)
            {
                return ""; //$NON-NLS-1$
            }
            return " An entry EDT does not show is still a record in the file: an infobase entry is a UUID no " //$NON-NLS-1$
                + "infobase registered in EDT has (EDT drops it when it reads the file, binds it again once a base " //$NON-NLS-1$
                + "with that UUID is registered, and drops it for good whenever it rewrites the file), and any " //$NON-NLS-1$
                + "other entry is a key EDT does not write."; //$NON-NLS-1$
        }

        /**
         * Re-reads, right before EDT's write of a whole-file deletion, what this check relied on: no stored branch
         * of the project may keep a reference to or from {@code ctx}, the dependents must still hold
         * ({@link #recheckDependents}), and - the last read, of the file the write deletes - the project's own
         * file must still hold nothing but {@code uuid}.
         *
         * @return the refusal when that no longer holds, otherwise {@code null}
         */
        String recheck(IInfobaseAssociationManager assocManager, IInfobaseAssociationContextProvider provider,
            Supplier<Collection<IProject>> dependents, IProject project, InfobaseAssociationContext ctx, String label,
            UUID uuid)
        {
            try
            {
                String referenceNow = referenceChange(assocManager, project, ctx);
                if (referenceNow != null)
                {
                    return changedRefusal(label, referenceNow);
                }
            }
            catch (RuntimeException e)
            {
                return readFailure(project, label, e);
            }
            String dependentsChanged = recheckDependents(assocManager, provider, dependents, project, ctx, label);
            if (dependentsChanged != null)
            {
                return dependentsChanged;
            }
            try
            {
                if (!Files.isRegularFile(ownFile))
                {
                    return changedRefusal(label, "its binding file is gone"); //$NON-NLS-1$
                }
                List<String> now = AssociationStoreFile.entriesBesides(ownFile, Set.of(uuid));
                if (!now.isEmpty())
                {
                    return changedRefusal(label, "its binding file now also holds: " + String.join(", ", now)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (RuntimeException | IOException e)
            {
                return readFailure(project, label, e);
            }
            return null;
        }

        /**
         * Re-reads, right before EDT's write, what the dependents' part of this check relied on
         * ({@link SetBranchInfobaseTool#checkDependents}), in this order:
         * <ol>
         * <li>no dependent extension project the plan did not check;</li>
         * <li>the branch each dependent reads now - the slow part, a question to EDT per project, so it comes before
         * the files. EDT keeps the file of a dependent that reads {@code ctx}, whatever it holds; a dependent the
         * plan spared because it read {@code ctx} must still read it - a checkout of the extension in between would
         * make EDT delete its file. A legacy key is never anyone's current context, so none is read for it;</li>
         * <li>then the file of each dependent EDT would delete whose file the plan found empty: it must still hold
         * nothing, with no reference of the dependent for {@code ctx}. Its branch is not needed for that - an empty
         * file loses nothing whichever branch the extension reads - so one EDT cannot name is judged by its
         * file.</li>
         * </ol>
         *
         * @return the refusal when that no longer holds, otherwise {@code null}
         */
        String recheckDependents(IInfobaseAssociationManager assocManager,
            IInfobaseAssociationContextProvider provider, Supplier<Collection<IProject>> dependents, IProject project,
            InfobaseAssociationContext ctx, String label)
        {
            try
            {
                for (IProject dependent : dependents.get())
                {
                    if (!plannedDependents.contains(dependent))
                    {
                        return changedRefusal(label, "extension project '" + dependent.getName() //$NON-NLS-1$
                            + "' now depends on this project"); //$NON-NLS-1$
                    }
                }
                Set<IProject> readingCtx = new HashSet<>();
                for (IProject dependent : plannedDependents)
                {
                    boolean spared = currentDependents.contains(dependent);
                    if (legacyKey || !spared && !emptyDependentFiles.containsValue(dependent))
                    {
                        continue;
                    }
                    BranchContexts.Current now = BranchContexts.current(provider, dependent);
                    if (now.ok() && ctx.equals(now.context()))
                    {
                        readingCtx.add(dependent);
                    }
                    else if (spared)
                    {
                        return !now.ok() ? now.error()
                            : changedRefusal(label, "extension project '" + dependent.getName() //$NON-NLS-1$
                                + "' no longer reads this branch (it reads '" + BranchContexts.displayName(now.context()) //$NON-NLS-1$
                                + "' now), so EDT would delete the binding it keeps for this branch"); //$NON-NLS-1$
                    }
                }
                for (Map.Entry<Path, IProject> dependentFile : emptyDependentFiles.entrySet())
                {
                    if (readingCtx.contains(dependentFile.getValue()))
                    {
                        continue;
                    }
                    String extension = dependentFile.getValue().getName();
                    List<String> dependentNow = AssociationStoreFile.entriesBesides(dependentFile.getKey(), Set.of());
                    if (!dependentNow.isEmpty())
                    {
                        return changedRefusal(label, "the binding file of extension project '" //$NON-NLS-1$
                            + extension + "' now holds: " + String.join(", ", dependentNow)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    InfobaseAssociationContext dependentReference = referenceOf(assocManager, dependentFile.getValue(),
                        ctx);
                    if (dependentReference != null)
                    {
                        return changedRefusal(label, "extension project '" + extension //$NON-NLS-1$
                            + "' now keeps its binding here as a reference to branch '" //$NON-NLS-1$
                            + BranchContexts.displayName(dependentReference) + "'"); //$NON-NLS-1$
                    }
                }
                return null;
            }
            catch (RuntimeException | IOException e)
            {
                return readFailure(project, label, e);
            }
        }
    }

    /**
     * The outcome of {@link #planDetach}: the context to dissociate in, the answer's note and the re-check to
     * run right before EDT's write - or a refusal.
     */
    private static final class DetachPlan
    {
        private final InfobaseAssociationContext context;

        private final String note;

        private final String refusal;

        private final Supplier<String> recheck;

        private DetachPlan(InfobaseAssociationContext context, String note, String refusal, Supplier<String> recheck)
        {
            this.context = context;
            this.note = note;
            this.refusal = refusal;
            this.recheck = recheck;
        }

        static DetachPlan of(InfobaseAssociationContext context, String note, Supplier<String> recheck)
        {
            return new DetachPlan(context, note, null, recheck);
        }

        static DetachPlan refused(String refusal)
        {
            return new DetachPlan(null, null, refusal, null);
        }

        InfobaseAssociationContext context()
        {
            return context;
        }

        String note()
        {
            return note;
        }

        String refusal()
        {
            return refusal;
        }

        /**
         * Runs the re-check, under the same lock as the plan and EDT's write, right before the write: what the
         * plan relied on must still hold.
         *
         * @return the refusal when it no longer holds, otherwise {@code null}
         */
        String recheck()
        {
            return recheck == null ? null : recheck.get();
        }
    }

    /**
     * Best-effort read-back of the branch context's bindings after the change -
     * proof the base is now bound (attach) or gone (detach). Any failure (manager
     * absent, {@link InfobaseAssociationException}) yields an empty map (the
     * caller then omits the {@code bound} key) rather than failing the
     * already-successful change.
     */
    private static Map<String, Object> readBack(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        try
        {
            Optional<IInfobaseAssociation> assoc = assocManager.getAssociation(project, ctx);
            List<String> names = new ArrayList<>();
            String defaultName = null;
            if (assoc.isPresent())
            {
                Collection<InfobaseReference> infobases = assoc.get().getInfobases();
                if (infobases != null)
                {
                    for (InfobaseReference ib : infobases)
                    {
                        if (ib != null)
                        {
                            names.add(ib.getName());
                        }
                    }
                }
                InfobaseReference def = assoc.get().getDefaultInfobase();
                defaultName = def != null ? def.getName() : null;
            }
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put(KEY_INFOBASES, names);
            bound.put(KEY_DEFAULT_INFOBASE, defaultName);
            return bound;
        }
        catch (RuntimeException e)
        {
            Activator.logError("set_branch_infobase: bindings read-back failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    /**
     * Resolves {@code applicationId} to an {@link IApplication} (mirrors how
     * {@code SetInfobaseCredentialsTool} / {@code CreateLaunchConfigTool} resolve an
     * applicationId) and then its {@link InfobaseReference} via
     * {@link InfobaseAccessSupport#resolveInfobaseReference(IApplication)}.
     */
    private static ApplicationReferenceResolution resolveApplicationReference(IProject project, String applicationId)
    {
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ApplicationReferenceResolution.error(ToolResult.error(
                "IApplicationManager service is not available. EDT may still be starting up — retry " //$NON-NLS-1$
                + "in a moment.").toJson()); //$NON-NLS-1$
        }
        // Bounded (#622): an unbounded lookup would hold this call open with nothing bound.
        ApplicationSupport.BoundedRead<Optional<IApplication>> read =
            ApplicationSupport.getApplicationBounded(appManager, project, applicationId,
                ApplicationSupport.LOOKUP_TIMEOUT_MS);
        if (!read.concluded())
        {
            Activator.logError("set_branch_infobase: " + read.deadlineFailure(), null); //$NON-NLS-1$
            return ApplicationReferenceResolution.error(ToolResult.error("Could not resolve application '" //$NON-NLS-1$
                + applicationId + "': " + read.deadlineFailure() //$NON-NLS-1$
                + ". Nothing was bound.").toJson()); //$NON-NLS-1$
        }
        Optional<IApplication> appOpt;
        try
        {
            appOpt = read.valueOrRethrow();
        }
        catch (Exception e) // NOSONAR EDT application lookup — surface as an actionable error
        {
            Activator.logError("set_branch_infobase: error resolving application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ApplicationReferenceResolution.error(ToolResult.error("Could not resolve application '" //$NON-NLS-1$
                + applicationId + "': " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
        if (appOpt.isEmpty())
        {
            return ApplicationReferenceResolution.error(ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                + ". Use get_applications to get valid application IDs.").toJson()); //$NON-NLS-1$
        }
        InfobaseReference ref = InfobaseAccessSupport.resolveInfobaseReference(appOpt.get());
        if (ref == null)
        {
            return ApplicationReferenceResolution.error(ToolResult.error("Application '" + applicationId //$NON-NLS-1$
                + "' has no infobase reference — it cannot be bound to a branch context.").toJson()); //$NON-NLS-1$
        }
        return ApplicationReferenceResolution.resolved(ref);
    }

    /**
     * Outcome of {@link #resolveApplicationReference}: either the resolved
     * {@link InfobaseReference}, or an actionable error tool-result JSON.
     */
    private static final class ApplicationReferenceResolution
    {
        private final InfobaseReference reference;

        private final String error;

        private ApplicationReferenceResolution(InfobaseReference reference, String error)
        {
            this.reference = reference;
            this.error = error;
        }

        static ApplicationReferenceResolution resolved(InfobaseReference reference)
        {
            return new ApplicationReferenceResolution(reference, null);
        }

        static ApplicationReferenceResolution error(String error)
        {
            return new ApplicationReferenceResolution(null, error);
        }

        InfobaseReference reference()
        {
            return reference;
        }

        String error()
        {
            return error;
        }
    }
}
