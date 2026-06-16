/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

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

    /** Input param: filesystem path of the output directory for the XML files. */
    private static final String KEY_OUTPUT_PATH = "outputPath"; //$NON-NLS-1$

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
             + "DumpConfigToFiles."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name to export (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT_PATH,
                "Filesystem path of the output directory for the XML files (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    @SuppressWarnings("java:S125")
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String outputPathStr = JsonUtils.extractStringArgument(params, KEY_OUTPUT_PATH);

        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_OUTPUT_PATH);
        if (err != null)
        {
            return err;
        }

        try
        {
            // Normalize to an absolute path so the underlying CLI API isn't
            // surprised by relative paths resolved against an unexpected
            // working directory. Reject early if the path exists but is a
            // file (not a directory); create the directory if it does not
            // exist yet so the export has a deterministic destination.
            Path outputPath = Paths.get(outputPathStr).toAbsolutePath().normalize();
            if (Files.exists(outputPath) && !Files.isDirectory(outputPath))
            {
                return ToolResult.error(
                    "outputPath exists but is not a directory: " + outputPath).toJson(); //$NON-NLS-1$
            }
            Files.createDirectories(outputPath);

            // Defense-in-depth: export can write to ANY absolute path. With the
            // server bound to loopback + optional token this is trusted-caller-only;
            // still flag writes outside the workspace so an injected/erroneous call
            // is visible. Non-breaking: warn, do not reject (local export to an
            // external dir is a legitimate action).
            boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(outputPath);
            if (outsideWorkspace)
            {
                Activator.logWarning("export_configuration_to_xml: outputPath is OUTSIDE the EDT workspace: " //$NON-NLS-1$
                    + outputPath + " (trusted-caller-only — see README Security & trust model)."); //$NON-NLS-1$
            }

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

            // Action result: status + the project and destination path. There is no
            // round-trip ID, machine-structured position, declared outputSchema, or
            // UI-bound payload here, so MARKDOWN is the right format (see the
            // "Response format policy" in README / edt-mcp-tool-conventions).
            FrontMatter fm = FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_OUTPUT_PATH, outputPath.toString());
            if (outsideWorkspace)
            {
                fm.put("outsideWorkspace", true); //$NON-NLS-1$
            }

            StringBuilder body = new StringBuilder();
            body.append("# Configuration exported to XML files\n\n"); //$NON-NLS-1$
            body.append("- Project: ").append(projectName).append('\n'); //$NON-NLS-1$
            body.append("- Output path: ").append(outputPath).append('\n'); //$NON-NLS-1$
            if (outsideWorkspace)
            {
                body.append('\n')
                    .append("> Note: outputPath is outside the EDT workspace; ") //$NON-NLS-1$
                    .append("ensure the caller is trusted.\n"); //$NON-NLS-1$
            }

            return fm.wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return CliReflectionErrors.toErrorJson(e, "Export", "CLI"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
