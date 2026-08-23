/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;

import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder.Scoping;

/**
 * Tests {@link ComparisonScopeBuilder}: the bilingual front door of the comparison engine, and the
 * one place that decides whether "no scope" means the whole configuration or a refusal.
 * <p>
 * Nothing here touches the engine - {@link ComparisonScope} is a plain value object, so the whole
 * contract is exercisable with no EDT workbench running.
 * <p>
 * Cyrillic is written as Unicode code-point escapes to keep this source pure ASCII, with the ASCII
 * transliteration in each constant's name.
 */
public class ComparisonScopeBuilderTest
{
    /** Spravochnik - the Russian type token for Catalog. */
    private static final String SPRAVOCHNIK = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"; //$NON-NLS-1$

    /** Tovary - a programmatic object Name, which must survive byte-identical. */
    private static final String TOVARY = "\u0422\u043e\u0432\u0430\u0440\u044b"; //$NON-NLS-1$

    /** Forma - the Russian NESTED kind token for Form. */
    private static final String FORMA = "\u0424\u043e\u0440\u043c\u0430"; //$NON-NLS-1$

    /** FormaElementa - a form's programmatic Name. */
    private static final String FORMA_ELEMENTA =
        "\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    /** Konfiguraciya - the Russian spelling of the configuration root symlink. */
    private static final String KONFIGURACIYA =
        "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f"; //$NON-NLS-1$

    // ==================== the three sides ====================

    @Test
    public void testThreeSidesCarryTheSameCanonicalList()
    {
        Scoping scoping =
            ComparisonScopeBuilder.build(Arrays.asList("Catalog.Products", "Document.SalesOrder")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(scoping.ok());
        assertFalse("a supplied scope is not the global one", scoping.isGlobal()); //$NON-NLS-1$
        ComparisonScope scope = scoping.scope();
        assertNotNull(scope);

        List<String> expected = Arrays.asList("Catalog.Products", "Document.SalesOrder"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expected, scoping.symlinks());
        // The caller names OBJECTS, not sides: an object present on only one side is still the object
        // being asked about, so all three sides carry the same list.
        for (ComparisonSide side : new ComparisonSide[] {ComparisonSide.MAIN, ComparisonSide.OTHER,
            ComparisonSide.COMMON_ANCESTOR})
        {
            assertEquals("side " + side.getLiteral() + " must carry the canonical list", expected, //$NON-NLS-1$ //$NON-NLS-2$
                scope.getInputScope(side));
        }
    }

    // ==================== bilingual canonicalisation ====================

    @Test
    public void testRussianNestedEntryIsScopedAsAnAllEnglishSymlink()
    {
        // The mutation this pins: canonicalising with normalizeFqn instead of the all-segments
        // canonicalizer. That leaves the Russian kind token in place, the engine matches it against
        // nothing, and the comparison reports "no differences" for an object nobody ever compared.
        Scoping scoping = ComparisonScopeBuilder
            .build(Collections.singletonList(SPRAVOCHNIK + '.' + TOVARY + '.' + FORMA + '.' + FORMA_ELEMENTA));

        assertTrue(scoping.errorJson(), scoping.ok());
        assertEquals(Collections.singletonList("Catalog." + TOVARY + ".Form." + FORMA_ELEMENTA), //$NON-NLS-1$ //$NON-NLS-2$
            scoping.symlinks());
        assertEquals(scoping.symlinks(), scoping.scope().getInputScope(ComparisonSide.MAIN));
    }

    @Test
    public void testEntriesAreTrimmedAndDeduplicatedAcrossLanguages()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Arrays.asList("  Catalogs.Products  ", SPRAVOCHNIK + ".Products", "Catalog.Products")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(scoping.errorJson(), scoping.ok());
        assertEquals("three spellings of one object are one symlink", //$NON-NLS-1$
            Collections.singletonList("Catalog.Products"), scoping.symlinks()); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationRootTokenIsAcceptedInBothLanguages()
    {
        // Not a metadata TYPE (it owns no Configuration collection and no src/ directory) but the one
        // symlink the comparison engine names in its own source - refusing it would be a false refusal.
        Scoping scoping = ComparisonScopeBuilder.build(Arrays.asList(KONFIGURACIYA, "configuration")); //$NON-NLS-1$

        assertTrue(scoping.errorJson(), scoping.ok());
        assertEquals(Collections.singletonList(ComparisonScopeBuilder.CONFIGURATION_SYMLINK),
            scoping.symlinks());
    }

    // ==================== refusals ====================

    @Test
    public void testUnknownTypeTokenIsRefusedNamingBothTheEntryAndTheToken()
    {
        String entry = "NoSuchType.Whatever"; //$NON-NLS-1$
        Scoping scoping = ComparisonScopeBuilder.build(Collections.singletonList(entry));

        assertFalse(scoping.ok());
        assertFalse("a refusal is not the global scope", scoping.isGlobal()); //$NON-NLS-1$
        assertNull("a refusal must carry no scope object at all", scoping.scope()); //$NON-NLS-1$
        assertTrue(scoping.symlinks().isEmpty());

        String error = scoping.errorJson();
        assertTrue("the refusal must quote the entry the caller wrote: " + error, error.contains(entry)); //$NON-NLS-1$
        assertTrue("the refusal must quote the token that failed: " + error, //$NON-NLS-1$
            error.contains("NoSuchType")); //$NON-NLS-1$
        assertTrue("the refusal must show the accepted forms: " + error, error.contains(SPRAVOCHNIK)); //$NON-NLS-1$
        assertTrue("the refusal must name the whole-configuration route: " + error, //$NON-NLS-1$
            error.contains("omit")); //$NON-NLS-1$
    }

    @Test
    public void testBlankEntryIsRefusedAndNeverSilentlyBecomesGlobal()
    {
        // The dangerous reading: a scope of one blank string collapses to an empty list, the engine
        // reads an empty scope as COMPARE EVERYTHING, and a typo turns into a full-configuration run.
        Scoping scoping = ComparisonScopeBuilder.build(Arrays.asList("Catalog.Products", "   ")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(scoping.ok());
        assertFalse("a blank entry must never escalate to the whole configuration", scoping.isGlobal()); //$NON-NLS-1$
        assertNull(scoping.scope());
        assertTrue("the refusal must point at the offending position: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("#2")); //$NON-NLS-1$
    }

    @Test
    public void testNullEntryIsRefusedTheSameWay()
    {
        Scoping scoping = ComparisonScopeBuilder.build(Collections.<String> singletonList(null));

        assertFalse(scoping.ok());
        assertFalse(scoping.isGlobal());
        assertNull(scoping.scope());
    }

    // ==================== the empty-scope policy (escalation 4, option a) ====================

    @Test
    public void testOmittedScopeIsGlobalAndNoComparisonScopeIsEverBuilt()
    {
        List<List<String>> omitted = new ArrayList<>();
        omitted.add(null);
        omitted.add(Collections.<String> emptyList());

        for (List<String> fqns : omitted)
        {
            Scoping scoping = ComparisonScopeBuilder.build(fqns);

            assertTrue(scoping.ok());
            assertTrue("an omitted scope means the WHOLE configuration", scoping.isGlobal()); //$NON-NLS-1$
            // This is the assertion that would fail if an empty list ever reached the ComparisonScope
            // constructor "by default": the engine's computeIsGlobalScope() is true exactly when every
            // side is null-or-empty, so an empty ComparisonScope and no ComparisonScope drive the same
            // comparison - and only one of the two lets the caller SAY which one it meant.
            assertNull("the whole-configuration answer must hand the caller no scope object", //$NON-NLS-1$
                scoping.scope());
            assertTrue(scoping.symlinks().isEmpty());
        }
    }

    @Test
    public void testABuiltScopeIsNeverEmptyOnAnySide()
    {
        Scoping scoping = ComparisonScopeBuilder.build(Collections.singletonList("Catalog.Products")); //$NON-NLS-1$

        ComparisonScope scope = scoping.scope();
        assertNotNull(scope);
        for (ComparisonSide side : new ComparisonSide[] {ComparisonSide.MAIN, ComparisonSide.OTHER,
            ComparisonSide.COMMON_ANCESTOR})
        {
            assertFalse("an empty side is read by the engine as 'compare everything'", //$NON-NLS-1$
                scope.getInputScope(side).isEmpty());
        }
    }

    // ==================== the report's view ====================

    @Test
    public void testSymlinksAreImmutableForTheCaller()
    {
        Scoping scoping = ComparisonScopeBuilder.build(Collections.singletonList("Catalog.Products")); //$NON-NLS-1$

        try
        {
            scoping.symlinks().add("Catalog.Sneaked"); //$NON-NLS-1$
            throw new AssertionError("the requested scope must not be editable after the fact"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // The report shows this list as what the caller ASKED FOR; a caller able to append to it
            // could make the report claim a scope that was never sent to the engine.
        }
    }

    // ==================== the platform's own "compare everything" predicate ====================
    //
    // ComparisonSession.computeIsGlobalScope answers true exactly when every side's list is
    // null-or-empty, and several participants branch on it - most importantly the one that
    // decides whether an object's own features are compared at all. A caller settling a comparison
    // SETTING before the session exists has to be able to ask the same question of the scope it is
    // about to hand over, and get the same answer.

    @Test
    public void testNoScopeObjectAtAllIsTheGlobalScope()
    {
        // How this builder spells the whole-configuration case: Scoping.GLOBAL carries no scope.
        assertTrue(ComparisonScopeBuilder.isGlobalScope(null));
        assertNull("the fixture must really be the no-scope case", //$NON-NLS-1$
            ComparisonScopeBuilder.build(null).scope());
    }

    @Test
    public void testAnEmptyScopeObjectIsTheGlobalScope()
    {
        assertTrue(ComparisonScopeBuilder.isGlobalScope(new ComparisonScope(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList())));
    }

    @Test
    public void testASuppliedScopeIsNotTheGlobalScope()
    {
        Scoping scoping = ComparisonScopeBuilder.build(Collections.singletonList("Catalog.Products")); //$NON-NLS-1$

        assertFalse(ComparisonScopeBuilder.isGlobalScope(scoping.scope()));
    }

    @Test
    public void testASingleNonEmptySideIsEnoughToMakeAScopeNotGlobal()
    {
        // Every side is asked, not just the main one: the platform's own loop runs over
        // ComparisonSide.values(), and a check that stopped at the first side would call a scope
        // global that the session does not.
        List<String> named = Collections.singletonList("Catalog.Products"); //$NON-NLS-1$
        List<String> none = Collections.emptyList();

        assertFalse("main alone", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(new ComparisonScope(named, none, none)));
        assertFalse("other alone", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(new ComparisonScope(none, named, none)));
        assertFalse("ancestor alone", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(new ComparisonScope(none, none, named)));
    }

    @Test
    public void testAnExtendedScopeAnswersWhatTheSessionWouldAnswer()
    {
        // The predicate reproduces ComparisonSession.computeIsGlobalScope, which reads getScope -
        // so this must too. It used to read getInputScope instead, which is the same list only
        // while nothing has been extended: an empty scope extended BEFORE the session is
        // constructed was called global here and scoped by the platform, and the
        // mergeObjectsContent setting derived from it came out the wrong way round for the whole
        // run. The order is safe at today's call site, which is exactly why a test had to pin the
        // predicate rather than the call site.
        ComparisonScope scope = new ComparisonScope(Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList());
        scope.extendScope("Catalog.PulledIn", "referenced by a compared object", //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.MAIN);

        assertFalse("the fixture must really have been extended", //$NON-NLS-1$
            scope.getScope(ComparisonSide.MAIN).isEmpty());
        assertTrue("the input scope is untouched - the two accessors really do disagree here", //$NON-NLS-1$
            scope.getInputScope(ComparisonSide.MAIN).isEmpty());
        assertFalse("the platform reads getScope, so this predicate must give the same answer", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(scope));
    }

    @Test
    public void testAFinishedRunIsNotDescribedByAskingThisPredicateAgain()
    {
        // The consequence of the fix, stated as a pin so nobody "restores" the old reading to make
        // a report easier: after the engine has pulled a dependency in, this predicate no longer
        // describes the run that was launched. Whoever needs that answer later reads the value the
        // SESSION saved (ComparisonView.isGlobalScope), which is what ComparisonTreeReport.Header
        // now carries - see ComparisonTreeReportTest.
        ComparisonScope launchedGlobal = new ComparisonScope(Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList());
        assertTrue("at launch it is global", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(launchedGlobal));

        launchedGlobal.extendScope("Catalog.PulledIn", "referenced by a compared object", //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.MAIN);

        assertFalse("and after the engine extended it, the same object no longer answers that", //$NON-NLS-1$
            ComparisonScopeBuilder.isGlobalScope(launchedGlobal));
    }
}
