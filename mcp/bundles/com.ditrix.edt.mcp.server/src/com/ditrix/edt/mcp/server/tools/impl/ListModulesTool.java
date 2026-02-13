/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.ModuleInfo;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.ModulePathInfo;

/**
 * Tool to list all BSL modules of a specific metadata object or the entire project.
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
        return "List all BSL modules of a specific metadata object or the entire project. " + //$NON-NLS-1$
               "When objectFqn is provided, lists modules of that object (ObjectModule, ManagerModule, FormModules, etc.). " + //$NON-NLS-1$
               "Without objectFqn, scans the entire project with optional metadataType filter."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Fully qualified name of a specific object (e.g. 'Document.SalesOrder', " + //$NON-NLS-1$
                "'CommonModule.ServerModule'). If omitted, lists all modules in the project.") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Filter by metadata type when listing all modules (e.g. 'Documents', " + //$NON-NLS-1$
                "'CommonModules', 'Catalogs'). Only used when objectFqn is omitted.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            String safeName = objectFqn.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "modules-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "modules-list.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }

        int limit = 200;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), 1000);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }

        IProject project = BslModuleUtils.resolveProject(projectName);
        if (project == null)
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        if (objectFqn != null && !objectFqn.isEmpty())
        {
            return listObjectModules(project, projectName, objectFqn);
        }
        else
        {
            return listProjectModules(project, projectName, metadataType, limit);
        }
    }

    /**
     * Lists modules of a specific metadata object.
     */
    private String listObjectModules(IProject project, String projectName, String objectFqn)
    {
        List<ModuleInfo> modules = BslModuleUtils.findObjectModules(project, objectFqn);

        StringBuilder sb = new StringBuilder();
        sb.append("## BSL Modules: ").append(objectFqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Project:** ").append(projectName).append(" | "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Modules found:** ").append(modules.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (modules.isEmpty())
        {
            sb.append("No BSL modules found for this object.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| Module Type | Path | Lines |\n"); //$NON-NLS-1$
        sb.append("|-------------|------|-------|\n"); //$NON-NLS-1$

        for (ModuleInfo module : modules)
        {
            String displayType = module.type.displayName;
            if (module.formName != null)
            {
                displayType = "FormModule (" + module.formName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }

            int lines = countModuleLines(project, module.relativePath);

            sb.append("| ").append(displayType); //$NON-NLS-1$
            sb.append(" | ").append(module.relativePath); //$NON-NLS-1$
            sb.append(" | ").append(lines >= 0 ? lines : "?"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" |\n"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Lists all modules in the project, optionally filtered by metadata type.
     */
    private String listProjectModules(IProject project, String projectName,
        String metadataType, int limit)
    {
        List<IFile> bslFiles;
        if (metadataType != null && !metadataType.isEmpty())
        {
            bslFiles = BslModuleUtils.findBslFilesByType(project, metadataType);
        }
        else
        {
            bslFiles = BslModuleUtils.findAllBslFiles(project);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## BSL Modules: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (metadataType != null && !metadataType.isEmpty())
        {
            sb.append("**Filter:** ").append(metadataType).append(" | "); //$NON-NLS-1$ //$NON-NLS-2$
        }

        int total = bslFiles.size();
        int shown = Math.min(total, limit);
        sb.append("**Total:** ").append(total).append(" modules"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown < total)
        {
            sb.append(" (showing ").append(shown).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (bslFiles.isEmpty())
        {
            sb.append("No BSL modules found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| Module Path | Module Type | Owner | Lines |\n"); //$NON-NLS-1$
        sb.append("|-------------|-------------|-------|-------|\n"); //$NON-NLS-1$

        int count = 0;
        for (IFile file : bslFiles)
        {
            if (count >= limit)
            {
                break;
            }

            String relativePath = BslModuleUtils.getRelativeModulePath(project, file);
            ModulePathInfo pathInfo = BslModuleUtils.parseModulePath(relativePath);

            String moduleType = pathInfo != null ? pathInfo.moduleType.displayName : "Unknown"; //$NON-NLS-1$
            String ownerFqn = pathInfo != null ? pathInfo.ownerFqn : "Unknown"; //$NON-NLS-1$
            int lines = BslModuleUtils.countLines(file);

            sb.append("| ").append(relativePath); //$NON-NLS-1$
            sb.append(" | ").append(moduleType); //$NON-NLS-1$
            sb.append(" | ").append(ownerFqn); //$NON-NLS-1$
            sb.append(" | ").append(lines >= 0 ? lines : "?"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" |\n"); //$NON-NLS-1$

            count++;
        }

        return sb.toString();
    }

    /**
     * Counts lines in a module by its relative path.
     */
    private int countModuleLines(IProject project, String modulePath)
    {
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (file == null)
        {
            return -1;
        }
        return BslModuleUtils.countLines(file);
    }
}
