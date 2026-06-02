/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link Pagination}.
 * Verifies the shared limit-clamping helper and the canonical constants.
 */
public class PaginationTest
{
    // ========== clampLimit ==========

    @Test
    public void testClampLimitWithinRangeReturnsUnchanged()
    {
        assertEquals(50, Pagination.clampLimit(50, 1000));
    }

    @Test
    public void testClampLimitAtLowerBoundReturnsOne()
    {
        assertEquals(1, Pagination.clampLimit(1, 1000));
    }

    @Test
    public void testClampLimitAtUpperBoundReturnsMax()
    {
        assertEquals(1000, Pagination.clampLimit(1000, 1000));
    }

    @Test
    public void testClampLimitZeroClampedToOne()
    {
        assertEquals(1, Pagination.clampLimit(0, 1000));
    }

    @Test
    public void testClampLimitNegativeClampedToOne()
    {
        assertEquals(1, Pagination.clampLimit(-100, 1000));
    }

    @Test
    public void testClampLimitAboveMaxClampedToMax()
    {
        assertEquals(1000, Pagination.clampLimit(5000, 1000));
    }

    @Test
    public void testClampLimitRespectsCustomMax()
    {
        assertEquals(200, Pagination.clampLimit(5000, 200));
        assertEquals(150, Pagination.clampLimit(150, 200));
    }

    @Test
    public void testClampLimitMaxOfOneClampsEverythingToOne()
    {
        // When maxLimit is 1, both the lower and upper bound are 1.
        assertEquals(1, Pagination.clampLimit(0, 1));
        assertEquals(1, Pagination.clampLimit(1, 1));
        assertEquals(1, Pagination.clampLimit(99, 1));
    }

    // ========== constants ==========

    @Test
    public void testCanonicalConstants()
    {
        assertEquals(100, Pagination.DEFAULT_LIMIT);
        assertEquals(1000, Pagination.MAX_LIMIT);
    }
}
