/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool that returns translation-related metadata for a project: the list of
 * translation storage IDs declared on the project (e.g. {@code edit:default},
 * {@code dictionary:common-camelcase}, {@code dictionary:common},
 * {@code context:model}, {@code context:interface}) and the available
 * translation provider IDs (Google, Microsoft, Yandex, history, etc.).
 *
 * <p>Wraps {@code com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi}
 * via reflection so this bundle has no build-time dependency on LanguageTool.
 */
public class GetTranslationProjectInfoTool implements IMcpTool
{
    public static final String NAME = "get_translation_project_info"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Return LanguageTool metadata for a project: the translation storages " //$NON-NLS-1$
             + "declared on it and the available translation provider IDs. Use it to " //$NON-NLS-1$
             + "check whether a dictionary storage is attached before translating; an " //$NON-NLS-1$
             + "empty storages list means none is attached yet (set up manually in EDT). " //$NON-NLS-1$
             + "Requires EDT with LanguageTool installed. " //$NON-NLS-1$
             + "Full parameters and examples: call get_tool_guide('get_translation_project_info')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Reports the LanguageTool (translation) setup for one EDT project so you can " //$NON-NLS-1$
            + "decide whether translation is configured before calling other translation tools " //$NON-NLS-1$
            + "(e.g. `generate_translation_strings`, `translate_configuration`). Output is " //$NON-NLS-1$
            + "Markdown with a `## Storages` list and a `## Translation providers` list, plus " //$NON-NLS-1$
            + "front-matter counts (`storagesCount`, `providersCount`).\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Before translating: confirm a dictionary storage is attached and which " //$NON-NLS-1$
            + "providers (Google, Microsoft, Yandex, history, etc.) are available.\n" //$NON-NLS-1$
            + "- To diagnose why a translation tool reports no storage: an empty " //$NON-NLS-1$
            + "`## Storages` list means the configuration has no dictionary storage attached " //$NON-NLS-1$
            + "yet.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` (required) - the EDT project name. Must be an open EDT project; " //$NON-NLS-1$
            + "an unknown or closed name returns `Project not found or closed`, and a plain " //$NON-NLS-1$
            + "(non-EDT) project returns `Not an EDT project`.\n\n" //$NON-NLS-1$

            + "## Storage IDs\n" //$NON-NLS-1$
            + "Storage IDs look like `edit:default`, `dictionary:common-camelcase`, " //$NON-NLS-1$
            + "`dictionary:common`, `context:model`, `context:interface`. The exact set " //$NON-NLS-1$
            + "depends on what is attached to the configuration.\n\n" //$NON-NLS-1$

            + "## Attaching a storage (manual, no MCP tool)\n" //$NON-NLS-1$
            + "If the storages list is empty, a storage must be set up by the user in EDT - " //$NON-NLS-1$
            + "there is no MCP tool for it:\n" //$NON-NLS-1$
            + "1. Create a plain Eclipse project (File -> New -> Project -> General -> Project).\n" //$NON-NLS-1$
            + "2. Attach it to the configuration via the configuration project's properties " //$NON-NLS-1$
            + "(Translation settings).\n\n" //$NON-NLS-1$
            + "The configuration itself can also act as its own storage if no separate " //$NON-NLS-1$
            + "Eclipse project is desired.\n\n" //$NON-NLS-1$

            + "## Gotchas\n" //$NON-NLS-1$
            + "- Do NOT use any '1C:Enterprise -> Dependent translation project' wizard - the " //$NON-NLS-1$
            + "dictionary storage project must be a plain Eclipse project.\n" //$NON-NLS-1$
            + "- Requires EDT with LanguageTool installed; without it the tool returns " //$NON-NLS-1$
            + "`LanguageTool IProjectInformationApi is not available`.\n\n" //$NON-NLS-1$

            + "## Example\n" //$NON-NLS-1$
            + "`{projectName: \"MyConfiguration\"}`\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

        try
        {
            // Resolve the IProject first so AI clients get a specific
            // "Project not found or closed" diagnostic for unknown names
            // instead of the generic "Not an EDT project" message that
            // ProjectStateChecker.checkReadyOrError returns for missing
            // projects.
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.isOpen())
            {
                return ToolResult.error("Project not found or closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
            if (dtProject == null)
            {
                return ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getProjectInformationApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IProjectInformationApi is not available. " //$NON-NLS-1$
                  + "Install LanguageTool in EDT.").toJson(); //$NON-NLS-1$
            }

            Method getStorages = api.getClass().getMethod("getProjectStorages", IDtProject.class); //$NON-NLS-1$
            Method getProviders = api.getClass().getMethod("getTranslationProvidersIds"); //$NON-NLS-1$

            Object storagesObj = getStorages.invoke(api, dtProject);
            Object providersObj = getProviders.invoke(api);

            List<String> storages = storagesObj instanceof List
                ? castStringList(storagesObj) : Collections.emptyList();
            List<String> providers = providersObj instanceof List
                ? castStringList(providersObj) : Collections.emptyList();

            StringBuilder body = new StringBuilder();
            body.append("## Storages\n\n"); //$NON-NLS-1$
            if (storages.isEmpty())
            {
                body.append("(none)\n"); //$NON-NLS-1$
            }
            else
            {
                for (String storage : storages)
                {
                    body.append("- ").append(storage).append('\n'); //$NON-NLS-1$
                }
            }
            body.append("\n## Translation providers\n\n"); //$NON-NLS-1$
            if (providers.isEmpty())
            {
                body.append("(none)\n"); //$NON-NLS-1$
            }
            else
            {
                for (String provider : providers)
                {
                    body.append("- ").append(provider).append('\n'); //$NON-NLS-1$
                }
            }

            return FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put("storagesCount", storages.size()) //$NON-NLS-1$
                .put("providersCount", providers.size()) //$NON-NLS-1$
                .wrapContent(body.toString());
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("get_translation_project_info failed", cause); //$NON-NLS-1$
            return ToolResult.error("Get info failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("LanguageTool API mismatch", e); //$NON-NLS-1$
            return ToolResult.error("LanguageTool API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in get_translation_project_info", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object o)
    {
        return (List<String>) o;
    }
}
