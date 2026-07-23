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

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
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
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Attaches or detaches an EXISTING infobase (application) to/from a specific git
 * branch <em>context</em>, so the binding {@code list_git_branches} reports and
 * {@code switch_git_branch} follows automatically is recorded for that branch
 * (issue #281 phase 2).
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
 */
public class SetBranchInfobaseTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "set_branch_infobase"; //$NON-NLS-1$

    /** Input param: short branch name whose context to bind/unbind. */
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
        return "Attach or detach an EXISTING infobase (application) to/from a specific git branch " //$NON-NLS-1$
            + "context, so switch_git_branch's automatic binding follows that branch. Target the " //$NON-NLS-1$
            + "application via applicationId (from get_applications) - this tool never creates an " //$NON-NLS-1$
            + "infobase, only records the binding. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('set_branch_infobase')."; //$NON-NLS-1$
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
        InfobaseReference ref = appRef.reference();
        InfobaseAssociationContext ctx = InfobaseAssociationContext.of(branch);

        try
        {
            if (attach)
            {
                assocManager.associate(project, ref, InfobaseAssociationSettings.alreadySynchronized(ctx));
                if (setDefault)
                {
                    assocManager.setDefaultInfobase(project, ref, ctx);
                }
            }
            else
            {
                if (!isCurrentlyBound(assocManager, project, ctx, ref))
                {
                    return ToolResult.error("Application '" + applicationId + "' is not bound to branch " //$NON-NLS-1$ //$NON-NLS-2$
                        + "context '" + branch + "'; nothing to detach. Use list_git_branches to see " //$NON-NLS-1$ //$NON-NLS-2$
                        + "current bindings.").toJson(); //$NON-NLS-1$
                }
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
        return ok.toJson();
    }

    /**
     * Checks whether {@code ref} is currently among the branch context's bound
     * infobases, so {@code detach} can reject a no-op with an actionable "not
     * bound" error instead of relying on {@code dissociate}'s own (unspecified)
     * behaviour for an absent binding. A read-back failure degrades to
     * {@code true} (assume bound) so the actual {@code dissociate} call - not a
     * guess here - produces the real error or succeeds.
     */
    private static boolean isCurrentlyBound(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx, InfobaseReference ref)
    {
        try
        {
            Optional<IInfobaseAssociation> current = assocManager.getAssociation(project, ctx);
            if (current.isEmpty())
            {
                return false;
            }
            Collection<InfobaseReference> infobases = current.get().getInfobases();
            if (infobases == null || ref.getName() == null)
            {
                return false;
            }
            for (InfobaseReference ib : infobases)
            {
                if (ib != null && ref.getName().equals(ib.getName()))
                {
                    return true;
                }
            }
            return false;
        }
        catch (RuntimeException e)
        {
            return true;
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
        Optional<IApplication> appOpt;
        try
        {
            appOpt = appManager.getApplication(project, applicationId);
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
