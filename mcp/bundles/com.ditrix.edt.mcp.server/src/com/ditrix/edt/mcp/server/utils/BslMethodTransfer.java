/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure line operations behind copying named methods out of one BSL module and deleting named methods from a
 * module (#978): no I/O, no EDT model. Methods are found by the same whole-line scanner and the same span
 * boundaries the method-targeted modes of {@code write_module_source} use
 * ({@link BslModuleUtils#findMethodSpansViaText}: the preamble of {@code //} comments, {@code &} annotations
 * and an async modifier belongs to the method), and names are compared the way those modes compare them,
 * {@link String#equalsIgnoreCase}. Every check runs before any result is built, so a refusal leaves nothing
 * half done.
 */
public final class BslMethodTransfer
{
    /** Upper bound of {@code methodNames}; a longer list is refused, never truncated. */
    public static final int MAX_METHOD_NAMES = 100;

    /** How many method names a not-found refusal lists before it stops. */
    private static final int AVAILABLE_NAMES_SHOWN = 50;

    /**
     * An annotation that binds a method to a method of ANOTHER module - the interception annotations of a
     * configuration extension, in both spellings: {@code &Before}/{@code &Перед}, {@code &After}/{@code &После},
     * {@code &Around}/{@code &Вместо}, {@code &ChangeAndValidate}/{@code &ИзменениеИКонтроль}. Found ANYWHERE on a
     * masked preamble line: several pragmas may share one line ({@code &AtServer &Before("Base")}).
     */
    private static final Pattern INTERCEPTION_ANNOTATION = Pattern.compile(
        "&\\s*(?:Before|After|Around|ChangeAndValidate" //$NON-NLS-1$
            + "|\u041F\u0435\u0440\u0435\u0434|\u041F\u043E\u0441\u043B\u0435|\u0412\u043C\u0435\u0441\u0442\u043E" //$NON-NLS-1$
            + "|\u0418\u0437\u043C\u0435\u043D\u0435\u043D\u0438\u0435\u0418\u041A\u043E\u043D\u0442\u0440\u043E\u043B\u044C)" //$NON-NLS-1$
            + "(?![\\p{L}\\p{N}_])", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private BslMethodTransfer()
    {
        // utility class
    }

    /**
     * The outcome of {@link #extract} or {@link #remove}: an error message (plain text, for
     * {@code ToolResult.error}) or the lines, never both.
     */
    public static final class Result
    {
        /** The refusal, or {@code null}. */
        public final String error;

        /** The copied method lines ({@link #extract}) or the module lines after the deletion ({@link #remove}). */
        public final List<String> lines;

        /** The methods, as DECLARED in the module, in request order. */
        public final List<String> methods;

        /** {@link #remove} only: the deleted names still called in the rest of the module, with the first line. */
        public final List<String> stillCalled;

        private Result(String error, List<String> lines, List<String> methods, List<String> stillCalled)
        {
            this.error = error;
            this.lines = lines;
            this.methods = methods;
            this.stillCalled = stillCalled;
        }

        static Result refused(String error)
        {
            return new Result(error, null, List.of(), List.of());
        }

        /** @return whether the operation was refused */
        public boolean failed()
        {
            return error != null;
        }
    }

    /**
     * Refuses a {@code methodNames} list that is empty, too long, holds a blank entry or names one method
     * twice (equal ignoring case - the lookup's own rule).
     *
     * @param names the names as sent (may be {@code null})
     * @return the refusal, or {@code null} when the list is usable
     */
    public static String validateNames(List<String> names)
    {
        if (names == null || names.isEmpty())
        {
            return "methodNames is required with fromModulePath and with mode=deleteMethods: " //$NON-NLS-1$
                + "pass the names of the methods as an array."; //$NON-NLS-1$
        }
        if (names.size() > MAX_METHOD_NAMES)
        {
            return "methodNames accepts at most " + MAX_METHOD_NAMES + " names, got " + names.size() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Split the move into several calls."; //$NON-NLS-1$
        }
        for (int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            if (name == null || name.isBlank())
            {
                return "methodNames holds an empty name at position " + (i + 1) + ". Nothing was written."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (int j = 0; j < i; j++)
            {
                if (names.get(j).equalsIgnoreCase(name))
                {
                    return "methodNames names '" + name + "' twice. Pass each method once."; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return null;
    }

    /**
     * The text of the named methods of {@code sourceLines}, in request order, each with its preamble, separated
     * by one blank line. The lines are taken as they are - no character is normalised - so the copy is the
     * source's text line for line (the line delimiter is the target's, chosen when it is written).
     * <p>
     * Refused, naming the method: a module this scanner cannot address; a name declared nowhere, more than
     * once (preprocessor branches) or without its terminator; a method inside or crossing an
     * {@code #If}/{@code #Если} block (the copy would drop the condition); a method carrying an interception
     * annotation (it binds the method to its own module).
     *
     * @param sourceLines the source module's lines
     * @param names the validated names (see {@link #validateNames})
     * @param sourceLabel the source module, for the messages
     * @return the copied lines and the declared names, or the refusal
     */
    public static Result extract(List<String> sourceLines, List<String> names, String sourceLabel)
    {
        String unaddressable = unaddressableModule(sourceLines, "fromModulePath '" + sourceLabel + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        if (unaddressable != null)
        {
            return Result.refused(unaddressable);
        }
        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(sourceLines);
        Lookup lookup = lookup(spans, names, "fromModulePath '" + sourceLabel + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        if (lookup.error != null)
        {
            return Result.refused(lookup.error);
        }
        int[] conditionals = BslSyntaxChecker.openConditionalLines(sourceLines);
        // Read on the MASKED lines: a keyword inside a pragma's string argument or a trailing comment is no pragma.
        List<String> masked = BslSyntaxChecker.maskLiteralsAndComments(sourceLines);
        for (BslModuleUtils.MethodSpan span : lookup.found)
        {
            int opened = conditionals[span.startLine] != 0 ? conditionals[span.startLine] : conditionals[span.endLine];
            if (opened != 0)
            {
                return Result.refused("Method '" + span.name + "' in '" + sourceLabel //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is inside or crosses a #If block (opened at line " + opened //$NON-NLS-1$
                    + "): copying it would drop the condition. Nothing was written. Copy it with " //$NON-NLS-1$
                    + "read_method_source and source, keeping the #If lines."); //$NON-NLS-1$
            }
            for (int line = span.startLine; line < span.declarationLine; line++)
            {
                if (INTERCEPTION_ANNOTATION.matcher(masked.get(line)).find())
                {
                    return Result.refused("Method '" + span.name + "' in '" + sourceLabel //$NON-NLS-1$ //$NON-NLS-2$
                        + "' carries an interception annotation (" + sourceLines.get(line).trim() //$NON-NLS-1$
                        + ") that binds it to its own module, so it is not copied. Nothing was written."); //$NON-NLS-1$
                }
            }
        }
        List<String> copied = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : lookup.found)
        {
            if (!copied.isEmpty())
            {
                copied.add(""); //$NON-NLS-1$
            }
            copied.addAll(sourceLines.subList(span.startLine, span.endLine + 1));
            declared.add(span.name);
        }
        return new Result(null, copied, declared, List.of());
    }

    /**
     * Refuses a copy into {@code targetLines} when the target already declares one of {@code methods}, or when
     * the target holds a shape this scanner cannot address (the duplicate check could not see a name hidden in
     * it).
     *
     * @param targetLines the target module's lines (empty for a module that does not exist yet)
     * @param methods the declared names of the copied methods
     * @return the refusal, or {@code null}
     */
    public static String targetConflict(List<String> targetLines, List<String> methods)
    {
        String unaddressable = unaddressableModule(targetLines, "the target module"); //$NON-NLS-1$
        if (unaddressable != null)
        {
            return unaddressable;
        }
        List<String> taken = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : BslModuleUtils.findMethodSpansViaText(targetLines))
        {
            for (String method : methods)
            {
                if (span.name.equalsIgnoreCase(method) && !taken.contains(method))
                {
                    taken.add(method);
                }
            }
        }
        if (taken.isEmpty())
        {
            return null;
        }
        return "cannot copy " + quotedList(taken) + ": the target module already declares " //$NON-NLS-1$ //$NON-NLS-2$
            + (taken.size() == 1 ? "it" : "them") + ". Nothing was written. A repeated copy is refused this way; " //$NON-NLS-1$ //$NON-NLS-2$
            + "to finish a move, delete the methods from the source with mode=deleteMethods."; //$NON-NLS-1$
    }

    /**
     * {@code moduleLines} without the named methods: each one goes with its preamble, and the blank lines at
     * each seam collapse to one (none at a module edge). Regions and other code stay as they are. The deleted
     * names still called in the rest of the module - an unqualified {@code Name(} outside comments and strings,
     * a text check - are reported, not refused.
     *
     * @param moduleLines the module's lines
     * @param names the validated names (see {@link #validateNames})
     * @return the new lines, the declared names and the remaining calls, or the refusal
     */
    public static Result remove(List<String> moduleLines, List<String> names)
    {
        String unaddressable = unaddressableModule(moduleLines, "the module"); //$NON-NLS-1$
        if (unaddressable != null)
        {
            return Result.refused(unaddressable);
        }
        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(moduleLines);
        Lookup lookup = lookup(spans, names, "the module"); //$NON-NLS-1$
        if (lookup.error != null)
        {
            return Result.refused(lookup.error);
        }
        boolean[] deleted = new boolean[moduleLines.size()];
        List<String> declared = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : lookup.found)
        {
            for (int line = span.startLine; line <= span.endLine; line++)
            {
                deleted[line] = true;
            }
            declared.add(span.name);
        }
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < moduleLines.size())
        {
            if (!deleted[i])
            {
                result.add(moduleLines.get(i));
                i++;
                continue;
            }
            // A seam: drop the deleted run and the blank lines on both sides of it, then put one blank line
            // back when there is code on both sides.
            while (!result.isEmpty() && result.get(result.size() - 1).isBlank())
            {
                result.remove(result.size() - 1);
            }
            while (i < moduleLines.size() && (deleted[i] || moduleLines.get(i).isBlank()))
            {
                i++;
            }
            if (!result.isEmpty() && i < moduleLines.size())
            {
                result.add(""); //$NON-NLS-1$
            }
        }
        return new Result(null, result, declared, remainingCalls(result, declared));
    }

    /**
     * The names of {@code methods} still called in {@code lines}: an identifier followed by {@code (} whose
     * previous code token is not a member dot, read outside comments and string literals - a text check, not a
     * resolution. The previous token is looked up across whitespace, line breaks and comments, so
     * {@code Other. Name(} and a dot that ends the line above both make the call qualified.
     *
     * @param lines the module lines
     * @param methods the method names
     * @return {@code "Name (line N)"} for each name still called, at its first call
     */
    static List<String> remainingCalls(List<String> lines, List<String> methods)
    {
        List<String> masked = BslSyntaxChecker.maskLiteralsAndComments(lines);
        List<String> calls = new ArrayList<>();
        for (String method : methods)
        {
            Pattern call = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(method) //$NON-NLS-1$
                + "(?![\\p{L}\\p{N}_])\\s*\\(", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); //$NON-NLS-1$
            int found = firstUnqualifiedCall(masked, call);
            if (found >= 0)
            {
                calls.add(method + " (line " + (found + 1) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return calls;
    }

    /** The 0-based line of the first match of {@code call} whose previous code token is not a dot, or -1. */
    private static int firstUnqualifiedCall(List<String> masked, Pattern call)
    {
        for (int line = 0; line < masked.size(); line++)
        {
            Matcher matcher = call.matcher(masked.get(line));
            while (matcher.find())
            {
                if (previousCodeCharacter(masked, line, matcher.start()) != '.')
                {
                    return line;
                }
            }
        }
        return -1;
    }

    /**
     * The last non-whitespace character before column {@code column} of line {@code line}, looking back across
     * earlier lines; comments and literals are already blanked. {@code 0} at the start of the module.
     */
    private static char previousCodeCharacter(List<String> masked, int line, int column)
    {
        String text = masked.get(line);
        for (int index = Math.min(column, text.length()) - 1; index >= 0; index--)
        {
            if (!Character.isWhitespace(text.charAt(index)))
            {
                return text.charAt(index);
            }
        }
        for (int previous = line - 1; previous >= 0; previous--)
        {
            String earlier = masked.get(previous);
            for (int index = earlier.length() - 1; index >= 0; index--)
            {
                if (!Character.isWhitespace(earlier.charAt(index)))
                {
                    return earlier.charAt(index);
                }
            }
        }
        return 0;
    }

    /** The spans of the requested names, in request order, or the refusal. */
    private static final class Lookup
    {
        final String error;
        final List<BslModuleUtils.MethodSpan> found;

        Lookup(String error, List<BslModuleUtils.MethodSpan> found)
        {
            this.error = error;
            this.found = found;
        }
    }

    private static Lookup lookup(List<BslModuleUtils.MethodSpan> spans, List<String> names, String where)
    {
        List<String> missing = new ArrayList<>();
        List<BslModuleUtils.MethodSpan> found = new ArrayList<>();
        for (String name : names)
        {
            List<BslModuleUtils.MethodSpan> matches = new ArrayList<>();
            for (BslModuleUtils.MethodSpan span : spans)
            {
                if (span.name.equalsIgnoreCase(name))
                {
                    matches.add(span);
                }
            }
            if (matches.isEmpty())
            {
                missing.add(name);
                continue;
            }
            if (matches.size() > 1)
            {
                return new Lookup("Method '" + name + "' is declared " + matches.size() + " times in " + where //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " (preprocessor branches?): it is ambiguous, so nothing was written.", null); //$NON-NLS-1$
            }
            if (!matches.get(0).complete)
            {
                return new Lookup("Method '" + name + "' in " + where //$NON-NLS-1$ //$NON-NLS-2$
                    + " has no matching terminator: its span is incomplete, so nothing was written.", null); //$NON-NLS-1$
            }
            found.add(matches.get(0));
        }
        if (!missing.isEmpty())
        {
            return new Lookup((missing.size() == 1 ? "Method " : "Methods ") + quotedList(missing) //$NON-NLS-1$ //$NON-NLS-2$
                + " not found in " + where + ". Nothing was written. Available methods: " //$NON-NLS-1$ //$NON-NLS-2$
                + availableNames(spans) + ".", null); //$NON-NLS-1$
        }
        return new Lookup(null, found);
    }

    private static String unaddressableModule(List<String> lines, String where)
    {
        BslModuleUtils.Unaddressable hidden = BslModuleUtils.unaddressable(lines);
        if (hidden != null)
        {
            return where + " has " + hidden.what + " at line " + (hidden.line + 1) + " ('" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + lines.get(hidden.line).trim() + "'), which methodNames cannot address: " + hidden.fix //$NON-NLS-1$
                + ". Nothing was written."; //$NON-NLS-1$
        }
        int crowded = BslModuleUtils.secondDeclarationOnLine(lines);
        if (crowded >= 0)
        {
            return where + " declares two methods on line " + (crowded + 1) + " ('" + lines.get(crowded).trim() //$NON-NLS-1$ //$NON-NLS-2$
                + "'), which methodNames cannot address: put one declaration per line. Nothing was written."; //$NON-NLS-1$
        }
        return null;
    }

    private static String availableNames(List<BslModuleUtils.MethodSpan> spans)
    {
        if (spans.isEmpty())
        {
            return "none"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(spans.size(), AVAILABLE_NAMES_SHOWN);
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(spans.get(i).name);
        }
        if (spans.size() > shown)
        {
            sb.append(", ... (").append(spans.size() - shown).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    private static String quotedList(List<String> names)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append('\'').append(names.get(i)).append('\'');
        }
        return sb.toString();
    }
}
