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
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Read and change the active project's EDT validation settings through the public repository. */
public class ProjectChecksTool implements IMcpTool
{
    public static final String NAME = "project_checks";
    private static final Set<String> ACTIONS = Set.of("list", "set", "profiles", "switch",
        "createProfile", "copyProfile", "deleteProfile");

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List or change EDT validation checks; switch, create, copy or delete check-settings profiles. "
            + "Writes ask for consent; profile deletion is destructive. "
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
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, null, Boolean.FALSE);
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true)
            .enumProperty("action", "Operation", true, "list", "set", "profiles", "switch",
                "createProfile", "copyProfile", "deleteProfile")
            .stringArrayProperty("checkIds", "Check ids to list; includes parameters when supplied")
            .booleanProperty("onlyCustomized", "List only checks customized in the active profile")
            .integerProperty("limit", "Page size (default 50, maximum 100)")
            .integerProperty("offset", "Zero-based page offset (default 0)")
            .stringProperty("changes", "For set: JSON array of {checkId, enabled?, severity?}")
            .stringProperty("profile", "Profile name: existing for switch/deleteProfile, new for createProfile/copyProfile")
            .stringProperty("from", "Source profile: required for copyProfile; createProfile defaults to Default")
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
            .stringProperty("profile", "Affected check-settings profile")
            .stringProperty("from", "Source of a created or copied profile")
            .stringProperty("deletedProfile", "Deleted check-settings profile")
            .integerProperty("deletedCustomizedChecks", "Customized checks in the deleted profile")
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
            return ToolResult.error("Unknown action '" + action
                + "'; use list, set, profiles, switch, createProfile, copyProfile or deleteProfile.").toJson();
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
                try
                {
                    ProjectCheckSettingsSupport.refreshSettingsBeforeRead(project);
                }
                catch (IllegalArgumentException e)
                {
                    return ToolResult.error(e.getMessage()).toJson();
                }
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
                Refusals.log("project_checks failed for project '" + projectName + "'", e);
            }
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("project_checks failed for project '" + projectName + "': "
                + detail + (detail.endsWith(".") ? " " : ". ")
                + "Use project_checks list/profiles before retrying.").toJson();
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
                refreshWarning = ProjectCheckSettingsSupport.refreshSettings(project, true);
                break;
            case "switch":
                String profile = JsonUtils.extractStringArgument(params, "profile");
                var switchPlan = ProjectCheckSettingsSupport.prepareSwitch(repo, project, active, profile);
                if (!switchPlan.changed())
                {
                    data = Map.of("changed", false);
                    break;
                }
                ConsentPreview switchPreview = new ConsentPreview("Switch check profile of " + project.getName(),
                    "'" + active + "' \u2192 '" + switchPlan.target() + "'; every check follows the target profile's settings; "
                        + "the response names the previous profile", 1, List.of(switchPlan.target()));
                String switchDenied = consentErrorOrNull(switchPreview);
                if (switchDenied != null)
                {
                    return switchDenied;
                }
                data = ProjectCheckSettingsSupport.switchProfile(repo, project, switchPlan);
                refreshWarning = ProjectCheckSettingsSupport.refreshSettings(project, true);
                break;
            case "createProfile":
            case "copyProfile":
                String newProfile = JsonUtils.extractStringArgument(params, "profile");
                String from = JsonUtils.extractStringArgument(params, "from");
                String source = ProjectCheckSettingsSupport.validateProfileCopy(repo, project, newProfile,
                    from, "copyProfile".equals(action));
                var sourceFingerprint = ProjectCheckSettingsSupport.profileFingerprint(repo, project, source);
                ConsentPreview copyPreview = new ConsentPreview(
                    ("createProfile".equals(action) ? "Create" : "Copy")
                        + " check profile of " + project.getName(),
                    "'" + source + "' -> '" + newProfile
                        + "'; copies stored check settings without switching the active profile", 1, List.of(newProfile));
                String copyDenied = consentErrorOrNull(copyPreview);
                if (copyDenied != null)
                {
                    return copyDenied;
                }
                data = ProjectCheckSettingsSupport.copyProfile(repo, project, newProfile, source,
                    "createProfile".equals(action) && from == null, sourceFingerprint, active);
                refreshWarning = ProjectCheckSettingsSupport.refreshSettings(project, true);
                break;
            case "deleteProfile":
                String deletedProfile = ProjectCheckSettingsSupport.validateProfileDelete(repo, project,
                    JsonUtils.extractStringArgument(params, "profile"));
                var fingerprint = ProjectCheckSettingsSupport.profileFingerprint(repo, project, deletedProfile);
                int customized = fingerprint.size();
                ConsentPreview deletePreview = new ConsentPreview("Delete check profile of " + project.getName(),
                    "Delete '" + deletedProfile + "' with " + customized
                        + " customized checks; deletion is destructive; the active profile stays unchanged",
                    1, List.of(deletedProfile));
                String deleteDenied = consentErrorOrNull(deletePreview);
                if (deleteDenied != null)
                {
                    return deleteDenied;
                }
                data = ProjectCheckSettingsSupport.deleteProfile(repo, project, deletedProfile, active, fingerprint);
                refreshWarning = ProjectCheckSettingsSupport.refreshSettings(project, true);
                break;
            default:
                throw new IllegalArgumentException(
                    "Use list, set, profiles, switch, createProfile, copyProfile or deleteProfile.");
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

    /** Single consent gate for all writes, including destructive deletion; overridable in unit tests. */
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

}
