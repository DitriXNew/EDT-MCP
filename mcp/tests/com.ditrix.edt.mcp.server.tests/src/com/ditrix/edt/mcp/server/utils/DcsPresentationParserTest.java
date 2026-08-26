/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com.google.gson.JsonParser;

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

    @Test
    public void testBuildNullReturnsNoPresentation()
    {
        assertNull(DcsPresentationParser.build(null));
    }

    @Test
    public void testPlainStringUsesSelectedLanguageOrConfigurationDefault()
    {
        DcsPresentationParser.LanguageContext defaultUkrainian =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "uk"), "uk", "uk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                false);
        DcsPresentationParser.LanguageContext selectedEnglish =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "uk"), "en", "uk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                true);

        Presentation defaultPresentation = parsePlainString(defaultUkrainian);
        Presentation selectedPresentation = parsePlainString(selectedEnglish);

        assertEquals("uk", defaultUkrainian.writeLanguageCode()); //$NON-NLS-1$
        assertEquals("uk", new DcsPresentationParser.LanguageContext( //$NON-NLS-1$
            Arrays.asList("en", "uk"), null, "uk").writeLanguageCode()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Title", defaultPresentation.getLocalValue().getContent().get("uk")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(defaultPresentation.getLocalValue().getContent().get("en")); //$NON-NLS-1$
        assertEquals("en", selectedEnglish.writeLanguageCode()); //$NON-NLS-1$
        assertEquals("Title", selectedPresentation.getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(selectedPresentation.getLocalValue().getContent().get("uk")); //$NON-NLS-1$
    }

    private static Presentation parsePlainString(DcsPresentationParser.LanguageContext languages)
    {
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(
            JsonParser.parseString("\"Title\""), languages, "title"); //$NON-NLS-1$ //$NON-NLS-2$
        return DcsPresentationParser.build(parsed.plan());
    }
}
