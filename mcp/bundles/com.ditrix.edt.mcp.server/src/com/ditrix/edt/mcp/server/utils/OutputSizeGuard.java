/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

/**
 * Absolute upper bound on the size of a tool's human-readable CONTENT TEXT.
 *
 * <p>Per-row / per-value caps already exist in individual tools, but there is no
 * absolute guard on the total size of what a single tools/call returns. A wide
 * dump (a full metadata reflection, hundreds of long code lines, a large search
 * result) can blow far past the context budget an agent can usefully consume.
 * This helper enforces one project-wide character budget on the content text and,
 * when the budget is exceeded, truncates it and appends a self-describing notice
 * telling the agent how to narrow the result.</p>
 *
 * <p><b>What this caps and what it must NOT.</b> The budget applies ONLY to the
 * human-readable content text a tool produces (the markdown / text / YAML / plain
 * string that becomes {@code content[0].text} or an embedded resource body). It
 * must NEVER be applied to the surrounding JSON-RPC envelope (that would corrupt
 * the wire frame) nor to a JSON tool's {@code structuredContent} object (that is
 * round-trip data — truncating it would corrupt the payload). The caller is
 * responsible for invoking this only on the content-text channel.</p>
 *
 * <p><b>Pure no-op below the budget.</b> When the input is at or below
 * {@link #MAX_CONTENT_CHARS} the very same string instance is returned unchanged
 * (byte-for-byte identical), so outputs verified elsewhere (e.g. the e2e-only
 * metadata synonym table) are not perturbed.</p>
 */
public final class OutputSizeGuard
{
    /**
     * Project-wide budget (characters) for a single tool's content text.
     *
     * <p>Chosen as roughly 25k tokens worth of text. A common rule of thumb is
     * ~4 characters per token for English/markdown, so 100k characters is about
     * 25k tokens, which is the size target referenced in the MCP tool-design
     * guidance. It is a deliberately generous ceiling: ordinary, already-paginated
     * results stay well under it, so the guard is a safety net for pathological
     * dumps rather than a routine truncator.</p>
     */
    public static final int MAX_CONTENT_CHARS = 100_000;

    private OutputSizeGuard()
    {
        // Utility class - no instantiation
    }

    /**
     * Caps {@code contentText} to {@link #MAX_CONTENT_CHARS} characters using the
     * default budget. See {@link #cap(String, int)}.
     *
     * @param contentText the tool's human-readable content text (may be {@code null})
     * @return the original string when within budget, otherwise a truncated string
     *         carrying a trailing truncation notice
     */
    public static String cap(String contentText)
    {
        return cap(contentText, MAX_CONTENT_CHARS);
    }

    /**
     * Caps {@code contentText} to {@code maxChars} characters. A {@code null} input
     * is returned unchanged. When the text length is at or below {@code maxChars}
     * the SAME string instance is returned (a pure no-op, byte-for-byte identical).
     * When it exceeds the budget the text is cut to fit and a self-describing
     * truncation notice (see {@link #buildTruncationNotice(int, int)}) is appended;
     * the cut point is chosen so the returned string's total length does not exceed
     * {@code maxChars} (the notice is accounted for in the budget).
     *
     * @param contentText the tool's human-readable content text (may be {@code null})
     * @param maxChars the budget in characters; values below 1 are treated as 1
     * @return the capped (or unchanged) content text
     */
    public static String cap(String contentText, int maxChars)
    {
        if (contentText == null)
        {
            return null;
        }
        int budget = Math.max(1, maxChars);
        int originalLength = contentText.length();
        if (originalLength <= budget)
        {
            // Pure no-op: never reformat or even rebuild a string that fits.
            return contentText;
        }

        String notice = buildTruncationNotice(originalLength, budget);
        // Reserve room for the notice so the final string stays within budget. If
        // the notice itself is longer than the budget (a pathologically small
        // budget), keep the full notice rather than corrupting it.
        int keep = budget - notice.length();
        if (keep < 0)
        {
            keep = 0;
        }
        return contentText.substring(0, keep) + notice;
    }

    /**
     * Builds the truncation notice appended when the content text is cut. The
     * notice is size-keyed (it reports the original and the kept character counts)
     * and actionable: it names the standard narrowing levers an agent can use so a
     * follow-up call returns less. Kept side-effect-free and public for testing.
     *
     * @param originalLength the original content-text length in characters
     * @param budget the budget the text was capped to
     * @return the notice text (begins on its own lines so it is visually distinct)
     */
    public static String buildTruncationNotice(int originalLength, int budget)
    {
        return "\n\n---\n" //$NON-NLS-1$
            + "[OUTPUT TRUNCATED] This result exceeded the maximum output size of " //$NON-NLS-1$
            + budget + " characters (full size was " + originalLength //$NON-NLS-1$
            + " characters); it was cut to fit. Narrow the result and call again: " //$NON-NLS-1$
            + "pass a smaller 'limit' (or an 'offset' to page), a more specific " //$NON-NLS-1$
            + "'modulePath' or 'fqn', a tighter line range, or request fewer " //$NON-NLS-1$
            + "objects/sections so the response stays under the size cap."; //$NON-NLS-1$
    }
}
