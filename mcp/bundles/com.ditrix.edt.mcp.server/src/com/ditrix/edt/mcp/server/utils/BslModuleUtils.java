/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for loading BSL modules and working with BSL AST.
 * Shared between get_module_structure, read_method_source, and get_method_call_hierarchy tools.
 */
public final class BslModuleUtils
{
    private BslModuleUtils()
    {
        // Utility class
    }

    /** Dummy BSL URI for IResourceServiceProvider lookup */
    public static final URI BSL_LOOKUP_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    /** Regex for BSL method start (Процедура/Функция / Procedure/Function). Group 1 = method name, group 2 = params text after '(' */
    public static final Pattern METHOD_START_PATTERN = Pattern.compile(
        "^\\s*(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s+(\\S+?)\\s*\\((.*)$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for BSL method end (КонецПроцедуры/КонецФункции / EndProcedure/EndFunction) */
    public static final Pattern METHOD_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndProcedure|EndFunction)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for function keyword check (Функция / Function) */
    public static final Pattern FUNC_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\s", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for region start (#Область / #Region) */
    public static final Pattern REGION_START_PATTERN = Pattern.compile(
        "^\\s*#(?:\u041e\u0431\u043b\u0430\u0441\u0442\u044c|Region)\\s+(\\S+)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for region end (#КонецОбласти / #EndRegion) */
    public static final Pattern REGION_END_PATTERN = Pattern.compile(
        "^\\s*#(?:\u041a\u043e\u043d\u0435\u0446\u041e\u0431\u043b\u0430\u0441\u0442\u0438|EndRegion)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * The EDT source-folder name. EDT lays a configuration out under
     * {@code <project>/src/...} and uses the same name internally (its own
     * {@code SRC_FOLDER_NAME} constant). Kept here as the single source of truth for
     * the assumption that the module tools previously inlined as the literal
     * {@code "src"}.
     */
    public static final String SOURCE_FOLDER = "src"; //$NON-NLS-1$

    /**
     * Resolves a module path (relative to the project's source folder) to an IFile.
     * Centralizes the source-folder assumption that every module tool previously
     * inlined as {@code project.getFile(new Path("src").append(path))}.
     * <p>
     * Resolution tries {@link #SOURCE_FOLDER} ({@code src/}) first - the EDT
     * convention - and, only if nothing exists there, falls back to scanning the
     * project's other top-level folders, so a project laid out under a non-standard
     * source folder still resolves. When the module is found nowhere, the
     * conventional {@code src/} handle is returned so the caller's own
     * "file not found" message points at the expected location.
     * <p>
     * The returned file is NOT guaranteed to exist; each caller keeps its own
     * existence check and tool-specific error message.
     *
     * @param project    the EDT project
     * @param modulePath path from the source folder, e.g.
     *                   "CommonModules/MyModule/Module.bsl" (no leading "src/")
     * @return the resolved IFile (may not exist)
     */
    public static IFile resolveModuleFile(IProject project, String modulePath)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return null;
        }
        // Absolute filesystem path: resolve by location among workspace files,
        // independent of the project. This is the single module resolver that
        // accepts BOTH a src/-relative path and an absolute path (the form the
        // debug tools pass); BreakpointUtils.resolveModuleFile delegates here.
        if (looksLikeAbsolutePath(modulePath))
        {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
                .findFilesForLocationURI(new File(modulePath).toURI());
            return files.length > 0 ? files[0] : null;
        }
        if (project == null)
        {
            return null;
        }
        IFile inSourceFolder = project.getFile(new Path(SOURCE_FOLDER).append(modulePath));
        if (inSourceFolder.exists())
        {
            return inSourceFolder;
        }
        try
        {
            for (IResource member : project.members())
            {
                if (member.getType() == IResource.FOLDER && !SOURCE_FOLDER.equals(member.getName()))
                {
                    IFile candidate = ((IFolder) member).getFile(new Path(modulePath));
                    if (candidate.exists())
                    {
                        return candidate;
                    }
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error scanning project folders for module " + modulePath, e); //$NON-NLS-1$
        }
        return inSourceFolder;
    }

    /**
     * Heuristic: a string is treated as an absolute filesystem path if it starts
     * with a slash, a backslash, or matches a Windows drive prefix like {@code C:}.
     * Used by {@link #resolveModuleFile(IProject, String)} to accept both
     * src/-relative and absolute module paths.
     *
     * @param s the candidate path
     * @return true if it looks like an absolute path
     */
    public static boolean looksLikeAbsolutePath(String s)
    {
        if (s == null || s.isEmpty())
        {
            return false;
        }
        char c0 = s.charAt(0);
        if (c0 == '/' || c0 == '\\')
        {
            return true;
        }
        return s.length() >= 2 && s.charAt(1) == ':';
    }

    /**
     * Loads BSL Module EMF model via BmAwareResourceSetProvider.
     * Tries ServiceTracker first, falls back to IResourceServiceProvider (Guice injector).
     *
     * @param project the EDT project
     * @param modulePath path from src/, e.g. "CommonModules/MyModule/Module.bsl"
     * @return loaded Module or null if not found
     */
    public static Module loadModule(IProject project, String modulePath)
    {
        // Try to obtain BmAwareResourceSetProvider
        BmAwareResourceSetProvider resourceSetProvider = Activator.getDefault().getResourceSetProvider();

        // Fallback: obtain via IResourceServiceProvider (Guice injector) —
        // BmAwareResourceSetProvider may not be registered as OSGi service
        if (resourceSetProvider == null)
        {
            Activator.logInfo("BmAwareResourceSetProvider not found via ServiceTracker, trying IResourceServiceProvider"); //$NON-NLS-1$
            try
            {
                IResourceServiceProvider rsp =
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_LOOKUP_URI);
                if (rsp != null)
                {
                    resourceSetProvider = rsp.get(BmAwareResourceSetProvider.class);
                }
            }
            catch (Exception e)
            {
                Activator.logError("Failed to get BmAwareResourceSetProvider via IResourceServiceProvider", e); //$NON-NLS-1$
            }
        }

        if (resourceSetProvider == null)
        {
            Activator.logWarning("BmAwareResourceSetProvider not available (neither ServiceTracker nor IResourceServiceProvider)"); //$NON-NLS-1$
            return null;
        }

        ResourceSet resourceSet = resourceSetProvider.get(project);
        if (resourceSet == null)
        {
            Activator.logWarning("ResourceSet is null for project: " + project.getName()); //$NON-NLS-1$
            return null;
        }

        // Use createPlatformResourceURI for proper encoding (handles Cyrillic paths)
        URI uri = URI.createPlatformResourceURI(project.getName() + "/" + SOURCE_FOLDER + "/" + modulePath, true); //$NON-NLS-1$ //$NON-NLS-2$
        Activator.logInfo("Loading BSL module: " + uri.toString()); //$NON-NLS-1$

        try
        {
            Resource resource = resourceSet.getResource(uri, true);
            if (resource == null)
            {
                Activator.logWarning("Resource is null for URI: " + uri); //$NON-NLS-1$
                return null;
            }
            if (resource.getContents().isEmpty())
            {
                Activator.logWarning("Resource contents empty for URI: " + uri); //$NON-NLS-1$
                return null;
            }
            EObject root = resource.getContents().get(0);
            if (root instanceof Module)
            {
                return (Module) root;
            }
            Activator.logWarning("Resource root is " + root.getClass().getName() + ", not Module for: " + uri); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to load BSL module: " + uri, e); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Finds a method by name (case-insensitive) in a Module.
     *
     * @param module the BSL module
     * @param methodName the method name to find
     * @return found Method or null
     */
    public static Method findMethod(Module module, String methodName)
    {
        if (module == null || methodName == null)
        {
            return null;
        }

        for (Method method : module.allMethods())
        {
            if (methodName.equalsIgnoreCase(method.getName()))
            {
                return method;
            }
        }

        return null;
    }

    /**
     * Location of a method found by text/regex scan (the fallback used when the
     * EMF model is unavailable). Line numbers are 0-indexed; {@link #startLine}
     * already includes any adjacent doc-comment block.
     */
    public static final class TextMethod
    {
        public final boolean found;
        public final int startLine;
        public final int endLine;
        public final String matchedName;
        public final boolean isFunction;
        public final List<String> allMethodNames;

        TextMethod(boolean found, int startLine, int endLine, String matchedName,
            boolean isFunction, List<String> allMethodNames)
        {
            this.found = found;
            this.startLine = startLine;
            this.endLine = endLine;
            this.matchedName = matchedName;
            this.isFunction = isFunction;
            this.allMethodNames = allMethodNames;
        }
    }

    /**
     * Locates a method by name via a text/regex scan (case-insensitive), the
     * shared fallback for read_method_source and go_to_definition when the EMF
     * model is unavailable. The returned {@code startLine} includes any adjacent
     * doc-comment block (via {@link #findDocCommentStartLine}); {@code endLine} is
     * the EndProcedure/EndFunction line (or the last line if unterminated).
     * {@code allMethodNames} lists every method found, for a not-found response.
     *
     * @param allLines   the module source lines (0-indexed)
     * @param methodName the method name to find (case-insensitive)
     * @return a {@link TextMethod}; {@code found} is false when the method is absent
     */
    public static TextMethod findMethodViaText(List<String> allLines, String methodName)
    {
        int methodStart = -1;
        int methodEnd = -1;
        String matchedName = null;
        boolean isFunction = false;
        List<String> allMethodNames = new ArrayList<>();

        for (int i = 0; i < allLines.size(); i++)
        {
            Matcher startMatcher = METHOD_START_PATTERN.matcher(allLines.get(i));
            if (startMatcher.find())
            {
                String foundName = startMatcher.group(1);
                allMethodNames.add(foundName);

                if (foundName.equalsIgnoreCase(methodName))
                {
                    methodStart = i;
                    matchedName = foundName;
                    isFunction = FUNC_KEYWORD_PATTERN.matcher(allLines.get(i)).find();
                }
            }

            if (methodStart >= 0 && methodEnd < 0)
            {
                if (METHOD_END_PATTERN.matcher(allLines.get(i)).find())
                {
                    methodEnd = i;
                    break;
                }
            }
        }

        if (methodStart < 0)
        {
            return new TextMethod(false, -1, -1, null, false, allMethodNames);
        }
        if (methodEnd < 0)
        {
            methodEnd = allLines.size() - 1;
        }

        // Include the doc-comment block preceding the method keyword.
        int docStart = findDocCommentStartLine(allLines, methodStart + 1) - 1;
        return new TextMethod(true, docStart, methodEnd, matchedName, isFunction, allMethodNames);
    }

    /**
     * Builds the standard "method not found" response listing the available
     * methods, shared by the text-scan fallback of read_method_source and
     * go_to_definition.
     *
     * @param methodName     the requested method name
     * @param modulePath     the module path (for the message)
     * @param allMethodNames the methods that were found
     * @return the markdown error string
     */
    public static String buildTextMethodNotFoundResponse(String methodName, String modulePath,
        List<String> allMethodNames)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Method '").append(methodName).append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Available methods** (").append(allMethodNames.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : allMethodNames)
        {
            sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /**
     * Reads all lines from an IFile with UTF-8 BOM detection.
     * BSL files in EDT are typically saved as UTF-8 with BOM.
     *
     * @param file the IFile to read
     * @return list of lines
     * @throws Exception if reading fails
     */
    public static List<String> readFileLines(IFile file) throws Exception
    {
        // Try IFile.getContents() first (workspace API); fall back to filesystem
        // if workspace is not synchronized (common in large projects)
        InputStream rawIs;
        try
        {
            rawIs = file.getContents();
        }
        catch (Exception e)
        {
            // Fallback: read directly from filesystem, bypassing workspace sync
            java.io.File fsFile = file.getLocation() != null
                ? file.getLocation().toFile() : null;
            if (fsFile == null || !fsFile.exists())
            {
                throw e; // Re-throw original if filesystem path not available
            }
            rawIs = new FileInputStream(fsFile);
        }

        List<String> lines = new ArrayList<>();
        // Wrap in BufferedInputStream to support mark/reset for BOM detection;
        // rawIs is closed by try-with-resources since BufferedInputStream wraps it
        try (InputStream input = new BufferedInputStream(rawIs))
        {
            // Detect UTF-8 BOM (EF BB BF)
            input.mark(3);
            byte[] bom = new byte[3];
            int bomRead = input.read(bom);
            boolean isUtf8Bom = bomRead == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
            if (!isUtf8Bom)
            {
                input.reset();
            }
            // BSL files in EDT are always UTF-8
            String charset = "UTF-8"; //$NON-NLS-1$
            if (!isUtf8Bom)
            {
                try
                {
                    charset = file.getCharset();
                }
                catch (Exception ce)
                {
                    charset = "UTF-8"; //$NON-NLS-1$
                }
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, charset)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Reads full text from an IFile preserving original line separators.
     * Needed when mapping TextEdit offsets to lines because EDT offsets are
     * based on raw file content rather than normalized LF-only text.
     *
     * @param file the IFile to read
     * @return full file text
     * @throws Exception if reading fails
     */
    public static String readFileText(IFile file) throws Exception
    {
        InputStream rawIs;
        try
        {
            rawIs = file.getContents();
        }
        catch (Exception e)
        {
            java.io.File fsFile = file.getLocation() != null
                ? file.getLocation().toFile() : null;
            if (fsFile == null || !fsFile.exists())
            {
                throw e;
            }
            rawIs = new FileInputStream(fsFile);
        }

        try (InputStream input = new BufferedInputStream(rawIs))
        {
            input.mark(3);
            byte[] bom = new byte[3];
            int bomRead = input.read(bom);
            boolean isUtf8Bom = bomRead == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
            if (!isUtf8Bom)
            {
                input.reset();
            }
            String charset = "UTF-8"; //$NON-NLS-1$
            if (!isUtf8Bom)
            {
                try
                {
                    charset = file.getCharset();
                }
                catch (Exception ce)
                {
                    charset = "UTF-8"; //$NON-NLS-1$
                }
            }
            try (InputStreamReader reader = new InputStreamReader(input, charset))
            {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1)
                {
                    content.append(buffer, 0, read);
                }
                return content.toString();
            }
        }
    }

    /**
     * Extracts module path from EMF URI (removes /src/ prefix).
     *
     * @param path URI path string
     * @return module path relative to src/
     */
    public static String extractModulePath(String path)
    {
        if (path == null)
        {
            return "Unknown module"; //$NON-NLS-1$
        }

        String marker = "/" + SOURCE_FOLDER + "/"; //$NON-NLS-1$ //$NON-NLS-2$
        int srcIdx = path.indexOf(marker);
        if (srcIdx >= 0)
        {
            return path.substring(srcIdx + marker.length());
        }

        return path;
    }

    /**
     * Gets the start line number of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return 1-based start line, or 0 if not found
     */
    public static int getStartLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getStartLine();
        }

        return 0;
    }

    /**
     * Gets the end line number of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return 1-based end line, or 0 if not found
     */
    public static int getEndLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getEndLine();
        }

        return 0;
    }

    /**
     * Gets the source text of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return source text or null if not found
     */
    public static String getSourceText(EObject eObject)
    {
        if (eObject == null)
        {
            return null;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getText();
        }

        return null;
    }

    /**
     * Builds an error response when a method is not found, listing all available methods.
     *
     * @param module the BSL module
     * @param modulePath the module path for display
     * @param methodName the method name that was not found
     * @return formatted error message with available methods
     */
    public static String buildMethodNotFoundResponse(Module module, String modulePath, String methodName)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Method '").append(methodName).append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<String> methodNames = new ArrayList<>();
        for (Method m : module.allMethods())
        {
            methodNames.add(m.getName());
        }

        sb.append("**Available methods** (").append(methodNames.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : methodNames)
        {
            sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    /**
     * Builds a full method signature string from EMF Method model.
     * E.g. "Function MyFunc(Param1, Val Param2 = 0) Export"
     *
     * @param method the BSL method
     * @return formatted signature string
     */
    public static String buildSignature(Method method)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(method instanceof Function ? "Function " : "Procedure "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(method.getName()).append("("); //$NON-NLS-1$
        sb.append(buildParamsString(method));
        sb.append(")"); //$NON-NLS-1$
        if (method.isExport())
        {
            sb.append(" Export"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Builds a parameters string from EMF Method model.
     * E.g. "Param1, Val Param2 = 0"
     *
     * @param method the BSL method
     * @return formatted parameters string, or "-" if no parameters
     */
    public static String buildParamsString(Method method)
    {
        StringBuilder paramsBuilder = new StringBuilder();
        EList<FormalParam> formalParams = method.getFormalParams();
        if (formalParams != null)
        {
            for (int i = 0; i < formalParams.size(); i++)
            {
                FormalParam param = formalParams.get(i);
                if (i > 0)
                {
                    paramsBuilder.append(", "); //$NON-NLS-1$
                }
                paramsBuilder.append(formatFormalParam(param));
            }
        }
        return paramsBuilder.length() > 0 ? paramsBuilder.toString() : "-"; //$NON-NLS-1$
    }

    /**
     * Formats a single formal parameter, e.g. "Val Param = 0".
     *
     * @param param the formal parameter
     * @return the parameter text without any leading separator
     */
    private static String formatFormalParam(FormalParam param)
    {
        StringBuilder builder = new StringBuilder();
        if (param.isByValue())
        {
            builder.append("Val "); //$NON-NLS-1$
        }
        builder.append(param.getName());
        if (param.getDefaultValue() != null)
        {
            String defaultText = getSourceText(param.getDefaultValue());
            if (defaultText != null)
            {
                builder.append(" = ").append(defaultText.trim()); //$NON-NLS-1$
            }
        }
        return builder.toString();
    }

    /**
     * Finds the innermost region name containing the given line.
     * Parses the file lines for #Область/#Region and #КонецОбласти/#EndRegion directives.
     *
     * @param allLines all file lines (0-indexed list)
     * @param targetLine 1-based line number
     * @return region name or null if line is not inside any region
     */
    public static String findRegionForLine(List<String> allLines, int targetLine)
    {
        if (allLines == null || targetLine < 1)
        {
            return null;
        }

        List<String> regionStack = new ArrayList<>();

        for (int i = 0; i < allLines.size(); i++)
        {
            int lineNum = i + 1;
            String line = allLines.get(i);

            Matcher startMatcher = REGION_START_PATTERN.matcher(line);
            if (startMatcher.find())
            {
                regionStack.add(startMatcher.group(1));
                if (lineNum >= targetLine)
                {
                    return regionStack.get(regionStack.size() - 1);
                }
                continue;
            }

            if (REGION_END_PATTERN.matcher(line).find())
            {
                if (lineNum >= targetLine && !regionStack.isEmpty())
                {
                    return regionStack.get(regionStack.size() - 1);
                }
                if (!regionStack.isEmpty())
                {
                    regionStack.remove(regionStack.size() - 1);
                }
                continue;
            }

            if (lineNum >= targetLine)
            {
                return regionStack.isEmpty() ? null : regionStack.get(regionStack.size() - 1);
            }
        }

        return null;
    }

    /**
     * Finds the start line of the contiguous documentation-comment block that
     * immediately precedes a method/declaration line.
     *
     * <p>Uses the ADJACENCY policy required by the 1C/EDT convention: a doc-comment
     * must be contiguous and immediately precede the declaration. Scanning stops at
     * the first blank line or first non-comment line (only consecutive lines whose
     * trimmed content starts with "//" are part of the block).
     *
     * @param sourceLines all file lines (0-indexed list)
     * @param declarationLine1Based 1-based line number of the declaration (method keyword)
     * @return 1-based line number where the doc-comment block starts, or
     *         {@code declarationLine1Based} if there is no adjacent comment
     */
    public static int findDocCommentStartLine(List<String> sourceLines, int declarationLine1Based)
    {
        if (sourceLines == null || declarationLine1Based <= 1)
        {
            return declarationLine1Based;
        }

        int idx = declarationLine1Based - 2; // 0-indexed, line before the declaration
        while (idx >= 0 && sourceLines.get(idx).trim().startsWith("//")) //$NON-NLS-1$
        {
            idx--;
        }

        int docStart = idx + 2; // convert back to 1-based
        return docStart < declarationLine1Based ? docStart : declarationLine1Based;
    }

    /**
     * Extracts the documentation-comment text that immediately precedes a
     * method/declaration line, using the ADJACENCY policy
     * (see {@link #findDocCommentStartLine(List, int)}).
     *
     * <p>Each comment line is stripped of its leading "//" and one optional space,
     * then the lines are joined with a single space. Returns {@code null} when there
     * is no adjacent comment block.
     *
     * @param sourceLines all file lines (0-indexed list)
     * @param declarationLine1Based 1-based line number of the declaration
     * @return joined comment text, or {@code null} if there is no adjacent comment
     */
    public static String extractDocCommentText(List<String> sourceLines, int declarationLine1Based)
    {
        int docStart = findDocCommentStartLine(sourceLines, declarationLine1Based);
        if (docStart >= declarationLine1Based)
        {
            return null;
        }

        List<String> commentLines = new ArrayList<>();
        // docStart..declarationLine1Based-1 are the contiguous comment lines (1-based)
        for (int line = docStart; line < declarationLine1Based; line++)
        {
            String text = sourceLines.get(line - 1).trim();
            // Strip leading // and one optional space
            String commentText = text.substring(2);
            if (commentText.startsWith(" ")) //$NON-NLS-1$
            {
                commentText = commentText.substring(1);
            }
            commentLines.add(commentText);
        }

        if (commentLines.isEmpty())
        {
            return null;
        }

        return String.join(" ", commentLines); //$NON-NLS-1$
    }
}
