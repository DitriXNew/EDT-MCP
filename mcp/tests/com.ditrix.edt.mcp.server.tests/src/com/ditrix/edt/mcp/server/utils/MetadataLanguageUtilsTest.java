/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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

    // --- declared locales / undeclared-locale rejection (issue #298) -----------

    @Test
    public void declaredLanguageCodesListsThemInDeclarationOrderWithoutBlanksOrDuplicates()
    {
        Configuration config =
            config(language("en_CA"), language("en_CA"), language(""), language("fr_CA"), language("en_CA"));
        assertEquals(Arrays.asList("en_CA", "fr_CA"), MetadataLanguageUtils.declaredLanguageCodes(config));
    }

    @Test
    public void declaredLanguageCodesIsEmptyForANullOrLanguagelessConfiguration()
    {
        // EMPTY means "cannot validate", never "nothing is allowed" - the callers rely on that.
        assertTrue(MetadataLanguageUtils.declaredLanguageCodes(null).isEmpty());
        assertTrue(MetadataLanguageUtils.declaredLanguageCodes(config(null)).isEmpty());
    }

    @Test
    public void canonicalLanguageCodeReturnsTheDeclaredSpelling()
    {
        Configuration config = config(language("en_CA"), language("en_CA"), language("fr_CA"));
        assertEquals("en_CA", MetadataLanguageUtils.canonicalLanguageCode(config, "en_CA"));
        // A differently-cased request must be stored under the CONFIGURATION's spelling, not create
        // a second, never-displayed key.
        assertEquals("en_CA", MetadataLanguageUtils.canonicalLanguageCode(config, "EN_ca"));
        assertNull(MetadataLanguageUtils.canonicalLanguageCode(config, "en"));
        assertNull(MetadataLanguageUtils.canonicalLanguageCode(config, null));
    }

    @Test
    public void localesMissingListsTheDeclaredCodesWithNoValue()
    {
        Configuration config = config(language("en_CA"), language("en_CA"), language("fr_CA"));
        assertEquals(Arrays.asList("fr_CA"),
            MetadataLanguageUtils.localesMissing(config, Collections.singletonList("en_CA")));
        assertEquals(Arrays.asList("en_CA", "fr_CA"), MetadataLanguageUtils.localesMissing(config, null));
        assertTrue(MetadataLanguageUtils.localesMissing(config, Arrays.asList("en_CA", "fr_CA")).isEmpty());
    }

    @Test
    public void resolveSynonymLanguageRejectsAnUndeclaredCodeAndListsTheDeclaredOnes()
    {
        // The bug of issue #298: 'en' against a configuration that declares only 'en_CA' used to be
        // accepted, and the value was then never displayed (the platform has no locale fallback).
        Configuration config = config(language("en_CA"), language("en_CA"), language("fr_CA"));
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", "en", "the synonym");
            fail("an undeclared language code must be rejected");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("'en'"));
            assertTrue("the message must name the subject", e.getMessage().contains("the synonym"));
            assertTrue("the message must list what IS declared", e.getMessage().contains("en_CA"));
            assertTrue("the message must list every declared code", e.getMessage().contains("fr_CA"));
        }
    }

    @Test
    public void resolveSynonymLanguageCanonicalizesADeclaredCodesCase()
    {
        Configuration config = config(language("en_CA"), language("en_CA"));
        assertEquals("en_CA",
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", "EN_ca", "the synonym"));
    }

    @Test
    public void resolveSynonymLanguageStillAcceptsAnyCodeWhenNothingIsDeclared()
    {
        // A configuration that declares no language code gives nothing to validate against; refusing
        // every localized write there would be worse than the bug being fixed.
        Configuration config = config(language("ru"));
        assertEquals("de",
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", "de", "the synonym"));
    }

    @Test
    public void resolveSynonymLanguageDoesNotValidateTheFallbackCode()
    {
        // With no explicit code the fallback comes FROM the configuration's own default language, so
        // it is declared by construction (in the real model defaultLanguage REFERENCES one of
        // languages) - hence only an EXPLICIT code is validated.
        Configuration config = config(language("en_CA"), language("en_CA"), language("fr_CA"));
        assertEquals("en_CA",
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", null, "the synonym"));
    }

    @Test
    public void resolveSynonymLanguageAcceptsACodeTheSameCallDeclares()
    {
        // One modify batch can set a Language's languageCode AND a localized value under that very
        // code. Validating against the model alone would reject the second half of an edit whose
        // first half declares the code (codex review on #298).
        Configuration config = config(language("en"), language("en"));
        assertEquals("fr", MetadataLanguageUtils.resolveSynonymLanguage(config, "Francais", "fr",
            "the synonym", Collections.singletonList("fr")));
        // ... and a code nobody declares, pending or not, is still refused.
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Deutsch", "de", "the synonym",
                Collections.singletonList("fr"));
            fail("a code neither declared nor pending must still be rejected");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("'de'"));
            assertTrue("the message must list the pending code as available too",
                e.getMessage().contains("fr"));
        }
    }

    @Test
    public void declaredLanguageCodesAppendsThePendingOnesAfterTheDeclaredOnes()
    {
        Configuration config = config(language("en"), language("en"));
        assertEquals(Arrays.asList("en", "fr"),
            MetadataLanguageUtils.declaredLanguageCodes(config, Arrays.asList("fr", "en", "", null)));
        assertEquals(Arrays.asList("en"), MetadataLanguageUtils.declaredLanguageCodes(config, null));
    }
}
