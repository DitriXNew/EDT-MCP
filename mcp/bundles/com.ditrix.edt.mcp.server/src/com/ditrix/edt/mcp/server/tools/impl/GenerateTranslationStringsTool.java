/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.eclipse.core.resources.IFile;
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
 * Tool that generates translation strings (.lstr / .trans / .dict) — populates
 * dictionary keys based on the parent project's translatable features.
 *
 * <p>Equivalent of the EDT UI action
 * <em>Translation &rarr; Generate translation strings</em>. Wraps the public
 * CLI API {@code com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi}
 * via reflection so this bundle has no build-time dependency on LanguageTool
 * (LanguageTool ships with EDT 2025.x but not with EDT 2026.1).
 *
 * <p>Accepts both source projects (V8ConfigurationNature) and dependent
 * translation projects (dependentProjectNature). For dependent projects the
 * parent project ID is read from {@code DT-INF/DEPENDENT.PMF} and used as the
 * IDtProject the API is invoked on.
 */
public class GenerateTranslationStringsTool implements IMcpTool
{
    public static final String NAME = "generate_translation_strings"; //$NON-NLS-1$

    private static final String DEPENDENT_NATURE_ID =
        "com.e1c.langtool.v8.dt.resourcestorage.dependentProjectNature"; //$NON-NLS-1$
    private static final String DEPENDENT_MANIFEST_PATH = "DT-INF/DEPENDENT.PMF"; //$NON-NLS-1$
    private static final String PARENT_PROJECT_HEADER = "Parent-Project"; //$NON-NLS-1$

    private static final String DEFAULT_STORAGE_ID = "edit:default"; //$NON-NLS-1$
    private static final String DEFAULT_COLLECT_MODEL_TYPE = "ANY"; //$NON-NLS-1$
    private static final String DEFAULT_FILL_UP_TYPE = "NOT_FILLUP"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Generate translation strings (.lstr/.trans/.dict) — populates " //$NON-NLS-1$
             + "dictionary keys based on the parent project's translatable " //$NON-NLS-1$
             + "features. Equivalent of the EDT menu " //$NON-NLS-1$
             + "Translation -> Generate translation strings. Accepts both " //$NON-NLS-1$
             + "source projects (V8ConfigurationNature) and dependent " //$NON-NLS-1$
             + "translation projects (dependentProjectNature) — for the " //$NON-NLS-1$
             + "latter the parent project is auto-resolved from " //$NON-NLS-1$
             + "DT-INF/DEPENDENT.PMF. Requires EDT with LanguageTool " //$NON-NLS-1$
             + "installed (EDT 2025.x)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Project name. Source (V8ConfigurationNature) or dependent translation " //$NON-NLS-1$
              + "(dependentProjectNature) — for dependent the parent is auto-resolved (required).")
            .stringArrayProperty("targetLanguages", //$NON-NLS-1$
                "Target language codes to generate strings for, e.g. [\"en\"] (required).") //$NON-NLS-1$
            .stringProperty("storageId", //$NON-NLS-1$
                "Storage ID to write generated keys into. Use get_translation_project_info to list " //$NON-NLS-1$
              + "available storages. Default: \"edit:default\".")
            .booleanProperty("collectInterface", //$NON-NLS-1$
                "Generate interface (.lstr) keys. Default: true.") //$NON-NLS-1$
            .booleanProperty("collectModel", //$NON-NLS-1$
                "Generate model (.trans) keys. Default: true.") //$NON-NLS-1$
            .stringProperty("collectModelType", //$NON-NLS-1$
                "Model collection mode: ANY | NONE | COMPUTED_ONLY | UNKNOWN_ONLY | TAGS_ONLY. Default: ANY.") //$NON-NLS-1$
            .stringProperty("fillUpType", //$NON-NLS-1$
                "Pre-fill new keys with values from: NOT_FILLUP | FROM_SOURCE_LANGUAGE | FROM_PROVIDER. Default: NOT_FILLUP.") //$NON-NLS-1$
            .stringProperty("providerId", //$NON-NLS-1$
                "Translation provider ID, used only when fillUpType=FROM_PROVIDER " //$NON-NLS-1$
              + "(e.g. \"com.e1c.langtool.history.externalTranslationProvider\"). " //$NON-NLS-1$
              + "Use get_translation_project_info to list available providers.")
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
        String storageId = JsonUtils.extractStringArgument(params, "storageId"); //$NON-NLS-1$
        boolean collectInterface = JsonUtils.extractBooleanArgument(params, "collectInterface", true); //$NON-NLS-1$
        boolean collectModel = JsonUtils.extractBooleanArgument(params, "collectModel", true); //$NON-NLS-1$
        String collectModelType = JsonUtils.extractStringArgument(params, "collectModelType"); //$NON-NLS-1$
        String fillUpType = JsonUtils.extractStringArgument(params, "fillUpType"); //$NON-NLS-1$
        String providerId = JsonUtils.extractStringArgument(params, "providerId"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (targetLanguages == null || targetLanguages.isEmpty())
        {
            return ToolResult.error("targetLanguages is required (e.g. [\"en\"])").toJson(); //$NON-NLS-1$
        }

        // Apply defaults for optional parameters.
        if (storageId == null || storageId.isEmpty()) storageId = DEFAULT_STORAGE_ID;
        if (collectModelType == null || collectModelType.isEmpty()) collectModelType = DEFAULT_COLLECT_MODEL_TYPE;
        if (fillUpType == null || fillUpType.isEmpty()) fillUpType = DEFAULT_FILL_UP_TYPE;
        if (providerId == null) providerId = ""; //$NON-NLS-1$

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

            // For dependent translation projects, the API works on the parent
            // (source) project — read DT-INF/DEPENDENT.PMF to find it.
            IProject targetProject = project;
            String resolvedParent = null;
            if (project.hasNature(DEPENDENT_NATURE_ID))
            {
                resolvedParent = readParentProjectFromManifest(project);
                if (resolvedParent == null || resolvedParent.isEmpty())
                {
                    return ToolResult.error(
                        "Dependent project " + projectName //$NON-NLS-1$
                      + " has no Parent-Project header in " + DEPENDENT_MANIFEST_PATH).toJson(); //$NON-NLS-1$
                }
                targetProject = workspace.getRoot().getProject(resolvedParent);
                if (targetProject == null || !targetProject.exists())
                {
                    return ToolResult.error(
                        "Parent project not found in workspace: " + resolvedParent //$NON-NLS-1$
                      + " (referenced by dependent " + projectName + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (!targetProject.isOpen())
                {
                    return ToolResult.error("Parent project is closed: " + resolvedParent).toJson(); //$NON-NLS-1$
                }
            }

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(targetProject) : null;
            if (dtProject == null)
            {
                return ToolResult.error("Not an EDT project: " + targetProject.getName()).toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getGenerateTranslationStringsApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IGenerateTranslationStringsApi is not available. " //$NON-NLS-1$
                  + "LanguageTool ships with EDT 2025.x but not EDT 2026.1; install it or use an EDT version that includes it.").toJson(); //$NON-NLS-1$
            }

            // Build fillUpAndProviderId argument: "FillUpType" or "FillUpType:providerId".
            String fillUpAndProviderId;
            if (DEFAULT_FILL_UP_TYPE.equals(fillUpType) || providerId.isEmpty())
            {
                fillUpAndProviderId = fillUpType;
            }
            else
            {
                fillUpAndProviderId = fillUpType + ":" + providerId; //$NON-NLS-1$
            }

            // Reflection call:
            // IGenerateTranslationStringsApi.generateTranslationStrings(
            //     IDtProject project,
            //     List<String> languages,
            //     String storageId,
            //     String fillUpAndProviderId,    // "FillUpType[:providerId]"
            //     Path explicitFileList,         // null = no explicit list
            //     boolean collectModelStrings,
            //     boolean collectInterfaceStrings,
            //     String collectModelType,       // ANY|NONE|COMPUTED_ONLY|UNKNOWN_ONLY|TAGS_ONLY
            //     boolean checkTranslationsInAnyAvailableStorage,
            //     Map<String,String> filterParameters)
            Method method = api.getClass().getMethod("generateTranslationStrings", //$NON-NLS-1$
                IDtProject.class, List.class, String.class, String.class, Path.class,
                boolean.class, boolean.class, String.class, boolean.class, Map.class);
            method.invoke(api,
                dtProject,
                targetLanguages,
                storageId,
                fillUpAndProviderId,
                null,
                collectModel,
                collectInterface,
                collectModelType,
                Boolean.FALSE,
                Collections.emptyMap());

            BuildUtils.waitForDerivedData(targetProject);

            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("targetLanguages", String.join(",", targetLanguages)) //$NON-NLS-1$ //$NON-NLS-2$
                .put("storageId", storageId) //$NON-NLS-1$
                .put("collectModel", String.valueOf(collectModel)) //$NON-NLS-1$
                .put("collectInterface", String.valueOf(collectInterface)) //$NON-NLS-1$
                .put("collectModelType", collectModelType) //$NON-NLS-1$
                .put("fillUpType", fillUpType) //$NON-NLS-1$
                .put("message", "Translation strings generated."); //$NON-NLS-1$ //$NON-NLS-2$
            if (resolvedParent != null)
            {
                result.put("resolvedParentProject", resolvedParent); //$NON-NLS-1$
            }
            return result.toJson();
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

    /**
     * Reads the Parent-Project header from a dependent translation project's
     * DT-INF/DEPENDENT.PMF manifest (java-properties / MANIFEST.MF format).
     */
    private static String readParentProjectFromManifest(IProject project) throws Exception
    {
        IFile manifestFile = project.getFile(DEPENDENT_MANIFEST_PATH);
        if (!manifestFile.exists())
        {
            return null;
        }
        try (InputStream is = manifestFile.getContents())
        {
            Manifest manifest = new Manifest(is);
            return manifest.getMainAttributes().getValue(PARENT_PROJECT_HEADER);
        }
    }
}
