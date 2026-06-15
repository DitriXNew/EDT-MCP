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
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
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
import com.ditrix.edt.mcp.server.utils.Pagination;
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

    private static final String LIMIT = "limit"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get a flat list of 1C configuration metadata objects (Name, Synonym, Comment, Type, " + //$NON-NLS-1$
               "ObjectModule, ManagerModule) as a Markdown table. " + //$NON-NLS-1$
               "Use it to discover what objects exist; filter by metadataType and/or nameFilter (Name only). " + //$NON-NLS-1$
               "Use this to list objects; for the full properties of one named object use get_metadata_details. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_metadata_objects')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Type filter (case-insensitive), default 'all'. One of: all, documents, catalogs, " + //$NON-NLS-1$
                "informationRegisters, accumulationRegisters, commonModules, enums, constants, reports, " + //$NON-NLS-1$
                "dataProcessors, exchangePlans, businessProcesses, tasks, commonAttributes, " + //$NON-NLS-1$
                "eventSubscriptions, scheduledJobs") //$NON-NLS-1$
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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
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
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();

        // Determine language CODE for synonyms (the synonym map is keyed by code,
        // e.g. "ru"/"en", not by the Language object's name). May be null when the
        // configuration has no languages; getSynonymForLanguage tolerates that.
        String effectiveLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
        
        // Collect metadata objects
        List<MetadataInfo> objects = new ArrayList<>();
        
        switch (metadataType.toLowerCase())
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
            default:
                return ToolResult.error("Unknown metadata type: " + metadataType + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                       "Supported (case-insensitive): all, documents, catalogs, informationRegisters, accumulationRegisters, " + //$NON-NLS-1$
                       "commonModules, enums, constants, reports, dataProcessors, exchangePlans, " + //$NON-NLS-1$
                       "businessProcesses, tasks, commonAttributes, eventSubscriptions, scheduledJobs").toJson(); //$NON-NLS-1$
        }
        
        // An object's ORIGIN (core vs extension-adopted vs extension-own) is only
        // meaningful for an EXTENSION project, where adopted base objects are listed
        // alongside the extension's own. Resolve the project type once and surface an
        // Origin column only then; a base configuration keeps its original columns.
        boolean isExtensionProject = ExtensionOriginUtils.isExtensionProject(project);

        // Format output
        return formatOutput(projectName, objects, limit, effectiveLanguage, metadataType, isExtensionProject);
    }

    /**
     * Formats the output as markdown.
     */
    private String formatOutput(String projectName, List<MetadataInfo> objects, int limit,
                                 String language, String metadataType, boolean isExtensionProject)
    {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## Configuration Metadata: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        
        int total = objects.size();
        int shown = Math.min(total, limit);
        
        if (!TYPE_ALL.equals(metadataType))
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

            // Get synonym for the specified language
            String displaySynonym = getSynonymForLanguage(info, language);
            String displayComment = info.comment != null ? info.comment : ""; //$NON-NLS-1$
            String objectModule = info.hasObjectModule ? "Yes" : "-"; //$NON-NLS-1$ //$NON-NLS-2$
            String managerModule = info.hasManagerModule ? "Yes" : "-"; //$NON-NLS-1$ //$NON-NLS-2$

            if (isExtensionProject)
            {
                sb.append(MarkdownUtils.tableRow(
                    info.name,
                    displaySynonym,
                    displayComment,
                    info.type,
                    objectModule,
                    managerModule,
                    ExtensionOriginUtils.originLabel(info.belonging, true)));
            }
            else
            {
                sb.append(MarkdownUtils.tableRow(
                    info.name,
                    displaySynonym,
                    displayComment,
                    info.type,
                    objectModule,
                    managerModule));
            }

            count++;
        }

        return sb.toString();
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
