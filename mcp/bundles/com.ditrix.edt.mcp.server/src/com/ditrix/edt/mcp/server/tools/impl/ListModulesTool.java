/**
 * Copyright (c) 2026 Diversus
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import com._1c.g5.v8.dt.metadata.mdclass.WebService;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to list all BSL modules in a project or for a specific metadata object.
 * Returns module paths with their types and parent objects.
 */
public class ListModulesTool implements IMcpTool
{
    public static final String NAME = "list_modules"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List all BSL modules in an EDT project. " + //$NON-NLS-1$
               "Can filter by metadata type (documents, catalogs, commonModules, etc.) " + //$NON-NLS-1$
               "or by specific object name. Returns module path, type, and parent object."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Filter by type: 'all', 'documents', 'catalogs', 'commonModules', " + //$NON-NLS-1$
                "'informationRegisters', 'accumulationRegisters', 'reports', 'dataProcessors', " + //$NON-NLS-1$
                "'exchangePlans', 'businessProcesses', 'tasks', 'constants', " + //$NON-NLS-1$
                "'commonCommands', 'commonForms', 'webServices', 'httpServices'. Default: 'all'") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Name of specific metadata object to list modules for (e.g. 'Products')") //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Substring filter on module path (case-insensitive)") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results. Default: 200") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "modules-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "modules-list.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", 200); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }

        if (metadataType == null || metadataType.isEmpty())
        {
            metadataType = "all"; //$NON-NLS-1$
        }

        limit = Math.min(Math.max(1, limit), 1000);

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String mdType = metadataType;
        final String objName = objectName;
        final String filter = nameFilter;
        final int maxResults = limit;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = listModulesInternal(projectName, mdType, objName, filter, maxResults);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error listing modules", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    private String listModulesInternal(String projectName, String metadataType,
                                        String objectName, String nameFilter, int limit)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        List<ModuleInfo> modules = new ArrayList<>();
        String type = metadataType.toLowerCase();

        // For "all" mode, use filesystem scan — covers ALL metadata types
        if ("all".equals(type)) //$NON-NLS-1$
        {
            collectAllModulesFromFileSystem(project, modules, objectName, nameFilter);
            return formatOutput(projectName, modules, limit, metadataType);
        }

        // For specific type filters, use EDT API
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project: " + projectName; //$NON-NLS-1$
        }

        switch (type)
        {
            case "commonmodules": //$NON-NLS-1$
                collectCommonModules(project, config, modules, objectName, nameFilter);
                break;
            case "documents": //$NON-NLS-1$
                collectDocumentModules(project, config, modules, objectName, nameFilter);
                break;
            case "catalogs": //$NON-NLS-1$
                collectCatalogModules(project, config, modules, objectName, nameFilter);
                break;
            case "informationregisters": //$NON-NLS-1$
                collectInfoRegisterModules(project, config, modules, objectName, nameFilter);
                break;
            case "accumulationregisters": //$NON-NLS-1$
                collectAccumRegisterModules(project, config, modules, objectName, nameFilter);
                break;
            case "reports": //$NON-NLS-1$
                collectReportModules(project, config, modules, objectName, nameFilter);
                break;
            case "dataprocessors": //$NON-NLS-1$
                collectDataProcessorModules(project, config, modules, objectName, nameFilter);
                break;
            case "exchangeplans": //$NON-NLS-1$
                collectExchangePlanModules(project, config, modules, objectName, nameFilter);
                break;
            case "businessprocesses": //$NON-NLS-1$
                collectBusinessProcessModules(project, config, modules, objectName, nameFilter);
                break;
            case "tasks": //$NON-NLS-1$
                collectTaskModules(project, config, modules, objectName, nameFilter);
                break;
            case "constants": //$NON-NLS-1$
                collectConstantModules(project, config, modules, objectName, nameFilter);
                break;
            case "commoncommands": //$NON-NLS-1$
                collectCommonCommandModules(project, config, modules, objectName, nameFilter);
                break;
            case "commonforms": //$NON-NLS-1$
                collectCommonFormModules(project, config, modules, objectName, nameFilter);
                break;
            case "webservices": //$NON-NLS-1$
                collectWebServiceModules(project, config, modules, objectName, nameFilter);
                break;
            case "httpservices": //$NON-NLS-1$
                collectHTTPServiceModules(project, config, modules, objectName, nameFilter);
                break;
            default:
                return "Error: Unknown metadata type: " + metadataType + //$NON-NLS-1$
                       ". Supported: all, documents, catalogs, commonModules, informationRegisters, " + //$NON-NLS-1$
                       "accumulationRegisters, reports, dataProcessors, exchangePlans, " + //$NON-NLS-1$
                       "businessProcesses, tasks, constants, commonCommands, commonForms, " + //$NON-NLS-1$
                       "webServices, httpServices"; //$NON-NLS-1$
        }

        return formatOutput(projectName, modules, limit, metadataType);
    }

    // ========== Collection methods ==========

    private void collectCommonModules(IProject project, Configuration config,
                                       List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (CommonModule mod : config.getCommonModules())
        {
            if (objectName != null && !objectName.isEmpty()
                && !mod.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            String path = "CommonModules/" + mod.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(project, modules, path, "Module", "CommonModule", mod.getName(), nameFilter); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void collectDocumentModules(IProject project, Configuration config,
                                          List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (Document doc : config.getDocuments())
        {
            if (objectName != null && !objectName.isEmpty()
                && !doc.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "Documents/" + doc.getName(), //$NON-NLS-1$
                "Document", doc.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectCatalogModules(IProject project, Configuration config,
                                         List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (Catalog cat : config.getCatalogs())
        {
            if (objectName != null && !objectName.isEmpty()
                && !cat.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "Catalogs/" + cat.getName(), //$NON-NLS-1$
                "Catalog", cat.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectInfoRegisterModules(IProject project, Configuration config,
                                              List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (InformationRegister reg : config.getInformationRegisters())
        {
            if (objectName != null && !objectName.isEmpty()
                && !reg.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "InformationRegisters/" + reg.getName(), //$NON-NLS-1$
                "InformationRegister", reg.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectAccumRegisterModules(IProject project, Configuration config,
                                               List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (AccumulationRegister reg : config.getAccumulationRegisters())
        {
            if (objectName != null && !objectName.isEmpty()
                && !reg.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "AccumulationRegisters/" + reg.getName(), //$NON-NLS-1$
                "AccumulationRegister", reg.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectReportModules(IProject project, Configuration config,
                                        List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (Report rep : config.getReports())
        {
            if (objectName != null && !objectName.isEmpty()
                && !rep.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "Reports/" + rep.getName(), //$NON-NLS-1$
                "Report", rep.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectDataProcessorModules(IProject project, Configuration config,
                                               List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (DataProcessor dp : config.getDataProcessors())
        {
            if (objectName != null && !objectName.isEmpty()
                && !dp.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "DataProcessors/" + dp.getName(), //$NON-NLS-1$
                "DataProcessor", dp.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectExchangePlanModules(IProject project, Configuration config,
                                              List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (ExchangePlan ep : config.getExchangePlans())
        {
            if (objectName != null && !objectName.isEmpty()
                && !ep.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "ExchangePlans/" + ep.getName(), //$NON-NLS-1$
                "ExchangePlan", ep.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectBusinessProcessModules(IProject project, Configuration config,
                                                 List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (BusinessProcess bp : config.getBusinessProcesses())
        {
            if (objectName != null && !objectName.isEmpty()
                && !bp.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "BusinessProcesses/" + bp.getName(), //$NON-NLS-1$
                "BusinessProcess", bp.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectTaskModules(IProject project, Configuration config,
                                      List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (Task task : config.getTasks())
        {
            if (objectName != null && !objectName.isEmpty()
                && !task.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "Tasks/" + task.getName(), //$NON-NLS-1$
                "Task", task.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectConstantModules(IProject project, Configuration config,
                                          List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (Constant con : config.getConstants())
        {
            if (objectName != null && !objectName.isEmpty()
                && !con.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            collectAllBslModules(project, modules, "Constants/" + con.getName(), //$NON-NLS-1$
                "Constant", con.getName(), nameFilter); //$NON-NLS-1$
        }
    }

    private void collectCommonCommandModules(IProject project, Configuration config,
                                               List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (CommonCommand cmd : config.getCommonCommands())
        {
            if (objectName != null && !objectName.isEmpty()
                && !cmd.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            String path = "CommonCommands/" + cmd.getName() + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(project, modules, path, "CommandModule", "CommonCommand", cmd.getName(), nameFilter); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void collectCommonFormModules(IProject project, Configuration config,
                                            List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (CommonForm form : config.getCommonForms())
        {
            if (objectName != null && !objectName.isEmpty()
                && !form.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            String path = "CommonForms/" + form.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(project, modules, path, "FormModule", "CommonForm", form.getName(), nameFilter); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void collectWebServiceModules(IProject project, Configuration config,
                                            List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (WebService ws : config.getWebServices())
        {
            if (objectName != null && !objectName.isEmpty()
                && !ws.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            String path = "WebServices/" + ws.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(project, modules, path, "Module", "WebService", ws.getName(), nameFilter); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void collectHTTPServiceModules(IProject project, Configuration config,
                                             List<ModuleInfo> modules, String objectName, String nameFilter)
    {
        for (HTTPService hs : config.getHttpServices())
        {
            if (objectName != null && !objectName.isEmpty()
                && !hs.getName().equalsIgnoreCase(objectName))
            {
                continue;
            }
            String path = "HTTPServices/" + hs.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(project, modules, path, "Module", "HTTPService", hs.getName(), nameFilter); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ========== Filesystem scan for all modules ==========

    /**
     * Collects all BSL modules by recursively scanning the src/ directory.
     * Covers ALL metadata types without needing explicit API support for each.
     */
    private void collectAllModulesFromFileSystem(IProject project, List<ModuleInfo> modules,
                                                   String objectName, String nameFilter)
    {
        IContainer srcFolder = project.getFolder(new Path("src")); //$NON-NLS-1$
        if (!srcFolder.exists())
        {
            return;
        }

        try
        {
            scanBslFilesRecursive(srcFolder, modules, objectName, nameFilter);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning all BSL modules", e); //$NON-NLS-1$
        }
    }

    private void scanBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
                                         String objectName, String nameFilter)
        throws Exception
    {
        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                if (file.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String fullPath = file.getProjectRelativePath().toString();
                    String modulePath = fullPath.startsWith("src/") //$NON-NLS-1$
                        ? fullPath.substring(4) : fullPath;

                    String[] segments = modulePath.split("/"); //$NON-NLS-1$
                    if (segments.length < 2)
                    {
                        continue;
                    }

                    String collectionName = segments[0];
                    String parentName;
                    String parentType;

                    if ("Configuration".equals(collectionName)) //$NON-NLS-1$
                    {
                        parentName = "Configuration"; //$NON-NLS-1$
                        parentType = "Configuration"; //$NON-NLS-1$
                    }
                    else
                    {
                        parentName = segments[1];
                        parentType = mapCollectionToParentType(collectionName);
                    }

                    if (objectName != null && !objectName.isEmpty()
                        && !parentName.equalsIgnoreCase(objectName))
                    {
                        continue;
                    }

                    if (nameFilter != null && !nameFilter.isEmpty()
                        && !modulePath.toLowerCase().contains(nameFilter.toLowerCase()))
                    {
                        continue;
                    }

                    String basePath = "Configuration".equals(collectionName) //$NON-NLS-1$
                        ? collectionName : collectionName + "/" + parentName; //$NON-NLS-1$
                    String moduleType = determineModuleType(modulePath, basePath);

                    ModuleInfo info = new ModuleInfo();
                    info.modulePath = modulePath;
                    info.moduleType = moduleType;
                    info.parentType = parentType;
                    info.parentName = parentName;
                    modules.add(info);
                }
            }
            else if (member instanceof IContainer)
            {
                scanBslFilesRecursive((IContainer) member, modules, objectName, nameFilter);
            }
        }
    }

    private static String mapCollectionToParentType(String collectionName)
    {
        switch (collectionName)
        {
            case "Documents": //$NON-NLS-1$
                return "Document"; //$NON-NLS-1$
            case "Catalogs": //$NON-NLS-1$
                return "Catalog"; //$NON-NLS-1$
            case "CommonModules": //$NON-NLS-1$
                return "CommonModule"; //$NON-NLS-1$
            case "InformationRegisters": //$NON-NLS-1$
                return "InformationRegister"; //$NON-NLS-1$
            case "AccumulationRegisters": //$NON-NLS-1$
                return "AccumulationRegister"; //$NON-NLS-1$
            case "AccountingRegisters": //$NON-NLS-1$
                return "AccountingRegister"; //$NON-NLS-1$
            case "CalculationRegisters": //$NON-NLS-1$
                return "CalculationRegister"; //$NON-NLS-1$
            case "Reports": //$NON-NLS-1$
                return "Report"; //$NON-NLS-1$
            case "DataProcessors": //$NON-NLS-1$
                return "DataProcessor"; //$NON-NLS-1$
            case "ExchangePlans": //$NON-NLS-1$
                return "ExchangePlan"; //$NON-NLS-1$
            case "BusinessProcesses": //$NON-NLS-1$
                return "BusinessProcess"; //$NON-NLS-1$
            case "Tasks": //$NON-NLS-1$
                return "Task"; //$NON-NLS-1$
            case "Constants": //$NON-NLS-1$
                return "Constant"; //$NON-NLS-1$
            case "CommonCommands": //$NON-NLS-1$
                return "CommonCommand"; //$NON-NLS-1$
            case "CommonForms": //$NON-NLS-1$
                return "CommonForm"; //$NON-NLS-1$
            case "WebServices": //$NON-NLS-1$
                return "WebService"; //$NON-NLS-1$
            case "HTTPServices": //$NON-NLS-1$
                return "HTTPService"; //$NON-NLS-1$
            case "ChartsOfAccounts": //$NON-NLS-1$
                return "ChartOfAccounts"; //$NON-NLS-1$
            case "ChartsOfCharacteristicTypes": //$NON-NLS-1$
                return "ChartOfCharacteristicTypes"; //$NON-NLS-1$
            case "ChartsOfCalculationTypes": //$NON-NLS-1$
                return "ChartOfCalculationTypes"; //$NON-NLS-1$
            case "Enums": //$NON-NLS-1$
                return "Enum"; //$NON-NLS-1$
            case "DocumentJournals": //$NON-NLS-1$
                return "DocumentJournal"; //$NON-NLS-1$
            case "Sequences": //$NON-NLS-1$
                return "Sequence"; //$NON-NLS-1$
            case "SettingsStorages": //$NON-NLS-1$
                return "SettingsStorage"; //$NON-NLS-1$
            case "FilterCriteria": //$NON-NLS-1$
                return "FilterCriterion"; //$NON-NLS-1$
            case "ExternalDataSources": //$NON-NLS-1$
                return "ExternalDataSource"; //$NON-NLS-1$
            default:
                return collectionName;
        }
    }

    // ========== Recursive BSL scanning ==========

    /**
     * Recursively collects all .bsl modules under a metadata object directory.
     * Finds ObjectModule, ManagerModule, form modules, command modules, etc.
     */
    private void collectAllBslModules(IProject project, List<ModuleInfo> modules,
                                        String basePath, String parentType, String parentName, String nameFilter)
    {
        IContainer folder = project.getFolder(new Path("src").append(basePath)); //$NON-NLS-1$
        if (!folder.exists())
        {
            return;
        }

        try
        {
            collectBslFilesRecursive(folder, modules, basePath, parentType, parentName, nameFilter);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning for BSL modules in " + basePath, e); //$NON-NLS-1$
        }
    }

    private void collectBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
                                            String basePath, String parentType, String parentName,
                                            String nameFilter)
        throws Exception
    {
        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                if (file.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    // Build module path relative to src/
                    String fullPath = file.getProjectRelativePath().toString();
                    String modulePath = fullPath.startsWith("src/") ? fullPath.substring(4) : fullPath; //$NON-NLS-1$

                    if (nameFilter != null && !nameFilter.isEmpty()
                        && !modulePath.toLowerCase().contains(nameFilter.toLowerCase()))
                    {
                        continue;
                    }

                    String moduleType = determineModuleType(modulePath, basePath);

                    ModuleInfo info = new ModuleInfo();
                    info.modulePath = modulePath;
                    info.moduleType = moduleType;
                    info.parentType = parentType;
                    info.parentName = parentName;
                    modules.add(info);
                }
            }
            else if (member instanceof IContainer)
            {
                collectBslFilesRecursive((IContainer) member, modules,
                    basePath, parentType, parentName, nameFilter);
            }
        }
    }

    /**
     * Determines the module type from its path relative to the metadata object.
     * Examples:
     *   Documents/Order/ObjectModule.bsl → ObjectModule
     *   Documents/Order/ManagerModule.bsl → ManagerModule
     *   Documents/Order/Forms/FormName/Module.bsl → FormModule
     *   Documents/Order/Commands/CmdName/CommandModule.bsl → CommandModule
     */
    private String determineModuleType(String modulePath, String basePath)
    {
        String relativePath = modulePath.substring(basePath.length());
        if (relativePath.startsWith("/")) //$NON-NLS-1$
        {
            relativePath = relativePath.substring(1);
        }

        String fileName = relativePath.contains("/") //$NON-NLS-1$
            ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
            : relativePath;

        // Remove .bsl extension
        String baseName = fileName.endsWith(".bsl") //$NON-NLS-1$
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;

        // "Module.bsl" in Forms subfolder → FormModule
        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            if (relativePath.startsWith("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }

        return baseName;
    }

    // ========== Helper methods ==========

    private void addIfExists(IProject project, List<ModuleInfo> modules, String modulePath,
                              String moduleType, String parentType, String parentName, String nameFilter)
    {
        if (nameFilter != null && !nameFilter.isEmpty()
            && !modulePath.toLowerCase().contains(nameFilter.toLowerCase()))
        {
            return;
        }

        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (file.exists())
        {
            ModuleInfo info = new ModuleInfo();
            info.modulePath = modulePath;
            info.moduleType = moduleType;
            info.parentType = parentType;
            info.parentName = parentName;
            modules.add(info);
        }
    }

    private String formatOutput(String projectName, List<ModuleInfo> modules, int limit, String metadataType)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## BSL Modules: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (!"all".equalsIgnoreCase(metadataType)) //$NON-NLS-1$
        {
            sb.append("**Filter:** ").append(metadataType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        int total = modules.size();
        int shown = Math.min(total, limit);
        sb.append("**Total:** ").append(total).append(" modules"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown < total)
        {
            sb.append(" (showing ").append(shown).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (modules.isEmpty())
        {
            sb.append("No modules found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| Module Path | Module Type | Parent Type | Parent Name |\n"); //$NON-NLS-1$
        sb.append("|-------------|-------------|-------------|-------------|\n"); //$NON-NLS-1$

        int count = 0;
        for (ModuleInfo info : modules)
        {
            if (count >= limit)
            {
                break;
            }
            sb.append("| ").append(info.modulePath); //$NON-NLS-1$
            sb.append(" | ").append(info.moduleType); //$NON-NLS-1$
            sb.append(" | ").append(info.parentType); //$NON-NLS-1$
            sb.append(" | ").append(info.parentName); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$
            count++;
        }

        return sb.toString();
    }

    private static class ModuleInfo
    {
        String modulePath;
        String moduleType;
        String parentType;
        String parentName;
    }
}
