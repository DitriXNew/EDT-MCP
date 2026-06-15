/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.InterceptionUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to read a specific procedure/function from a BSL module.
 * Instead of reading 60,000 lines, reads only the needed 50.
 */
public class ReadMethodSourceTool implements IMcpTool
{
    public static final String NAME = "read_method_source"; //$NON-NLS-1$

    /** Input param: name of the procedure/function to read. */
    private static final String KEY_METHOD_NAME = "methodName"; //$NON-NLS-1$
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$
    private static final String KEY_METHOD = "method"; //$NON-NLS-1$
    private static final String KEY_EXPORT = "export"; //$NON-NLS-1$
    private static final String KEY_START_LINE = "startLine"; //$NON-NLS-1$
    private static final String KEY_END_LINE = "endLine"; //$NON-NLS-1$
    private static final String TYPE_PROCEDURE = "Procedure"; //$NON-NLS-1$
    private static final String TYPE_FUNCTION = "Function"; //$NON-NLS-1$
    private static final String BSL_FENCE_OPEN = "```bsl\n"; //$NON-NLS-1$
    private static final String FENCE_CLOSE = "```\n"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read a specific procedure/function from a BSL module by name. " + //$NON-NLS-1$
               "Returns source code with metadata. Lists available methods if not found. " + //$NON-NLS-1$
               "Use this for one method body; to read the whole module source use read_module_source."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Path from src/, e.g. 'CommonModules/MyModule/Module.bsl'", true) //$NON-NLS-1$
            .stringProperty(KEY_METHOD_NAME,
                "Procedure/function name (case-insensitive)", true) //$NON-NLS-1$
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
        String methodName = JsonUtils.extractStringArgument(params, KEY_METHOD_NAME);
        if (methodName != null && !methodName.isEmpty())
        {
            return "method-" + methodName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "method-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.MODULE_PATH, KEY_METHOD_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String methodName = JsonUtils.extractStringArgument(params, KEY_METHOD_NAME);

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
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

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
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        List<String> allLines;
        try
        {
            allLines = BslModuleUtils.readFileLines(file);
        }
        catch (Exception e)
        {
            Activator.logWarning("readMethodViaEmf: failed to read file, using getText(): " + e.getMessage()); //$NON-NLS-1$
            // Fallback: use getText() from EMF node (may include doc-comment)
            return readMethodFromEmfText(method, projectName, modulePath, startLine, endLine);
        }

        // Include doc-comment block preceding the method keyword
        int docStartLine = findDocCommentStart(allLines, startLine);

        // Clamp range to file boundaries
        int from = Math.max(1, docStartLine);
        int to = Math.min(allLines.size(), endLine);

        // Build signature info
        String typeStr = method instanceof Function ? TYPE_FUNCTION : TYPE_PROCEDURE; //$NON-NLS-1$ //$NON-NLS-2$

        // Find containing region
        String region = BslModuleUtils.findRegionForLine(allLines, startLine);

        // Whole-MODULE revision token (not just this method) so the caller can round-trip
        // it into write_module_source's expectedHash. Same canonical text read_module_source
        // hashes (readFileText, \n-normalized) so the tokens agree across both read tools.
        // Best-effort: a re-read failure just omits the field rather than failing the read.
        String contentHash = computeModuleHash(file);

        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put(KEY_MODULE, modulePath);
        if (contentHash != null)
        {
            fm.put("contentHash", contentHash); //$NON-NLS-1$
        }
        fm.put(KEY_METHOD, method.getName())
            .put("type", typeStr) //$NON-NLS-1$
            .put(KEY_EXPORT, method.isExport())
            .put(KEY_START_LINE, from)
            .put(KEY_END_LINE, to)
            .put("totalLines", allLines.size()); //$NON-NLS-1$

        if (region != null)
        {
            fm.put("region", region); //$NON-NLS-1$
        }

        StringBuilder sb = new StringBuilder();
        sb.append(BSL_FENCE_OPEN);
        for (int i = from - 1; i < to; i++)
        {
            sb.append(allLines.get(i)).append('\n');
        }
        sb.append(FENCE_CLOSE);

        // Extension interception: if this is a core method intercepted by an extension
        // (or an extension method that intercepts a core one), note it below the code.
        // Best-effort — only the EMF path has the resolved Method model.
        String interception = InterceptionUtils.methodFooter(module, method);
        if (interception != null)
        {
            sb.append(interception);
        }

        return fm.wrapContent(sb.toString());
    }

    /**
     * Fallback approach: Read method using text search.
     */
    private String readMethodViaText(String projectName, String modulePath, String methodName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (!file.exists())
        {
            return ToolResult.error("File not found: src/" + modulePath).toJson(); //$NON-NLS-1$
        }

        try
        {
            List<String> allLines = BslModuleUtils.readFileLines(file);

            // Locate the method via the shared text-scan fallback.
            BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(allLines, methodName);
            if (!tm.found)
            {
                return BslModuleUtils.buildTextMethodNotFoundResponse(methodName, modulePath, tm.allMethodNames);
            }

            int methodStart = tm.startLine;
            int methodEnd = tm.endLine;
            String typeStr = tm.isFunction ? TYPE_FUNCTION : TYPE_PROCEDURE; //$NON-NLS-1$ //$NON-NLS-2$

            // Detect export flag
            boolean isExport = false;
            for (int i = methodStart; i <= methodEnd; i++)
            {
                Matcher startMatcher = BslModuleUtils.METHOD_START_PATTERN.matcher(allLines.get(i));
                if (startMatcher.find())
                {
                    String afterMethod = allLines.get(i).substring(startMatcher.end());
                    int closeParen = afterMethod.indexOf(')');
                    if (closeParen >= 0)
                    {
                        String afterParen = afterMethod.substring(closeParen + 1);
                        isExport = afterParen.matches("(?iu)\\s*(?:\u042d\u043a\u0441\u043f\u043e\u0440\u0442|Export)\\s*"); //$NON-NLS-1$
                    }
                    break;
                }
            }

            // Find containing region
            String region = BslModuleUtils.findRegionForLine(allLines, methodStart + 1);

            // Whole-MODULE revision token (see readMethodViaEmf) for the expectedHash
            // round-trip; same canonical text read_module_source hashes.
            String contentHash = computeModuleHash(file);

            FrontMatter fm = FrontMatter.create()
                .put(McpKeys.PROJECT_NAME, projectName)
                .put(KEY_MODULE, modulePath);
            if (contentHash != null)
            {
                fm.put("contentHash", contentHash); //$NON-NLS-1$
            }
            fm.put(KEY_METHOD, methodName)
                .put("type", typeStr) //$NON-NLS-1$
                .put(KEY_EXPORT, isExport)
                .put(KEY_START_LINE, methodStart + 1)
                .put(KEY_END_LINE, methodEnd + 1)
                .put("totalLines", allLines.size()); //$NON-NLS-1$

            if (region != null)
            {
                fm.put("region", region); //$NON-NLS-1$
            }

            StringBuilder sb = new StringBuilder();
            sb.append(BSL_FENCE_OPEN);
            for (int i = methodStart; i <= methodEnd; i++)
            {
                sb.append(allLines.get(i)).append('\n');
            }
            sb.append(FENCE_CLOSE);

            return fm.wrapContent(sb.toString());
        }
        catch (Exception e)
        {
            return ToolResult.error("reading file: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // ========== Helper methods ==========

    /**
     * Computes the WHOLE-module optimistic-lock token for the {@code expectedHash}
     * round-trip into write_module_source. Reads the same canonical text
     * read_module_source hashes ({@code readFileText}, {@code \n}-normalized) so a method
     * read and a module read of the same file yield the same token. Best-effort: a read
     * failure returns {@code null} so the field is simply omitted rather than failing the
     * method read.
     *
     * @param file the module file
     * @return the token, or {@code null} when the file could not be read
     */
    private String computeModuleHash(IFile file)
    {
        try
        {
            return ContentHash.of(BslModuleUtils.readFileText(file).replace("\r\n", "\n")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("read_method_source: contentHash unavailable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Fallback: format method source from EMF getText() when file reading fails.
     * Note: getText() may include doc-comments, so line numbers may be inaccurate.
     */
    private String readMethodFromEmfText(Method method, String projectName, String modulePath,
        int startLine, int endLine)
    {
        String sourceText = BslModuleUtils.getSourceText(method);
        String typeStr = method instanceof Function ? TYPE_FUNCTION : TYPE_PROCEDURE; //$NON-NLS-1$ //$NON-NLS-2$

        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put(KEY_MODULE, modulePath)
            .put(KEY_METHOD, method.getName())
            .put("type", typeStr) //$NON-NLS-1$
            .put(KEY_EXPORT, method.isExport())
            .put(KEY_START_LINE, startLine)
            .put(KEY_END_LINE, endLine);

        StringBuilder sb = new StringBuilder();
        if (sourceText != null)
        {
            sb.append(BSL_FENCE_OPEN);
            sb.append(sourceText);
            if (!sourceText.endsWith("\n")) //$NON-NLS-1$
            {
                sb.append('\n');
            }
            sb.append(FENCE_CLOSE);
        }

        return fm.wrapContent(sb.toString());
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
        // Delegate to the shared ADJACENCY-policy helper (stop at first blank or
        // non-comment line) so all read-tools share one boundary rule.
        return BslModuleUtils.findDocCommentStartLine(allLines, methodKeywordLine);
    }
}
