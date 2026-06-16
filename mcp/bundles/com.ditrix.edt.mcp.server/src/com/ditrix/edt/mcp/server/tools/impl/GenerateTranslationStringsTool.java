/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool that generates translation strings (.lstr / .trans / .dict) for a
 * configuration project — scans the configuration's translatable features and
 * writes the resulting keys into the storages declared on the project (each
 * storage routes to either an external dictionary storage project — a plain
 * Eclipse project with the dependentProjectNature — or to the configuration
 * itself, depending on {@code .settings/translation_storages.yml}).
 *
 * <p>Equivalent of the EDT UI action
 * <em>Translation &rarr; Generate translation strings</em>, which is invoked
 * on the <strong>configuration project</strong> (V8ConfigurationNature). It is
 * NOT meant to be invoked on a dictionary storage project — pass the
 * configuration project here.
 *
 * <p>Wraps the public CLI API
 * {@code com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi} via
 * reflection so this bundle has no build-time dependency on LanguageTool
 * (LanguageTool is installed separately via Help -&gt; Install New Software
 * on both EDT 2025.x and 2026.1; not bundled with the EDT base distribution).
 */
public class GenerateTranslationStringsTool implements IMcpTool
{
    public static final String NAME = "generate_translation_strings"; //$NON-NLS-1$

    private static final String DEFAULT_STORAGE_ID = "edit:default"; //$NON-NLS-1$
    private static final String DEFAULT_COLLECT_MODEL_TYPE = "ANY"; //$NON-NLS-1$
    private static final String DEFAULT_FILL_UP_TYPE = "NOT_FILLUP"; //$NON-NLS-1$
    private static final String FILL_UP_FROM_PROVIDER = "FROM_PROVIDER"; //$NON-NLS-1$
    private static final String V8_CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    private static final String TARGET_LANGUAGES = "targetLanguages"; //$NON-NLS-1$
    private static final String STORAGE_ID = "storageId"; //$NON-NLS-1$
    private static final String COLLECT_INTERFACE = "collectInterface"; //$NON-NLS-1$
    private static final String COLLECT_MODEL = "collectModel"; //$NON-NLS-1$
    private static final String COLLECT_MODEL_TYPE = "collectModelType"; //$NON-NLS-1$
    private static final String FILL_UP_TYPE = "fillUpType"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Generate translation strings (.lstr/.trans/.dict) for a configuration " //$NON-NLS-1$
             + "project: scans translatable features and writes the resulting keys into " //$NON-NLS-1$
             + "the project's storages (EDT menu Translation -> Generate translation " //$NON-NLS-1$
             + "strings). Run on the configuration project (V8ConfigurationNature), not " //$NON-NLS-1$
             + "a dictionary storage project; requires LanguageTool installed in EDT. " //$NON-NLS-1$
             + "Full parameters and examples: call get_tool_guide('generate_translation_strings')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "Configuration project name (V8ConfigurationNature), not a dictionary storage project. Required.", //$NON-NLS-1$
                true)
            .stringArrayProperty(TARGET_LANGUAGES,
                "Target language codes to generate, e.g. [\"en\"]. Required.", true) //$NON-NLS-1$
            .stringProperty(STORAGE_ID,
                "Storage ID to write keys into (see get_translation_project_info). Default: \"edit:default\".") //$NON-NLS-1$
            .booleanProperty(COLLECT_INTERFACE,
                "Generate interface (.lstr) keys. Default: true.") //$NON-NLS-1$
            .booleanProperty(COLLECT_MODEL,
                "Generate model (.trans) keys. Default: true.") //$NON-NLS-1$
            .stringProperty(COLLECT_MODEL_TYPE,
                "Model mode: ANY | NONE | COMPUTED_ONLY | UNKNOWN_ONLY | TAGS_ONLY. Default: ANY.") //$NON-NLS-1$
            .stringProperty(FILL_UP_TYPE,
                "Pre-fill source: NOT_FILLUP | FROM_SOURCE_LANGUAGE | FROM_PROVIDER. Default: NOT_FILLUP.") //$NON-NLS-1$
            .stringProperty("providerId", //$NON-NLS-1$
                "Translation provider ID; required only when fillUpType=FROM_PROVIDER (see get_translation_project_info).") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        List<String> targetLanguages = JsonUtils.extractArrayArgument(params, TARGET_LANGUAGES);
        boolean collectInterface = JsonUtils.extractBooleanArgument(params, COLLECT_INTERFACE, true);
        boolean collectModel = JsonUtils.extractBooleanArgument(params, COLLECT_MODEL, true);

        // Parse + default + validate the scalar/string arguments (no side effects).
        Options opts = parseOptions(params, targetLanguages);
        if (opts.error != null)
        {
            return opts.error;
        }

        try
        {
            // Resolve the configuration project and the LanguageTool API (no side
            // effects; same value/case errors as before).
            Resolved resolved = resolveProjectAndApi(projectName);
            if (resolved.error != null)
            {
                return resolved.error;
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
            Method method = resolved.api.getClass().getMethod("generateTranslationStrings", //$NON-NLS-1$
                IDtProject.class, List.class, String.class, String.class, Path.class,
                boolean.class, boolean.class, String.class, boolean.class, Map.class);
            method.invoke(resolved.api,
                resolved.dtProject,
                targetLanguages,
                opts.storageId,
                opts.fillUpAndProviderId(),
                null,
                collectModel,
                collectInterface,
                opts.collectModelType,
                Boolean.FALSE,
                Collections.emptyMap());

            BuildUtils.waitForDerivedData(resolved.project);

            return FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put(TARGET_LANGUAGES, String.join(", ", targetLanguages)) //$NON-NLS-1$
                .put(STORAGE_ID, opts.storageId)
                .put(COLLECT_INTERFACE, collectInterface)
                .put(COLLECT_MODEL, collectModel)
                .put(COLLECT_MODEL_TYPE, opts.collectModelType)
                .put(FILL_UP_TYPE, opts.fillUpType)
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .wrapContent("Translation strings generated."); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return CliReflectionErrors.toErrorJson(e, "Generate translation strings", "LanguageTool"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Extracts, defaults and validates the scalar/string options (no side
     * effects). Returns a holder carrying either the populated {@link Options} or
     * the exact error JSON the inline guards produced (same value, same case); the
     * caller re-checks {@code error}. {@code targetLanguages} is passed in
     * already-extracted and validated here for the required-argument and
     * non-empty-languages checks.
     */
    private static Options parseOptions(Map<String, String> params, List<String> targetLanguages)
    {
        String storageId = JsonUtils.extractStringArgument(params, STORAGE_ID);
        String collectModelType = JsonUtils.extractStringArgument(params, COLLECT_MODEL_TYPE);
        String fillUpType = JsonUtils.extractStringArgument(params, FILL_UP_TYPE);
        String providerId = JsonUtils.extractStringArgument(params, "providerId"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return Options.failed(err);
        }
        if (targetLanguages == null || targetLanguages.isEmpty())
        {
            return Options.failed(
                ToolResult.error("targetLanguages is required (e.g. [\"en\"])").toJson()); //$NON-NLS-1$
        }

        // Apply defaults for optional parameters.
        if (storageId == null || storageId.isEmpty()) storageId = DEFAULT_STORAGE_ID;
        if (collectModelType == null || collectModelType.isEmpty()) collectModelType = DEFAULT_COLLECT_MODEL_TYPE;
        if (fillUpType == null || fillUpType.isEmpty()) fillUpType = DEFAULT_FILL_UP_TYPE;
        if (providerId == null) providerId = ""; //$NON-NLS-1$

        if (FILL_UP_FROM_PROVIDER.equals(fillUpType) && providerId.isEmpty())
        {
            return Options.failed(ToolResult.error(
                "providerId is required when fillUpType=FROM_PROVIDER. " //$NON-NLS-1$
              + "Use get_translation_project_info to list available providers.").toJson()); //$NON-NLS-1$
        }

        return Options.of(storageId, collectModelType, fillUpType, providerId);
    }

    /**
     * Resolves the configuration {@link IProject}, its {@link IDtProject} and the
     * LanguageTool API for {@code projectName}, in the same order and with the same
     * value/case diagnostics as the original inline chain (most-specific
     * "Project not found"/"closed" first, then BUILDING, then nature, then
     * IDtProject, then API). No side effects. Returns a holder carrying either the
     * resolved trio or the exact error JSON; the caller re-checks {@code error}.
     *
     * @throws org.eclipse.core.runtime.CoreException from {@code IProject.hasNature}
     */
    private static Resolved resolveProjectAndApi(String projectName) throws org.eclipse.core.runtime.CoreException
    {
        // Resolve the IProject first so AI clients get the most specific
        // diagnostic ("Project not found" / "Project is closed") for bad
        // names. The readiness pre-check below refuses only the transient
        // BUILDING state and returns null for a missing/closed/unknown
        // project, so a bad name reaches these value-naming branches.
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return Resolved.failed(ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return Resolved.failed(ToolResult.error("Project is closed: " + projectName).toJson()); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        // Refuse only the transient BUILDING state; a missing/closed project
        // falls through to the value-naming "Project not found" below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return Resolved.failed(ToolResult.error(building).toJson());
        }

        // Reject dictionary storage projects, extensions and any
        // non-configuration EDT project — they would either resolve to a
        // non-null IDtProject and fail deep inside LangTool with a
        // confusing error, or simply do nothing useful.
        if (!project.hasNature(V8_CONFIGURATION_NATURE))
        {
            return Resolved.failed(ToolResult.error(
                "Not a V8 configuration project: " + projectName //$NON-NLS-1$
              + ". This action must be run on the configuration project (V8ConfigurationNature), " //$NON-NLS-1$
              + "not on a dictionary storage project or extension.").toJson()); //$NON-NLS-1$
        }

        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
        if (dtProject == null)
        {
            return Resolved.failed(ToolResult.error(
                "EDT has not yet resolved an IDtProject for: " + projectName //$NON-NLS-1$
              + ". The project may still be indexing — please retry.").toJson()); //$NON-NLS-1$
        }

        Object api = Activator.getDefault().getGenerateTranslationStringsApi();
        if (api == null)
        {
            return Resolved.failed(ToolResult.error(
                "LanguageTool IGenerateTranslationStringsApi is not available. " //$NON-NLS-1$
              + "Install LanguageTool in EDT.").toJson()); //$NON-NLS-1$
        }

        return Resolved.of(project, dtProject, api);
    }

    /**
     * Holder for the parsed/defaulted/validated scalar options (no side effects):
     * exactly one of the value fields / {@code error} is meaningful. {@code error}
     * carries the same error JSON (same case) the inline guards returned.
     */
    private static final class Options
    {
        final String storageId;
        final String collectModelType;
        final String fillUpType;
        final String providerId;
        final String error;

        private Options(String storageId, String collectModelType, String fillUpType, String providerId,
            String error)
        {
            this.storageId = storageId;
            this.collectModelType = collectModelType;
            this.fillUpType = fillUpType;
            this.providerId = providerId;
            this.error = error;
        }

        static Options of(String storageId, String collectModelType, String fillUpType, String providerId)
        {
            return new Options(storageId, collectModelType, fillUpType, providerId, null);
        }

        static Options failed(String error)
        {
            return new Options(null, null, null, null, error);
        }

        /**
         * Builds the {@code fillUpAndProviderId} argument. Provider suffix is
         * meaningful only for FROM_PROVIDER (other modes ignore providerId —
         * appending it would produce malformed values like
         * FROM_SOURCE_LANGUAGE:foo). The earlier validation already enforced
         * non-empty providerId when fillUpType is FROM_PROVIDER.
         */
        String fillUpAndProviderId()
        {
            return FILL_UP_FROM_PROVIDER.equals(fillUpType)
                ? fillUpType + ":" + providerId //$NON-NLS-1$
                : fillUpType;
        }
    }

    /**
     * Holder for the resolved configuration project / IDtProject / LanguageTool
     * API: exactly one of the value trio / {@code error} is non-null. {@code error}
     * carries the same error JSON (same case) the inline guards returned.
     */
    private static final class Resolved
    {
        final IProject project;
        final IDtProject dtProject;
        final Object api;
        final String error;

        private Resolved(IProject project, IDtProject dtProject, Object api, String error)
        {
            this.project = project;
            this.dtProject = dtProject;
            this.api = api;
            this.error = error;
        }

        static Resolved of(IProject project, IDtProject dtProject, Object api)
        {
            return new Resolved(project, dtProject, api, null);
        }

        static Resolved failed(String error)
        {
            return new Resolved(null, null, null, error);
        }
    }

}
