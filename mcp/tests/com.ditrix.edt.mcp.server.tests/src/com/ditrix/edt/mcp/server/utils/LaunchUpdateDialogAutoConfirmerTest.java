/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the pure decision of {@link LaunchUpdateDialogAutoConfirmer}: which
 * shell title is the "Application update" launch modal it auto-confirms.
 *
 * <p>The SWT plumbing ({@code arm}/{@code disarm} + the {@code Display} filter)
 * is exercised only live (it needs a real workbench); here we lock down the
 * exact-match contract so the filter never fires on an unrelated dialog, plus
 * the no-op safety of an unbalanced {@code disarm}.
 */
public class LaunchUpdateDialogAutoConfirmerTest
{
    @Test
    public void testExactTitleMatches()
    {
        assertTrue("the exact EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update"));
    }

    @Test
    public void testDifferentTitleDoesNotMatch()
    {
        assertFalse("an unrelated dialog title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Save resources"));
    }

    @Test
    public void testMatchIsCaseSensitive()
    {
        assertFalse("matching is exact, not case-insensitive",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("application update"));
    }

    @Test
    public void testWhitespaceVariantDoesNotMatch()
    {
        assertFalse("a trailing-space variant must not match (exact compare)",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update "));
    }

    @Test
    public void testNullTitleDoesNotMatch()
    {
        assertFalse("a null shell title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(null));
    }

    @Test
    public void testUnbalancedDisarmIsNoOp()
    {
        // Releasing without a prior arm() must not throw or touch a Display:
        // with no filter installed it returns before any UI access.
        LaunchUpdateDialogAutoConfirmer.disarm();
        LaunchUpdateDialogAutoConfirmer.disarm();
    }

    @Test
    public void testRussianTitleMatches()
    {
        // A Russian EDT build shows this localized title; the English-only match used
        // to miss it, so the launch hung on the modal (a root cause of YAXUnit "dancing").
        assertTrue("the Russian EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(
                LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU));
    }

    @Test
    public void testRussianTitleEncoding()
    {
        // Guard the exact codepoints so a re-encode can't silently break the match
        // against the live "Обновление приложения" dialog (О=U+041E … я=U+044F, 21 chars).
        String ru = LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU;
        assertEquals(21, ru.length());
        assertEquals(0x041E, ru.charAt(0));
        assertEquals(0x044F, ru.charAt(ru.length() - 1));
    }
}
