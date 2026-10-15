/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;

/**
 * Shared pagination helpers for MCP tools.
 *
 * <p>The single place that clamps a requested {@code limit} into a sane range.
 * Tools used to inline {@code Math.min(Math.max(1, limit), MAX)} dozens of
 * times; route through {@link #clampLimit(int, int)} instead.</p>
 *
 * <p>The {@link #DEFAULT_LIMIT} and {@link #MAX_LIMIT} constants are the
 * project-wide canonical values, but individual tools may still pass their own
 * max to {@link #clampLimit(int, int)} until defaults are unified in a later,
 * attended (output-changing) slice.</p>
 */
public final class Pagination
{
    /** Canonical default page size for tools that do not specify their own. */
    public static final int DEFAULT_LIMIT = 100;

    /** Canonical maximum page size for tools that do not specify their own. */
    public static final int MAX_LIMIT = 1000;

    private Pagination()
    {
        // Utility class - no instantiation
    }

    /**
     * Clamps a requested limit into the inclusive range {@code [1, maxLimit]}.
     * A request below 1 becomes 1; a request above {@code maxLimit} becomes
     * {@code maxLimit}.
     *
     * @param rawLimit the requested limit (may be out of range)
     * @param maxLimit the upper bound to clamp to
     * @return the clamped limit, never below 1 and never above {@code maxLimit}
     */
    public static int clampLimit(int rawLimit, int maxLimit)
    {
        return Math.min(Math.max(1, rawLimit), maxLimit);
    }

    /**
     * Canonical truncation notice for a tool that knows the true total. Append it
     * after the count header (e.g. {@code "**Total:** " + total + " modules"}).
     * Returns {@code " (showing <shown> of <total>)"} when the result was
     * truncated, or an empty string when everything is shown.
     *
     * @param shown the number of items actually rendered
     * @param total the true total number of items
     * @return the notice text (empty when {@code total <= shown})
     */
    public static String truncationNotice(int shown, int total)
    {
        if (total > shown)
        {
            return " (showing " + shown + " of " + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Canonical truncation notice for a tool that caps collection at the limit
     * and therefore does NOT know the true total (only that the limit was hit).
     * Append it after the count header when the cap was reached.
     *
     * @param limit the limit that was reached
     * @return the notice text
     */
    public static String limitReachedNotice(int limit)
    {
        return " (showing first " + limit + ", limit reached; pass a higher limit for more)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An inline listing bounded at {@code limit}, with the rest counted: {@code a, b, ... (N more)}.
     * The first {@code limit} items are printed in the order given, joined by {@code ", "}; when any
     * are left out, {@code ", ... (N more)"} follows, {@code N} being how many. A list of at most
     * {@code limit} items is printed whole, with nothing appended.
     *
     * <p>For a refusal or a table cell that names the values a caller may choose from, where the whole
     * list can be long - a data-lock field list of an ERP-size document runs to several KB. Unlike
     * {@link #truncationNotice}, nothing is paged here: the reader learns how many were left out, not
     * how to fetch them. New code calls this rather than spelling the suffix out again.</p>
     *
     * @param items the items, in the order to print them ({@code null} lists nothing)
     * @param limit how many items to print before the rest is counted; at least 1
     * @return the listing, or an empty string when there are no items
     * @throws IllegalArgumentException if {@code limit} is below 1
     */
    public static String boundedListing(List<String> items, int limit)
    {
        if (limit < 1)
        {
            throw new IllegalArgumentException("a bounded listing prints at least 1 item, got limit " + limit); //$NON-NLS-1$
        }
        if (items == null || items.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        int shown = Math.min(limit, items.size());
        String joined = String.join(", ", items.subList(0, shown)); //$NON-NLS-1$
        int rest = items.size() - shown;
        return rest > 0 ? joined + ", ... (" + rest + " more)" : joined; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
