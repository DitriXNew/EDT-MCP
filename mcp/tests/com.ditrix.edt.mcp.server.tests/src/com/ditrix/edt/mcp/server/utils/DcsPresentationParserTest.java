/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Tests for DCS presentation language handling. */
public class DcsPresentationParserTest
{
    @Test
    public void testResolvedLanguageOverridesDeclarationOrder()
    {
        DcsPresentationParser.LanguageContext russian = new DcsPresentationParser.LanguageContext(
            Arrays.asList("en", "ru"), "ru"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsPresentationParser.LanguageContext english = new DcsPresentationParser.LanguageContext(
            Arrays.asList("ru", "en"), "en"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("ru", russian.resolvedCode()); //$NON-NLS-1$
        assertEquals("en", english.resolvedCode()); //$NON-NLS-1$
    }

    @Test
    public void testSingleArgumentContextKeepsDeclarationOrderFallback()
    {
        DcsPresentationParser.LanguageContext declared = new DcsPresentationParser.LanguageContext(
            Arrays.asList("ru", "en")); //$NON-NLS-1$ //$NON-NLS-2$
        DcsPresentationParser.LanguageContext empty =
            new DcsPresentationParser.LanguageContext(Collections.emptyList());

        assertEquals("ru", declared.resolvedCode()); //$NON-NLS-1$
        assertEquals("en", empty.resolvedCode()); //$NON-NLS-1$
    }
}
