/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to get list of metadata objects from 1C configuration.
 * Returns Name, Synonym, Type for each metadata object.
 */
public class GetMetadataObjectsTool implements IMcpTool
{
    public static final String NAME = "get_metadata_objects"; //$NON-NLS-1$
    
    /** Metadata type constants (all lowercase for case-insensitive matching) */
    private static final String TYPE_ALL = "all"; //$NON-NLS-1$
    private static final String TYPE_DOCUMENTS = "documents"; //$NON-NLS-1$
    private static final String TYPE_CATALOGS = "catalogs"; //$NON-NLS-1$
    private static final String TYPE_INFORMATION_REGISTERS = "informationregisters"; //$NON-NLS-1$
    private static final String TYPE_ACCUMULATION_REGISTERS = "accumulationregisters"; //$NON-NLS-1$
    private static final String TYPE_COMMON_MODULES = "commonmodules"; //$NON-NLS-1$
    private static final String TYPE_ENUMS = "enums"; //$NON-NLS-1$
    private static final String TYPE_CONSTANTS = "constants"; //$NON-NLS-1$
    private static final String TYPE_REPORTS = "reports"; //$NON-NLS-1$
    private static final String TYPE_DATA_PROCESSORS = "dataprocessors"; //$NON-NLS-1$
    private static final String TYPE_EXCHANGE_PLANS = "exchangeplans"; //$NON-NLS-1$
    private static final String TYPE_BUSINESS_PROCESSES = "businessprocesses"; //$NON-NLS-1$
    private static final String TYPE_TASKS = "tasks"; //$NON-NLS-1$
    private static final String TYPE_COMMON_ATTRIBUTES = "commonattributes"; //$NON-NLS-1$
    private static final String TYPE_EVENT_SUBSCRIPTIONS = "eventsubscriptions"; //$NON-NLS-1$
    private static final String TYPE_SCHEDULED_JOBS = "scheduledjobs"; //$NON-NLS-1$

    private static final String TYPE_XDTO_PACKAGES = "xdtopackages"; //$NON-NLS-1$

    /** The two categories only an EXTERNAL-OBJECTS project can answer (issue #309). */
    private static final String TYPE_EXTERNAL_DATA_PROCESSORS = "externaldataprocessors"; //$NON-NLS-1$
    private static final String TYPE_EXTERNAL_REPORTS = "externalreports"; //$NON-NLS-1$

    /** The English singular type token behind {@link #TYPE_EXTERNAL_DATA_PROCESSORS}. */
    private static final String TOKEN_EXTERNAL_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$
    /** The English singular type token behind {@link #TYPE_EXTERNAL_REPORTS}. */
    private static final String TOKEN_EXTERNAL_REPORT = "ExternalReport"; //$NON-NLS-1$

    /**
     * The category tokens this tool actually collects (lowercase). Used both as the
     * legacy vocabulary of {@code metadataType} and as the target of the type-name-token
     * normalization in {@link #normalizeMetadataType(String)}: {@code MetadataTypeUtils}
     * recognizes far more type names (e.g. Role, Subsystem) than this tool has collectors
     * for, so a resolved type name is only accepted when its category is a member of this
     * set.
     */
    private static final Set<String> SUPPORTED_CATEGORIES = new HashSet<>(Arrays.asList(
        TYPE_DOCUMENTS, TYPE_CATALOGS, TYPE_INFORMATION_REGISTERS, TYPE_ACCUMULATION_REGISTERS,
        TYPE_COMMON_MODULES, TYPE_ENUMS, TYPE_CONSTANTS, TYPE_REPORTS, TYPE_DATA_PROCESSORS,
        TYPE_EXCHANGE_PLANS, TYPE_BUSINESS_PROCESSES, TYPE_TASKS, TYPE_COMMON_ATTRIBUTES,
        TYPE_EVENT_SUBSCRIPTIONS, TYPE_SCHEDULED_JOBS, TYPE_XDTO_PACKAGES));

    private static final String LIMIT = "limit"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Discover metadata objects available in a 1C configuration. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_metadata_objects')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Type filter (case-insensitive), default 'all'. Accepts EITHER a category token - all, " + //$NON-NLS-1$
                "documents, catalogs, informationRegisters, accumulationRegisters, commonModules, enums, " + //$NON-NLS-1$
                "constants, reports, dataProcessors, exchangePlans, businessProcesses, tasks, " + //$NON-NLS-1$
                "commonAttributes, eventSubscriptions, scheduledJobs, xdtoPackages - OR a single " + //$NON-NLS-1$
                "standard metadata " + //$NON-NLS-1$
                "type name (the FQN token, English or its Russian equivalent, e.g. 'ScheduledJob', " + //$NON-NLS-1$
                "'Document'). Single value only - not an array. An unrecognized value returns an error " + //$NON-NLS-1$
                "listing the supported options. In an EXTERNAL-OBJECTS project the vocabulary is " + //$NON-NLS-1$
                "all / externalDataProcessors / externalReports instead - that project holds its " + //$NON-NLS-1$
                "own roots, not a configuration.") //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Case-insensitive substring matched against Name only (not Synonym)") //$NON-NLS-1$
            .integerProperty(LIMIT,
                "Max rows (default from preferences: 100, max 1000)") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "metadata-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "metadata-objects.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        // Validate required parameter
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        // Set defaults
        if (metadataType == null || metadataType.isEmpty())
        {
            metadataType = TYPE_ALL;
        }
        // Note: language will be resolved from configuration default if null/empty

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, LIMIT, 100);
        int limit = JsonUtils.extractIntArgument(params, LIMIT, defaultLimit);
        limit = Pagination.clampLimit(limit, 1000);

        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final String mdType = metadataType;
        final String filter = nameFilter;
        final int maxResults = limit;
        final String lang = language; // null means use config default
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getMetadataObjectsInternal(projectName, mdType, filter, maxResults, lang);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting metadata objects", e); //$NON-NLS-1$
                // NOT e.getMessage(): the failure actually seen here was a NullPointerException,
                // whose message is null, so the caller was handed "Unknown error" - a dead end for
                // anyone, and worse for an agent that cannot read the EDT log. PlatformFailures
                // walks the cause chain and any IStatus children for something that names the
                // failure, and falls back to the exception's own type when nothing else does.
                resultRef.set(ToolResult.error(
                    "Could not list metadata objects: " + PlatformFailures.describe(e) //$NON-NLS-1$
                    + ". If this followed a clean_project or a project reload, EDT may still be " //$NON-NLS-1$
                    + "restarting the project context - retry once it reports ready.").toJson()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getMetadataObjectsInternal(String projectName, String metadataType,
                                               String nameFilter, int limit, String language)
    {
        // Resolve the project and its configuration
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveMetadataRoot(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();
        MetadataScope scope = resolved.scope();

        // Determine language CODE for synonyms (the synonym map is keyed by code,
        // e.g. "ru"/"en", not by the Language object's name). May be null when the
        // project declares no languages; getSynonymForLanguage tolerates that.
        String effectiveLanguage = scope.resolveLanguageCode(language);

        // An EXTERNAL-OBJECTS project answers about its OWN roots. Its "configuration" is the
        // linked BASE one, so listing that here answered with a different project's objects
        // (issue #309): the external data processors / reports the caller asked for were absent
        // and unrelated configuration objects took their place.
        if (scope.isExternalObjects())
        {
            return externalObjectsOutput(projectName, scope, metadataType, nameFilter, limit,
                effectiveLanguage);
        }

        // Normalize metadataType to the internal category token: either it already IS
        // one (legacy vocabulary, back-compat), or it is a standard type-name token
        // (FQN form, English/Russian, singular/plural - e.g. "ScheduledJob") resolved
        // via the shared MetadataTypeUtils resolver. See normalizeMetadataType javadoc.
        String category = normalizeMetadataType(metadataType);
        if (category == null)
        {
            String standalone = standaloneTypeRefusal(scope, metadataType);
            if (standalone != null)
            {
                return standalone;
            }
            return ToolResult.error("Unknown metadata type: " + metadataType + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                   "Supported categories (case-insensitive): all, documents, catalogs, informationRegisters, " + //$NON-NLS-1$
                   "accumulationRegisters, commonModules, enums, constants, reports, dataProcessors, " + //$NON-NLS-1$
                   "exchangePlans, businessProcesses, tasks, commonAttributes, eventSubscriptions, " + //$NON-NLS-1$
                   "scheduledJobs, xdtoPackages. Also accepts a standard metadata type name (the FQN " + //$NON-NLS-1$
                   "token, English " + //$NON-NLS-1$
                   "or Russian, singular or plural, e.g. 'ScheduledJob', 'Document') for one of these " + //$NON-NLS-1$
                   "categories.").toJson(); //$NON-NLS-1$
        }

        // Collect metadata objects
        List<MetadataInfo> objects = new ArrayList<>();

        switch (category)
        {
            case TYPE_ALL:
                collectDocuments(config, objects, nameFilter);
                collectCatalogs(config, objects, nameFilter);
                collectInformationRegisters(config, objects, nameFilter);
                collectAccumulationRegisters(config, objects, nameFilter);
                collectCommonModules(config, objects, nameFilter);
                collectEnums(config, objects, nameFilter);
                collectConstants(config, objects, nameFilter);
                collectReports(config, objects, nameFilter);
                collectDataProcessors(config, objects, nameFilter);
                collectExchangePlans(config, objects, nameFilter);
                collectBusinessProcesses(config, objects, nameFilter);
                collectTasks(config, objects, nameFilter);
                collectCommonAttributes(config, objects, nameFilter);
                collectEventSubscriptions(config, objects, nameFilter);
                collectScheduledJobs(config, objects, nameFilter);
                collectXdtoPackages(config, objects, nameFilter);
                break;
            case TYPE_DOCUMENTS:
                collectDocuments(config, objects, nameFilter);
                break;
            case TYPE_CATALOGS:
                collectCatalogs(config, objects, nameFilter);
                break;
            case TYPE_INFORMATION_REGISTERS:
                collectInformationRegisters(config, objects, nameFilter);
                break;
            case TYPE_ACCUMULATION_REGISTERS:
                collectAccumulationRegisters(config, objects, nameFilter);
                break;
            case TYPE_COMMON_MODULES:
                collectCommonModules(config, objects, nameFilter);
                break;
            case TYPE_ENUMS:
                collectEnums(config, objects, nameFilter);
                break;
            case TYPE_CONSTANTS:
                collectConstants(config, objects, nameFilter);
                break;
            case TYPE_REPORTS:
                collectReports(config, objects, nameFilter);
                break;
            case TYPE_DATA_PROCESSORS:
                collectDataProcessors(config, objects, nameFilter);
                break;
            case TYPE_EXCHANGE_PLANS:
                collectExchangePlans(config, objects, nameFilter);
                break;
            case TYPE_BUSINESS_PROCESSES:
                collectBusinessProcesses(config, objects, nameFilter);
                break;
            case TYPE_TASKS:
                collectTasks(config, objects, nameFilter);
                break;
            case TYPE_COMMON_ATTRIBUTES:
                collectCommonAttributes(config, objects, nameFilter);
                break;
            case TYPE_EVENT_SUBSCRIPTIONS:
                collectEventSubscriptions(config, objects, nameFilter);
                break;
            case TYPE_SCHEDULED_JOBS:
                collectScheduledJobs(config, objects, nameFilter);
                break;
            case TYPE_XDTO_PACKAGES:
                collectXdtoPackages(config, objects, nameFilter);
                break;
            default:
                // Unreachable: normalizeMetadataType only ever returns TYPE_ALL or a
                // member of SUPPORTED_CATEGORIES, both fully covered above. Kept as a
                // defensive net against the two enumerations drifting apart.
                return ToolResult.error("Unknown metadata type: " + metadataType).toJson(); //$NON-NLS-1$
        }

        // An object's ORIGIN (core vs extension-adopted vs extension-own) is only
        // meaningful for an EXTENSION project, where adopted base objects are listed
        // alongside the extension's own. Resolve the project type once and surface an
        // Origin column only then; a base configuration keeps its original columns.
        boolean isExtensionProject = ExtensionOriginUtils.isExtensionProject(project);

        // Format output. Show the caller's ORIGINAL filter value in the "Filter:" line (what
        // they typed - a category token, a type name, whatever casing), not the internal
        // lowercased category token; the TYPE_ALL comparison in formatOutput is case-insensitive.
        return formatOutput(projectName, objects, limit, effectiveLanguage, metadataType,
            isExtensionProject, false);
    }

    /**
     * Lists the OWN root objects of an external-objects project: its external data processors
     * and reports, which are standalone BM top objects rather than entries in a Configuration
     * collection (issue #309).
     *
     * <p>A configuration category (catalogs, documents, ...) asked of such a project is refused
     * with the reason, not answered with the linked base configuration's objects: the caller
     * asked about THIS project, and quietly answering about another one is what made the bug
     * invisible.</p>
     *
     * @param projectName the project the caller named
     * @param scope the external-objects resolution root
     * @param metadataType the caller's raw type filter
     * @param nameFilter the caller's case-insensitive Name substring, or {@code null}
     * @param limit max rows
     * @param language the resolved synonym language code (may be {@code null})
     * @return the Markdown listing, or a JSON error for a type this project cannot hold
     */
    private String externalObjectsOutput(String projectName, MetadataScope scope, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String metadataType, String nameFilter, int limit, String language)
    {
        String category = normalizeExternalMetadataType(metadataType);
        if (category == null)
        {
            return ToolResult.error("Unknown metadata type for an external-objects project: " //$NON-NLS-1$
                + metadataType + ". Supported (case-insensitive): all, externalDataProcessors, " //$NON-NLS-1$
                + "externalReports - or the type name itself (ExternalDataProcessor / " //$NON-NLS-1$
                + "ExternalReport, English or Russian)." + scope.addressingHint(metadataType + ".x")) //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        List<MetadataInfo> objects = new ArrayList<>();
        if (TYPE_ALL.equals(category) || TYPE_EXTERNAL_DATA_PROCESSORS.equals(category))
        {
            collectExternalObjects(scope, TOKEN_EXTERNAL_DATA_PROCESSOR, objects, nameFilter);
        }
        if (TYPE_ALL.equals(category) || TYPE_EXTERNAL_REPORTS.equals(category))
        {
            collectExternalObjects(scope, TOKEN_EXTERNAL_REPORT, objects, nameFilter);
        }
        // An external-objects project holds no adopted objects, so it has no Origin column.
        return formatOutput(projectName, objects, limit, language, metadataType, false, true);
    }

    /**
     * The {@link #normalizeMetadataType} twin for an external-objects project: only {@code all}
     * and the two external categories exist there. Accepts the category token and the bilingual
     * type name alike, through the SAME shared resolver.
     *
     * Package-private so it can be unit-tested directly: like {@link #normalizeMetadataType} it
     * touches neither the workbench nor a live model.
     *
     * @param metadataType raw filter value as supplied by the caller
     * @return {@link #TYPE_ALL} / {@link #TYPE_EXTERNAL_DATA_PROCESSORS} /
     *     {@link #TYPE_EXTERNAL_REPORTS}, or {@code null} if not recognized here
     */
    String normalizeExternalMetadataType(String metadataType)
    {
        if (metadataType == null || metadataType.isEmpty())
        {
            return null;
        }
        String lower = metadataType.toLowerCase();
        if (TYPE_ALL.equals(lower) || TYPE_EXTERNAL_DATA_PROCESSORS.equals(lower)
            || TYPE_EXTERNAL_REPORTS.equals(lower))
        {
            return lower;
        }
        MetadataTypeUtils.MetadataTypeInfo info = MetadataTypeUtils.resolve(metadataType);
        if (info == null)
        {
            return null;
        }
        if (TOKEN_EXTERNAL_DATA_PROCESSOR.equals(info.getEnglishSingular()))
        {
            return TYPE_EXTERNAL_DATA_PROCESSORS;
        }
        if (TOKEN_EXTERNAL_REPORT.equals(info.getEnglishSingular()))
        {
            return TYPE_EXTERNAL_REPORTS;
        }
        return null;
    }

    /**
     * Appends the external objects of one TYPE, honouring the Name substring filter.
     */
    private void collectExternalObjects(MetadataScope scope, String typeToken,
        List<MetadataInfo> objects, String filter)
    {
        List<? extends MdObject> found = scope.objects(typeToken);
        if (found == null)
        {
            return;
        }
        for (MdObject object : found)
        {
            if (!matchesFilter(object.getName(), filter))
            {
                continue;
            }
            MetadataInfo info = createMetadataInfo(object, typeToken);
            // An external data processor / report carries an object module and no manager one.
            info.hasObjectModule = hasModule(externalObjectModule(object));
            objects.add(info);
        }
    }

    /** The object module of an external data processor / report, or {@code null}. */
    private static Module externalObjectModule(MdObject object)
    {
        if (object instanceof ExternalDataProcessor)
        {
            return ((ExternalDataProcessor)object).getObjectModule();
        }
        if (object instanceof ExternalReport)
        {
            return ((ExternalReport)object).getObjectModule();
        }
        return null;
    }

    /**
     * The refusal for an external-objects TYPE asked of a project that is not one - the mirror of
     * the check {@link #externalObjectsOutput} makes in the other direction (issue #309).
     *
     * @param scope the project's resolution root
     * @param metadataType the caller's raw type filter
     * @return the ready JSON error, or {@code null} when the value is not a standalone type
     */
    private static String standaloneTypeRefusal(MetadataScope scope, String metadataType)
    {
        MetadataTypeUtils.MetadataTypeInfo info = MetadataTypeUtils.resolve(metadataType);
        if (info == null || !info.isStandalone())
        {
            return null;
        }
        return ToolResult.error("Metadata type '" + info.getEnglishSingular() //$NON-NLS-1$
            + "' is not part of a configuration." //$NON-NLS-1$
            + scope.addressingHint(info.getEnglishSingular() + ".x")).toJson(); //$NON-NLS-1$
    }

    /**
     * Normalizes the {@code metadataType} filter value to the internal category token
     * used by the collection switch above. Accepted forms, checked in this order:
     * <ol>
     *   <li>{@code "all"} (special, always wins);</li>
     *   <li>an existing category token ({@code documents}, {@code catalogs}, ...,
     *       {@code scheduledJobs}), case-insensitive - checked BEFORE type-name
     *       resolution so a category token can never be shadowed by it;</li>
     *   <li>a standard metadata type name in any form {@code MetadataTypeUtils}
     *       recognizes (English/Russian, singular/plural, e.g. "ScheduledJob",
     *       "РегламентноеЗадание"),
     *       mapped to its category token via {@link MetadataTypeUtils.MetadataTypeInfo#getConfigReferenceName()}
     *       IF that category is one this tool actually collects
     *       ({@link #SUPPORTED_CATEGORIES}) - a type MetadataTypeUtils recognizes but
     *       this tool has no collector for (e.g. Role, Subsystem) falls
     *       through to "not recognized" here, same as an unknown value.</li>
     * </ol>
     * This reuses the shared bilingual resolver (do NOT hand-roll type resolution,
     * see CLAUDE.md #4) rather than adding a second Russian-token table.
     *
     * Package-private (not {@code private}) so it can be unit-tested directly: unlike
     * the rest of {@code getMetadataObjectsInternal}, this method touches neither the
     * workbench nor a live {@code Configuration} - it is pure string/lookup logic
     * against {@code MetadataTypeUtils}, which {@code MetadataTypeUtilsTest} already
     * proves runs standalone.
     *
     * @param metadataType raw filter value as supplied by the caller
     * @return the category token to switch on ({@link #TYPE_ALL} or a member of
     *         {@link #SUPPORTED_CATEGORIES}), or {@code null} if not recognized in any form
     */
    String normalizeMetadataType(String metadataType)
    {
        if (metadataType == null || metadataType.isEmpty())
        {
            return null;
        }

        String lower = metadataType.toLowerCase();
        if (TYPE_ALL.equals(lower) || SUPPORTED_CATEGORIES.contains(lower))
        {
            return lower;
        }

        // Not a category token - try resolving it as a standard metadata type name
        // (FQN token, English or Russian, singular or plural) via the shared resolver.
        MetadataTypeUtils.MetadataTypeInfo typeInfo = MetadataTypeUtils.resolve(metadataType);
        if (typeInfo != null)
        {
            String configReferenceName = typeInfo.getConfigReferenceName();
            if (configReferenceName != null)
            {
                String category = configReferenceName.toLowerCase();
                if (SUPPORTED_CATEGORIES.contains(category))
                {
                    return category;
                }
            }
        }

        return null;
    }

    /**
     * Formats the output as markdown.
     */
    private String formatOutput(String projectName, List<MetadataInfo> objects, int limit, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                                 String language, String metadataType, boolean isExtensionProject,
                                 boolean externalObjects)
    {
        StringBuilder sb = new StringBuilder();
        
        // The heading names WHAT was listed: an external-objects project holds no configuration,
        // and calling its roots "Configuration Metadata" is the same confusion issue #309 was.
        sb.append(externalObjects ? "## External Objects: " : "## Configuration Metadata: ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(projectName).append("\n\n"); //$NON-NLS-1$
        
        int total = objects.size();
        int shown = Math.min(total, limit);
        
        if (!TYPE_ALL.equalsIgnoreCase(metadataType))
        {
            sb.append("**Filter:** ").append(metadataType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("**Total:** ").append(total).append(" objects"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(shown, total));
        sb.append("\n\n"); //$NON-NLS-1$
        
        if (objects.isEmpty())
        {
            sb.append("No metadata objects found.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        
        // Table header. Cells are escaped by MarkdownUtils.tableRow, so a
        // synonym or comment containing '|' cannot break the table. The Origin
        // column is appended only for an extension project (see isExtensionProject).
        if (isExtensionProject)
        {
            sb.append(MarkdownUtils.tableHeader(
                "Name", "Synonym", "Comment", "Type", "ObjectModule", "ManagerModule", "Origin")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        }
        else
        {
            sb.append(MarkdownUtils.tableHeader(
                "Name", "Synonym", "Comment", "Type", "ObjectModule", "ManagerModule")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }

        // Table rows
        int count = 0;
        for (MetadataInfo info : objects)
        {
            if (count >= limit)
            {
                break;
            }

            sb.append(formatObjectRow(info, language, isExtensionProject));

            count++;
        }

        return sb.toString();
    }

    /**
     * Formats a single metadata object as one markdown table row.
     */
    private String formatObjectRow(MetadataInfo info, String language, boolean isExtensionProject)
    {
        // Get synonym for the specified language
        String displaySynonym = getSynonymForLanguage(info, language);
        String displayComment = info.comment != null ? info.comment : ""; //$NON-NLS-1$
        String objectModule = info.hasObjectModule ? "Yes" : "-"; //$NON-NLS-1$ //$NON-NLS-2$
        String managerModule = info.hasManagerModule ? "Yes" : "-"; //$NON-NLS-1$ //$NON-NLS-2$

        if (isExtensionProject)
        {
            return MarkdownUtils.tableRow(
                info.name,
                displaySynonym,
                displayComment,
                info.type,
                objectModule,
                managerModule,
                ExtensionOriginUtils.originLabel(info.belonging, true));
        }
        return MarkdownUtils.tableRow(
            info.name,
            displaySynonym,
            displayComment,
            info.type,
            objectModule,
            managerModule);
    }
    
    // ========== Collection methods ==========
    
    private void collectDocuments(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (Document doc : config.getDocuments())
        {
            if (matchesFilter(doc.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(doc, "Document"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(doc.getObjectModule());
                info.hasManagerModule = hasModule(doc.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectCatalogs(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (Catalog cat : config.getCatalogs())
        {
            if (matchesFilter(cat.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(cat, "Catalog"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(cat.getObjectModule());
                info.hasManagerModule = hasModule(cat.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectInformationRegisters(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (InformationRegister reg : config.getInformationRegisters())
        {
            if (matchesFilter(reg.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(reg, "InformationRegister"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(reg.getRecordSetModule());
                info.hasManagerModule = hasModule(reg.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectAccumulationRegisters(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (AccumulationRegister reg : config.getAccumulationRegisters())
        {
            if (matchesFilter(reg.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(reg, "AccumulationRegister"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(reg.getRecordSetModule());
                info.hasManagerModule = hasModule(reg.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectCommonModules(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (CommonModule mod : config.getCommonModules())
        {
            if (matchesFilter(mod.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(mod, "CommonModule"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(mod.getModule());
                info.hasManagerModule = false;
                objects.add(info);
            }
        }
    }
    
    private void collectEnums(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (com._1c.g5.v8.dt.metadata.mdclass.Enum en : config.getEnums())
        {
            if (matchesFilter(en.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(en, "Enum"); //$NON-NLS-1$
                info.hasObjectModule = false;
                info.hasManagerModule = hasModule(en.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectConstants(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (Constant con : config.getConstants())
        {
            if (matchesFilter(con.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(con, "Constant"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(con.getValueManagerModule());
                info.hasManagerModule = hasModule(con.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectReports(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (Report rep : config.getReports())
        {
            if (matchesFilter(rep.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(rep, "Report"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(rep.getObjectModule());
                info.hasManagerModule = hasModule(rep.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectDataProcessors(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (DataProcessor dp : config.getDataProcessors())
        {
            if (matchesFilter(dp.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(dp, "DataProcessor"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(dp.getObjectModule());
                info.hasManagerModule = hasModule(dp.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectExchangePlans(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (ExchangePlan ep : config.getExchangePlans())
        {
            if (matchesFilter(ep.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(ep, "ExchangePlan"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(ep.getObjectModule());
                info.hasManagerModule = hasModule(ep.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectBusinessProcesses(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (BusinessProcess bp : config.getBusinessProcesses())
        {
            if (matchesFilter(bp.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(bp, "BusinessProcess"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(bp.getObjectModule());
                info.hasManagerModule = hasModule(bp.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectTasks(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (Task task : config.getTasks())
        {
            if (matchesFilter(task.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(task, "Task"); //$NON-NLS-1$
                info.hasObjectModule = hasModule(task.getObjectModule());
                info.hasManagerModule = hasModule(task.getManagerModule());
                objects.add(info);
            }
        }
    }
    
    private void collectCommonAttributes(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (CommonAttribute attr : config.getCommonAttributes())
        {
            if (matchesFilter(attr.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(attr, "CommonAttribute"); //$NON-NLS-1$
                info.hasObjectModule = false;
                info.hasManagerModule = false;
                objects.add(info);
            }
        }
    }
    
    /**
     * XDTO packages. They carry no modules, and their MEMBERS (ObjectType / Property) are addressed
     * through the package FQN - which is exactly what a caller needs before create_metadata /
     * modify_metadata / validate_xdto_package on 'XDTOPackage.&lt;Name&gt;...', and had no listing
     * route at all before.
     */
    private void collectXdtoPackages(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (XDTOPackage pkg : config.getXDTOPackages())
        {
            if (matchesFilter(pkg.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(pkg, "XDTOPackage"); //$NON-NLS-1$
                info.hasObjectModule = false;
                info.hasManagerModule = false;
                objects.add(info);
            }
        }
    }

    private void collectEventSubscriptions(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (EventSubscription sub : config.getEventSubscriptions())
        {
            if (matchesFilter(sub.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(sub, "EventSubscription"); //$NON-NLS-1$
                info.hasObjectModule = false;
                info.hasManagerModule = false;
                objects.add(info);
            }
        }
    }
    
    private void collectScheduledJobs(Configuration config, List<MetadataInfo> objects, String filter)
    {
        for (ScheduledJob job : config.getScheduledJobs())
        {
            if (matchesFilter(job.getName(), filter))
            {
                MetadataInfo info = createMetadataInfo(job, "ScheduledJob"); //$NON-NLS-1$
                info.hasObjectModule = false;
                info.hasManagerModule = false;
                objects.add(info);
            }
        }
    }
    
    // ========== Helper methods ==========
    
    private MetadataInfo createMetadataInfo(MdObject mdObject, String type)
    {
        MetadataInfo info = new MetadataInfo();
        info.name = mdObject.getName();
        info.type = type;
        info.comment = mdObject.getComment();
        // ORIGIN discriminator: NATIVE vs ADOPTED. Only meaningful when the owning
        // project is an extension; resolved into a label at format time.
        info.belonging = mdObject.getObjectBelonging();
        
        // Get synonyms - getSynonym() returns EMap<String, String> directly
        EMap<String, String> synonym = mdObject.getSynonym();
        if (synonym != null)
        {
            // Copy all language entries
            for (java.util.Map.Entry<String, String> entry : synonym.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null)
                {
                    info.synonyms.put(entry.getKey(), entry.getValue());
                }
            }
        }
        
        return info;
    }
    
    private boolean matchesFilter(String name, String filter)
    {
        if (filter == null || filter.isEmpty())
        {
            return true;
        }
        return name != null && name.toLowerCase().contains(filter.toLowerCase());
    }
    
    private boolean hasModule(Module module)
    {
        return module != null;
    }
    
    /**
     * Gets synonym for the specified language with fallback.
     */
    private String getSynonymForLanguage(MetadataInfo info, String language)
    {
        // info.synonyms is keyed by language CODE; delegate to the shared resolver.
        return MetadataLanguageUtils.getSynonymForLanguage(info.synonyms, language);
    }
    
    /**
     * Holds metadata object information.
     */
    private static class MetadataInfo
    {
        String name;
        java.util.Map<String, String> synonyms = new java.util.HashMap<>();
        String comment;
        String type;
        boolean hasObjectModule;
        boolean hasManagerModule;
        ObjectBelonging belonging;
    }
}
