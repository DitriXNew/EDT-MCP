/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;

/**
 * Tool that wraps the LanguageTool "Convert to translation language" action.
 *
 * <p>Equivalent of the EDT context-menu action
 * <em>Translation &rarr; Convert to translation language</em>. Uses the 1C
 * public CLI API
 * {@code com.e1c.langtool.v8.dt.cli.api.IConvertLanguageProjectApi} via
 * reflection so this bundle has no build-time dependency on LanguageTool.
 *
 * <p>The underlying API takes three IProject arguments. Signatures don't
 * preserve parameter names, so callers must pass project names exactly as
 * they would supply via the EDT UI (typically: source / dependent / ?).
 */
public class ConvertToTranslationLanguageTool implements IMcpTool
{
    public static final String NAME = "convert_to_translation_language"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run EDT 'Convert to translation language' on a project. " //$NON-NLS-1$
             + "Wraps IConvertLanguageProjectApi.convertLanguageProject(IProject, IProject, IProject). " //$NON-NLS-1$
             + "Requires EDT with LanguageTool installed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName1", "First project name (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName2", "Second project name (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName3", "Third project name (required)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String name1 = JsonUtils.extractStringArgument(params, "projectName1"); //$NON-NLS-1$
        String name2 = JsonUtils.extractStringArgument(params, "projectName2"); //$NON-NLS-1$
        String name3 = JsonUtils.extractStringArgument(params, "projectName3"); //$NON-NLS-1$

        if (name1 == null || name2 == null || name3 == null
            || name1.isEmpty() || name2.isEmpty() || name3.isEmpty())
        {
            return ToolResult.error("All three project names are required").toJson(); //$NON-NLS-1$
        }

        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject p1 = resolveOpenProject(workspace, name1);
            if (p1 == null)
            {
                return ToolResult.error("Project not found or closed: " + name1).toJson(); //$NON-NLS-1$
            }
            IProject p2 = resolveOpenProject(workspace, name2);
            if (p2 == null)
            {
                return ToolResult.error("Project not found or closed: " + name2).toJson(); //$NON-NLS-1$
            }
            IProject p3 = resolveOpenProject(workspace, name3);
            if (p3 == null)
            {
                return ToolResult.error("Project not found or closed: " + name3).toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getConvertLanguageProjectApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IConvertLanguageProjectApi is not available. " //$NON-NLS-1$
                  + "Install LanguageTool in EDT.").toJson(); //$NON-NLS-1$
            }

            Method method = api.getClass().getMethod("convertLanguageProject", //$NON-NLS-1$
                IProject.class, IProject.class, IProject.class);
            method.invoke(api, p1, p2, p3);

            BuildUtils.waitForDerivedData(p1);

            return ToolResult.success()
                .put("project1", name1) //$NON-NLS-1$
                .put("project2", name2) //$NON-NLS-1$
                .put("project3", name3) //$NON-NLS-1$
                .put("message", "Convert to translation language completed.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("Convert language project failed", cause); //$NON-NLS-1$
            return ToolResult.error("Convert language project failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("LanguageTool API mismatch", e); //$NON-NLS-1$
            return ToolResult.error("LanguageTool API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in convert_to_translation_language", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    private static IProject resolveOpenProject(IWorkspace workspace, String name)
    {
        IProject project = workspace.getRoot().getProject(name);
        if (project == null || !project.exists() || !project.isOpen())
        {
            return null;
        }
        return project;
    }
}
