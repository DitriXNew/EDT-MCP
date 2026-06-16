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
import java.util.function.Function;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to list all BSL modules in a project or for a specific metadata object.
 * Returns module paths with their types and parent objects.
 */
public class ListModulesTool implements IMcpTool
{
    public static final String NAME = "list_modules"; //$NON-NLS-1$

    private static final int MAX_RECURSION_DEPTH = 20;

    private static final String MODULE_BSL = "Module.bsl"; //$NON-NLS-1$
    private static final String MODULE = "Module"; //$NON-NLS-1$
    private static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List BSL modules in an EDT project as a table (module path, module type, parent type, parent name). " //$NON-NLS-1$
            + "Use it to discover module paths before reading or editing code; filter by metadataType, objectName or nameFilter. " //$NON-NLS-1$
            + "Use this to enumerate a project's modules; for the methods/regions inside one module use get_module_structure. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('list_modules')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .enumProperty("metadataType", //$NON-NLS-1$
                "Type filter, default 'all' (case-insensitive).", //$NON-NLS-1$
                "all", "documents", "catalogs", "commonModules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "informationRegisters", "accumulationRegisters", "reports", "dataProcessors", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "exchangePlans", "businessProcesses", "tasks", "constants", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "commonCommands", "commonForms", "webServices", "httpServices") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .stringProperty("objectName", //$NON-NLS-1$
                "Programmatic Name of one object to scope to, e.g. 'Products' (case-insensitive)") //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Case-insensitive substring matched against the module path") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max rows, default 200 (clamped to 1000)") //$NON-NLS-1$
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
            return "modules-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "modules-list.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, 200);

        if (metadataType == null || metadataType.isEmpty())
        {
            metadataType = "all"; //$NON-NLS-1$
        }

        limit = Pagination.clampLimit(limit, 1000);

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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    private String listModulesInternal(String projectName, String metadataType,
                                        String objectName, String nameFilter, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        List<ModuleInfo> modules = new ArrayList<>();
        String type = metadataType.toLowerCase();

        // For "all" mode, use filesystem scan — covers ALL metadata types
        if ("all".equals(type)) //$NON-NLS-1$
        {
            collectAllModulesFromFileSystem(project, modules, objectName, nameFilter);
            return formatOutput(projectName, modules, limit, metadataType);
        }

        // For specific type filters, use EDT API
        ProjectContext.ConfigurationResult resolved = ctx.resolveConfiguration();
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        Configuration config = resolved.configuration();

        CollectContext scanCtx = new CollectContext(project, modules, objectName, nameFilter);

        switch (type)
        {
            case "commonmodules": //$NON-NLS-1$
                collectSingleModuleType(scanCtx,
                    config.getCommonModules(), CommonModule::getName,
                    "CommonModules", MODULE_BSL, MODULE, "CommonModule"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "documents": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getDocuments(), Document::getName, "Documents", "Document"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "catalogs": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getCatalogs(), Catalog::getName, "Catalogs", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "informationregisters": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getInformationRegisters(), InformationRegister::getName,
                    "InformationRegisters", "InformationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "accumulationregisters": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getAccumulationRegisters(), AccumulationRegister::getName,
                    "AccumulationRegisters", "AccumulationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "reports": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getReports(), Report::getName, "Reports", "Report"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "dataprocessors": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getDataProcessors(), DataProcessor::getName,
                    "DataProcessors", "DataProcessor"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "exchangeplans": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getExchangePlans(), ExchangePlan::getName,
                    "ExchangePlans", "ExchangePlan"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "businessprocesses": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getBusinessProcesses(), BusinessProcess::getName,
                    "BusinessProcesses", "BusinessProcess"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "tasks": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getTasks(), Task::getName, "Tasks", "Task"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "constants": //$NON-NLS-1$
                collectMultiModuleType(scanCtx,
                    config.getConstants(), Constant::getName, "Constants", "Constant"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "commoncommands": //$NON-NLS-1$
                collectSingleModuleType(scanCtx,
                    config.getCommonCommands(), CommonCommand::getName,
                    "CommonCommands", "CommandModule.bsl", "CommandModule", "CommonCommand"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                break;
            case "commonforms": //$NON-NLS-1$
                collectSingleModuleType(scanCtx,
                    config.getCommonForms(), CommonForm::getName,
                    "CommonForms", MODULE_BSL, "FormModule", "CommonForm"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                break;
            case "webservices": //$NON-NLS-1$
                collectSingleModuleType(scanCtx,
                    config.getWebServices(), WebService::getName,
                    "WebServices", MODULE_BSL, MODULE, "WebService"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case "httpservices": //$NON-NLS-1$
                collectSingleModuleType(scanCtx,
                    config.getHttpServices(), HTTPService::getName,
                    "HTTPServices", MODULE_BSL, MODULE, "HTTPService"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            default:
                return ToolResult.error("Unknown metadata type: " + metadataType + //$NON-NLS-1$
                       ". Supported: all, documents, catalogs, commonModules, informationRegisters, " + //$NON-NLS-1$
                       "accumulationRegisters, reports, dataProcessors, exchangePlans, " + //$NON-NLS-1$
                       "businessProcesses, tasks, constants, commonCommands, commonForms, " + //$NON-NLS-1$
                       "webServices, httpServices").toJson(); //$NON-NLS-1$
        }

        return formatOutput(projectName, modules, limit, metadataType);
    }

    // ========== Generic collection methods ==========

    /**
     * Invariant inputs shared by every per-type collector call: the target
     * project, the accumulating module list, and the two optional filters
     * ({@code objectName} / {@code nameFilter}). Identical across all metadata
     * types, so bundled once per {@code execute} and threaded into the generic
     * collectors. Fields and read order match the previous flat parameters.
     */
    private static final class CollectContext
    {
        final IProject project;
        final List<ModuleInfo> modules;
        final String objectName;
        final String nameFilter;

        CollectContext(IProject project, List<ModuleInfo> modules, String objectName,
                String nameFilter)
        {
            this.project = project;
            this.modules = modules;
            this.objectName = objectName;
            this.nameFilter = nameFilter;
        }
    }

    /**
     * Generic collector for metadata types with a single known BSL module file.
     * Used for CommonModules, CommonCommands, CommonForms, WebServices, HTTPServices.
     *
     * @param ctx the invariant scan context (project, target list, filters)
     * @param objects the metadata objects to iterate
     * @param nameGetter function to extract the name from a metadata object
     * @param folderName the folder name in src/ (e.g. "CommonModules", "WebServices")
     * @param fileName the BSL file name (e.g. "Module.bsl", "CommandModule.bsl")
     * @param moduleType the module type label (e.g. "Module", "FormModule", "CommandModule")
     * @param parentType the parent type label (e.g. "CommonModule", "WebService")
     */
    private <T> void collectSingleModuleType(CollectContext ctx, Iterable<T> objects,
        Function<T, String> nameGetter, String folderName, String fileName, String moduleType,
        String parentType)
    {
        for (T obj : objects)
        {
            String name = nameGetter.apply(obj);
            if (ctx.objectName != null && !ctx.objectName.isEmpty()
                && !name.equalsIgnoreCase(ctx.objectName))
            {
                continue;
            }
            String path = folderName + "/" + name + "/" + fileName; //$NON-NLS-1$ //$NON-NLS-2$
            addIfExists(ctx.project, ctx.modules, path, moduleType, parentType, name,
                ctx.nameFilter);
        }
    }

    /**
     * Generic collector for metadata types with multiple possible BSL modules.
     * Recursively scans the object directory for all .bsl files.
     * Used for Documents, Catalogs, Registers, Reports, DataProcessors, etc.
     *
     * @param ctx the invariant scan context (project, target list, filters)
     * @param objects the metadata objects to iterate
     * @param nameGetter function to extract the name from a metadata object
     * @param folderName the folder name in src/ (e.g. "Documents", "Catalogs")
     * @param parentType the parent type label (e.g. "Document", "Catalog")
     */
    private <T> void collectMultiModuleType(CollectContext ctx, Iterable<T> objects,
        Function<T, String> nameGetter, String folderName, String parentType)
    {
        for (T obj : objects)
        {
            String name = nameGetter.apply(obj);
            if (ctx.objectName != null && !ctx.objectName.isEmpty()
                && !name.equalsIgnoreCase(ctx.objectName))
            {
                continue;
            }
            collectAllBslModules(ctx.project, ctx.modules, folderName + "/" + name, //$NON-NLS-1$
                parentType, name, ctx.nameFilter);
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
            scanBslFilesRecursive(srcFolder, modules, objectName, nameFilter, 0);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning all BSL modules", e); //$NON-NLS-1$
        }
    }

    private void scanBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
                                         String objectName, String nameFilter, int depth)
        throws Exception
    {
        if (depth > MAX_RECURSION_DEPTH)
        {
            return;
        }
        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                addScannedBslFile((IFile) member, modules, objectName, nameFilter);
            }
            else if (member instanceof IContainer)
            {
                scanBslFilesRecursive((IContainer) member, modules, objectName, nameFilter, depth + 1);
            }
        }
    }

    /**
     * Handles a single file member of the filesystem scan in {@link #scanBslFilesRecursive}:
     * a non-.bsl file, a file with too few path segments, or a file failing the
     * {@code objectName}/{@code nameFilter} is ignored; otherwise a {@link ModuleInfo} is
     * added to {@code modules}. Extracted verbatim from the scan's per-file branch; each
     * original loop {@code continue} (skip this file) becomes an early {@code return} here.
     *
     * @param file the file member to consider
     * @param modules target list for collected module info
     * @param objectName optional filter by parent object name (case-insensitive)
     * @param nameFilter optional filter by module path substring (case-insensitive)
     */
    private void addScannedBslFile(IFile file, List<ModuleInfo> modules,
                                    String objectName, String nameFilter)
    {
        if (!file.getName().endsWith(".bsl")) //$NON-NLS-1$
        {
            return;
        }
        String fullPath = file.getProjectRelativePath().toString();
        String modulePath = fullPath.startsWith("src/") //$NON-NLS-1$
            ? fullPath.substring(4) : fullPath;

        String[] segments = modulePath.split("/"); //$NON-NLS-1$
        if (segments.length < 2)
        {
            return;
        }

        String collectionName = segments[0];
        String parentName;
        String parentType;

        if (CONFIGURATION.equals(collectionName))
        {
            parentName = CONFIGURATION;
            parentType = CONFIGURATION;
        }
        else
        {
            parentName = segments[1];
            parentType = mapCollectionToParentType(collectionName);
        }

        if (objectName != null && !objectName.isEmpty()
            && !parentName.equalsIgnoreCase(objectName))
        {
            return;
        }

        if (nameFilter != null && !nameFilter.isEmpty()
            && !modulePath.toLowerCase().contains(nameFilter.toLowerCase()))
        {
            return;
        }

        String basePath = CONFIGURATION.equals(collectionName)
            ? collectionName : collectionName + "/" + parentName; //$NON-NLS-1$
        String moduleType = determineModuleType(modulePath, basePath);

        ModuleInfo info = new ModuleInfo();
        info.modulePath = modulePath;
        info.moduleType = moduleType;
        info.parentType = parentType;
        info.parentName = parentName;
        modules.add(info);
    }

    private static String mapCollectionToParentType(String collectionName)
    {
        String type = MetadataTypeUtils.getTypeByDirectoryName(collectionName);
        return type != null ? type : collectionName;
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
            collectBslFilesRecursive(folder, modules, basePath, parentType, parentName, nameFilter, 0);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning for BSL modules in " + basePath, e); //$NON-NLS-1$
        }
    }

    private void collectBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
                                            String basePath, String parentType, String parentName,
                                            String nameFilter, int depth)
        throws Exception
    {
        if (depth > MAX_RECURSION_DEPTH)
        {
            return;
        }
        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                addBslFileIfMatches((IFile) member, modules, basePath, parentType, parentName, nameFilter);
            }
            else if (member instanceof IContainer)
            {
                collectBslFilesRecursive((IContainer) member, modules,
                    basePath, parentType, parentName, nameFilter, depth + 1);
            }
        }
    }

    /**
     * Handles a single file member of {@link #collectBslFilesRecursive}: a non-.bsl file
     * is ignored, and a .bsl file is added to {@code modules} only when it passes the
     * {@code nameFilter}. Extracted verbatim from the recursive walk's per-file branch;
     * the original loop {@code continue} (skip this file) is an early {@code return} here.
     */
    private void addBslFileIfMatches(IFile file, List<ModuleInfo> modules, String basePath,
                                      String parentType, String parentName, String nameFilter)
    {
        if (!file.getName().endsWith(".bsl")) //$NON-NLS-1$
        {
            return;
        }
        // Build module path relative to src/
        String fullPath = file.getProjectRelativePath().toString();
        String modulePath = fullPath.startsWith("src/") ? fullPath.substring(4) : fullPath; //$NON-NLS-1$

        if (nameFilter != null && !nameFilter.isEmpty()
            && !modulePath.toLowerCase().contains(nameFilter.toLowerCase()))
        {
            return;
        }

        String moduleType = determineModuleType(modulePath, basePath);

        ModuleInfo info = new ModuleInfo();
        info.modulePath = modulePath;
        info.moduleType = moduleType;
        info.parentType = parentType;
        info.parentName = parentName;
        modules.add(info);
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
        if (MODULE.equals(baseName))
        {
            if (relativePath.startsWith("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            return MODULE;
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

        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
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
        sb.append(Pagination.truncationNotice(shown, total));
        sb.append("\n\n"); //$NON-NLS-1$

        if (modules.isEmpty())
        {
            sb.append("No modules found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append(MarkdownUtils.tableHeader(
            "Module Path", "Module Type", "Parent Type", "Parent Name")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        int count = 0;
        for (ModuleInfo info : modules)
        {
            if (count >= limit)
            {
                break;
            }
            sb.append(MarkdownUtils.tableRow(
                info.modulePath, info.moduleType, info.parentType, info.parentName));
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
