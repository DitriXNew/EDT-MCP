/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Tests for {@link MetadataScope}, the root a metadata FQN resolves against (issue #309).
 *
 * <p>The CONFIGURATION scope is exercised against an in-memory {@code Configuration} and must be
 * indistinguishable from the direct {@code MetadataTypeUtils} calls it replaced - that equivalence
 * is what makes the change safe for configuration and extension projects. The EXTERNAL-OBJECTS
 * scope needs a live {@code IExternalObjectProject} (a workspace project + the BM model), so its
 * behaviour is covered by {@code tests/e2e/tools/test_external_objects_project.py} against the
 * {@code ExternalObjects} fixture; what is asserted here is the part that is decidable without one:
 * the addressing hint a configuration scope gives for an external-objects type.</p>
 */
public class MetadataScopeTest
{
    /** Builds a string from BMP code points (keeps this test source pure ASCII). */
    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    private static Configuration configurationWithCatalog(String name)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        config.getCatalogs().add(catalog);
        return config;
    }

    @Test
    public void testConfigurationScopeIsNotExternal()
    {
        assertFalse(MetadataScope.ofConfiguration(
            MdClassFactory.eINSTANCE.createConfiguration()).isExternalObjects());
        // An EMPTY scope (no configuration at all) is still not an external-objects scope: the two
        // are different answers and must not collapse into one.
        assertFalse(MetadataScope.ofConfiguration(null).isExternalObjects());
    }

    @Test
    public void testConfigurationScopeFindsTopObjectByEnglishAndRussianToken()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$

        MdObject byEnglish = scope.findObject("Catalog", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(byEnglish);
        assertEquals("Products", byEnglish.getName()); //$NON-NLS-1$

        // Справочник - the Russian TYPE token; the NAME is programmatic and stays as it is.
        MdObject byRussian = scope.findObject(
            fromCp(0x0421, 0x043F, 0x0440, 0x0430, 0x0432, 0x043E, 0x0447, 0x043D, 0x0438, 0x043A),
            "products"); //$NON-NLS-1$
        assertNotNull(byRussian);
        assertEquals(byEnglish, byRussian);
    }

    @Test
    public void testConfigurationScopeAnswersUnknownTypeWithNull()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$
        // "unknown here", not "an empty collection" - the caller has to be able to tell them apart.
        assertNull(scope.objects("NoSuchType")); //$NON-NLS-1$
        // An external-objects type is unknown to a Configuration root for the same reason.
        assertNull(scope.objects("ExternalDataProcessor")); //$NON-NLS-1$
        assertNull(scope.findObject("ExternalDataProcessor", "ExtProc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfigurationScopeListsTheTypeCollection()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$
        List<? extends MdObject> catalogs = scope.objects("Catalogs"); //$NON-NLS-1$
        assertNotNull(catalogs);
        assertEquals(1, catalogs.size());
        assertEquals("Products", catalogs.get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationScopeHasNoExternalObjects()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$
        assertTrue(scope.allExternalObjects().isEmpty());
        // "the root set cannot be read" is a statement about an EXTERNAL-OBJECTS project only; a
        // configuration scope must never claim it, or every configuration call would report it.
        assertFalse(scope.externalRootUnavailable());
    }

    @Test
    public void testAddressingHintNamesTheProjectKindForAnExternalType()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$

        String hint = scope.addressingHint("ExternalDataProcessor.ExtProc.Form.MainForm"); //$NON-NLS-1$
        assertTrue("the hint must name the type: " + hint, //$NON-NLS-1$
            hint.contains("ExternalDataProcessor")); //$NON-NLS-1$
        assertTrue("the hint must point at the right project: " + hint, //$NON-NLS-1$
            hint.contains("list_projects")); //$NON-NLS-1$

        // Russian type token - the same verdict, since the token catalogue is bilingual.
        // ВнешнийОтчет
        String ru = scope.addressingHint(fromCp(0x0412, 0x043D, 0x0435, 0x0448, 0x043D, 0x0438,
            0x0439, 0x041E, 0x0442, 0x0447, 0x0435, 0x0442) + ".Report1"); //$NON-NLS-1$
        assertTrue("the Russian token must be recognized too: " + ru, //$NON-NLS-1$
            ru.contains("ExternalReport")); //$NON-NLS-1$
    }

    @Test
    public void testAddressingHintIsEmptyWhenTheTypeFitsTheRoot()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configurationWithCatalog("Products")); //$NON-NLS-1$
        // A configuration type in a configuration scope: nothing to explain.
        assertEquals("", scope.addressingHint("Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$
        // An unrecognized type token says nothing either - it is not a project-kind mismatch.
        assertEquals("", scope.addressingHint("NoSuchType.X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", scope.addressingHint(null)); //$NON-NLS-1$
    }

    @Test
    public void testLanguageAccessorsDelegateToTheConfiguration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        config.getLanguages().add(english);
        config.setDefaultLanguage(english);

        MetadataScope scope = MetadataScope.ofConfiguration(config);
        // Keyed by the language CODE, never the Language object's NAME.
        assertEquals("en", scope.defaultLanguageCode()); //$NON-NLS-1$
        assertEquals("ru", scope.resolveLanguageCode("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("en", scope.resolveLanguageCode(null)); //$NON-NLS-1$
        assertEquals(1, scope.declaredLanguageCodes().size());
        assertEquals("en", scope.declaredLanguageCodes().get(0)); //$NON-NLS-1$
        // With a configuration present the override must stay null, so the shared language helper
        // keeps deciding exactly as it did before this class existed.
        assertNull(scope.declaredLanguageOverride());
    }

    @Test
    public void testSynonymLanguageResolutionMatchesTheSharedHelper()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        config.getLanguages().add(english);
        config.setDefaultLanguage(english);
        MetadataScope scope = MetadataScope.ofConfiguration(config);

        assertEquals(MetadataLanguageUtils.resolveSynonymLanguage(config, "Name", null, "the synonym"), //$NON-NLS-1$ //$NON-NLS-2$
            scope.resolveSynonymLanguage("Name", null, "the synonym")); //$NON-NLS-1$ //$NON-NLS-2$
        // No value -> no code, in both.
        assertNull(scope.resolveSynonymLanguage(null, null, "the synonym")); //$NON-NLS-1$
    }
}
