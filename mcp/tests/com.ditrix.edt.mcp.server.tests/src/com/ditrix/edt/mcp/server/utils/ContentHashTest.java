/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ContentHash}, the optimistic-lock revision token used by the
 * read/write module tools. Pure and headless (no Eclipse workspace).
 */
public class ContentHashTest
{
    @Test
    public void testTokenIsStableForSameInput()
    {
        String text = "Procedure A()\nEndProcedure\n"; //$NON-NLS-1$
        assertEquals(ContentHash.of(text), ContentHash.of(text));
    }

    @Test
    public void testTokenIsSixteenLowercaseHexChars()
    {
        String token = ContentHash.of("anything"); //$NON-NLS-1$
        assertEquals("token must be 16 chars", 16, token.length()); //$NON-NLS-1$
        assertTrue("token must be lowercase hex", token.matches("[0-9a-f]{16}")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCrlfAndLfHashIdentically()
    {
        // The token must be line-ending agnostic so a CRLF/LF difference alone is
        // never a spurious lost-update mismatch (read and write normalize the same way).
        assertEquals(ContentHash.of("a\r\nb\r\n"), ContentHash.of("a\nb\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullHashesAsEmpty()
    {
        assertEquals(ContentHash.of(null), ContentHash.of("")); //$NON-NLS-1$
    }

    @Test
    public void testDifferentContentDiffersWithHighProbability()
    {
        assertNotEquals(ContentHash.of("x = 1;"), ContentHash.of("x = 2;")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMatchesRoundTripsOwnToken()
    {
        String text = "Функция Ф()\nКонецФункции\n"; //$NON-NLS-1$
        assertTrue(ContentHash.matches(text, ContentHash.of(text)));
    }

    @Test
    public void testMatchesToleratesCrlfDifference()
    {
        // Agent read CRLF content, server now sees LF (or vice versa): still a match.
        assertTrue(ContentHash.matches("a\nb\n", ContentHash.of("a\r\nb\r\n"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMatchesToleratesSurroundingQuotesAndCase()
    {
        String text = "data"; //$NON-NLS-1$
        String token = ContentHash.of(text);
        assertTrue("surrounding quotes (a YAML scalar) must still match", //$NON-NLS-1$
            ContentHash.matches(text, "\"" + token + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("uppercased token must still match", //$NON-NLS-1$
            ContentHash.matches(text, token.toUpperCase()));
        assertTrue("padded token must still match", //$NON-NLS-1$
            ContentHash.matches(text, "  " + token + "  ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMatchesRejectsWrongOrBlankToken()
    {
        String text = "data"; //$NON-NLS-1$
        assertFalse(ContentHash.matches(text, "0000000000000000")); //$NON-NLS-1$
        assertFalse("null never matches", ContentHash.matches(text, null)); //$NON-NLS-1$
        assertFalse("blank never matches", ContentHash.matches(text, "   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty quotes never matches", ContentHash.matches(text, "\"\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
