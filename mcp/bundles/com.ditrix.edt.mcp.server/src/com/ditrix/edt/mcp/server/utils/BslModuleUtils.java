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

    /** Regex for BSL method start (Процедура/Функция / Procedure/Function, with an optional leading
     * Асинх/Async modifier - platform async methods). Group 1 = method name, group 2 = params text after '(' */
    public static final Pattern METHOD_START_PATTERN = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\((.*)$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for a BSL procedure end. The right boundary prevents an identifier such as
     * {@code EndProcedureResult} from being mistaken for the terminator. */
    public static final Pattern PROCEDURE_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|EndProcedure)(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for a BSL function end, with the same identifier boundary as procedures. */
    public static final Pattern FUNCTION_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndFunction)(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for either BSL method terminator, kept for existing fallback consumers. */
    public static final Pattern METHOD_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndProcedure|EndFunction)(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for function keyword check (Функция / Function, optional leading Асинх/Async) */
    public static final Pattern FUNC_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\s", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A method declaration this line-based scanner cannot ADDRESS: the keyword and a name with
     * the opening parenthesis on a later line.
     * <p>
     * BSL hides the newline, so such a declaration is real - and everything here is measured in
     * whole lines, so it can be neither located nor bounded. A module holding one is therefore
     * REFUSED for method-targeted edits rather than guessed at: guessing produced a span that
     * ran through the hidden method and a duplicate-name check that did not see it.
     * </p>
     */
    public static final Pattern UNADDRESSABLE_DECLARATION_PATTERN = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)"
            + "(?!\\p{L}|\\p{N}|_)\\s+[\\p{L}_][\\p{L}\\p{N}_]*\\s*$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A bare {@code Async}/{@code Асинх} modifier on its own line, directly above the
     * declaration it applies to. It is part of the method - replacing the method without it
     * would leave the modifier behind, binding to whatever is written in its place.
     */
    private static final Pattern ASYNC_MODIFIER_LINE_PATTERN = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445|Async)\\s*$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * A declaration line that also carries a method TERMINATOR: a whole method written on one
     * physical line, and anything hiding behind it on that line.
     * <p>
     * Legal under the hidden-whitespace grammar and equally unaddressable by a whole-line
     * scanner: the closer cannot be seen by a matcher that reads a terminator only at the start
     * of a line, so the method silently borrows a later one. Named and refused instead.
     * </p>
     */
    private static final Pattern INLINE_TERMINATOR_PATTERN = Pattern.compile(
        "(?<![.\\p{L}\\p{N}_])(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndProcedure|EndFunction)(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * A declaration sharing its line with the pragma(s) that annotate it
     * ({@code &AtClient Procedure Added()}).
     * <p>
     * Legal, and equally unaddressable by a scan anchored on the keyword at line start: the
     * method is invisible to the span search AND to the duplicate-name check, so an insert could
     * add a second declaration of the same name. Refused by name instead of guessed at.
     * </p>
     */
    private static final Pattern PRAGMA_ON_DECLARATION_LINE_PATTERN = Pattern.compile(
        "^\\s*(?:&[\\p{L}\\p{N}_]+(?:\\([^)]*\\))?\\s+)+"
            + "(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)"
            + "(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
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

    /** Default charset for BSL files in EDT (always UTF-8). */
    private static final String UTF_8 = "UTF-8"; //$NON-NLS-1$

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
     * Full line span of one BSL method. Line numbers are 0-based and inclusive.
     * {@link #startLine} includes the contiguous documentation comments and
     * ampersand annotations/directives owned by the declaration;
     * {@link #declarationLine} points at Procedure/Function itself.
     */
    public static final class MethodSpan
    {
        public final int startLine;
        public final int declarationLine;
        public final int endLine;
        public final String name;
        public final boolean isFunction;
        public final boolean complete;

        MethodSpan(int startLine, int declarationLine, int endLine, String name,
            boolean isFunction, boolean complete)
        {
            this.startLine = startLine;
            this.declarationLine = declarationLine;
            this.endLine = endLine;
            this.name = name;
            this.isFunction = isFunction;
            this.complete = complete;
        }
    }

    /**
     * Finds every method declaration in source order using the shared bilingual
     * fallback patterns. Each declaration is paired only with its matching kind of
     * terminator. An unterminated declaration is returned with {@code complete=false}
     * and an end line clamped to the final source line.
     *
     * @param lines BSL module or fragment lines
     * @return all discovered method spans
     */
    public static List<MethodSpan> findMethodSpansViaText(List<String> lines)
    {
        List<MethodSpan> spans = new ArrayList<>();
        if (lines == null)
        {
            return spans;
        }

        // Every rule below reads text whose string literals and comments are blanked out: a
        // keyword inside a default value or behind a trailing comment is not code, and the
        // splice still uses the ORIGINAL lines.
        List<String> scan = BslSyntaxChecker.maskLiteralsAndComments(lines);
        for (int declarationLine = 0; declarationLine < scan.size(); declarationLine++)
        {
            Matcher startMatcher = METHOD_START_PATTERN.matcher(scan.get(declarationLine));
            if (!startMatcher.find())
            {
                continue;
            }
            // Not a declaration at all when the line above left a dangling member dot: newlines
            // are hidden, so "X = Object." and "Function And (Condition);" is the single
            // expression Object.Function - and reading it as a declaration bounds the enclosing
            // method before its real terminator, which refuses every edit of a valid module.
            if (previousMeaningfulLineEndsWithMemberDot(scan, declarationLine))
            {
                continue;
            }

            boolean isFunction = FUNC_KEYWORD_PATTERN.matcher(scan.get(declarationLine)).find();
            Pattern terminator = isFunction ? FUNCTION_END_PATTERN : PROCEDURE_END_PATTERN;
            Pattern wrongKind = isFunction ? PROCEDURE_END_PATTERN : FUNCTION_END_PATTERN;
            // The search stops at the NEXT declaration: unbounded, an unterminated method
            // borrows its neighbour's terminator, reports itself complete, and a
            // replaceMethod on it would delete that neighbour and its documentation.
            int searchLimit = nextDeclarationLine(scan, declarationLine + 1) - 1;
            int endLine =
                findTerminatorLine(scan, declarationLine, searchLimit, terminator, wrongKind);
            boolean complete = endLine >= 0;
            if (!complete)
            {
                endLine = searchLimit;
            }
            int startLine = findMethodPreambleStartLine(lines, scan, declarationLine + 1) - 1;
            spans.add(new MethodSpan(startLine, declarationLine, endLine,
                startMatcher.group(1), isFunction, complete));
        }
        return spans;
    }

    /**
     * The tail a terminator line may carry and still be owned entirely by the method:
     * whitespace, at most one statement separator, and an end-of-line comment. BSL puts
     * module-level statements AFTER the methods (Bsl.xtext {@code Module} rule), so
     * {@code EndProcedure; ModuleValue = Call();} is valid code whose terminator does NOT
     * end the line - and every span here is measured in whole lines, so without this the
     * statement would ride along inside the method span.
     */
    private static final Pattern METHOD_END_TAIL_PATTERN = Pattern.compile("^\\s*;?\\s*(?://.*)?$"); //$NON-NLS-1$

    /**
     * Where a real method DECLARATION begins at or after {@code from}, or -1.
     * <p>
     * The regex answers the shape; this answers whether the keyword is a keyword at all, by the
     * same lexical rule the terminator uses. Whitespace after a dot is hidden, so
     * {@code X = Object. Function + (1);} is a member expression and not a declaration - a
     * fixed-width lookbehind sees the space and takes the "+" for a method name.
     * </p>
     *
     * @param maskedLine the line with its literals and comments blanked
     * @param from where to start looking
     * @return the index of the keyword, or -1
     */
    private static int declarationShapeAt(String maskedLine, int from)
    {
        Matcher shape = ANY_DECLARATION_KEYWORD_PATTERN.matcher(maskedLine);
        int at = from;
        while (shape.find(at))
        {
            if (!precededByMemberDot(maskedLine, shape.start()))
            {
                return shape.start();
            }
            at = shape.start() + 1;
        }
        return -1;
    }

    /**
     * Whether the parameter list opened at or after {@code from} closes on this line.
     * <p>
     * A declaration whose list stays open - {@code Procedure Added(} with the terminator on the
     * next line - was emitted as a complete span, and the balance check only counts block
     * keywords, so invalid BSL went to disk under the exactly-one-method contract.
     * </p>
     *
     * @param maskedLine the line with its literals and comments blanked
     * @param from where the name ended
     * @return whether the list closes here
     */
    private static boolean parameterListCloses(String maskedLine, int from)
    {
        int depth = 0;
        for (int i = from; i < maskedLine.length(); i++)
        {
            char ch = maskedLine.charAt(i);
            depth += ch == '(' ? 1 : 0;
            depth -= ch == ')' ? 1 : 0;
            if (depth == 0 && ch == ')')
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the line is nothing but complete pragmas: an ampersand name, optionally followed by
     * a closed argument list, repeated.
     * <p>
     * Walked rather than matched. The regex that did this repeated a group containing two runs of
     * optional whitespace, which CodeQL flagged as exponential backtracking on a line of many
     * "&x" - and it was right: nothing decides which run a space belongs to. A walk has one
     * answer per character and no backtracking at all.
     * </p>
     *
     * @param trimmed the trimmed, masked line
     * @return whether it holds only complete pragmas
     */
    private static boolean pragmasOnly(String trimmed)
    {
        int i = 0;
        while (i < trimmed.length())
        {
            if (trimmed.charAt(i) != '&')
            {
                return false;
            }
            i++;
            int nameStart = i;
            while (i < trimmed.length()
                && (Character.isLetterOrDigit(trimmed.charAt(i)) || trimmed.charAt(i) == '_'))
            {
                i++;
            }
            if (i == nameStart)
            {
                return false;
            }
            i = skipWhitespace(trimmed, i);
            if (i < trimmed.length() && trimmed.charAt(i) == '(')
            {
                int close = trimmed.indexOf(')', i);
                if (close < 0)
                {
                    return false;
                }
                i = close + 1;
            }
            i = skipWhitespace(trimmed, i);
        }
        return true;
    }

    /**
     * @param text the text to walk
     * @param from where to start
     * @return the first index at or after {@code from} that is not whitespace
     */
    private static int skipWhitespace(String text, int from)
    {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i)))
        {
            i++;
        }
        return i;
    }

    /**
     * Whether the ampersand line cannot be owned as a whole line of pragmas.
     * <p>
     * Three ways it cannot: its argument list stays open, its arguments continue on the line
     * below - {@code &Instead} then {@code (} - or it carries something after the pragma that is
     * not one. All three are boundaries a whole-line scanner would have to guess, and both wrong
     * guesses destroy something: the annotation moves to an inserted method, or the code riding
     * on the line is deleted with the target.
     * </p>
     *
     * @param masked all lines with their literals and comments blanked
     * @param index the line to judge
     * @return whether it must be refused
     */
    private static boolean unaddressablePragmaLine(List<String> masked, int index)
    {
        String trimmed = masked.get(index).trim();
        if (!trimmed.startsWith("&")) //$NON-NLS-1$
        {
            return false;
        }
        if (!pragmasOnly(trimmed))
        {
            // Either an open argument list or something else riding along. A declaration after
            // the pragma has its own refusal and message, so it is left to that one.
            return !PRAGMA_ON_DECLARATION_LINE_PATTERN.matcher(trimmed).find();
        }
        for (int next = index + 1; next < masked.size(); next++)
        {
            String below = masked.get(next).trim();
            if (below.isEmpty())
            {
                continue;
            }
            // A complete-looking pragma whose arguments were put on the NEXT line.
            return below.startsWith("("); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * A line holding nothing but a declaration keyword, its name having been put on the next
     * line. Hidden whitespace makes that a real method, and no anchored rule here can see it.
     */
    private static final Pattern BARE_DECLARATION_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s*$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A method DECLARATION anywhere on a line, used only to look PAST the opener of a
     * declaration line.
     * <p>
     * The shape matters, not the keyword: BSL hides line breaks, so a body may begin on the
     * declaration line, and a reserved word is a legal member name after a dot (grammar rule
     * {@code ExtName}) - {@code Procedure Target() X = Object.Function();} is one ordinary
     * method. So the keyword must not be preceded by a dot or by word characters, and must be
     * followed by a name and an opening parenthesis, the way a declaration is written.
     * </p>
     */
    private static final Pattern ANY_DECLARATION_KEYWORD_PATTERN = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s+[\\p{L}_][\\p{L}\\p{N}_]*\\s*(?:\\(|$)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * The first line that declares a SECOND method after the one it opens with, or -1.
     * <p>
     * Every rule here is anchored at the start of a line, so a declaration written after the
     * opener - {@code Procedure A() Function B()} - is invisible to all of them: the scan reports
     * one method, the balance check sees matching pairs, and a method nested inside another
     * method gets written as if it were valid. Such a line is refused instead.
     * </p>
     *
     * @param lines BSL module or fragment lines
     * @return the 0-based line index, or -1 when every declaration line opens exactly one
     */
    public static int secondDeclarationOnLine(List<String> lines)
    {
        if (lines == null)
        {
            return -1;
        }
        List<String> scan = BslSyntaxChecker.maskLiteralsAndComments(lines);
        for (int i = 0; i < scan.size(); i++)
        {
            Matcher opener = METHOD_START_PATTERN.matcher(scan.get(i));
            // Only a DECLARATION line is examined, and only past its own opener: a statement
            // line may legitimately carry the keyword twice, since a reserved word is a legal
            // member name after a dot (grammar rule ExtName).
            if (!opener.find())
            {
                continue;
            }
            if (declarationShapeAt(scan.get(i), opener.end(1)) >= 0)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reports whether the line is a method terminator that owns its whole line.
     *
     * @param line source line to test (may be {@code null})
     * @param terminator {@link #PROCEDURE_END_PATTERN} or {@link #FUNCTION_END_PATTERN}
     * @return {@code true} when the line holds only that terminator (plus an optional
     *         separator/comment tail)
     */
    public static boolean isMethodTerminatorLine(String line, Pattern terminator)
    {
        if (line == null)
        {
            return false;
        }
        Matcher matcher = terminator.matcher(line);
        if (!matcher.find())
        {
            return false;
        }
        return METHOD_END_TAIL_PATTERN.matcher(line.substring(matcher.end())).matches();
    }

    /**
     * Index of the first method declaration at or after {@code from}, or the line count
     * when there is none. A method cannot contain another declaration, so this is the
     * hard upper bound of a method's terminator search.
     * <p>
     * A keyword that opens a line right after one ending in a member-access dot is NOT a
     * declaration: the grammar's {@code ExtName} rule lists the reserved words legal as member
     * names - {@code Процедура} and {@code Функция} among them - and whitespace after the dot
     * includes a line break, so {@code Объект.} followed by {@code Функция()} is one expression.
     * Bounding there would end the enclosing method before its terminator and refuse a legal
     * write. Same reasoning, and the same helper shape, as {@code BslSyntaxChecker.isMemberName}.
     * </p>
     */
    private static int nextDeclarationLine(List<String> lines, int from)
    {
        for (int i = Math.max(0, from); i < lines.size(); i++)
        {
            // The same exemption the span scan makes: a reserved member reached across a line
            // break is not a declaration, and taking it for one shortens the search window and
            // leaves the real method looking unterminated.
            if (METHOD_START_PATTERN.matcher(lines.get(i)).find()
                && !previousMeaningfulLineEndsWithMemberDot(lines, i))
            {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * The first line holding a declaration this scanner cannot address, or {@code -1}: one whose
     * opening parenthesis is on a later line, or one sharing its line with a terminator.
     *
     * @param lines module or fragment lines
     * @return the 0-based line index, or {@code -1} when there is none
     */
    public static int unaddressableDeclarationLine(List<String> lines)
    {
        Unaddressable found = unaddressable(lines);
        return found == null ? -1 : found.line;
    }

    /** A line a whole-line scanner cannot address, and the reason it cannot. */
    public static final class Unaddressable
    {
        /** The 0-based line. */
        public final int line;

        /** What is wrong with it, as a phrase that completes "the module has ...". */
        public final String what;

        /** What the caller has to change, as an imperative sentence. */
        public final String fix;

        Unaddressable(int line, String what, String fix)
        {
            this.line = line;
            this.what = what;
            this.fix = fix;
        }
    }

    /**
     * The first line this scanner cannot address, with the reason - or {@code null}.
     * <p>
     * Six shapes, one rule: a whole-line scanner may only edit what it can DELIMIT. Each of
     * these hides a declaration or a terminator from every anchored rule in this class, and a
     * span that guesses past one either rebinds an annotation or deletes code that belongs to
     * somebody else. The reason travels with the line because the six need six different fixes.
     * </p>
     *
     * @param lines BSL module or fragment lines
     * @return the finding, or {@code null} when every line can be addressed
     */
    public static Unaddressable unaddressable(List<String> lines)
    {
        if (lines == null)
        {
            return null;
        }
        List<String> scan = BslSyntaxChecker.maskLiteralsAndComments(lines);
        for (int i = 0; i < scan.size(); i++)
        {
            String line = scan.get(i);
            // NONE of the rules below apply when the line above left a dangling member dot: the
            // newline is hidden, so this line continues an expression - "Value = Object." then
            // "EndFunction(Arg);" is one member call, and "Function And" is one member name. Each
            // rule judged that line on its own and refused a valid module.
            if (previousMeaningfulLineEndsWithMemberDot(scan, i))
            {
                continue;
            }
            if (UNADDRESSABLE_DECLARATION_PATTERN.matcher(line).find())
            {
                return new Unaddressable(i, "a method declaration split across lines", //$NON-NLS-1$
                    "put the declaration and its opening parenthesis on one line"); //$NON-NLS-1$
            }
            // A terminator that does NOT own its line, wherever it stands: on the declaration
            // line or after a body statement. The span scan only recognises a closer that owns
            // its line, so an inline one is invisible to it - the span reads past it, borrows a
            // later closer, and a replaceMethod deletes everything in between while the balance
            // check sees a healed result.
            Matcher opener = METHOD_START_PATTERN.matcher(line);
            if (opener.find())
            {
                if (BslSyntaxChecker.isBlockKeyword(opener.group(1)))
                {
                    return new Unaddressable(i, "a method named after a block keyword", //$NON-NLS-1$
                        "give the method a name that is not " + opener.group(1)); //$NON-NLS-1$
                }
                if (!parameterListCloses(line, opener.end(1)))
                {
                    return new Unaddressable(i, "a declaration whose parameter list does not close", //$NON-NLS-1$
                        "close the parameter list on the declaration line"); //$NON-NLS-1$
                }
            }
            if (hasInlineTerminator(line) && !isMethodTerminatorLine(line, METHOD_END_PATTERN))
            {
                return new Unaddressable(i, "a method terminator sharing its line with other code", //$NON-NLS-1$
                    "keep the terminator on a line of its own"); //$NON-NLS-1$
            }
            // A pragma whose argument list does not close on its own line. A whole-line scanner
            // cannot say where such a pragma ends - four different shapes of it have each been
            // delimited wrongly here - and getting the boundary wrong either rebinds the
            // annotation to an inserted method or deletes the code above it. Refused instead.
            if (unaddressablePragmaLine(scan, i))
            {
                return new Unaddressable(i, "a pragma this scanner cannot delimit", //$NON-NLS-1$
                    "put the pragma and its arguments on one line of their own"); //$NON-NLS-1$
            }
            if (PRAGMA_ON_DECLARATION_LINE_PATTERN.matcher(line).find())
            {
                return new Unaddressable(i, "a pragma sharing its line with the declaration", //$NON-NLS-1$
                    "put the pragma on the line above the declaration"); //$NON-NLS-1$
            }
            // A declaration the ANCHORED scan cannot see: written after something else on the
            // line - the tail of a split pragma, say - it is invisible to every rule here, so
            // the module holds a method this tool cannot count, address or check for duplicate
            // names. Refusing is the only honest answer a whole-line scanner has.
            if (declarationShapeAt(line, 0) >= 0 && !METHOD_START_PATTERN.matcher(line).find())
            {
                return new Unaddressable(i, "a declaration written after something else on the line", //$NON-NLS-1$
                    "start the declaration on a line of its own"); //$NON-NLS-1$
            }
            // The keyword ALONE, with its name on the next line. Not refused when the line
            // above left a dangling member dot: a reserved word is a legal member name, so
            // "Value = Object." and "Procedure" is one expression and not a declaration.
            if (BARE_DECLARATION_KEYWORD_PATTERN.matcher(line).matches()
                && !previousMeaningfulLineEndsWithMemberDot(scan, i))
            {
                return new Unaddressable(i, "a declaration whose name is on the next line", //$NON-NLS-1$
                    "put the declaration and its name on one line"); //$NON-NLS-1$
            }
        }
        return null;
    }
    private static int findTerminatorLine(List<String> lines, int from, int to, Pattern terminator,
        Pattern wrongKind)
    {
        for (int i = from; i <= to; i++)
        {
            // A reserved word is a legal MEMBER name (grammar rule ExtName), and the newline
            // after the dot is hidden, so "Object." on one line and "EndProcedure" on the next is
            // one expression rather than a method end. Skipping it here fails SAFE, unlike the
            // same exemption on the declaration side: a terminator missed by mistake ends the
            // span later or leaves it incomplete, and an incomplete span is a refusal.
            if (previousMeaningfulLineEndsWithMemberDot(lines, i))
            {
                continue;
            }
            if (isMethodTerminatorLine(lines.get(i), terminator))
            {
                return i;
            }
            if (i > from && isMethodTerminatorLine(lines.get(i), wrongKind))
            {
                // The WRONG closer, reached before the right one: the declaration is malformed,
                // and reading past it would hand this span a terminator belonging to nothing -
                // with whatever stands between them, module-level statements included, deleted
                // by a replaceMethod. An incomplete span is a refusal, which is the safe end.
                return -1;
            }
        }
        return -1;
    }

    /**
     * Whether the line ends a method ON that line, as opposed to merely naming a terminator.
     * <p>
     * A reserved word is a legal MEMBER name and whitespace around the dot is hidden trivia, so
     * {@code Procedure Target() X = Object. EndProcedure;} names one and ends nothing. Judged
     * lexically - the nearest non-space character before the word - rather than by a
     * fixed-width lookbehind, which sees the space and not the dot.
     * </p>
     *
     * @param line the line, with literals and comments already masked
     * @return whether a real inline terminator stands on it
     */
    private static boolean hasInlineTerminator(String line)
    {
        Matcher terminator = INLINE_TERMINATOR_PATTERN.matcher(line);
        while (terminator.find())
        {
            if (!precededByMemberDot(line, terminator.start()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the nearest non-space character before {@code index} is a member-access dot.
     *
     * @param line the line
     * @param index where the word begins
     * @return whether the word is a member name
     */
    private static boolean precededByMemberDot(String line, int index)
    {
        int i = index - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i)))
        {
            i--;
        }
        return i >= 0 && line.charAt(i) == '.' && isMemberAccessDot(line, i);
    }

    /**
     * Whether the dot at {@code dot} takes a member from something, as opposed to ending a
     * numeric literal.
     * <p>
     * One rule for both directions of this question - the dangling dot at the end of a line and
     * the dot in front of a word on it - because they are the same question. "Value = 1." ends
     * in a dot and takes a member from nothing, and reading it as an access suppressed a real
     * terminator.
     * </p>
     *
     * @param line the line
     * @param dot the index of the dot
     * @return whether it is a member access
     */
    private static boolean isMemberAccessDot(String line, int dot)
    {
        int base = dot - 1;
        while (base >= 0 && Character.isWhitespace(line.charAt(base)))
        {
            base--;
        }
        if (base < 0)
        {
            return false;
        }
        char before = line.charAt(base);
        if (before == ')' || before == ']')
        {
            return true;
        }
        // An identifier may END in a digit (Object1.), so walk the token back and judge it by
        // its FIRST character: a run that starts with a digit is a numeric literal.
        int i = base;
        while (i >= 0 && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_'))
        {
            i--;
        }
        if (i == base)
        {
            return false;
        }
        char first = line.charAt(i + 1);
        return Character.isLetter(first) || first == '_';
    }

    /**
     * Whether the last line that carries anything before {@code index} ends in a member-access
     * dot.
     * <p>
     * Blank and comment-only lines are crossed, because whitespace and comments are hidden
     * terminals: {@code Value = Object.}, a comment line, then {@code EndFunction;} is the one
     * expression {@code Object.EndFunction} and not a method end. Looking only at the line
     * immediately above would read the blank and conclude the dot was gone.
     * </p>
     *
     * @param lines the lines being scanned, with literals and comments already masked
     * @param index the line under examination
     * @return whether the preceding code line left a dangling member dot
     */
    private static boolean previousMeaningfulLineEndsWithMemberDot(List<String> lines, int index)
    {
        for (int i = index - 1; i >= 0; i--)
        {
            String line = lines.get(i);
            if (line != null && !line.trim().isEmpty())
            {
                return endsWithMemberDot(line);
            }
        }
        return false;
    }

    /**
     * Whether the line's last meaningful character is a member-access dot. An end-of-line
     * comment is dropped first; everything else is judged as written.
     */
    private static boolean endsWithMemberDot(String line)
    {
        if (line == null)
        {
            return false;
        }
        int comment = line.indexOf("//"); //$NON-NLS-1$
        String code = comment >= 0 ? line.substring(0, comment) : line;
        String trimmed = code.stripTrailing();
        return trimmed.endsWith(".") //$NON-NLS-1$
            && isMemberAccessDot(trimmed, trimmed.length() - 1);
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
        MethodSpan found = null;
        List<String> allMethodNames = new ArrayList<>();

        for (MethodSpan span : findMethodSpansViaText(allLines))
        {
            allMethodNames.add(span.name);
            if (found == null && span.name.equalsIgnoreCase(methodName))
            {
                found = span;
            }
        }

        if (found == null)
        {
            return new TextMethod(false, -1, -1, null, false, allMethodNames);
        }
        return new TextMethod(true, found.startLine, found.endLine, found.name,
            found.isFunction, allMethodNames);
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
        // Wrap in BufferedInputStream to support mark/reset for BOM detection; // NOSONAR explanatory comment, not commented-out code
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
            String charset = UTF_8;
            if (!isUtf8Bom)
            {
                try
                {
                    charset = file.getCharset();
                }
                catch (Exception ce)
                {
                    charset = UTF_8;
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
            String charset = UTF_8;
            if (!isUtf8Bom)
            {
                try
                {
                    charset = file.getCharset();
                }
                catch (Exception ce)
                {
                    charset = UTF_8;
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

            RegionScanResult result = scanRegionLine(line, lineNum, targetLine, regionStack);
            if (result.resolved)
            {
                return result.regionName;
            }
        }

        return null;
    }

    /**
     * Carries the outcome of scanning a single line in {@link #findRegionForLine}.
     * When {@link #resolved} is {@code true} the scan has reached the target line
     * and {@link #regionName} (which may be {@code null}) is the final answer;
     * otherwise the loop must continue.
     */
    private static final class RegionScanResult
    {
        final boolean resolved;
        final String regionName;

        private RegionScanResult(boolean resolved, String regionName)
        {
            this.resolved = resolved;
            this.regionName = regionName;
        }

        /** Keep scanning: the target line has not been reached yet. */
        static RegionScanResult keepScanning()
        {
            return new RegionScanResult(false, null);
        }

        /** Target reached: {@code regionName} (possibly null) is the final answer. */
        static RegionScanResult ofResolved(String regionName)
        {
            return new RegionScanResult(true, regionName);
        }
    }

    /**
     * Processes one line of the region scan, mutating {@code regionStack} for
     * #Region/#EndRegion directives. Returns a {@link RegionScanResult} telling the
     * caller whether the target line has been reached (and, if so, the enclosing
     * region name) or whether it should keep scanning.
     *
     * @param line       the raw source line
     * @param lineNum    1-based number of {@code line}
     * @param targetLine the 1-based line whose region is sought
     * @param regionStack the running stack of open region names (mutated in place)
     * @return the scan result for this line
     */
    private static RegionScanResult scanRegionLine(String line, int lineNum, int targetLine,
        List<String> regionStack)
    {
        RegionScanResult startResult = handleRegionStart(line, lineNum, targetLine, regionStack);
        if (startResult != null)
        {
            return startResult;
        }

        RegionScanResult endResult = handleRegionEnd(line, lineNum, targetLine, regionStack);
        if (endResult != null)
        {
            return endResult;
        }

        // Plain line: resolve once the target is reached, otherwise keep scanning.
        if (lineNum >= targetLine)
        {
            return RegionScanResult.ofResolved(topRegion(regionStack));
        }
        return RegionScanResult.keepScanning();
    }

    /**
     * Handles a #Region/#Область start directive: pushes the region name and, if the
     * target line has been reached, resolves to the (now innermost) region.
     *
     * @param line       the raw source line
     * @param lineNum    1-based number of {@code line}
     * @param targetLine the 1-based line whose region is sought
     * @param regionStack the running stack of open region names (mutated in place)
     * @return a scan result if this is a start directive, or {@code null} otherwise
     */
    private static RegionScanResult handleRegionStart(String line, int lineNum, int targetLine,
        List<String> regionStack)
    {
        Matcher startMatcher = REGION_START_PATTERN.matcher(line);
        if (!startMatcher.find())
        {
            return null;
        }
        regionStack.add(startMatcher.group(1));
        if (lineNum >= targetLine)
        {
            return RegionScanResult.ofResolved(topRegion(regionStack));
        }
        return RegionScanResult.keepScanning();
    }

    /**
     * Handles a #EndRegion/#КонецОбласти end directive: if the target line has been
     * reached it resolves to the innermost open region; otherwise it pops the stack.
     *
     * @param line       the raw source line
     * @param lineNum    1-based number of {@code line}
     * @param targetLine the 1-based line whose region is sought
     * @param regionStack the running stack of open region names (mutated in place)
     * @return a scan result if this is an end directive, or {@code null} otherwise
     */
    private static RegionScanResult handleRegionEnd(String line, int lineNum, int targetLine,
        List<String> regionStack)
    {
        if (!REGION_END_PATTERN.matcher(line).find())
        {
            return null;
        }
        if (lineNum >= targetLine && !regionStack.isEmpty())
        {
            return RegionScanResult.ofResolved(topRegion(regionStack));
        }
        if (!regionStack.isEmpty())
        {
            regionStack.remove(regionStack.size() - 1);
        }
        return RegionScanResult.keepScanning();
    }

    /**
     * Returns the innermost (top-of-stack) open region name, or {@code null} when no
     * region is currently open.
     *
     * @param regionStack the stack of open region names
     * @return the top region name, or {@code null} if the stack is empty
     */
    private static String topRegion(List<String> regionStack)
    {
        return regionStack.isEmpty() ? null : regionStack.get(regionStack.size() - 1);
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
     * Finds the beginning of the contiguous preamble owned by a BSL method:
     * documentation-comment lines and ampersand annotations/directives immediately
     * above the Procedure/Function declaration. Code ends the preamble, so a
     * branch/region directive is never absorbed into the method.
     *
     * <p>Blank lines are crossed only to reach a comment/annotation group that CONTAINS an
     * annotation, wherever in the group it sits. Whitespace and comments are hidden terminals
     * in the BSL grammar and {@code Procedure} carries its {@code pragmas} directly, so
     * {@code &AtClient}, an explaining comment, a blank line and the declaration are one unit -
     * inserting between them would rebind the directive to the inserted method. A group of
     * comments alone stays detached, keeping the documentation adjacency policy.
     *
     * @param sourceLines all file lines (0-indexed list)
     * @param declarationLine1Based 1-based declaration line
     * @return 1-based first owned line, or the declaration line itself
     */
    public static int findMethodPreambleStartLine(List<String> sourceLines,
        int declarationLine1Based)
    {
        return findMethodPreambleStartLine(sourceLines,
            sourceLines == null ? null : BslSyntaxChecker.maskLiteralsAndComments(sourceLines),
            declarationLine1Based);
    }

    /**
     * The same walk, told what the lines look like with their literals and comments blanked.
     * <p>
     * Two views on purpose: the COMMENT policy above needs the raw text - a comment above a
     * declaration is documentation and must stay recognisable - while everything that is really
     * a lexical question (a pragma's parentheses, a bare async modifier carrying a trailing
     * comment) has to be asked of the masked text, or punctuation inside a comment or a literal
     * decides it.
     * </p>
     *
     * @param sourceLines all file lines (0-indexed list)
     * @param masked those lines with literals and comments blanked
     * @param declarationLine1Based 1-based declaration line
     * @return 1-based first owned line, or the declaration line itself
     */
    private static int findMethodPreambleStartLine(List<String> sourceLines, List<String> masked,
        int declarationLine1Based)
    {
        if (sourceLines == null || declarationLine1Based <= 1)
        {
            return declarationLine1Based;
        }

        int idx = declarationLine1Based - 2;
        int owned = declarationLine1Based;
        while (idx >= 0)
        {
            String trimmed = sourceLines.get(idx).trim();
            if (isTrivia(trimmed, masked.get(idx).trim()))
            {
                owned = idx + 1;
                idx--;
                continue;
            }
            if (!trimmed.isEmpty())
            {
                // Code ends the preamble. A pragma with arguments split across lines would end
                // it here too - its tail looks like code - but such a module never reaches this
                // point: unaddressableDeclarationLine refuses it, because a whole-line scanner
                // cannot say where that pragma ends and both wrong answers destroy something.
                break;
            }
            // A blank run: cross it only to an ANNOTATION above it - "&AtClient", an explaining
            // comment, a blank line and the declaration are one unit. Ownership then starts at
            // that annotation and stops: a comment ABOVE it, on the far side of a blank line, is
            // as likely to be the previous method's footer, and claiming it would let
            // replaceMethod delete a note that belongs to somebody else. A group of comments
            // alone is not crossed at all, which is the documentation adjacency policy.
            int annotation = topAnnotationAboveBlankRun(sourceLines, masked, idx);
            if (annotation < 0)
            {
                break;
            }
            owned = annotation + 1;
            break;
        }
        return owned;
    }

    /**
     * A line that binds to the declaration below it: a comment, an ampersand annotation, or a
     * bare async modifier (which is part of the declaration itself, not decoration around it).
     */
    private static boolean isTrivia(String trimmedLine, String trimmedMasked)
    {
        // The async modifier is asked of the MASKED line: "Async // explanation" is still one
        // async declaration, and an end-anchored pattern on the raw text fails on the comment -
        // which would let an insertBefore land between the modifier and its method, binding the
        // modifier to the inserted one and making the target synchronous.
        return trimmedLine.startsWith("//") || trimmedLine.startsWith("&") //$NON-NLS-1$ //$NON-NLS-2$
            || ASYNC_MODIFIER_LINE_PATTERN.matcher(trimmedMasked).matches();
    }

    /**
     * Given the index of a blank line, finds the TOPMOST annotation in the contiguous
     * comment/annotation group directly above the blank run.
     * <p>
     * The annotation is what may be crossed to, because a directive keeps binding to its
     * declaration across hidden trivia; the comments above it are not, because on the far side of
     * a blank line they read as the previous method's trailing note just as easily.
     * </p>
     *
     * @return the 0-based index of that annotation, or {@code -1} when the group has none and the
     *         blank run must not be crossed
     */
    private static int topAnnotationAboveBlankRun(List<String> sourceLines, List<String> masked,
        int blankIndex)
    {
        int probe = blankIndex;
        while (probe >= 0 && sourceLines.get(probe).trim().isEmpty())
        {
            probe--;
        }
        int topAnnotation = -1;
        while (probe >= 0)
        {
            String trimmed = sourceLines.get(probe).trim();
            String trimmedMasked = masked.get(probe).trim();
            if (!isTrivia(trimmed, trimmedMasked))
            {
                break;
            }
            if (trimmed.startsWith("&") //$NON-NLS-1$
                || ASYNC_MODIFIER_LINE_PATTERN.matcher(trimmedMasked).matches())
            {
                topAnnotation = probe;
            }
            probe--;
        }
        return topAnnotation;
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
