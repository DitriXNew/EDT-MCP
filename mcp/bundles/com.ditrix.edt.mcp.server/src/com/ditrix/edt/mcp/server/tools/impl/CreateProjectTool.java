/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.osgi.framework.Bundle;
import org.osgi.service.prefs.BackingStoreException;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.ditrix.edt.mcp.server.utils.CommandInterfaceSupport;
import com.ditrix.edt.mcp.server.utils.ExtensionRootBuilder;
import com.ditrix.edt.mcp.server.utils.ExternalObjectRoots;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

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
 *   <li>Constructs a configuration's {@link Configuration}, or an optional external-object root,
 *       through the version-aware {@link IModelObjectFactory}. An extension's root is instead the
 *       base root ADOPTED the way EDT's own new-extension wizard builds it
 *       ({@link ExtensionRootBuilder}, issue #706) - never a factory-made configuration.</li>
 *   <li>Calls the appropriate project manager's {@code create()} method in a background
 *       {@link Job} (never on the UI thread — unattended-safety rule) via the shared
 *       {@link #runCreateJob} helper, and joins with a {@link #CREATE_TIMEOUT_MS} timeout.</li>
 *   <li>Waits for the new project to reach the {@code STARTED} lifecycle state
 *       via {@link LifecycleWaiter} - which reports at once a context {@code create()} already
 *       started.</li>
 *   <li>For {@code extension}: attaches the two command interfaces EDT's wizard creates,
 *       force-exports them with the root, and answers {@code ready} only once the root file on
 *       disk shows the wizard's shape and the root this call built (a bounded wait) - all of it only once
 *       {@code create()} ended within the wait window: a create still running then gets an answer and no
 *       write ({@link #afterExtensionCreate}).</li>
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

    /**
     * Budget (ms) for confirming a new extension's root on disk: EDT's export queue drained - waited for with all of
     * it but the second the look keeps for the root file - and the root file read back, and in the same budget its
     * two command interface files seen. EDT saves them asynchronously after create() attached the root.
     */
    private static final long ROOT_CONFIRM_TIMEOUT_MS = 30_000L;

    /** Pause (ms) between two reads of the root file while confirming it. */
    private static final long ROOT_CONFIRM_POLL_MS = 250L;

    /** Top-object FQN of a configuration root. */
    private static final String CONFIGURATION_FQN = "Configuration"; //$NON-NLS-1$

    /**
     * Why a save submission was refused, as {@link BmTransactions#forceExportToDisk(IProject, List)} reports it: by a
     * {@code false} that covers all of these at once.
     */
    private static final String EXPORT_NOT_ACCEPTED = "EDT accepted no save task for them (its project services " //$NON-NLS-1$
        + "or the new project's DtProject were not available, or EDT declined or failed the submission; a failure " //$NON-NLS-1$
        + "is in the EDT log)"; //$NON-NLS-1$

    /** Parameter/output key: the extension compatibility mode. */
    private static final String KEY_COMPATIBILITY_MODE = "compatibilityMode"; //$NON-NLS-1$

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

    /** Parameter key: optional external-object root to seed during project creation. */
    private static final String KEY_EXTERNAL_OBJECT = "externalObject"; //$NON-NLS-1$

    /** Parameter key: whether Russian yo is normalized in a seeded root Name. */
    private static final String KEY_NORMALIZE_YO = "normalizeYo"; //$NON-NLS-1$

    /** Output key: whether the requested external-object root was observed after a slow create. */
    private static final String KEY_EXTERNAL_OBJECT_CONFIRMED = "externalObjectConfirmed"; //$NON-NLS-1$

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

    /** state value: lifecycle STARTED reached (and, for an extension, its root seen on disk). */
    private static final String VAL_READY = "ready"; //$NON-NLS-1$

    /** Output value: {@code action} of a slow create whose result needs verification. */
    private static final String VAL_VERIFICATION_REQUIRED = "verificationRequired"; //$NON-NLS-1$

    /** Output value: {@code state} of a slow create whose root was not seen. */
    private static final String VAL_ROOT_UNCONFIRMED = "rootUnconfirmed"; //$NON-NLS-1$

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

    /** How an extension answer names the two command interface files EDT's new-extension wizard creates. */
    private static final String COMMAND_INTERFACE_FILES = "The command interface files CommandInterface.cmi and " //$NON-NLS-1$
        + "MainSectionCommandInterface.cmi that EDT's wizard creates"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start a new EDT configuration, extension, or external-objects project. Parameters and " //$NON-NLS-1$
            + "examples: get_tool_guide('create_project')."; //$NON-NLS-1$
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
            .stringProperty(KEY_EXTERNAL_OBJECT,
                "Optional root to seed for projectKind=externalObjects: " //$NON-NLS-1$
                    + "'ExternalDataProcessor.<Name>' or 'ExternalReport.<Name>'; the TYPE token may be " //$NON-NLS-1$
                    + "English or Russian and is not normalized; Name is a programmatic identifier whose " //$NON-NLS-1$
                    + "'ё'/'Ё' is normalized by default according to normalizeYo. Omit for an empty project; " //$NON-NLS-1$
                    + "REJECTED for other project kinds.") //$NON-NLS-1$
            .booleanProperty(KEY_NORMALIZE_YO,
                "Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in the seeded externalObject root " //$NON-NLS-1$
                    + "NAME (default true). 'ё' in a Name is flagged by the 1C standard " //$NON-NLS-1$
                    + "mdo-ru-name-unallowed-letter, so normalizing on input stores a compliant name. " //$NON-NLS-1$
                    + "Set false to keep 'ё' exactly as supplied. The TYPE token is never normalized.") //$NON-NLS-1$
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
            .stringProperty(KEY_COMPATIBILITY_MODE,
                "Optional extension compatibility-mode string matching a CompatibilityMode enum literal " //$NON-NLS-1$
                    + "(e.g. 'Version8_3_24'); omitted or empty = the base configuration's compatibility mode, " //$NON-NLS-1$
                    + "the one EDT's new-extension wizard always uses. Unknown values are rejected, and so is " //$NON-NLS-1$
                    + "8.3.13 or lower over a base above 8.3.13. Extension only; REJECTED for other kinds.") //$NON-NLS-1$
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
            .booleanProperty("success", //$NON-NLS-1$
                "Whether the tool returned project status instead of an immediate error; inspect action and " //$NON-NLS-1$
                    + "externalObjectConfirmed for a slow seeded create", true)
            .stringProperty(McpKeys.ACTION,
                "'created' on completion; 'verificationRequired' for every slow seeded create") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT,
                "EDT workspace project name of the created project (round-trip key for sibling tools)") //$NON-NLS-1$
            .stringProperty(KEY_PROJECT_KIND, "Kind of project created: configuration, extension, or externalObjects") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Configuration name (configuration/extension) or the supplied identifier (externalObjects).") //$NON-NLS-1$
            .stringProperty(KEY_BASE_PROJECT,
                "Name of the base configuration project it extends (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_PREFIX, "NamePrefix applied (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_PURPOSE, "ConfigurationExtensionPurpose value applied (extension only, conditional)") //$NON-NLS-1$
            .stringProperty(KEY_COMPATIBILITY_MODE,
                "Effective extension compatibility mode as its enum name, e.g. Version8_5_1 (extension only); the " //$NON-NLS-1$
                    + "base configuration's unless one was requested") //$NON-NLS-1$
            .stringProperty(KEY_SCRIPT_VARIANT, "ScriptVariant applied or inherited") //$NON-NLS-1$
            .stringProperty(KEY_VERSION, "Platform version string used for project creation") //$NON-NLS-1$
            .stringProperty(KEY_EXTERNAL_OBJECT,
                "Canonical FQN of the seeded external-object root, when requested") //$NON-NLS-1$
            .booleanProperty(KEY_EXTERNAL_OBJECT_CONFIRMED,
                "Whether a requested root was observed through metadata scope resolution on the slow path") //$NON-NLS-1$
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Fields whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringProperty(KEY_STATE,
                "'ready' when lifecycle STARTED was reached (for an extension, also once its root file was seen " //$NON-NLS-1$
                    + "on disk), 'created' otherwise, or " //$NON-NLS-1$
                    + "'rootConfirmed'/'rootUnconfirmed' when a slow seeded create requires verification") //$NON-NLS-1$
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
        String externalObjectStr = JsonUtils.extractStringArgument(params, KEY_EXTERNAL_OBJECT);
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String prefix = JsonUtils.extractStringArgument(params, KEY_PREFIX);
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String purposeStr = JsonUtils.extractStringArgument(params, KEY_PURPOSE);
        String compatModeStr = JsonUtils.extractStringArgument(params, KEY_COMPATIBILITY_MODE);
        String scriptVariantStr = JsonUtils.extractStringArgument(params, KEY_SCRIPT_VARIANT);
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, KEY_NORMALIZE_YO, true);
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
        String constraintErr = validateKindConstraints(new KindConstraintInputs(projectKind, isExtension,
            isExternalObjects, versionStr, baseProjectName, prefix, purposeStr, compatModeStr, synonym, comment,
            scriptVariantStr, externalObjectStr));
        if (constraintErr != null)
        {
            return constraintErr;
        }

        ExternalObjectSpec externalObject = null;
        if (isExternalObjects && externalObjectStr != null)
        {
            externalObject = resolveExternalObject(externalObjectStr, normalizeYo);
            if (externalObject.error != null)
            {
                return externalObject.error;
            }
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
        CreateRequest request = new CreateRequest(configName, projectName, versionStr, baseProjectName, prefix,
            synonym, comment, purposeStr, compatModeStr, scriptVariantStr, externalObject, standardChecks,
            commonChecks);
        if (isExtension)
        {
            return executeExtension(request);
        }
        else if (isConfiguration)
        {
            return executeConfiguration(request);
        }
        else
        {
            return executeExternalObjects(request);
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
     * which {@code projectKind}) plus the strict scriptVariant value check. Thin orchestrator:
     * delegates to the per-group validators and returns the first non-null error.
     *
     * @return a ready-to-return JSON error string when a constraint is violated, or
     *     {@code null} when all constraints pass
     */
    private static String validateKindConstraints(KindConstraintInputs in)
    {
        String err = validateExtensionInheritedParams(in);
        if (err != null)
        {
            return err;
        }
        err = validateNonExtensionParams(in);
        if (err != null)
        {
            return err;
        }
        err = validateExternalObjectsParams(in);
        if (err != null)
        {
            return err;
        }
        err = validateExternalObjectParam(in);
        if (err != null)
        {
            return err;
        }
        return validateScriptVariantValue(in);
    }

    /**
     * Rejects parameters that an extension always inherits from its base configuration
     * ({@code version}, {@code scriptVariant}) when {@code projectKind=extension}.
     *
     * @return a ready-to-return JSON error string, or {@code null} when valid
     */
    private static String validateExtensionInheritedParams(KindConstraintInputs in)
    {
        if (in.isExtension && in.versionStr != null && !in.versionStr.isEmpty())
        {
            return ToolResult.error(
                "'version' is not valid for projectKind=extension: " //$NON-NLS-1$
                    + "the extension always inherits the version from the base configuration. " //$NON-NLS-1$
                    + "Remove the 'version' parameter.").toJson(); //$NON-NLS-1$
        }
        if (in.isExtension && in.scriptVariantStr != null && !in.scriptVariantStr.isEmpty())
        {
            return ToolResult.error(
                "'scriptVariant' is not valid for projectKind=extension: " //$NON-NLS-1$
                    + "the extension always inherits the scriptVariant from the base configuration. " //$NON-NLS-1$
                    + "Remove the 'scriptVariant' parameter.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Rejects extension-only parameters ({@code baseProjectName}, {@code prefix}, {@code purpose},
     * {@code compatibilityMode}) when {@code projectKind} is not {@code extension}.
     *
     * @return a ready-to-return JSON error string, or {@code null} when valid
     */
    private static String validateNonExtensionParams(KindConstraintInputs in)
    {
        if (!in.isExtension && in.baseProjectName != null && !in.baseProjectName.isEmpty())
        {
            return ToolResult.error(
                "'baseProjectName' is only valid for projectKind=extension. " //$NON-NLS-1$
                    + "Remove the 'baseProjectName' parameter for kind=" + in.projectKind + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!in.isExtension && in.prefix != null && !in.prefix.isEmpty())
        {
            return ToolResult.error("'prefix' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        if (!in.isExtension && in.purposeStr != null && !in.purposeStr.isEmpty())
        {
            return ToolResult.error("'purpose' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        if (!in.isExtension && in.compatModeStr != null && !in.compatModeStr.isEmpty())
        {
            return ToolResult.error("'compatibilityMode' is only valid for projectKind=extension.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Rejects externalObjects-incompatible parameters ({@code synonym}, {@code comment}) when
     * {@code projectKind=externalObjects}.
     *
     * @return a ready-to-return JSON error string, or {@code null} when valid
     */
    private static String validateExternalObjectsParams(KindConstraintInputs in)
    {
        if (in.isExternalObjects && in.synonym != null && !in.synonym.isEmpty())
        {
            return ToolResult.error("'synonym' is not valid for projectKind=externalObjects.").toJson(); //$NON-NLS-1$
        }
        if (in.isExternalObjects && in.comment != null && !in.comment.isEmpty())
        {
            return ToolResult.error("'comment' is not valid for projectKind=externalObjects.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Rejects {@code externalObject} outside the one project kind whose create API accepts it.
     *
     * @return a ready-to-return JSON error string, or {@code null} when valid
     */
    private static String validateExternalObjectParam(KindConstraintInputs in)
    {
        if (!in.isExternalObjects && in.externalObjectStr != null)
        {
            return ToolResult.error("externalObject '" + in.externalObjectStr //$NON-NLS-1$
                + "' is only valid for projectKind=externalObjects. Remove externalObject or set " //$NON-NLS-1$
                + "projectKind=externalObjects.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Strict scriptVariant value validation: must be exactly {@code "Russian"} or {@code "English"}
     * (case-insensitive) when supplied for configuration or externalObjects.
     *
     * @return a ready-to-return JSON error string, or {@code null} when valid
     */
    private static String validateScriptVariantValue(KindConstraintInputs in)
    {
        if (!in.isExtension && in.scriptVariantStr != null && !in.scriptVariantStr.isEmpty()
            && !SCRIPT_RUSSIAN.equalsIgnoreCase(in.scriptVariantStr)
            && !SCRIPT_ENGLISH.equalsIgnoreCase(in.scriptVariantStr))
        {
            return ToolResult.error("Invalid scriptVariant value: '" + in.scriptVariantStr //$NON-NLS-1$
                + "'. Allowed values: 'Russian', 'English'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolves an optional external-object root address without touching the workbench. The TYPE
     * token is interpreted only by the shared bilingual {@link MetadataTypeUtils} catalogue; the
     * resolved kind then selects one of the two root EClasses accepted by EDT's external-project
     * creation API.
     *
     * @param value external-object FQN supplied by the caller
     * @return the resolved EClass/name pair, or a ready-to-return validation error
     */
    static ExternalObjectSpec resolveExternalObject(String value)
    {
        return resolveExternalObject(value, true);
    }

    /**
     * Resolves and, by default, yo-normalizes only the root Name. The TYPE token is looked up
     * exactly as supplied and is never passed through the name normalizer.
     */
    static ExternalObjectSpec resolveExternalObject(String value, boolean normalizeYo)
    {
        String normalized = value == null ? null : value.trim();
        String[] parts = normalized == null ? new String[0] : normalized.split("\\.", -1); //$NON-NLS-1$
        if (parts.length != 2)
        {
            return ExternalObjectSpec.failure(invalidExternalObjectShape(value));
        }

        EClass eClass = ExternalObjectRoots.rootEClass(parts[0]);
        if (eClass == null)
        {
            return ExternalObjectSpec.failure(invalidExternalObjectShape(value));
        }

        MdNameNormalizer.Report normReport = new MdNameNormalizer.Report(normalizeYo);
        String objectName = normReport.apply("name", parts[1]); //$NON-NLS-1$
        if (!isValidIdentifier(objectName))
        {
            return ExternalObjectSpec.failure(ToolResult.error("Invalid externalObject '" + value //$NON-NLS-1$
                + "': root Name '" + objectName + "' must start with a letter or underscore and contain only " //$NON-NLS-1$ //$NON-NLS-2$
                + "letters, digits and underscores. Use 'ExternalDataProcessor.<Name>' or " //$NON-NLS-1$
                + "'ExternalReport.<Name>'.").toJson()); //$NON-NLS-1$
        }

        return ExternalObjectSpec.success(eClass, objectName, eClass.getName() + "." + objectName, //$NON-NLS-1$
            normReport);
    }

    /** Returns the common actionable error for a malformed or unsupported root address. */
    private static String invalidExternalObjectShape(String value)
    {
        return ToolResult.error("Invalid externalObject '" + value //$NON-NLS-1$
            + "'. Expected 'ExternalDataProcessor.<Name>' or 'ExternalReport.<Name>'; " //$NON-NLS-1$
            + "the TYPE token may also be Russian.").toJson(); //$NON-NLS-1$
    }

    // ─────────────────────── EXTENSION path ──────────────────────────────────

    private String executeExtension(CreateRequest req)
    {
        String configName = req.configName;
        String prefix = req.prefix;

        // Validate the base project and new project name (read-only)
        BaseProjectResolution baseResolution =
            validateExtensionBaseProject(configName, req.projectName, req.baseProjectName);
        if (baseResolution.error != null)
        {
            return baseResolution.error;
        }
        String baseProjectName = baseResolution.baseProjectName;
        String effectiveProjectName = baseResolution.effectiveProjectName;
        IProject baseIProject = baseResolution.baseIProject;

        // Resolve services and the base project's model (read-only)
        ExtensionServices services = resolveExtensionServices(baseIProject, baseProjectName);
        if (services.error != null)
        {
            return services.error;
        }
        IExtensionProjectManager extMgr = services.extMgr;
        Version version = services.version;

        // Validate purpose
        PurposeResolution purposeResolution = resolveExtensionPurpose(req.purposeStr);
        if (purposeResolution.error != null)
        {
            return purposeResolution.error;
        }
        ConfigurationExtensionPurpose purpose = purposeResolution.purpose;

        // Validate CompatibilityMode if supplied
        CompatModeResolution compatModeResolution = resolveCompatibilityMode(req.compatModeStr);
        if (compatModeResolution.error != null)
        {
            return compatModeResolution.error;
        }
        CompatibilityMode compatMode = compatModeResolution.compatMode;

        // Build the root the way EDT's new-extension wizard does: the base root ADOPTED (read in a read
        // transaction of the base's model), then the wizard's overrides. A refusal creates nothing.
        ExtensionRootBuilder.Result built = ExtensionRootBuilder.buildFromBase(services.baseModel, services.adopter,
            version, new ExtensionRootBuilder.Spec(baseProjectName, configName, prefix, purpose, compatMode,
                req.comment, req.synonym));
        if (built.error != null)
        {
            return ToolResult.error(built.error).toJson();
        }
        ScriptVariant scriptVariant = built.scriptVariant != null ? built.scriptVariant : ScriptVariant.RUSSIAN;

        // Create the extension project in a background Job
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;
        final Configuration finalConfig = built.root;
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

        ExtensionResponseData responseData = new ExtensionResponseData(finalEffectiveProjectName, configName,
            baseProjectName, prefix, purpose, scriptVariant, version, built.synonymApplied);
        ExtensionFacts facts = new ExtensionFacts(built.compatibilityMode, built.baseCompatibilityMode,
            compatMode != null, built.compatibilityNote, built.identity);

        CreateJobResult jobResult = runCreateJob(createJob, finalEffectiveProjectName, KIND_EXTENSION);
        // The steps that write to the new project run only once EDT's create() has ended.
        return afterExtensionCreate(jobResult.status, jobResult.errorJson, responseData, facts,
            () -> concludeExtensionCreate(req, finalEffectiveProjectName, baseProjectName, createdHolder[0],
                errorHolder[0], responseData, facts));
    }

    /**
     * Where an extension's create goes once {@link #runCreateJob} has told how its job ended. Only a job that ENDED
     * within the wait window ({@link CreateStatus#OK}) goes on to {@code concluded}: the steps after {@code create()} -
     * those that write to the new project (its command interfaces, the save of them with its root), the look at its
     * files and the codestyle preferences. On {@link CreateStatus#SLOW_EXISTS} the project exists but the job had not
     * ended: {@link #runCreateJob} only asked it to stop, and EDT's {@code create()} may still be running - a write
     * then would race it, and could wait on its model's locks past the window - so the answer
     * ({@link #buildExtensionSlowResponse}) is all this call does, and it says what was not done. Any other outcome
     * answers its error. Package-visible for tests.
     *
     * @param status how the create job ended
     * @param errorJson the ready answer of {@link CreateStatus#TIMED_OUT} and {@link CreateStatus#INTERRUPTED}
     * @param data the response data
     * @param facts what the steps before {@code create()} found
     * @param concluded the steps after an ended {@code create()}, and their answer
     * @return the answer
     */
    static String afterExtensionCreate(CreateStatus status, String errorJson, ExtensionResponseData data,
        ExtensionFacts facts, Supplier<String> concluded)
    {
        if (status == CreateStatus.OK)
        {
            return concluded.get();
        }
        if (status == CreateStatus.SLOW_EXISTS)
        {
            return buildExtensionSlowResponse(data, facts);
        }
        return errorJson;
    }

    /**
     * The steps after an extension's {@code create()} that ENDED within the wait window
     * ({@link #afterExtensionCreate}): its error, or the lifecycle wait, the command interfaces saved with the root and
     * the bounded look at their files, and the codestyle preferences.
     */
    private static String concludeExtensionCreate(CreateRequest req, String projectName, String baseProjectName, // NOSONAR the extension path's state after create(), passed on as it is
        IProject created, Throwable error, ExtensionResponseData responseData, ExtensionFacts facts)
    {
        // Check for creation errors reported via errorHolder
        if (error != null)
        {
            Activator.logError("create_project (extension) failed", error); //$NON-NLS-1$
            return creationFailure(projectName,
                "Failed to create extension project: " + error.getMessage()); //$NON-NLS-1$
        }

        Activator.logInfo(LOG_PREFIX + "created extension '" + projectName //$NON-NLS-1$
            + "' extending '" + baseProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED (create() started the context itself, so this is normally immediate)
        IProject createdProject = created != null ? created : ProjectContext.of(projectName).project();
        facts.lifecycleStarted = waitForStarted(projectName, created, KIND_EXTENSION);

        // The two command interfaces EDT's wizard creates, exported with the root EDT attached; then the
        // root file must show the wizard's shape and this call's root before the answer may say 'ready'. The command
        // interface files are looked for in the same bounded look - the same budget, not a second one - and never
        // change 'ready'.
        saveAndLook(createdProject, ensureCommandInterfaces(createdProject, facts), facts,
            ExtensionDisk.of(createdProject), ROOT_CONFIRM_TIMEOUT_MS, ROOT_CONFIRM_POLL_MS);

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap = applyCodestylePrefs(projectName, req.standardChecks, req.commonChecks);

        return buildExtensionSuccessResponse(responseData, facts, codestyleMap);
    }

    /**
     * Attaches the two command interfaces EDT's new-extension wizard creates ({@code CommandInterface.cmi},
     * {@code MainSectionCommandInterface.cmi}) in one write of the new project's model. Non-fatal: the
     * project and its root are valid without them, so a failure - or a root not yet in the model - is
     * recorded in {@code facts} for the answer's message, never thrown.
     *
     * @param project the new extension project
     * @param facts where a failure is recorded
     * @return the command interfaces' FQNs to export, empty when none was ensured
     */
    private static List<String> ensureCommandInterfaces(IProject project, ExtensionFacts facts)
    {
        BmModelResolver.Resolution model = BmModelResolver.resolve(project);
        if (!model.isAvailable())
        {
            facts.commandInterfaceFailure = "the new project's model was not available"; //$NON-NLS-1$
            return Collections.emptyList();
        }
        try
        {
            CommandInterfaceSupport.Ensured ensured = BmTransactions.write(model.getModel(),
                LOG_PREFIX + "command interfaces", //$NON-NLS-1$
                (tx, monitor) -> CommandInterfaceSupport.ensureConfigurationCommandInterfaces(tx));
            facts.commandInterfaceFailure = ensured.skipped;
            return ensured.fqns;
        }
        catch (RuntimeException e)
        {
            Activator.logError(LOG_PREFIX + "the command interfaces of '" + project.getName() //$NON-NLS-1$
                + "' were not created", e); //$NON-NLS-1$
            facts.commandInterfaceFailure = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage()
                : e.getClass().getSimpleName();
            return Collections.emptyList();
        }
    }

    /** Hands save tasks to EDT: {@link BmTransactions#forceExportToDisk(IProject, List)} outside tests. */
    @FunctionalInterface
    interface ExportSubmission
    {
        /**
         * @param project the project the objects belong to
         * @param topObjectFqns the top objects whose files to save
         * @return whether EDT accepted a save task
         */
        boolean submit(IProject project, List<String> topObjectFqns);
    }

    /**
     * The bounded wait for a project's export queue: {@link BuildUtils#waitForDiskExport(IProject, long)} outside
     * tests. It is handed the project and how long it may take, and binds no timeout of its own: how long is the look's
     * to decide - its share of the look's budget ({@link #saveAndLook}).
     */
    @FunctionalInterface
    interface ExportWait
    {
        /**
         * @param project the project whose export queue to wait for
         * @param timeoutMs how long the wait may take
         * @return how the wait ended
         */
        DiskExportState await(IProject project, long timeoutMs);
    }

    /**
     * Queues the export of the root and of the command interfaces this call attached ({@code commandInterfaceFqns}),
     * and records in {@code facts} a submission EDT did not accept - or one that threw - while there were command
     * interfaces to save: they are then in the model, but this call queued no save of their files. EDT already
     * scheduled its own save of the root it attached; queuing it here as well puts the root on the queue the
     * confirmation waits for, and the root's file is judged on disk, so nothing is recorded for the root alone. Never
     * throws. Package-visible for tests.
     *
     * @param project the new extension project
     * @param commandInterfaceFqns the command interfaces' FQNs this call ensured (attached, or found attached); empty
     *     for none
     * @param facts where a refused submission is recorded
     * @param submission hands the save tasks to EDT
     */
    static void exportWithRoot(IProject project, List<String> commandInterfaceFqns, ExtensionFacts facts,
        ExportSubmission submission)
    {
        List<String> fqns = new ArrayList<>(commandInterfaceFqns.size() + 1);
        fqns.add(CONFIGURATION_FQN);
        fqns.addAll(commandInterfaceFqns);
        String refused;
        try
        {
            refused = submission.submit(project, fqns) ? null : EXPORT_NOT_ACCEPTED;
        }
        catch (RuntimeException e)
        {
            Activator.logError(LOG_PREFIX + "the save of the new root and command interfaces of '" //$NON-NLS-1$
                + project.getName() + "' could not be queued", e); //$NON-NLS-1$
            refused = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage()
                : e.getClass().getSimpleName();
        }
        if (refused != null && !commandInterfaceFqns.isEmpty())
        {
            facts.commandInterfaceSaveFailure = refused;
        }
    }

    /**
     * The command interface files the bounded look waits for beside the root: both of them when this call attached
     * the command interfaces, none when it did not (a failure the answer already names). Package-visible for tests.
     *
     * @param commandInterfaceFqns the command interfaces' FQNs this call ensured (attached, or found attached)
     * @return the project-relative paths to look for
     */
    static List<String> commandInterfaceFiles(List<String> commandInterfaceFqns)
    {
        return commandInterfaceFqns.isEmpty() ? Collections.emptyList() : ExtensionRootBuilder.COMMAND_INTERFACE_FILES;
    }

    /**
     * A new extension's files as the steps after create() reach them: EDT's save of top objects, the bounded wait for
     * the project's export queue, the root file, and the check of a file beside it. {@link #of} binds EDT's own to a
     * project; tests pass theirs. Package-visible for tests.
     */
    static final class ExtensionDisk
    {
        /** Hands save tasks to EDT. */
        final ExportSubmission save;
        /** The bounded wait for a project's export queue, handed the project and how long it may take. */
        final ExportWait exportWait;
        /** Reads the root file. */
        final ExtensionRootBuilder.RootFileReader rootFile;
        /** Whether a project-relative path is a file on disk. */
        final Predicate<String> fileExists;

        ExtensionDisk(ExportSubmission save, ExportWait exportWait, ExtensionRootBuilder.RootFileReader rootFile,
            Predicate<String> fileExists)
        {
            this.save = save;
            this.exportWait = exportWait;
            this.rootFile = rootFile;
            this.fileExists = fileExists;
        }

        /**
         * EDT's own for {@code project}: its save ({@link BmTransactions#forceExportToDisk(IProject, List)}), its
         * export wait ({@link BuildUtils#waitForDiskExport} as it is: {@link CreateProjectTool#saveAndLook} hands it
         * the project and the share of the budget the look gives it), and the root file and the files beside it, read
         * straight from the project's location ({@link ExtensionRootBuilder#readRootFile},
         * {@link ExtensionRootBuilder#projectFileExists}) - unbounded reads the look runs only inside bounded reads of
         * its own ({@link CreateProjectTool#saveAndLook}). Nothing is asked of the project until a step runs.
         *
         * @param project the new extension project
         * @return its disk
         */
        static ExtensionDisk of(IProject project)
        {
            return new ExtensionDisk(BmTransactions::forceExportToDisk, BuildUtils::waitForDiskExport,
                () -> ExtensionRootBuilder.readRootFile(project),
                path -> ExtensionRootBuilder.projectFileExists(project).test(path));
        }
    }

    /**
     * The save and the bounded look a created extension gets: queues the save of the root and of the command
     * interfaces this call attached ({@link #exportWithRoot}) and - once EDT reported the context started - looks for
     * the root file and, in the same budget, for the files of those command interfaces
     * ({@link #commandInterfaceFiles}), recording in {@code facts} a save EDT did not accept and what the look found.
     * The files looked for are decided here, from what this call attached; and here the project's export wait is handed
     * the share of the budget the look gives it - the budget less the second the look keeps for the root file. Every
     * read of the look runs in a bounded job of its own under the project's claim on reads of its files, keyed by the
     * project's name ({@link ExtensionRootBuilder#confirmOnDisk(String, ExtensionRootBuilder.RootIdentity,
     * LongFunction, ExtensionRootBuilder.RootFileReader, List, Predicate, long, long)}), and it confirms only a root file
     * that shows the root this call built ({@link ExtensionFacts#root}), so a file system that does not answer holds
     * the call no longer than the bound that method states: the look's budget, the export wait included, plus
     * BoundedJob's 0.5s grace for a last read whose job had not started by its deadline. Package-visible for tests.
     *
     * @param project the new extension project
     * @param commandInterfaceFqns the command interfaces' FQNs this call ensured (attached, or found attached); empty
     *     for none
     * @param facts where a refused save and the look's findings are recorded
     * @param disk the project's files ({@link ExtensionDisk#of} outside tests)
     * @param budgetMs how long the whole look may take, the export wait included
     * @param pollMs the pause between two rounds of the look
     */
    static void saveAndLook(IProject project, List<String> commandInterfaceFqns, ExtensionFacts facts, // NOSONAR the project, what to save, where to record, the disk and the two bounds
        ExtensionDisk disk, long budgetMs, long pollMs)
    {
        exportWithRoot(project, commandInterfaceFqns, facts, disk.save);
        if (facts.lifecycleStarted)
        {
            // The look decides the wait's share of its budget; this project's wait is handed that share as it is.
            LongFunction<DiskExportState> exportWait = timeoutMs -> disk.exportWait.await(project, timeoutMs);
            facts.disk = ExtensionRootBuilder.confirmOnDisk(project.getName(), facts.root, exportWait, disk.rootFile,
                commandInterfaceFiles(commandInterfaceFqns), disk.fileExists, budgetMs, pollMs);
        }
    }

    // ─────────────────────── CONFIGURATION path ──────────────────────────────

    private String executeConfiguration(CreateRequest req)
    {
        String configName = req.configName;

        // Derive default EDT project name: <configName>
        String effectiveProjectName = (req.projectName != null) ? req.projectName : configName;

        // Check the new project name is not taken - in the workspace or by a directory on disk
        String taken = takenProjectRefusal(effectiveProjectName);
        if (taken != null)
        {
            return taken;
        }

        // Parse version (read-only)
        VersionResolution versionResolution = parseVersion(req.versionStr);
        if (versionResolution.error != null)
        {
            return versionResolution.error;
        }
        Version version = versionResolution.version;

        // Resolve MD factory and IConfigurationProjectManager (read-only)
        ConfigurationServices services = resolveConfigurationServices();
        if (services.error != null)
        {
            return services.error;
        }
        IModelObjectFactory factory = services.factory;
        IConfigurationProjectManager configMgr = services.configMgr;

        // Resolve scriptVariant and language tokens (read-only)
        ConfigurationScript script = resolveConfigurationScript(req.scriptVariantStr);
        ScriptVariant scriptVariant = script.scriptVariant;

        // Build Configuration model object (NOT inside a BM transaction)
        Configuration config = (Configuration) factory.create(MdClassPackage.Literals.CONFIGURATION, version);
        factory.fillDefaultReferences(config);

        // ObjectBelonging stays default OWN for a standalone configuration
        config.setName(configName);
        config.setScriptVariant(scriptVariant);

        // Ensure the configuration has a default Language (per ConfigurationWizard recipe)
        // If fillDefaultReferences already produced a language, reuse it; otherwise create one.
        setupConfigurationLanguage(config, script.langCode, script.langName);

        // Optional attributes
        applyComment(config, req.comment);

        // Set synonym via language-code-keyed EMap
        boolean synonymApplied = applyConfigurationSynonym(config, script.langCode, req.synonym, configName);

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

        CreateJobResult jobResult = runCreateJob(createJob, finalEffectiveProjectName, KIND_CONFIGURATION);
        if (jobResult.status == CreateStatus.SLOW_EXISTS)
        {
            // Creation completed past the wait window — build the full configuration response
            return buildConfigurationSlowResponse(finalEffectiveProjectName, configName,
                scriptVariant, finalVersion, synonymApplied);
        }
        if (jobResult.errorJson != null)
        {
            return jobResult.errorJson;
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("create_project (configuration) failed", errorHolder[0]); //$NON-NLS-1$
            return creationFailure(finalEffectiveProjectName,
                "Failed to create configuration project: " + errorHolder[0].getMessage()); //$NON-NLS-1$
        }

        Activator.logInfo(LOG_PREFIX + "created configuration '" + finalEffectiveProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED
        String projectState = waitForLifecycle(finalEffectiveProjectName, createdHolder[0], KIND_CONFIGURATION);

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap =
            applyCodestylePrefs(finalEffectiveProjectName, req.standardChecks, req.commonChecks);

        return buildConfigurationSuccessResponse(finalEffectiveProjectName, configName, scriptVariant,
            finalVersion, projectState, codestyleMap, synonymApplied);
    }

    /**
     * The refusal of a create whose project name is illegal or taken, or {@code null} when it is free. A name the
     * workspace does not accept as a project name ({@code WorkspacePaths.projectNameProblem} - the same validation
     * {@code IProject.create} applies, so EDT's create could not have used it either) is refused first, before anything
     * looks it up: a name a path cannot hold would otherwise throw out of the call. The name is taken by a workspace
     * project of that name, or - with none - by a directory or file at the place EDT's create would put the project.
     * create_project passes
     * EDT no location, and for every kind EDT's project manager then puts the project in the workspace location joined
     * with its name ({@code ConfigurationProjectManager}, {@code AbstractDependentProjectManager} for extensions and
     * external objects - EDT 2026.1.2, 2026.2.0 and 2026.2.1); {@code V8ProjectCreator} then creates the project over
     * whatever that directory holds, and deletes the project WITH its content when the create fails. Such a directory
     * is what {@code delete_project} without {@code deleteContent} leaves, and an extension created over it would find
     * the old root file there. The check and EDT's create are not atomic: a directory made between them is not caught.
     * Package-visible for tests.
     *
     * @param projectName the workspace project name the create would use
     * @return the ready JSON refusal, or {@code null}
     */
    static String takenProjectRefusal(String projectName)
    {
        String problem = WorkspacePaths.projectNameProblem(projectName);
        if (problem != null)
        {
            return ToolResult.error(illegalProjectNameRefusal(projectName, problem)).toJson();
        }
        if (ProjectContext.of(projectName).exists())
        {
            return ToolResult.error(ERR_PROJECT_EXISTS_PREFIX + projectName + ERR_PROJECT_EXISTS_SUFFIX).toJson();
        }
        Path directory;
        try
        {
            directory = WorkspacePaths.defaultProjectFolder(projectName);
        }
        catch (InvalidPathException e)
        {
            // A name the workspace accepts but this file system's paths cannot hold. On Windows the workspace already
            // refuses every character a path cannot hold; this keeps any other platform's case a refusal too.
            return ToolResult.error(illegalProjectNameRefusal(projectName, e.getReason())).toJson();
        }
        String refusal = existingDirectoryRefusal(projectName, directory);
        return refusal == null ? null : ToolResult.error(refusal).toJson();
    }

    /**
     * The refusal text for a project name that cannot be a workspace project's name. Package-visible for tests.
     *
     * @param projectName the project name the create would use
     * @param reason why it cannot, as the workspace or the file system says
     * @return the refusal
     */
    static String illegalProjectNameRefusal(String projectName, String reason)
    {
        return "The project name '" + projectName + "' is not a legal workspace project name: " + reason //$NON-NLS-1$ //$NON-NLS-2$
            + ". Nothing was created. Pass another projectName, or, without projectName, another name (the " //$NON-NLS-1$
            + "project name defaults from it)."; //$NON-NLS-1$
    }

    /**
     * The refusal text when {@code directory} - the place of project {@code projectName}, which no workspace project
     * has - exists on disk; {@code null} when it does not, or is not known. Package-visible for tests.
     *
     * @param projectName the project name
     * @param directory where EDT's create would put it, or {@code null} when the workspace has no local location
     * @return the refusal, or {@code null}
     */
    static String existingDirectoryRefusal(String projectName, Path directory)
    {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
        {
            return null;
        }
        return "The directory " + directory + " of project '" + projectName + "' already exists on disk, though " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "the workspace has no project of that name (delete_project without deleteContent leaves one): EDT " //$NON-NLS-1$
            + "would create the new project over the files there, and delete them with it if that create failed. " //$NON-NLS-1$
            + "Nothing was created. If a workspace project keeps its files there, delete it with delete_project and " //$NON-NLS-1$
            + "deleteContent=true; otherwise check what the directory holds and remove it. Then retry, or pass " //$NON-NLS-1$
            + "another name or projectName."; //$NON-NLS-1$
    }

    /**
     * Maps the {@code scriptVariant} parameter string to a {@link ScriptVariant} plus its
     * language code/name tokens (read-only). Defaults to Russian when blank.
     */
    private static ConfigurationScript resolveConfigurationScript(String scriptVariantStr)
    {
        boolean isRussian = scriptVariantStr == null || scriptVariantStr.isEmpty()
            || SCRIPT_RUSSIAN.equalsIgnoreCase(scriptVariantStr);
        ScriptVariant scriptVariant = isRussian ? ScriptVariant.RUSSIAN : ScriptVariant.ENGLISH;
        String langCode = isRussian ? "ru" : "en"; //$NON-NLS-1$ //$NON-NLS-2$
        String langName = isRussian ? SCRIPT_RUSSIAN : SCRIPT_ENGLISH;
        return new ConfigurationScript(scriptVariant, langCode, langName);
    }

    /**
     * Applies the synonym to the in-memory standalone {@link Configuration} via its
     * language-code-keyed {@code synonym} EMap (in-memory model mutation only). The synonym
     * value defaults to {@code configName} when {@code synonym} is blank.
     *
     * @return {@code true} when the synonym was applied; {@code false} when no synonym map
     *     was available on the new Configuration
     */
    private static boolean applyConfigurationSynonym(Configuration config, String langCode, String synonym,
        String configName)
    {
        String synonymValue = (synonym != null && !synonym.isEmpty()) ? synonym : configName;
        EMap<String, String> synonymMap = config.getSynonym();
        if (synonymMap != null)
        {
            synonymMap.put(langCode, synonymValue);
            return true;
        }
        return false;
    }

    /**
     * Builds the normal success JSON response for a created standalone configuration project
     * (read-only), adding {@code synonymNote} when the synonym could not be applied.
     *
     * @return the response JSON string
     */
    private static String buildConfigurationSuccessResponse(String effectiveProjectName, String configName,
        ScriptVariant scriptVariant, Version version, String projectState, Map<String, Object> codestyleMap,
        boolean synonymApplied)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_CONFIGURATION)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_SCRIPT_VARIANT, scriptVariant.getLiteral())
            .put(KEY_VERSION, version.toString())
            .put(KEY_STATE, projectState)
            .put(KEY_CODESTYLE, codestyleMap)
            .put(McpKeys.MESSAGE, "Configuration project '" + effectiveProjectName + "' created."); //$NON-NLS-1$ //$NON-NLS-2$
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

    private String executeExternalObjects(CreateRequest req)
    {
        String configName = req.configName;
        String scriptVariantStr = req.scriptVariantStr;

        // Derive default EDT project name: <configName>
        String effectiveProjectName = (req.projectName != null) ? req.projectName : configName;

        // Check the new project name is not taken - in the workspace or by a directory on disk
        String taken = takenProjectRefusal(effectiveProjectName);
        if (taken != null)
        {
            return taken;
        }

        // Parse version (read-only)
        VersionResolution versionResolution = parseVersion(req.versionStr);
        if (versionResolution.error != null)
        {
            return versionResolution.error;
        }
        Version version = versionResolution.version;

        // Resolve IExternalObjectProjectManager
        IExternalObjectProjectManager extObjMgr = Activator.getDefault().getExternalObjectProjectManager();
        if (extObjMgr == null)
        {
            return ToolResult.error(
                "IExternalObjectProjectManager service not available. The EDT platform may not be ready.").toJson(); //$NON-NLS-1$
        }

        MdObject externalObject = null;
        if (req.externalObject != null)
        {
            IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
            if (factory == null)
            {
                return ToolResult.error("IModelObjectFactory service not available; cannot seed externalObject '" //$NON-NLS-1$
                    + req.externalObject.canonicalFqn + "'. Retry after EDT finishes starting, or omit " //$NON-NLS-1$
                    + "externalObject to create an empty project.").toJson(); //$NON-NLS-1$
            }
            try
            {
                externalObject = createExternalObjectRoot(factory, req.externalObject, version);
            }
            catch (RuntimeException e)
            {
                return ToolResult.error("Failed to initialize externalObject '" + req.externalObject.canonicalFqn //$NON-NLS-1$
                    + "': " + e.getMessage() + ". Retry after EDT finishes starting, or omit externalObject " //$NON-NLS-1$ //$NON-NLS-2$
                    + "to create an empty project.").toJson(); //$NON-NLS-1$
            }
            if (externalObject == null)
            {
                return ToolResult.error("Failed to initialize externalObject '" + req.externalObject.canonicalFqn //$NON-NLS-1$
                    + "'. Retry after EDT finishes starting, or omit externalObject to create an empty project.") //$NON-NLS-1$
                    .toJson();
            }
        }

        // Create the external objects project in a background Job
        final IProject[] createdHolder = new IProject[1];
        final Throwable[] errorHolder = new Throwable[1];
        final String finalEffectiveProjectName = effectiveProjectName;
        final Version finalVersion = version;
        final MdObject finalExternalObject = externalObject;

        Job createJob = new Job(LOG_PREFIX + finalEffectiveProjectName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    // A null external object preserves the legitimate empty-project/import workflow;
                    // the second null means the project has no parent configuration.
                    createdHolder[0] = extObjMgr.create(
                        finalEffectiveProjectName, finalVersion, finalExternalObject, null, monitor);
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
            // Creation exceeded the wait window. A requested root needs its own read-back:
            // the project container existing does not prove EDT attached the root before cancel.
            return buildExternalObjectsSlowResponse(finalEffectiveProjectName, configName,
                finalVersion, scriptVariantStr, req.externalObject);
        }
        if (jobResult.errorJson != null)
        {
            return jobResult.errorJson;
        }

        if (errorHolder[0] != null)
        {
            Activator.logError("create_project (externalObjects) failed", errorHolder[0]); //$NON-NLS-1$
            return creationFailure(finalEffectiveProjectName,
                "Failed to create external objects project: " + errorHolder[0].getMessage()); //$NON-NLS-1$
        }

        Activator.logInfo(LOG_PREFIX + "created externalObjects '" + finalEffectiveProjectName + "'"); //$NON-NLS-1$ //$NON-NLS-2$

        // Wait for lifecycle STARTED
        String projectState = waitForLifecycle(finalEffectiveProjectName, createdHolder[0], KIND_EXTERNAL_OBJECTS);

        // Apply v8codestyle preferences
        Map<String, Object> codestyleMap =
            applyCodestylePrefs(finalEffectiveProjectName, req.standardChecks, req.commonChecks);

        return buildExternalObjectsSuccessResponse(new ExternalObjectsSuccessInputs(extObjMgr,
            finalEffectiveProjectName, configName, finalVersion, createdHolder[0], projectState,
            scriptVariantStr, codestyleMap, req.externalObject));
    }

    /**
     * Creates the detached root exactly like the Configuration paths: version-aware factory first,
     * then default-reference filling, then the caller's programmatic Name.
     */
    static MdObject createExternalObjectRoot(IModelObjectFactory factory, ExternalObjectSpec spec, Version version)
    {
        return ExternalObjectRoots.newRootForNewProject(factory, spec.eClass, spec.objectName, version);
    }

    /**
     * Builds the normal success JSON response for a created externalObjects project. Applies the
     * requested scriptVariant post-create (non-fatal on failure, mirroring the original inline
     * block exactly) and emits the canonical literal/note when supplied.
     *
     * @return the response JSON string
     */
    private static String buildExternalObjectsSuccessResponse(ExternalObjectsSuccessInputs in)
    {
        // Post-create: apply scriptVariant if supplied (non-fatal on failure)
        // Input is guaranteed "Russian" or "English" (case-insensitive) by the shared validation above.
        String scriptVariantNote = null;
        ScriptVariant requestedSv = null;
        if (in.scriptVariantStr != null && !in.scriptVariantStr.isEmpty())
        {
            requestedSv = SCRIPT_RUSSIAN.equalsIgnoreCase(in.scriptVariantStr)
                ? ScriptVariant.RUSSIAN : ScriptVariant.ENGLISH;
            scriptVariantNote = applyExternalObjectsScriptVariant(in.extObjMgr, in.effectiveProjectName,
                in.createdProject, requestedSv, in.projectState);
        }

        String message = "External objects project '" + in.effectiveProjectName + "' created."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (scriptVariantNote != null)
        {
            message = message + " " + scriptVariantNote; //$NON-NLS-1$
        }
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_CREATED)
            .put(McpKeys.PROJECT, in.effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTERNAL_OBJECTS)
            .put("name", in.configName) //$NON-NLS-1$
            .put(KEY_VERSION, in.version.toString())
            .put(KEY_STATE, in.projectState)
            .put(KEY_CODESTYLE, in.codestyleMap)
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
        if (in.externalObject != null)
        {
            result.put(KEY_EXTERNAL_OBJECT, in.externalObject.canonicalFqn);
            in.externalObject.normReport.addTo(result);
        }
        return result.toJson();
    }

    /**
     * Builds the externalObjects slow-path response (creation exceeded the wait window).
     * When a {@code scriptVariantStr} was supplied, emits the canonical ScriptVariant literal
     * and a note that setScriptVariant was skipped.
     *
     * @return the response JSON string
     */
    static String buildExternalObjectsSlowResponse(String effectiveProjectName, String configName,
        Version version, String scriptVariantStr, ExternalObjectSpec externalObject)
    {
        if (externalObject == null)
        {
            // This is the pre-seeded-root response byte-for-byte: project existence is the complete
            // fact for the intentional empty-project workflow, so its old answer remains correct.
            return buildEmptyExternalObjectsSlowResponse(effectiveProjectName, configName, version,
                scriptVariantStr);
        }

        boolean rootConfirmed = confirmExternalObject(effectiveProjectName, externalObject);
        String rootState = rootConfirmed ? "rootConfirmed" : VAL_ROOT_UNCONFIRMED; //$NON-NLS-1$
        String typeToken = externalObject.canonicalFqn.substring(0,
            externalObject.canonicalFqn.indexOf('.'));
        String verification = " Once indexing finishes, verify the root with " //$NON-NLS-1$
            + "get_metadata_objects (projectName='" + effectiveProjectName //$NON-NLS-1$
            + "', metadataType='" + typeToken + "'). Do not repeat create_project: the " //$NON-NLS-1$ //$NON-NLS-2$
            + "duplicate-project guard will refuse the existing project."; //$NON-NLS-1$
        String message;
        if (rootConfirmed)
        {
            message = "External objects project '" + effectiveProjectName //$NON-NLS-1$
                + "' exists after creation exceeded the " + (CREATE_TIMEOUT_MS / 1000) //$NON-NLS-1$
                + "s wait window; requested root '" + externalObject.canonicalFqn //$NON-NLS-1$
                + "' was CONFIRMED through metadata scope resolution, but project creation did " //$NON-NLS-1$
                + "not finish within the wait window." + verification; //$NON-NLS-1$
        }
        else
        {
            message = "External objects project '" + effectiveProjectName //$NON-NLS-1$
                + "' exists after creation exceeded the " + (CREATE_TIMEOUT_MS / 1000) //$NON-NLS-1$
                + "s wait window, but requested root '" + externalObject.canonicalFqn //$NON-NLS-1$
                + "' was NOT CONFIRMED. EDT may still be indexing the project, or creation may fail " //$NON-NLS-1$
                + "after creating its container." + verification; //$NON-NLS-1$
        }

        ToolResult slowResult = ToolResult.success()
            .put(McpKeys.ACTION, VAL_VERIFICATION_REQUIRED)
            .put(McpKeys.PROJECT, effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTERNAL_OBJECTS)
            .put("name", configName) //$NON-NLS-1$
            .put(KEY_VERSION, version.toString())
            .put(KEY_STATE, rootState)
            .put(KEY_EXTERNAL_OBJECT, externalObject.canonicalFqn)
            .put(KEY_EXTERNAL_OBJECT_CONFIRMED, rootConfirmed)
            .put(KEY_CODESTYLE, slowPathCodestyleMap())
            .put(McpKeys.MESSAGE, message);
        externalObject.normReport.addTo(slowResult);
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

    /** The unchanged slow response for an intentionally empty external-objects project. */
    private static String buildEmptyExternalObjectsSlowResponse(String effectiveProjectName, String configName,
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
     * Best-effort read-back through the same metadata scope used by get_metadata_objects. A false
     * answer includes every transient case (not started, still indexing, or a momentarily failed
     * root-set read); none is promoted to a second tool error.
     */
    private static boolean confirmExternalObject(String effectiveProjectName, ExternalObjectSpec externalObject)
    {
        try
        {
            ProjectContext.ConfigurationResult resolved =
                ProjectContext.resolveMetadataRoot(effectiveProjectName);
            if (!resolved.ok())
            {
                return false;
            }
            int dot = externalObject.canonicalFqn.indexOf('.');
            String typeToken = externalObject.canonicalFqn.substring(0, dot);
            return resolved.scope().findObject(typeToken, externalObject.objectName) != null;
        }
        catch (RuntimeException e)
        {
            // Indexing may legitimately make the root unreadable here. The structured false is the
            // caller-visible fact; logging this expected slow-path race as an error would be noise.
            return false;
        }
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

    /**
     * Parses the optional platform-version string (read-only). A {@code null} {@code versionStr}
     * yields {@link Version#LATEST}; a malformed value yields a ready-to-return JSON error.
     *
     * @return a {@link VersionResolution} carrying the parsed {@link Version}, or a ready-to-return
     *     JSON error for a malformed value
     */
    private static VersionResolution parseVersion(String versionStr)
    {
        try
        {
            Version version = (versionStr != null) ? new Version(versionStr) : Version.LATEST;
            return new VersionResolution(null, version);
        }
        catch (Exception e)
        {
            return new VersionResolution(ToolResult.error("Invalid version string '" + versionStr //$NON-NLS-1$
                + "'. Use a dot-separated version like '8.3.27'. " //$NON-NLS-1$
                + "Original error: " + e.getMessage()).toJson(), null); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the {@link IModelObjectFactory} and {@link IConfigurationProjectManager} services
     * required by the standalone-configuration path (read-only lookups).
     *
     * @return {@link ConfigurationServices#ok} with the resolved services, or
     *     {@link ConfigurationServices#failure} carrying a ready-to-return JSON error
     */
    private static ConfigurationServices resolveConfigurationServices()
    {
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        if (factory == null)
        {
            return ConfigurationServices.failure(
                ToolResult.error("IModelObjectFactory (MD) not available. MdPlugin may not be ready.").toJson()); //$NON-NLS-1$
        }

        IConfigurationProjectManager configMgr = Activator.getDefault().getConfigurationProjectManager();
        if (configMgr == null)
        {
            return ConfigurationServices.failure(ToolResult.error(
                "IConfigurationProjectManager service not available. The EDT platform may not be ready.").toJson()); //$NON-NLS-1$
        }
        return ConfigurationServices.ok(factory, configMgr);
    }

    /**
     * Sets the optional free-text comment on the in-memory {@link Configuration} when non-blank
     * (in-memory model mutation only). No-op when {@code comment} is {@code null} or empty.
     */
    private static void applyComment(Configuration config, String comment)
    {
        if (comment != null && !comment.isEmpty())
        {
            config.setComment(comment);
        }
    }

    /** Status codes returned by {@link #runCreateJob}. Package-visible for tests. */
    enum CreateStatus
    {
        /** Job completed within the timeout window; caller must check {@code errorHolder}. */
        OK,
        /**
         * Job timed out but the project already exists in the workspace (slow creation
         * past the wait window). Existence is the only fact this status carries; the caller
         * must build a kind-specific response and verify any finer-grained mutation it needs.
         * The job had NOT ended: its cancellation was only requested, so the project manager's
         * {@code create()} may still be running - the caller writes nothing to the project.
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
     * Outcome of {@link #validateExtensionBaseProject}: either a ready-to-return JSON
     * {@code error}, or the validated base-project handles. Exactly one of {@code error}
     * and {@code baseIProject} is non-null on success/failure respectively.
     */
    private static final class BaseProjectResolution
    {
        /** Ready-to-return JSON error, or {@code null} when validation passed. */
        final String error;
        /** Trimmed base project name (non-null when {@code error} is null). */
        final String baseProjectName;
        /** Derived EDT project name for the new extension (non-null when {@code error} is null). */
        final String effectiveProjectName;
        /** The validated, open base {@link IProject} (non-null when {@code error} is null). */
        final IProject baseIProject;

        private BaseProjectResolution(String error, String baseProjectName, String effectiveProjectName,
            IProject baseIProject)
        {
            this.error = error;
            this.baseProjectName = baseProjectName;
            this.effectiveProjectName = effectiveProjectName;
            this.baseIProject = baseIProject;
        }

        static BaseProjectResolution failure(String error)
        {
            return new BaseProjectResolution(error, null, null, null);
        }

        static BaseProjectResolution ok(String baseProjectName, String effectiveProjectName, IProject baseIProject)
        {
            return new BaseProjectResolution(null, baseProjectName, effectiveProjectName, baseIProject);
        }
    }

    /**
     * Outcome of {@link #resolveExtensionServices}: either a ready-to-return JSON {@code error},
     * or the platform services/model handles needed to build the extension's root. All value
     * fields are non-null exactly when {@code error} is null.
     */
    private static final class ExtensionServices
    {
        /** Ready-to-return JSON error, or {@code null} when resolution succeeded. */
        final String error;
        final IExtensionProjectManager extMgr;
        final IModelObjectAdopter adopter;
        final IBmModel baseModel;
        final Version version;

        private ExtensionServices(String error, IExtensionProjectManager extMgr, IModelObjectAdopter adopter,
            IBmModel baseModel, Version version)
        {
            this.error = error;
            this.extMgr = extMgr;
            this.adopter = adopter;
            this.baseModel = baseModel;
            this.version = version;
        }

        static ExtensionServices failure(String error)
        {
            return new ExtensionServices(error, null, null, null, null);
        }

        static ExtensionServices ok(IExtensionProjectManager extMgr, IModelObjectAdopter adopter,
            IBmModel baseModel, Version version)
        {
            return new ExtensionServices(null, extMgr, adopter, baseModel, version);
        }
    }

    /**
     * Outcome of {@link #resolveExtensionPurpose}: either a ready-to-return JSON {@code error},
     * or the resolved {@link ConfigurationExtensionPurpose}. Exactly one field is non-null.
     */
    private static final class PurposeResolution
    {
        final String error;
        final ConfigurationExtensionPurpose purpose;

        private PurposeResolution(String error, ConfigurationExtensionPurpose purpose)
        {
            this.error = error;
            this.purpose = purpose;
        }
    }

    /**
     * Outcome of {@link #resolveCompatibilityMode}: either a ready-to-return JSON {@code error},
     * or the resolved {@link CompatibilityMode} (which may legitimately be {@code null} when no
     * value was supplied). The {@code error} field disambiguates the two null cases.
     */
    private static final class CompatModeResolution
    {
        final String error;
        final CompatibilityMode compatMode;

        private CompatModeResolution(String error, CompatibilityMode compatMode)
        {
            this.error = error;
            this.compatMode = compatMode;
        }
    }

    /**
     * Immutable bundle of the raw parameter values needed by {@link #validateKindConstraints}
     * and its per-group sub-validators (avoids an over-long parameter list).
     */
    private static final class KindConstraintInputs
    {
        final String projectKind;
        final boolean isExtension;
        final boolean isExternalObjects;
        final String versionStr;
        final String baseProjectName;
        final String prefix;
        final String purposeStr;
        final String compatModeStr;
        final String synonym;
        final String comment;
        final String scriptVariantStr;
        final String externalObjectStr;

        private KindConstraintInputs(String projectKind, boolean isExtension, boolean isExternalObjects, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String versionStr, String baseProjectName, String prefix, String purposeStr, String compatModeStr,
            String synonym, String comment, String scriptVariantStr, String externalObjectStr)
        {
            this.projectKind = projectKind;
            this.isExtension = isExtension;
            this.isExternalObjects = isExternalObjects;
            this.versionStr = versionStr;
            this.baseProjectName = baseProjectName;
            this.prefix = prefix;
            this.purposeStr = purposeStr;
            this.compatModeStr = compatModeStr;
            this.synonym = synonym;
            this.comment = comment;
            this.scriptVariantStr = scriptVariantStr;
            this.externalObjectStr = externalObjectStr;
        }
    }

    /**
     * Immutable bundle of the normalized request parameters passed to the kind-specific
     * {@code executeXxx} handlers (avoids an over-long parameter list). Field values are the
     * post-normalization values computed in {@link #execute}.
     */
    private static final class CreateRequest
    {
        final String configName;
        final String projectName;
        final String versionStr;
        final String baseProjectName;
        final String prefix;
        final String synonym;
        final String comment;
        final String purposeStr;
        final String compatModeStr;
        final String scriptVariantStr;
        final ExternalObjectSpec externalObject;
        final boolean standardChecks;
        final boolean commonChecks;

        private CreateRequest(String configName, String projectName, String versionStr, String baseProjectName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String prefix, String synonym, String comment, String purposeStr, String compatModeStr,
            String scriptVariantStr, ExternalObjectSpec externalObject, boolean standardChecks, boolean commonChecks)
        {
            this.configName = configName;
            this.projectName = projectName;
            this.versionStr = versionStr;
            this.baseProjectName = baseProjectName;
            this.prefix = prefix;
            this.synonym = synonym;
            this.comment = comment;
            this.purposeStr = purposeStr;
            this.compatModeStr = compatModeStr;
            this.scriptVariantStr = scriptVariantStr;
            this.externalObject = externalObject;
            this.standardChecks = standardChecks;
            this.commonChecks = commonChecks;
        }
    }

    /**
     * What the steps after an extension's {@code create()} found, from which its state and message are
     * built. Package-visible for tests.
     */
    static final class ExtensionFacts
    {
        /** The effective extension compatibility mode. */
        final CompatibilityMode mode;
        /** The base configuration's compatibility mode. */
        final CompatibilityMode baseMode;
        /** Whether the caller passed compatibilityMode. */
        final boolean modeRequested;
        /** The warning for a requested mode above the base's, or {@code null}. */
        final String compatibilityNote;
        /**
         * The root handed to EDT's create, as its file must show it: the look confirms no root file that shows another
         * name, prefix, purpose or mode, or lacks one ({@link ExtensionRootBuilder#missingOnDisk}) - hardening beyond
         * {@link CreateProjectTool#takenProjectRefusal}, which keeps an earlier project's files out of a new project's
         * directory.
         */
        final ExtensionRootBuilder.RootIdentity root;
        /** Why the command interfaces were not created, or {@code null} when they were. */
        String commandInterfaceFailure;
        /** Why the save of the command interfaces this call created was not queued, or {@code null} when it was. */
        String commandInterfaceSaveFailure;
        /** Whether EDT reported the new project's context started. */
        boolean lifecycleStarted;
        /**
         * What the bounded look at the root file - and at the command interface files - found, or {@code null} when it
         * was not looked at.
         */
        ExtensionRootBuilder.RootOnDisk disk;

        ExtensionFacts(CompatibilityMode mode, CompatibilityMode baseMode, boolean modeRequested,
            String compatibilityNote, ExtensionRootBuilder.RootIdentity root)
        {
            this.mode = mode;
            this.baseMode = baseMode;
            this.modeRequested = modeRequested;
            this.compatibilityNote = compatibilityNote;
            this.root = root;
        }
    }

    /**
     * Immutable bundle of the common fields shared by the extension success and slow-path
     * response builders (avoids an over-long parameter list). Package-visible for tests.
     */
    static final class ExtensionResponseData
    {
        final String effectiveProjectName;
        final String configName;
        final String baseProjectName;
        final String prefix;
        final ConfigurationExtensionPurpose purpose;
        final ScriptVariant scriptVariant;
        final Version version;
        final boolean synonymApplied;

        ExtensionResponseData(String effectiveProjectName, String configName, String baseProjectName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String prefix, ConfigurationExtensionPurpose purpose, ScriptVariant scriptVariant, Version version,
            boolean synonymApplied)
        {
            this.effectiveProjectName = effectiveProjectName;
            this.configName = configName;
            this.baseProjectName = baseProjectName;
            this.prefix = prefix;
            this.purpose = purpose;
            this.scriptVariant = scriptVariant;
            this.version = version;
            this.synonymApplied = synonymApplied;
        }
    }

    /**
     * Outcome of {@link #parseVersion}: either a ready-to-return JSON {@code error}, or the
     * parsed {@link Version}. Exactly one field is non-null.
     */
    private static final class VersionResolution
    {
        final String error;
        final Version version;

        private VersionResolution(String error, Version version)
        {
            this.error = error;
            this.version = version;
        }
    }

    /**
     * Outcome of {@link #resolveConfigurationServices}: either a ready-to-return JSON {@code error},
     * or the resolved {@link IModelObjectFactory} and {@link IConfigurationProjectManager}. The
     * value fields are non-null exactly when {@code error} is null.
     */
    private static final class ConfigurationServices
    {
        final String error;
        final IModelObjectFactory factory;
        final IConfigurationProjectManager configMgr;

        private ConfigurationServices(String error, IModelObjectFactory factory,
            IConfigurationProjectManager configMgr)
        {
            this.error = error;
            this.factory = factory;
            this.configMgr = configMgr;
        }

        static ConfigurationServices failure(String error)
        {
            return new ConfigurationServices(error, null, null);
        }

        static ConfigurationServices ok(IModelObjectFactory factory, IConfigurationProjectManager configMgr)
        {
            return new ConfigurationServices(null, factory, configMgr);
        }
    }

    /**
     * Immutable result of {@link #resolveConfigurationScript}: the {@link ScriptVariant} plus the
     * language code and name tokens for the default Language.
     */
    private static final class ConfigurationScript
    {
        final ScriptVariant scriptVariant;
        final String langCode;
        final String langName;

        private ConfigurationScript(ScriptVariant scriptVariant, String langCode, String langName)
        {
            this.scriptVariant = scriptVariant;
            this.langCode = langCode;
            this.langName = langName;
        }
    }

    /** Headless-safe resolution of an external-object root parameter. */
    static final class ExternalObjectSpec
    {
        final String error;
        final EClass eClass;
        final String objectName;
        final String canonicalFqn;
        final MdNameNormalizer.Report normReport;

        private ExternalObjectSpec(String error, EClass eClass, String objectName, String canonicalFqn,
            MdNameNormalizer.Report normReport)
        {
            this.error = error;
            this.eClass = eClass;
            this.objectName = objectName;
            this.canonicalFqn = canonicalFqn;
            this.normReport = normReport;
        }

        static ExternalObjectSpec failure(String error)
        {
            return new ExternalObjectSpec(error, null, null, null, null);
        }

        static ExternalObjectSpec success(EClass eClass, String objectName, String canonicalFqn,
            MdNameNormalizer.Report normReport)
        {
            return new ExternalObjectSpec(null, eClass, objectName, canonicalFqn, normReport);
        }
    }

    /**
     * Immutable bundle of the inputs needed by {@link #buildExternalObjectsSuccessResponse}
     * (avoids an over-long parameter list).
     */
    private static final class ExternalObjectsSuccessInputs
    {
        final IExternalObjectProjectManager extObjMgr;
        final String effectiveProjectName;
        final String configName;
        final Version version;
        final IProject createdProject;
        final String projectState;
        final String scriptVariantStr;
        final Map<String, Object> codestyleMap;
        final ExternalObjectSpec externalObject;

        private ExternalObjectsSuccessInputs(IExternalObjectProjectManager extObjMgr, String effectiveProjectName, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String configName, Version version, IProject createdProject, String projectState,
            String scriptVariantStr, Map<String, Object> codestyleMap, ExternalObjectSpec externalObject)
        {
            this.extObjMgr = extObjMgr;
            this.effectiveProjectName = effectiveProjectName;
            this.configName = configName;
            this.version = version;
            this.createdProject = createdProject;
            this.projectState = projectState;
            this.scriptVariantStr = scriptVariantStr;
            this.codestyleMap = codestyleMap;
            this.externalObject = externalObject;
        }
    }

    /**
     * Validates the extension's base project and the new project name (read-only). Trims
     * {@code baseProjectName}, requires it to be non-blank, derives the EDT project name
     * ({@code projectName} or {@code <baseProjectName>.<configName>}), and verifies that the
     * new project does not already exist and that the base project exists, is open and carries
     * {@code V8ConfigurationNature}.
     *
     * @return {@link BaseProjectResolution#ok} with the validated handles, or
     *     {@link BaseProjectResolution#failure} carrying a ready-to-return JSON error
     */
    private BaseProjectResolution validateExtensionBaseProject(String configName, String projectName,
        String baseProjectName)
    {
        // baseProjectName required for extension
        if (baseProjectName == null || baseProjectName.trim().isEmpty())
        {
            return BaseProjectResolution.failure(ToolResult.error(
                "'baseProjectName' is required for projectKind=extension. " //$NON-NLS-1$
                    + "Use list_projects to find the base configuration project name.").toJson()); //$NON-NLS-1$
        }
        baseProjectName = baseProjectName.trim();

        // Derive default EDT project name: <baseProjectName>.<configName>
        String effectiveProjectName = (projectName != null) ? projectName
            : baseProjectName + "." + configName; //$NON-NLS-1$

        // Check the new project name is not taken - in the workspace or by a directory on disk
        String taken = takenProjectRefusal(effectiveProjectName);
        if (taken != null)
        {
            return BaseProjectResolution.failure(taken);
        }

        // Validate the base project
        ProjectContext baseCtx = ProjectContext.of(baseProjectName);
        if (!baseCtx.exists())
        {
            return BaseProjectResolution.failure(ToolResult.error(ProjectContext.notFoundMessage(baseProjectName)).toJson());
        }
        if (!baseCtx.isOpen())
        {
            return BaseProjectResolution.failure(ToolResult.error("Base project '" + baseProjectName //$NON-NLS-1$
                + "' exists but is not open. Open it first.").toJson()); //$NON-NLS-1$
        }
        IProject baseIProject = baseCtx.project();

        // Validate the base project has V8ConfigurationNature (not an extension itself)
        try
        {
            if (!baseIProject.hasNature(NATURE_CONFIGURATION))
            {
                return BaseProjectResolution.failure(ToolResult.error("'" + baseProjectName //$NON-NLS-1$
                    + "' is not a configuration project (V8ConfigurationNature). " //$NON-NLS-1$
                    + "Pass the BASE configuration's project name, not an extension.").toJson()); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError(LOG_PREFIX + "error checking nature for " + baseProjectName, e); //$NON-NLS-1$
            return BaseProjectResolution.failure(
                ToolResult.error("Failed to inspect base project nature: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }

        return BaseProjectResolution.ok(baseProjectName, effectiveProjectName, baseIProject);
    }

    /**
     * Resolves the platform services and the base project's handles needed to build the extension's root
     * (read-only lookups): {@link IExtensionProjectManager}, the base {@link Version} (through
     * {@link IV8ProjectManager}; EDT's wizard adopts with it), EDT's {@link IModelObjectAdopter} and the
     * base project's BM model. Every refusal comes before anything is created.
     *
     * @return {@link ExtensionServices#ok} with the resolved handles, or
     *     {@link ExtensionServices#failure} carrying a ready-to-return JSON error
     */
    private ExtensionServices resolveExtensionServices(IProject baseIProject, String baseProjectName)
    {
        IExtensionProjectManager extMgr = Activator.getDefault().getExtensionProjectManager();
        if (extMgr == null)
        {
            return ExtensionServices.failure(ToolResult.error(
                "IExtensionProjectManager service not available. The EDT platform may not be ready.").toJson()); //$NON-NLS-1$
        }

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ExtensionServices.failure(ToolResult.error("IV8ProjectManager service not available.").toJson()); //$NON-NLS-1$
        }

        IV8Project baseV8Project = v8ProjectManager.getProject(baseIProject);
        if (baseV8Project == null)
        {
            return ExtensionServices.failure(ToolResult.error("Could not obtain IV8Project for base project '" //$NON-NLS-1$
                + baseProjectName + "'. Ensure the project is fully loaded.").toJson()); //$NON-NLS-1$
        }

        Version version = baseV8Project.getVersion();

        IModelObjectAdopter adopter = resolveAdopter();
        if (adopter == null)
        {
            return ExtensionServices.failure(
                ToolResult.error(ExtensionRootBuilder.adopterUnavailable(baseProjectName)).toJson());
        }

        BmModelResolver.Resolution baseModel = BmModelResolver.resolve(baseIProject);
        if (!baseModel.isAvailable())
        {
            return ExtensionServices.failure(ToolResult.error(
                baseModel.actionableError(NAME, "Nothing was created.")).toJson()); //$NON-NLS-1$
        }

        return ExtensionServices.ok(extMgr, adopter, baseModel.getModel(), version);
    }

    /**
     * EDT's model object adopter, or {@code null} when it is not available. {@link ServiceAccess#get} throws
     * rather than returning {@code null} for a service that is not registered.
     */
    private static IModelObjectAdopter resolveAdopter()
    {
        try
        {
            return ServiceAccess.get(IModelObjectAdopter.class);
        }
        catch (RuntimeException e)
        {
            // The call is refused with an actionable error; the platform condition is a warning, not a failure.
            Activator.logWarning(LOG_PREFIX + "model object adopter service not available: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Maps the {@code purpose} parameter string to a {@link ConfigurationExtensionPurpose}
     * (read-only). Defaults to {@link ConfigurationExtensionPurpose#CUSTOMIZATION} when blank.
     *
     * @return a {@link PurposeResolution} carrying the resolved purpose, or a ready-to-return
     *     JSON error for an unknown value
     */
    private static PurposeResolution resolveExtensionPurpose(String purposeStr)
    {
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
                    return new PurposeResolution(ToolResult.error("Unknown purpose value: '" + purposeStr //$NON-NLS-1$
                        + "'. Allowed values: Customization, AddOn, Patch.").toJson(), null); //$NON-NLS-1$
            }
        }
        return new PurposeResolution(null, purpose);
    }

    /**
     * Resolves the optional {@code compatibilityMode} string to a {@link CompatibilityMode}
     * (read-only). Tries an exact {@link CompatibilityMode#get} first, then a normalized
     * (alphanumeric, lower-cased) match against each candidate's literal and name. A {@code null}
     * {@code compatModeStr} yields a {@code null} mode with no error.
     *
     * @return a {@link CompatModeResolution} carrying the resolved mode (possibly {@code null}),
     *     or a ready-to-return JSON error for an unknown value
     */
    private static CompatModeResolution resolveCompatibilityMode(String compatModeStr)
    {
        CompatibilityMode compatMode = null;
        if (compatModeStr != null)
        {
            compatMode = CompatibilityMode.get(compatModeStr);
            if (compatMode == null)
            {
                compatMode = matchCompatibilityModeByNormalizedName(compatModeStr);
            }
            if (compatMode == null)
            {
                return new CompatModeResolution(buildUnknownCompatModeError(compatModeStr), null);
            }
        }
        return new CompatModeResolution(null, compatMode);
    }

    /**
     * Finds the {@link CompatibilityMode} whose literal or name matches {@code compatModeStr}
     * after stripping non-alphanumerics and lower-casing both sides (read-only).
     *
     * @return the matching mode, or {@code null} when none matches
     */
    private static CompatibilityMode matchCompatibilityModeByNormalizedName(String compatModeStr)
    {
        String normalizedInput = compatModeStr.replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
        for (CompatibilityMode candidate : CompatibilityMode.VALUES)
        {
            String normalizedCandidate = candidate.getLiteral()
                .replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
            if (normalizedInput.equals(normalizedCandidate))
            {
                return candidate;
            }
            String normalizedName = candidate.getName()
                .replaceAll(RE_NON_ALNUM, "").toLowerCase(); //$NON-NLS-1$
            if (normalizedInput.equals(normalizedName))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Builds the ready-to-return JSON error for an unrecognized {@code compatibilityMode} value,
     * listing up to the first three known literals as examples (read-only).
     */
    private static String buildUnknownCompatModeError(String compatModeStr)
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
            + "), or omit to use the base configuration's mode.").toJson(); //$NON-NLS-1$
    }

    /**
     * Builds the JSON response for an extension whose create job had not ended when the wait window closed
     * ({@link CreateStatus#SLOW_EXISTS}; read-only): the project exists, and the answer asks for verification as a
     * slow seeded create does - {@code action="verificationRequired"}, {@code state="rootUnconfirmed"},
     * {@code codestyle.applied=false} - with what names the project and none of the root's values (configuration name,
     * prefix, purpose, script variant, compatibility mode): nobody checked that EDT attached that root. Nothing after
     * {@code create()} ran, and the message says so: EDT's {@code create()} may still be running, so the root handed to it
     * is not claimed as attached, and the command interface files EDT's wizard creates were not created - with how to
     * get them once that create has ended, since no MCP tool attaches them to an existing extension and a delete or a
     * second create while it runs would race it. Adds {@code synonymNote} when the synonym could not be applied.
     * Package-visible for tests.
     */
    static String buildExtensionSlowResponse(ExtensionResponseData data, ExtensionFacts facts)
    {
        List<String> parts = new ArrayList<>();
        parts.add("Extension project '" + data.effectiveProjectName //$NON-NLS-1$
            + "' exists, but EDT's create of it was still running when the " + (CREATE_TIMEOUT_MS / 1000) //$NON-NLS-1$
            + "s wait window ended; this call asked that create to stop and did not wait for it, so EDT may " //$NON-NLS-1$
            + "still finish it or stop partway."); //$NON-NLS-1$
        parts.add("Its root - the root of '" + data.baseProjectName //$NON-NLS-1$
            + "' adopted the way EDT's new-extension wizard builds it - was handed to that create; nothing " //$NON-NLS-1$
            + "checked that EDT attached it, so this answer reports none of the root's values (name, prefix, " //$NON-NLS-1$
            + "purpose, scriptVariant, compatibilityMode) as applied."); //$NON-NLS-1$
        addNotes(parts, facts, COMMAND_INTERFACE_FILES + " were not created: this call writes nothing to a " //$NON-NLS-1$
            + "project EDT may still be creating, and no MCP tool attaches them to an existing extension. Do not " //$NON-NLS-1$
            + "remove or recreate the project while that create may still be running - a delete or a second create " //$NON-NLS-1$
            + "would race it: first make sure it has ended - restarting EDT ends it; until then EDT's Progress view " //$NON-NLS-1$
            + "lists it as '" + LOG_PREFIX + data.effectiveProjectName + "'. Then check list_projects before using " //$NON-NLS-1$ //$NON-NLS-2$
            + "the project; to have the command interface files, remove the project with delete_project and " //$NON-NLS-1$
            + "deleteContent=true - without it the project's directory stays on disk and create_project refuses it - " //$NON-NLS-1$
            + "and run create_project again."); //$NON-NLS-1$
        // Only what names the project: a value of a root nobody checked is not reported as applied.
        ToolResult slowResult = extensionProject(VAL_VERIFICATION_REQUIRED, data)
            .put(KEY_VERSION, data.version.toString())
            .put(KEY_STATE, VAL_ROOT_UNCONFIRMED)
            .put(KEY_CODESTYLE, slowPathCodestyleMap())
            .put(McpKeys.MESSAGE, String.join(" ", parts)); //$NON-NLS-1$
        // Mirror the normal success path: report when the synonym could not be applied.
        putSynonymNote(slowResult, data);
        return slowResult.toJson();
    }

    /**
     * Builds the normal success JSON response for a created extension project (read-only),
     * adding {@code synonymNote} when the synonym could not be applied. Package-visible for tests.
     */
    static String buildExtensionSuccessResponse(ExtensionResponseData data, ExtensionFacts facts,
        Map<String, Object> codestyleMap)
    {
        ToolResult result = extensionResult(data, facts)
            .put(KEY_STATE, extensionState(facts))
            .put(KEY_CODESTYLE, codestyleMap)
            .put(McpKeys.MESSAGE, extensionMessage(data, facts));
        putSynonymNote(result, data);
        return result.toJson();
    }

    /**
     * The fields every extension answer carries: what names the project - not the configuration name ({@code name}),
     * which outputSchema documents as the root's Configuration name, a value of the root.
     */
    private static ToolResult extensionProject(String action, ExtensionResponseData data)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, action)
            .put(McpKeys.PROJECT, data.effectiveProjectName)
            .put(KEY_PROJECT_KIND, KIND_EXTENSION)
            .put(KEY_BASE_PROJECT, data.baseProjectName);
    }

    /** The fields of an extension whose create ended: what names the project, then its root's values. */
    private static ToolResult extensionResult(ExtensionResponseData data, ExtensionFacts facts)
    {
        ToolResult result = extensionProject(VAL_CREATED, data)
            .put("name", data.configName) //$NON-NLS-1$
            .put(KEY_PREFIX, data.prefix)
            .put(KEY_PURPOSE, data.purpose.getLiteral())
            .put(KEY_SCRIPT_VARIANT, data.scriptVariant.getLiteral())
            .put(KEY_VERSION, data.version.toString());
        if (facts.mode != null)
        {
            result.put(KEY_COMPATIBILITY_MODE, facts.mode.getName());
        }
        return result;
    }

    private static void putSynonymNote(ToolResult result, ExtensionResponseData data)
    {
        if (!data.synonymApplied)
        {
            result.put(KEY_SYNONYM_NOTE,
                "Synonym was not applied: could not determine a language code from " //$NON-NLS-1$
                    + "the base configuration or the new extension configuration."); //$NON-NLS-1$
        }
    }

    /**
     * The state of a created extension: {@code ready} only when EDT reported its context started AND the
     * root file on disk showed the wizard's shape and this call's root - never on a root nobody saw, nor on one that
     * shows another name, prefix, purpose or mode, or lacks one; an earlier project's root is not left in the new
     * project's directory, as {@link #takenProjectRefusal} refuses an existing one. The command interface files do not
     * enter it: {@code ready} is documented as the root seen on disk, and {@link #commandInterfaceNote} names a command
     * interface file that was not. Package-visible for tests.
     *
     * @param facts what the post-create steps found
     * @return {@code ready} or {@code created}
     */
    static String extensionState(ExtensionFacts facts)
    {
        return facts.lifecycleStarted && facts.disk != null && facts.disk.confirmed ? VAL_READY : VAL_CREATED;
    }

    /**
     * The message of a created extension: how its root was built, what was not confirmed, and what EDT's
     * wizard does that this tool does not. Package-visible for tests.
     *
     * @param data the response data
     * @param facts what the post-create steps found
     * @return the message
     */
    static String extensionMessage(ExtensionResponseData data, ExtensionFacts facts)
    {
        List<String> parts = new ArrayList<>();
        parts.add("Extension project '" + data.effectiveProjectName + "' created and bound to '" //$NON-NLS-1$ //$NON-NLS-2$
            + data.baseProjectName + "'."); //$NON-NLS-1$
        parts.add("Its root was adopted from the root of '" + data.baseProjectName //$NON-NLS-1$
            + "' the way EDT's new-extension wizard builds it: " + mappingClause(data.version) //$NON-NLS-1$
            + ", and its compatibility mode is " + modeClause(facts) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        if (!facts.lifecycleStarted)
        {
            // No duration: 'not started' also covers a project with no DT project yet and a wait that threw.
            parts.add("EDT did not report the project's context as started; check list_projects before using it."); //$NON-NLS-1$
        }
        else if (facts.disk != null && !facts.disk.confirmed)
        {
            parts.add("Its root was not confirmed on disk: " + facts.disk.describe() + " after " //$NON-NLS-1$ //$NON-NLS-2$
                + seconds(facts.disk.budgetMs) + "s (EDT's export queue: " + facts.disk.exportState //$NON-NLS-1$
                + "). EDT may still be writing it; wait a few seconds before restarting EDT or running " //$NON-NLS-1$
                + "clean_project on this project, which would race that save."); //$NON-NLS-1$
        }
        addNotes(parts, facts, commandInterfaceNote(facts));
        return String.join(" ", parts); //$NON-NLS-1$
    }

    /**
     * The notes every extension answer carries when they apply, in a stable order; {@code commandInterfaces} is the
     * answer's note on the command interface files, or {@code null} for none.
     */
    private static void addNotes(List<String> parts, ExtensionFacts facts, String commandInterfaces)
    {
        if (facts.compatibilityNote != null)
        {
            parts.add(facts.compatibilityNote);
        }
        if (commandInterfaces != null)
        {
            parts.add(commandInterfaces);
        }
        String role = defaultRoleNote(facts.baseMode, facts.mode);
        if (role != null)
        {
            parts.add(role);
        }
    }

    /**
     * What the answer says of the two command interface files EDT's wizard creates, or {@code null} when there is
     * nothing to say. Their FILES are the evidence, as the root's file is the root's: a save submission EDT did not
     * accept is reported only while the files were not seen - by a look that did not run or that ended without them -
     * since EDT also saves a committed change on its own; files the look did see need no note. A file the look did not
     * see is named. None of this changes {@code state}, which is the root's. Package-visible for tests.
     *
     * @param facts what the post-create steps found
     * @return the note, or {@code null}
     */
    static String commandInterfaceNote(ExtensionFacts facts)
    {
        String files = COMMAND_INTERFACE_FILES;
        if (facts.commandInterfaceFailure != null)
        {
            return files + " were not created: " + withoutFinalPeriod(facts.commandInterfaceFailure) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<String> notSeen = facts.disk == null ? Collections.emptyList() : facts.disk.missingFiles;
        boolean refused = facts.commandInterfaceSaveFailure != null && (facts.disk == null || !notSeen.isEmpty());
        if (!refused && notSeen.isEmpty())
        {
            return null;
        }
        StringBuilder note = new StringBuilder(files);
        if (refused)
        {
            note.append(" were attached but not queued for saving: ") //$NON-NLS-1$
                .append(withoutFinalPeriod(facts.commandInterfaceSaveFailure));
            if (!notSeen.isEmpty())
            {
                note.append("; "); //$NON-NLS-1$
            }
        }
        else
        {
            note.append(" were attached, but "); //$NON-NLS-1$
        }
        if (!notSeen.isEmpty())
        {
            // "Not seen", not "not found": a file whose check did not complete in time - or failed - was not seen either.
            note.append(String.join(" and ", notSeen)).append(notSeen.size() == 1 ? " was" : " were") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" not seen on disk after ").append(seconds(facts.disk.budgetMs)).append('s'); //$NON-NLS-1$
        }
        note.append('.');
        if (!notSeen.isEmpty() && facts.disk.confirmed)
        {
            // The root's own note gives the same advice when the root was not confirmed either.
            note.append(" EDT may still be writing ").append(notSeen.size() == 1 ? "it" : "them") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append("; wait a few seconds before restarting EDT or running clean_project on this project, ") //$NON-NLS-1$
                .append("which would race that save."); //$NON-NLS-1$
        }
        return note.toString();
    }

    /**
     * What the root's mapping flag means at the extension's platform version, which is its base's: EDT's adopter
     * links an adopted object to its base object by ID only above 8.3.14
     * ({@link ExtensionRootBuilder#versionMapsByNameOnly}),
     * whatever the flag says.
     */
    private static String mappingClause(Version version)
    {
        if (ExtensionRootBuilder.versionMapsByNameOnly(version))
        {
            return "it keeps the flag that maps adopted objects to the base by IDs, but at platform version " //$NON-NLS-1$
                + version + " EDT maps them by name (by ID only above 8.3.14)"; //$NON-NLS-1$
        }
        return "it keeps mapping adopted objects to the base by IDs"; //$NON-NLS-1$
    }

    /** Names the effective mode and, when it was requested, the base's beside it. */
    private static String modeClause(ExtensionFacts facts)
    {
        String mode = facts.mode == null ? "unknown" : facts.mode.getName(); //$NON-NLS-1$
        if (facts.modeRequested && facts.mode != facts.baseMode && facts.baseMode != null)
        {
            return mode + " (requested; the base configuration's is " + facts.baseMode.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return mode + " (the base configuration's)"; //$NON-NLS-1$
    }

    /**
     * What EDT's wizard does beyond the root that this tool does not: it creates a default role, and no MCP tool
     * can add a role to the root's defaultRoles yet. The wizard decides on its root's extension mode, which in the
     * wizard is always the BASE's, so the note is given only over a base above 8.3.13 - never over a base at 8.3.13
     * or lower, whatever mode was requested - and only when the effective mode is above 8.3.13 too.
     * Package-visible for tests.
     *
     * @param baseMode the base configuration's compatibility mode
     * @param mode the effective extension compatibility mode
     * @return the note, or {@code null} when the wizard would create no default role for this base
     */
    static String defaultRoleNote(CompatibilityMode baseMode, CompatibilityMode mode)
    {
        if (!ExtensionRootBuilder.wizardCreatesDefaultRole(baseMode)
            || !ExtensionRootBuilder.wizardCreatesDefaultRole(mode))
        {
            return null;
        }
        return "EDT's wizard also creates a default role that gives users rights to the extension's new objects; " //$NON-NLS-1$
            + "this tool does not, and no MCP path can add a role to the extension root's defaultRoles yet - " //$NON-NLS-1$
            + "add it in EDT."; //$NON-NLS-1$
    }

    private static String withoutFinalPeriod(String text)
    {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text; //$NON-NLS-1$
    }

    private static long seconds(long ms)
    {
        return Math.max(1L, (ms + 999L) / 1000L);
    }

    /**
     * Runs the given project-creation Job, joins with {@link #CREATE_TIMEOUT_MS}, and
     * returns a {@link CreateJobResult} describing the outcome:
     * <ul>
     *   <li>{@link CreateStatus#OK} — job completed in time; caller checks {@code errorHolder}.</li>
     *   <li>{@link CreateStatus#SLOW_EXISTS} — timed out but project already exists; caller
     *       builds a kind-specific response without inferring any finer-grained mutation. The job
     *       had not ended (its cancellation is only requested), so the caller writes nothing
     *       to the project.</li>
     *   <li>{@link CreateStatus#TIMED_OUT} — timed out and project absent; {@code errorJson}
     *       is ready to return.</li>
     *   <li>{@link CreateStatus#INTERRUPTED} — job interrupted; {@code errorJson} is ready
     *       to return.</li>
     * </ul>
     */
    private CreateJobResult runCreateJob(Job createJob, String effectiveProjectName, String kindLabel)
    {
        createJob.setUser(false);
        McpJobs.schedule(createJob);

        try
        {
            createJob.join(CREATE_TIMEOUT_MS, new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new CreateJobResult(CreateStatus.INTERRUPTED,
                ToolResult.errorWithUnknownMutationOutcome(
                    kindLabel + " project creation was interrupted.").toJson()); //$NON-NLS-1$
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
                ToolResult.errorWithUnknownMutationOutcome(
                    kindLabel + " project creation timed out after " //$NON-NLS-1$
                    + (CREATE_TIMEOUT_MS / 1000) + " seconds. The project may still appear shortly; " //$NON-NLS-1$
                    + "if it does and is unwanted, remove it with delete_project.").toJson()); //$NON-NLS-1$
        }
        return new CreateJobResult(CreateStatus.OK, null);
    }

    /** Derives a failed create's strongest honest marker from the workspace after the Job. */
    private static String creationFailure(String projectName, String message)
    {
        return ProjectContext.of(projectName).exists()
            ? ToolResult.errorAfterMutation(message).toJson()
            : ToolResult.errorWithUnknownMutationOutcome(message).toJson();
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
        return waitForStarted(effectiveProjectName, newIProject, kindLabel) ? VAL_READY : VAL_CREATED;
    }

    /**
     * Waits for the newly created project to reach lifecycle STARTED - at once when {@code create()} already
     * started it, which a project manager does before it returns ({@link LifecycleWaiter}).
     *
     * @param effectiveProjectName the workspace project name
     * @param newIProject the {@link IProject} returned by the create call (may be null)
     * @param kindLabel kind label for log messages
     * @return whether STARTED was reached
     */
    private static boolean waitForStarted(String effectiveProjectName, IProject newIProject, String kindLabel)
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
                    return LifecycleWaiter.waitForProjectStarted(dtProject, STARTED_TIMEOUT_MS);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError(LOG_PREFIX + "lifecycle wait error (" + kindLabel + ")", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return false;
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
