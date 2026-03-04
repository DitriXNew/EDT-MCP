/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

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

    // Opening keywords
    private static final Pattern PROCEDURE_START = Pattern.compile(
        "^\\s*(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|Procedure)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern FUNCTION_START = Pattern.compile(
        "^\\s*(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\b", //$NON-NLS-1$
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

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            int lineNum = i + 1;

            // Skip empty lines
            if (line.trim().isEmpty())
            {
                continue;
            }

            // Skip lines that are entirely a comment
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) //$NON-NLS-1$
            {
                continue;
            }

            // Skip multiline string continuation lines (start with |)
            if (trimmed.startsWith("|")) //$NON-NLS-1$
            {
                continue;
            }

            // Remove inline comment (everything after //)
            // Known limitation: // inside string literals (e.g. URLs like "https://...")
            // will be treated as a comment start. This is acceptable because keywords
            // are matched at line start (^\s*keyword\b), so truncation does not affect results.
            int commentIdx = trimmed.indexOf("//"); //$NON-NLS-1$
            if (commentIdx > 0)
            {
                trimmed = trimmed.substring(0, commentIdx);
            }

            // Check closing keywords FIRST
            if (END_PROCEDURE.matcher(trimmed).find())
            {
                popAndCheck(stack, TAG_PROCEDURE, "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B/EndProcedure", lineNum, errors); //$NON-NLS-1$
                continue;
            }
            if (END_FUNCTION.matcher(trimmed).find())
            {
                popAndCheck(stack, TAG_FUNCTION, "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438/EndFunction", lineNum, errors); //$NON-NLS-1$
                continue;
            }
            if (END_IF.matcher(trimmed).find())
            {
                popAndCheck(stack, TAG_IF, "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438/EndIf", lineNum, errors); //$NON-NLS-1$
                continue;
            }
            if (END_DO.matcher(trimmed).find())
            {
                popAndCheck(stack, TAG_LOOP, "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430/EndDo", lineNum, errors); //$NON-NLS-1$
                continue;
            }
            if (END_TRY.matcher(trimmed).find())
            {
                popAndCheck(stack, TAG_TRY, "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438/EndTry", lineNum, errors); //$NON-NLS-1$
                continue;
            }

            // Check opening keywords
            if (PROCEDURE_START.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_PROCEDURE, String.valueOf(lineNum) });
                continue;
            }
            if (FUNCTION_START.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_FUNCTION, String.valueOf(lineNum) });
                continue;
            }
            // If but NOT ElsIf
            if (IF_START.matcher(trimmed).find() && !ELSIF_PATTERN.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_IF, String.valueOf(lineNum) });
                continue;
            }
            if (WHILE_START.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_LOOP, String.valueOf(lineNum) });
                continue;
            }
            if (FOR_START.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_LOOP, String.valueOf(lineNum) });
                continue;
            }
            if (TRY_START.matcher(trimmed).find())
            {
                stack.push(new String[] { TAG_TRY, String.valueOf(lineNum) });
                continue;
            }
        }

        // Report unclosed blocks
        while (!stack.isEmpty())
        {
            String[] entry = stack.pop();
            errors.add("Unclosed " + tagToKeyword(entry[0]) + " from line " + entry[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return new CheckResult(errors.isEmpty(), errors);
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
