/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders a {@link ResourceTextSearch} result as Markdown in the three output modes the full-text
 * search tools share: {@code full} (matches with context, capped), {@code count} (exact totals
 * only) and {@code files} (one row per matched file with its exact count).
 * <p>
 * The text is the {@code search_in_code} contract, byte for byte: {@link #forCode()} configures
 * nothing beyond the {@code bsl} code fence. A tool may add two things and nothing else:
 * <ul>
 * <li>a per-file <b>address</b> (e.g. the DCS template FQN a {@code .dcs} file belongs to),
 * printed under the file heading in {@code full} mode and as an extra column in {@code files}
 * mode; a file without one prints none;</li>
 * <li>a <b>fail-closed empty result</b>: when nothing matched but the scan was incomplete, the
 * {@code "No matches found."} sentinel is replaced by a line that says the scan is incomplete,
 * so an absence is never claimed on files that were never read.</li>
 * </ul>
 */
public final class SearchResultFormatter
{
    /** Output mode: matches with context. */
    public static final String MODE_FULL = "full"; //$NON-NLS-1$

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
     * @param outputMode {@link #MODE_FULL}, {@link #MODE_COUNT} or {@link #MODE_FILES}; anything
     *     else renders as {@code full}
     * @param query the query as the caller sent it (echoed in the heading)
     * @param search the finished scan
     * @return the Markdown text
     */
    public String format(String outputMode, String query, ResourceTextSearch search)
    {
        if (MODE_COUNT.equals(outputMode))
        {
            return formatCount(query, search);
        }
        else if (MODE_FILES.equals(outputMode))
        {
            return formatFiles(query, search);
        }
        return formatFull(query, search);
    }

    private String formatCount(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Count for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(search.getTotalMatches()); //$NON-NLS-1$
        sb.append(" in **").append(search.getTotalMatchedFiles()).append("** files\n"); //$NON-NLS-1$ //$NON-NLS-2$

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

    private String formatFiles(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Files for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(search.getTotalMatches()); //$NON-NLS-1$
        sb.append(" in **").append(search.getTotalMatchedFiles()).append("** files\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

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

        if (addressOf == null)
        {
            sb.append(MarkdownUtils.tableHeader("File", "Matches")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            sb.append(MarkdownUtils.tableHeader("File", addressLabel, "Matches")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        for (Map.Entry<String, Integer> entry : search.getMatchCountByFile().entrySet())
        {
            String count = String.valueOf(entry.getValue());
            if (addressOf == null)
            {
                sb.append(MarkdownUtils.tableRow(entry.getKey(), count));
            }
            else
            {
                String address = addressOf.apply(entry.getKey());
                sb.append(MarkdownUtils.tableRow(entry.getKey(), address == null ? "-" : address, count)); //$NON-NLS-1$
            }
        }

        return sb.toString();
    }

    private String formatFull(String query, ResourceTextSearch search)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Results for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$

        int totalMatches = search.getTotalMatches();
        sb.append("**Total:** ").append(totalMatches); //$NON-NLS-1$
        sb.append(" matches in ").append(search.getTotalMatchedFiles()).append(" files"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(search.getShownMatches(), totalMatches));
        sb.append("\n"); //$NON-NLS-1$

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
