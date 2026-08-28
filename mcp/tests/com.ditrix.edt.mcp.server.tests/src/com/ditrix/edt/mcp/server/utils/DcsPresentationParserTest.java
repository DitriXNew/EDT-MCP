/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com.google.gson.JsonParser;

/** Tests for DCS presentation language handling. */
public class DcsPresentationParserTest
{
    /**
     * An empty declared list is ambiguous - "declares none" and "the Language objects have not
     * resolved yet" look identical. When the caller NAMED a language, resolveLanguage passes it
     * through unvalidated because there is no list to validate against, so a code the caller asked
     * for exists and the write must not be refused over a list we could not read.
     */
    @Test
    public void testPlainStringSurvivesAnEmptyDeclaredListWhenTheCallerNamedALanguage()
    {
        DcsPresentationParser.LanguageContext selected =
            new DcsPresentationParser.LanguageContext(java.util.Collections.<String> emptyList(),
                "ru", null, true); //$NON-NLS-1$
        DcsPresentationParser.ParseResult parsed =
            DcsPresentationParser.parse(new com.google.gson.JsonPrimitive("Margin"), selected, //$NON-NLS-1$
                "title"); //$NON-NLS-1$

        assertTrue(parsed.error(), parsed.isSuccess());
        com._1c.g5.v8.dt.dcs.model.core.Presentation built =
            DcsPresentationParser.build(parsed.plan());
        assertEquals("Margin", built.getLocalValue().getContent().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

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
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru", "uk"), "en", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "uk", //$NON-NLS-1$
                false);
        DcsPresentationParser.LanguageContext selectedEnglish =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru", "uk"), "en", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "uk", //$NON-NLS-1$
                true);

        Presentation defaultPresentation = parsePlainString(defaultUkrainian);
        Presentation selectedPresentation = parsePlainString(selectedEnglish);

        assertEquals("uk", defaultUkrainian.writeLanguageCode()); //$NON-NLS-1$
        assertEquals("uk", new DcsPresentationParser.LanguageContext( //$NON-NLS-1$
            Arrays.asList("en", "uk"), null, "uk", false).writeLanguageCode()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Title", defaultPresentation.getLocalValue().getContent().get("uk")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(defaultPresentation.getLocalValue().getContent().get("en")); //$NON-NLS-1$
        assertEquals("en", selectedEnglish.writeLanguageCode()); //$NON-NLS-1$
        assertEquals("Title", selectedPresentation.getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(selectedPresentation.getLocalValue().getContent().get("uk")); //$NON-NLS-1$
    }

    @Test
    public void testPlainStringRequiresDeclaredLanguageWhenContextExists()
    {
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(
            JsonParser.parseString("\"Title\""), //$NON-NLS-1$
            new DcsPresentationParser.LanguageContext(Collections.emptyList()), "body.title"); //$NON-NLS-1$

        assertFalse(parsed.isSuccess());
        assertTrue(parsed.error(), parsed.error().contains("No language code is available")); //$NON-NLS-1$
        assertTrue(parsed.error(), parsed.error().contains("still opening")); //$NON-NLS-1$
        assertTrue(parsed.error(), parsed.error().contains("body.title")); //$NON-NLS-1$
        assertTrue(parsed.error(),
            parsed.error().contains("add a Language object with a 'languageCode'")); //$NON-NLS-1$
        // Both halves of the advice are pinned: an external-objects project has no Language object
        // to add, so naming only that would hand half the callers an unusable instruction.
        assertTrue(parsed.error(),
            parsed.error().contains("external-objects project")); //$NON-NLS-1$
    }

    @Test
    public void testPlainStringWithoutLanguageContextStillParses()
    {
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(
            JsonParser.parseString("\"Title\""), null, "title"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(parsed.error(), parsed.isSuccess());
        Presentation presentation = DcsPresentationParser.build(parsed.plan());
        assertEquals("Title", presentation.getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Presentation parsePlainString(DcsPresentationParser.LanguageContext languages)
    {
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(
            JsonParser.parseString("\"Title\""), languages, "title"); //$NON-NLS-1$ //$NON-NLS-2$
        return DcsPresentationParser.build(parsed.plan());
    }
}
