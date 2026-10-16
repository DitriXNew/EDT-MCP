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
 * A detach from a branch that is NOT checked out is guarded (#656): EDT removes a
 * binding from a context other than the one it reads now
 * ({@link BranchContexts#current(IInfobaseAssociationContextProvider, IProject)}) by
 * deleting that context's whole binding file, and the same-named file of each dependent
 * extension project whose own current context differs. Such a detach runs only when that
 * deletion can take nothing but the binding being removed; see
 * {@link #applyResolved}.
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
     * it. A detach from the branch EDT reads now goes through as before. A detach from a branch that is
     * not checked out runs only when deleting that branch's whole binding file - which is how EDT removes
     * a binding from a context it does not read now - can take nothing else; otherwise it is refused,
     * naming what would be lost. A legacy short key is never read by EDT, so its detach always goes
     * through, and the message names the other bindings stored under it, which may go with it.
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
        BranchContexts.Target target = resolution.target();
        InfobaseAssociationContext ctx = target.context();
        String note = null;

        if (!attach)
        {
            DetachPlan plan = planDetach(assocManager, provider, dependents, project, applicationId, target, ref);
            if (plan.refusal() != null)
            {
                return ToolResult.error(plan.refusal()).toJson();
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
     * <li>When the branch's context is the one EDT reads now, the detach goes through: EDT rewrites the
     * file without this binding.</li>
     * <li>Otherwise EDT deletes the context's whole binding file - and, for every dependent extension
     * project whose own current context differs, that project's same-named file - when it holds a
     * synchronization for the project, which on a running EDT it practically always does. The detach
     * then runs only when that deletion can take nothing else: no other base in the branch's file, no
     * dependent extension binding under the branch, and the branch neither references another branch's
     * binding nor is referenced by one (the file would be the shared one).</li>
     * <li>A legacy short key is never the current context, so its file always goes as a whole; it is
     * dead (EDT never reads it), so the detach goes through and the note names what went with it.</li>
     * <li>Any failure to read one of these facts refuses the detach: nothing after the call shows a loss
     * (EDT logs none), so an unread fact cannot be reported later.</li>
     * </ul>
     * Known residual, as in EDT's own Delete: a detach from the CURRENT context still deletes the
     * same-named file of a dependent extension project whose own current context differs (another
     * repository or branch).
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
                return planLegacyDetach(assocManager, dependents, project, target, uuid, inLegacy);
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
        if (ctx.equals(current.context()))
        {
            return DetachPlan.of(ctx, null);
        }

        List<String> lost = new ArrayList<>();
        boolean dependentLost = false;
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
                        return DetachPlan.refused(sharesAnotherBranchRefusal(applicationId, target, referenced,
                            current.context()));
                    }
                    if (ctx.getContext().equals(referenced.getContext()))
                    {
                        lost.add("the binding of branch '" + referencingLabel(stored) //$NON-NLS-1$
                            + "', which shares this branch's binding file"); //$NON-NLS-1$
                    }
                }
            }
            for (InfobaseReference sibling : inBranch)
            {
                if (!uuid.equals(sibling.getUuid()))
                {
                    lost.add("infobase '" + sibling.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            for (IProject dependent : dependents.get())
            {
                List<InfobaseReference> inDependent = boundInfobases(assocManager, dependent, ctx);
                if (inDependent.isEmpty())
                {
                    continue;
                }
                BranchContexts.Current dependentCurrent = BranchContexts.current(provider, dependent);
                if (!dependentCurrent.ok())
                {
                    return DetachPlan.refused(dependentCurrent.error());
                }
                if (!ctx.equals(dependentCurrent.context()))
                {
                    lost.add("the binding of extension project '" + dependent.getName() + "' to this branch (" //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", names(inDependent)) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    dependentLost = true;
                }
            }
        }
        catch (RuntimeException e)
        {
            return DetachPlan.refused(readFailure(project, target.fullRef(), e));
        }

        if (!lost.isEmpty())
        {
            return DetachPlan.refused("Branch '" + target.shortName() + "' is not checked out. EDT may remove a " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding from a branch that is not checked out by deleting the branch's whole binding file, " //$NON-NLS-1$
                + "so detaching application '" + applicationId + "' here may also remove: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join("; ", lost) + ". Nothing was changed. " //$NON-NLS-1$
                + notCheckedOutRemedy(target, current.context(), dependentLost));
        }
        return DetachPlan.of(ctx, "Branch '" + target.shortName() + "' is not checked out, and EDT may remove a " //$NON-NLS-1$ //$NON-NLS-2$
            + "binding from such a branch by deleting its whole binding file; that file held no other " //$NON-NLS-1$
            + "binding, so no other binding was removed."); //$NON-NLS-1$
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
     * checkout (another repository) - the residual {@link #planDetach} names. So "only this binding" is
     * claimed only when no dependent binding was listed; otherwise the residual is named.</li>
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
        return remedy + "keeps this project's other bindings, but a dependent extension project listed above " //$NON-NLS-1$
            + "that does not follow the checkout (one in another repository) still loses its binding to this " //$NON-NLS-1$
            + "branch."; //$NON-NLS-1$
    }

    /** The detach of a binding stored under the legacy short key; see {@link #planDetach}. */
    private static DetachPlan planLegacyDetach(IInfobaseAssociationManager assocManager,
        Supplier<Collection<IProject>> dependents, IProject project, BranchContexts.Target target, UUID uuid,
        List<InfobaseReference> inLegacy)
    {
        InfobaseAssociationContext legacy = target.legacyContext();
        List<String> alsoHeld = new ArrayList<>();
        for (InfobaseReference sibling : inLegacy)
        {
            if (!uuid.equals(sibling.getUuid()))
            {
                alsoHeld.add("infobase '" + sibling.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        try
        {
            for (IProject dependent : dependents.get())
            {
                List<InfobaseReference> inDependent = boundInfobases(assocManager, dependent, legacy);
                if (!inDependent.isEmpty())
                {
                    alsoHeld.add("the binding of extension project '" + dependent.getName() + "' under it (" //$NON-NLS-1$ //$NON-NLS-2$
                        + String.join(", ", names(inDependent)) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        catch (RuntimeException e)
        {
            return DetachPlan.refused(readFailure(project, target.shortName(), e));
        }
        String note = "Removed the binding stored under the legacy short key '" + target.shortName() //$NON-NLS-1$
            + "' (written by an older version of this tool; EDT never read it as a branch " //$NON-NLS-1$
            + "context). No binding existed under '" + target.fullRef() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        if (!alsoHeld.isEmpty())
        {
            note += " EDT may remove such a key by deleting its whole binding file, so these other bindings " //$NON-NLS-1$
                + "stored under it may be gone too (EDT never read them either): " + String.join("; ", alsoHeld) //$NON-NLS-1$ //$NON-NLS-2$
                + "."; //$NON-NLS-1$
        }
        return DetachPlan.of(legacy, note);
    }

    /** The refusal for a branch whose binding is a reference to another branch's binding (C5). */
    private static String sharesAnotherBranchRefusal(String applicationId, BranchContexts.Target target,
        InfobaseAssociationContext referenced, InfobaseAssociationContext current)
    {
        String referencedName = BranchContexts.displayName(referenced);
        return "Branch '" + target.shortName() + "' has no binding file of its own: EDT keeps its binding as a " //$NON-NLS-1$ //$NON-NLS-2$
            + "reference to branch '" + referencedName + "' (EDT's source-branch infobase option), so detaching " //$NON-NLS-1$ //$NON-NLS-2$
            + "application '" + applicationId + "' here would change the binding of '" + referencedName //$NON-NLS-1$ //$NON-NLS-2$
            + "' and of every branch that shares it. Nothing was changed. Detach it from branch '" //$NON-NLS-1$
            + referencedName + "' instead" //$NON-NLS-1$
            + (referenced.getContext().equals(current.getContext()) ? ", the binding EDT reads now." : "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A referencing context's own name, shown like a branch: {@code refs/heads/X} as {@code X}. */
    private static String referencingLabel(InfobaseAssociationContext referencing)
    {
        String raw = referencing.getContext().orElse(BranchContexts.DEFAULT_CONTEXT_LABEL);
        return raw.startsWith(BranchContexts.REFS_HEADS) ? raw.substring(BranchContexts.REFS_HEADS.length()) : raw;
    }

    private static String readFailure(IProject project, String context, RuntimeException failure)
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
        Optional<IInfobaseAssociation> association = assocManager.getAssociation(project, ctx);
        List<InfobaseReference> bound = new ArrayList<>();
        if (association != null && association.isPresent() && association.get().getInfobases() != null)
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

    /** The outcome of {@link #planDetach}: the context to dissociate in plus the answer's note, or a refusal. */
    private static final class DetachPlan
    {
        private final InfobaseAssociationContext context;

        private final String note;

        private final String refusal;

        private DetachPlan(InfobaseAssociationContext context, String note, String refusal)
        {
            this.context = context;
            this.note = note;
            this.refusal = refusal;
        }

        static DetachPlan of(InfobaseAssociationContext context, String note)
        {
            return new DetachPlan(context, note, null);
        }

        static DetachPlan refused(String refusal)
        {
            return new DetachPlan(null, null, refusal);
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
