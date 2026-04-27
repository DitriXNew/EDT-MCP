/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool that invokes the EDT "Translate configuration" action on a project.
 *
 * <p>Equivalent of the context-menu action
 * <em>Translation &rarr; Translate configuration</em> in EDT. Under the hood
 * this is the LanguageTool <em>SynchronizeProject</em> operation: it takes the
 * source project's current state + dictionaries from its dependent translation
 * projects and regenerates the translated artifacts.
 *
 * <p>Pass the project the user right-clicks in EDT — typically the source
 * (e.g. ru) project. The API resolves the dependent translation projects
 * automatically and regenerates each translated project accordingly.
 *
 * <p>Uses the 1C public CLI API
 * {@code com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi} via reflection
 * so this bundle has no build-time dependency on LanguageTool (LanguageTool is
 * installed separately via Help -&gt; Install New Software on both EDT 2025.x
 * and 2026.1; not bundled with the EDT base distribution). When running on an
 * EDT without LanguageTool, returns a clear "not available" error.
 */
public class TranslateConfigurationTool implements IMcpTool
{
    public static final String NAME = "translate_configuration"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run EDT 'Translate configuration' on a project - " //$NON-NLS-1$
             + "propagates dictionary changes from dependent translation projects " //$NON-NLS-1$
             + "to produce translated artifacts. Equivalent of the context-menu " //$NON-NLS-1$
             + "action Translation -> Translate configuration. " //$NON-NLS-1$
             + "Requires LanguageTool installed in EDT."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Project name (typically the source, e.g. the ru project). Required.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("targetLanguages", //$NON-NLS-1$
                "Target language codes to synchronize (e.g. [\"en\"]). Required.") //$NON-NLS-1$
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
        List<String> targetLanguages = JsonUtils.extractArrayArgument(params, "targetLanguages"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (targetLanguages == null || targetLanguages.isEmpty())
        {
            return ToolResult.error("targetLanguages is required (e.g. [\"en\"])").toJson(); //$NON-NLS-1$
        }

        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = workspace.getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
            if (dtProject == null)
            {
                return ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getSynchronizeProjectApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool ISynchronizeProjectApi is not available. " //$NON-NLS-1$
                  + "Install LanguageTool in EDT.").toJson(); //$NON-NLS-1$
            }

            // Reflection call:
            // ISynchronizeProjectApi.synchronizeProject(IDtProject, List<String> languages)
            Method method = api.getClass().getMethod("synchronizeProject", //$NON-NLS-1$
                IDtProject.class, List.class);
            method.invoke(api, dtProject, targetLanguages);

            BuildUtils.waitForDerivedData(project);

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("message", "Translate configuration completed.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            // Unwrap the real exception thrown by the LanguageTool API
            // (typically com.e1c.langtool.v8.dt.cli.api.TranslationCliApiException).
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("Translate configuration failed", cause); //$NON-NLS-1$
            return ToolResult.error("Translate configuration failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("LanguageTool API mismatch (method signature changed?)", e); //$NON-NLS-1$
            return ToolResult.error("LanguageTool API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running Translate configuration", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}
