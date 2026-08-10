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
            banner.contains("30 of 45")); //$NON-NLS-1$
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
        assertFalse(index.isUndocumented());

        index.markUndocumented("AnyRef"); //$NON-NLS-1$
        assertTrue(index.isUndocumented());
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
        assertTrue(banner.contains("1 of 2 published names")); //-NLS-1$
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
