/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport.Plan;
import com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Read and change the active project's EDT validation settings through the public repository. */
public class ProjectChecksTool implements IMcpTool
{
    public static final String NAME = "project_checks";
    private static final Set<String> ACTIONS = Set.of("list", "set", "profiles", "switch");

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List, enable/disable or re-grade EDT validation checks of a project and switch its "
            + "check-settings profile; set/switch ask for consent and return before/after. "
            + "Parameters: get_tool_guide('project_checks').";
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, Boolean.FALSE);
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true)
            .enumProperty("action", "Operation", true, "list", "set", "profiles", "switch")
            .stringArrayProperty("checkIds", "Check ids to list; includes parameters when supplied")
            .booleanProperty("onlyCustomized", "List only checks customized in the active profile")
            .integerProperty("limit", "Page size (default 50, maximum 100)")
            .integerProperty("offset", "Zero-based page offset (default 0)")
            .stringProperty("changes", "For set: JSON array of {checkId, enabled?, severity?}")
            .stringProperty("profile", "For switch: an existing profile name from profiles")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        // Action-dependent fields (including array/object before/after and integer/boolean changed)
        // stay permissive, as in the other action tools' success envelopes.
        Map<String, Object> schema = JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true)
            .stringProperty("project", "EDT project name")
            .stringProperty("activeProfile", "Active check-settings profile")
            .objectArrayProperty("checks", "Validation checks in the requested page")
            .objectArrayProperty("profiles", "Available check-settings profiles")
            .booleanProperty("persisted", "Repository read-back matches the requested state")
            .buildMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)schema.get("properties");
        properties.put("before", Map.of("description", "Previous check values or active profile"));
        properties.put("after", Map.of("description", "Repository read-back of check values or active profile"));
        properties.put("changed", Map.of("description", "Number of changed checks, or whether the profile changed"));
        return GsonProvider.toJson(schema);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, "projectName", "action");
        if (missing != null)
        {
            return missing;
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName");
        String action = JsonUtils.extractStringArgument(params, "action");
        if (!ACTIONS.contains(action))
        {
            return ToolResult.error("Unknown action '" + action + "'; use list, set, profiles or switch.").toJson();
        }
        try
        {
            String building = buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }
            IProject project = resolveProject(projectName);
            if (project == null)
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: '" + projectName
                    + "'; open it in EDT and retry project_checks.").toJson();
            }
            ICheckRepository repo = checkRepository();
            if (repo == null)
            {
                return ToolResult.error("check repository service not available; wait for EDT to finish "
                    + "starting and retry project_checks.").toJson();
            }
            try
            {
                // getActiveSettingsProfile dereferences a missing store; getSettings supplies
                // the platform's specific unregistered-project exception for our recovery path.
                var first = repo.getChecksWithDescriptions().keySet().stream().findFirst();
                if (first.isPresent())
                {
                    repo.getSettings(first.get(), project);
                }
                String active = repo.getActiveSettingsProfile(project);
                return executeAction(params, action, repo, project, active);
            }
            catch (IllegalArgumentException e)
            {
                if (e.getMessage() != null && e.getMessage().startsWith("Unregistered settings for project:"))
                {
                    return ToolResult.error(unstartedContextReason(project,
                        "Check settings are not available for project '" + projectName
                            + "'; wait for EDT to start its project context and retry project_checks.")).toJson();
                }
                throw e;
            }
        }
        catch (Exception e)
        {
            if (!(e instanceof IllegalArgumentException))
            {
                Activator.logError("project_checks failed for project '" + projectName + "'", e);
            }
            return ToolResult.error("project_checks failed for project '" + projectName + "': "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                + " Use project_checks list/profiles before retrying.").toJson();
        }
    }

    private String executeAction(Map<String, String> params, String action, ICheckRepository repo,
        IProject project, String active)
    {
        Map<String, Object> data;
        String refreshWarning = null;
        switch (action)
        {
            case "list":
                data = ProjectCheckSettingsSupport.list(repo, project, active,
                    JsonUtils.extractArrayArgument(params, "checkIds"),
                    JsonUtils.extractBooleanArgument(params, "onlyCustomized", false),
                    JsonUtils.extractIntArgument(params, "limit", ProjectCheckSettingsSupport.DEFAULT_LIST_LIMIT),
                    JsonUtils.extractIntArgument(params, "offset", 0));
                break;
            case "profiles":
                data = ProjectCheckSettingsSupport.profiles(repo, project, active);
                break;
            case "set":
                Plan plan = ProjectCheckSettingsSupport.prepare(repo, project, active,
                    JsonUtils.extractStringArgument(params, "changes"));
                if (plan.changed() == 0)
                {
                    data = Map.of("changed", 0, "before", plan.beforeRows(), "after", plan.beforeRows(),
                        "persisted", true);
                    break;
                }
                ConsentPreview setPreview = new ConsentPreview("Change check settings of " + project.getName(),
                    plan.changed() + " checks: " + plan.disabled() + " disabled, " + plan.enabled()
                        + " enabled, " + plan.severityChanges() + " severity changes; profile '" + active
                        + "'; the response carries the previous values", plan.changed(), plan.checkIds());
                String setDenied = consentErrorOrNull(setPreview);
                if (setDenied != null)
                {
                    return setDenied;
                }
                data = ProjectCheckSettingsSupport.applyAll(repo, project, plan);
                refreshWarning = refreshSettings(project);
                break;
            case "switch":
                String profile = JsonUtils.extractStringArgument(params, "profile");
                if (!ProjectCheckSettingsSupport.validateSwitch(repo, project, active, profile))
                {
                    data = Map.of("changed", false);
                    break;
                }
                ConsentPreview switchPreview = new ConsentPreview("Switch check profile of " + project.getName(),
                    "'" + active + "' \u2192 '" + profile + "'; every check follows the target profile's settings; "
                        + "the response names the previous profile", 1, List.of(profile));
                String switchDenied = consentErrorOrNull(switchPreview);
                if (switchDenied != null)
                {
                    return switchDenied;
                }
                data = ProjectCheckSettingsSupport.switchProfile(repo, project, active, profile);
                refreshWarning = refreshSettings(project);
                break;
            default:
                throw new IllegalArgumentException("Use list, set, profiles or switch.");
        }
        ToolResult result = ToolResult.success().put("action", action).put("project", project.getName())
            .put("profile", "switch".equals(action) && data.containsKey("after")
                ? ((Map<?, ?>)data.get("after")).get("activeProfile") : active);
        data.forEach(result::put);
        if (refreshWarning != null)
        {
            result.put("workspaceRefreshWarning", refreshWarning);
        }
        return result.toJson();
    }

    private String consentErrorOrNull(ConsentPreview preview)
    {
        ConsentDecision decision = requireConsent(preview);
        return decision == ConsentDecision.ALLOW ? null
            : ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
    }

    /** Single consent call site for both write actions; overridable without an EDT workbench. */
    protected ConsentDecision requireConsent(ConsentPreview preview)
    {
        return DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
    }

    protected String buildingErrorOrNull(String projectName)
    {
        return ProjectStateChecker.buildingErrorOrNull(projectName);
    }

    protected IProject resolveProject(String projectName)
    {
        ProjectContext context = ProjectContext.of(projectName);
        return context.exists() ? context.project() : null;
    }

    protected ICheckRepository checkRepository()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getCheckRepository();
    }

    protected String unstartedContextReason(IProject project, String refusal)
    {
        if (Boolean.FALSE.equals(ProjectStateChecker.hasBmModelProjectNature(project)))
        {
            return "Project '" + project.getName() + "' is not an EDT project; project_checks needs an EDT "
                + "configuration/extension project.";
        }
        return ProjectStateChecker.withUnstartedContextReason(project, refusal);
    }

    private static String refreshSettings(IProject project)
    {
        try
        {
            project.getFolder(".settings").refreshLocal(IResource.DEPTH_ONE, new NullProgressMonitor());
            return null;
        }
        catch (CoreException e)
        {
            // The repository write has succeeded: preserve the restore recipe and its read-back.
            return "Check settings were applied, but EDT could not refresh .settings: " + e.getMessage()
                + ". Refresh the project in EDT, then poll get_project_errors.";
        }
    }
}
