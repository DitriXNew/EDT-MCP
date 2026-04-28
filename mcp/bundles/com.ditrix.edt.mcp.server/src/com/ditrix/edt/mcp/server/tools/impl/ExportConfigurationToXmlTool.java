/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool that wraps the EDT "Export → Configuration to XML Files" action.
 *
 * <p>Equivalent of the EDT context-menu action <em>Export → Configuration to
 * XML Files</em>. Dumps the configuration of an EDT project to a directory of
 * XML source files (the same format produced by the 1C platform's
 * {@code DumpConfigToFiles} command).
 *
 * <p>Wraps {@code com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi}
 * via reflection so this bundle has no build-time dependency on the API
 * package (the API ships with EDT 2025.x and 2026.1, but reflection keeps the
 * plugin portable).
 */
public class ExportConfigurationToXmlTool implements IMcpTool
{
    public static final String NAME = "export_configuration_to_xml"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Export an EDT configuration project to XML files (EDT menu: " //$NON-NLS-1$
             + "Export -> Configuration to XML Files). Equivalent of 1C platform " //$NON-NLS-1$
             + "DumpConfigToFiles. Wraps IExportConfigurationFilesApi.exportProject(String, Path)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name to export (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("outputPath", //$NON-NLS-1$
                "Filesystem path of the output directory for the XML files (required)", true) //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String outputPathStr = JsonUtils.extractStringArgument(params, "outputPath"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (outputPathStr == null || outputPathStr.isEmpty())
        {
            return ToolResult.error("outputPath is required").toJson(); //$NON-NLS-1$
        }

        try
        {
            Path outputPath = Paths.get(outputPathStr);

            Object api = Activator.getDefault().getExportConfigurationFilesApi();
            if (api == null)
            {
                return ToolResult.error(
                    "IExportConfigurationFilesApi is not available. " //$NON-NLS-1$
                  + "Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed.").toJson(); //$NON-NLS-1$
            }

            // exportProject(String projectName, Path outputPath)
            Method method = api.getClass().getMethod("exportProject", //$NON-NLS-1$
                String.class, Path.class);
            method.invoke(api, projectName, outputPath);

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("outputPath", outputPath.toString()) //$NON-NLS-1$
                .put("message", "Configuration exported to XML files.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("export_configuration_to_xml failed", cause); //$NON-NLS-1$
            return ToolResult.error("Export failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("CLI API mismatch", e); //$NON-NLS-1$
            return ToolResult.error("CLI API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in export_configuration_to_xml", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}
