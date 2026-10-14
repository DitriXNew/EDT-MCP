/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

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

    // ========== truncationNotice (known total) ==========

    @Test
    public void testTruncationNoticeWhenTruncated()
    {
        assertEquals(" (showing 50 of 200)", Pagination.truncationNotice(50, 200));
    }

    @Test
    public void testTruncationNoticeEmptyWhenAllShown()
    {
        assertEquals("", Pagination.truncationNotice(200, 200));
        assertEquals("", Pagination.truncationNotice(200, 150));
    }

    // ========== limitReachedNotice (capped, unknown total) ==========

    @Test
    public void testLimitReachedNoticeMentionsLimitAndIsActionable()
    {
        String notice = Pagination.limitReachedNotice(100);
        assertTrue(notice.contains("100"));
        assertTrue(notice.contains("limit reached"));
        assertTrue(notice.contains("higher limit"));
    }

    // ========== boundedListing (inline listing, the rest counted) ==========

    @Test
    public void testBoundedListingPrintsAListAtTheLimitWhole()
    {
        assertEquals("a, b, c", Pagination.boundedListing(Arrays.asList("a", "b", "c"), 3));
    }

    @Test
    public void testBoundedListingCountsOneItemPastTheLimitAsOneMore()
    {
        assertEquals("a, b, c, ... (1 more)", Pagination.boundedListing(Arrays.asList("a", "b", "c", "d"), 3));
    }

    @Test
    public void testBoundedListingPrintsTheFirstItemsInTheOrderGivenAndCountsTheRest()
    {
        assertEquals("Weight, Code, Description, ... (2 more)",
            Pagination.boundedListing(Arrays.asList("Weight", "Code", "Description", "Number", "Parent"), 3));
    }

    @Test
    public void testBoundedListingOfASingleItemIsTheItemItself()
    {
        assertEquals("a", Pagination.boundedListing(Collections.singletonList("a"), 25));
    }

    @Test
    public void testBoundedListingOfNoItemsIsEmpty()
    {
        assertEquals("", Pagination.boundedListing(Collections.<String> emptyList(), 25));
        assertEquals("", Pagination.boundedListing(null, 25));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBoundedListingRefusesALimitBelowOne()
    {
        Pagination.boundedListing(Arrays.asList("a", "b"), 0);
    }
}
