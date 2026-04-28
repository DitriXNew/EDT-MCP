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
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool that wraps the LanguageTool "Convert to translation language" action.
 *
 * <p>Equivalent of the EDT context-menu action
 * <em>Translation &rarr; Convert to translation language</em>. Use case:
 * an existing configuration was previously translated by another tool and
 * has additional language objects baked into its metadata. This action
 * extracts those translations into a dependent translation project so
 * LangTool can manage them going forward.
 *
 * <p>Wraps the public CLI API
 * {@code com.e1c.langtool.v8.dt.cli.api.IConvertLanguageProjectApi} via
 * reflection so this bundle has no build-time dependency on LanguageTool.
 * The API resolves to {@code converterService.getManager(masterProject)
 * .runConverter(sourceProject, targetProject)}, where the source
 * project's files are visited and their translations are written into the
 * target dependent translation project ({@code target/src} is wiped first).
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
        return "Convert a configuration's pre-existing language objects into a " //$NON-NLS-1$
             + "dependent translation project for LangTool. Equivalent of the EDT " //$NON-NLS-1$
             + "menu Translation -> Convert to translation language. " //$NON-NLS-1$
             + "Requires EDT with LanguageTool installed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("masterProject", //$NON-NLS-1$
                "Project that hosts the converter manager (typically the source " //$NON-NLS-1$
              + "configuration with the language objects to extract). Required.", true)
            .stringProperty("sourceProject", //$NON-NLS-1$
                "Project whose files are iterated and translated. Typically the " //$NON-NLS-1$
              + "same as masterProject. Required.", true)
            .stringProperty("targetProject", //$NON-NLS-1$
                "Dependent translation project where the extracted translations " //$NON-NLS-1$
              + "are written. Existing target/src is replaced. Required.", true)
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
        String masterName = JsonUtils.extractStringArgument(params, "masterProject"); //$NON-NLS-1$
        String sourceName = JsonUtils.extractStringArgument(params, "sourceProject"); //$NON-NLS-1$
        String targetName = JsonUtils.extractStringArgument(params, "targetProject"); //$NON-NLS-1$

        if (masterName == null || sourceName == null || targetName == null
            || masterName.isEmpty() || sourceName.isEmpty() || targetName.isEmpty())
        {
            return ToolResult.error(
                "masterProject, sourceProject and targetProject are required").toJson(); //$NON-NLS-1$
        }

        for (String name : new String[] { masterName, sourceName, targetName })
        {
            String notReadyError = ProjectStateChecker.checkReadyOrError(name);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }

        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject master = resolveOpenProject(workspace, masterName);
            if (master == null)
            {
                return ToolResult.error("Project not found or closed: " + masterName).toJson(); //$NON-NLS-1$
            }
            IProject source = resolveOpenProject(workspace, sourceName);
            if (source == null)
            {
                return ToolResult.error("Project not found or closed: " + sourceName).toJson(); //$NON-NLS-1$
            }
            IProject target = resolveOpenProject(workspace, targetName);
            if (target == null)
            {
                return ToolResult.error("Project not found or closed: " + targetName).toJson(); //$NON-NLS-1$
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
            method.invoke(api, master, source, target);

            BuildUtils.waitForDerivedData(target);

            return ToolResult.success()
                .put("masterProject", masterName) //$NON-NLS-1$
                .put("sourceProject", sourceName) //$NON-NLS-1$
                .put("targetProject", targetName) //$NON-NLS-1$
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
