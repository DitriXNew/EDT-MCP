/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Tool that invokes the EDT "Translate configuration" action on a project.
 *
 * <p>Equivalent of the context-menu action
 * <em>Translation &rarr; Translate configuration</em> in EDT. Under the hood
 * this is the LanguageTool <em>SynchronizeProject</em> operation: it takes the
 * source configuration project's current state and the dictionaries from the
 * storages bound to it (which may live in external dictionary storage
 * projects — plain Eclipse projects with the dependentProjectNature — or
 * inside the configuration itself) and regenerates the translated artifacts.
 *
 * <p>Pass the project the user right-clicks in EDT — typically the source
 * (e.g. ru) configuration project. The API resolves the bound dictionary
 * storages automatically and regenerates each translated artifact
 * accordingly.
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

    private static final String TARGET_LANGUAGES = "targetLanguages"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "SYNCHRONIZE a 1C configuration's translated artifacts with the target languages. Does " //$NON-NLS-1$
            + "NOT translate anything itself: it regenerates the artifacts from translations ALREADY " //$NON-NLS-1$
            + "present in the bound dictionary storages, so text absent from the dictionaries stays " //$NON-NLS-1$
            + "untranslated - fill the dictionaries first (generate_translation_strings). Parameters " //$NON-NLS-1$
            + "and examples: get_tool_guide('translate_configuration')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "Project name (typically the source, e.g. the ru project). Required.", true) //$NON-NLS-1$
            .stringArrayProperty(TARGET_LANGUAGES,
                "Target language codes to synchronize (e.g. [\"en\"]). Required.", true) //$NON-NLS-1$
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

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        if (targetLanguages == null || targetLanguages.isEmpty())
        {
            return ToolResult.error("targetLanguages is required (e.g. [\"en\"])").toJson(); //$NON-NLS-1$
        }

        boolean mutationApiEntered = false;
        boolean mutationApiReturned = false;
        try
        {
            // Resolve the IProject first so AI clients get the most specific
            // diagnostic ("Project not found" / "Project is closed", naming the
            // value) for bad names.
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

            // The project is resolved and open above; refuse only the transient
            // BUILDING state here (a missing/closed name was already named above).
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
            if (dtProject == null)
            {
                return ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
            }

            // Vendor support (#642), before LanguageTool is touched: synchronizing a language the
            // configuration declares writes its synonyms and strings into the configuration's objects.
            ProjectContext.ConfigurationResult root = ctx.resolveMetadataRoot();
            if (!root.ok())
            {
                return root.errorJson();
            }
            String locked = inPlaceRefusal(root.scope(), projectName,
                declaredTargets(project, root.scope().configuration(), targetLanguages));
            if (locked != null)
            {
                return ToolResult.error(locked).toJson();
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
            mutationApiEntered = true;
            method.invoke(api, dtProject, targetLanguages);
            mutationApiReturned = true;

            BuildUtils.waitForDerivedData(project);

            return FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put(TARGET_LANGUAGES, String.join(", ", targetLanguages)) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .wrapContent("Translate configuration completed."); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String error = CliReflectionErrors.toErrorJson(e,
                "Translate configuration", "LanguageTool"); //$NON-NLS-1$ //$NON-NLS-2$
            if (mutationApiReturned)
            {
                return ToolResult.markErrorAfterMutation(error);
            }
            return mutationApiEntered ? ToolResult.markErrorWithUnknownMutationOutcome(error) : error;
        }
    }

    /**
     * The target languages the configuration itself declares - the ones a synchronize writes in
     * place, as synonyms and strings of the configuration's own objects. A language it does not
     * declare has no place in it, so that target can only be a dependent translation project.
     * Read inside a read of the project's model when there is one.
     *
     * @param project the source project, or {@code null} for a bare configuration
     * @param configuration the source configuration
     * @param targetLanguages the requested language codes
     * @return the declared ones, as requested
     */
    static List<String> declaredTargets(IProject project, Configuration configuration, List<String> targetLanguages)
    {
        if (configuration == null)
        {
            return new ArrayList<>();
        }
        Activator activator = project == null ? null : Activator.getDefault();
        IBmModelManager manager = activator == null ? null : activator.getBmModelManager();
        IBmModel model = manager == null ? null : manager.getModel(project);
        if (model == null)
        {
            return declaredIn(configuration, targetLanguages);
        }
        return BmTransactions.read(model, "TranslateVendorSupportCheck", //$NON-NLS-1$
            (tx, monitor) -> declaredIn(configuration, targetLanguages));
    }

    private static List<String> declaredIn(Configuration configuration, List<String> targetLanguages)
    {
        List<String> declared = new ArrayList<>();
        for (String target : targetLanguages)
        {
            for (Language language : configuration.getLanguages())
            {
                String code = language.getLanguageCode();
                if (target != null && code != null
                    && code.toLowerCase(Locale.ROOT).equals(target.trim().toLowerCase(Locale.ROOT)))
                {
                    declared.add(target);
                    break;
                }
            }
        }
        return declared;
    }

    /**
     * The refusal for synchronizing {@code declaredTargets} in place when vendor support locks the
     * configuration (#642). LanguageTool exposes no per-object scope for the run, so the whole
     * configuration is asked; an unanswerable check refuses.
     *
     * @param scope the source project's resolution root
     * @param projectName the project the call named
     * @param declaredTargets the target languages the configuration declares
     * @return the refusal message, or {@code null} when the run may proceed
     */
    static String inPlaceRefusal(MetadataScope scope, String projectName, List<String> declaredTargets)
    {
        if (scope == null || scope.isExternalObjects() || declaredTargets.isEmpty())
        {
            return null;
        }
        Configuration configuration = scope.configuration();
        if (configuration == null || VendorSupportGuard.allowsEdit(configuration))
        {
            return null;
        }
        return "'" + projectName + "' cannot be translated into " + String.join(", ", declaredTargets) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " in place: the configuration '" + configuration.getName() //$NON-NLS-1$
            + "' is under vendor support and its support rule does not allow changes (or that could not " //$NON-NLS-1$
            + "be checked), and synchronizing a language the configuration declares writes that " //$NON-NLS-1$
            + "language's synonyms and strings into its objects. Nothing was changed. Translate into a " //$NON-NLS-1$
            + "dependent translation project instead (a target language this configuration does not " //$NON-NLS-1$
            + "declare), or ask the user to allow changes in EDT's support settings (Configuration > " //$NON-NLS-1$
            + "Support > Support settings); this server does not change support settings."; //$NON-NLS-1$
    }
}
