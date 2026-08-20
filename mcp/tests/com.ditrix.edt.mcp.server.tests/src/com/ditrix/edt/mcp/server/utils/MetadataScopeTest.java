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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
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

    /**
     * The POST-BATCH declared set must reach the shared validator: one modify_metadata batch can
     * declare a language code and write a value under it, and the write must be judged by what
     * the batch leaves behind, not by the model as it was (issue #309 review).
     */
    @Test
    public void testSynonymLanguageHonoursThePostBatchDeclaredOverride()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        config.getLanguages().add(english);
        config.setDefaultLanguage(english);
        MetadataScope scope = MetadataScope.ofConfiguration(config);

        // "fr" is NOT declared by the model, but IS by the batch - so it must be accepted, and
        // the shared helper must be the one deciding that.
        assertEquals("fr", scope.resolveSynonymLanguage("Nom", "fr", "the synonym", //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Arrays.asList("fr"))); //$NON-NLS-1$
        assertEquals(
            MetadataLanguageUtils.resolveSynonymLanguage(config, "Nom", "fr", "the synonym", //$NON-NLS-1$ //$NON-NLS-2$
                java.util.Arrays.asList("fr")), //$NON-NLS-1$
            scope.resolveSynonymLanguage("Nom", "fr", "the synonym", //$NON-NLS-1$
                java.util.Arrays.asList("fr"))); //$NON-NLS-1$
        // An empty override falls back to what the scope itself declares.
        assertEquals(java.util.Arrays.asList("en"), scope.declaredOrOverride(null)); //$NON-NLS-1$
    }

    /**
     * The classification behind the create guard: several dispatches own their resolution and
     * reach the Configuration directly, so they are refused on the TYPE TOKEN before any lookup.
     * The FQN shapes they take are deeper than "Type.Name", and the hint must still decide them
     * - a nested subsystem and an XDTO member are exactly those shapes
     * (issue #309 review round 5).
     */
    @Test
    public void testAddressingHintDecidesTheDeepFqnShapesToo()
    {
        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("ExtProc"); //$NON-NLS-1$
        MetadataScope external = MetadataScope.ofExternalObjectProject(null,
            configurationWithCatalog("Products"), startedProjectWith(proc)); //$NON-NLS-1$

        // A nested subsystem and an XDTO member: configuration-only, however deep the FQN.
        String nested = external.addressingHint("Subsystem.Sales.Subsystem.Orders"); //$NON-NLS-1$
        assertTrue(nested, nested.contains("EXTERNAL-OBJECTS")); //$NON-NLS-1$
        assertTrue(nested, nested.contains("Subsystem")); //$NON-NLS-1$
        String xdto = external.addressingHint("XDTOPackage.BasePkg.ObjectType.NewType"); //$NON-NLS-1$
        assertTrue(xdto, xdto.contains("XDTOPackage")); //$NON-NLS-1$

        // Its OWN type at the same depth is not a mismatch, so the guard stays out of the way.
        assertEquals("", external.addressingHint( //$NON-NLS-1$
            "ExternalDataProcessor.ExtProc.Form.MainForm.Field.Note")); //$NON-NLS-1$

        // And the REVERSE direction, which the same guard covers: a standalone type addressed
        // at a configuration says which project KIND holds it.
        MetadataScope configuration = MetadataScope.ofConfiguration(
            configurationWithCatalog("Products")); //$NON-NLS-1$
        String reverse = configuration.addressingHint( //$NON-NLS-1$
            "ExternalDataProcessor.ExtProc.Attribute.Note"); //$NON-NLS-1$
        assertTrue(reverse, reverse.contains("EXTERNAL-OBJECTS type")); //$NON-NLS-1$
        assertTrue(reverse, reverse.contains("list_projects")); //$NON-NLS-1$
        assertEquals("", configuration.addressingHint("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A started external-objects project holding the given standalone root objects. */
    private static IExternalObjectProject startedProjectWith(MdObject... objects)
    {
        IExternalObjectProject started = mock(IExternalObjectProject.class);
        when(started.getExternalObjects()).thenReturn(Arrays.asList(objects));
        return started;
    }

    /**
     * A STARTED external-objects project answers about its OWN objects - by the English type
     * token and by the Russian one - even though a base configuration is linked; and a
     * CONFIGURATION type asked here is "unknown", never an empty list.
     */
    @Test
    public void testExternalScopeResolvesItsOwnObjectsRatherThanTheBaseConfiguration()
    {
        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("ExtProc"); //$NON-NLS-1$
        ExternalReport report = MdClassFactory.eINSTANCE.createExternalReport();
        report.setName("ExtReport"); //$NON-NLS-1$
        // A base configuration IS linked - and must never be the answer (issue #309).
        MetadataScope scope = MetadataScope.ofExternalObjectProject(null,
            configurationWithCatalog("Products"), startedProjectWith(proc, report)); //$NON-NLS-1$

        assertTrue(scope.isExternalObjects());
        assertFalse(scope.externalRootUnavailable());
        assertEquals(proc, scope.findObject("ExternalDataProcessor", "extproc")); //$NON-NLS-1$ //$NON-NLS-2$
        // ВнешнийОтчет - the Russian TYPE token; the NAME stays programmatic.
        assertEquals(report, scope.findObject(
            fromCp(0x0412, 0x043D, 0x0435, 0x0448, 0x043D, 0x0438, 0x0439, 0x041E, 0x0442, 0x0447,
                0x0435, 0x0442),
            "ExtReport")); //$NON-NLS-1$
        // The linked configuration DOES hold that Catalog - and this root still says "unknown".
        assertNull(scope.objects("Catalog")); //$NON-NLS-1$
        assertNull(scope.findObject("Catalog", "Products")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The "did you mean?" list is answered from THIS root. {@code go_to_definition} advertises
     * every catalogue type, so its suggestion path runs here too - and on a project EDT has not
     * started it must stay empty instead of quietly becoming the base configuration's
     * objects, which is the #309 bug itself (review round 2).
     */
    @Test
    public void testSimilarObjectsComeFromTheScopeRootAndNeverFromTheBaseConfiguration()
    {
        ExternalDataProcessor proc = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        proc.setName("ExtProc"); //$NON-NLS-1$
        Configuration base = configurationWithCatalog("Products"); //$NON-NLS-1$

        MetadataScope started = MetadataScope.ofExternalObjectProject(null, base,
            startedProjectWith(proc));
        assertEquals(Arrays.asList("ExtProc"), //$NON-NLS-1$
            started.findSimilarObjects("ExternalDataProcessor", "Ext", 10)); //$NON-NLS-1$ //$NON-NLS-2$
        // A configuration type has no candidates HERE, however well it matches over there.
        assertTrue(started.findSimilarObjects("Catalog", "Product", 10).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$

        // Not started: no root to answer from - empty, NOT the base configuration's Products.
        MetadataScope unstarted = MetadataScope.ofExternalObjectProject(null, base, null);
        assertTrue(unstarted.externalRootUnavailable());
        assertTrue(unstarted.findSimilarObjects("Catalog", "Product", 10).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        // The same question on a CONFIGURATION scope does answer, so the empty lists above are
        // the scope talking and not a helper that stopped working.
        assertEquals(Arrays.asList("Products"), MetadataScope.ofConfiguration(base) //$NON-NLS-1$
            .findSimilarObjects("Catalog", "Product", 10)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The base project is a POINTER, never a root. An external-objects project's answer must
     * not contain the base configuration's objects - but a refusal may NAME the project that
     * does hold them, which is the difference between a dead end and the next call
     * (issue #309 review round 3).
     */
    @Test
    public void testBaseProjectNameIsAPointerAndOnlyWhenThereIsOne()
    {
        assertEquals("TestConfiguration", //$NON-NLS-1$
            MetadataScope.ofExternalObjectProject(null, null,
                linkedTo(liveProject("TestConfiguration"))).baseProjectName()); //$NON-NLS-1$

        // Unlinked, not started, or a plain configuration scope: no pointer to give.
        IExternalObjectProject unlinked = mock(IExternalObjectProject.class);
        when(unlinked.getParentProject()).thenReturn(null);
        assertNull(MetadataScope.ofExternalObjectProject(null, null, unlinked).baseProjectName());
        assertNull(MetadataScope.ofExternalObjectProject(null, null, null).baseProjectName());
        assertNull(MetadataScope.ofConfiguration(
            MdClassFactory.eINSTANCE.createConfiguration()).baseProjectName());
    }

    /** A workspace project that exists and is open - the only kind worth pointing a caller at. */
    private static IProject liveProject(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        return project;
    }

    private static IExternalObjectProject linkedTo(IProject parent)
    {
        IExternalObjectProject linked = mock(IExternalObjectProject.class);
        when(linked.getParentProject()).thenReturn(parent);
        return linked;
    }

    /**
     * A hint is only worth giving if the caller can act on it: a parent that is deleted or
     * closed still HAS a name, and naming it would send the caller to a project EDT cannot
     * answer for - a useful refusal turned into a failing next call (issue #309 review round 4).
     */
    @Test
    public void testBaseProjectNameSkipsAProjectThatIsGoneOrClosed()
    {
        IProject deleted = mock(IProject.class);
        when(deleted.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$
        when(deleted.exists()).thenReturn(false);
        when(deleted.isOpen()).thenReturn(true);
        assertNull(MetadataScope.ofExternalObjectProject(null, null, linkedTo(deleted))
            .baseProjectName());

        IProject closed = mock(IProject.class);
        when(closed.getName()).thenReturn("TestConfiguration"); //$NON-NLS-1$
        when(closed.exists()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);
        assertNull(MetadataScope.ofExternalObjectProject(null, null, linkedTo(closed))
            .baseProjectName());
    }

    /**
     * The locales a new node still owes come from whatever declares them. Asked of a
     * {@code null} Configuration the shared helper reports NOTHING missing, so a create in an
     * unlinked external-objects project would claim every translation was done while its
     * manifest still declared untranslated languages (issue #309 review round 4).
     */
    @Test
    public void testLocalesMissingAnswersFromTheManifestWhenThereIsNoConfiguration()
    {
        IExternalObjectProject manifestOnly = mock(IExternalObjectProject.class);
        when(manifestOnly.getLanguages()).thenReturn(languages("ru", "en")); //$NON-NLS-1$ //$NON-NLS-2$
        MetadataScope scope = MetadataScope.ofExternalObjectProject(null, null, manifestOnly);

        // A value written in "en" still owes "ru" - the shared helper would have said nothing.
        assertEquals(Arrays.asList("ru"), //$NON-NLS-1$
            scope.localesMissing(Collections.singletonList("en"))); //$NON-NLS-1$
        assertTrue(scope.localesMissing(Arrays.asList("ru", "en")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("ru", "en"), scope.localesMissing(null)); //$NON-NLS-1$ //$NON-NLS-2$
        // "declared but unused" cannot be decided without a configuration synonym map, and
        // guessing true would query every legitimate write in a manifest-only project.
        assertFalse(scope.isDeclaredButUnused("ru")); //$NON-NLS-1$

        // With a Configuration the answer stays the shared helper's, exactly as before.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        config.getLanguages().add(english);
        MetadataScope configScope = MetadataScope.ofConfiguration(config);
        assertEquals(MetadataLanguageUtils.localesMissing(config, Collections.singletonList("en")), //$NON-NLS-1$
            configScope.localesMissing(Collections.singletonList("en"))); //$NON-NLS-1$
        assertEquals(MetadataLanguageUtils.isDeclaredButUnused(config, "en"), //$NON-NLS-1$
            configScope.isDeclaredButUnused("en")); //$NON-NLS-1$
    }

    /** Language objects carrying the given codes, as a project manifest surfaces them. */
    private static Collection<Language> languages(String... codes)
    {
        List<Language> result = new ArrayList<>();
        for (String code : codes)
        {
            Language language = MdClassFactory.eINSTANCE.createLanguage();
            language.setLanguageCode(code);
            result.add(language);
        }
        return result;
    }

    /**
     * A FAILED read of the external root must TRAVEL, not be flattened into "this project holds
     * nothing": swallowed, an unavailable BM model turns every real object into a plausible
     * "not found" - exactly the class of lie this scope exists to stop (review round 2).
     */
    @Test
    public void testUnreadableExternalRootFailsLoudlyInsteadOfLookingEmpty()
    {
        IExternalObjectProject broken = mock(IExternalObjectProject.class);
        when(broken.getExternalObjects())
            .thenThrow(new IllegalStateException("BM model is not available")); //$NON-NLS-1$
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Reports"); //$NON-NLS-1$
        MetadataScope scope = MetadataScope.ofExternalObjectProject(project, null, broken);

        try
        {
            scope.objects("ExternalDataProcessor"); //$NON-NLS-1$
            fail("A failed root read must not come back as an empty collection"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            String message = e.getMessage();
            // The project, what ACTUALLY went wrong, and a way out.
            assertTrue(message, message.contains("Reports")); //$NON-NLS-1$
            assertTrue(message, message.contains("BM model is not available")); //$NON-NLS-1$
            assertTrue(message, message.contains("list_projects")); //$NON-NLS-1$
            assertTrue(message, message.contains("clean_project")); //$NON-NLS-1$
        }

        // Every root-reading entry point travels the same way - none degrades to empty.
        try
        {
            scope.allExternalObjects();
            fail("allExternalObjects must not swallow the read failure either"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertNotNull(expected.getMessage());
        }
    }
}
