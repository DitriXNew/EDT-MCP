/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;

/**
 * Renders {@link JUnitTestResults} as a Markdown report.
 *
 * The output contains a summary table, an overall pass/fail verdict, and
 * per-section details for failed, errored and skipped test cases.
 */
public final class JUnitMarkdownFormatter
{
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
        sb.append("| Skipped| ").append(results.getSkipped()).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

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
                sb.append("```\n").append(tc.trace.trim()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
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
