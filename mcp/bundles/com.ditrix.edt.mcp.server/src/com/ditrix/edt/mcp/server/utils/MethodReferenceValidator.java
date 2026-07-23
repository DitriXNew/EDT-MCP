/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Validates a "method reference" value BEFORE it is written to a
 * {@link com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob ScheduledJob}'s {@code methodName} or an
 * {@link com._1c.g5.v8.dt.metadata.mdclass.EventSubscription EventSubscription}'s {@code handler}
 * property, so {@code modify_metadata} refuses a job/subscription pointed at a method that does not
 * exist yet - a real authoring mistake reported by a maintainer: an AI bound a ScheduledJob's
 * {@code methodName} at a function it had not created yet, and EDT accepted the value silently; the
 * mistake surfaced only at {@code update_database}, with an opaque "no such function" failure. This
 * guard forces the correct order (create the module + Exported method first, THEN bind it) by failing
 * fast, at validation time, with an actionable error.
 *
 * <p>Both properties reference a {@link CommonModule} method as {@code <ModuleName>.<MethodName>},
 * optionally prefixed with the {@code CommonModule} type token (mdclass stores an EventSubscription's
 * {@code handler} as {@code CommonModule.<ModuleName>.<MethodName>}; a bare
 * {@code <ModuleName>.<MethodName>} is the live-verified ScheduledJob {@code methodName} form, e.g.
 * {@code "Calc.Add"}). {@link #validate} runs four checks, in order, failing fast on the first that does
 * not hold:</p>
 * <ol>
 * <li>the value parses as a module/method reference (has a dot separating a module part from a method
 * name);</li>
 * <li>the referenced {@link CommonModule} exists (resolved through the shared bilingual resolver
 * {@link MetadataTypeUtils#findObject});</li>
 * <li>the referenced method exists in that module's {@code Module.bsl} source (a missing method OR a
 * missing module file are reported the same way: create the method first);</li>
 * <li>the method is {@code Export}ed;</li>
 * <li>the module has the {@code Server} flag ({@link CommonModule#isServer()}) - a scheduled job /
 * event-subscription handler always runs server-side.</li>
 * </ol>
 *
 * <p>The parse ({@link #parse}) and decision ({@link #decide}) logic is pure (no {@link IProject}, no
 * workspace access) so it is unit-testable in-JVM; only {@link #validate} touches the workspace
 * (resolving + reading the module's source file through {@link BslModuleUtils}).</p>
 */
public final class MethodReferenceValidator
{
    private MethodReferenceValidator()
    {
        // Utility class
    }

    /** The CommonModule metadata type token (canonical English), reused to detect/strip a leading prefix. */
    private static final String COMMON_MODULE_TYPE = "CommonModule"; //$NON-NLS-1$

    /**
     * Matches the BSL {@code Export} / {@code Экспорт} keyword as a
     * standalone token (never a substring of a longer identifier), case-insensitively.
     */
    // The Cyrillic keyword is written as backslash-u escapes (matches the BslModuleUtils /
    // MetadataTypeUtils convention for a REGEX, which a non-UTF-8 Tycho build could otherwise
    // corrupt). NOTE: never spell a literal backslash-u sequence inside a comment - javac
    // processes unicode escapes in comments too and a malformed one is a compile error.
    private static final Pattern EXPORT_KEYWORD_PATTERN = Pattern.compile(
        "(?:^|[^\\p{L}\\p{Nd}_])(?:" //$NON-NLS-1$
            + "\u042D\u043A\u0441\u043F\u043E\u0440\u0442" // Экспорт //$NON-NLS-1$
            + "|Export)(?:[^\\p{L}\\p{Nd}_]|$)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ---- parse (pure) -----------------------------------------------------------------------------

    /**
     * The outcome of {@link #parse}: either a ready JSON {@link #error} (the value is not a well-formed
     * module/method reference) or the split {@link #moduleName} / {@link #methodName}. Exactly one side
     * is non-null.
     */
    public static final class ParsedReference
    {
        /** A ready {@link ToolResult#error} JSON when the value does not parse, else {@code null}. */
        public final String error;

        /** The module name (type-token prefix already stripped), or {@code null} on error. */
        public final String moduleName;

        /** The method name, or {@code null} on error. */
        public final String methodName;

        private ParsedReference(String error, String moduleName, String methodName)
        {
            this.error = error;
            this.moduleName = moduleName;
            this.methodName = methodName;
        }

        static ParsedReference ofError(String error)
        {
            return new ParsedReference(error, null, null);
        }

        static ParsedReference of(String moduleName, String methodName)
        {
            return new ParsedReference(null, moduleName, methodName);
        }
    }

    /**
     * Parses a {@code <ModuleName>.<MethodName>} reference value (optionally prefixed with the
     * {@code CommonModule} / {@code ОбщийМодуль}
     * type token, e.g. {@code "CommonModule.Calc.Add"}): splits on the LAST dot into a module part and
     * the method name, then strips a leading {@code CommonModule} type-token prefix from the module part
     * when present. The prefix is detected + normalized through
     * {@link MetadataTypeUtils#toEnglishSingular} - the SAME bilingual-token resolver every type token
     * in this plugin goes through - never hand-rolled.
     *
     * @param rawValue the raw property value (the caller only invokes this for a non-empty value)
     * @param propertyLabel the property name to name in the error (e.g. {@code "methodName"} / {@code "handler"})
     * @param expectedFormat a human-readable description of the expected shape, quoted (e.g.
     *     {@code "'CommonModuleName.MethodName'"})
     * @param exampleValue a concrete example value for the error hint (e.g. {@code "Calc.Add"})
     * @return a {@link ParsedReference}; check {@link ParsedReference#error} first
     */
    public static ParsedReference parse(String rawValue, String propertyLabel, String expectedFormat,
        String exampleValue)
    {
        int dotIdx = rawValue == null ? -1 : rawValue.lastIndexOf('.');
        if (rawValue == null || dotIdx <= 0 || dotIdx == rawValue.length() - 1)
        {
            return ParsedReference.ofError(formatError(rawValue, propertyLabel, expectedFormat, exampleValue));
        }
        String modulePart = rawValue.substring(0, dotIdx);
        String methodName = rawValue.substring(dotIdx + 1);
        String moduleName = stripCommonModuleTypeToken(modulePart);
        if (moduleName.isEmpty())
        {
            return ParsedReference.ofError(formatError(rawValue, propertyLabel, expectedFormat, exampleValue));
        }
        return ParsedReference.of(moduleName, methodName);
    }

    private static String formatError(String rawValue, String propertyLabel, String expectedFormat,
        String exampleValue)
    {
        return ToolResult.error("'" + rawValue + "' is not a valid '" + propertyLabel //$NON-NLS-1$ //$NON-NLS-2$
            + "' reference: expected " + expectedFormat + " (e.g. '" + exampleValue + "').").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Strips a leading {@code CommonModule} /
     * {@code ОбщийМодуль} type-token prefix from a
     * module-part string (e.g. {@code "CommonModule.Calc"} -&gt; {@code "Calc"}), reusing
     * {@link MetadataTypeUtils#toEnglishSingular} rather than hand-rolling the bilingual match. A module
     * part with no embedded dot (nothing to strip, e.g. plain {@code "Calc"}) is returned unchanged.
     */
    private static String stripCommonModuleTypeToken(String modulePart)
    {
        int firstDot = modulePart.indexOf('.');
        if (firstDot <= 0)
        {
            return modulePart;
        }
        String prefixCandidate = modulePart.substring(0, firstDot);
        String rest = modulePart.substring(firstDot + 1);
        return COMMON_MODULE_TYPE.equals(MetadataTypeUtils.toEnglishSingular(prefixCandidate)) ? rest : modulePart;
    }

    // ---- decide (pure: module + source lines already resolved) ------------------------------------

    /**
     * Decides whether a parsed reference is a VALID, bindable method: it must exist in the module's
     * source, be {@code Export}ed, and the module must have the {@code Server} flag (a scheduled job /
     * event-subscription handler always runs server-side). Pure - takes the already-resolved
     * {@link CommonModule} + its source lines (or {@code null} lines when the source could not be read,
     * reported the SAME as "method not found": a missing file cannot host the method either) - so it is
     * unit-testable without an {@link IProject}.
     *
     * @param module the resolved common module (its {@link CommonModule#isServer()} flag is checked)
     * @param moduleName the module name, for the error text
     * @param moduleLines the module's source lines, or {@code null} when the source could not be read
     * @param methodName the method name to find
     * @param propertyLabel the property name to name in the error
     * @return a ready JSON error, or {@code null} when the reference is valid
     */
    public static String decide(CommonModule module, String moduleName, List<String> moduleLines,
        String methodName, String propertyLabel)
    {
        BslModuleUtils.TextMethod found =
            moduleLines == null ? null : BslModuleUtils.findMethodViaText(moduleLines, methodName);
        if (found == null || !found.found)
        {
            return methodNotFoundError(moduleName, methodName, propertyLabel);
        }
        if (!isExported(moduleLines, found))
        {
            return ToolResult.error("Method '" + methodName + "' in CommonModule '" + moduleName //$NON-NLS-1$ //$NON-NLS-2$
                + "' must be marked Export ('Экспорт') before it can " //$NON-NLS-1$
                + "be used as '" + propertyLabel + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (module != null && !module.isServer())
        {
            return ToolResult.error("CommonModule '" + moduleName + "' does not have the Server flag: " //$NON-NLS-1$ //$NON-NLS-2$
                + "a scheduled job / event-subscription handler always runs server-side, so give the " //$NON-NLS-1$
                + "module the Server property (set 'server' to true) before setting '" + propertyLabel //$NON-NLS-1$
                + "'.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    private static String methodNotFoundError(String moduleName, String methodName, String propertyLabel)
    {
        return ToolResult.error("Method '" + methodName + "' not found in CommonModule '" + moduleName //$NON-NLS-1$ //$NON-NLS-2$
            + "'. Create it first with write_module_source (an Exported server procedure/function), " //$NON-NLS-1$
            + "then set '" + propertyLabel + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Detects whether the found method's declaration carries {@code Export} /
     * {@code Экспорт}. {@link BslModuleUtils.TextMethod} carries no
     * export flag of its own (and its {@link BslModuleUtils.TextMethod#startLine startLine} may include
     * an adjacent doc-comment block), so the ACTUAL declaration line is re-located within
     * {@code [startLine, endLine]} via the shared {@link BslModuleUtils#METHOD_START_PATTERN}; every
     * line from there up to (and including) the first line that closes the parameter list ({@code ")"})
     * is then scanned for the keyword - covering both a single-line signature and one whose parameter
     * list wraps across multiple lines.
     */
    static boolean isExported(List<String> lines, BslModuleUtils.TextMethod found)
    {
        int declLine = findDeclarationLine(lines, found);
        for (int i = declLine; i <= found.endLine && i < lines.size(); i++)
        {
            // Mask string literals and strip a trailing inline comment BEFORE matching: neither a
            // signature comment (`Procedure Foo() // TODO: Export`) nor a parameter default carrying
            // the word (`Procedure Foo(P = "Export")`) may count as the Export modifier.
            String line = maskStringsAndStripComment(lines.get(i));
            if (EXPORT_KEYWORD_PATTERN.matcher(line).find())
            {
                return true;
            }
            if (line.indexOf(')') >= 0)
            {
                // The parameter list (and so the declaration header) is closed on this line: Export, if
                // present, must be here too (already checked above) - stop before the method BODY, so a
                // body line mentioning the word is never mistaken for the Export modifier.
                break;
            }
        }
        return false;
    }

    /**
     * MASKS double-quoted string-literal content (each in-string char becomes a space, so a parameter
     * default like {@code P = "Export"} can never look like the Export modifier, and a {@code ")"}
     * inside a string cannot fake the header's end) and truncates at the first {@code //} that is
     * OUTSIDE a string (a {@code //} inside a default string value stays masked, not treated as a
     * comment). Quote tracking is the minimal intra-line kind a declaration header needs; it does not
     * attempt multi-line literals.
     */
    static String maskStringsAndStripComment(String line)
    {
        StringBuilder masked = new StringBuilder(line.length());
        boolean inString = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '"')
            {
                inString = !inString;
                masked.append(' ');
                continue;
            }
            if (inString)
            {
                masked.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')
            {
                break;
            }
            masked.append(c);
        }
        return masked.toString();
    }

    private static int findDeclarationLine(List<String> lines, BslModuleUtils.TextMethod found)
    {
        for (int i = found.startLine; i <= found.endLine && i < lines.size(); i++)
        {
            Matcher matcher = BslModuleUtils.METHOD_START_PATTERN.matcher(lines.get(i));
            if (matcher.find() && found.matchedName.equalsIgnoreCase(matcher.group(1)))
            {
                return i;
            }
        }
        return found.startLine;
    }

    // ---- validate (the IProject-touching seam) -----------------------------------------------------

    /**
     * Full validation: parses the reference, resolves the {@link CommonModule} through the shared
     * bilingual resolver {@link MetadataTypeUtils#findObject}, reads its {@code Module.bsl} source, and
     * decides via {@link #decide}. This is the ONLY method here that touches the workspace.
     *
     * @param project the EDT project (for resolving the module's source file)
     * @param config the project's configuration (for resolving the module object)
     * @param rawValue the raw property value (the caller only invokes this for a non-empty value)
     * @param propertyLabel the property name to name in the error (e.g. {@code "methodName"} / {@code "handler"})
     * @param expectedFormat a human-readable description of the expected shape, quoted
     * @param exampleValue a concrete example value for the format-error hint
     * @return a ready JSON error, or {@code null} when the reference is valid
     */
    public static String validate(IProject project, Configuration config, String rawValue, String propertyLabel,
        String expectedFormat, String exampleValue)
    {
        ParsedReference parsed = parse(rawValue, propertyLabel, expectedFormat, exampleValue);
        if (parsed.error != null)
        {
            return parsed.error;
        }

        MdObject moduleObj = MetadataTypeUtils.findObject(config, COMMON_MODULE_TYPE, parsed.moduleName);
        if (!(moduleObj instanceof CommonModule))
        {
            return ToolResult.error("Common module '" + parsed.moduleName + "' not found (from '" + rawValue //$NON-NLS-1$ //$NON-NLS-2$
                + "'). Use get_metadata_objects to see the available common modules, or create it " //$NON-NLS-1$
                + "first.").toJson(); //$NON-NLS-1$
        }
        CommonModule module = (CommonModule)moduleObj;

        // Read the source by the RESOLVED metadata name, not the raw input: findObject resolves the
        // module tolerantly (e.g. a case difference), but the folder on disk carries the metadata
        // name's exact casing - building the path from the raw input would miss the file on a
        // case-sensitive filesystem and mis-report the method as missing.
        List<String> lines = readModuleLines(project, module.getName());
        return decide(module, parsed.moduleName, lines, parsed.methodName, propertyLabel);
    }

    /**
     * Returns the CANONICAL stored form of a VALID reference, or {@code null} when the value does not
     * parse/resolve (callers run {@link #validate} first, so {@code null} is only a defensive fallback -
     * they then keep the raw value). The canonical form uses the RESOLVED module's exact metadata name
     * (so a tolerated case/prefix variant is not serialized verbatim into the model, where the
     * platform's own resolution would then miss it):
     * <ul>
     * <li>{@code withTypePrefix == false} (a ScheduledJob's {@code methodName}):
     * {@code <ModuleName>.<MethodName>};</li>
     * <li>{@code withTypePrefix == true} (an EventSubscription's {@code handler}):
     * {@code CommonModule.<ModuleName>.<MethodName>}.</li>
     * </ul>
     */
    public static String canonicalReference(Configuration config, String rawValue, boolean withTypePrefix)
    {
        ParsedReference parsed = parse(rawValue, "", "", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (parsed.error != null)
        {
            return null;
        }
        MdObject moduleObj = MetadataTypeUtils.findObject(config, COMMON_MODULE_TYPE, parsed.moduleName);
        if (!(moduleObj instanceof CommonModule))
        {
            return null;
        }
        String base = moduleObj.getName() + "." + parsed.methodName; //$NON-NLS-1$
        return withTypePrefix ? COMMON_MODULE_TYPE + "." + base : base; //$NON-NLS-1$
    }

    /**
     * Reads a common module's {@code Module.bsl} source lines, or {@code null} when the file is missing
     * / unreadable (the caller then reports the SAME "method not found" error as an existing-but-empty
     * source would).
     */
    private static List<String> readModuleLines(IProject project, String moduleName)
    {
        IFile file = BslModuleUtils.resolveModuleFile(project,
            "CommonModules/" + moduleName + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        if (file == null || !file.exists())
        {
            return null;
        }
        try
        {
            return BslModuleUtils.readFileLines(file);
        }
        catch (Exception e)
        {
            Activator.logError("MethodReferenceValidator: failed to read " + moduleName + "/Module.bsl", e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }
}
