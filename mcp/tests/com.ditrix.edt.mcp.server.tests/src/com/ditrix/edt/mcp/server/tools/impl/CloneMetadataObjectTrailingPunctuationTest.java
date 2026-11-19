/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.SonarRegexRewriteTest;

/**
 * {@link CloneMetadataObjectTool#withoutTrailingPunctuation} strips exactly what
 * {@code replaceFirst("[.!?]+$", "")} stripped, without retrying a long inner run from each of its
 * characters.
 */
public class CloneMetadataObjectTrailingPunctuationTest
{
    @Test
    public void testItStripsExactlyWhatTheFormerReplacementStripped()
    {
        for (String input : SonarRegexRewriteTest.allStrings(".!?a\n\r", 6)) //$NON-NLS-1$
        {
            assertEquals(SonarRegexRewriteTest.escape(input), input.replaceFirst("[.!?]+$", ""), //$NON-NLS-1$ //$NON-NLS-2$
                CloneMetadataObjectTool.withoutTrailingPunctuation(input));
        }
    }

    @Test
    public void testTheMessagesKeepTheirShape()
    {
        assertEquals("Model is busy", CloneMetadataObjectTool.withoutTrailingPunctuation("Model is busy...")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("v1.2 failed", CloneMetadataObjectTool.withoutTrailingPunctuation("v1.2 failed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test(timeout = 3000)
    public void testALongInnerRunIsScannedOnce()
    {
        String text = ".".repeat(60_000) + "x"; //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(text, CloneMetadataObjectTool.withoutTrailingPunctuation(text));
    }
}
