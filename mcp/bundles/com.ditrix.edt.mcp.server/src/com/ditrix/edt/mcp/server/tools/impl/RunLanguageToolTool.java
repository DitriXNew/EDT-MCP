/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
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
 * Tool that invokes LanguageTool to regenerate translation strings
 * (.lstr / .trans / .dict) for a dependent translation project.
 *
 * <p>Equivalent of the EDT UI action
 * <em>Translation &rarr; Generate translation strings</em>. Uses the 1C public
 * CLI API {@code com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi}
 * via reflection so this bundle has no build-time dependency on LanguageTool
 * (LanguageTool ships with EDT 2025.x but not with EDT 2026.1). When running on
 * an EDT without LanguageTool, returns a clear "not available" error.
 */
public class RunLanguageToolTool implements IMcpTool
{
    public static final String NAME = "run_language_tool"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run LanguageTool - regenerate translation strings " //$NON-NLS-1$
             + "(.lstr/.trans/.dict) for a dependent translation project. " //$NON-NLS-1$
             + "Equivalent of the EDT 'Translate' / LanguageTool action. " //$NON-NLS-1$
             + "Requires EDT with LanguageTool installed (EDT 2025.x)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Dependent translation project name (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("sourceLanguage", "Source language code, e.g. 'ru' (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("targetLanguage", "Target language code, e.g. 'en' (required)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String sourceLanguage = JsonUtils.extractStringArgument(params, "sourceLanguage"); //$NON-NLS-1$
        String targetLanguage = JsonUtils.extractStringArgument(params, "targetLanguage"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (sourceLanguage == null || sourceLanguage.isEmpty())
        {
            return ToolResult.error("sourceLanguage is required (e.g. 'ru')").toJson(); //$NON-NLS-1$
        }
        if (targetLanguage == null || targetLanguage.isEmpty())
        {
            return ToolResult.error("targetLanguage is required (e.g. 'en')").toJson(); //$NON-NLS-1$
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

            Object api = Activator.getDefault().getGenerateTranslationStringsApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IGenerateTranslationStringsApi is not available. " //$NON-NLS-1$
                  + "LanguageTool ships with EDT 2025.x but not EDT 2026.1; install it or use an EDT version that includes it.").toJson(); //$NON-NLS-1$
            }

            // Reflection call:
            // IGenerateTranslationStringsApi.generateTranslationStrings(
            //     IDtProject, List<String>, String, String, Path,
            //     boolean, boolean, String, boolean, Map<String,String>)
            Method method = api.getClass().getMethod("generateTranslationStrings", //$NON-NLS-1$
                IDtProject.class, List.class, String.class, String.class, Path.class,
                boolean.class, boolean.class, String.class, boolean.class, Map.class);
            method.invoke(api,
                dtProject,
                Collections.emptyList(),
                sourceLanguage,
                targetLanguage,
                null,
                Boolean.TRUE,
                Boolean.TRUE,
                null,
                Boolean.FALSE,
                Collections.emptyMap());

            BuildUtils.waitForDerivedData(project);

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("sourceLanguage", sourceLanguage) //$NON-NLS-1$
                .put("targetLanguage", targetLanguage) //$NON-NLS-1$
                .put("message", "LanguageTool completed.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            // Unwrap the real exception thrown by the LanguageTool API
            // (typically com.e1c.langtool.v8.dt.cli.api.TranslationCliApiException).
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("LanguageTool invocation failed", cause); //$NON-NLS-1$
            return ToolResult.error("LanguageTool failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("LanguageTool API mismatch (method signature changed?)", e); //$NON-NLS-1$
            return ToolResult.error("LanguageTool API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running LanguageTool", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}
