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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool that wraps the LanguageTool "Import translations from translated object"
 * action.
 *
 * <p>Equivalent of the EDT context-menu action
 * <em>Translation &rarr; Import translations from translated object</em>.
 * Imports one or more dependent translation projects from disk into the
 * workspace via
 * {@code com.e1c.langtool.v8.dt.cli.api.IImportLanguageProjectApi.importLanguageProjects(List&lt;Path&gt;)}.
 *
 * <p>Uses reflection to keep this bundle build-independent of LanguageTool.
 */
public class ImportTranslationsFromTranslatedObjectTool implements IMcpTool
{
    public static final String NAME = "import_translations_from_translated_object"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Import dependent translation projects from filesystem paths " //$NON-NLS-1$
             + "(EDT menu: Translation -> Import translations from translated object). " //$NON-NLS-1$
             + "Wraps IImportLanguageProjectApi.importLanguageProjects(List<Path>). " //$NON-NLS-1$
             + "Requires EDT with LanguageTool installed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringArrayProperty("paths", //$NON-NLS-1$
                "Filesystem paths to translation projects to import (required, at least one)") //$NON-NLS-1$
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
        List<String> pathStrings = JsonUtils.extractArrayArgument(params, "paths"); //$NON-NLS-1$
        if (pathStrings == null || pathStrings.isEmpty())
        {
            return ToolResult.error("paths is required (non-empty array of filesystem paths)").toJson(); //$NON-NLS-1$
        }

        List<Path> paths = new ArrayList<>(pathStrings.size());
        for (String s : pathStrings)
        {
            if (s == null || s.isEmpty())
            {
                return ToolResult.error("path entry is empty").toJson(); //$NON-NLS-1$
            }
            paths.add(Paths.get(s));
        }

        try
        {
            Object api = Activator.getDefault().getImportLanguageProjectApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IImportLanguageProjectApi is not available. " //$NON-NLS-1$
                  + "Install LanguageTool in EDT.").toJson(); //$NON-NLS-1$
            }

            Method method = api.getClass().getMethod("importLanguageProjects", List.class); //$NON-NLS-1$
            Object result = method.invoke(api, paths);

            List<String> imported = new ArrayList<>();
            if (result instanceof Collection)
            {
                for (Object item : (Collection<?>) result)
                {
                    if (item instanceof IProject)
                    {
                        imported.add(((IProject) item).getName());
                    }
                }
            }

            return ToolResult.success()
                .put("imported", imported) //$NON-NLS-1$
                .put("count", imported.size()) //$NON-NLS-1$
                .put("message", "Import completed.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError("Import translation project failed", cause); //$NON-NLS-1$
            return ToolResult.error("Import failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            Activator.logError("LanguageTool API mismatch", e); //$NON-NLS-1$
            return ToolResult.error("LanguageTool API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in import_translations_from_translated_object", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}
