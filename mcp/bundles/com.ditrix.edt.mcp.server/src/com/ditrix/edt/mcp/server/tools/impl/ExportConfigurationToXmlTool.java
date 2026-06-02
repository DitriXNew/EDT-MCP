/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

import org.eclipse.core.resources.ResourcesPlugin;

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

        String err = JsonUtils.requireArguments(params, "projectName", "outputPath"); //$NON-NLS-1$ //$NON-NLS-2$
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
            boolean outsideWorkspace = isOutsideWorkspace(outputPath);
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

            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("outputPath", outputPath.toString()) //$NON-NLS-1$
                .put("message", "Configuration exported to XML files."); //$NON-NLS-1$ //$NON-NLS-2$
            if (outsideWorkspace)
            {
                result.put("securityNote", //$NON-NLS-1$
                    "outputPath is outside the EDT workspace; ensure the caller is trusted."); //$NON-NLS-1$
            }
            return result.toJson();
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

    /**
     * Returns true if {@code path} is not under the Eclipse workspace root.
     * Used to flag (not block) configuration exports to external locations.
     * Deliberately fails OPEN (returns false on any uncertainty): this is an
     * advisory-only check, so a false negative merely omits a warning and never
     * rejects a legitimate export.
     */
    private static boolean isOutsideWorkspace(Path path)
    {
        try
        {
            org.eclipse.core.runtime.IPath loc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            if (loc == null)
            {
                return false;
            }
            Path wsRoot = loc.toFile().toPath().toAbsolutePath().normalize();
            return !path.startsWith(wsRoot);
        }
        catch (Exception e)
        {
            return false; // cannot determine — do not flag
        }
    }
}
