/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.doc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link PlatformNameIndex} - the "not found" banner of
 * {@code get_platform_documentation}.
 * <p>
 * The defect it was built for (issue #355): the banner listed the first 30 names the platform
 * provider handed out and called them "Available types", while those very names were the ones the
 * lookup could not resolve. An agent read its own query back out of the list, retried the other
 * spelling from the same list, and looped. These tests pin the three properties that break that
 * loop - only resolvable names are listed, the query itself is never suggested back, and the total
 * is stated - plus the bilingual near-match ranking a miss usually needs.
 */
public class PlatformNameIndexTest
{
    /** The Russian name of the catalog-object type set, as the platform publishes it. */
    private static final String CATALOG_OBJECT_RU = cyrillic(0x0421, 0x043f, 0x0440, 0x0430, 0x0432,
        0x043e, 0x0447, 0x043d, 0x0438, 0x043a, 0x041e, 0x0431, 0x044a, 0x0435, 0x043a, 0x0442);

    private static String cyrillic(int... codePoints)
    {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    public void testBannerStatesTheTotalNotJustTheSample()
    {
        // "first 30 ... (more available)" hid the scale: a caller could not tell a sample of 30 from
        // the whole vocabulary. The count is now explicit.
        PlatformNameIndex index = new PlatformNameIndex("Nope"); //$NON-NLS-1$
        for (int i = 0; i < 45; i++)
        {
            index.accept("Type" + i); //$NON-NLS-1$
        }
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(45, index.total());
        assertTrue("the banner must state the sample size and the total", //$NON-NLS-1$
            banner.contains("30 shown of 45")); //$NON-NLS-1$
        assertTrue("the sample must be capped at 30", banner.contains("- Type29")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the 31st name must not be listed", banner.contains("- Type30")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBannerIsRecognizedAsASoftNotFoundBanner()
    {
        // The tool turns the banner into a real ToolResult.error by this exact marker; losing it
        // would silently make every miss look like a success on the wire.
        PlatformNameIndex index = new PlatformNameIndex("Nope"); //$NON-NLS-1$
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(PlatformDocumentationService.isNotFoundBanner(banner));
        assertTrue(PlatformDocumentationService.stripNotFoundBanner(banner)
            .startsWith("Type not found: Nope")); //$NON-NLS-1$
    }

    @Test
    public void testTheQueryIsNeverSuggestedBackToTheCaller()
    {
        // The whole loop: "Type not found: CatalogObject ... Available types: - CatalogObject".
        // A name equal to the query is never offered, whatever else it is.
        PlatformNameIndex index = new PlatformNameIndex("CatalogObject"); //$NON-NLS-1$
        // A different spelling is still the same name.
        index.accept("catalogobject"); //$NON-NLS-1$
        index.accept("CatalogObjectCatalogName"); //$NON-NLS-1$

        assertFalse("the looked-up name must not be suggested back", //$NON-NLS-1$
            index.suggestions().contains("catalogobject")); //$NON-NLS-1$
        assertTrue("a genuinely different, related name is still offered", //$NON-NLS-1$
            index.suggestions().contains("CatalogObjectCatalogName")); //$NON-NLS-1$
    }

    @Test
    public void testPrefixMatchesAreOfferedBeforeOtherRelatedNames()
    {
        PlatformNameIndex index = new PlatformNameIndex("Value"); //$NON-NLS-1$
        index.accept("FixedValueTable"); //$NON-NLS-1$ contains the query, but does not start with it
        index.accept("ValueTable"); //$NON-NLS-1$

        assertEquals("a prefix match is the likeliest correction, so it comes first", //$NON-NLS-1$
            "ValueTable", index.suggestions().get(0)); //$NON-NLS-1$
        assertTrue(index.suggestions().contains("FixedValueTable")); //$NON-NLS-1$
    }

    @Test
    public void testAQualifiedNameIsPointedAtItsBaseType()
    {
        // A caller that asks for a CONCRETE metadata type ('CatalogObject.Currencies') must be sent
        // to the generic one, which is the type that IS documented - reported in issue #355.
        PlatformNameIndex index = new PlatformNameIndex("CatalogObject.Currencies"); //$NON-NLS-1$
        index.accept("CatalogObject"); //$NON-NLS-1$
        index.accept("Array"); //$NON-NLS-1$

        assertTrue("the base name the query qualifies must be suggested", //$NON-NLS-1$
            index.suggestions().contains("CatalogObject")); //$NON-NLS-1$
        assertFalse("an unrelated name must not be suggested", //$NON-NLS-1$
            index.suggestions().contains("Array")); //$NON-NLS-1$
    }

    @Test
    public void testAnIncidentalSubstringIsNotOfferedAsACorrection()
    {
        // The qualifying direction needs the dot. Without it every query that merely CONTAINS a
        // short platform name ("NoSuchType_ZZZ" contains "Type") was answered "Did you mean: Type?".
        PlatformNameIndex index = new PlatformNameIndex("NoSuchType_ZZZ"); //$NON-NLS-1$
        index.accept("Type"); //$NON-NLS-1$

        assertTrue("an incidental substring is not a correction", index.suggestions().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testSuggestionsWorkOnTheRussianName()
    {
        // Resolution is bilingual, so the suggestions must be too: a Russian query has to find the
        // Russian names the provider publishes.
        PlatformNameIndex index = new PlatformNameIndex(CATALOG_OBJECT_RU + ".Currencies"); //$NON-NLS-1$
        index.accept(CATALOG_OBJECT_RU);

        assertTrue(index.suggestions().contains(CATALOG_OBJECT_RU));
    }

    @Test
    public void testAKnownButUndocumentedNameGetsItsOwnDiagnosis()
    {
        // 'AnyRef' is a real platform type set that documents nothing. Reporting it as non-existent
        // is a wrong diagnosis and offers no way forward.
        PlatformNameIndex index = new PlatformNameIndex("AnyRef"); //$NON-NLS-1$
        index.accept("CatalogRef"); //$NON-NLS-1$
        assertEquals(PlatformNameIndex.MissReason.UNKNOWN_NAME, index.missReason());

        index.markDocumentsNothing();
        assertEquals(PlatformNameIndex.MissReason.DOCUMENTS_NOTHING, index.missReason());
    }

    @Test
    public void testAnUnreachableTargetIsNotReportedAsDocumentingNothing()
    {
        // The two failures are not the same answer: one says the platform HAS nothing (a retry is
        // pointless), the other says we could not reach what it has (a retry may well work). Saying
        // the first over the second states a fact about the platform that is not in evidence.
        PlatformNameIndex index = new PlatformNameIndex("CatalogObject"); //$NON-NLS-1$

        index.markTargetUnresolved();
        assertEquals(PlatformNameIndex.MissReason.TARGET_UNRESOLVED, index.missReason());
    }

    @Test
    public void testAReachabilityFailureOutranksADocumentsNothingVerdict()
    {
        // One name can match two descriptions (the platform publishes duplicates) and fail
        // differently in each. The claim that survives must be the one that is still true.
        PlatformNameIndex index = new PlatformNameIndex("CatalogObject"); //$NON-NLS-1$

        index.markDocumentsNothing();
        index.markTargetUnresolved();
        assertEquals(PlatformNameIndex.MissReason.TARGET_UNRESOLVED, index.missReason());

        // ... and in the other arrival order too.
        PlatformNameIndex reversed = new PlatformNameIndex("CatalogObject"); //$NON-NLS-1$
        reversed.markTargetUnresolved();
        reversed.markDocumentsNothing();
        assertEquals(PlatformNameIndex.MissReason.TARGET_UNRESOLVED, reversed.missReason());
    }

    @Test
    public void testTheHintIsAppendedAsTheNextStep()
    {
        PlatformNameIndex index = new PlatformNameIndex("Nope"); //$NON-NLS-1$
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Use get_metadata_details for a configuration object."); //$NON-NLS-1$

        assertTrue(banner.contains("Use get_metadata_details for a configuration object.")); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptyProviderSaysSoInsteadOfListingNothing()
    {
        PlatformNameIndex index = new PlatformNameIndex("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Array", "types", "hint"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(0, index.total());
        assertTrue(banner.contains("provider may be empty")); //$NON-NLS-1$
        assertFalse("an empty provider must not claim an available list", //$NON-NLS-1$
            banner.contains("Available types (")); //$NON-NLS-1$
    }

    @Test
    public void testAGoodNameBehindABlockOfBadOnesStillGetsListed()
    {
        // The pool must not be exhausted by a run of failures at the HEAD of the scan: the provider
        // hands names out in its own order, and a hundred unresolvable ones in front used to bury
        // every usable name behind them.
        PlatformNameIndex index = new PlatformNameIndex("Nope", n -> !n.startsWith("Broken")); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 100; i++)
        {
            index.accept("BrokenType" + i); //$NON-NLS-1$
        }
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", "hint"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("a resolvable name behind the bad block must still be listed", //$NON-NLS-1$
            banner.contains("- Array")); //$NON-NLS-1$
        assertFalse(banner.contains("- BrokenType0")); //$NON-NLS-1$
    }

    @Test
    public void testABadBlockLONGERThanTheAttemptBudgetStillDoesNotBuryTheGoodNames()
    {
        // The previous test stopped SHORT of the real boundary: 100 bad names against an attempt
        // budget of 120 never tested what happens when the block outlasts the budget itself. It
        // does happen - unresolvable entries arrive clustered, one broken package at a time - and
        // walking the pool head-first then spends every attempt inside the block while thousands of
        // usable names sit behind it, unvisited, under a banner claiming nothing resolved.
        //
        // The budget stays (each attempt loads a platform resource on the UI thread); what changed
        // is that the attempts are SPREAD across the pool instead of being consumed by its head.
        PlatformNameIndex index = new PlatformNameIndex("Nope", n -> !n.startsWith("Broken")); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 400; i++)
        {
            index.accept("BrokenType" + i); //$NON-NLS-1$
        }
        for (int i = 0; i < 50; i++)
        {
            index.accept("GoodType" + i); //$NON-NLS-1$
        }
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", "hint"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("a bad block longer than the attempt budget must not hide what is behind it", //$NON-NLS-1$
            banner.contains("- GoodType")); //$NON-NLS-1$
        assertFalse("and no unresolvable name may be advertised even so", //$NON-NLS-1$
            banner.contains("- BrokenType")); //$NON-NLS-1$
    }

    @Test
    public void testTheBudgetSpansThePoolRightAtItsBoundary()
    {
        // The exact shape called out in review: one more candidate than the budget can check, with
        // the only resolvable name last. Head-first ordering misses it by one; a strided pass visits
        // the whole range and finds it.
        PlatformNameIndex index = new PlatformNameIndex("Nope", "Array"::equals); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 120; i++)
        {
            index.accept("BrokenType" + i); //$NON-NLS-1$
        }
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", "hint"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("the one resolvable name sits past the budget and must still be found", //$NON-NLS-1$
            banner.contains("- Array")); //$NON-NLS-1$
    }

    @Test
    public void testAnExhaustedSampleIsNotReportedAsAnEmptyProvider()
    {
        // "provider may be empty" is a specific diagnosis and it would be FALSE here: the provider
        // published names, they just did not resolve. And the hint - the caller's next step - must
        // survive precisely in the case where it is needed most.
        PlatformNameIndex index = new PlatformNameIndex("Nope", n -> false); //$NON-NLS-1$
        index.accept("Array"); //$NON-NLS-1$
        index.accept("ValueTable"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Use get_metadata_details for a configuration object."); //$NON-NLS-1$

        assertFalse("the provider is not empty - it published 2 names", //$NON-NLS-1$
            banner.contains("provider may be empty")); //$NON-NLS-1$
        assertTrue(banner.contains("2 candidate types were found")); //$NON-NLS-1$
        assertTrue("the next step must survive", //$NON-NLS-1$
            banner.contains("Use get_metadata_details for a configuration object.")); //$NON-NLS-1$
    }

    @Test
    public void testAQueryCannotForgeItsOwnEntryInTheVerifiedList()
    {
        // The bullets carry a promise ("every name listed here resolves") that consumers parse.
        // A looked-up name is echoed back, so a name carrying newlines could write its own bullet
        // and get an unverifiable name counted under that promise.
        String forged = "Nope\n- ForgedType"; //$NON-NLS-1$
        PlatformNameIndex index = new PlatformNameIndex(forged);
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", forged, "types", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a newline in the query must not produce a bullet line", //$NON-NLS-1$
            banner.contains("\n- ForgedType")); //$NON-NLS-1$
        assertTrue("the bad value is still echoed, just flattened", //$NON-NLS-1$
            banner.contains("ForgedType")); //$NON-NLS-1$
        assertTrue(banner.contains("- Array")); //$NON-NLS-1$
    }

    @Test
    public void testAProviderNameThatCannotBePrintedOnOneLineIsDropped()
    {
        // The echoed query is flattened; a LISTED name may not be. A listed name is one the caller
        // is invited to copy and query, so printing a flattened version would advertise a string
        // that no longer resolves - and its tail would read as a second bullet nobody verified.
        String twoLine = "Good" + (char)0x2028 + "- Sneaky"; //$NON-NLS-1$ //$NON-NLS-2$
        PlatformNameIndex index = new PlatformNameIndex("Nope", candidate -> true); //$NON-NLS-1$
        index.accept(twoLine);
        index.accept("Array"); //$NON-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a name that cannot be printed on one line must not be listed", //$NON-NLS-1$
            banner.contains("Sneaky")); //$NON-NLS-1$
        assertTrue("the printable names are still listed", banner.contains("- Array")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("an unprintable name must not be counted as a candidate either", //$NON-NLS-1$
            1, index.total());
    }

    @Test
    public void testAFullPrefixBucketDoesNotStopTheWeakerBucketsFromFilling()
    {
        // Collection used to stop entirely once the prefix bucket was full - so a run of
        // unresolvable prefix matches at the head of the provider's output emptied the fallback for
        // the whole rest of the scan. Verification then found nothing in the strong bucket and had
        // nothing to fall back ON, and the misspelling got no "Did you mean" however many usable
        // names came later. Each bucket is capped separately now; the scan does not stop.
        PlatformNameIndex index =
            new PlatformNameIndex("ValueTabel", candidate -> "ValueTable".equals(candidate)); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 40; i++)
        {
            index.accept("ValueTabelBroken" + i); // all match by prefix, none resolve //$NON-NLS-1$
        }
        index.accept("ValueTable"); // arrives long after the prefix bucket filled //$NON-NLS-1$

        assertTrue("a candidate seen after the prefix bucket filled must still be offered", //$NON-NLS-1$
            index.suggestions().contains("ValueTable")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvableStrongCandidateDoesNotSuppressTheTypoFallback()
    {
        // The strong bucket outranks the typo bucket - but "last resort" has to be decided on what
        // the strong bucket YIELDS, not on what it holds. One registered-but-unresolvable name
        // sharing the query's prefix used to consume the decision, and the misspelling then got no
        // "Did you mean" at all - the one case the typo bucket exists for.
        PlatformNameIndex index =
            new PlatformNameIndex("ValueTabel", candidate -> "ValueTable".equals(candidate)); //$NON-NLS-1$ //$NON-NLS-2$
        index.accept("ValueTabelBroken"); // matches by prefix, does not resolve //$NON-NLS-1$
        index.accept("ValueTable"); // two edits away, resolves //$NON-NLS-1$

        List<String> suggestions = index.suggestions();
        assertTrue("the resolvable typo match must still be offered", //$NON-NLS-1$
            suggestions.contains("ValueTable")); //$NON-NLS-1$
        assertFalse("an unresolvable name must never be advertised", //$NON-NLS-1$
            suggestions.contains("ValueTabelBroken")); //$NON-NLS-1$
    }

    @Test
    public void testABrokenPrefixBucketDoesNotStarveTheSubstringBucket()
    {
        // Ranked first is not entitled to everything. A wall of unresolvable prefix matches used to
        // consume the whole verification allowance, and the base-type hint in the substring bucket
        // - the single most useful suggestion this banner can give a qualified query - never got a
        // resolution attempt at all.
        PlatformNameIndex index = new PlatformNameIndex(
            "CatalogObject.Currencies", "CatalogObject"::equals); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 300; i++)
        {
            // Start with the query, so they land in the STRONG prefix bucket, and never resolve.
            index.accept("CatalogObject.CurrenciesBroken" + i); //$NON-NLS-1$
        }
        index.accept("CatalogObject"); // the base type the query qualifies - resolves //$NON-NLS-1$

        assertTrue("the base-type hint must survive a prefix bucket full of broken names", //$NON-NLS-1$
            index.suggestions().contains("CatalogObject")); //$NON-NLS-1$
    }

    @Test
    public void testASaturatedPrefixBucketStillReachesAResolvablePrefixMatch()
    {
        // The prefix bucket is the STRONGEST one, so a caller whose correction is a prefix match
        // should get it. Capped at three times the offered count, a run of unresolvable names
        // sharing the query's prefix filled the bucket outright and the good prefix match behind
        // them was never kept - verification then rejected all 24 retained entries and the typo
        // fallback could not help, because a prefix match never lands in the typo bucket.
        PlatformNameIndex index =
            new PlatformNameIndex("ValueTab", "ValueTable"::equals); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 200; i++)
        {
            index.accept("ValueTabBroken" + i); // all match by prefix, none resolve //$NON-NLS-1$
        }
        index.accept("ValueTable"); // matches by prefix too, and is the only one that resolves //$NON-NLS-1$

        assertTrue("a resolvable prefix match behind a saturated bucket must still be offered", //$NON-NLS-1$
            index.suggestions().contains("ValueTable")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnprintableNameDoesNotSuppressTheRealSuggestion()
    {
        // Refusing such a name only at render time is not enough. Matching the query by prefix, it
        // fills the STRONG suggestion bucket, and a non-empty strong bucket is what stops the typo
        // bucket from being offered - so the one good answer would be suppressed by a name the
        // banner was never going to print.
        PlatformNameIndex index = new PlatformNameIndex("ValueTabel", candidate -> true); //$NON-NLS-1$
        index.accept("ValueTabel" + (char)0x2028 + "Injected"); //$NON-NLS-1$ //$NON-NLS-2$
        index.accept("ValueTable"); //$NON-NLS-1$

        assertTrue("the typo suggestion must survive", //$NON-NLS-1$
            index.suggestions().contains("ValueTable")); //$NON-NLS-1$
    }

    @Test
    public void testTheForgeryGuardCoversEveryTerminatorAReaderHonours()
    {
        // Flattening CR/LF alone is not enough: this banner is parsed by consumers that break lines
        // more widely than a Java reader does (Python's str.splitlines breaks on VT, FF, the three
        // information separators, NEL and the Unicode line/paragraph separators). Any terminator
        // that survives into the output is a working forgery for exactly those readers. Built with
        // (char) casts on purpose - a \\uXXXX escape for U+2028 in Java SOURCE is itself a line
        // terminator to the compiler.
        int[] terminators = {0x000B, 0x000C, 0x001C, 0x001D, 0x001E, 0x0085, 0x2028, 0x2029};
        for (int terminator : terminators)
        {
            String forged = "Nope" + (char)terminator + "- ForgedType"; //$NON-NLS-1$ //$NON-NLS-2$
            PlatformNameIndex index = new PlatformNameIndex(forged);
            index.accept("Array"); //$NON-NLS-1$
            String banner = index.buildNotFoundBanner("Type not found: ", forged, "types", null); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(String.format("U+%04X survived into the banner and can still start a line", //$NON-NLS-1$
                Integer.valueOf(terminator)), -1, banner.indexOf(terminator));
            assertTrue("the bad value is still echoed, just flattened", //$NON-NLS-1$
                banner.contains("ForgedType")); //$NON-NLS-1$
        }
    }

    @Test
    public void testBlankNamesAreIgnored()
    {
        PlatformNameIndex index = new PlatformNameIndex("Array"); //$NON-NLS-1$
        index.accept(null);
        index.accept(""); //$NON-NLS-1$
        assertEquals(0, index.total());
    }

    @Test
    public void testARepeatedNameIsCountedAndOfferedOnce()
    {
        // The platform really does publish one name twice - two distinct types can share a single
        // Russian name - so the total over-reported and one name could eat two suggestion slots.
        PlatformNameIndex index = new PlatformNameIndex("ValueTab"); //$NON-NLS-1$
        index.accept("ValueTable"); //$NON-NLS-1$
        index.accept("ValueTable"); //$NON-NLS-1$
        index.accept("valuetable"); //$NON-NLS-1$ same name, different spelling of the same letters

        assertEquals("a repeated name must be counted once", 1, index.total()); //$NON-NLS-1$
        assertEquals("and offered once", 1, index.suggestions().size()); //$NON-NLS-1$
    }

    @Test
    public void testAMisspellingStillGetsASuggestion()
    {
        // A transposition shares no useful prefix and contains nothing, so the substring rules alone
        // answered a plain typo with no suggestion at all.
        PlatformNameIndex index = new PlatformNameIndex("ValueTabel"); //$NON-NLS-1$
        index.accept("ValueTable"); //$NON-NLS-1$
        index.accept("Structure"); //$NON-NLS-1$

        assertEquals(List.of("ValueTable"), index.suggestions()); //$NON-NLS-1$
    }

    @Test
    public void testATypoSuggestionIsOnlyALastResort()
    {
        // A name genuinely related to the query beats one that merely looks similar; mixing them
        // would bury the good answer under lookalikes.
        PlatformNameIndex index = new PlatformNameIndex("ValueTabl"); //$NON-NLS-1$
        index.accept("ValueTablePro"); //$NON-NLS-1$ starts with the query -> the strong bucket
        index.accept("ValueTable"); //$NON-NLS-1$ also a 1-edit typo hit

        assertEquals("the prefix match alone answers", //$NON-NLS-1$
            List.of("ValueTablePro", "ValueTable"), index.suggestions()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAShortQueryIsNotTypoMatched()
    {
        // Within two edits of a short query sits half the vocabulary - that is noise, not help.
        PlatformNameIndex index = new PlatformNameIndex("Xyz"); //$NON-NLS-1$
        index.accept("Xml"); //$NON-NLS-1$
        index.accept("Map"); //$NON-NLS-1$

        assertTrue(index.suggestions().isEmpty());
    }

    @Test
    public void testANameThatDoesNotResolveIsNeverPrinted()
    {
        // The final guard. A name can pass every cheap structural check and still fail to resolve;
        // printing it recreates the retry loop, so the banner re-checks what it is about to show.
        PlatformNameIndex index = new PlatformNameIndex("Nope", n -> !n.startsWith("Broken")); //-NLS-1$ //-NLS-2$
        index.accept("BrokenType"); //-NLS-1$
        index.accept("Array"); //-NLS-1$
        String banner = index.buildNotFoundBanner("Type not found: ", "Nope", "types", null); //-NLS-1$ //-NLS-2$ //-NLS-3$

        assertTrue("a resolvable name is listed", banner.contains("- Array")); //-NLS-1$ //-NLS-2$
        assertFalse("an unresolvable name must never be advertised", //-NLS-1$
            banner.contains("- BrokenType")); //-NLS-1$
        // The total still reports what the platform PUBLISHES, and says so rather than calling them
        // all documented.
        assertTrue(banner.contains("1 shown of 2 candidate names")); //-NLS-1$
    }

    @Test
    public void testAnUnresolvableSuggestionIsDroppedToo()
    {
        PlatformNameIndex index = new PlatformNameIndex("Value", n -> !n.startsWith("Broken")); //-NLS-1$ //-NLS-2$
        index.accept("BrokenValueThing"); //-NLS-1$
        index.accept("ValueTable"); //-NLS-1$

        assertEquals(List.of("ValueTable"), index.suggestions()); //-NLS-1$
    }

    @Test
    public void testADistantNameIsNotOfferedAsATypo()
    {
        PlatformNameIndex index = new PlatformNameIndex("ValueTabel"); //$NON-NLS-1$
        index.accept("HTTPConnection"); //$NON-NLS-1$

        assertTrue(index.suggestions().isEmpty());
    }
}
