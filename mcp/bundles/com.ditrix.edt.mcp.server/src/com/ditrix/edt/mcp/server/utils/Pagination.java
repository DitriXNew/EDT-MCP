/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

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
}
