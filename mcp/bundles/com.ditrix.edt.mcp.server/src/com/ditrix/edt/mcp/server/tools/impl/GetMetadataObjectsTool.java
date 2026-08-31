/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
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
    
    /** Special metadata type that lists every configuration collection. */
    private static final String TYPE_ALL = "all"; //$NON-NLS-1$

    /** The two categories only an EXTERNAL-OBJECTS project can answer (issue #309). */
    private static final String TYPE_EXTERNAL_DATA_PROCESSORS = "externaldataprocessors"; //$NON-NLS-1$
    private static final String TYPE_EXTERNAL_REPORTS = "externalreports"; //$NON-NLS-1$

    /** The English singular type token behind {@link #TYPE_EXTERNAL_DATA_PROCESSORS}. */
    private static final String TOKEN_EXTERNAL_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$
    /** The English singular type token behind {@link #TYPE_EXTERNAL_REPORTS}. */
    private static final String TOKEN_EXTERNAL_REPORT = "ExternalReport"; //$NON-NLS-1$

    private static final String FEATURE_MANAGER_MODULE = "managerModule"; //$NON-NLS-1$
    private static final String FEATURE_OBJECT_MODULE = "objectModule"; //$NON-NLS-1$
    private static final String FEATURE_RECORD_SET_MODULE = "recordSetModule"; //$NON-NLS-1$
    private static final String FEATURE_VALUE_MANAGER_MODULE = "valueManagerModule"; //$NON-NLS-1$
    private static final String FEATURE_MODULE = "module"; //$NON-NLS-1$
    private static final String FEATURE_COMMAND_MODULE = "commandModule"; //$NON-NLS-1$

    private static final String LIMIT = "limit"; //$NON-NLS-1$

    private static final String NAME_FILTER = "nameFilter"; //$NON-NLS-1$
    private static final String TEXT_FILTER = "textFilter"; //$NON-NLS-1$

    /** The two deliberately separate filtering contracts exposed by this tool. */
    enum FilterMode
    {
        NAME,
        TEXT
    }

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
                "Type filter (case-insensitive), default 'all'. Accepts 'all' or any standard metadata " + //$NON-NLS-1$
                "type name (the FQN token). English resolves in singular OR plural ('ScheduledJob', " + //$NON-NLS-1$
                "'Role', 'httpServices'); Russian resolves in the spelling 1C registers for that type, " + //$NON-NLS-1$
                "which for most types is the singular alone ('\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A', " + //$NON-NLS-1$
                "'\u041E\u0431\u0449\u0430\u044F\u0424\u043E\u0440\u043C\u0430'). Single value only - not an array. " + //$NON-NLS-1$
                "In an EXTERNAL-OBJECTS project the vocabulary is " + //$NON-NLS-1$
                "all / externalDataProcessors / externalReports instead - that project holds its " + //$NON-NLS-1$
                "own roots, not a configuration.") //$NON-NLS-1$
            .stringProperty(NAME_FILTER,
                "Case-insensitive substring matched against Name only (not Synonym)") //$NON-NLS-1$
            .stringProperty(TEXT_FILTER,
                "Case-insensitive substring matched against Name or Synonym selected by language; " + //$NON-NLS-1$
                "mutually exclusive with nameFilter") //$NON-NLS-1$
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
        String nameFilter = JsonUtils.extractStringArgument(params, NAME_FILTER);
        String textFilter = JsonUtils.extractStringArgument(params, TEXT_FILTER);
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        // Validate required parameter
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        if (hasFilter(nameFilter) && hasFilter(textFilter))
        {
            return ToolResult.error("Use either nameFilter or textFilter, not both. Received " //$NON-NLS-1$
                + "nameFilter='" + nameFilter + "' and " //$NON-NLS-1$ //$NON-NLS-2$
                + "textFilter='" + textFilter + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + "nameFilter matches only the programmatic Name; textFilter matches Name or " //$NON-NLS-1$
                + "Synonym in the effective language selected by language.").toJson(); //$NON-NLS-1$
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
        final boolean useTextFilter = hasFilter(textFilter);
        final String filter = useTextFilter ? textFilter : nameFilter;
        final FilterMode filterMode = useTextFilter ? FilterMode.TEXT : FilterMode.NAME;
        final int maxResults = limit;
        final String lang = language; // null means use config default
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getMetadataObjectsInternal(projectName, mdType, filter, filterMode,
                    maxResults, lang);
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
        String filter, FilterMode filterMode, int limit, String language)
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
            return externalObjectsOutput(projectName, scope, metadataType, filter, filterMode,
                limit, effectiveLanguage);
        }

        // Normalize the caller's bilingual type spelling to the canonical English FQN token.
        String normalizedType = normalizeMetadataType(metadataType);
        if (normalizedType == null)
        {
            String standalone = standaloneTypeRefusal(scope, metadataType);
            if (standalone != null)
            {
                return standalone;
            }
            return unknownMetadataType(metadataType);
        }

        // Count every match for the Total line, but copy synonym maps only for rows that
        // can be rendered. Large configurations contain tens of thousands of top objects.
        List<MetadataInfo> objects = new ArrayList<>();
        int total = 0;
        if (TYPE_ALL.equals(normalizedType))
        {
            for (MetadataTypeUtils.MetadataTypeInfo info
                : MetadataTypeUtils.MetadataTypeInfo.values())
            {
                if (!info.isStandalone())
                {
                    total += collectMetadataObjects(config, info, objects, filter, filterMode,
                        limit, effectiveLanguage);
                }
            }
        }
        else
        {
            MetadataTypeUtils.MetadataTypeInfo info = MetadataTypeUtils.resolve(normalizedType);
            total = collectMetadataObjects(config, info, objects, filter, filterMode, limit,
                effectiveLanguage);
        }

        // An object's ORIGIN (core vs extension-adopted vs extension-own) is only
        // meaningful for an EXTENSION project, where adopted base objects are listed
        // alongside the extension's own. Resolve the project type once and surface an
        // Origin column only then; a base configuration keeps its original columns.
        boolean isExtensionProject = ExtensionOriginUtils.isExtensionProject(project);

        // Show the caller's original filter spelling in the Filter line.
        return formatOutput(projectName, objects, total, limit, effectiveLanguage, metadataType,
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
     * @param filter the caller's case-insensitive substring, or {@code null}
     * @param filterMode whether the substring matches Name only or Name / effective Synonym
     * @param limit max rows
     * @param language the resolved synonym language code (may be {@code null})
     * @return the Markdown listing, or a JSON error for a type this project cannot hold
     */
    private String externalObjectsOutput(String projectName, MetadataScope scope, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String metadataType, String filter, FilterMode filterMode, int limit, String language)
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
        int total = 0;
        if (TYPE_ALL.equals(category) || TYPE_EXTERNAL_DATA_PROCESSORS.equals(category))
        {
            total += collectExternalObjects(scope, TOKEN_EXTERNAL_DATA_PROCESSOR, objects,
                filter, filterMode, limit, language);
        }
        if (TYPE_ALL.equals(category) || TYPE_EXTERNAL_REPORTS.equals(category))
        {
            total += collectExternalObjects(scope, TOKEN_EXTERNAL_REPORT, objects, filter,
                filterMode, limit, language);
        }
        // An external-objects project holds no adopted objects, so it has no Origin column.
        return formatOutput(projectName, objects, total, limit, language, metadataType, false, true);
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
     * Appends the external objects of one TYPE, honouring the selected substring filter.
     */
    private int collectExternalObjects(MetadataScope scope, String typeToken,
        List<MetadataInfo> objects, String filter, FilterMode filterMode, int limit,
        String language)
    {
        List<? extends MdObject> found = scope.objects(typeToken);
        if (found == null)
        {
            return 0;
        }

        int total = 0;
        for (MdObject object : found)
        {
            if (!matches(object, filter, filterMode, language))
            {
                continue;
            }
            total++;
            if (objects.size() >= limit)
            {
                continue;
            }
            MetadataInfo info = createMetadataInfo(object, typeToken);
            // An external data processor / report carries an object module and no manager one.
            info.hasObjectModule = hasModule(externalObjectModule(object));
            objects.add(info);
        }
        return total;
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
     * Normalizes a configuration {@code metadataType} filter through the shared bilingual
     * resolver. {@code all} is handled first as the one special value; every configuration
     * member resolves to its canonical English singular FQN token. Standalone external objects
     * deliberately return {@code null} so {@link #standaloneTypeRefusal(MetadataScope, String)}
     * can explain the project boundary.
     *
     * <p>Package-private so the string-only normalization can be unit-tested without a live
     * workbench or configuration.</p>
     *
     * @param metadataType raw filter value as supplied by the caller
     * @return {@link #TYPE_ALL}, a canonical English singular type token, or {@code null}
     */
    String normalizeMetadataType(String metadataType)
    {
        if (TYPE_ALL.equalsIgnoreCase(metadataType))
        {
            return TYPE_ALL;
        }

        MetadataTypeUtils.MetadataTypeInfo typeInfo = MetadataTypeUtils.resolve(metadataType);
        if (typeInfo == null || typeInfo.isStandalone())
        {
            return null;
        }
        return typeInfo.getEnglishSingular();
    }

    /** Builds the complete configuration-type vocabulary only when an invalid value is reported. */
    private static String unknownMetadataType(String metadataType)
    {
        StringBuilder acceptedTypes = new StringBuilder();
        for (MetadataTypeUtils.MetadataTypeInfo info : MetadataTypeUtils.MetadataTypeInfo.values())
        {
            if (!info.isStandalone())
            {
                if (acceptedTypes.length() > 0)
                {
                    acceptedTypes.append(", "); //$NON-NLS-1$
                }
                acceptedTypes.append(info.getEnglishSingular());
            }
        }

        return ToolResult.error("Unknown metadata type: " + metadataType + ". Accepted values are " //$NON-NLS-1$ //$NON-NLS-2$
            + "'all' or any standard configuration metadata type name (case-insensitive). Each type " //$NON-NLS-1$
            + "below is accepted in English singular or plural, and in the Russian spelling 1C " //$NON-NLS-1$
            + "registers for it - for most types the singular alone. Configuration metadata types: " //$NON-NLS-1$
            + acceptedTypes
            + ". See get_tool_guide('get_metadata_objects') for the full parameter list.").toJson(); //$NON-NLS-1$
    }

    /**
     * Formats the output as markdown.
     */
    private String formatOutput(String projectName, List<MetadataInfo> objects, int total, int limit, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        String language, String metadataType, boolean isExtensionProject, boolean externalObjects)
    {
        StringBuilder sb = new StringBuilder();
        
        // The heading names WHAT was listed: an external-objects project holds no configuration,
        // and calling its roots "Configuration Metadata" is the same confusion issue #309 was.
        sb.append(externalObjects ? "## External Objects: " : "## Configuration Metadata: ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(projectName).append("\n\n"); //$NON-NLS-1$
        
        int shown = Math.min(total, limit);
        
        if (!TYPE_ALL.equalsIgnoreCase(metadataType))
        {
            sb.append("**Filter:** ").append(metadataType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("**Total:** ").append(total).append(" objects"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(shown, total));
        sb.append("\n\n"); //$NON-NLS-1$
        
        if (total == 0)
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
    
    /**
     * Counts one configuration collection and materializes only rows that can be displayed.
     * Package-private so pure model filtering can be unit-tested without the workbench.
     */
    int collectMetadataObjects(Configuration config, MetadataTypeUtils.MetadataTypeInfo typeInfo,
        List<MetadataInfo> objects, String filter, FilterMode filterMode, int limit,
        String language)
    {
        if (typeInfo == null)
        {
            return 0;
        }

        List<? extends MdObject> found = MetadataTypeUtils.getObjects(config,
            typeInfo.getEnglishSingular());
        if (found == null)
        {
            return 0;
        }

        int total = 0;
        for (MdObject object : found)
        {
            if (!matches(object, filter, filterMode, language))
            {
                continue;
            }

            total++;
            if (objects.size() >= limit)
            {
                continue;
            }

            MetadataInfo info = createMetadataInfo(object, typeInfo.getEnglishSingular());
            info.hasObjectModule = hasFeatureValue(object, FEATURE_OBJECT_MODULE)
                || hasFeatureValue(object, FEATURE_RECORD_SET_MODULE)
                || hasFeatureValue(object, FEATURE_VALUE_MANAGER_MODULE)
                || hasFeatureValue(object, FEATURE_MODULE)
                || hasFeatureValue(object, FEATURE_COMMAND_MODULE);
            info.hasManagerModule = hasFeatureValue(object, FEATURE_MANAGER_MODULE);
            objects.add(info);
        }
        return total;
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
    
    /** One filtering decision shared by configuration and external-object collection. */
    boolean matches(MdObject mdObject, String filter, FilterMode filterMode, String language)
    {
        if (filter == null || filter.isEmpty())
        {
            return true;
        }
        String lowerFilter = filter.toLowerCase();
        String name = mdObject == null ? null : mdObject.getName();
        if (name != null && name.toLowerCase().contains(lowerFilter))
        {
            return true;
        }
        if (mdObject == null || filterMode != FilterMode.TEXT)
        {
            return false;
        }
        EMap<String, String> synonyms = mdObject.getSynonym();
        String synonym = MetadataLanguageUtils.getSynonymForLanguage(
            synonyms == null ? null : synonyms.map(), language);
        return synonym.toLowerCase().contains(lowerFilter);
    }

    private static boolean hasFilter(String filter)
    {
        return filter != null && !filter.isEmpty();
    }
    
    private boolean hasModule(Module module)
    {
        return module != null;
    }

    /** Checks an inherited or directly declared module feature without type-specific casts. */
    private boolean hasFeatureValue(MdObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature != null && object.eGet(feature) != null;
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
    static class MetadataInfo
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
