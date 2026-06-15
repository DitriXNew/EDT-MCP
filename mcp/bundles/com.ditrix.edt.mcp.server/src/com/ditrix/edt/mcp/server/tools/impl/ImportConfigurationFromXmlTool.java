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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

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

    /** Input param: path of the source directory of XML files. */
    private static final String KEY_IMPORT_PATH = "importPath"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Import a configuration from a directory of XML files into a NEW EDT " //$NON-NLS-1$
             + "project (EDT menu: Import); the reverse of export_configuration_to_xml. " //$NON-NLS-1$
             + "The projectName must not already exist in the workspace. " //$NON-NLS-1$
             + "Full parameters and examples: call get_tool_guide('import_configuration_from_xml')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_IMPORT_PATH,
                "Path of the source directory of XML files.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME,
                "Name of the NEW EDT project to create (must not already exist).", true) //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "Optional EDT nature ID, e.g. 'com._1c.g5.v8.dt.core.V8ConfigurationNature'; empty = auto-detect.") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "Optional XML format version, e.g. '8.3.20'; empty = auto-detect.") //$NON-NLS-1$
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
        String err = JsonUtils.requireArguments(params, KEY_IMPORT_PATH, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String importPathStr = JsonUtils.extractStringArgument(params, KEY_IMPORT_PATH);
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String projectNature = JsonUtils.extractStringArgument(params, "projectNature"); //$NON-NLS-1$
        String xmlVersion = JsonUtils.extractStringArgument(params, "xmlVersion"); //$NON-NLS-1$

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
            // Normalize to an absolute path so the underlying CLI API isn't
            // surprised by relative paths resolved against an unexpected
            // working directory. Reject early if the path is missing or is
            // a file (not a directory) so failures are deterministic and
            // the AI agent gets a clear error instead of an opaque API
            // exception.
            Path importPath = Paths.get(importPathStr).toAbsolutePath().normalize();
            boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(importPath);
            if (outsideWorkspace)
            {
                Activator.logWarning("import_configuration_from_xml: importPath is OUTSIDE the EDT workspace: " //$NON-NLS-1$
                    + importPath + " (trusted-caller-only — see README Security & trust model)."); //$NON-NLS-1$
            }
            if (!Files.exists(importPath))
            {
                return ToolResult.error(
                    "importPath does not exist: " + importPath).toJson(); //$NON-NLS-1$
            }
            if (!Files.isDirectory(importPath))
            {
                return ToolResult.error(
                    "importPath is not a directory: " + importPath).toJson(); //$NON-NLS-1$
            }

            // The tool's contract is "import into a NEW project", so reject early
            // if a workspace project with this name already exists. Without this
            // check the underlying EDT API still throws (with a less direct
            // message) and we'd surface it via the catch block — but a clean
            // up-front error is friendlier and matches the validation pattern
            // used elsewhere (DeleteMetadataTool, CleanProjectTool, etc.).
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

            // Action result: status + the source path and the created project name.
            // There is no round-trip ID, machine-structured position, declared
            // outputSchema, or UI-bound payload here, so MARKDOWN is the right format
            // (see the "Response format policy" in README / edt-mcp-tool-conventions).
            FrontMatter fm = FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_IMPORT_PATH, importPath.toString());
            if (outsideWorkspace)
            {
                fm.put("outsideWorkspace", true); //$NON-NLS-1$
            }

            StringBuilder body = new StringBuilder();
            body.append("# Configuration imported from XML files\n\n"); //$NON-NLS-1$
            body.append("- Project: ").append(projectName).append('\n'); //$NON-NLS-1$
            body.append("- Import path: ").append(importPath).append('\n'); //$NON-NLS-1$
            if (outsideWorkspace)
            {
                body.append('\n')
                    .append("> Note: importPath is outside the EDT workspace; ") //$NON-NLS-1$
                    .append("ensure the caller is trusted.\n"); //$NON-NLS-1$
            }

            return fm.wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return CliReflectionErrors.toErrorJson(e, "Import", "CLI"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
