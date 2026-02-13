/**
 * Copyright (c) 2026 Diversus
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

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

    /** Regex for pragma annotation */
    private static final Pattern PRAGMA_PATTERN = Pattern.compile(
        "^\\s*&(\\S+)", Pattern.UNICODE_CASE); //$NON-NLS-1$

    /** Regex for region start */
    private static final Pattern REGION_START = Pattern.compile(
        "^\\s*#(?:\u041e\u0431\u043b\u0430\u0441\u0442\u044c|Region)\\s+(\\S+)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for region end */
    private static final Pattern REGION_END_PATTERN = Pattern.compile(
        "^\\s*#(?:\u041a\u043e\u043d\u0435\u0446\u041e\u0431\u043b\u0430\u0441\u0442\u0438|EndRegion)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for Export keyword */
    private static final Pattern EXPORT_PATTERN = Pattern.compile(
        "(?:\u042d\u043a\u0441\u043f\u043e\u0440\u0442|Export)\\s*$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
            return "Error: modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        // Try EMF approach first (on UI thread)
        AtomicReference<String> resultRef = new AtomicReference<>();

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getStructureInternal(projectName, modulePath);
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

        // Fallback: text-based structure parsing
        return getStructureViaText(projectName, modulePath);
    }

    private String getStructureInternal(String projectName, String modulePath)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return null; // Signal to try text fallback
        }

        // Collect regions from text (EMF RegionPreprocessor nodes return incorrect endLine)
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        List<RegionInfo> regions;
        if (file.exists())
        {
            try
            {
                regions = collectRegionsFromText(file);
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to parse regions from text, falling back to EMF: " + e.getMessage()); //$NON-NLS-1$
                regions = collectRegions(module);
            }
        }
        else
        {
            regions = collectRegions(module);
        }

        // Collect methods
        List<MethodInfo> methods = collectMethods(module, regions);

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
     * Text-based fallback: parses module structure using regex when EMF model is not available.
     */
    private String getStructureViaText(String projectName, String modulePath)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: File not found: src/" + modulePath; //$NON-NLS-1$
        }

        try
        {
            List<String> lines = BslModuleUtils.readFileLines(file);
            int totalLines = lines.size();

            List<RegionInfo> regions = new ArrayList<>();
            List<MethodInfo> methods = new ArrayList<>();
            List<RegionInfo> regionStack = new ArrayList<>();
            String pendingPragma = null;

            for (int i = 0; i < lines.size(); i++)
            {
                String line = lines.get(i);

                // Check for pragma (before method declaration)
                Matcher pragmaMatcher = PRAGMA_PATTERN.matcher(line);
                if (pragmaMatcher.find())
                {
                    pendingPragma = (pendingPragma != null ? pendingPragma + ", " : "") + "&" + pragmaMatcher.group(1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    continue;
                }

                // Check for region start
                Matcher regionStartMatcher = REGION_START.matcher(line);
                if (regionStartMatcher.find())
                {
                    RegionInfo region = new RegionInfo();
                    region.name = regionStartMatcher.group(1);
                    region.startLine = i + 1;
                    regionStack.add(region);
                    pendingPragma = null;
                    continue;
                }

                // Check for region end
                if (REGION_END_PATTERN.matcher(line).find())
                {
                    if (!regionStack.isEmpty())
                    {
                        RegionInfo region = regionStack.remove(regionStack.size() - 1);
                        region.endLine = i + 1;
                        regions.add(region);
                    }
                    pendingPragma = null;
                    continue;
                }

                // Check for method start
                Matcher methodMatcher = BslModuleUtils.METHOD_START_PATTERN.matcher(line);
                if (methodMatcher.find())
                {
                    MethodInfo info = new MethodInfo();
                    info.name = methodMatcher.group(1);
                    info.isFunction = BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher(line).find();
                    info.startLine = i + 1;
                    info.executionContext = pendingPragma;
                    pendingPragma = null;

                    // Extract parameters from declaration (may span multiple lines)
                    String declLine = methodMatcher.group(2);
                    // Check if declaration closes on this line
                    int parenDepth = 1;
                    StringBuilder paramsBuf = new StringBuilder(declLine);
                    for (int j = 0; j < declLine.length(); j++)
                    {
                        if (declLine.charAt(j) == '(') parenDepth++;
                        if (declLine.charAt(j) == ')') parenDepth--;
                    }
                    // Multi-line parameters
                    int nextLine = i + 1;
                    while (parenDepth > 0 && nextLine < lines.size())
                    {
                        String cont = lines.get(nextLine);
                        paramsBuf.append(cont);
                        for (int j = 0; j < cont.length(); j++)
                        {
                            if (cont.charAt(j) == '(') parenDepth++;
                            if (cont.charAt(j) == ')') parenDepth--;
                        }
                        nextLine++;
                    }

                    // Extract params text and export flag
                    String fullDecl = paramsBuf.toString();
                    int closeParen = fullDecl.indexOf(')');
                    if (closeParen >= 0)
                    {
                        String paramsText = fullDecl.substring(0, closeParen).trim();
                        info.paramsString = paramsText.isEmpty() ? "-" : paramsText; //$NON-NLS-1$
                        String afterParams = fullDecl.substring(closeParen + 1);
                        info.isExport = EXPORT_PATTERN.matcher(afterParams).find();
                    }
                    else
                    {
                        info.paramsString = "-"; //$NON-NLS-1$
                    }

                    // Find method end
                    for (int j = i + 1; j < lines.size(); j++)
                    {
                        if (BslModuleUtils.METHOD_END_PATTERN.matcher(lines.get(j)).find())
                        {
                            info.endLine = j + 1;
                            break;
                        }
                    }
                    if (info.endLine == 0)
                    {
                        info.endLine = totalLines;
                    }

                    // Find containing region
                    info.region = findContainingRegion(info.startLine, regionStack, regions);

                    methods.add(info);
                }
                else
                {
                    // Reset pragma if line is not a pragma and not a method start
                    if (!line.trim().isEmpty())
                    {
                        pendingPragma = null;
                    }
                }
            }

            // Format output (same format as EMF)
            int procCount = 0;
            int funcCount = 0;
            for (MethodInfo m : methods)
            {
                if (m.isFunction) funcCount++;
                else procCount++;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Module Structure: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Total:** ").append(procCount).append(" procedures, ") //$NON-NLS-1$ //$NON-NLS-2$
              .append(funcCount).append(" functions | **Lines:** ").append(totalLines).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("*Note: parsed via text fallback (EMF model was not available)*\n\n"); //$NON-NLS-1$

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

            if (methods.isEmpty())
            {
                sb.append("No methods found in this module.\n"); //$NON-NLS-1$
                return sb.toString();
            }

            appendMethodsTable(sb, methods);

            return sb.toString();
        }
        catch (Exception e)
        {
            return "Error reading file: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Appends a markdown methods table to the StringBuilder.
     * Shared between EMF and text-based paths to avoid duplication.
     */
    private void appendMethodsTable(StringBuilder sb, List<MethodInfo> methods)
    {
        sb.append("### Methods\n\n"); //$NON-NLS-1$
        sb.append("| # | Type | Name | Export | Context | Lines | Parameters | Region |\n"); //$NON-NLS-1$
        sb.append("|---|------|------|--------|---------|-------|------------|--------|\n"); //$NON-NLS-1$

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
            sb.append(" |\n"); //$NON-NLS-1$
        }
    }

    /**
     * Finds the innermost region containing the given line.
     * Checks open (stack) regions first, then closed regions by narrowest range.
     *
     * @param line the line number to check
     * @param openRegions still-open regions (from text parsing stack), may be null
     * @param closedRegions fully closed regions with start/end lines
     * @return region name or null
     */
    private String findContainingRegion(int line, List<RegionInfo> openRegions, List<RegionInfo> closedRegions)
    {
        // Find innermost region from open (active) regions — last one on the stack is innermost
        if (openRegions != null)
        {
            for (int i = openRegions.size() - 1; i >= 0; i--)
            {
                if (line >= openRegions.get(i).startLine)
                {
                    return openRegions.get(i).name;
                }
            }
        }
        // Find innermost region from closed regions (narrowest range)
        String bestRegion = null;
        int bestRange = Integer.MAX_VALUE;
        for (RegionInfo region : closedRegions)
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
     * Collects regions from file text using regex (reliable for all cases).
     */
    private List<RegionInfo> collectRegionsFromText(IFile file) throws Exception
    {
        List<String> lines = BslModuleUtils.readFileLines(file);
        List<RegionInfo> regions = new ArrayList<>();
        List<RegionInfo> regionStack = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);

            Matcher regionStartMatcher = REGION_START.matcher(line);
            if (regionStartMatcher.find())
            {
                RegionInfo region = new RegionInfo();
                region.name = regionStartMatcher.group(1);
                region.startLine = i + 1;
                regionStack.add(region);
                continue;
            }

            if (REGION_END_PATTERN.matcher(line).find())
            {
                if (!regionStack.isEmpty())
                {
                    RegionInfo region = regionStack.remove(regionStack.size() - 1);
                    region.endLine = i + 1;
                    regions.add(region);
                }
            }
        }

        // Close any unclosed regions at EOF
        for (RegionInfo region : regionStack)
        {
            region.endLine = lines.size();
            regions.add(region);
        }

        return regions;
    }

    /**
     * Collects regions from EMF model (fallback, may have inaccurate endLine).
     */
    private List<RegionInfo> collectRegions(Module module)
    {
        List<RegionInfo> regions = new ArrayList<>();

        try
        {
            // Walk all contents looking for RegionPreprocessor nodes
            for (var iter = module.eAllContents(); iter.hasNext(); )
            {
                EObject obj = iter.next();
                if (obj instanceof RegionPreprocessor)
                {
                    RegionPreprocessor region = (RegionPreprocessor) obj;
                    RegionInfo info = new RegionInfo();
                    info.name = region.getName();
                    info.startLine = BslModuleUtils.getStartLine(region);
                    info.endLine = BslModuleUtils.getEndLine(region);
                    if (info.name != null && !info.name.isEmpty())
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

    private List<MethodInfo> collectMethods(Module module, List<RegionInfo> regions)
    {
        List<MethodInfo> methods = new ArrayList<>();

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
                info.region = findContainingRegion(info.startLine, null, regions);

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
    }

    private static class RegionInfo
    {
        String name;
        int startLine;
        int endLine;
    }
}
