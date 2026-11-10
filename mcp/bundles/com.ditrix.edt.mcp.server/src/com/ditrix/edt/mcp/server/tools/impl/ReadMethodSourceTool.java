/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.utils.ReadToolExecution;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker;
import com.ditrix.edt.mcp.server.utils.InterceptionUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.Refusals;

/**
 * Tool to read procedures/functions from a BSL module by name.
 * Instead of reading 60,000 lines, reads only the needed 50.
 * <p>
 * {@code methodName} alone keeps the single-method answer (front matter describing that one
 * method); {@code methodNames} reads several methods of the module in one call: the module is
 * loaded, read and hashed ONCE, every name is located against that one copy, and one front
 * matter describes the module, followed by one section per found method in request order.
 * The EMF path and the text-scan fallback both reduce their findings to {@link LocatedMethod}
 * and share {@link #render}.
 */
public class ReadMethodSourceTool implements IMcpTool
{
    public static final String NAME = "read_method_source"; //$NON-NLS-1$

    /** Input param: name of the procedure/function to read. */
    private static final String KEY_METHOD_NAME = "methodName"; //$NON-NLS-1$
    /** Input param: several procedure/function names read in one call. */
    private static final String KEY_METHOD_NAMES = "methodNames"; //$NON-NLS-1$
    /** Upper bound of {@code methodNames}; a longer list is refused, never truncated. */
    static final int MAX_METHOD_NAMES = 50;
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$
    private static final String KEY_METHOD = "method"; //$NON-NLS-1$
    private static final String KEY_EXPORT = "export"; //$NON-NLS-1$
    private static final String KEY_START_LINE = "startLine"; //$NON-NLS-1$
    private static final String KEY_END_LINE = "endLine"; //$NON-NLS-1$
    private static final String KEY_CONTENT_HASH = "contentHash"; //$NON-NLS-1$
    private static final String KEY_TOTAL_LINES = "totalLines"; //$NON-NLS-1$
    private static final String KEY_REGION = "region"; //$NON-NLS-1$
    private static final String KEY_METHODS_FOUND = "methodsFound"; //$NON-NLS-1$
    private static final String KEY_METHODS_NOT_FOUND = "methodsNotFound"; //$NON-NLS-1$
    private static final String TYPE_PROCEDURE = "Procedure"; //$NON-NLS-1$
    private static final String TYPE_FUNCTION = "Function"; //$NON-NLS-1$
    private static final String BSL_FENCE_OPEN = "```bsl\n"; //$NON-NLS-1$
    private static final String FENCE_CLOSE = "```\n"; //$NON-NLS-1$
    /** Separator of the one-line method summary under each section heading. */
    private static final String SUMMARY_SEPARATOR = " · "; //$NON-NLS-1$
    /** Appended to "methodName is required": the refusal names both ways to pass a name. */
    private static final String METHOD_NAME_REQUIRED_DETAIL =
        " (or pass methodNames, an array of up to " + MAX_METHOD_NAMES + " names)." //$NON-NLS-1$ //$NON-NLS-2$
            + " Use get_module_structure to list the module's procedures and functions."; //$NON-NLS-1$

    /** Where the tool reads the module from; a seam so the read count is testable. */
    private final ModuleAccess access;

    /** Creates the tool reading through the EDT platform. */
    public ReadMethodSourceTool()
    {
        this(PLATFORM_ACCESS);
    }

    /**
     * Creates the tool reading through the given access.
     *
     * @param access the module access
     */
    ReadMethodSourceTool(ModuleAccess access)
    {
        this.access = access;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Inspect the source of one or more BSL procedures or functions. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('read_method_source')."; //$NON-NLS-1$
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
                "Procedure/function name (case-insensitive)") //$NON-NLS-1$
            .stringArrayProperty(KEY_METHOD_NAMES,
                "Several procedure/function names read in one call (up to " + MAX_METHOD_NAMES //$NON-NLS-1$
                    + "); use instead of methodName. One of the two is required.") //$NON-NLS-1$
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
        if (!listedNames(params).isEmpty())
        {
            String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
            if (modulePath != null && !modulePath.isEmpty())
            {
                return "methods-" //$NON-NLS-1$
                    + modulePath.replace("/", "-").replace("\\", "-").toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            }
            return "methods-source.md"; //$NON-NLS-1$
        }
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
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.MODULE_PATH);
        if (err != null)
        {
            return err;
        }
        List<String> listed = listedNames(params);
        if (listed.size() > MAX_METHOD_NAMES)
        {
            return ToolResult.error("methodNames accepts at most " + MAX_METHOD_NAMES + " names, got " //$NON-NLS-1$ //$NON-NLS-2$
                + listed.size() + ". Split them over several calls, or read the whole module with " //$NON-NLS-1$
                + "read_module_source.").toJson(); //$NON-NLS-1$
        }
        boolean multi = !listed.isEmpty();
        if (!multi)
        {
            err = JsonUtils.requireArgument(params, KEY_METHOD_NAME, METHOD_NAME_REQUIRED_DETAIL);
            if (err != null)
            {
                return err;
            }
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String methodName = JsonUtils.extractStringArgument(params, KEY_METHOD_NAME);
        List<String> names = multi ? requestedNames(methodName, listed) : List.of(methodName);

        // Try EMF approach first (on UI thread)
        AtomicReference<String> resultRef = new AtomicReference<>();

        ReadToolExecution.dispatch(() -> {
            try
            {
                String result = ReadToolExecution.readOnHost(projectName, NAME,
                    () -> readMethodViaEmf(projectName, modulePath, names, multi));
                resultRef.set(result);
            }
            catch (Exception e)
            {
                String refusal = Refusals.messageOf(e);
                if (refusal != null)
                {
                    resultRef.set(ToolResult.error(refusal).toJson());
                    return;
                }
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
        return readMethodViaText(projectName, modulePath, names, multi);
    }

    /**
     * The non-blank entries of {@code methodNames}, trimmed, in request order (duplicates kept,
     * so the size check counts what the caller sent). Accepts a JSON array or a comma list.
     *
     * @param params the call arguments (may be {@code null})
     * @return the listed names, never {@code null}
     */
    static List<String> listedNames(Map<String, String> params)
    {
        List<String> names = new ArrayList<>();
        List<String> raw = JsonUtils.extractArrayArgument(params, KEY_METHOD_NAMES);
        if (raw != null)
        {
            for (String name : raw)
            {
                if (name != null && !name.isBlank())
                {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    /**
     * The names to read: {@code methodName} (when given) followed by {@code methodNames}, in
     * request order, with duplicates dropped by the lookup's own rule: a name is a duplicate
     * when it {@link String#equalsIgnoreCase equalsIgnoreCase} a name already kept - the very
     * comparison {@link BslModuleUtils#findMethod} and the text scan use - so it would only
     * render the same method twice. (Lower-casing is NOT that rule: a capital I with a dot
     * above and a plain i are equal ignoring case yet lower-case differently.) At most
     * {@link #MAX_METHOD_NAMES} names, so the quadratic check is cheap.
     *
     * @param methodName the single name (may be {@code null} or blank)
     * @param listed the listed names
     * @return the union, first spelling of each name kept
     */
    static List<String> requestedNames(String methodName, List<String> listed)
    {
        List<String> names = new ArrayList<>();
        if (methodName != null && !methodName.isBlank())
        {
            names.add(methodName);
        }
        for (String name : listed)
        {
            if (!containsIgnoringCase(names, name))
            {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether {@code kept} holds a name {@code name} equals ignoring case (the lookup's rule). */
    private static boolean containsIgnoringCase(List<String> kept, String name)
    {
        for (String each : kept)
        {
            if (each.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Primary approach: Read method using BSL EMF model.
     */
    private String readMethodViaEmf(String projectName, String modulePath, List<String> names, boolean multi)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        return readViaEmf(ctx.project(), projectName, modulePath, names, multi);
    }

    /**
     * EMF path over a resolved project: one module load, one file read, one hash.
     *
     * @return the answer, or {@code null} to fall back to the text scan
     */
    String readViaEmf(IProject project, String projectName, String modulePath, List<String> names,
        boolean multi)
    {
        Module module = access.load(project, modulePath);
        if (module == null)
        {
            // EMF not available - return null to trigger fallback
            return null;
        }

        List<Method> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : names)
        {
            Method method = BslModuleUtils.findMethod(module, name);
            if (method == null)
            {
                missing.add(name);
            }
            else
            {
                found.add(method);
            }
        }
        if (found.isEmpty())
        {
            // None found - list all available methods
            return BslModuleUtils.buildMethodsNotFoundResponse(names, modulePath,
                BslModuleUtils.methodNames(module));
        }

        // Read file to get actual source lines (getText() may include preceding doc-comments)
        IFile file = access.resolve(project, modulePath);
        List<String> allLines;
        try
        {
            allLines = access.readLines(file);
        }
        catch (Exception e)
        {
            Activator.logWarning("readMethodViaEmf: failed to read file, using getText(): " + e.getMessage()); //$NON-NLS-1$
            if (multi)
            {
                // Several sections need real line numbers: let the text path report the read.
                return null;
            }
            // Fallback: use getText() from EMF node (may include doc-comment)
            Method method = found.get(0);
            return readMethodFromEmfText(method, projectName, modulePath, access.startLine(method),
                access.endLine(method));
        }

        // Whole-MODULE revision token (not just this method) so the caller can round-trip
        // it into write_module_source's expectedHash. Same canonical text read_module_source
        // hashes (readFileText, \n-normalized) so the tokens agree across both read tools.
        // Best-effort: a re-read failure just omits the field rather than failing the read.
        String contentHash = access.moduleHash(file);

        List<LocatedMethod> located = new ArrayList<>();
        for (Method method : found)
        {
            int startLine = access.startLine(method);
            // Include doc-comment block preceding the method keyword; clamp to the file.
            int from = Math.max(1, BslModuleUtils.findDocCommentStartLine(allLines, startLine));
            int to = Math.min(allLines.size(), access.endLine(method));
            // Extension interception: if this is a core method intercepted by an extension
            // (or an extension method that intercepts a core one), note it below the code.
            // Best-effort — only the EMF path has the resolved Method model.
            located.add(new LocatedMethod(method.getName(), method instanceof Function, method.isExport(),
                from, to, BslModuleUtils.findRegionForLine(allLines, startLine),
                access.footer(module, method)));
        }
        return render(projectName, modulePath, contentHash, allLines, located, missing, multi);
    }

    /**
     * Fallback approach: Read method using text search.
     */
    private String readMethodViaText(String projectName, String modulePath, List<String> names, boolean multi)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        return readViaText(ctx.project(), projectName, modulePath, names, multi);
    }

    /**
     * Text-scan path over a resolved project: one file read, one method scan, one hash.
     */
    String readViaText(IProject project, String projectName, String modulePath, List<String> names,
        boolean multi)
    {
        try
        {
            IFile file = access.resolve(project, modulePath);
            if (!file.exists())
            {
                return ToolResult.error("File not found: src/" + modulePath).toJson(); //$NON-NLS-1$
            }

            List<String> allLines = access.readLines(file);

            // Locate every method via ONE pass of the shared text-scan fallback; mask the module
            // once for the export reads below, however many names are requested.
            List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(allLines);
            List<String> masked = BslSyntaxChecker.maskLiteralsAndComments(allLines);
            List<LocatedMethod> located = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String name : names)
            {
                BslModuleUtils.MethodSpan span = firstSpanNamed(spans, name);
                if (span == null)
                {
                    missing.add(name);
                    continue;
                }
                // The single-name answer echoes the requested spelling (historical shape);
                // the sections name the method as declared.
                located.add(new LocatedMethod(multi ? span.name : name, span.isFunction,
                    BslModuleUtils.isExportDeclaration(masked, span.declarationLine, span.endLine),
                    span.startLine + 1,
                    span.endLine + 1, BslModuleUtils.findRegionForLine(allLines, span.startLine + 1), null));
            }
            if (located.isEmpty())
            {
                List<String> allMethodNames = new ArrayList<>();
                for (BslModuleUtils.MethodSpan span : spans)
                {
                    allMethodNames.add(span.name);
                }
                return BslModuleUtils.buildMethodsNotFoundResponse(names, modulePath, allMethodNames);
            }

            // Whole-MODULE revision token (see readViaEmf) for the expectedHash
            // round-trip; same canonical text read_module_source hashes.
            String contentHash = access.moduleHash(file);
            return render(projectName, modulePath, contentHash, allLines, located, missing, multi);
        }
        catch (Exception e)
        {
            return ToolResult.error("reading file: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // ========== Rendering (shared by the EMF and text paths) ==========

    /**
     * One method found in the module, reduced to what the answer prints. Lines are 1-based
     * and inclusive; {@code from} already includes the doc-comment.
     */
    static final class LocatedMethod
    {
        final String name;
        final boolean function;
        final boolean export;
        final int from;
        final int to;
        final String region;
        final String footer;

        LocatedMethod(String name, boolean function, boolean export, int from, int to, String region,
            String footer)
        {
            this.name = name;
            this.function = function;
            this.export = export;
            this.from = from;
            this.to = to;
            this.region = region;
            this.footer = footer;
        }
    }

    /**
     * Renders the answer: the single-method shape for {@code methodName}, the sectioned shape
     * for {@code methodNames}.
     */
    private static String render(String projectName, String modulePath, String contentHash,
        List<String> allLines, List<LocatedMethod> located, List<String> missing, boolean multi)
    {
        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put(KEY_MODULE, modulePath);
        if (contentHash != null)
        {
            fm.put(KEY_CONTENT_HASH, contentHash);
        }
        StringBuilder sb = new StringBuilder();
        if (!multi)
        {
            LocatedMethod method = located.get(0);
            fm.put(KEY_METHOD, method.name)
                .put("type", typeOf(method)) //$NON-NLS-1$
                .put(KEY_EXPORT, method.export)
                .put(KEY_START_LINE, method.from)
                .put(KEY_END_LINE, method.to)
                .put(KEY_TOTAL_LINES, allLines.size());
            if (method.region != null)
            {
                fm.put(KEY_REGION, method.region);
            }
            appendBody(sb, allLines, method);
            return fm.wrapContent(sb.toString());
        }

        fm.put(KEY_TOTAL_LINES, allLines.size())
            .put(KEY_METHODS_FOUND, located.size());
        if (!missing.isEmpty())
        {
            fm.putList(KEY_METHODS_NOT_FOUND, missing);
        }
        for (int i = 0; i < located.size(); i++)
        {
            LocatedMethod method = located.get(i);
            if (i > 0)
            {
                sb.append('\n');
            }
            sb.append("### ").append(method.name).append('\n'); //$NON-NLS-1$
            sb.append(typeOf(method));
            if (method.export)
            {
                sb.append(SUMMARY_SEPARATOR).append(KEY_EXPORT);
            }
            sb.append(SUMMARY_SEPARATOR).append("lines ").append(method.from).append('-').append(method.to); //$NON-NLS-1$
            if (method.region != null)
            {
                sb.append(SUMMARY_SEPARATOR).append("region ").append(method.region); //$NON-NLS-1$
            }
            sb.append('\n');
            appendBody(sb, allLines, method);
        }
        return fm.wrapContent(sb.toString());
    }

    /** The {@code ```bsl} fence with the method's lines, then the interception footer if any. */
    private static void appendBody(StringBuilder sb, List<String> allLines, LocatedMethod method)
    {
        sb.append(BSL_FENCE_OPEN);
        for (int i = method.from - 1; i < method.to; i++)
        {
            sb.append(allLines.get(i)).append('\n');
        }
        sb.append(FENCE_CLOSE);
        if (method.footer != null)
        {
            sb.append(method.footer);
        }
    }

    private static String typeOf(LocatedMethod method)
    {
        return method.function ? TYPE_FUNCTION : TYPE_PROCEDURE;
    }

    // ========== Helper methods ==========

    /** The first scanned method whose name equals {@code name} ignoring case, or {@code null}. */
    private static BslModuleUtils.MethodSpan firstSpanNamed(List<BslModuleUtils.MethodSpan> spans, String name)
    {
        for (BslModuleUtils.MethodSpan span : spans)
        {
            if (span.name.equalsIgnoreCase(name))
            {
                return span;
            }
        }
        return null;
    }

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
    private static String computeModuleHash(IFile file)
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

    // ========== Module access (seam) ==========

    /**
     * Where the tool reads the module from. The platform implementation delegates to the
     * shared {@link BslModuleUtils} / {@link InterceptionUtils} helpers; tests count the calls
     * to prove one load, one read and one hash per call however many names are requested.
     */
    interface ModuleAccess
    {
        /** @return the parsed module, or {@code null} when the BSL model is unavailable */
        Module load(IProject project, String modulePath);

        /** @return the module file (may not exist) */
        IFile resolve(IProject project, String modulePath);

        /** @return the module's lines */
        List<String> readLines(IFile file) throws Exception;

        /** @return the whole-module {@code contentHash}, or {@code null} when unreadable */
        String moduleHash(IFile file);

        /** @return the 1-based line of the method keyword */
        int startLine(Method method);

        /** @return the 1-based last line of the method */
        int endLine(Method method);

        /** @return the interception footer, or {@code null} */
        String footer(Module module, Method method);
    }

    /** The production access: the EDT BSL model and the workspace file. */
    private static final ModuleAccess PLATFORM_ACCESS = new ModuleAccess()
    {
        @Override
        public Module load(IProject project, String modulePath)
        {
            return BslModuleUtils.loadModule(project, modulePath);
        }

        @Override
        public IFile resolve(IProject project, String modulePath)
        {
            return BslModuleUtils.resolveModuleFile(project, modulePath);
        }

        @Override
        public List<String> readLines(IFile file) throws Exception
        {
            return BslModuleUtils.readFileLines(file);
        }

        @Override
        public String moduleHash(IFile file)
        {
            return computeModuleHash(file);
        }

        @Override
        public int startLine(Method method)
        {
            return BslModuleUtils.getStartLine(method);
        }

        @Override
        public int endLine(Method method)
        {
            return BslModuleUtils.getEndLine(method);
        }

        @Override
        public String footer(Module module, Method method)
        {
            return InterceptionUtils.methodFooter(module, method);
        }
    };
}
