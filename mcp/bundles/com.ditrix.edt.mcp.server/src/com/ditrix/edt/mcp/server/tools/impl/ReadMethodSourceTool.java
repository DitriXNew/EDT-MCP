/**
 * Copyright (c) 2026 Diversus
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to read a specific procedure/function from a BSL module.
 * Instead of reading 60,000 lines, reads only the needed 50.
 */
public class ReadMethodSourceTool implements IMcpTool
{
    public static final String NAME = "read_method_source"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read a specific procedure/function from a BSL module by name. " + //$NON-NLS-1$
               "Returns the method source code with line numbers and signature. " + //$NON-NLS-1$
               "If method not found, returns list of all available methods."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Name of the procedure/function to read (case-insensitive, required)", true) //$NON-NLS-1$
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
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        if (methodName != null && !methodName.isEmpty())
        {
            return "method-" + methodName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "method-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required"; //$NON-NLS-1$
        }
        if (methodName == null || methodName.isEmpty())
        {
            return "Error: methodName is required"; //$NON-NLS-1$
        }

        // Try EMF approach first (on UI thread)
        AtomicReference<String> resultRef = new AtomicReference<>();

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = readMethodViaEmf(projectName, modulePath, methodName);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error reading method via EMF", e); //$NON-NLS-1$
                resultRef.set(null); // Signal to try fallback
            }
        });

        String result = resultRef.get();
        if (result != null)
        {
            return result;
        }

        // Fallback: text-based approach
        return readMethodViaText(projectName, modulePath, methodName);
    }

    /**
     * Primary approach: Read method using BSL EMF model.
     */
    private String readMethodViaEmf(String projectName, String modulePath, String methodName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            // EMF not available - return null to trigger fallback
            return null;
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            // Method not found - list all available methods
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        // Get line range from EMF node
        int startLine = BslModuleUtils.getStartLine(method);
        int endLine = BslModuleUtils.getEndLine(method);

        // Read file to get actual source lines (getText() may include preceding doc-comments)
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        List<String> allLines;
        try
        {
            allLines = BslModuleUtils.readFileLines(file);
        }
        catch (Exception e)
        {
            Activator.logWarning("readMethodViaEmf: failed to read file, using getText(): " + e.getMessage()); //$NON-NLS-1$
            // Fallback: use getText() from EMF node (may include doc-comment)
            return readMethodFromEmfText(method, modulePath, startLine, endLine);
        }

        // Include doc-comment block preceding the method keyword
        int docStartLine = findDocCommentStart(allLines, startLine);

        // Clamp range to file boundaries
        int from = Math.max(1, docStartLine);
        int to = Math.min(allLines.size(), endLine);
        int lineCount = to - from + 1;

        // Build signature info
        String typeStr = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        String signature = BslModuleUtils.buildSignature(method);

        StringBuilder sb = new StringBuilder();
        sb.append("## Method: ").append(method.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Type:** ").append(typeStr).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Lines:** ").append(from).append(" - ").append(to) //$NON-NLS-1$ //$NON-NLS-2$
          .append(" (").append(lineCount).append(" lines)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Signature:** ").append(signature).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("```bsl\n"); //$NON-NLS-1$
        for (int i = from - 1; i < to; i++)
        {
            sb.append(String.format("%d: %s\n", i + 1, allLines.get(i))); //$NON-NLS-1$
        }
        sb.append("```\n"); //$NON-NLS-1$

        return sb.toString();
    }

    /**
     * Fallback approach: Read method using text search.
     */
    private String readMethodViaText(String projectName, String modulePath, String methodName)
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
            List<String> allLines = BslModuleUtils.readFileLines(file);

            // Find method by regex
            int methodStart = -1;
            int methodEnd = -1;
            List<String> allMethodNames = new ArrayList<>();

            for (int i = 0; i < allLines.size(); i++)
            {
                Matcher startMatcher = BslModuleUtils.METHOD_START_PATTERN.matcher(allLines.get(i));
                if (startMatcher.find())
                {
                    String foundName = startMatcher.group(1);
                    allMethodNames.add(foundName);

                    if (foundName.equalsIgnoreCase(methodName))
                    {
                        methodStart = i;
                    }
                }

                if (methodStart >= 0 && methodEnd < 0)
                {
                    Matcher endMatcher = BslModuleUtils.METHOD_END_PATTERN.matcher(allLines.get(i));
                    if (endMatcher.find())
                    {
                        methodEnd = i;
                        break; // Method found — stop scanning
                    }
                }
            }

            if (methodStart < 0)
            {
                // Method not found - list available methods
                StringBuilder sb = new StringBuilder();
                sb.append("Error: Method '").append(methodName).append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                sb.append("**Available methods** (").append(allMethodNames.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String name : allMethodNames)
                {
                    sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return sb.toString();
            }

            if (methodEnd < 0)
            {
                methodEnd = allLines.size() - 1;
            }

            // Include doc-comment block preceding the method keyword
            int docStart = findDocCommentStart(allLines, methodStart + 1) - 1; // convert to 0-indexed
            methodStart = docStart;

            // Build output
            int lineCount = methodEnd - methodStart + 1;
            StringBuilder sb = new StringBuilder();
            sb.append("## Method: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Lines:** ").append(methodStart + 1).append(" - ").append(methodEnd + 1) //$NON-NLS-1$ //$NON-NLS-2$
              .append(" (").append(lineCount).append(" lines)\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("*Note: read via text fallback (EMF model was not available)*\n\n"); //$NON-NLS-1$

            sb.append("```bsl\n"); //$NON-NLS-1$
            for (int i = methodStart; i <= methodEnd; i++)
            {
                sb.append(String.format("%d: %s\n", i + 1, allLines.get(i))); //$NON-NLS-1$
            }
            sb.append("```\n"); //$NON-NLS-1$

            return sb.toString();
        }
        catch (Exception e)
        {
            return "Error reading file: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    // ========== Helper methods ==========

    /**
     * Fallback: format method source from EMF getText() when file reading fails.
     * Note: getText() may include doc-comments, so line numbers may be inaccurate.
     */
    private String readMethodFromEmfText(Method method, String modulePath, int startLine, int endLine)
    {
        String sourceText = BslModuleUtils.getSourceText(method);
        int lineCount = endLine - startLine + 1;

        String typeStr = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        String signature = BslModuleUtils.buildSignature(method);

        StringBuilder sb = new StringBuilder();
        sb.append("## Method: ").append(method.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Type:** ").append(typeStr).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Lines:** ").append(startLine).append(" - ").append(endLine) //$NON-NLS-1$ //$NON-NLS-2$
          .append(" (").append(lineCount).append(" lines)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Signature:** ").append(signature).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("*Note: read via EMF getText(), line numbers may include doc-comment offset*\n\n"); //$NON-NLS-1$

        if (sourceText != null)
        {
            sb.append("```bsl\n"); //$NON-NLS-1$
            String[] lines = sourceText.split("\n", -1); //$NON-NLS-1$
            for (int i = 0; i < lines.length; i++)
            {
                sb.append(String.format("%d: %s\n", startLine + i, lines[i])); //$NON-NLS-1$
            }
            sb.append("```\n"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Scans backwards from the method keyword line to find the start of a doc-comment block.
     * BSL doc-comments are consecutive lines starting with //.
     *
     * @param allLines all file lines (0-indexed list)
     * @param methodKeywordLine 1-based line number of the method keyword (Функция/Процедура)
     * @return 1-based line number where the doc-comment starts, or methodKeywordLine if no doc-comment
     */
    private int findDocCommentStart(List<String> allLines, int methodKeywordLine)
    {
        if (methodKeywordLine <= 1)
        {
            return methodKeywordLine;
        }

        int idx = methodKeywordLine - 2; // 0-indexed, line before the keyword
        while (idx >= 0 && allLines.get(idx).trim().startsWith("//")) //$NON-NLS-1$
        {
            idx--;
        }

        int docStart = idx + 2; // convert back to 1-based
        return docStart < methodKeywordLine ? docStart : methodKeywordLine;
    }
}
