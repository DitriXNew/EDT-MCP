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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to get the structure of a BSL module: methods, signatures, regions, export flags.
 */
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
        return "Get structure of a BSL module: all procedures/functions with signatures, " + //$NON-NLS-1$
               "line numbers, regions, execution context (&AtServer, &AtClient), " + //$NON-NLS-1$
               "export flag, and parameters."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .booleanProperty("includeVariables", //$NON-NLS-1$
                "Include module-level variable declarations. Default: false") //$NON-NLS-1$
            .booleanProperty("includeComments", //$NON-NLS-1$
                "Include documentation comments for methods. Default: false") //$NON-NLS-1$
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
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "structure-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "module-structure.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        boolean includeVariables = JsonUtils.extractBooleanArgument(params, "includeVariables", false); //$NON-NLS-1$
        boolean includeComments = JsonUtils.extractBooleanArgument(params, "includeComments", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        // Try EMF approach first (on UI thread)
        AtomicReference<String> resultRef = new AtomicReference<>();

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getStructureInternal(projectName, modulePath, includeVariables, includeComments);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting module structure via EMF", e); //$NON-NLS-1$
                resultRef.set(null); // Signal to try fallback
            }
        });

        String result = resultRef.get();
        if (result != null)
        {
            return result;
        }

        return "Error: BSL model is not available for '" + modulePath + "'\n" + //$NON-NLS-1$ //$NON-NLS-2$
               "Make sure project '" + projectName + "' is open and fully indexed in EDT."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String getStructureInternal(String projectName, String modulePath,
        boolean includeVariables, boolean includeComments)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return "Error: BSL model is not available for '" + modulePath + "'\n" + //$NON-NLS-1$ //$NON-NLS-2$
                   "Make sure project '" + projectName + "' is open and fully indexed in EDT."; //$NON-NLS-1$ //$NON-NLS-2$
        }

        List<RegionInfo> regions = collectRegions(module);

        // Collect methods
        List<MethodInfo> methods = collectMethods(module, regions, includeComments);

        // Collect variables if requested
        List<VariableInfo> variables = includeVariables ? collectVariables(module, regions) : null;

        // Count procedures and functions
        int procCount = 0;
        int funcCount = 0;
        for (MethodInfo m : methods)
        {
            if (m.isFunction)
            {
                funcCount++;
            }
            else
            {
                procCount++;
            }
        }

        // Get total line count
        int totalLines = 0;
        INode moduleNode = NodeModelUtils.findActualNodeFor(module);
        if (moduleNode != null)
        {
            totalLines = moduleNode.getEndLine();
        }

        // Format output
        StringBuilder sb = new StringBuilder();
        sb.append("## Module Structure: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total:** ").append(procCount).append(" procedures, ") //$NON-NLS-1$ //$NON-NLS-2$
          .append(funcCount).append(" functions | **Lines:** ").append(totalLines).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Regions section
        if (!regions.isEmpty())
        {
            sb.append("### Regions\n\n"); //$NON-NLS-1$
            for (RegionInfo region : regions)
            {
                sb.append("- ").append(region.name) //$NON-NLS-1$
                  .append(" (line ").append(region.startLine) //$NON-NLS-1$
                  .append("-").append(region.endLine).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        // Variables section
        if (variables != null && !variables.isEmpty())
        {
            appendVariablesTable(sb, variables);
        }

        // Methods table
        if (methods.isEmpty())
        {
            sb.append("No methods found in this module.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        appendMethodsTable(sb, methods);

        return sb.toString();
    }


    /**
     * Appends a markdown methods table to the StringBuilder.
     * Shared between EMF and text-based paths to avoid duplication.
     */
    private void appendMethodsTable(StringBuilder sb, List<MethodInfo> methods)
    {
        // Check if any method has doc-comments
        boolean hasComments = false;
        for (MethodInfo m : methods)
        {
            if (m.docComment != null)
            {
                hasComments = true;
                break;
            }
        }

        sb.append("### Methods\n\n"); //$NON-NLS-1$
        if (hasComments)
        {
            sb.append("| # | Type | Name | Export | Context | Lines | Parameters | Region | Description |\n"); //$NON-NLS-1$
            sb.append("|---|------|------|--------|---------|-------|------------|--------|-------------|\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("| # | Type | Name | Export | Context | Lines | Parameters | Region |\n"); //$NON-NLS-1$
            sb.append("|---|------|------|--------|---------|-------|------------|--------|\n"); //$NON-NLS-1$
        }

        int idx = 1;
        for (MethodInfo m : methods)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(m.name)); //$NON-NLS-1$
            sb.append(" | ").append(m.isExport ? "Yes" : "-"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append(" | ").append(m.executionContext != null ? MarkdownUtils.escapeForTable(m.executionContext) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(m.paramsString)); //$NON-NLS-1$
            sb.append(" | ").append(m.region != null ? MarkdownUtils.escapeForTable(m.region) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            if (hasComments)
            {
                sb.append(" | ").append(m.docComment != null ? MarkdownUtils.escapeForTable(m.docComment) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append(" |\n"); //$NON-NLS-1$
        }
    }

    /**
     * Finds the innermost region (narrowest line range) containing the given line.
     */
    private String findContainingRegion(int line, List<RegionInfo> regions)
    {
        String bestRegion = null;
        int bestRange = Integer.MAX_VALUE;
        for (RegionInfo region : regions)
        {
            if (line >= region.startLine && line <= region.endLine)
            {
                int range = region.endLine - region.startLine;
                if (range < bestRange)
                {
                    bestRange = range;
                    bestRegion = region.name;
                }
            }
        }
        return bestRegion;
    }
    // ========== Data collection ==========


    /**
     * Collects regions from the BSL AST model.
     */
    private List<RegionInfo> collectRegions(Module module)
    {
        List<RegionInfo> regions = new ArrayList<>();

        try
        {
            for (var iter = module.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (obj instanceof RegionPreprocessor region)
                {
                    RegionInfo info = new RegionInfo();
                    info.name = region.getName();
                    info.startLine = BslModuleUtils.getStartLine(region);
                    info.endLine = computeRegionEndLine(region, info.startLine);
                    if (info.name != null && !info.name.isEmpty() && info.startLine > 0)
                    {
                        regions.add(info);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting regions", e); //$NON-NLS-1$
        }

        return regions;
    }

    /** Computes region end line by scanning all contained EObjects for the maximum end line. */
    private int computeRegionEndLine(RegionPreprocessor region, int startLine)
    {
        int endLine = startLine;
        for (var iter = region.eAllContents(); iter.hasNext();)
        {
            int childEnd = BslModuleUtils.getEndLine(iter.next());
            if (childEnd > endLine)
            {
                endLine = childEnd;
            }
        }
        return endLine > startLine ? endLine + 1 : startLine + 1; // +1 for #EndRegion line
    }
    private List<MethodInfo> collectMethods(Module module, List<RegionInfo> regions,
        boolean includeComments)
    {
        List<MethodInfo> methods = new ArrayList<>();
        
        // Load source lines if includeComments is enabled
        List<String> sourceLines = null;
        if (includeComments)
        {
            try
            {
                Resource resource = module.eResource();
                if (resource != null)
                {
                    URI uri = resource.getURI();
                    if (uri.isPlatformResource())
                    {
                        String platformString = uri.toPlatformString(true);
                        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platformString));
                        if (file != null && file.exists())
                        {
                            sourceLines = BslModuleUtils.readFileLines(file);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to load source for comment extraction: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        for (Method method : module.allMethods())
        {
            try
            {
                MethodInfo info = new MethodInfo();
                info.name = method.getName();
                info.isFunction = method instanceof Function;
                info.isExport = method.isExport();
                info.startLine = BslModuleUtils.getStartLine(method);
                info.endLine = BslModuleUtils.getEndLine(method);

                // Collect parameters via shared utility
                info.paramsString = BslModuleUtils.buildParamsString(method);

                // Collect execution context from pragmas
                info.executionContext = collectPragmas(method);

                // Find containing region (innermost)
                info.region = findContainingRegion(info.startLine, regions);

                // Collect doc-comment if requested
                if (includeComments && sourceLines != null)
                {
                    info.docComment = extractDocCommentFromLines(sourceLines, info.startLine);
                }

                methods.add(info);
            }
            catch (Exception e)
            {
                Activator.logError("Error processing method: " + method.getName(), e); //$NON-NLS-1$
            }
        }

        return methods;
    }

    private String collectPragmas(Method method)
    {
        try
        {
            EList<Pragma> pragmas = method.getPragmas();
            if (pragmas != null && !pragmas.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pragmas.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    Pragma pragma = pragmas.get(i);
                    sb.append("&").append(pragma.getSymbol()); //$NON-NLS-1$
                }
                return sb.toString();
            }
        }
        catch (Exception e)
        {
            // Ignore - pragmas may not be available in all module types
        }
        return null;
    }

    // ========== Internal data structures ==========

    private static class MethodInfo
    {
        String name;
        boolean isFunction;
        boolean isExport;
        int startLine;
        int endLine;
        String executionContext;
        String region;
        String paramsString;
        String docComment;
    }

    private static class RegionInfo
    {
        String name;
        int startLine;
        int endLine;
    }

    private static class VariableInfo
    {
        String name;
        boolean isExport;
        int line;
        String region;
    }

    // ========== Variables collection ==========

    /**
     * Collects module-level variable declarations from the EMF model.
     */
    private List<VariableInfo> collectVariables(Module module, List<RegionInfo> regions)
    {
        List<VariableInfo> variables = new ArrayList<>();
        try
        {
            EList<DeclareStatement> declareStatements = module.allDeclareStatements();
            if (declareStatements != null)
            {
                for (DeclareStatement decl : declareStatements)
                {
                    EList<ExplicitVariable> vars = decl.getVariables();
                    if (vars == null)
                    {
                        continue;
                    }
                    for (ExplicitVariable var : vars)
                    {
                        VariableInfo info = new VariableInfo();
                        info.name = var.getName();
                        info.isExport = var.isExport();
                        info.line = BslModuleUtils.getStartLine(var);
                        info.region = findContainingRegion(info.line, regions);
                        variables.add(info);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting variables", e); //$NON-NLS-1$
        }
        return variables;
    }

    /**
     * Appends a markdown variables table to the StringBuilder.
     */
    private void appendVariablesTable(StringBuilder sb, List<VariableInfo> variables)
    {
        sb.append("### Variables\n\n"); //$NON-NLS-1$
        sb.append("| # | Name | Export | Line | Region |\n"); //$NON-NLS-1$
        sb.append("|---|------|--------|------|--------|\n"); //$NON-NLS-1$

        int idx = 1;
        for (VariableInfo v : variables)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(v.name)); //$NON-NLS-1$
            sb.append(" | ").append(v.isExport ? "Yes" : "-"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append(" | ").append(v.line); //$NON-NLS-1$
            sb.append(" | ").append(v.region != null ? MarkdownUtils.escapeForTable(v.region) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" |\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    // ========== Documentation comments ==========

    /**
     * Extracts documentation comment from source lines by scanning backwards
     * from the method's start line.
     * 
     * @param sourceLines all lines of the source file (1-based indexing)
     * @param methodStartLine method's start line number (1-based)
     * @return concatenated doc-comment or null
     */
    private String extractDocCommentFromLines(List<String> sourceLines, int methodStartLine)
    {
        if (sourceLines == null || methodStartLine <= 1)
        {
            return null;
        }
        
        List<String> commentLines = new ArrayList<>();
        
        // Scan backwards from line before method (convert to 0-based index)
        for (int i = methodStartLine - 2; i >= 0; i--)
        {
            String line = sourceLines.get(i).trim();
            
            if (line.startsWith("//")) //$NON-NLS-1$
            {
                // Strip leading // and optional space
                String commentText = line.substring(2);
                if (commentText.startsWith(" ")) //$NON-NLS-1$
                {
                    commentText = commentText.substring(1);
                }
                commentLines.add(0, commentText);
            }
            else if (line.isEmpty())
            {
                // Skip empty lines between comment and method
                continue;
            }
            else
            {
                // Hit non-comment, non-empty line → stop
                break;
            }
        }
        
        if (commentLines.isEmpty())
        {
            return null;
        }
        
        return String.join(" ", commentLines); //$NON-NLS-1$
    }

    /**
     * Extracts documentation comment text for a method by reading nodes
     * immediately preceding the method declaration.
     * (DEPRECATED: replaced by extractDocCommentFromLines for reliability)
     */
    @SuppressWarnings("unused")
    private String extractDocComment(Method method)
    {
        try
        {
            INode methodNode = NodeModelUtils.findActualNodeFor(method);
            if (methodNode == null)
            {
                return null;
            }

            // Walk backward from method node to find comment lines
            INode current = methodNode.getPreviousSibling();
            List<String> commentLines = new ArrayList<>();

            while (current != null)
            {
                // Skip composite nodes (entire methods, regions, etc.) — only process leaf nodes
                if (!(current instanceof ILeafNode))
                {
                    current = current.getPreviousSibling();
                    continue;
                }
                
                String text = current.getText();
                if (text == null)
                {
                    break;
                }
                text = text.trim();
                if (text.startsWith("//")) //$NON-NLS-1$
                {
                    // Strip the leading // and optional space
                    String commentText = text.substring(2);
                    if (commentText.startsWith(" ")) //$NON-NLS-1$
                    {
                        commentText = commentText.substring(1);
                    }
                    commentLines.add(0, commentText);
                    current = current.getPreviousSibling();
                }
                else if (text.isEmpty())
                {
                    // Skip empty lines between comment and method
                    current = current.getPreviousSibling();
                }
                else
                {
                    // Hit non-comment, non-empty content → stop
                    break;
                }
            }

            if (commentLines.isEmpty())
            {
                return null;
            }

            return String.join(" ", commentLines); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
