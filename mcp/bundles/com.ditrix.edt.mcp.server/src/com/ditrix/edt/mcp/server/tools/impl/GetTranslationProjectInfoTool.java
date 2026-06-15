/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
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
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);

        try
        {
            // Resolve the IProject first so AI clients get a specific
            // "Project not found or closed" diagnostic (naming the value) for
            // unknown names.
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.isOpen())
            {
                return ToolResult.error("Project not found or closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

            // The project is resolved and open above; refuse only the transient
            // BUILDING state here (a missing/closed name was already named above).
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
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
            // IDs are wrapped in backticks so they round-trip VERBATIM into
            // generate_translation_strings.storageId without the agent scraping prose.
            appendBacktickedList(body, storages);
            body.append("\n## Translation providers\n\n"); //$NON-NLS-1$
            // Backticked for the same reason: a provider id feeds
            // generate_translation_strings.providerId verbatim.
            appendBacktickedList(body, providers);

            return FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put("storagesCount", storages.size()) //$NON-NLS-1$
                .put("providersCount", providers.size()) //$NON-NLS-1$
                .wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return CliReflectionErrors.toErrorJson(e, "Get info", "LanguageTool"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Appends {@code items} to {@code body} as a Markdown bullet list with each value wrapped
     * in backticks, or the literal {@code (none)} when the list is empty. Extracted verbatim
     * from the Storages / providers section rendering (identical shape for both).
     */
    private static void appendBacktickedList(StringBuilder body, List<String> items)
    {
        if (items.isEmpty())
        {
            body.append("(none)\n"); //$NON-NLS-1$
        }
        else
        {
            for (String item : items)
            {
                body.append("- `").append(item).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object o)
    {
        return (List<String>) o;
    }
}
