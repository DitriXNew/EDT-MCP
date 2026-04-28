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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool that wraps the EDT "Import → Configuration from XML Files" action.
 *
 * <p>Imports a configuration from a directory of XML source files into a new
 * EDT project in the workspace. The reverse of
 * {@link ExportConfigurationToXmlTool}.
 *
 * <p>Wraps {@code com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi}
 * via reflection so this bundle has no build-time dependency on the API
 * package.
 */
public class ImportConfigurationFromXmlTool implements IMcpTool
{
    public static final String NAME = "import_configuration_from_xml"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Import a configuration from a directory of XML files into a new " //$NON-NLS-1$
             + "EDT project (EDT menu: Import). The reverse of " //$NON-NLS-1$
             + "export_configuration_to_xml. Wraps " //$NON-NLS-1$
             + "IImportConfigurationFilesApi.importProject(Path, String, String, String)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("importPath", //$NON-NLS-1$
                "Filesystem path of the source directory containing XML files (required)", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the new EDT project to create in the workspace (required)", true) //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "EDT project nature ID, e.g. 'com._1c.g5.v8.dt.core.V8ConfigurationNature'. " //$NON-NLS-1$
              + "Pass empty string to let EDT auto-detect.") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "XML format version, e.g. '8.3.20'. Pass empty string to let EDT auto-detect.") //$NON-NLS-1$
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
        String importPathStr = JsonUtils.extractStringArgument(params, "importPath"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String projectNature = JsonUtils.extractStringArgument(params, "projectNature"); //$NON-NLS-1$
        String xmlVersion = JsonUtils.extractStringArgument(params, "xmlVersion"); //$NON-NLS-1$

        if (importPathStr == null || importPathStr.isEmpty())
        {
            return ToolResult.error("importPath is required").toJson(); //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        // projectNature and xmlVersion are optional — pass null on empty
        if (projectNature != null && projectNature.isEmpty())
        {
            projectNature = null;
        }
        if (xmlVersion != null && xmlVersion.isEmpty())
        {
            xmlVersion = null;
        }

        try
        {
            Path importPath = Paths.get(importPathStr);

            // The tool's contract is "import into a NEW project", so reject early
            // if a workspace project with this name already exists. Without this
            // check the underlying EDT API still throws (with a less direct
            // message) and we'd surface it via the catch block — but a clean
            // up-front error is friendlier and matches the validation pattern
            // used elsewhere (DeleteMetadataObjectTool, CleanProjectTool, etc.).
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject existing = workspace.getRoot().getProject(projectName);
            if (existing != null && existing.exists())
            {
                return ToolResult.error(
                    "Project already exists in workspace: " + projectName //$NON-NLS-1$
                  + ". Import requires a new project name.").toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getImportConfigurationFilesApi();
            if (api == null)
            {
                return ToolResult.error(
                    "IImportConfigurationFilesApi is not available. " //$NON-NLS-1$
                  + "Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed.").toJson(); //$NON-NLS-1$
            }

            // importProject(Path importSource, String projectName, String nature, String xmlVersion)
            Method method = api.getClass().getMethod("importProject", //$NON-NLS-1$
                Path.class, String.class, String.class, String.class);
            method.invoke(api, importPath, projectName, projectNature, xmlVersion);

            // The CLI API hardcodes setRefreshProject(false) on the import
            // operation, so the imported project is left in a state where
            // IDtProjectManager.getDtProject(p) returns null until something
            // triggers EDT's project lifecycle. Close + open + refresh here
            // forces EDT to re-scan and bring the project to the ready state.
            IProject created = workspace.getRoot().getProject(projectName);
            if (created != null && created.exists())
            {
                NullProgressMonitor monitor = new NullProgressMonitor();
                if (created.isOpen())
                {
                    created.close(monitor);
                }
                created.open(monitor);
                created.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            }

            return ToolResult.success()
                .put("importPath", importPath.toString()) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put("message", "Configuration imported from XML files.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("import_configuration_from_xml failed", cause); //$NON-NLS-1$
            return ToolResult.error("Import failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("CLI API mismatch", e); //$NON-NLS-1$
            return ToolResult.error("CLI API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in import_configuration_from_xml", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}
