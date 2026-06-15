/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool that creates a new 1C project of one of three kinds:
 * <ul>
 *   <li><b>configuration</b> — a full standalone 1C configuration project</li>
 *   <li><b>extension</b> — a configuration extension bound to a base configuration project</li>
 *   <li><b>externalObjects</b> — an external data processors/reports project</li>
 * </ul>
 *
 * <p>The {@code projectKind} parameter selects the kind. The extension path reuses the
 * fully reviewed machinery from the former extension-only tool:
 * <ol>
 *   <li>Validates inputs and checks that neither the new project nor the base are missing.</li>
 *   <li>Constructs a {@link Configuration} object (or not, for externalObjects).</li>
 *   <li>Calls the appropriate project manager's {@code create()} method in a background
 *       {@link Job} (never on the UI thread — unattended-safety rule) via the shared
 *       {@link #runCreateJob} helper, and joins with a {@link #CREATE_TIMEOUT_MS} timeout.</li>
 *   <li>Waits for the new project to reach the {@code STARTED} lifecycle state
 *       via {@link LifecycleWaiter}.</li>
 *   <li>For {@code externalObjects}: if {@code scriptVariant} was supplied, calls
 *       {@link IExternalObjectProjectManager#setScriptVariant} after lifecycle wait
 *       (non-fatal on failure).</li>
 *   <li>Optionally applies v8codestyle {@code standardChecks}/{@code commonChecks}
 *       preferences when the {@code com.e1c.v8codestyle} bundle is installed (guarded —
 *       no compile dependency).</li>
 * </ol>
 *
 * <p>{@code autoSortTopObjects} is accepted in the schema for API stability but is NOT applied
 * in this release because the exact enable-key in {@code com.e1c.v8codestyle.autosort.prefs}
 * could not be confirmed without running against a live wizard-created project; see the
 * {@code codestyle.autoSortNote} field in the response.
 */
public class CreateProjectTool implements IMcpTool
{
    public static final String NAME = "create_project"; //$NON-NLS-1$

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

    /** Eclipse nature id for configuration projects. */
    private static final String NATURE_CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    /** Parameter/output key: the kind of project to create. */
    private static final String KEY_PROJECT_KIND = "projectKind"; //$NON-NLS-1$

    /** projectKind value: standalone configuration. */
    private static final String KIND_CONFIGURATION = "configuration"; //$NON-NLS-1$

    /** projectKind value: external data processors/reports project. */
    private static final String KIND_EXTERNAL_OBJECTS = "externalObjects"; //$NON-NLS-1$

    /** projectKind value: configuration extension. */
    private static final String KIND_EXTENSION = "extension"; //$NON-NLS-1$

    /** Parameter/output key: platform version string. */
    private static final String KEY_VERSION = "version"; //$NON-NLS-1$

    /** Parameter/output key: extension name prefix. */
    private static final String KEY_PREFIX = "prefix"; //$NON-NLS-1$

    /** Parameter/output key: extension purpose. */
    private static final String KEY_PURPOSE = "purpose"; //$NON-NLS-1$

    /** Parameter/output key: script variant. */
    private static final String KEY_SCRIPT_VARIANT = "scriptVariant"; //$NON-NLS-1$

    /** scriptVariant value: English. */
    private static final String SCRIPT_ENGLISH = "English"; //$NON-NLS-1$

    /** scriptVariant value: Russian. */
    private static final String SCRIPT_RUSSIAN = "Russian"; //$NON-NLS-1$

    /** Output key: name of the base configuration project. */
    private static final String KEY_BASE_PROJECT = "baseProject"; //$NON-NLS-1$

    /** Output key: project lifecycle state. */
    private static final String KEY_STATE = "state"; //$NON-NLS-1$

    /** Output key: v8codestyle preference application result. */
    private static final String KEY_CODESTYLE = "codestyle"; //$NON-NLS-1$

    /** Output key: note when the synonym could not be applied. */
    private static final String KEY_SYNONYM_NOTE = "synonymNote"; //$NON-NLS-1$

    /** Output key: note when the scriptVariant could not be applied. */
    private static final String KEY_SCRIPT_VARIANT_NOTE = "scriptVariantNote"; //$NON-NLS-1$

    /** action/state value: created. */
    private static final String VAL_CREATED = "created"; //$NON-NLS-1$

    /** Error prefix: project already exists in workspace. */
    private static final String ERR_PROJECT_EXISTS_PREFIX = "Project already exists in workspace: "; //$NON-NLS-1$

    /** Error suffix: choose a different name. */
    private static final String ERR_PROJECT_EXISTS_SUFFIX = ". Choose a different name or projectName."; //$NON-NLS-1$

    /** Regex matching any non-alphanumeric character. */
    private static final String RE_NON_ALNUM = "[^A-Za-z0-9]"; //$NON-NLS-1$

    /** Log/Job name prefix for project creation. */
    private static final String LOG_PREFIX = "create_project: "; //$NON-NLS-1$

    /** Message suffix: creation completed past the wait window. */
    private static final String MSG_WAIT_WINDOW_SUFFIX = "s wait window; project now exists)."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a NEW 1C project in the EDT workspace. " //$NON-NLS-1$
            + "projectKind selects the kind: 'configuration' (standalone), " //$NON-NLS-1$
            + "'extension' (bound to a base configuration), or " //$NON-NLS-1$
            + "'externalObjects' (external data processors/reports). " //$NON-NLS-1$
            + "The name must not already exist as a project. " //$NON-NLS-1$
            + "standardChecks/commonChecks are applied only when com.e1c.v8codestyle is installed. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_project')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty(KEY_PROJECT_KIND,
                "Kind of project to create (required): " //$NON-NLS-1$
                    + "'configuration' = standalone 1C configuration; " //$NON-NLS-1$
                    + "'extension' = configuration extension bound to a base project; " //$NON-NLS-1$
                    + "'externalObjects' = external data processors/reports project.", //$NON-NLS-1$
                true,
                KIND_CONFIGURATION, KIND_EXTENSION, KIND_EXTERNAL_OBJECTS)
            .stringProperty("name", //$NON-NLS-1$
                "Name of the new Configuration object (required). " //$NON-NLS-1$
                    + "For configuration/extension: the programmatic Configuration name. " //$NON-NLS-1$
                    + "For externalObjects: used as the default project name (no Configuration object exists). " //$NON-NLS-1$
                    + "Must be a valid 1C identifier: starts with a letter or underscore, " //$NON-NLS-1$
                    + "then letters, digits and underscores only (Cyrillic allowed). " //$NON-NLS-1$
                    + "Also used as the default EDT project name if projectName is not supplied.", //$NON-NLS-1$
                true)
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT workspace project name to create. " //$NON-NLS-1$
                    + "Default: extension -> '<baseProjectName>.<name>'; " //$NON-NLS-1$
                    + "configuration/externalObjects -> 'name'.") //$NON-NLS-1$
            .stringProperty(KEY_VERSION,
                "Platform version string, e.g. '8.3.27' (configuration and externalObjects only; " //$NON-NLS-1$
                    + "for extension: REJECTED — version is always inherited from the base configuration). " //$NON-NLS-1$
                    + "Default: Version.LATEST when omitted.") //$NON-NLS-1$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Name of the BASE configuration EDT project (required for extension; " //$NON-NLS-1$
                    + "REJECTED for configuration and externalObjects). " //$NON-NLS-1$
                    + "Must be an existing, open V8 configuration project. Use list_projects to find it.") //$NON-NLS-1$
            .stringProperty(KEY_PREFIX,
                "NamePrefix for the extension (extension only; REJECTED for other kinds). " //$NON-NLS-1$
                    + "Default: empty string. The wizard generates a value like 'Ext1_'; " //$NON-NLS-1$
                    + "pass an explicit value or omit for empty.") //$NON-NLS-1$
            .enumProperty(KEY_PURPOSE,
                "Extension purpose (extension only; REJECTED for other kinds). " //$NON-NLS-1$
                    + "Default: Customization. " //$NON-NLS-1$
                    + "Customization = user adaptation; AddOn = add-on functionality; Patch = hotfix.", //$NON-NLS-1$
                "Customization", "AddOn", "Patch") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("compatibilityMode", //$NON-NLS-1$
                "Optional extension compatibility-mode string matching a CompatibilityMode enum literal " //$NON-NLS-1$
                    + "(e.g. 'Version8_3_10'); empty = factory default. Unknown values are rejected. " //$NON-NLS-1$
                    + "Extension only; REJECTED for other kinds.") //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Human-readable synonym for the Configuration (configuration and extension only; " //$NON-NLS-1$
                    + "REJECTED for externalObjects). Defaults to the 'name' value if omitted.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional free-text comment set on the Configuration " //$NON-NLS-1$
                    + "(configuration and extension only; REJECTED for externalObjects).") //$NON-NLS-1$
            .enumProperty(KEY_SCRIPT_VARIANT,
                "Script variant (Russian or English). " //$NON-NLS-1$
                    + "configuration: sets the Configuration scriptVariant and default Language code (default Russian). " //$NON-NLS-1$
                    + "externalObjects: applied post-create via setScriptVariant (non-fatal on failure). " //$NON-NLS-1$
                    + "extension: REJECTED — scriptVariant is always inherited from the base configuration.", //$NON-NLS-1$
                SCRIPT_RUSSIAN, SCRIPT_ENGLISH)
            .booleanProperty(PREF_STANDARD_CHECKS,
                "Enable 1C:Standards BSL checks for the new project (default true). " //$NON-NLS-1$
                    + "Applied to all kinds only when com.e1c.v8codestyle is installed; ignored otherwise.") //$NON-NLS-1$
            .booleanProperty(PREF_COMMON_CHECKS,
                "Enable common (project-level) BSL checks for the new project (default true). " //$NON-NLS-1$
                    + "Applied to all kinds only when com.e1c.v8codestyle is installed; ignored otherwise.") //$NON-NLS-1$
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
            .stringProperty(McpKeys.ACTION, "'created' on success") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT,
                "EDT workspace project name of the created project (round-trip key for sibling tools)") //$NON-NLS-1$
            .stringProperty(KEY_PROJECT_KIND, "Kind of project created: configuration, extension, or externalObjects") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Configuration name (configuration/extension) or the supplied identifier (externalObjects).") //$NON-NLS-1$
            .stringProperty(KEY_BASE_PROJECT,
                "Name of the base configuration project it extends (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_PREFIX, "NamePrefix applied (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_PURPOSE, "ConfigurationExtensionPurpose value applied (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_SCRIPT_VARIANT, "ScriptVariant applied or inherited") //$NON-NLS-1$
            .stringProperty(KEY_VERSION, "Platform version string used for project creation") //$NON-NLS-1$
            .stringProperty(KEY_STATE, "'ready' when lifecycle STARTED was reached, 'created' otherwise") //$NON-NLS-1$
            .objectProperty(KEY_CODESTYLE,
                "v8codestyle preference application result: {applied: bool, note: string, autoSortNote: string}") //$NON-NLS-1$
            .stringProperty(KEY_SYNONYM_NOTE,
                "Present only when the synonym could not be applied (no resolvable language code).") //$NON-NLS-1$
            .stringProperty(KEY_SCRIPT_VARIANT_NOTE,
                "Present only when a requested scriptVariant could not be applied (externalObjects).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable confirmation message") //$NON-NLS-1$
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
        String argErr = JsonUtils.requireArguments(params, KEY_PROJECT_KIND, "name"); //$NON-NLS-1$
        if (argErr != null)
        {
            return argErr;
        }

        String projectKind = JsonUtils.extractStringArgument(params, KEY_PROJECT_KIND);
        String configName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String versionStr = JsonUtils.extractStringArgument(params, KEY_VERSION);
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String prefix = JsonUtils.extractStringArgument(params, KEY_PREFIX);
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String purposeStr = JsonUtils.extractStringArgument(params, KEY_PURPOSE);
        String compatModeStr = JsonUtils.extractStringArgument(params, "compatibilityMode"); //$NON-NLS-1$
        String scriptVariantStr = JsonUtils.extractStringArgument(params, KEY_SCRIPT_VARIANT);
        boolean standardChecks = JsonUtils.extractBooleanArgument(params, PREF_STANDARD_CHECKS, true);
        boolean commonChecks = JsonUtils.extractBooleanArgument(params, PREF_COMMON_CHECKS, true);
        // autoSortTopObjects is read to satisfy schema parity; not yet applied (see class-level doc)
        JsonUtils.extractBooleanArgument(params, "autoSortTopObjects", true); //$NON-NLS-1$

        // 2. Validate projectKind
        boolean isConfiguration = KIND_CONFIGURATION.equals(projectKind);
        boolean isExtension = KIND_EXTENSION.equals(projectKind);
        boolean isExternalObjects = KIND_EXTERNAL_OBJECTS.equals(projectKind);

        if (!isConfiguration && !isExtension && !isExternalObjects)
        {
            return ToolResult.error("Unknown projectKind: '" + projectKind //$NON-NLS-1$
                + "'. Allowed values: configuration, extension, externalObjects.").toJson(); //$NON-NLS-1$
        }

        // 3. Validate kind-specific parameter constraints
        String constraintErr = validateKindConstraints(projectKind, isExtension, isExternalObjects,
            versionStr, baseProjectName, prefix, purposeStr, compatModeStr, synonym, comment, scriptVariantStr);
        if (constraintErr != null)
        {
            return constraintErr;
        }

        // 4. Validate 'name'
        if (configName != null)
        {
            configName = configName.trim();
        }
        String nameErr = validateName(configName);
        if (nameErr != null)
        {
            return nameErr;
        }

        // 5. Trim projectName if provided
        projectName = normalizeProjectName(projectName);

        // Normalize empties
        if (prefix == null)
        {
            prefix = ""; //$NON-NLS-1$
        }
        if (compatModeStr != null && compatModeStr.isEmpty())
        {
            compatModeStr = null;
        }
        if (versionStr != null && versionStr.isEmpty())
        {
            versionStr = null;
        }

        // Dispatch to kind-specific handler
        if (isExtension)
        {
            return executeExtension(configName, projectName, baseProjectName, prefix, synonym, comment,
                purposeStr, compatModeStr, standardChecks, commonChecks);
        }
        else if (isConfiguration)
        {
            return executeConfiguration(configName, projectName, versionStr, synonym, comment,
                scriptVariantStr, standardChecks, commonChecks);
        }
        else
        {
            return executeExternalObjects(configName, projectName, versionStr, scriptVariantStr,
                standardChecks, commonChecks);
        }
    }

    /**
     * Validates the (already trimmed) Configuration name: must be non-blank and a valid
     * 1C identifier.
     *
     * @param configName the trimmed name (may be {@code null})
     * @return a ready-to-return JSON error string when invalid, or {@code null} when valid
     */
    private static String validateName(String configName)
    {
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
        return null;
    }

    /**
     * Trims the supplied EDT project name and collapses a now-empty value to {@code null}
     * so the kind handlers fall back to their default-name derivation.
     *
     * @param projectName the raw project name (may be {@code null})
     * @return the trimmed project name, or {@code null} when absent or blank
     */
    private static String normalizeProjectName(String projectName)
    {
        if (projectName != null)
        {
            projectName = projectName.trim();
        }
        if (projectName != null && projectName.isEmpty())
        {
            projectName = null;
        }
        return projectName;
    }

    /**
     * Validates the kind-specific parameter constraints (which parameters are allowed for
     * which {@code projectKind}) plus the strict scriptVariant value check.
     *
     * @return a ready-to-return JSON error string when a constraint is violated, or
     *     {@code null} when all constraints pass
     */
    private static String validateKindConstraints(String projectKind, boolean isExtension,
        boolean isExternalObjects, String versionStr, String baseProjectName, String prefix,
        String purposeStr, String compatModeStr, String synonym, String comment, String scriptVariantStr)
    {
        if (isExtension && versionStr != null && !versionStr.isEmpty())
        {
            return ToolResult.error(
                "'version' is not valid for projectKind=extension: " //$NON-NLS-1$
                    + "the extension always inherits the version from the base configuration. " //$NON-NLS-1$
                    + "Remove the 'version' parameter.").toJson(); //$NON-NLS-1$
        }
        if (!isExtension && baseProjectName != null && !baseProjectName.isEmpty())
        {
            return ToolResult.error(
                "'baseProjectName' is only valid for projectKind=extension. " //$NON-NLS-1$
                    + "Remove the 'baseProjectName' parameter for kind=" + projectKind + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isExtension && prefix != null && !prefix.isEmpty())
        {
            return ToolResult.error("'prefix' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        if (!isExtension && purposeStr != null && !purposeStr.isEmpty())
        {
            return ToolResult.error("'purpose' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        if (!isExtension && compatModeStr != null && !compatModeStr.isEmpty())
        {
            return ToolResult.error("'compatibilityMode' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        if (isExternalObjects && synonym != null && !synonym.isEmpty())
        {
            return ToolResult.error("'synonym' is not valid for projectKind=externalObjects.").toJson(); //$NON-NLS-1$
        }
        if (isExternalObjects && comment != null && !comment.isEmpty())
        {
            return ToolResult.error("'comment' is not valid for projectKind=externalObjects.").toJson(); //$NON-NLS-1$
        }
        if (isExtension && scriptVariantStr != null && !scriptVariantStr.isEmpty())
        {
            return ToolResult.error(
                "'scriptVariant' is not valid for projectKind=extension: " //$NON-NLS-1$
                    + "the extension always inherits the scriptVariant from the base configuration. " //$NON-NLS-1$
                    + "Remove the 'scriptVariant' parameter.").toJson(); //$NON-NLS-1$
        }

        // Strict scriptVariant validation: must be exactly "Russian" or "English" (case-insensitive)
        // when supplied for configuration or externalObjects.
        if (!isExtension && scriptVariantStr != null && !scriptVariantStr.isEmpty()
            && !SCRIPT_RUSSIAN.equalsIgnoreCase(scriptVariantStr)
            && !SCRIPT_ENGLISH.equalsIgnoreCase(scriptVariantStr))
        {
            return ToolResult.error("Invalid scriptVariant value: '" + scriptVariantStr //$NON-NLS-1$
                + "'. Allowed values: 'Russian', 'English'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    // ─────────────────────── EXTENSION path ──────────────────────────────────

    private String executeExtension(String configName, String projectName, String baseProjectName,
        String prefix, String synonym, String comment, String purposeStr, String compatModeStr,
        boolean standardChecks, boolean commonChecks)
    {
        // baseProjectName required for extension
        if (baseProjectName == null || baseProjectName.trim().isEmpty())
        {
            return ToolResult.error(
                "'baseProjectName' is required for projectKind=extension. " //$NON-NLS-1$
                    + "Use list_projects to find the base configuration project name.").toJson(); //$NON-NLS-1$
        }
        baseProjectName = baseProjectName.trim();

        // Derive default EDT project name: <baseProjectName>.<configName>
        String effectiveProjectName = (projectName != null) ? projectName
            : baseProjectName + "." + configName; //$NON-NLS-1$

        // Check the new project name does not already exist
        if (ProjectContext.of(effectiveProjectName).exists())
        {
            return ToolResult.error(ERR_PROJECT_EXISTS_PREFIX + effectiveProjectName
                + ERR_PROJECT_EXISTS_SUFFIX).toJson();
        }

        // Validate the base project
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

        // Validate the base project has V8ConfigurationNature (not an extension itself)
        try
        {
            if (!baseIProject.hasNature(NATURE_CONFIGURATION))
            {
                return ToolResult.error("'" + baseProjectName //$NON-NLS-1$
                    + "' is not a configuration project (V8ConfigurationNature). " //$NON-NLS-1$
                    + "Pass the BASE configuration's project name, not an extension.").toJson(); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError(LOG_PREFIX + "error checking nature for " + baseProjectName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to inspect base project nature: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Resolve services
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

        // Resolve the base configuration for ScriptVariant and synonym language
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

        // Validate purpose
        ConfigurationExtensionPurpose purpose = ConfigurationExtensionPurpose.CUSTOMIZATION;
        if (purposeStr != null && !purposeStr.isEmpty())
        {
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

        // Validate CompatibilityMode if supplied
        CompatibilityMode compatMode = null;
        if (compatModeStr != null)
        {
            compatMode = CompatibilityMode.get(compatModeStr);
            if (compatMode == null)
            {
                String normalizedInput = compatModeStr.replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
                for (CompatibilityMode candidate : CompatibilityMode.VALUES)
                {
                    String normalizedCandidate = candidate.getLiteral()
                        .replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
                    if (normalizedInput.equals(normalizedCandidate))
                    {
                        compatMode = candidate;
                        break;
                    }
                    String normalizedName = candidate.getName()
                        .replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
                    if (normalizedInput.equals(normalizedName))
                    {
                        compatMode = candidate;
                        break;
                    }
                }
            }
            if (compatMode == null)
            {
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

        // Build the extension Configuration model object
        Configuration config = (Configuration) factory.create(MdClassPackage.Literals.CONFIGURATION, version);
        factory.fillDefaultReferences(config);

        config.setObjectBelonging(ObjectBelonging.ADOPTED);
        config.setName(configName);
        config.setNamePrefix(prefix);
        config.setScriptVariant(scriptVariant);
        config.setConfigurationExtensionPurpose(purpose);

        if (compatMode != null)
        {
            config.setConfigurationExtensionCompatibilityMode(compatMode);
        }
        if (comment != null && !comment.isEmpty())
        {
            config.setComment(comment);
        }

        // Set synonym via language-code-keyed EMap
        String synonymValue = (synonym != null && !synonym.isEmpty()) ? synonym : configName;
        String langCode = MetadataLanguageUtils.resolveLanguageCode(baseConfig, null);
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

        // Create the extension project in a background Job
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;
        final Configuration finalConfig = config;
        final IProject finalBaseIProject = baseIProject;

        Job createJob = new Job(LOG_PREFIX + finalEffectiveProjectName)
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

        final ScriptVariant finalScriptVariant = scriptVariant;
        final ConfigurationExtensionPurpose finalPurpose = purpose;

        CreateJobResult jobResult = runCreateJob(createJob, finalEffectiveProjectName, KIND_EXTENSION);
        if (jobResult.status == CreateStatus.SLOW_EXISTS)
        {
            // Creation completed past the wait window — build the full extension response
            ToolResult slowResult = ToolResult.success()
                .put(McpKeys.ACTION, VAL_CREATED)
                .put(McpKeys.PROJECT, finalEffectiveProjectName)
                .put(KEY_PROJECT_KIND, KIND_EXTENSION)
                .put("name", configName) //$NON-NLS-1$
                .put(KEY_BASE_PROJECT, baseProjectName)
                .put(KEY_PREFIX, prefix)
                .put(KEY_PURPOSE, finalPurpose.getLiteral())
                .put(KEY_SCRIPT_VARIANT, finalScriptVariant.getLiteral())
                .put(KEY_VERSION, version.toString())
                .put(KEY_STATE, VAL_CREATED)
                .put(KEY_CODESTYLE, slowPathCodestyleMap())
                .put(McpKeys.MESSAGE, "Extension project '" + finalEffectiveProjectName //$NON-NLS-1$
                    + "' created and bound to '" + baseProjectName //$NON-NLS-1$
                    + "' (creation completed past the " //$NON-NLS-1$
                    + (CREATE_TIMEOUT_MS / 1000) + MSG_WAIT_WINDOW_SUFFIX);
            // Mirror the normal success path: report when the synonym could not be applied.
            if (!synonymApplied)
            {
                slowResult.put(KEY_SYNONYM_NOTE,
                    "Synonym was not applied: could not determine a language code from " //$NON-NLS-1$
                        + "the base configuration or the new extension configuration."); //$NON-NLS-1$
            }
            return slowResult.toJson();
        }
        if (jobResult.errorJson != null)
        {
            return jobResult.errorJson;
        }

        // Check for creation errors reported via errorHolder
        if (errorHolder[0] != null)
        {
            Activator.logError("create_project (extension) failed", errorHolder[0]); //$NON-NLS-1$
            return ToolResult.error("Failed to create extension project: " //$NON-NLS-1$
                + errorHolder[0].getMessage()).toJson();
        }

        Activator.logInfo(LOG_PREFIX + "created extension '" + finalEffectiveProjectName //$NON-NLS-1$
            + "' extending '" + baseProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED
        String projectState = waitForLifecycle(finalEffectiveProjectName, createdHolder[0], KIND_EXTENSION);

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap = applyCodestylePrefs(finalEffectiveProjectName, standardChecks, commonChecks);

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, finalEffectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTENSION)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_BASE_PROJECT, baseProjectName)
            .put(KEY_PREFIX, prefix)
            .put(KEY_PURPOSE, finalPurpose.getLiteral())
            .put(KEY_SCRIPT_VARIANT, finalScriptVariant.getLiteral())
            .put(KEY_VERSION, version.toString())
            .put(KEY_STATE, projectState)
            .put(KEY_CODESTYLE, codestyleMap)
            .put(McpKeys.MESSAGE, "Extension project '" + finalEffectiveProjectName //$NON-NLS-1$
                + "' created and bound to '" + baseProjectName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        if (!synonymApplied)
        {
            result.put(KEY_SYNONYM_NOTE,
                "Synonym was not applied: could not determine a language code from " //$NON-NLS-1$
                    + "the base configuration or the new extension configuration."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    // ─────────────────────── CONFIGURATION path ──────────────────────────────

    private String executeConfiguration(String configName, String projectName, String versionStr,
        String synonym, String comment, String scriptVariantStr, boolean standardChecks, boolean commonChecks)
    {
        // Derive default EDT project name: <configName>
        String effectiveProjectName = (projectName != null) ? projectName : configName;

        // Check the new project name does not already exist
        if (ProjectContext.of(effectiveProjectName).exists())
        {
            return ToolResult.error(ERR_PROJECT_EXISTS_PREFIX + effectiveProjectName
                + ERR_PROJECT_EXISTS_SUFFIX).toJson();
        }

        // Parse version
        Version version;
        try
        {
            version = (versionStr != null) ? new Version(versionStr) : Version.LATEST;
        }
        catch (Exception e)
        {
            return ToolResult.error("Invalid version string '" + versionStr //$NON-NLS-1$
                + "'. Use a dot-separated version like '8.3.27'. " //$NON-NLS-1$
                + "Original error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Resolve MD factory
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ToolResult.error("IModelObjectFactory (MD) not available. MdPlugin may not be ready.").toJson(); //$NON-NLS-1$
        }

        // Resolve IConfigurationProjectManager
        IConfigurationProjectManager configMgr = Activator.getDefault().getConfigurationProjectManager();
        if (configMgr == null)
        {
            return ToolResult.error(
                "IConfigurationProjectManager service not available. The EDT platform may not be ready.").toJson(); //$NON-NLS-1$
        }

        // Resolve scriptVariant
        boolean isRussian = scriptVariantStr == null || scriptVariantStr.isEmpty()
            || SCRIPT_RUSSIAN.equalsIgnoreCase(scriptVariantStr);
        ScriptVariant scriptVariant = isRussian ? ScriptVariant.RUSSIAN : ScriptVariant.ENGLISH;
        String langCode = isRussian ? "ru" : "en"; //$NON-NLS-1$ //$NON-NLS-2$
        String langName = isRussian ? SCRIPT_RUSSIAN : SCRIPT_ENGLISH;

        // Build Configuration model object (NOT inside a BM transaction)
        Configuration config = (Configuration) factory.create(MdClassPackage.Literals.CONFIGURATION, version);
        factory.fillDefaultReferences(config);

        // ObjectBelonging stays default OWN for a standalone configuration
        config.setName(configName);
        config.setScriptVariant(scriptVariant);

        // Ensure the configuration has a default Language (per ConfigurationWizard recipe)
        // If fillDefaultReferences already produced a language, reuse it; otherwise create one.
        String effectiveLangCode = langCode;
        setupConfigurationLanguage(config, langCode, langName);

        // Optional attributes
        if (comment != null && !comment.isEmpty())
        {
            config.setComment(comment);
        }

        // Set synonym via language-code-keyed EMap
        String synonymValue = (synonym != null && !synonym.isEmpty()) ? synonym : configName;
        boolean synonymApplied = false;
        EMap<String, String> synonymMap = config.getSynonym();
        if (synonymMap != null)
        {
            synonymMap.put(effectiveLangCode, synonymValue);
            synonymApplied = true;
        }

        // Create the configuration project in a background Job
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;
        final Configuration finalConfig = config;

        Job createJob = new Job(LOG_PREFIX + finalEffectiveProjectName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    createdHolder[0] = configMgr.create(
                        finalEffectiveProjectName, finalVersion, finalConfig, monitor);
                }
                catch (Throwable t)
                {
                    errorHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };

        final ScriptVariant finalScriptVariant = scriptVariant;

        CreateJobResult jobResult = runCreateJob(createJob, finalEffectiveProjectName, KIND_CONFIGURATION);
        if (jobResult.status == CreateStatus.SLOW_EXISTS)
        {
            // Creation completed past the wait window — build the full configuration response
            return buildConfigurationSlowResponse(finalEffectiveProjectName, configName,
                finalScriptVariant, finalVersion, synonymApplied);
        }
        if (jobResult.errorJson != null)
        {
            return jobResult.errorJson;
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("create_project (configuration) failed", errorHolder[0]); //$NON-NLS-1$
            return ToolResult.error("Failed to create configuration project: " //$NON-NLS-1$
                + errorHolder[0].getMessage()).toJson();
        }

        Activator.logInfo(LOG_PREFIX + "created configuration '" + finalEffectiveProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED
        String projectState = waitForLifecycle(finalEffectiveProjectName, createdHolder[0], KIND_CONFIGURATION);

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap = applyCodestylePrefs(finalEffectiveProjectName, standardChecks, commonChecks);

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, finalEffectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_CONFIGURATION)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_SCRIPT_VARIANT, finalScriptVariant.getLiteral())
            .put(KEY_VERSION, finalVersion.toString())
            .put(KEY_STATE, projectState)
            .put(KEY_CODESTYLE, codestyleMap)
            .put(McpKeys.MESSAGE, "Configuration project '" + finalEffectiveProjectName + "' created."); //$NON-NLS-1$ //$NON-NLS-2$
        if (!synonymApplied)
        {
            result.put(KEY_SYNONYM_NOTE,
                "Synonym was not applied: synonym map was not available on the new Configuration."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Ensures the new standalone configuration has a default {@link Language} aligned to the
     * requested script variant (per the ConfigurationWizard recipe). If
     * {@code fillDefaultReferences} already produced a language it is realigned in place;
     * otherwise a new language is created and made the default.
     *
     * @param config the in-memory Configuration model object being built
     * @param langCode the language code to apply (e.g. {@code "ru"} or {@code "en"})
     * @param langName the language name to apply (e.g. {@code "Russian"} or {@code "English"})
     */
    private static void setupConfigurationLanguage(Configuration config, String langCode, String langName)
    {
        if (!config.getLanguages().isEmpty())
        {
            // Align the existing language to the requested script variant
            Language existingLang = config.getLanguages().get(0);
            existingLang.setName(langName);
            existingLang.setLanguageCode(langCode);
            EMap<String, String> existingSynonym = existingLang.getSynonym();
            if (existingSynonym != null)
            {
                existingSynonym.clear();
                existingSynonym.put(langCode, langName);
            }
            if (config.getDefaultLanguage() == null)
            {
                config.setDefaultLanguage(existingLang);
            }
        }
        else
        {
            // Create a Language exactly per R3 recipe
            Language lang = MdClassFactory.eINSTANCE.createLanguage();
            lang.setUuid(UUID.randomUUID());
            lang.setName(langName);
            lang.setLanguageCode(langCode);
            EMap<String, String> langSynonym = lang.getSynonym();
            if (langSynonym != null)
            {
                langSynonym.put(langCode, langName);
            }
            config.getLanguages().add(lang);
            config.setDefaultLanguage(lang);
        }
    }

    /**
     * Builds the configuration slow-path response (creation completed past the wait window).
     * Mirrors the normal success path, including the synonym note when the synonym could not
     * be applied.
     *
     * @return the response JSON string
     */
    private static String buildConfigurationSlowResponse(String effectiveProjectName, String configName,
        ScriptVariant scriptVariant, Version version, boolean synonymApplied)
    {
        ToolResult slowResult = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_CONFIGURATION)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_SCRIPT_VARIANT, scriptVariant.getLiteral())
            .put(KEY_VERSION, version.toString())
            .put(KEY_STATE, VAL_CREATED)
            .put(KEY_CODESTYLE, slowPathCodestyleMap())
            .put(McpKeys.MESSAGE, "Configuration project '" + effectiveProjectName //$NON-NLS-1$
                + "' created (creation completed past the " //$NON-NLS-1$
                + (CREATE_TIMEOUT_MS / 1000) + MSG_WAIT_WINDOW_SUFFIX);
        // Mirror the normal success path: report when the synonym could not be applied.
        if (!synonymApplied)
        {
            slowResult.put(KEY_SYNONYM_NOTE,
                "Synonym was not applied: synonym map was not available on the new Configuration."); //$NON-NLS-1$
        }
        return slowResult.toJson();
    }

    // ─────────────────────── EXTERNAL OBJECTS path ───────────────────────────

    private String executeExternalObjects(String configName, String projectName, String versionStr,
        String scriptVariantStr, boolean standardChecks, boolean commonChecks)
    {
        // Derive default EDT project name: <configName>
        String effectiveProjectName = (projectName != null) ? projectName : configName;

        // Check the new project name does not already exist
        if (ProjectContext.of(effectiveProjectName).exists())
        {
            return ToolResult.error(ERR_PROJECT_EXISTS_PREFIX + effectiveProjectName
                + ERR_PROJECT_EXISTS_SUFFIX).toJson();
        }

        // Parse version
        Version version;
        try
        {
            version = (versionStr != null) ? new Version(versionStr) : Version.LATEST;
        }
        catch (Exception e)
        {
            return ToolResult.error("Invalid version string '" + versionStr //$NON-NLS-1$
                + "'. Use a dot-separated version like '8.3.27'. " //$NON-NLS-1$
                + "Original error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Resolve IExternalObjectProjectManager
        IExternalObjectProjectManager extObjMgr = Activator.getDefault().getExternalObjectProjectManager();
        if (extObjMgr == null)
        {
            return ToolResult.error(
                "IExternalObjectProjectManager service not available. The EDT platform may not be ready.").toJson(); //$NON-NLS-1$
        }

        // Create the external objects project in a background Job
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;

        Job createJob = new Job(LOG_PREFIX + finalEffectiveProjectName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    // null, null = empty project (no pre-seeded MdObject, no parent)
                    createdHolder[0] = extObjMgr.create(
                        finalEffectiveProjectName, finalVersion, null, null, monitor);
                }
                catch (Throwable t)
                {
                    errorHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };

        CreateJobResult jobResult = runCreateJob(createJob, finalEffectiveProjectName, KIND_EXTERNAL_OBJECTS);
        if (jobResult.status == CreateStatus.SLOW_EXISTS)
        {
            // Creation completed past the wait window — build the full externalObjects response
            return buildExternalObjectsSlowResponse(finalEffectiveProjectName, configName,
                finalVersion, scriptVariantStr);
        }
        if (jobResult.errorJson != null)
        {
            return jobResult.errorJson;
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("create_project (externalObjects) failed", errorHolder[0]); //$NON-NLS-1$
            return ToolResult.error("Failed to create external objects project: " //$NON-NLS-1$
                + errorHolder[0].getMessage()).toJson();
        }

        Activator.logInfo(LOG_PREFIX + "created externalObjects '" + finalEffectiveProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED
        String projectState = waitForLifecycle(finalEffectiveProjectName, createdHolder[0], KIND_EXTERNAL_OBJECTS);

        // Post-create: apply scriptVariant if supplied (non-fatal on failure)
        // Input is guaranteed "Russian" or "English" (case-insensitive) by the shared validation above.
        String scriptVariantNote = null;
        ScriptVariant requestedSv = null;
        if (scriptVariantStr != null && !scriptVariantStr.isEmpty())
        {
            requestedSv = SCRIPT_RUSSIAN.equalsIgnoreCase(scriptVariantStr)
                ? ScriptVariant.RUSSIAN : ScriptVariant.ENGLISH;
            scriptVariantNote = applyExternalObjectsScriptVariant(extObjMgr, finalEffectiveProjectName,
                createdHolder[0], requestedSv, projectState);
        }

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap = applyCodestylePrefs(finalEffectiveProjectName, standardChecks, commonChecks);

        String message = "External objects project '" + finalEffectiveProjectName + "' created."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (scriptVariantNote != null)
        {
            message = message + " " + scriptVariantNote; //$NON-NLS-1$
        }
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, finalEffectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTERNAL_OBJECTS)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_VERSION, finalVersion.toString())
            .put(KEY_STATE, projectState)
            .put(KEY_CODESTYLE, codestyleMap)
            .put(McpKeys.MESSAGE, message);
        if (requestedSv != null)
        {
            // Emit the canonical ScriptVariant literal (normalized from user input casing)
            result.put(KEY_SCRIPT_VARIANT, requestedSv.getLiteral());
        }
        if (scriptVariantNote != null)
        {
            result.put(KEY_SCRIPT_VARIANT_NOTE, scriptVariantNote);
        }
        return result.toJson();
    }

    /**
     * Builds the externalObjects slow-path response (creation completed past the wait window).
     * When a {@code scriptVariantStr} was supplied, emits the canonical ScriptVariant literal
     * and a note that setScriptVariant was skipped.
     *
     * @return the response JSON string
     */
    private static String buildExternalObjectsSlowResponse(String effectiveProjectName, String configName,
        Version version, String scriptVariantStr)
    {
        ToolResult slowResult = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTERNAL_OBJECTS)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_VERSION, version.toString())
            .put(KEY_STATE, VAL_CREATED)
            .put(KEY_CODESTYLE, slowPathCodestyleMap())
            .put(McpKeys.MESSAGE, "External objects project '" + effectiveProjectName //$NON-NLS-1$
                + "' created (creation completed past the " //$NON-NLS-1$
                + (CREATE_TIMEOUT_MS / 1000) + MSG_WAIT_WINDOW_SUFFIX);
        if (scriptVariantStr != null && !scriptVariantStr.isEmpty())
        {
            // Emit canonical literal (normalized from user input casing)
            ScriptVariant slowSv = SCRIPT_RUSSIAN.equalsIgnoreCase(scriptVariantStr)
                ? ScriptVariant.RUSSIAN : ScriptVariant.ENGLISH;
            slowResult.put(KEY_SCRIPT_VARIANT, slowSv.getLiteral());
            String slowScriptNote =
                "setScriptVariant skipped: creation exceeded the wait window; set the project preferences manually if needed."; //$NON-NLS-1$
            slowResult.put(KEY_SCRIPT_VARIANT_NOTE, slowScriptNote);
        }
        return slowResult.toJson();
    }

    /**
     * Applies the requested script variant to a freshly created externalObjects project
     * (non-fatal: failures are logged and reported via the returned note, never propagated).
     * Mirrors the original inline post-create block exactly.
     *
     * @param extObjMgr the external-object project manager
     * @param effectiveProjectName the workspace project name
     * @param createdProject the {@link IProject} returned by create (may be {@code null})
     * @param requestedSv the canonical script variant to apply
     * @param projectState the resolved lifecycle state (for the skip note)
     * @return a note describing why the variant could not be applied, or {@code null} on success
     */
    private static String applyExternalObjectsScriptVariant(IExternalObjectProjectManager extObjMgr,
        String effectiveProjectName, IProject createdProject, ScriptVariant requestedSv, String projectState)
    {
        String scriptVariantNote = null;
        try
        {
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            IProject newIProject = createdProject != null ? createdProject
                : ProjectContext.of(effectiveProjectName).project();
            if (v8ProjectManager != null && newIProject != null && newIProject.exists())
            {
                IV8Project v8project = v8ProjectManager.getProject(newIProject);
                if (v8project instanceof IExternalObjectProject)
                {
                    extObjMgr.setScriptVariant((IExternalObjectProject) v8project, requestedSv,
                        new NullProgressMonitor());
                }
                else
                {
                    scriptVariantNote = "setScriptVariant skipped: project is not yet " //$NON-NLS-1$
                        + "an IExternalObjectProject (state=" + projectState + ")."; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("create_project (externalObjects): setScriptVariant failed", e); //$NON-NLS-1$
            scriptVariantNote = "setScriptVariant failed (non-fatal): " + e.getMessage(); //$NON-NLS-1$
        }
        return scriptVariantNote;
    }

    // ─────────────────────── Shared helpers ──────────────────────────────────

    /** Status codes returned by {@link #runCreateJob}. */
    private enum CreateStatus
    {
        /** Job completed within the timeout window; caller must check {@code errorHolder}. */
        OK,
        /**
         * Job timed out but the project already exists in the workspace (slow creation
         * past the wait window). The caller must build the full kind-specific response
         * with {@code state="created"} and {@code codestyle.applied=false}.
         */
        SLOW_EXISTS,
        /** Job timed out and the project does NOT exist — return an error response. */
        TIMED_OUT,
        /** Job was interrupted — return an error response. */
        INTERRUPTED
    }

    /** Minimal result returned by {@link #runCreateJob}. */
    private static final class CreateJobResult
    {
        final CreateStatus status;
        /** Non-null for {@link CreateStatus#TIMED_OUT} and {@link CreateStatus#INTERRUPTED}: ready-to-return JSON error. */
        final String errorJson;

        private CreateJobResult(CreateStatus status, String errorJson)
        {
            this.status = status;
            this.errorJson = errorJson;
        }
    }

    /**
     * Runs the given project-creation Job, joins with {@link #CREATE_TIMEOUT_MS}, and
     * returns a {@link CreateJobResult} describing the outcome:
     * <ul>
     *   <li>{@link CreateStatus#OK} — job completed in time; caller checks {@code errorHolder}.</li>
     *   <li>{@link CreateStatus#SLOW_EXISTS} — timed out but project already exists; caller
     *       builds the full kind-specific success response with {@code state="created"}.</li>
     *   <li>{@link CreateStatus#TIMED_OUT} — timed out and project absent; {@code errorJson}
     *       is ready to return.</li>
     *   <li>{@link CreateStatus#INTERRUPTED} — job interrupted; {@code errorJson} is ready
     *       to return.</li>
     * </ul>
     */
    private CreateJobResult runCreateJob(Job createJob, String effectiveProjectName, String kindLabel)
    {
        createJob.setUser(false);
        createJob.schedule();

        try
        {
            createJob.join(CREATE_TIMEOUT_MS, new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new CreateJobResult(CreateStatus.INTERRUPTED,
                ToolResult.error(kindLabel + " project creation was interrupted.").toJson()); //$NON-NLS-1$
        }

        // Check job state: if still running, it timed out
        if (createJob.getState() != Job.NONE)
        {
            createJob.cancel();
            // Re-check whether the project actually exists despite the timeout
            if (ProjectContext.of(effectiveProjectName).exists())
            {
                return new CreateJobResult(CreateStatus.SLOW_EXISTS, null);
            }
            return new CreateJobResult(CreateStatus.TIMED_OUT,
                ToolResult.error(kindLabel + " project creation timed out after " //$NON-NLS-1$
                    + (CREATE_TIMEOUT_MS / 1000) + " seconds. The project may still appear shortly; " //$NON-NLS-1$
                    + "if it does and is unwanted, remove it with delete_project.").toJson()); //$NON-NLS-1$
        }
        return new CreateJobResult(CreateStatus.OK, null);
    }

    /**
     * Builds the {@code codestyle} map for the slow-path (creation exceeded the wait window).
     * Applied is always {@code false}; the note directs the caller to set preferences manually.
     */
    private static Map<String, Object> slowPathCodestyleMap()
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("applied", false); //$NON-NLS-1$
        map.put("note", //$NON-NLS-1$
            "Not applied: creation exceeded the wait window; set the project preferences manually if needed."); //$NON-NLS-1$
        map.put("autoSortNote", //$NON-NLS-1$
            "autoSortTopObjects is reserved for a future release and was not applied (enable-key unconfirmed)."); //$NON-NLS-1$
        return map;
    }

    /**
     * Waits for the newly created project to reach lifecycle STARTED.
     *
     * @param effectiveProjectName the workspace project name
     * @param newIProject the {@link IProject} returned by the create call (may be null)
     * @param kindLabel kind label for log messages
     * @return {@code "ready"} when STARTED was reached; {@code "created"} otherwise
     */
    private static String waitForLifecycle(String effectiveProjectName, IProject newIProject, String kindLabel)
    {
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IProject resolvedProject = (newIProject != null) ? newIProject
                : ProjectContext.of(effectiveProjectName).project();
            if (dtProjectManager != null && resolvedProject != null && resolvedProject.exists())
            {
                com._1c.g5.v8.dt.core.platform.IDtProject dtProject =
                    dtProjectManager.getDtProject(resolvedProject);
                if (dtProject != null)
                {
                    boolean started = LifecycleWaiter.waitForProjectStarted(dtProject, STARTED_TIMEOUT_MS);
                    if (started)
                    {
                        return "ready"; //$NON-NLS-1$
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError(LOG_PREFIX + "lifecycle wait error (" + kindLabel + ")", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return VAL_CREATED;
    }

    /**
     * Applies v8codestyle preferences to the newly created project (guarded: no compile
     * dependency on {@code com.e1c.v8codestyle}).
     *
     * @param effectiveProjectName workspace project name
     * @param standardChecks whether to enable standard checks
     * @param commonChecks whether to enable common checks
     * @return a {@code Map} suitable for the {@code codestyle} response field
     */
    private static Map<String, Object> applyCodestylePrefs(String effectiveProjectName,
        boolean standardChecks, boolean commonChecks)
    {
        boolean codestyleApplied = false;
        String codestyleNote = ""; //$NON-NLS-1$
        Bundle v8codestyleBundle = Platform.getBundle(V8CODESTYLE_BUNDLE);
        if (v8codestyleBundle != null)
        {
            try
            {
                ProjectContext newCtx = ProjectContext.of(effectiveProjectName);
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
                Activator.logError(LOG_PREFIX + "failed to write v8codestyle prefs", e); //$NON-NLS-1$
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
        return codestyleMap;
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
