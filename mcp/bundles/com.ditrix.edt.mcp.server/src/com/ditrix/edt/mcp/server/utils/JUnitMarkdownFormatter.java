/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders {@link JUnitTestResults} as a Markdown report.
 *
 * The output contains a summary table, an overall pass/fail verdict, and
 * per-section details for failed, errored and skipped test cases.
 */
public final class JUnitMarkdownFormatter
{
    /**
     * Identifiers of YAXUnit-internal modules whose stack frames carry no
     * diagnostic value — the assertion engine, the executor and the server-call
     * plumbing. They are always identical framework frames, so collapsing them
     * keeps the actionable user frames while drastically shrinking the report
     * (see issue #112).
     *
     * <p>The tokens are 1C metadata object <em>names</em> (not synonyms), so
     * they stay Cyrillic regardless of the platform UI language. The
     * surrounding metadata-kind words ({@code ОбщийМодуль}/{@code CommonModule},
     * {@code Модуль}/{@code Module}) <em>are</em> localized, so we deliberately
     * key only on the dot-delimited module name to stay language-independent.
     */
    private static final String[] INTERNAL_FRAME_MODULE_TOKENS = {
        ".ЮТУтверждения.", //$NON-NLS-1$
        ".ЮТМетодыСлужебный.", //$NON-NLS-1$
        ".ЮТИсполнительСлужебныйКлиентСервер.", //$NON-NLS-1$
        ".ЮТИсполнительСлужебныйВызовСервера.", //$NON-NLS-1$
    };

    /**
     * Matches a {@code [tag] <content>} wrapper (e.g. {@code [Failed] <...>})
     * so the inner content can be compared with the failure message. The tag
     * word is captured loosely to stay language-independent.
     */
    private static final Pattern BRACKET_WRAPPER = Pattern.compile("^\\[[^\\]]*\\]\\s*<(.*)>$"); //$NON-NLS-1$

    /** Minimum length before a head/message overlap is treated as a real duplicate. */
    private static final int MIN_DUPLICATE_LENGTH = 16;

    private JUnitMarkdownFormatter()
    {
        // utility class
    }

    /**
     * Formats parsed results as a Markdown document.
     */
    public static String format(JUnitTestResults results)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# YAXUnit Test Results\n\n"); //$NON-NLS-1$

        sb.append("## Summary\n\n"); //$NON-NLS-1$
        sb.append("| Metric | Count |\n"); //$NON-NLS-1$
        sb.append("|--------|-------|\n"); //$NON-NLS-1$
        sb.append("| Total  | ").append(results.getTotal()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Passed | ").append(results.getPassed()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Failed | ").append(results.getFailures()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Errors | ").append(results.getErrors()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Skipped | ").append(results.getSkipped()).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append(results.isPassed() ? "**Result: PASSED**\n" : "**Result: FAILED**\n"); //$NON-NLS-1$ //$NON-NLS-2$

        appendSection(sb, "Failures", results.getFailureDetails(), true); //$NON-NLS-1$
        appendSection(sb, "Errors", results.getErrorDetails(), true); //$NON-NLS-1$
        appendSkippedSection(sb, results.getSkippedDetails());

        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title,
            List<JUnitTestResults.TestCase> cases, boolean withTrace)
    {
        if (cases.isEmpty())
        {
            return;
        }
        sb.append("\n## ").append(title).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (JUnitTestResults.TestCase tc : cases)
        {
            sb.append("\n### ").append(tc.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (tc.message != null && !tc.message.isEmpty())
            {
                sb.append("**Message:** ").append(tc.message).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (withTrace && tc.trace != null && !tc.trace.trim().isEmpty())
            {
                String trace = compactTrace(tc.trace, tc.message);
                if (!trace.isEmpty())
                {
                    sb.append("```\n").append(trace).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    /**
     * Compacts a YAXUnit stack trace for the report: drops the trailing engine
     * value-dump that duplicates the failure message, then collapses contiguous
     * runs of YAXUnit-internal frames into a single marker line. The raw trace
     * is preserved untouched in the on-disk {@code junit.xml}.
     *
     * <p>The trimming keys on language-independent signals (YAXUnit module
     * names and stack-frame structure), so it works on any platform UI language.
     *
     * @param trace the raw stack trace
     * @param message the failure message, used to drop a head that merely
     *            restates it (may be {@code null})
     */
    static String compactTrace(String trace, String message)
    {
        String trimmed = dropTrailingEngineDump(trace);
        trimmed = dropDuplicateHead(trimmed, message);
        return collapseInternalFrames(trimmed).trim();
    }

    /**
     * Drops the head — the lines before the first stack frame — when it merely
     * restates the failure message (the {@code [Failed] <...>} block or a bare
     * message copy). The head is removed only when it clearly duplicates the
     * message, so unique diagnostic text is preserved.
     */
    private static String dropDuplicateHead(String trace, String message)
    {
        if (message == null || message.isEmpty())
        {
            return trace;
        }
        String[] lines = trace.split("\n", -1); //$NON-NLS-1$
        int firstFrame = -1;
        for (int i = 0; i < lines.length; i++)
        {
            if (isStackFrame(lines[i]))
            {
                firstFrame = i;
                break;
            }
        }
        if (firstFrame <= 0)
        {
            // No head (the first line is already a frame) or no frames at all.
            return trace;
        }

        StringBuilder headSb = new StringBuilder();
        for (int i = 0; i < firstFrame; i++)
        {
            if (i > 0)
            {
                headSb.append('\n');
            }
            headSb.append(lines[i]);
        }
        if (!headDuplicatesMessage(headSb.toString(), message))
        {
            return trace;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = firstFrame; i < lines.length; i++)
        {
            if (i > firstFrame)
            {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} when the trace head is just a restatement of the
     * failure message — equal to it, a suffix of it (message carries an extra
     * prefix such as {@code "Исполнения: "}) or its {@code [tag] <...>} wrapper.
     */
    private static boolean headDuplicatesMessage(String head, String message)
    {
        String normalizedMessage = normalizeWhitespace(message);
        if (normalizedMessage.length() < MIN_DUPLICATE_LENGTH)
        {
            return false;
        }
        String normalizedHead = normalizeWhitespace(head);
        return isRestatement(normalizedMessage, normalizedHead)
                || isRestatement(normalizedMessage, stripBracketWrapper(normalizedHead));
    }

    private static boolean isRestatement(String message, String head)
    {
        if (head.length() < MIN_DUPLICATE_LENGTH)
        {
            return false;
        }
        return message.equals(head) || message.endsWith(head) || head.endsWith(message);
    }

    private static String stripBracketWrapper(String text)
    {
        Matcher matcher = BRACKET_WRAPPER.matcher(text);
        return matcher.matches() ? matcher.group(1).trim() : text;
    }

    private static String normalizeWhitespace(String text)
    {
        return text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Drops everything after the last stack-frame line. The engine value-dump
     * that duplicates the failure message (the localized {@code [..]} error
     * category and its {@code "#value"} payload) always trails the frames, so
     * cutting after the last frame removes it without depending on the
     * platform language.
     */
    private static String dropTrailingEngineDump(String trace)
    {
        String[] lines = trace.split("\n", -1); //$NON-NLS-1$
        int lastFrame = -1;
        for (int i = 0; i < lines.length; i++)
        {
            if (isStackFrame(lines[i]))
            {
                lastFrame = i;
            }
        }
        if (lastFrame < 0)
        {
            // No recognizable frames — leave the trace untouched.
            return trace;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= lastFrame; i++)
        {
            sb.append(lines[i]);
            if (i < lastFrame)
            {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Collapses each contiguous run of YAXUnit-internal frames into one marker
     * line, keeping all other (actionable) frames intact.
     */
    private static String collapseInternalFrames(String trace)
    {
        StringBuilder sb = new StringBuilder();
        int hidden = 0;
        for (String line : trace.split("\n", -1)) //$NON-NLS-1$
        {
            if (isInternalFrame(line))
            {
                hidden++;
                continue;
            }
            if (hidden > 0)
            {
                sb.append(hiddenMarker(hidden)).append('\n');
                hidden = 0;
            }
            sb.append(line).append('\n');
        }
        if (hidden > 0)
        {
            sb.append(hiddenMarker(hidden)).append('\n');
        }
        return sb.toString();
    }

    /** A 1C stack-frame line looks like {@code {...}:statement}. */
    private static boolean isStackFrame(String line)
    {
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.contains("}:"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isInternalFrame(String line)
    {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) //$NON-NLS-1$
        {
            return false;
        }
        for (String token : INTERNAL_FRAME_MODULE_TOKENS)
        {
            if (trimmed.contains(token))
            {
                return true;
            }
        }
        return false;
    }

    private static String hiddenMarker(int count)
    {
        return "{… " + count + " internal YAXUnit frames hidden …}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendSkippedSection(StringBuilder sb, List<JUnitTestResults.TestCase> cases)
    {
        if (cases.isEmpty())
        {
            return;
        }
        sb.append("\n## Skipped\n\n"); //$NON-NLS-1$
        for (JUnitTestResults.TestCase tc : cases)
        {
            sb.append("- **").append(tc.name).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            if (tc.message != null && !tc.message.isEmpty())
            {
                sb.append(" — ").append(tc.message); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
    }
}
