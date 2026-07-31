/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.eclipse.emf.common.util.BasicEMap;
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

    /** A configuration whose OWN synonym carries text for the given codes only. */
    private static Configuration configWithSynonym(Map<String, String> synonym, Language... configured)
    {
        Configuration config = config(configured.length == 0 ? null : configured[0], configured);
        BasicEMap<String, String> map = new BasicEMap<>();
        synonym.forEach(map::put);
        when(config.getSynonym()).thenReturn(map);
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
        // 'en' has to BE declared for an explicit 'en' to resolve - an undeclared code is refused,
        // and a configuration that declares nothing refuses every code (see the tests below).
        Configuration bilingual = config(language("ru"), language("ru"), language("en"));
        assertEquals("en",
            MetadataLanguageUtils.resolveSynonymLanguage(bilingual, "Goods", "en", "the synonym"));
        // The FALLBACK is the default language's own code and needs no declaration lookup.
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
        // Named in BOTH languages: only then is either of them owed a translation (a configuration
        // that is named in neither uses neither - see localesInUseIsEmptyWhenTheConfigurationIsNamedInNoLanguage).
        Configuration config = configWithSynonym(
            new java.util.LinkedHashMap<>(Map.of("en_CA", "Trade", "fr_CA", "Commerce")),
            language("en_CA"), language("fr_CA"));
        assertEquals(Arrays.asList("fr_CA"),
            MetadataLanguageUtils.localesMissing(config, Collections.singletonList("en_CA")));
        assertEquals(Arrays.asList("en_CA", "fr_CA"), MetadataLanguageUtils.localesMissing(config, null));
        assertTrue(MetadataLanguageUtils.localesMissing(config, Arrays.asList("en_CA", "fr_CA")).isEmpty());
    }

    @Test
    public void localesInUseKeepsOnlyTheLanguagesTheConfigurationFillsIn()
    {
        // Declared: en_CA + fr_CA; the configuration itself is named in en_CA only. fr_CA is
        // declared but nobody is translating into it - a single-language branch of a multilingual
        // configuration - so it is not a translation gap.
        Configuration config = configWithSynonym(Map.of("en_CA", "Trade"), language("en_CA"),
            language("fr_CA"));
        assertEquals(Arrays.asList("en_CA"), MetadataLanguageUtils.localesInUse(config));
    }

    @Test
    public void localesInUseIsEmptyWhenTheConfigurationIsNamedInNoLanguage()
    {
        // A configuration whose own synonym is empty everywhere uses NO language. Treating them all
        // as in use would suppress the confirmation the caller is owed and then demand translations
        // into every declared language on top of it.
        Configuration config = configWithSynonym(Map.of(), language("en_CA"), language("fr_CA"));
        assertTrue(MetadataLanguageUtils.localesInUse(config).isEmpty());
        assertTrue(MetadataLanguageUtils.isDeclaredButUnused(config, "en_CA"));
        assertTrue(MetadataLanguageUtils.localesInUse(null).isEmpty());
    }

    @Test
    public void localesInUseAnswersAboutTheCodesTHISCallDeclares()
    {
        // A batch that declares a new language code in the very call that writes under it has a
        // declaration set the model does not carry yet. Asking the model would answer for the
        // before-state: the new code could never be in use, and the one write that just started
        // translating into it would be reported as a language nobody translates into.
        Configuration config = configWithSynonym(Map.of("en_CA", "Trade"), language("en_CA"));
        assertEquals(Arrays.asList("en_CA"),
            MetadataLanguageUtils.localesInUse(config, Arrays.asList("en_CA", "de")));

        // With NO synonym at all NOTHING is in use - not even the code this call declares. The
        // write into it is the one that has to ask for confirmation.
        Configuration fresh = configWithSynonym(Map.of(), language("en_CA"));
        assertTrue(MetadataLanguageUtils.localesInUse(fresh, Arrays.asList("en_CA", "de")).isEmpty());
        assertTrue(MetadataLanguageUtils.localesInUse(fresh, null).isEmpty());
        // A null configuration is a different thing entirely: nothing to read, so nothing is
        // claimed either way and the caller's declared set stands.
        assertEquals(Arrays.asList("en_CA", "de"),
            MetadataLanguageUtils.localesInUse(null, Arrays.asList("en_CA", "de")));
    }

    @Test
    public void localesInUseIsEmptyForOrphanedTextAndForNoTextAtAll()
    {
        // Two ways to end up with nothing in use, and both answer the same: a batch that RENAMES
        // the only language's code leaves the configuration's name under the OLD key (text exists,
        // but no DECLARED language carries it), and a configuration nobody has named yet has no
        // text at all. Either way the write is the one that must ask for confirmation.
        Configuration renamed = configWithSynonym(Map.of("en", "Trade"), language("fr"));
        assertTrue(MetadataLanguageUtils.localesInUse(renamed, Arrays.asList("fr")).isEmpty());
        assertTrue(MetadataLanguageUtils.isDeclaredButUnused(renamed, "fr"));

        Configuration fresh = configWithSynonym(Map.of(), language("fr"));
        assertTrue(MetadataLanguageUtils.localesInUse(fresh, Arrays.asList("fr")).isEmpty());
        assertTrue(MetadataLanguageUtils.isDeclaredButUnused(fresh, "fr"));
    }

    @Test
    public void localesInUseKeepsDeclarationOrder()
    {
        // The synonym map's own iteration order is not the declaration order callers read the
        // report in, so a configuration named in both languages must still answer in that order.
        Configuration config = configWithSynonym(new java.util.LinkedHashMap<>(
            Map.of("fr_CA", "Commerce", "en_CA", "Trade")), language("en_CA"), language("fr_CA"));
        assertEquals(Arrays.asList("en_CA", "fr_CA"), MetadataLanguageUtils.localesInUse(config));
    }

    @Test
    public void localesMissingSkipsADeclaredLanguageTheConfigurationDoesNotUse()
    {
        Configuration config = configWithSynonym(Map.of("en_CA", "Trade"), language("en_CA"),
            language("fr_CA"));
        // Without the in-use filter this would report fr_CA and nag about a language the
        // configuration is not translated into either.
        assertTrue(MetadataLanguageUtils.localesMissing(config, Collections.singletonList("en_CA")).isEmpty());
        assertEquals(Arrays.asList("en_CA"), MetadataLanguageUtils.localesMissing(config, null));
    }

    @Test
    public void isDeclaredButUnusedFlagsAWriteIntoALanguageNobodyTranslatesInto()
    {
        Configuration config = configWithSynonym(Map.of("en_CA", "Trade"), language("en_CA"),
            language("fr_CA"));
        assertTrue(MetadataLanguageUtils.isDeclaredButUnused(config, "fr_CA"));
        // The language in use, an undeclared code and the degenerate inputs are all NOT the case
        // this flag is about - an undeclared code is rejected outright, long before this.
        assertFalse(MetadataLanguageUtils.isDeclaredButUnused(config, "en_CA"));
        assertFalse(MetadataLanguageUtils.isDeclaredButUnused(config, "de"));
        assertFalse(MetadataLanguageUtils.isDeclaredButUnused(config, null));
        assertFalse(MetadataLanguageUtils.isDeclaredButUnused(null, "fr_CA"));
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
    public void resolveSynonymLanguageRefusesEveryCodeWhenNothingIsDeclared()
    {
        // An EMPTY declaration set makes every code undeclared, not every code acceptable: a value
        // stored there is displayed by nothing at all. The error has to say how to get out of it.
        Configuration config = config(language("ru"));   // a Language with no languageCode set
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", "de", "the synonym");
            fail("expected an undeclared-language refusal"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("declares no language codes")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("languageCode")); //$NON-NLS-1$
        }
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
    public void resolveSynonymLanguageValidatesAgainstThePostCallCodesWhenGivenThem()
    {
        // One modify batch can set a Language's languageCode AND a localized value under that very
        // code, so the caller passes the codes declared AFTER the batch (codex review on #298).
        Configuration config = config(language("en"), language("en"));
        assertEquals("fr", MetadataLanguageUtils.resolveSynonymLanguage(config, "Francais", "fr",
            "the synonym", Arrays.asList("en", "fr")));
        // A code in NEITHER set is still refused, and the message lists the post-call codes.
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Deutsch", "de", "the synonym",
                Arrays.asList("en", "fr"));
            fail("a code the call does not declare either must still be rejected");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("'de'"));
            assertTrue("the message must list what will be declared", e.getMessage().contains("fr"));
        }
    }

    @Test
    public void resolveSynonymLanguageRefusesACodeTheSameCallREMOVES()
    {
        // The override REPLACES the model's codes rather than adding to them: a batch that renames a
        // language's code en -> fr leaves no 'en' behind, so a value written under 'en' in that same
        // batch would be invisible - exactly what this guard exists to prevent.
        Configuration config = config(language("en"), language("en"));
        try
        {
            MetadataLanguageUtils.resolveSynonymLanguage(config, "English", "en", "the synonym",
                Collections.singletonList("fr"));
            fail("a code the batch removes must be rejected even though the model still has it");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("'en'"));
        }
    }

    @Test
    public void declaredOrOverrideTakesTheOverrideOnlyWhenItHasContent()
    {
        Configuration config = config(language("en"), language("en"));
        assertEquals(Arrays.asList("fr"),
            MetadataLanguageUtils.declaredOrOverride(config, Arrays.asList("fr", "", null, "fr")));
        assertEquals(Arrays.asList("en"), MetadataLanguageUtils.declaredOrOverride(config, null));
        assertEquals(Arrays.asList("en"),
            MetadataLanguageUtils.declaredOrOverride(config, Collections.<String> emptyList()));
    }

    @Test
    public void theFallbackCodeIsAlsoRefusedWhenTheSameCallRemovesIt()
    {
        // A batch that RENAMES the default language's code and writes a localized value WITHOUT an
        // explicit 'language' used to take the OLD default from the model - the very code the call
        // deletes - so the value landed invisible. With exactly one code left the intent is
        // unambiguous, so it is used (codex review on #298).
        Configuration config = config(language("en"), language("en"));
        assertEquals("fr", MetadataLanguageUtils.resolveSynonymLanguage(config, "Francais", null,
            "the synonym", Collections.singletonList("fr")));
    }

    @Test
    public void theFallbackFollowsTheRENAMEDDefaultEvenWithOtherLanguagesAround()
    {
        // Counting what is left is the wrong question (codex review on #298): with a second,
        // untouched language present two codes remain, yet the answer is not ambiguous at all -
        // defaultLanguage still points at the SAME object, so its post-edit code is the fallback.
        // The caller passes it FIRST in the override.
        Configuration config = config(language("en"), language("en"), language("de"));
        assertEquals("fr", MetadataLanguageUtils.resolveSynonymLanguage(config, "Wert", null,
            "the synonym", Arrays.asList("fr", "de")));
    }

    @Test
    public void aFallbackThatSurvivesTheCallIsLeftAlone()
    {
        // The batch declares another code but does NOT remove the default: the default still wins.
        // (The override's FIRST entry is that same 'en', so preferring it changes nothing here.)
        Configuration config = config(language("en"), language("en"));
        assertEquals("en", MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", null,
            "the synonym", Arrays.asList("en", "fr")));
    }

    @Test
    public void theFallbackFollowsTheDefaultThatJUSTGotItsFirstCode()
    {
        // The stale-fallback bug that is NOT visible by looking for the missing code: the default
        // language had no languageCode at all, so the model-derived fallback borrowed 'fr' from the
        // language that did have one. This call gives the default its first code, 'en' - and 'fr'
        // is still perfectly valid, so nothing looks wrong, yet a write with no explicit 'language'
        // belongs to 'en'. The caller passes the post-edit default first; prefer it (codex review).
        Language withoutCode = mock(Language.class);          // the default: no languageCode yet
        Configuration config = config(withoutCode, withoutCode, language("fr"));
        assertEquals("en", MetadataLanguageUtils.resolveSynonymLanguage(config, "Goods", null,
            "the synonym", Arrays.asList("en", "fr")));
    }
}
