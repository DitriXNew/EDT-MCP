/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Renders a {@link ResourceTextSearch} result as Markdown in the four output modes the full-text
 * search tools share: {@code full} (matches with context, capped), {@code lines} (one
 * {@code path:line: text} row per match, capped like {@code full}, no context), {@code count}
 * (exact totals only) and {@code files} (a page of matched files with their exact counts).
 * <p>
 * {@link #forCode()} uses a {@code bsl} code fence, no per-file address, and the plain
 * no-match sentinel. A tool may add:
 * <ul>
 * <li>a per-file <b>address</b> (e.g. the DCS template FQN a {@code .dcs} file belongs to),
 * printed under the file heading in {@code full} mode, after the path in {@code lines} mode and
 * as an extra column in {@code files} mode; a file without one prints none;</li>
 * <li>a <b>fail-closed empty result</b>: when nothing matched but the scan was incomplete, the
 * {@code "No matches found."} sentinel is replaced by a line that says the scan is incomplete,
 * so an absence is never claimed on files that were never read.</li>
 * </ul>
 * Every rendering, {@code search_in_code} included, adds a warning line when the {@code fileMask}
 * rejected every file ({@link ResourceTextSearch#fileMaskMatchedNoFile()}): then nothing was
 * searched, and a bare zero would read as "the text is absent".
 */
public final class SearchResultFormatter
{
    /** Output mode: matches with context. */
    public static final String MODE_FULL = "full"; //$NON-NLS-1$

    /** Output mode: one {@code path:line: text} row per match in a single fence, no context. */
    public static final String MODE_LINES = "lines"; //$NON-NLS-1$

    /** Output mode: totals only. */
    public static final String MODE_COUNT = "count"; //$NON-NLS-1$

    /** Output mode: matched files with their counts. */
    public static final String MODE_FILES = "files"; //$NON-NLS-1$

    /** The no-match sentinel of a complete scan. */
    public static final String NO_MATCHES = "No matches found.\n"; //$NON-NLS-1$

    /** Closing quote of a heading echoing the query, followed by a blank line. */
    private static final String QUOTE_NEWLINES = "\"\n\n"; //$NON-NLS-1$

    /** Markdown prefix for inline warnings in the rendered output. */
    private static final String WARNING_PREFIX = "**Warning:** "; //$NON-NLS-1$

    private static final String INTERRUPTED_WARNING =
        "**Warning:** Search was interrupted, results may be incomplete"; //$NON-NLS-1$

    // Reserve space for the page notice, without ever cutting a table row at the transport cap.
    private static final int FILES_PAGE_BUDGET = OutputSizeGuard.MAX_CONTENT_CHARS - 1_000;
    private static final int MAX_FILES_TITLE_QUERY_CHARS = 1_000;

    private final String fenceLanguage;
    private final String addressLabel;
    private final Function<String, String> addressOf;
    private final boolean failClosedWhenIncomplete;

    private SearchResultFormatter(String fenceLanguage, String addressLabel,
        Function<String, String> addressOf, boolean failClosedWhenIncomplete)
    {
        this.fenceLanguage = fenceLanguage;
        this.addressLabel = addressLabel;
        this.addressOf = addressOf;
        this.failClosedWhenIncomplete = failClosedWhenIncomplete;
    }

    /**
     * The {@code search_in_code} rendering: {@code bsl} fences, no per-file address, and the plain
     * sentinel whatever the completeness.
     *
     * @return the formatter
     */
    public static SearchResultFormatter forCode()
    {
        return new SearchResultFormatter("bsl", null, null, false); //$NON-NLS-1$
    }

    /**
     * A rendering with a per-file address and a fail-closed empty result.
     *
     * @param fenceLanguage the code-fence language of the context blocks (e.g. {@code xml})
     * @param addressLabel the label of the per-file address (e.g. {@code Template})
     * @param addressOf maps a display path to its address, or to {@code null} when it has none
     * @return the formatter
     */
    public static SearchResultFormatter withAddress(String fenceLanguage, String addressLabel,
        Function<String, String> addressOf)
    {
        return new SearchResultFormatter(fenceLanguage, addressLabel, addressOf, true);
    }

    /**
     * Renders the result in the given mode.
     *
     * @param outputMode {@link #MODE_FULL}, {@link #MODE_LINES}, {@link #MODE_COUNT} or
     *     {@link #MODE_FILES}; anything else renders as {@code full}
     * @param query the query as the caller sent it (echoed in the heading)
     * @param search the finished scan ({@code full} and {@code lines} need its detailed matches)
     * @param limit the clamped file-row limit for {@code files} (details already capped in the scan)
     * @param offset the non-negative number of matched files to skip in {@code files}
     * @return the Markdown text
     */
    public String format(String outputMode, String query, ResourceTextSearch search, int limit, int offset)
    {
        String filters = addressOf == null ? "'fileMask' or 'metadataType'" : "'fileMask'"; //$NON-NLS-1$ //$NON-NLS-2$
        String text;
        String advice;
        if (MODE_FILES.equals(outputMode))
        {
            text = formatFiles(query, search, limit, offset);
            advice = "use 'outputMode=count' for totals or narrow the search with " + filters + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (MODE_COUNT.equals(outputMode))
        {
            text = formatCount(query, search);
            advice = "use a shorter query or narrow the search with " + filters + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            text = MODE_LINES.equals(outputMode) ? formatLines(query, search) : formatFull(query, search);
            advice = "use 'outputMode=count' for totals, 'outputMode=files' for file pages, " //$NON-NLS-1$
                + "a smaller 'limit', or narrow the search with " + filters + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // File pages already fit at row boundaries. This also handles unusual inputs such as a huge
        // rejected mask echoed by a warning, and keeps truncation advice specific to this mode.
        return OutputSizeGuard.cap(text, OutputSizeGuard.MAX_CONTENT_CHARS, advice);
    }

    private String formatCount(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Count for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(search.getTotalMatches()); //$NON-NLS-1$
        sb.append(" in **").append(search.getTotalMatchedFiles()).append("** files\n"); //$NON-NLS-1$ //$NON-NLS-2$

        appendFileMaskWarning(sb, search, "\n"); //$NON-NLS-1$
        if (search.getSkippedFiles() > 0)
        {
            sb.append(WARNING_PREFIX).append(search.getSkippedFiles())
              .append(" file(s) could not be read\n"); //$NON-NLS-1$
        }
        if (search.wasInterrupted())
        {
            sb.append(INTERRUPTED_WARNING).append('\n');
        }
        return sb.toString();
    }

    private String formatFiles(String query, ResourceTextSearch search, int limit, int offset)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Files for \"").append(filesTitleQuery(query)).append(QUOTE_NEWLINES); //$NON-NLS-1$
        int totalFiles = search.getTotalMatchedFiles();
        sb.append("**Total matches:** ").append(search.getTotalMatches()); //$NON-NLS-1$
        sb.append(" in **").append(totalFiles).append("** files"); //$NON-NLS-1$ //$NON-NLS-2$
        int noticeStart = sb.length();
        sb.append("\n\n"); //$NON-NLS-1$

        appendFileMaskWarning(sb, search, "\n\n"); //$NON-NLS-1$
        if (search.getSkippedFiles() > 0)
        {
            sb.append(WARNING_PREFIX).append(search.getSkippedFiles())
              .append(" file(s) could not be read\n\n"); //$NON-NLS-1$
        }
        if (search.wasInterrupted())
        {
            sb.append(INTERRUPTED_WARNING).append("\n\n"); //$NON-NLS-1$
        }

        if (search.getMatchCountByFile().isEmpty())
        {
            sb.append(emptyResult(search));
            return sb.toString();
        }

        if (offset >= totalFiles)
        {
            return sb.append("No files on this page (offset=").append(offset) //$NON-NLS-1$
                .append("). Use a smaller offset.\n").toString(); //$NON-NLS-1$
        }

        if (addressOf == null)
        {
            sb.append(MarkdownUtils.tableHeader("File", "Matches")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            sb.append(MarkdownUtils.tableHeader("File", addressLabel, "Matches")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        int shown = 0;
        int index = 0;
        // A resource walk need not produce the same order across calls. Page by display path.
        for (Map.Entry<String, Integer> entry : new TreeMap<>(search.getMatchCountByFile()).entrySet())
        {
            if (index++ < offset)
            {
                continue;
            }
            String row = fileRow(entry);
            if (row.length() > FILES_PAGE_BUDGET - sb.length())
            {
                break;
            }
            sb.append(row);
            if (++shown == limit)
            {
                break;
            }
        }
        if (shown == 0)
        {
            return sb.append("\nA file row exceeds the output budget. Narrow the search with 'fileMask'.\n") //$NON-NLS-1$
                .toString();
        }

        sb.insert(noticeStart, Pagination.pageNotice(offset, shown, totalFiles));
        return sb.toString();
    }

    private static String filesTitleQuery(String query)
    {
        if (query.length() <= MAX_FILES_TITLE_QUERY_CHARS)
        {
            return query;
        }
        int end = MAX_FILES_TITLE_QUERY_CHARS;
        if (Character.isHighSurrogate(query.charAt(end - 1)))
        {
            end--;
        }
        return query.substring(0, end) + "..."; //$NON-NLS-1$
    }

    private String fileRow(Map.Entry<String, Integer> entry)
    {
        String count = String.valueOf(entry.getValue());
        if (addressOf == null)
        {
            return MarkdownUtils.tableRow(entry.getKey(), count);
        }
        String address = addressOf.apply(entry.getKey());
        return MarkdownUtils.tableRow(entry.getKey(), address == null ? "-" : address, count); //$NON-NLS-1$
    }

    private String formatFull(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Results for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        appendDetailTotals(sb, search);

        if (search.getMatchesByFile().isEmpty())
        {
            sb.append(emptyResult(search));
            return sb.toString();
        }

        for (Map.Entry<String, List<ResourceTextSearch.Match>> entry : search.getMatchesByFile().entrySet())
        {
            sb.append("### ").append(entry.getKey()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            String address = addressOf == null ? null : addressOf.apply(entry.getKey());
            if (address != null)
            {
                sb.append("**").append(addressLabel).append(":** ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(MarkdownUtils.inlineCode(address)).append("\n\n"); //$NON-NLS-1$
            }

            for (ResourceTextSearch.Match match : entry.getValue())
            {
                sb.append("**Line ").append(match.getLineNumber()).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("```").append(fenceLanguage).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String contextLine : match.getContextLines())
                {
                    sb.append(contextLine).append("\n"); //$NON-NLS-1$
                }
                sb.append("```\n\n"); //$NON-NLS-1$
            }
        }

        return sb.toString();
    }

    /**
     * The compact rendering: the {@code full} totals and warnings, then ONE fence holding a
     * {@code <path>[ [<label>: <address>]]:<line>: <matched line>} row per detailed match. The line
     * is the file's own text ({@link ResourceTextSearch.Match#getLineText()}), never cut back out
     * of the numbered context; the context window is not printed. The fence is the {@code text}
     * language and is lengthened past the longest backtick run of the rows, so no source line can
     * close it.
     */
    private String formatLines(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Lines for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        appendDetailTotals(sb, search);

        if (search.getMatchesByFile().isEmpty())
        {
            sb.append(emptyResult(search));
            return sb.toString();
        }

        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, List<ResourceTextSearch.Match>> entry : search.getMatchesByFile().entrySet())
        {
            String address = addressOf == null ? null : addressOf.apply(entry.getKey());
            String location = address == null ? entry.getKey()
                : entry.getKey() + " [" + addressLabel + ": " + address + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (ResourceTextSearch.Match match : entry.getValue())
            {
                rows.append(location).append(':').append(match.getLineNumber()).append(": ") //$NON-NLS-1$
                    .append(match.getLineText()).append('\n');
            }
        }
        String fence = MarkdownUtils.codeBlockFence(rows);
        sb.append(fence).append("text\n").append(rows).append(fence).append('\n'); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * The totals line, its truncation notice, the warnings and the blank line that {@code full}
     * and {@code lines} share.
     */
    private static void appendDetailTotals(StringBuilder sb, ResourceTextSearch search)
    {
        int totalMatches = search.getTotalMatches();
        sb.append("**Total:** ").append(totalMatches); //$NON-NLS-1$
        sb.append(" matches in ").append(search.getTotalMatchedFiles()).append(" files"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(search.getShownMatches(), totalMatches));
        sb.append("\n"); //$NON-NLS-1$

        appendFileMaskWarning(sb, search, "\n"); //$NON-NLS-1$
        if (search.getSkippedFiles() > 0)
        {
            sb.append(WARNING_PREFIX).append(search.getSkippedFiles())
              .append(" file(s) could not be read (check EDT Error Log)\n"); //$NON-NLS-1$
        }
        if (search.wasInterrupted())
        {
            sb.append(INTERRUPTED_WARNING).append('\n');
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    /**
     * The warning that the {@code fileMask} rejected every file, so nothing was searched; names one
     * real path so the caller sees the shape the mask is matched against. Nothing when the mask
     * selected a file or none was given.
     */
    private static void appendFileMaskWarning(StringBuilder sb, ResourceTextSearch search, String terminator)
    {
        if (!search.fileMaskMatchedNoFile())
        {
            return;
        }
        sb.append(WARNING_PREFIX).append("fileMask ").append(MarkdownUtils.inlineCode(search.getFileMask())) //$NON-NLS-1$
            .append(" matched none of the ").append(search.getFilesOfExtension()) //$NON-NLS-1$
            .append(" file paths, so nothing was searched (paths look like ") //$NON-NLS-1$
            .append(MarkdownUtils.inlineCode(search.getFirstPathOfExtension())).append(')').append(terminator);
    }

    /**
     * The empty-result line: the plain sentinel, unless this rendering fails closed and the scan
     * did not read everything it should have.
     */
    private String emptyResult(ResourceTextSearch search)
    {
        if (!failClosedWhenIncomplete || search.isComplete())
        {
            return NO_MATCHES;
        }
        StringBuilder reason = new StringBuilder();
        if (search.getSkippedFiles() > 0)
        {
            reason.append(search.getSkippedFiles()).append(" file(s) could not be read"); //$NON-NLS-1$
        }
        if (search.wasInterrupted())
        {
            if (reason.length() > 0)
            {
                reason.append("; "); //$NON-NLS-1$
            }
            reason.append("the search was interrupted"); //$NON-NLS-1$
        }
        return "No matches in the files that were read, but the scan is INCOMPLETE (" + reason //$NON-NLS-1$
            + "), so this is not proof that the text is absent - retry the search.\n"; //$NON-NLS-1$
    }
}
