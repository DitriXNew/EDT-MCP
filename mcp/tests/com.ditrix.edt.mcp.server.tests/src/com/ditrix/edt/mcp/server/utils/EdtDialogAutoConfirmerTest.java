/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.junit.Test;

/**
 * Tests the pure title-matching decision of {@link EdtDialogAutoConfirmer} and
 * the no-op safety of an unbalanced {@code disarm}. The SWT plumbing
 * ({@code arm}/{@code disarm} + the {@code Display} filter) is exercised only
 * live (it needs a real workbench).
 */
public class EdtDialogAutoConfirmerTest
{
    private static EdtDialogAutoConfirmer confirmer(String... titles)
    {
        return new EdtDialogAutoConfirmer(new LinkedHashSet<>(Arrays.asList(titles)));
    }

    @Test
    public void testConfiguredTitlesMatch()
    {
        EdtDialogAutoConfirmer c = confirmer("Application update", "Database restructuring");
        assertTrue(c.isTargetTitle("Application update"));
        assertTrue(c.isTargetTitle("Database restructuring"));
    }

    @Test
    public void testUnconfiguredTitleDoesNotMatch()
    {
        EdtDialogAutoConfirmer c = confirmer("Application update");
        assertFalse(c.isTargetTitle("Save resources"));
    }

    @Test
    public void testMatchIsCaseSensitive()
    {
        EdtDialogAutoConfirmer c = confirmer("Application update");
        assertFalse(c.isTargetTitle("application update"));
    }

    @Test
    public void testNullTitleDoesNotMatch()
    {
        EdtDialogAutoConfirmer c = confirmer("Application update");
        assertFalse(c.isTargetTitle(null));
    }

    @Test
    public void testInstancesAreIndependent()
    {
        EdtDialogAutoConfirmer a = confirmer("A");
        EdtDialogAutoConfirmer b = confirmer("B");
        assertTrue(a.isTargetTitle("A"));
        assertFalse("each instance matches only its own titles", a.isTargetTitle("B"));
        assertTrue(b.isTargetTitle("B"));
        assertFalse(b.isTargetTitle("A"));
    }

    @Test
    public void testUnbalancedDisarmIsNoOp()
    {
        // Releasing without a prior arm() must not throw or touch a Display.
        EdtDialogAutoConfirmer c = confirmer("Application update");
        c.disarm();
        c.disarm();
    }
}
