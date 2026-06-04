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
import com.ditrix.edt.mcp.server.utils.FrontMatter;

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
        return "Import a configuration from a directory of XML files into a NEW EDT " //$NON-NLS-1$
             + "project (EDT menu: Import); the reverse of export_configuration_to_xml. " //$NON-NLS-1$
             + "The projectName must not already exist in the workspace. " //$NON-NLS-1$
             + "Full parameters and examples: call get_tool_guide('import_configuration_from_xml')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("importPath", //$NON-NLS-1$
                "Path of the source directory of XML files.", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the NEW EDT project to create (must not already exist).", true) //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "Optional EDT nature ID, e.g. 'com._1c.g5.v8.dt.core.V8ConfigurationNature'; empty = auto-detect.") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "Optional XML format version, e.g. '8.3.20'; empty = auto-detect.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Wraps the EDT \"Import → Configuration from XML Files\" action. It " //$NON-NLS-1$
             + "creates a brand-new EDT project in the workspace from a directory of XML " //$NON-NLS-1$
             + "source files. This is the inverse of `export_configuration_to_xml`.\n\n" //$NON-NLS-1$
             + "## When to use\n\n" //$NON-NLS-1$
             + "- You have a configuration on disk as an XML dump (e.g. produced by " //$NON-NLS-1$
             + "`export_configuration_to_xml`, a Designer dump, or a VCS checkout) and you " //$NON-NLS-1$
             + "want it as a live EDT project.\n" //$NON-NLS-1$
             + "- Use this only to create a NEW project. To refresh or re-import into an " //$NON-NLS-1$
             + "existing project, this tool will reject the call (see Gotchas).\n\n" //$NON-NLS-1$
             + "## Parameter details\n\n" //$NON-NLS-1$
             + "- **importPath** (required): filesystem path to the DIRECTORY containing the " //$NON-NLS-1$
             + "XML files. The path is normalized to an absolute path before use. It must " //$NON-NLS-1$
             + "exist and must be a directory (a missing path or a file is rejected up front " //$NON-NLS-1$
             + "with a deterministic error). A path outside the EDT workspace root is allowed " //$NON-NLS-1$
             + "but FLAGGED (logged as a warning and noted in the response) — import from " //$NON-NLS-1$
             + "an external location is trusted-caller-only (see README Security & trust model).\n" //$NON-NLS-1$
             + "- **projectName** (required): name of the new EDT project to create. Must NOT " //$NON-NLS-1$
             + "already exist in the workspace, otherwise the call is rejected.\n" //$NON-NLS-1$
             + "- **projectNature** (optional): EDT project nature ID, e.g. " //$NON-NLS-1$
             + "`com._1c.g5.v8.dt.core.V8ConfigurationNature`. Pass an empty string (or omit) " //$NON-NLS-1$
             + "to let EDT auto-detect the nature from the source.\n" //$NON-NLS-1$
             + "- **xmlVersion** (optional): XML format version, e.g. `8.3.20`. Pass an empty " //$NON-NLS-1$
             + "string (or omit) to let EDT auto-detect.\n\n" //$NON-NLS-1$
             + "## Examples\n\n" //$NON-NLS-1$
             + "Minimal (auto-detect nature and version):\n\n" //$NON-NLS-1$
             + "```json\n" //$NON-NLS-1$
             + "{\"importPath\": \"D:/dumps/MyConfig\", \"projectName\": \"MyConfig\"}\n" //$NON-NLS-1$
             + "```\n\n" //$NON-NLS-1$
             + "Explicit nature and XML version:\n\n" //$NON-NLS-1$
             + "```json\n" //$NON-NLS-1$
             + "{\"importPath\": \"D:/dumps/MyConfig\", \"projectName\": \"MyConfig\", " //$NON-NLS-1$
             + "\"projectNature\": \"com._1c.g5.v8.dt.core.V8ConfigurationNature\", " //$NON-NLS-1$
             + "\"xmlVersion\": \"8.3.20\"}\n" //$NON-NLS-1$
             + "```\n\n" //$NON-NLS-1$
             + "## Notes\n\n" //$NON-NLS-1$
             + "- On success the tool returns MARKDOWN with the created project name and the " //$NON-NLS-1$
             + "(normalized) import path; when importPath is outside the workspace the response " //$NON-NLS-1$
             + "carries an `outsideWorkspace` flag and a trust note.\n" //$NON-NLS-1$
             + "- After import the tool forces a close/open/refresh of the new project, because " //$NON-NLS-1$
             + "the underlying CLI API imports with refresh disabled and the project would " //$NON-NLS-1$
             + "otherwise stay un-scanned (DtProject not ready) until something triggers EDT's " //$NON-NLS-1$
             + "project lifecycle.\n\n" //$NON-NLS-1$
             + "## Gotchas\n\n" //$NON-NLS-1$
             + "- **Project must be new.** If a workspace project with `projectName` already " //$NON-NLS-1$
             + "exists, the call is rejected early; pick a fresh name.\n" //$NON-NLS-1$
             + "- **Directory, not a file.** `importPath` must point at the directory of XML " //$NON-NLS-1$
             + "files, not at a single `.xml` file.\n" //$NON-NLS-1$
             + "- **Requires the CLI API plugin.** Needs the EDT plugin " //$NON-NLS-1$
             + "`com._1c.g5.v8.dt.cli.api`; if it is not installed the tool returns an error " //$NON-NLS-1$
             + "instead of importing."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, "importPath", "projectName"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }

        String importPathStr = JsonUtils.extractStringArgument(params, "importPath"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
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
            boolean outsideWorkspace = isOutsideWorkspace(importPath);
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
                .put("project", projectName) //$NON-NLS-1$
                .put("importPath", importPath.toString()); //$NON-NLS-1$
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

    /**
     * Returns true if {@code path} is not under the Eclipse workspace root.
     * Used to flag (not block) configuration imports from external locations.
     * Deliberately fails OPEN (returns false on any uncertainty): this is an
     * advisory-only check, so a false negative merely omits a warning and never
     * rejects a legitimate import.
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
