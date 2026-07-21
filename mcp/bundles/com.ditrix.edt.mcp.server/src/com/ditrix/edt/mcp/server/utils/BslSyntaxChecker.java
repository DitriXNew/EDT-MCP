/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight BSL syntax checker that validates balanced block keywords.
 * Checks: Procedure/EndProcedure, Function/EndFunction, If/EndIf,
 * While/EndDo, For/EndDo, Try/EndTry (Russian and English, case-insensitive).
 */
public final class BslSyntaxChecker
{
    private BslSyntaxChecker()
    {
        // utility class
    }

    private static final String TAG_PROCEDURE = "PROCEDURE"; //$NON-NLS-1$
    private static final String TAG_FUNCTION = "FUNCTION"; //$NON-NLS-1$
    private static final String TAG_IF = "IF"; //$NON-NLS-1$
    private static final String TAG_LOOP = "LOOP"; //$NON-NLS-1$
    private static final String TAG_TRY = "TRY"; //$NON-NLS-1$

    // Closing keywords — check FIRST (before opening)
    private static final Pattern END_PROCEDURE = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|EndProcedure)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern END_FUNCTION = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndFunction)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern END_IF = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438|EndIf)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern END_DO = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430|EndDo)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern END_TRY = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438|EndTry)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Opening keywords. An optional "\u0410\u0441\u0438\u043D\u0445"/"Async" prefix is allowed before
    // Procedure/Function (async procedures/functions, #287); "\u0416\u0434\u0430\u0442\u044C"/"Await" is
    // an expression keyword and introduces no block of its own, so it is intentionally not matched here.
    private static final Pattern PROCEDURE_START = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|Procedure)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern FUNCTION_START = Pattern.compile(
        "^\\s*(?:\u0410\u0441\u0438\u043D\u0445\\s+|Async\\s+)?(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // If but NOT ElsIf/ElseIf/ИначеЕсли
    private static final Pattern IF_START = Pattern.compile(
        "^\\s*(?:\u0415\u0441\u043B\u0438|If)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ELSIF_PATTERN = Pattern.compile(
        "^\\s*(?:\u0418\u043D\u0430\u0447\u0435\u0415\u0441\u043B\u0438|ElsIf|ElseIf)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern WHILE_START = Pattern.compile(
        "^\\s*(?:\u041F\u043E\u043A\u0430|While)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // For / Для (includes Для Каждого / For Each)
    private static final Pattern FOR_START = Pattern.compile(
        "^\\s*(?:\u0414\u043B\u044F|For)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern TRY_START = Pattern.compile(
        "^\\s*(?:\u041F\u043E\u043F\u044B\u0442\u043A\u0430|Try)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Result of a BSL syntax check.
     */
    public static class CheckResult
    {
        private final boolean valid;
        private final List<String> errors;

        public CheckResult(boolean valid, List<String> errors)
        {
            this.valid = valid;
            this.errors = errors;
        }

        public boolean isValid()
        {
            return valid;
        }

        public List<String> getErrors()
        {
            return errors;
        }
    }

    /**
     * Checks the given BSL source lines for balanced block keywords.
     *
     * @param lines the source lines to check
     * @return check result with validity flag and error messages
     */
    public static CheckResult check(List<String> lines)
    {
        List<String> errors = new ArrayList<>();
        // Stack of (tag, lineNumber as string)
        Deque<String[]> stack = new ArrayDeque<>();
        // Carries whether a string literal is still open across physical lines
        StringLiteralState stringState = new StringLiteralState();

        for (int i = 0; i < lines.size(); i++) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            int lineNum = i + 1;

            // A double-quote string only continues onto the next physical line via a leading '|'
            // continuation. If the previous line ended still inside a string but THIS line is not a
            // continuation, the literal was not a valid multi-line string (a mis-tracked quote or a
            // genuinely unclosed string) - reset so it does not mask the real code that follows,
            // which would hide real block keywords (and their imbalance).
            if (stringState.insideString && !lines.get(i).trim().startsWith("|")) //$NON-NLS-1$
            {
                stringState.insideString = false;
            }

            String trimmed = preprocessLine(lines.get(i), stringState);
            if (trimmed == null)
            {
                continue;
            }

            // Check closing keywords FIRST
            if (handleClosingKeyword(trimmed, lineNum, stack, errors))
            {
                continue;
            }

            // Check opening keywords
            handleOpeningKeyword(trimmed, lineNum, stack);
        }

        reportUnclosedBlocks(stack, errors);

        return new CheckResult(errors.isEmpty(), errors);
    }

    /**
     * Carries whether a string literal opened on an earlier physical line is still
     * open when the next line starts. BSL string literals can span several lines,
     * each continuation line starting with a leading {@code |}.
     */
    private static final class StringLiteralState
    {
        private boolean insideString;
    }

    /**
     * Normalize a source line for keyword matching: masks any string-literal (and
     * comment) content via {@link #maskStringLiterals}, then drops leading
     * whitespace and stray statement separators.
     * <p>
     * The leftover leading {@code ;} case shows up when a string literal closes
     * mid-line and the assignment's trailing {@code ;} is the only unmasked
     * character before the next real statement — e.g. a closing continuation line
     * such as {@code |tail"; If x Then} masks to a run of spaces followed by
     * {@code ; If x Then}; skipping that separator lets {@code If} still be
     * recognized "at line start".
     *
     * @param line the raw source line
     * @param state carries whether a string literal is already open when this
     *     line starts; updated in place to reflect the state after this line
     * @return the masked line ready for keyword matching, or {@code null} if there
     *     is no real code left on the line (blank, a full comment, a pure string
     *     continuation line, or only statement separators)
     */
    private static String preprocessLine(String line, StringLiteralState state)
    {
        String masked = maskStringLiterals(line, state);

        int start = 0;
        int len = masked.length();
        while (start < len && (Character.isWhitespace(masked.charAt(start)) || masked.charAt(start) == ';'))
        {
            start++;
        }
        return start >= len ? null : masked.substring(start);
    }

    /**
     * Masks the parts of a line that are lexically inside a string literal so that
     * keyword-looking text embedded in query or message text can never match a
     * block keyword, while any real code on the same physical line — including
     * code that follows the string's closing quote — is left untouched.
     * <p>
     * Walks the line character by character, toggling {@code state.insideString}
     * on every double quote, EXCEPT a doubled {@code ""} which is an escaped quote
     * inside the literal and does not close it. Masked characters are replaced
     * with a space (not removed), so the column position of any real code after
     * the string is preserved. An inline {@code //} comment is only recognized
     * while NOT inside a string, so a {@code //} inside a URL or message text no
     * longer truncates the line. {@code state.insideString} is updated in place so
     * the flag carries across lines for a literal that spans several physical
     * lines (each continuation masks to blank and is effectively skipped, same
     * outcome as before, but now for the correct reason).
     *
     * @param line the raw source line
     * @param state carries whether a string literal is already open when this
     *     line starts; updated in place to reflect the state after this line
     * @return the line with string-literal content (and any trailing comment)
     *     replaced by spaces or dropped
     */
    private static String maskStringLiterals(String line, StringLiteralState state)
    {
        int len = line.length();
        StringBuilder masked = new StringBuilder(len);
        boolean insideString = state.insideString; // double-quote "..." string, CARRIED across lines
        boolean insideDate = false;                 // single-quote '...' date literal, intra-line only

        for (int i = 0; i < len; i++)
        {
            char c = line.charAt(i);
            if (insideString)
            {
                if (c == '"' && i + 1 < len && line.charAt(i + 1) == '"')
                {
                    // Doubled quote: an escaped quote inside the literal, string stays open
                    masked.append(' ').append(' ');
                    i++;
                    continue;
                }
                if (c == '"')
                {
                    insideString = false;
                }
                masked.append(' ');
                continue;
            }
            if (insideDate)
            {
                // A single-quote '...' date literal. A double-quote inside it is CONTENT, not a
                // string toggle - masking it here is what stops a stray '"' inside a date/'...'
                // token from flipping insideString and swallowing the rest of the module.
                if (c == '\'')
                {
                    insideDate = false;
                }
                masked.append(' ');
                continue;
            }
            if (c == '"')
            {
                insideString = true;
                masked.append(' ');
                continue;
            }
            if (c == '\'')
            {
                insideDate = true;
                masked.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < len && line.charAt(i + 1) == '/')
            {
                break; // inline comment - the rest of the line is not code
            }
            masked.append(c);
        }

        // A single-quote date literal never spans physical lines, so insideDate is intentionally
        // NOT persisted; only the double-quote string carries (via the '|'-continuation rule the
        // caller applies before the next line - see check()).
        state.insideString = insideString;
        return masked.toString();
    }

    /**
     * Match a closing block keyword on the line and, if found, pop the matching opening
     * block from the stack (recording any mismatch).
     *
     * @param trimmed the preprocessed line
     * @param lineNum the 1-based line number
     * @param stack the open-block stack
     * @param errors the accumulated error messages
     * @return true if a closing keyword was matched and handled
     */
    private static boolean handleClosingKeyword(String trimmed, int lineNum,
        Deque<String[]> stack, List<String> errors)
    {
        if (END_PROCEDURE.matcher(trimmed).find())
        {
            popAndCheck(stack, TAG_PROCEDURE, "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B/EndProcedure", lineNum, errors); //$NON-NLS-1$
            return true;
        }
        if (END_FUNCTION.matcher(trimmed).find())
        {
            popAndCheck(stack, TAG_FUNCTION, "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438/EndFunction", lineNum, errors); //$NON-NLS-1$
            return true;
        }
        if (END_IF.matcher(trimmed).find())
        {
            popAndCheck(stack, TAG_IF, "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438/EndIf", lineNum, errors); //$NON-NLS-1$
            return true;
        }
        if (END_DO.matcher(trimmed).find())
        {
            popAndCheck(stack, TAG_LOOP, "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430/EndDo", lineNum, errors); //$NON-NLS-1$
            return true;
        }
        if (END_TRY.matcher(trimmed).find())
        {
            popAndCheck(stack, TAG_TRY, "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438/EndTry", lineNum, errors); //$NON-NLS-1$
            return true;
        }
        return false;
    }

    /**
     * Match an opening block keyword on the line and, if found, push the corresponding
     * open block onto the stack.
     *
     * @param trimmed the preprocessed line
     * @param lineNum the 1-based line number
     * @param stack the open-block stack
     */
    private static void handleOpeningKeyword(String trimmed, int lineNum, Deque<String[]> stack)
    {
        if (PROCEDURE_START.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_PROCEDURE, String.valueOf(lineNum) });
            return;
        }
        if (FUNCTION_START.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_FUNCTION, String.valueOf(lineNum) });
            return;
        }
        // If but NOT ElsIf
        if (IF_START.matcher(trimmed).find() && !ELSIF_PATTERN.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_IF, String.valueOf(lineNum) });
            return;
        }
        if (WHILE_START.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_LOOP, String.valueOf(lineNum) });
            return;
        }
        if (FOR_START.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_LOOP, String.valueOf(lineNum) });
            return;
        }
        if (TRY_START.matcher(trimmed).find())
        {
            stack.push(new String[] { TAG_TRY, String.valueOf(lineNum) });
        }
    }

    /**
     * Drain the open-block stack, appending an "unclosed" error for each remaining block.
     *
     * @param stack the open-block stack
     * @param errors the accumulated error messages
     */
    private static void reportUnclosedBlocks(Deque<String[]> stack, List<String> errors)
    {
        while (!stack.isEmpty())
        {
            String[] entry = stack.pop();
            errors.add("Unclosed " + tagToKeyword(entry[0]) + " from line " + entry[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void popAndCheck(Deque<String[]> stack, String expectedTag,
        String keyword, int lineNum, List<String> errors)
    {
        if (stack.isEmpty())
        {
            errors.add("Unexpected " + keyword + " at line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + " (no matching opening keyword)"); //$NON-NLS-1$
            return;
        }
        String[] top = stack.pop();
        if (!top[0].equals(expectedTag))
        {
            errors.add("Mismatched " + keyword + " at line " + lineNum //$NON-NLS-1$ //$NON-NLS-2$
                + ", expected closing for " + tagToKeyword(top[0]) //$NON-NLS-1$
                + " from line " + top[1]); //$NON-NLS-1$
        }
    }

    private static String tagToKeyword(String tag)
    {
        switch (tag)
        {
            case TAG_PROCEDURE:
                return "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430/Procedure"; //$NON-NLS-1$
            case TAG_FUNCTION:
                return "\u0424\u0443\u043D\u043A\u0446\u0438\u044F/Function"; //$NON-NLS-1$
            case TAG_IF:
                return "\u0415\u0441\u043B\u0438/If"; //$NON-NLS-1$
            case TAG_LOOP:
                return "\u041F\u043E\u043A\u0430|\u0414\u043B\u044F/While|For"; //$NON-NLS-1$
            case TAG_TRY:
                return "\u041F\u043E\u043F\u044B\u0442\u043A\u0430/Try"; //$NON-NLS-1$
            default:
                return tag;
        }
    }
}
