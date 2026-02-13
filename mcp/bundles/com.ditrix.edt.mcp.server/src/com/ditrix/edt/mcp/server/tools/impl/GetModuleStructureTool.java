/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Preprocessor;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.MethodInfo;

/**
 * Tool to get module structure: all procedures/functions with signatures,
 * line numbers, regions, execution context, and Export flags.
 */
@SuppressWarnings("restriction")
public class GetModuleStructureTool implements IMcpTool
{
    public static final String NAME = "get_module_structure"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get BSL module structure: all procedures/functions with their signatures, " + //$NON-NLS-1$
               "line numbers, #Region names, execution context (&AtServer, &AtClient, etc.), " + //$NON-NLS-1$
               "and Export flags. Useful for understanding module layout without reading the full source."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to BSL file relative to project's src folder " + //$NON-NLS-1$
                "(e.g. 'CommonModules/MyModule/Module.bsl', " + //$NON-NLS-1$
                "'Documents/SalesOrder/Ext/ObjectModule.bsl')", true) //$NON-NLS-1$
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
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace(".bsl", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "structure-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "module-structure.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required"; //$NON-NLS-1$
        }

        IProject project = BslModuleUtils.resolveProject(projectName);
        if (project == null)
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (file == null)
        {
            return "Error: Module not found: " + modulePath; //$NON-NLS-1$
        }

        // Count total lines
        int totalLines = BslModuleUtils.countLines(file);

        // Load BSL module AST
        Module module = BslModuleUtils.loadBslModule(file);
        if (module == null)
        {
            return "Error: Failed to load BSL module AST for: " + modulePath; //$NON-NLS-1$
        }

        // Extract methods
        List<MethodInfo> methods = BslModuleUtils.extractMethods(module);

        // Extract regions
        List<RegionInfo> regions = extractRegions(module);

        // Format output
        return formatOutput(modulePath, totalLines, methods, regions);
    }

    /**
     * Extracts region information from the module AST.
     */
    private List<RegionInfo> extractRegions(Module module)
    {
        List<RegionInfo> regions = new ArrayList<>();

        EList<Preprocessor> preprocessors = module.getPreprocessors();
        if (preprocessors != null)
        {
            for (Preprocessor preprocessor : preprocessors)
            {
                collectRegions(preprocessor, regions);
            }
        }

        // Also check module body for regions
        for (EObject child : module.eContents())
        {
            if (child instanceof RegionPreprocessor)
            {
                addRegionInfo((RegionPreprocessor) child, regions);
            }
        }

        regions.sort(Comparator.comparingInt(r -> r.startLine));
        return regions;
    }

    /**
     * Recursively collects region preprocessors.
     */
    private void collectRegions(Preprocessor preprocessor, List<RegionInfo> regions)
    {
        if (preprocessor instanceof RegionPreprocessor)
        {
            addRegionInfo((RegionPreprocessor) preprocessor, regions);
        }
    }

    /**
     * Adds a region's info to the list.
     */
    private void addRegionInfo(RegionPreprocessor region, List<RegionInfo> regions)
    {
        String name = region.getName();
        if (name == null || name.isEmpty())
        {
            return;
        }

        // Check for duplicates
        for (RegionInfo existing : regions)
        {
            if (name.equals(existing.name))
            {
                return;
            }
        }

        INode node = NodeModelUtils.findActualNodeFor(region);
        int startLine = node != null ? node.getStartLine() : 0;
        int endLine = node != null ? node.getEndLine() : 0;

        regions.add(new RegionInfo(name, startLine, endLine));
    }

    /**
     * Formats the output as markdown.
     */
    private String formatOutput(String modulePath, int totalLines,
        List<MethodInfo> methods, List<RegionInfo> regions)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("## Module Structure: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total lines:** ").append(totalLines >= 0 ? totalLines : "?"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(" | **Methods:** ").append(methods.size()); //$NON-NLS-1$
        sb.append(" | **Regions:** ").append(regions.size()); //$NON-NLS-1$
        sb.append("\n\n"); //$NON-NLS-1$

        // Methods table
        if (!methods.isEmpty())
        {
            sb.append("### Methods\n\n"); //$NON-NLS-1$
            sb.append("| # | Name | Type | Export | Context | Lines | Region |\n"); //$NON-NLS-1$
            sb.append("|---|------|------|--------|---------|-------|--------|\n"); //$NON-NLS-1$

            int index = 1;
            for (MethodInfo method : methods)
            {
                String type = method.isFunction ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
                String export = method.isExport ? "Yes" : "-"; //$NON-NLS-1$ //$NON-NLS-2$
                String context = method.pragmas != null && !method.pragmas.isEmpty()
                    ? method.pragmas : "-"; //$NON-NLS-1$
                String lines = method.startLine + "-" + method.endLine; //$NON-NLS-1$
                String region = method.regionName != null ? method.regionName : "-"; //$NON-NLS-1$

                sb.append("| ").append(index); //$NON-NLS-1$
                sb.append(" | ").append(method.name); //$NON-NLS-1$
                sb.append(" | ").append(type); //$NON-NLS-1$
                sb.append(" | ").append(export); //$NON-NLS-1$
                sb.append(" | ").append(context); //$NON-NLS-1$
                sb.append(" | ").append(lines); //$NON-NLS-1$
                sb.append(" | ").append(region); //$NON-NLS-1$
                sb.append(" |\n"); //$NON-NLS-1$

                index++;
            }
        }
        else
        {
            sb.append("No methods found in this module.\n"); //$NON-NLS-1$
        }

        // Regions table
        if (!regions.isEmpty())
        {
            sb.append("\n### Regions\n\n"); //$NON-NLS-1$
            sb.append("| Region | Lines |\n"); //$NON-NLS-1$
            sb.append("|--------|-------|\n"); //$NON-NLS-1$

            for (RegionInfo region : regions)
            {
                sb.append("| ").append(region.name); //$NON-NLS-1$
                sb.append(" | ").append(region.startLine).append("-").append(region.endLine); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(" |\n"); //$NON-NLS-1$
            }
        }

        // Signatures section
        if (!methods.isEmpty())
        {
            sb.append("\n### Signatures\n\n"); //$NON-NLS-1$
            for (MethodInfo method : methods)
            {
                if (method.pragmas != null && !method.pragmas.isEmpty())
                {
                    sb.append(method.pragmas).append("\n"); //$NON-NLS-1$
                }
                sb.append(method.signature).append("\n\n"); //$NON-NLS-1$
            }
        }

        return sb.toString();
    }

    /**
     * Holds region information.
     */
    private static class RegionInfo
    {
        final String name;
        final int startLine;
        final int endLine;

        RegionInfo(String name, int startLine, int endLine)
        {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}
