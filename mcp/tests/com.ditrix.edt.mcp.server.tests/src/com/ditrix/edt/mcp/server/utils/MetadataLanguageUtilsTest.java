/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;

/**
 * Unit tests for {@link MetadataLanguageUtils}.
 * <p>
 * The synonym map is keyed by the language CODE (e.g. "ru"/"en"), so resolution
 * must use {@link Language#getLanguageCode()} - never {@link Language#getName()} -
 * and must never hardcode "ru".
 */
public class MetadataLanguageUtilsTest
{
    private static Language language(String code)
    {
        Language lang = mock(Language.class);
        when(lang.getLanguageCode()).thenReturn(code);
        return lang;
    }

    private static Configuration config(Language defaultLanguage, Language... configured)
    {
        Configuration config = mock(Configuration.class);
        when(config.getDefaultLanguage()).thenReturn(defaultLanguage);
        when(config.getLanguages()).thenReturn(new BasicEList<>(Arrays.asList(configured)));
        return config;
    }

    // --- resolveLanguageCode ---------------------------------------------------

    @Test
    public void resolveExplicitWins()
    {
        assertEquals("en", MetadataLanguageUtils.resolveLanguageCode(config(language("ru")), "en"));
    }

    @Test
    public void resolveFallsBackToDefaultLanguageCodeWhenExplicitNull()
    {
        assertEquals("ru", MetadataLanguageUtils.resolveLanguageCode(config(language("ru")), null));
    }

    @Test
    public void resolveFallsBackToDefaultLanguageCodeWhenExplicitEmpty()
    {
        assertEquals("en", MetadataLanguageUtils.resolveLanguageCode(config(language("en")), ""));
    }

    @Test
    public void resolveUsesCodeNotName()
    {
        // getName() would return "Russian"; the code is "ru". The map is keyed by code.
        Language lang = mock(Language.class);
        when(lang.getLanguageCode()).thenReturn("ru");
        when(lang.getName()).thenReturn("Russian");
        assertEquals("ru", MetadataLanguageUtils.resolveLanguageCode(config(lang), null));
    }

    @Test
    public void resolveFallsBackToFirstConfiguredLanguageWhenNoDefault()
    {
        assertEquals("en",
            MetadataLanguageUtils.resolveLanguageCode(config(null, language("en"), language("ru")), null));
    }

    @Test
    public void resolveSkipsBlankDefaultCodeAndUsesFirstConfigured()
    {
        assertEquals("de",
            MetadataLanguageUtils.resolveLanguageCode(config(language(""), language("de")), null));
    }

    @Test
    public void resolveReturnsNullWhenNothingAvailable()
    {
        Configuration config = mock(Configuration.class);
        when(config.getDefaultLanguage()).thenReturn(null);
        when(config.getLanguages()).thenReturn(new BasicEList<>(Collections.<Language> emptyList()));
        assertNull(MetadataLanguageUtils.resolveLanguageCode(config, null));
    }

    @Test
    public void resolveReturnsNullForNullConfig()
    {
        assertNull(MetadataLanguageUtils.resolveLanguageCode(null, null));
    }

    // --- getSynonymForLanguage -------------------------------------------------

    private static Map<String, String> synonyms(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void synonymHitByCode()
    {
        assertEquals("Catalog",
            MetadataLanguageUtils.getSynonymForLanguage(synonyms("ru", "Справочник", "en", "Catalog"), "en"));
    }

    @Test
    public void synonymKeyedByCodeNotName()
    {
        // The map is keyed by code "ru"; looking up by the NAME "Russian" misses and
        // falls back to the first non-empty value (still the Russian synonym).
        assertEquals("Справочник",
            MetadataLanguageUtils.getSynonymForLanguage(synonyms("ru", "Справочник"), "Russian"));
    }

    @Test
    public void synonymMissingCodeFallsBackToFirstNonEmpty()
    {
        assertEquals("Справочник",
            MetadataLanguageUtils.getSynonymForLanguage(synonyms("ru", "Справочник"), "en"));
    }

    @Test
    public void synonymSkipsEmptyValuesInFallback()
    {
        assertEquals("Catalog",
            MetadataLanguageUtils.getSynonymForLanguage(synonyms("ru", "", "en", "Catalog"), "de"));
    }

    @Test
    public void synonymNullCodeFallsBackToFirstNonEmpty()
    {
        assertEquals("Catalog",
            MetadataLanguageUtils.getSynonymForLanguage(synonyms("en", "Catalog"), null));
    }

    @Test
    public void synonymNullMapReturnsEmpty()
    {
        assertEquals("", MetadataLanguageUtils.getSynonymForLanguage(null, "ru"));
    }

    @Test
    public void synonymEmptyMapReturnsEmpty()
    {
        assertEquals("", MetadataLanguageUtils.getSynonymForLanguage(new LinkedHashMap<String, String>(), "ru"));
    }

    // --- resolveSynonymLanguage (the shared resolve-or-error block, used at 4 sites) ---

    @Test
    public void resolveSynonymLanguageReturnsNullForAbsentValue()
    {
        // No localized value -> nothing to localize, no error (even with no resolvable code).
        assertNull(MetadataLanguageUtils.resolveSynonymLanguage(null, null, null, "the synonym"));
        assertNull(MetadataLanguageUtils.resolveSynonymLanguage(null, "", null, "the synonym"));
    }

    @Test
    public void resolveSynonymLanguageResolvesCodeForPresentValue()
    {
        assertEquals("en",
            MetadataLanguageUtils.resolveSynonymLanguage(config(language("ru")), "Goods", "en", "the synonym"));
        assertEquals("ru",
            MetadataLanguageUtils.resolveSynonymLanguage(config(language("ru")), "Goods", null, "the synonym"));
    }

    @Test
    public void resolveSynonymLanguageThrowsActionableErrorWhenUndeterminable()
    {
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(null, "Goods", null, "the title");
            org.junit.Assert.fail("an undeterminable language code must throw");
        }
        catch (IllegalArgumentException e)
        {
            // The message is ToolResult-ready: it names the subject and the fix.
            org.junit.Assert.assertTrue("message must name the subject",
                e.getMessage().contains("the title"));
            org.junit.Assert.assertTrue("message must suggest the 'language' parameter",
                e.getMessage().contains("'language'"));
        }
    }
}
