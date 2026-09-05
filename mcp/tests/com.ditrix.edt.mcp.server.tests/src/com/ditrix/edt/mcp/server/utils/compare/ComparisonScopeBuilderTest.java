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

    // ==================== a padded name is not a name ====================
    //
    // String.trim cuts only code points at or below U+0020, so an entry padded with U+2003 or
    // U+00A0 survives it - non-blank, structurally sound, leading token a real type - and the
    // builder used to hand the engine a symlink that can never match. EDT's single comparison
    // slot is then spent for minutes on a name no node can answer to: the padding survives the
    // trim, so the entry reaches the engine exactly as sent and the report can only quote it back
    // that way. Every pin below therefore checks the REFUSAL, not the symlink: a build that
    // produced the padded symlink was already the defect.
    //
    // The characters are written as escapes so this source stays pure ASCII (and so the pins are
    // readable at all - the whole point is that the character is invisible).

    @Test
    public void testAnEntryPaddedWithAnEmSpaceIsRefused()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2003")); //$NON-NLS-1$

        assertFalse("a padded entry must not build a scope", scoping.ok()); //$NON-NLS-1$
        assertNull("and it must not reach the engine as a symlink either", scoping.scope()); //$NON-NLS-1$
    }

    @Test
    public void testAnEntryPaddedWithANonBreakingSpaceIsRefused()
    {
        // U+00A0 is the harder half: Character.isWhitespace says NO, so String.isBlank does too,
        // and a check written on isBlank alone would let this through. It is caught by
        // Character.isSpaceChar, which is why the shared predicate asks both.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u00a0")); //$NON-NLS-1$

        assertFalse("a non-breaking space is padding to every reader, and to no trim", //$NON-NLS-1$
            scoping.ok());
    }

    @Test
    public void testPaddingOnAnInnerSegmentIsRefusedToo()
    {
        // The question is asked per SEGMENT, not about the ends of the whole string: this entry
        // begins with 'C' and ends with 's', so a check that looked only at the outside would
        // pass it while the second name still matches nothing.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.\u00a0Products")); //$NON-NLS-1$

        assertFalse("an inner name has two ends of its own", scoping.ok()); //$NON-NLS-1$
    }

    @Test
    public void testPaddingOnTheLeadingTypeTokenIsNamedAsPaddingRatherThanAsAnUnknownType()
    {
        // The ORDER of the two checks, pinned. Ask about the type first and the caller is told
        // "'Catalog' is neither a metadata type ... nor the configuration root" over a token that
        // reads as exactly 'Catalog' on any screen - a true sentence that cannot be acted on.
        //
        // U+2007 rather than U+2003, and the whole phrase rather than the code: the refusal's
        // closing advice NAMES U+2003 and U+00A0 as examples, so contains("U+2003") is satisfied
        // by a message that got the offending character wrong.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog\u2007.Products")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertTrue("the padding must be named, not reported as an unknown type: " //$NON-NLS-1$
            + scoping.errorJson(),
            scoping.errorJson().contains("has U+2007, a whitespace character, at character 8")); //$NON-NLS-1$
        assertFalse("and the unknown-type wording must not be what comes out: " //$NON-NLS-1$
            + scoping.errorJson(),
            scoping.errorJson().contains("does not start with a known metadata type")); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalNamesTheCodePointAndWhereItSits()
    {
        // Named by code, not echoed: quoting the entry back carries the character instead of
        // naming it. The position is the 1-based UTF-16 offset, so 'Catalog.' (8) +
        // 'Products' (8) puts the pad at 17.
        //
        // U+2007 and the whole phrase, not contains("U+2003"): the closing advice names U+2003
        // and U+00A0 as examples of trim-surviving whitespace, so a code-only assertion on either
        // of those passes even when the message names the wrong character.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2007")); //$NON-NLS-1$

        String error = scoping.errorJson();
        assertTrue("the character and its position, as one phrase: " + error, //$NON-NLS-1$
            error.contains("has U+2007, a whitespace character, at character 17")); //$NON-NLS-1$
        assertTrue("and the entry identified by position in the list: " + error, //$NON-NLS-1$
            error.contains("Scope entry #1")); //$NON-NLS-1$
    }

    @Test
    public void testThePositionIsCountedInTheTrimmedEntryAndSaysSo()
    {
        // The number and the string it is counted in, pinned as ONE phrase. 'entry' is
        // raw.trim() by construction, so 17 is true here whatever the caller sent; the frame is
        // what makes 17 readable rather than an offset the reader has to guess the origin of.
        //
        // The whole phrase rather than "of that entry once ordinary spaces": a message that
        // stated the frame somewhere else in its own prose would satisfy a looser assertion
        // while leaving the number unattached to it.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("  Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertTrue("the position and the string it indexes must arrive together: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson().contains(
                "at character 17 of that entry once ordinary spaces (U+0020 and below) are " //$NON-NLS-1$
                    + "trimmed off its ends")); //$NON-NLS-1$
    }

    @Test
    public void testThePositionIsNotFramedAsTheStringTheCallerSent()
    {
        // The frame that was measured and dropped. 'scope' reaches this class two ways, and on
        // the comma-separated one JsonUtils.extractArrayArgument trims every entry on the way in
        // - so the leading spaces this class could add back are not the ones the caller typed,
        // and 19 would be a number nothing here can stand behind. Pinned as the ABSENCE of 19,
        // because the frame sentence alone would still read correctly beside a restored offset.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("  Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("the offset into the untrimmed string must not come back: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson().contains("at character 19")); //$NON-NLS-1$
    }

    @Test
    public void testThePaddedRefusalNamesTheSlotItSavesRatherThanClaimingTheReportReadsAsAgreement()
    {
        // What the refusal is FOR, stated as what actually happens. It used to justify itself by
        // saying the finished run "reads as 'these objects did not differ'" - which is false
        // about our own report: ComparisonTreeReport prints "That is an absence of data, NOT a
        // statement that the sides agree" and, for a scope, "The requested scope matched no
        // object". The real harm is the slot spent on a name that cannot match plus the fact
        // that the report is left with nothing to do about the name but quote it back, and both
        // are pinned.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        String error = scoping.errorJson();
        assertTrue("the cost of running it anyway must be named: " + error, //$NON-NLS-1$
            error.contains("EDT's single comparison slot would be spent for minutes on a name " //$NON-NLS-1$
                + "that can never match")); //$NON-NLS-1$
        assertTrue("and what the report is left able to do about the name: " + error, //$NON-NLS-1$
            error.contains("all the report could do about the name is quote it back in its " //$NON-NLS-1$
                + "Requested column, where it still reads as a name")); //$NON-NLS-1$
    }

    @Test
    public void testThePaddedRefusalDoesNotSayTheReportWouldReadAsAgreement()
    {
        // The false claim, pinned as an absence in its own @Test: JUnit stops a method at its
        // first failed assertion, so an absence sharing a method with the positive pins above
        // would only be reached while they all held.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("our report never says this, so the refusal must not claim it would: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson().contains("did not differ")); //$NON-NLS-1$
    }

    @Test
    public void testThePaddedRefusalDoesNotSayThePaddingCannotBeSeen()
    {
        // The second false claim this message carried: "the entry looks correct wherever it is
        // quoted back, because the padding is invisible". Invisibility is not a property of the
        // inputs this refusal fires on - see the U+0020 pins below - so it cannot be the reason
        // the entry is harmful. It is the reason the row is EASY TO MISS, and the message keeps
        // that idea only where it is true: as a note about the characters an ordinary trim
        // leaves behind.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("invisibility is not true of every padded entry, so it may not be given as " //$NON-NLS-1$
            + "the reason: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("the padding is invisible")); //$NON-NLS-1$
    }

    @Test
    public void testThePaddedRefusalDoesNotSayTheEntryLooksCorrect()
    {
        // The other half of the same sentence, pinned separately: dropping "because the padding
        // is invisible" while keeping "the entry looks correct" would leave the overclaim
        // standing in shorter words, and the absence above would not notice.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u2007")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("what the entry LOOKS like is the caller's screen to judge: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson().contains("looks correct")); //$NON-NLS-1$
    }

    @Test
    public void testAnEntryPaddedWithAnOrdinaryVisibleSpaceIsStillRefused()
    {
        // The input that disproved "the padding is invisible". U+0020 is ordinary and perfectly
        // visible, and inside the entry String.trim never touches it - trim cuts the ENDS of the
        // whole string, while the question is asked at the ends of each SEGMENT. So this entry
        // reaches the padding check, is refused, and its offending character is one anybody can
        // see.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog. Products")); //$NON-NLS-1$

        assertFalse("padding at a segment boundary is padding whether or not it can be seen", //$NON-NLS-1$
            scoping.ok());
        assertTrue("and it is named as the character it is: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("has U+0020, a whitespace character, at character 9")); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalForAVisibleSpaceDoesNotClaimThePaddingCannotBeSeen()
    {
        // The same absence as above, asked of the input that makes it a lie rather than merely
        // an overreach. Its own @Test for the reason the pair above states.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog. Products")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("this padding is a plain space the caller can see: " //$NON-NLS-1$
            + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("the padding is invisible")); //$NON-NLS-1$
    }

    // ==================== a segment that names nothing ====================
    //
    // The other half of the same failure, and the one the padding rule deliberately walks past:
    // it SKIPS a component that names nothing, because for a merge-rule key another predicate
    // reports that one. Here there is no other predicate, so the skip was a hole - and an entry
    // with an empty segment builds a symlink that matches nothing exactly as a padded one does.

    @Test
    public void testAnEmptyInnerSegmentIsRefused()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog..Products")); //$NON-NLS-1$

        assertFalse("a name with nothing between two dots matches no object", scoping.ok()); //$NON-NLS-1$
        assertTrue("and the refusal says which segment: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("nothing in segment 2")); //$NON-NLS-1$
    }

    @Test
    public void testTheEmptySegmentRefusalNamesTheSlotItSaves()
    {
        // The same correction as the padded refusal, at the sibling door: this message carried
        // the same false justification, and both had to move together or the two doors would
        // give different reasons for one rule.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog..Products")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertTrue("the cost of running it anyway must be named: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("EDT's single comparison slot would be spent for " //$NON-NLS-1$
                + "minutes on a name that can never match")); //$NON-NLS-1$
    }

    @Test
    public void testTheEmptySegmentRefusalDoesNotSayTheReportWouldReadAsAgreement()
    {
        // Pinned as an absence in its own @Test, for the reason the padded pair states.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog..Products")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        assertFalse("our report never says this, so the refusal must not claim it would: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson().contains("did not differ")); //$NON-NLS-1$
    }

    @Test
    public void testATrailingDotIsRefused()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products.")); //$NON-NLS-1$

        assertFalse("a trailing separator leaves a segment naming nothing", scoping.ok()); //$NON-NLS-1$
        assertTrue("and it is the LAST segment: " + scoping.errorJson(), //$NON-NLS-1$
            scoping.errorJson().contains("nothing in segment 3")); //$NON-NLS-1$
    }

    @Test
    public void testASegmentOfNothingButUnicodeWhitespaceIsRefused()
    {
        // U+2003 IS Character.isWhitespace, so isBlank calls this segment blank and the padding
        // question skips it. Without the empty-segment question it walked straight through.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.\u2003.Products")); //$NON-NLS-1$

        assertFalse("a segment of nothing but whitespace names nothing", scoping.ok()); //$NON-NLS-1$
    }

    @Test
    public void testASegmentOfNothingButANonBreakingSpaceIsReportedAsPaddingNotAsEmpty()
    {
        // The asymmetry the shared judgement rests on, pinned where it bites. U+00A0 is NOT
        // Character.isWhitespace, so isBlank says the segment names something and the
        // empty-segment question passes it - which is exactly why the padding question must use
        // the WIDER predicate. Either refusal keeps the address off the engine; the pin is that
        // one of them fires.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.\u00a0.Products")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        // The WHOLE phrase, not contains("U+00A0"): the closing advice names U+00A0 as an
        // example of trim-surviving whitespace, so a code-only assertion passes even when the
        // offending-character clause names something else.
        assertTrue("the wider predicate is what catches it, so it is named as padding: " //$NON-NLS-1$
            + scoping.errorJson(), scoping.errorJson()
                .contains("has U+00A0, a whitespace character, at character 9")); //$NON-NLS-1$
    }

    @Test
    public void testALeadingDotIsRefusedAndTheWordingFitsIt()
    {
        // The shape the first wording left out. An empty segment BEFORE the first separator is
        // neither "between two '.'" nor "after the last one", so the sentence described an entry
        // the caller had not sent. The message now describes the SEGMENT instead of guessing
        // where a separator sits.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList(".Catalog")); //$NON-NLS-1$

        assertFalse(scoping.ok());
        String error = scoping.errorJson();
        assertTrue("it is the FIRST segment that names nothing: " + error, //$NON-NLS-1$
            error.contains("nothing in segment 1")); //$NON-NLS-1$
        assertFalse("and the wording may not describe a shape this entry does not have: " //$NON-NLS-1$
            + error, error.contains("between two")); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalSaysNothingWasStartedAndDoesNotOfferToTrimIt()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u00a0")); //$NON-NLS-1$

        String error = scoping.errorJson();
        assertTrue("the caller has to know the expensive run did not happen: " + error, //$NON-NLS-1$
            error.contains("Nothing was started")); //$NON-NLS-1$
        assertTrue("and why it is not trimmed for them: " + error, //$NON-NLS-1$
            error.contains("is no longer the address you asked about")); //$NON-NLS-1$
    }

    @Test
    public void testAnAsciiPaddedEntryIsStillAccepted()
    {
        // The check must not become a blanket refusal of anything with a space near it. Ordinary
        // ASCII padding is what trim() is for, and it was already handled: this pins that the new
        // question did not take that behaviour away.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("  Catalog.Products  ")); //$NON-NLS-1$

        assertTrue("trim still deals with the padding it was always able to deal with", //$NON-NLS-1$
            scoping.ok());
        assertEquals(Collections.singletonList("Catalog.Products"), scoping.symlinks()); //$NON-NLS-1$
    }

    @Test
    public void testAnOrdinaryEntryIsUnaffected()
    {
        Scoping scoping = ComparisonScopeBuilder.build(
            Arrays.asList("Catalog.Products", SPRAVOCHNIK + "." + TOVARY)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(scoping.ok());
        assertEquals(Arrays.asList("Catalog.Products", "Catalog." + TOVARY), //$NON-NLS-1$ //$NON-NLS-2$
            scoping.symlinks());
    }

    @Test
    public void testASpaceInsideANameIsNotPadding()
    {
        // Whitespace INSIDE a name is not padding, and whether a 1C name may hold a space is a
        // question about names that only a comparison answers. Refusing it here would refuse an
        // address this builder has no standing to judge.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.My Product")); //$NON-NLS-1$

        assertTrue("only the ENDS of a name are asked about", scoping.ok()); //$NON-NLS-1$
        assertEquals(Collections.singletonList("Catalog.My Product"), scoping.symlinks()); //$NON-NLS-1$
    }

    @Test
    public void testAZeroWidthCharacterIsNotReportedAsPadding()
    {
        // The DECLARED boundary, pinned so widening it is a decision rather than a drift.
        // U+200B is neither isWhitespace nor isSpaceChar - it is not whitespace in any Unicode
        // sense - and whether the object it spells exists is a question only a live comparison
        // answers. Refusing it here would be this class inventing a rule about names.
        Scoping scoping = ComparisonScopeBuilder.build(
            Collections.singletonList("Catalog.Products\u200b")); //$NON-NLS-1$

        assertTrue("a zero-width character is out of scope by decision, not by omission", //$NON-NLS-1$
            scoping.ok());
    }

    @Test
    public void testTheAddressPredicateIsTheOneOtherToolsAsk()
    {
        // get_comparison_node asks this same method about its objectFqn, so the two tools cannot
        // disagree about what a padded address is. Pinned directly, because the sharing is the
        // point: a second copy of the judgement would pass every test above.
        assertEquals(16, ComparisonScopeBuilder.paddedNameCharacter("Catalog.Products\u2003")); //$NON-NLS-1$
        assertEquals(8, ComparisonScopeBuilder.paddedNameCharacter("Catalog.\u00a0Products")); //$NON-NLS-1$
        assertEquals(-1, ComparisonScopeBuilder.paddedNameCharacter("Catalog.Products")); //$NON-NLS-1$
        assertEquals(-1, ComparisonScopeBuilder.paddedNameCharacter(null));
    }

    @Test
    public void testTheEmptySegmentPredicateCountsSegmentsFromOne()
    {
        assertEquals(2, PaddedNames.firstEmptyComponent("Catalog..Products", '.')); //$NON-NLS-1$
        assertEquals(3, PaddedNames.firstEmptyComponent("Catalog.Products.", '.')); //$NON-NLS-1$
        assertEquals(1, PaddedNames.firstEmptyComponent(".Catalog", '.')); //$NON-NLS-1$
        assertEquals(-1, PaddedNames.firstEmptyComponent("Catalog.Products", '.')); //$NON-NLS-1$
        assertEquals(-1, PaddedNames.firstEmptyComponent(null, '.'));
    }
}
