/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EMap;
import org.osgi.framework.Bundle;
import org.osgi.service.prefs.BackingStoreException;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool that creates a NEW 1C configuration extension project in the EDT workspace
 * bound to a given base configuration project.
 *
 * <p>Wraps the EDT extension-creation wizard path:
 * <ol>
 *   <li>Validates inputs and checks that neither the new project nor the base are missing.</li>
 *   <li>Constructs a {@link Configuration} object for the extension (name, prefix, script
 *       variant from base config, purpose, optional compatibility mode, synonym/comment).</li>
 *   <li>Calls {@link IExtensionProjectManager#create(String, Version, Configuration, IProject,
 *       org.eclipse.core.runtime.IProgressMonitor)} in a background {@link Job} (never on the
 *       UI thread — unattended-safety rule) and joins with a 120-second timeout.</li>
 *   <li>Waits for the new project to reach the {@code STARTED} lifecycle state
 *       via {@link LifecycleWaiter}.</li>
 *   <li>Optionally applies v8codestyle {@code standardChecks}/{@code commonChecks} preferences
 *       when the {@code com.e1c.v8codestyle} bundle is active (guarded — no compile dependency).</li>
 * </ol>
 *
 * <p>{@code autoSortTopObjects} is accepted in the schema for API stability but is NOT applied
 * in this release because the exact enable-key in {@code com.e1c.v8codestyle.autosort.prefs}
 * could not be confirmed without running against a live wizard-created project; see the
 * {@code codestyle.autoSortNote} field in the response.
 */
public class CreateExtensionProjectTool implements IMcpTool
{
    public static final String NAME = "create_extension_project"; //$NON-NLS-1$

    /** Timeout (ms) for the background project-creation Job. */
    private static final long CREATE_TIMEOUT_MS = 120_000L;

    /** Timeout (ms) for the lifecycle-STARTED wait after create(). */
    private static final long STARTED_TIMEOUT_MS = 60_000L;

    /** Bundle symbolic name for the optional v8codestyle check plugin. */
    private static final String V8CODESTYLE_BUNDLE = "com.e1c.v8codestyle"; //$NON-NLS-1$

    /** Preference qualifier for v8codestyle project settings. */
    private static final String V8CODESTYLE_PREF_QUALIFIER = "com.e1c.v8codestyle"; //$NON-NLS-1$

    /** Preference key: enable standard BSL checks (CheckUtils.PREF_KEY_STANDARD_CHECKS). */
    private static final String PREF_STANDARD_CHECKS = "standardChecks"; //$NON-NLS-1$

    /** Preference key: enable common (project-level) checks (CheckUtils.PREF_KEY_COMMON_CHECKS). */
    private static final String PREF_COMMON_CHECKS = "commonChecks"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a NEW 1C configuration extension project in the EDT workspace " //$NON-NLS-1$
            + "bound to a base configuration project. The extension project is immediately " //$NON-NLS-1$
            + "available for metadata adoption (adopt_metadata_object) and BSL override. " //$NON-NLS-1$
            + "The name must not already exist as a project. " //$NON-NLS-1$
            + "standardChecks/commonChecks are applied only when com.e1c.v8codestyle is installed. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_extension_project')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("name", //$NON-NLS-1$
                "Name of the new extension Configuration object (required). " //$NON-NLS-1$
                    + "Must be a valid 1C identifier: starts with a letter or underscore, " //$NON-NLS-1$
                    + "then letters, digits and underscores only (Cyrillic allowed). " //$NON-NLS-1$
                    + "Also used as the default EDT project name if projectName is not supplied.", //$NON-NLS-1$
                true)
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Name of the BASE configuration EDT project the extension will extend (required). " //$NON-NLS-1$
                    + "Must be an existing, open V8 configuration project. Use list_projects to find it.", //$NON-NLS-1$
                true)
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT workspace project name to create. Defaults to '<baseProjectName>.<name>' when omitted.") //$NON-NLS-1$
            .stringProperty("prefix", //$NON-NLS-1$
                "NamePrefix for the extension (default empty string). " //$NON-NLS-1$
                    + "The wizard generates a value like 'Ext1_'; pass an explicit value or omit for empty.") //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Human-readable synonym for the extension Configuration. " //$NON-NLS-1$
                    + "Defaults to the 'name' value if omitted.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional free-text comment set on the extension Configuration.") //$NON-NLS-1$
            .enumProperty("purpose", //$NON-NLS-1$
                "Extension purpose (default Customization). " //$NON-NLS-1$
                    + "Customization = user adaptation; AddOn = add-on functionality; Patch = hotfix.", //$NON-NLS-1$
                "Customization", "AddOn", "Patch") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("compatibilityMode", //$NON-NLS-1$
                "Optional extension compatibility-mode string matching a CompatibilityMode enum literal " //$NON-NLS-1$
                    + "(e.g. 'Version8_3_10'); empty = factory default. Unknown values are rejected.") //$NON-NLS-1$
            .booleanProperty("standardChecks", //$NON-NLS-1$
                "Enable 1C:Standards BSL checks for the new project (default true). " //$NON-NLS-1$
                    + "Applied only when com.e1c.v8codestyle is installed; ignored otherwise.") //$NON-NLS-1$
            .booleanProperty("commonChecks", //$NON-NLS-1$
                "Enable common (project-level) BSL checks for the new project (default true). " //$NON-NLS-1$
                    + "Applied only when com.e1c.v8codestyle is installed; ignored otherwise.") //$NON-NLS-1$
            .booleanProperty("autoSortTopObjects", //$NON-NLS-1$
                "Reserved for future use: auto-sort top-level metadata objects. " //$NON-NLS-1$
                    + "Accepted but not yet applied in this release (see codestyle.autoSortNote in the response).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the project was created", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "'created' on success") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensionProject", //$NON-NLS-1$
                "EDT workspace project name of the created extension (round-trip key for sibling tools)") //$NON-NLS-1$
            .stringProperty("name", "Configuration name set on the extension") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baseProject", "Name of the base configuration project it extends") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("prefix", "NamePrefix applied to the extension") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("purpose", "ConfigurationExtensionPurpose value applied") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scriptVariant", "ScriptVariant inherited from the base configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("version", "Platform version string from the base project") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("state", "'ready' when lifecycle STARTED was reached, 'created' otherwise") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("codestyle", //$NON-NLS-1$
                "v8codestyle preference application result: {applied: bool, note: string, autoSortNote: string}") //$NON-NLS-1$
            .stringProperty("synonymNote", //$NON-NLS-1$
                "Present only when the synonym could not be applied (no resolvable language code).") //$NON-NLS-1$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
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
        // 1. Validate required parameters
        String argErr = JsonUtils.requireArguments(params, "name", "baseProjectName"); //$NON-NLS-1$ //$NON-NLS-2$
        if (argErr != null)
        {
            return argErr;
        }

        String configName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String prefix = JsonUtils.extractStringArgument(params, "prefix"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String purposeStr = JsonUtils.extractStringArgument(params, "purpose"); //$NON-NLS-1$
        String compatModeStr = JsonUtils.extractStringArgument(params, "compatibilityMode"); //$NON-NLS-1$
        boolean standardChecks = JsonUtils.extractBooleanArgument(params, "standardChecks", true); //$NON-NLS-1$
        boolean commonChecks = JsonUtils.extractBooleanArgument(params, "commonChecks", true); //$NON-NLS-1$
        // autoSortTopObjects is read to satisfy schema parity; not yet applied (see class-level doc)
        JsonUtils.extractBooleanArgument(params, "autoSortTopObjects", true); //$NON-NLS-1$

        // Trim and validate 'name'
        if (configName != null)
        {
            configName = configName.trim();
        }
        if (configName == null || configName.isEmpty())
        {
            return ToolResult.error("'name' must not be blank.").toJson(); //$NON-NLS-1$
        }
        if (!isValidIdentifier(configName))
        {
            return ToolResult.error("Invalid name '" + configName //$NON-NLS-1$
                + "'. A name must start with a letter or underscore and contain only " //$NON-NLS-1$
                + "letters, digits and underscores (Cyrillic letters are allowed).").toJson(); //$NON-NLS-1$
        }

        // Trim projectName if provided
        if (projectName != null)
        {
            projectName = projectName.trim();
        }

        // Normalize empties to null
        if (projectName != null && projectName.isEmpty())
        {
            projectName = null;
        }
        if (prefix == null)
        {
            prefix = ""; //$NON-NLS-1$
        }
        if (compatModeStr != null && compatModeStr.isEmpty())
        {
            compatModeStr = null;
        }

        // 2. Derive default EDT project name: <baseProjectName>.<configName>
        String effectiveProjectName = (projectName != null) ? projectName
            : baseProjectName + "." + configName; //$NON-NLS-1$

        // 3. Check the new project name does not already exist
        if (ProjectContext.of(effectiveProjectName).exists())
        {
            return ToolResult.error("Project already exists in workspace: " + effectiveProjectName //$NON-NLS-1$
                + ". Choose a different name or projectName.").toJson(); //$NON-NLS-1$
        }

        // 4. Validate the base project
        ProjectContext baseCtx = ProjectContext.of(baseProjectName);
        if (!baseCtx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(baseProjectName)).toJson();
        }
        if (!baseCtx.isOpen())
        {
            return ToolResult.error("Base project '" + baseProjectName //$NON-NLS-1$
                + "' exists but is not open. Open it first.").toJson(); //$NON-NLS-1$
        }
        IProject baseIProject = baseCtx.project();

        // 5. Validate the base project has V8ConfigurationNature (not an extension itself)
        try
        {
            if (!baseIProject.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature")) //$NON-NLS-1$
            {
                return ToolResult.error("'" + baseProjectName //$NON-NLS-1$
                    + "' is not a configuration project (V8ConfigurationNature). " //$NON-NLS-1$
                    + "Pass the BASE configuration's project name, not an extension.").toJson(); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError("create_extension_project: error checking nature for " + baseProjectName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to inspect base project nature: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // 6. Resolve services
        IExtensionProjectManager extMgr = Activator.getDefault().getExtensionProjectManager();
        if (extMgr == null)
        {
            return ToolResult.error(
                "IExtensionProjectManager service not available. The EDT platform may not be ready.").toJson(); //$NON-NLS-1$
        }

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ToolResult.error("IV8ProjectManager service not available.").toJson(); //$NON-NLS-1$
        }

        IV8Project baseV8Project = v8ProjectManager.getProject(baseIProject);
        if (baseV8Project == null)
        {
            return ToolResult.error("Could not obtain IV8Project for base project '" //$NON-NLS-1$
                + baseProjectName + "'. Ensure the project is fully loaded.").toJson(); //$NON-NLS-1$
        }

        Version version = baseV8Project.getVersion();

        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ToolResult.error("IModelObjectFactory (MD) not available. MdPlugin may not be ready.").toJson(); //$NON-NLS-1$
        }

        // 7. Resolve the base configuration for ScriptVariant and synonym language
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration baseConfig = (configProvider != null)
            ? configProvider.getConfiguration(baseIProject)
            : null;

        if (baseConfig == null)
        {
            return ToolResult.error("The configuration model of base project '" + baseProjectName //$NON-NLS-1$
                + "' is not loaded yet (the project may still be indexing). " //$NON-NLS-1$
                + "Wait until the project is ready and retry.").toJson(); //$NON-NLS-1$
        }
        ScriptVariant scriptVariant = baseConfig.getScriptVariant();
        if (scriptVariant == null)
        {
            scriptVariant = ScriptVariant.RUSSIAN;
        }

        // 8. Validate purpose
        ConfigurationExtensionPurpose purpose = ConfigurationExtensionPurpose.CUSTOMIZATION;
        if (purposeStr != null && !purposeStr.isEmpty())
        {
            // Map the wire value to the enum
            switch (purposeStr)
            {
                case "Customization": //$NON-NLS-1$
                    purpose = ConfigurationExtensionPurpose.CUSTOMIZATION;
                    break;
                case "AddOn": //$NON-NLS-1$
                    purpose = ConfigurationExtensionPurpose.ADD_ON;
                    break;
                case "Patch": //$NON-NLS-1$
                    purpose = ConfigurationExtensionPurpose.PATCH;
                    break;
                default:
                    return ToolResult.error("Unknown purpose value: '" + purposeStr //$NON-NLS-1$
                        + "'. Allowed values: Customization, AddOn, Patch.").toJson(); //$NON-NLS-1$
            }
        }

        // 9. Validate CompatibilityMode if supplied
        CompatibilityMode compatMode = null;
        if (compatModeStr != null)
        {
            // First try exact literal match
            compatMode = CompatibilityMode.get(compatModeStr);
            if (compatMode == null)
            {
                // Tolerant fallback: strip non-alphanumerics and compare case-insensitively
                // (handles 'Version8.3.10' vs 'Version8_3_10' and case variants)
                String normalizedInput = compatModeStr.replaceAll("[^A-Za-z0-9]", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
                for (CompatibilityMode candidate : CompatibilityMode.VALUES)
                {
                    String normalizedCandidate = candidate.getLiteral()
                        .replaceAll("[^A-Za-z0-9]", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
                    if (normalizedInput.equals(normalizedCandidate))
                    {
                        compatMode = candidate;
                        break;
                    }
                    // Also try getName() if different from getLiteral()
                    String normalizedName = candidate.getName()
                        .replaceAll("[^A-Za-z0-9]", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
                    if (normalizedInput.equals(normalizedName))
                    {
                        compatMode = candidate;
                        break;
                    }
                }
            }
            if (compatMode == null)
            {
                // Build a short example list from VALUES dynamically
                StringBuilder examples = new StringBuilder();
                int shown = 0;
                for (CompatibilityMode candidate : CompatibilityMode.VALUES)
                {
                    if (shown > 0)
                    {
                        examples.append(", "); //$NON-NLS-1$
                    }
                    examples.append("'").append(candidate.getLiteral()).append("'"); //$NON-NLS-1$ //$NON-NLS-2$
                    shown++;
                    if (shown >= 3)
                    {
                        break;
                    }
                }
                int total = CompatibilityMode.VALUES.size();
                return ToolResult.error("Unknown compatibilityMode value: '" + compatModeStr //$NON-NLS-1$
                    + "'. Use a CompatibilityMode enum literal (e.g. " + examples //$NON-NLS-1$
                    + (total > 3 ? " and " + (total - 3) + " more" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "), or omit for the factory default.").toJson(); //$NON-NLS-1$
            }
        }

        // 10. Build the extension Configuration model object (NOT inside a BM transaction — this is a
        //     high-level project creation operation, not a BM metadata mutation)
        Configuration config = (Configuration) factory.create(MdClassPackage.Literals.CONFIGURATION, version);
        factory.fillDefaultReferences(config);

        // Mandatory attributes for an extension Configuration
        config.setObjectBelonging(ObjectBelonging.ADOPTED);
        config.setName(configName);
        config.setNamePrefix(prefix);
        config.setScriptVariant(scriptVariant);
        config.setConfigurationExtensionPurpose(purpose);

        // Optional attributes
        if (compatMode != null)
        {
            config.setConfigurationExtensionCompatibilityMode(compatMode);
        }
        if (comment != null && !comment.isEmpty())
        {
            config.setComment(comment);
        }

        // Set synonym via language-code-keyed EMap (do not use Language.getName() — bug #2 in CLAUDE.md)
        String synonymValue = (synonym != null && !synonym.isEmpty()) ? synonym : configName;
        String langCode = MetadataLanguageUtils.resolveLanguageCode(baseConfig, null);
        // FIX 5: If base config gave no language code, fall back to the new extension config
        // (which was populated by fillDefaultReferences above).
        if (langCode == null)
        {
            langCode = MetadataLanguageUtils.resolveLanguageCode(config, null);
        }
        boolean synonymApplied = false;
        if (langCode != null)
        {
            EMap<String, String> synonymMap = config.getSynonym();
            if (synonymMap != null)
            {
                synonymMap.put(langCode, synonymValue);
                synonymApplied = true;
            }
        }

        // 11. Create the extension project in a background Job (never on the UI thread — trap 7)
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;
        final Configuration finalConfig = config;
        final IProject finalBaseIProject = baseIProject;

        Job createJob = new Job("create_extension_project: " + finalEffectiveProjectName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    createdHolder[0] = extMgr.create(
                        finalEffectiveProjectName, finalVersion, finalConfig, finalBaseIProject, monitor);
                }
                catch (Throwable t)
                {
                    errorHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };
        createJob.setUser(false);
        createJob.schedule();

        // Join the job with timeout
        try
        {
            createJob.join(CREATE_TIMEOUT_MS, new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Extension project creation was interrupted.").toJson(); //$NON-NLS-1$
        }

        // Check job state: if still running, it timed out
        if (createJob.getState() != Job.NONE)
        {
            createJob.cancel();
            // Re-check whether the project actually exists despite the timeout
            if (ProjectContext.of(finalEffectiveProjectName).exists())
            {
                // Creation completed slowly past the wait window — treat as success, but be
                // honest that the post-create steps (codestyle prefs) were skipped.
                Map<String, Object> slowCodestyle = new LinkedHashMap<>();
                slowCodestyle.put("applied", false); //$NON-NLS-1$
                slowCodestyle.put("note", //$NON-NLS-1$
                    "Not applied: creation exceeded the wait window; set the project preferences manually if needed."); //$NON-NLS-1$
                return ToolResult.success()
                    .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("extensionProject", finalEffectiveProjectName) //$NON-NLS-1$
                    .put("name", configName) //$NON-NLS-1$
                    .put("baseProject", baseProjectName) //$NON-NLS-1$
                    .put("prefix", prefix) //$NON-NLS-1$
                    .put("purpose", purpose.getLiteral()) //$NON-NLS-1$
                    .put("scriptVariant", scriptVariant.getLiteral()) //$NON-NLS-1$
                    .put("version", version.toString()) //$NON-NLS-1$
                    .put("state", "created") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("codestyle", slowCodestyle) //$NON-NLS-1$
                    .put("message", "Extension project '" + finalEffectiveProjectName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' was created (creation completed past the " //$NON-NLS-1$
                        + (CREATE_TIMEOUT_MS / 1000) + "s wait window; project now exists).") //$NON-NLS-1$
                    .toJson();
            }
            return ToolResult.error("Extension project creation timed out after " //$NON-NLS-1$
                + (CREATE_TIMEOUT_MS / 1000) + " seconds. The project may still appear shortly; " //$NON-NLS-1$
                + "if it does and is unwanted, remove it with delete_project.").toJson(); //$NON-NLS-1$
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("create_extension_project failed", errorHolder[0]); //$NON-NLS-1$
            return ToolResult.error("Failed to create extension project: " //$NON-NLS-1$
                + errorHolder[0].getMessage()).toJson();
        }

        Activator.logInfo("create_extension_project: created '" + finalEffectiveProjectName //$NON-NLS-1$
            + "' extending '" + baseProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // 12. Wait for lifecycle STARTED
        String projectState = "created"; //$NON-NLS-1$
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IProject newIProject = createdHolder[0] != null ? createdHolder[0]
                : ProjectContext.of(finalEffectiveProjectName).project();
            if (dtProjectManager != null && newIProject != null && newIProject.exists())
            {
                com._1c.g5.v8.dt.core.platform.IDtProject dtProject =
                    dtProjectManager.getDtProject(newIProject);
                if (dtProject != null)
                {
                    boolean started = LifecycleWaiter.waitForProjectStarted(dtProject, STARTED_TIMEOUT_MS);
                    if (started)
                    {
                        projectState = "ready"; //$NON-NLS-1$
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Non-fatal: lifecycle wait failed, project was created but not confirmed ready
            Activator.logError("create_extension_project: lifecycle wait error", e); //$NON-NLS-1$
        }

        // 13. Apply v8codestyle preferences (guarded — no compile dependency on com.e1c.v8codestyle)
        boolean codestyleApplied = false;
        String codestyleNote = ""; //$NON-NLS-1$
        // Installed is enough: the prefs are plain project-scoped entries the (lazily
        // activated) v8codestyle bundle reads later — do not require Bundle.ACTIVE.
        Bundle v8codestyleBundle = Platform.getBundle(V8CODESTYLE_BUNDLE);
        if (v8codestyleBundle != null)
        {
            try
            {
                ProjectContext newCtx = ProjectContext.of(finalEffectiveProjectName);
                IProject newProject = newCtx.exists() ? newCtx.project() : null;
                if (newProject != null)
                {
                    org.eclipse.core.runtime.preferences.IEclipsePreferences prefs =
                        new ProjectScope(newProject)
                            .getNode(V8CODESTYLE_PREF_QUALIFIER);
                    prefs.putBoolean(PREF_STANDARD_CHECKS, standardChecks);
                    prefs.putBoolean(PREF_COMMON_CHECKS, commonChecks);
                    prefs.flush();
                    codestyleApplied = true;
                }
            }
            catch (BackingStoreException e)
            {
                Activator.logError("create_extension_project: failed to write v8codestyle prefs", e); //$NON-NLS-1$
                codestyleNote = "v8codestyle prefs write failed: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        else
        {
            codestyleNote = "com.e1c.v8codestyle is not installed; standardChecks/commonChecks were not applied."; //$NON-NLS-1$
        }

        String autoSortNote = "autoSortTopObjects is reserved for a future release " //$NON-NLS-1$
            + "and was not applied (enable-key unconfirmed)."; //$NON-NLS-1$

        Map<String, Object> codestyleMap = new LinkedHashMap<>();
        codestyleMap.put("applied", codestyleApplied); //$NON-NLS-1$
        codestyleMap.put("note", codestyleNote); //$NON-NLS-1$
        codestyleMap.put("autoSortNote", autoSortNote); //$NON-NLS-1$

        ToolResult result = ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("extensionProject", finalEffectiveProjectName) //$NON-NLS-1$
            .put("name", configName) //$NON-NLS-1$
            .put("baseProject", baseProjectName) //$NON-NLS-1$
            .put("prefix", prefix) //$NON-NLS-1$
            .put("purpose", purpose.getLiteral()) //$NON-NLS-1$
            .put("scriptVariant", scriptVariant.getLiteral()) //$NON-NLS-1$
            .put("version", version.toString()) //$NON-NLS-1$
            .put("state", projectState) //$NON-NLS-1$
            .put("codestyle", codestyleMap) //$NON-NLS-1$
            .put("message", "Extension project '" + finalEffectiveProjectName //$NON-NLS-1$ //$NON-NLS-2$
                + "' created and bound to '" + baseProjectName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        // FIX 5: report when synonym could not be applied (language code indeterminate)
        if (!synonymApplied)
        {
            result.put("synonymNote", //$NON-NLS-1$
                "Synonym was not applied: could not determine a language code from " //$NON-NLS-1$
                    + "the base configuration or the new extension configuration."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Checks that a name is a valid 1C identifier: starts with a letter or underscore,
     * then letters, digits and underscores only. Cyrillic letters are valid.
     */
    private static boolean isValidIdentifier(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_')
            {
                return false;
            }
        }
        return true;
    }
}
