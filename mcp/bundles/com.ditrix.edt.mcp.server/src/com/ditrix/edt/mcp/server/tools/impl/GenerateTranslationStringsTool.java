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

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
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
            .stringProperty("projectName", //$NON-NLS-1$
                "Configuration project name (V8ConfigurationNature), not a dictionary storage project. Required.", //$NON-NLS-1$
                true)
            .stringArrayProperty("targetLanguages", //$NON-NLS-1$
                "Target language codes to generate, e.g. [\"en\"]. Required.", true) //$NON-NLS-1$
            .stringProperty("storageId", //$NON-NLS-1$
                "Storage ID to write keys into (see get_translation_project_info). Default: \"edit:default\".") //$NON-NLS-1$
            .booleanProperty("collectInterface", //$NON-NLS-1$
                "Generate interface (.lstr) keys. Default: true.") //$NON-NLS-1$
            .booleanProperty("collectModel", //$NON-NLS-1$
                "Generate model (.trans) keys. Default: true.") //$NON-NLS-1$
            .stringProperty("collectModelType", //$NON-NLS-1$
                "Model mode: ANY | NONE | COMPUTED_ONLY | UNKNOWN_ONLY | TAGS_ONLY. Default: ANY.") //$NON-NLS-1$
            .stringProperty("fillUpType", //$NON-NLS-1$
                "Pre-fill source: NOT_FILLUP | FROM_SOURCE_LANGUAGE | FROM_PROVIDER. Default: NOT_FILLUP.") //$NON-NLS-1$
            .stringProperty("providerId", //$NON-NLS-1$
                "Translation provider ID; required only when fillUpType=FROM_PROVIDER (see get_translation_project_info).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# generate_translation_strings\n\n" //$NON-NLS-1$
            + "Generates translation strings (`.lstr` / `.trans` / `.dict`) for a 1C " //$NON-NLS-1$
            + "configuration. It scans the configuration's translatable features and " //$NON-NLS-1$
            + "writes the resulting keys into the storages declared on the project. " //$NON-NLS-1$
            + "Each storage routes either to an external dictionary storage project (a " //$NON-NLS-1$
            + "plain Eclipse project with the dependentProjectNature) or to the " //$NON-NLS-1$
            + "configuration itself, depending on `.settings/translation_storages.yml`. " //$NON-NLS-1$
            + "This is the programmatic equivalent of the EDT UI action " //$NON-NLS-1$
            + "**Translation -> Generate translation strings**.\n\n" //$NON-NLS-1$

            + "## When to use\n\n" //$NON-NLS-1$
            + "- You added or changed translatable content (synonyms, interface text, " //$NON-NLS-1$
            + "model strings) and need to (re)collect the keys for one or more target " //$NON-NLS-1$
            + "languages.\n" //$NON-NLS-1$
            + "- Run it on the **configuration project** (V8ConfigurationNature) only. " //$NON-NLS-1$
            + "Do NOT run it on a dictionary storage project (the plain Eclipse project " //$NON-NLS-1$
            + "where the `.lstr`/`.trans`/`.dict` files physically live) or on an " //$NON-NLS-1$
            + "extension: the tool rejects those with a clear error.\n" //$NON-NLS-1$
            + "- Requires **LanguageTool** installed in EDT (Help -> Install New " //$NON-NLS-1$
            + "Software; it is not bundled with the EDT base distribution on either " //$NON-NLS-1$
            + "2025.x or 2026.1). If it is missing the tool returns an actionable " //$NON-NLS-1$
            + "error telling you to install it.\n\n" //$NON-NLS-1$

            + "## Parameters\n\n" //$NON-NLS-1$
            + "- **projectName** (string, required) - the configuration project name. " //$NON-NLS-1$
            + "Must have V8ConfigurationNature. Passing a dictionary storage project or " //$NON-NLS-1$
            + "an extension is rejected.\n" //$NON-NLS-1$
            + "- **targetLanguages** (string[], required) - language codes to generate " //$NON-NLS-1$
            + "strings for, e.g. `[\"en\"]` or `[\"en\",\"de\"]`. Must be non-empty.\n" //$NON-NLS-1$
            + "- **storageId** (string, optional) - the storage to write generated keys " //$NON-NLS-1$
            + "into. Defaults to `edit:default`. Call `get_translation_project_info` to " //$NON-NLS-1$
            + "list the storages configured for the project.\n" //$NON-NLS-1$
            + "- **collectInterface** (boolean, optional, default true) - collect " //$NON-NLS-1$
            + "interface keys (the `.lstr` strings).\n" //$NON-NLS-1$
            + "- **collectModel** (boolean, optional, default true) - collect model keys " //$NON-NLS-1$
            + "(the `.trans` strings).\n" //$NON-NLS-1$
            + "- **collectModelType** (string, optional, default `ANY`) - how model " //$NON-NLS-1$
            + "strings are collected; see the Modes section below.\n" //$NON-NLS-1$
            + "- **fillUpType** (string, optional, default `NOT_FILLUP`) - how new keys " //$NON-NLS-1$
            + "are pre-filled; see the Modes section below.\n" //$NON-NLS-1$
            + "- **providerId** (string, optional) - translation provider ID, used " //$NON-NLS-1$
            + "**only** when `fillUpType=FROM_PROVIDER`. Required in that case; call " //$NON-NLS-1$
            + "`get_translation_project_info` to list available providers (e.g. " //$NON-NLS-1$
            + "`com.e1c.langtool.history.externalTranslationProvider`).\n\n" //$NON-NLS-1$

            + "## Modes\n\n" //$NON-NLS-1$
            + "**collectModelType** (applies when `collectModel` is true):\n" //$NON-NLS-1$
            + "- `ANY` - collect all model strings (default).\n" //$NON-NLS-1$
            + "- `NONE` - collect no model strings.\n" //$NON-NLS-1$
            + "- `COMPUTED_ONLY` - only computed strings.\n" //$NON-NLS-1$
            + "- `UNKNOWN_ONLY` - only strings whose language is unknown.\n" //$NON-NLS-1$
            + "- `TAGS_ONLY` - only tagged strings.\n\n" //$NON-NLS-1$
            + "**fillUpType** (how new keys get an initial value):\n" //$NON-NLS-1$
            + "- `NOT_FILLUP` - leave the target value empty (default).\n" //$NON-NLS-1$
            + "- `FROM_SOURCE_LANGUAGE` - copy the source-language text into the target.\n" //$NON-NLS-1$
            + "- `FROM_PROVIDER` - pre-translate via a provider; **requires** " //$NON-NLS-1$
            + "`providerId`.\n\n" //$NON-NLS-1$

            + "## Examples\n\n" //$NON-NLS-1$
            + "Minimal - collect English interface + model keys with defaults:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"projectName\": \"MyConfig\", \"targetLanguages\": [\"en\"] }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "Interface-only into a specific storage:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"projectName\": \"MyConfig\", \"targetLanguages\": [\"en\"], " //$NON-NLS-1$
            + "\"storageId\": \"edit:default\", \"collectModel\": false }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "Pre-fill from a provider:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"projectName\": \"MyConfig\", \"targetLanguages\": [\"en\"], " //$NON-NLS-1$
            + "\"fillUpType\": \"FROM_PROVIDER\", " //$NON-NLS-1$
            + "\"providerId\": \"com.e1c.langtool.history.externalTranslationProvider\" }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$

            + "## Notes\n\n" //$NON-NLS-1$
            + "- After generation the tool waits for EDT to rebuild derived data, then " //$NON-NLS-1$
            + "returns a Markdown summary (project, languages, storage, collect flags, " //$NON-NLS-1$
            + "model type, fill-up type, status).\n" //$NON-NLS-1$
            + "- Language codes are 1C language **codes** (e.g. `en`, `ru`), not display " //$NON-NLS-1$
            + "names.\n\n" //$NON-NLS-1$

            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `providerId` is required when (and only when) `fillUpType=FROM_PROVIDER`; " //$NON-NLS-1$
            + "for the other fill-up modes any `providerId` is ignored.\n" //$NON-NLS-1$
            + "- Wrong project type is the most common mistake: this runs on the " //$NON-NLS-1$
            + "configuration project, never on the dictionary storage project where the " //$NON-NLS-1$
            + "files live.\n" //$NON-NLS-1$
            + "- If the project is still indexing, the tool may report that EDT has not " //$NON-NLS-1$
            + "resolved an IDtProject yet - retry after indexing completes.\n" //$NON-NLS-1$
            + "- If LanguageTool is not installed the call fails with a clear " //$NON-NLS-1$
            + "\"install LanguageTool\" message.\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
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

        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
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

        if (FILL_UP_FROM_PROVIDER.equals(fillUpType) && providerId.isEmpty())
        {
            return ToolResult.error(
                "providerId is required when fillUpType=FROM_PROVIDER. " //$NON-NLS-1$
              + "Use get_translation_project_info to list available providers.").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Resolve the IProject first so AI clients get the most specific
            // diagnostic ("Project not found" / "Project is closed") for bad
            // names. The readiness pre-check below refuses only the transient
            // BUILDING state and returns null for a missing/closed/unknown
            // project, so a bad name reaches these value-naming branches.
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

            // Refuse only the transient BUILDING state; a missing/closed project
            // falls through to the value-naming "Project not found" below.
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }

            // Reject dictionary storage projects, extensions and any
            // non-configuration EDT project — they would either resolve to a
            // non-null IDtProject and fail deep inside LangTool with a
            // confusing error, or simply do nothing useful.
            if (!project.hasNature(V8_CONFIGURATION_NATURE))
            {
                return ToolResult.error(
                    "Not a V8 configuration project: " + projectName //$NON-NLS-1$
                  + ". This action must be run on the configuration project (V8ConfigurationNature), " //$NON-NLS-1$
                  + "not on a dictionary storage project or extension.").toJson(); //$NON-NLS-1$
            }

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
            if (dtProject == null)
            {
                return ToolResult.error(
                    "EDT has not yet resolved an IDtProject for: " + projectName //$NON-NLS-1$
                  + ". The project may still be indexing — please retry.").toJson(); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getGenerateTranslationStringsApi();
            if (api == null)
            {
                return ToolResult.error(
                    "LanguageTool IGenerateTranslationStringsApi is not available. " //$NON-NLS-1$
                  + "Install LanguageTool in EDT.").toJson(); //$NON-NLS-1$
            }

            // Build fillUpAndProviderId argument. Provider suffix is meaningful
            // only for FROM_PROVIDER (other modes ignore providerId — appending
            // it would produce malformed values like FROM_SOURCE_LANGUAGE:foo).
            // The earlier validation already enforced non-empty providerId when
            // fillUpType is FROM_PROVIDER, so no extra check is needed here.
            String fillUpAndProviderId = FILL_UP_FROM_PROVIDER.equals(fillUpType)
                ? fillUpType + ":" + providerId //$NON-NLS-1$
                : fillUpType;

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

            BuildUtils.waitForDerivedData(project);

            return FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put("targetLanguages", String.join(", ", targetLanguages)) //$NON-NLS-1$ //$NON-NLS-2$
                .put("storageId", storageId) //$NON-NLS-1$
                .put("collectInterface", collectInterface) //$NON-NLS-1$
                .put("collectModel", collectModel) //$NON-NLS-1$
                .put("collectModelType", collectModelType) //$NON-NLS-1$
                .put("fillUpType", fillUpType) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .wrapContent("Translation strings generated."); //$NON-NLS-1$
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
