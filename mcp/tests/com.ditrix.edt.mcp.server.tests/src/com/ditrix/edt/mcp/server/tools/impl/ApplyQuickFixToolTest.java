/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ApplyQuickFixTool.MarkerMatch;
import com.ditrix.edt.mcp.server.tools.impl.ApplyQuickFixTool.SelectorArgument;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;

/**
 * Lightweight contract tests for {@link ApplyQuickFixTool}: tool metadata and JSON schema,
 * without the Eclipse/EDT runtime. The actual fix behaviour (resolve marker by id -&gt; prepare
 * -&gt; variants -&gt; execute) needs a live workbench + marker manager + IFixManager, so it is
 * covered by the E2E suite (test_apply_quick_fix.py).
 */
import java.util.Collections;

import com.google.gson.JsonObject;

public class ApplyQuickFixToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("apply_quick_fix", new ApplyQuickFixTool().getName()); //$NON-NLS-1$
        assertEquals(ApplyQuickFixTool.NAME, new ApplyQuickFixTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ApplyQuickFixTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ApplyQuickFixTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('apply_quick_fix')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ApplyQuickFixTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"checkId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"index\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"variant\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new ApplyQuickFixTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("checkId must be required", tail.contains("\"checkId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // modulePath/line/index/variant are optional locator-narrowing / disambiguation params.
        assertFalse("modulePath must NOT be required", tail.contains("\"modulePath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("variant must NOT be required", tail.contains("\"variant\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresResultKeys()
    {
        String schema = new ApplyQuickFixTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"success\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"checkId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"location\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"appliedVariant\"")); //$NON-NLS-1$
    }

    // ---- chooseIndex: pure marker-index / fix-variant selection decision -----------------------

    @Test
    public void testChooseIndexNoSelectorSingleCandidateAutoSelects()
    {
        assertEquals(0, ApplyQuickFixTool.chooseIndex(1, -1));
    }

    @Test
    public void testChooseIndexNoSelectorMultipleCandidatesIsAmbiguous()
    {
        assertEquals(-1, ApplyQuickFixTool.chooseIndex(3, -1));
    }

    @Test
    public void testChooseIndexValidSelectorInRange()
    {
        assertEquals(0, ApplyQuickFixTool.chooseIndex(3, 1));
        assertEquals(2, ApplyQuickFixTool.chooseIndex(3, 3));
    }

    @Test
    public void testChooseIndexSelectorOutOfRangeIsRejected()
    {
        assertEquals(-1, ApplyQuickFixTool.chooseIndex(3, 4));
    }

    @Test
    public void testChooseIndexStaleSelectorAgainstSingleCandidateIsRejectedNotSilentlyResolved()
    {
        // The bug this guards: a selector left over from an earlier multi-candidate response (index=2,
        // say) must NOT be silently honored against a NOW-single candidate set - it must be rejected
        // as out of range, exactly like it would be against the original multi-candidate set.
        assertEquals(-1, ApplyQuickFixTool.chooseIndex(1, 2));
    }

    @Test
    public void testChooseIndexSelectorOfOneAgainstSingleCandidateIsAccepted()
    {
        // An explicit, IN-RANGE selector (1) against a single candidate is legitimate and must
        // still resolve - only an out-of-range selector is rejected.
        assertEquals(0, ApplyQuickFixTool.chooseIndex(1, 1));
    }

    // ---- extractSelectorArgument: presence-vs-default detection for index/variant/line ----------

    @Test
    public void testExtractSelectorArgumentOmittedYieldsNotGivenSentinel()
    {
        Map<String, String> params = new HashMap<>();
        SelectorArgument result = ApplyQuickFixTool.extractSelectorArgument(params, "index"); //$NON-NLS-1$
        assertFalse("an omitted argument must not be rejected", result.isRejected()); //$NON-NLS-1$
        assertEquals(-1, result.value);
    }

    @Test
    public void testExtractSelectorArgumentBlankYieldsNotGivenSentinel()
    {
        Map<String, String> params = new HashMap<>();
        params.put("index", ""); //$NON-NLS-1$ //$NON-NLS-2$
        SelectorArgument result = ApplyQuickFixTool.extractSelectorArgument(params, "index"); //$NON-NLS-1$
        assertFalse("a blank argument must be treated as omitted, not rejected", result.isRejected()); //$NON-NLS-1$
        assertEquals(-1, result.value);
    }

    @Test
    public void testExtractSelectorArgumentValidValuePassesThrough()
    {
        Map<String, String> params = new HashMap<>();
        params.put("index", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        SelectorArgument result = ApplyQuickFixTool.extractSelectorArgument(params, "index"); //$NON-NLS-1$
        assertFalse(result.isRejected());
        assertEquals(2, result.value);
    }

    @Test
    public void testExtractSelectorArgumentExplicitZeroIsRejectedNotDefaulted()
    {
        // The bug this guards: index=0 (or variant=0 / line=0) is invalid (1-based), but
        // JsonUtils.extractIntArgument has no way to tell "explicit 0" from "omitted" - both would
        // otherwise silently resolve to the same default. An explicit 0 must be REJECTED here.
        Map<String, String> params = new HashMap<>();
        params.put("index", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        SelectorArgument result = ApplyQuickFixTool.extractSelectorArgument(params, "index"); //$NON-NLS-1$
        assertTrue("explicit index=0 must be rejected, not silently defaulted", result.isRejected()); //$NON-NLS-1$
        assertNotNull(result.rejection);
        assertTrue(result.rejection.contains("index")); //$NON-NLS-1$
    }

    @Test
    public void testExtractSelectorArgumentExplicitZeroRejectedForVariantAndLine()
    {
        Map<String, String> variantParams = new HashMap<>();
        variantParams.put("variant", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ApplyQuickFixTool.extractSelectorArgument(variantParams, "variant").isRejected()); //$NON-NLS-1$

        Map<String, String> lineParams = new HashMap<>();
        lineParams.put("line", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ApplyQuickFixTool.extractSelectorArgument(lineParams, "line").isRejected()); //$NON-NLS-1$
    }

    @Test
    public void testExtractSelectorArgumentExplicitNegativeIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("index", "-3"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ApplyQuickFixTool.extractSelectorArgument(params, "index").isRejected()); //$NON-NLS-1$
    }

    @Test
    public void testExtractSelectorArgumentNullParamsMapYieldsNotGivenSentinel()
    {
        SelectorArgument result = ApplyQuickFixTool.extractSelectorArgument(null, "index"); //$NON-NLS-1$
        assertFalse(result.isRejected());
        assertEquals(-1, result.value);
        assertNull(result.rejection);
    }

    // ---- MarkerMatch.DETERMINISTIC_ORDER: index stability across marker-stream encounter order ---

    @Test
    public void testDeterministicOrderIsSameRegardlessOfEncounterOrder()
    {
        // The bug this guards: IMarkerManager.markers() makes no ordering promise, so an index
        // chosen from one call's listing must still resolve to the same marker on the next call
        // even if the stream happens to enumerate the same set in a different order.
        MarkerMatch a = new MarkerMatch(null, "check-a", "Module1.bsl", 5, "first"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MarkerMatch b = new MarkerMatch(null, "check-b", "Module1.bsl", 10, "second"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MarkerMatch c = new MarkerMatch(null, "check-c", "Module2.bsl", 1, "third"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<MarkerMatch> encounterOrder1 = new ArrayList<>(List.of(c, a, b));
        List<MarkerMatch> encounterOrder2 = new ArrayList<>(List.of(b, c, a));
        encounterOrder1.sort(MarkerMatch.DETERMINISTIC_ORDER);
        encounterOrder2.sort(MarkerMatch.DETERMINISTIC_ORDER);

        List<String> sortedIds1 = idsOf(encounterOrder1);
        List<String> sortedIds2 = idsOf(encounterOrder2);
        assertEquals("two different stream encounter orders of the same set must sort identically", //$NON-NLS-1$
            sortedIds1, sortedIds2);
        assertEquals(List.of("check-a", "check-b", "check-c"), sortedIds1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testDeterministicOrderSortsNullModulePathAndLineFirst()
    {
        MarkerMatch withModule = new MarkerMatch(null, "check-a", "Module1.bsl", 1, "msg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MarkerMatch metadataOnly = new MarkerMatch(null, "check-b", null, null, "msg"); //$NON-NLS-1$ //$NON-NLS-2$
        MarkerMatch noLine = new MarkerMatch(null, "check-c", "Module1.bsl", null, "msg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<MarkerMatch> matches = new ArrayList<>(List.of(withModule, noLine, metadataOnly));
        matches.sort(MarkerMatch.DETERMINISTIC_ORDER);

        assertEquals(List.of("check-b", "check-c", "check-a"), idsOf(matches)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static List<String> idsOf(List<MarkerMatch> matches)
    {
        List<String> ids = new ArrayList<>();
        for (MarkerMatch m : matches)
        {
            ids.add(m.checkId);
        }
        return ids;
    }

    // ---- location(): an object-level marker must still identify its target ---------------------

    @Test
    public void testLocationPrefersModulePositionWhenAvailable()
    {
        MarkerMatch m = new MarkerMatch(null, "check-a", "Module.bsl", 12, "msg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        m.objectPresentation = "Catalog.Products"; //$NON-NLS-1$
        assertEquals("a BSL-positioned marker keeps its module:line locator", //$NON-NLS-1$
            "Module.bsl:12", m.location()); //$NON-NLS-1$
    }

    @Test
    public void testLocationFallsBackToObjectPresentationForAnObjectLevelMarker()
    {
        // The bug this guards: an object/metadata-level marker has NO modulePath/line, so location()
        // used to degrade straight to the check id - and two such markers of the same check then
        // printed as an identical choice in the "several markers match" listing, leaving index=N
        // pointing at a target the caller could not identify.
        MarkerMatch m = new MarkerMatch(null, "check-a", null, null, "msg"); //$NON-NLS-1$ //$NON-NLS-2$
        m.objectPresentation = "Catalog.Products"; //$NON-NLS-1$
        assertEquals("Catalog.Products", m.location()); //$NON-NLS-1$
    }

    @Test
    public void testLocationFallsBackToCheckIdWhenNothingElseResolved()
    {
        MarkerMatch m = new MarkerMatch(null, "check-a", null, null, "msg"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("check-a", m.location()); //$NON-NLS-1$
    }

    @Test
    public void testDeterministicOrderSeparatesObjectLevelMarkersByTheirTarget()
    {
        // Two object-level markers of the SAME check with the SAME message differ only by their
        // target object. Without objectPresentation in the comparator they would tie, and their
        // printed order - hence what index=N selects - would fall back to the marker stream's own
        // unspecified order.
        MarkerMatch b = new MarkerMatch(null, "check-a", null, null, "same message"); //$NON-NLS-1$ //$NON-NLS-2$
        b.objectPresentation = "Catalog.Beta"; //$NON-NLS-1$
        MarkerMatch a = new MarkerMatch(null, "check-a", null, null, "same message"); //$NON-NLS-1$ //$NON-NLS-2$
        a.objectPresentation = "Catalog.Alpha"; //$NON-NLS-1$

        List<MarkerMatch> order1 = new ArrayList<>(List.of(b, a));
        List<MarkerMatch> order2 = new ArrayList<>(List.of(a, b));
        order1.sort(MarkerMatch.DETERMINISTIC_ORDER);
        order2.sort(MarkerMatch.DETERMINISTIC_ORDER);

        assertEquals("Catalog.Alpha", order1.get(0).objectPresentation); //$NON-NLS-1$
        assertEquals("Catalog.Beta", order1.get(1).objectPresentation); //$NON-NLS-1$
        assertEquals("both encounter orders must sort identically", //$NON-NLS-1$
            order1.get(0).objectPresentation, order2.get(0).objectPresentation);
    }

    // ---- sortVariantsDeterministically: variant=N stability across getApplicableFixVariants() ----
    // encounter order (the class of hazard the DETERMINISTIC_ORDER tests above cover for markers) --

    @Test
    public void testSortVariantsDeterministicallyIsStableAcrossEncounterOrder()
    {
        FixVariantDescriptor alpha = new FixVariantDescriptor("Alpha fix", null); //$NON-NLS-1$
        FixVariantDescriptor beta = new FixVariantDescriptor("Beta fix", null); //$NON-NLS-1$

        // Two opposite encounter orders, standing in for getApplicableFixVariants() enumerating
        // the SAME two variants differently on separate calls (it makes no ordering promise).
        List<FixVariantDescriptor> order1 = new ArrayList<>(List.of(beta, alpha));
        List<FixVariantDescriptor> order2 = new ArrayList<>(List.of(alpha, beta));
        ApplyQuickFixTool.sortVariantsDeterministically(order1);
        ApplyQuickFixTool.sortVariantsDeterministically(order2);

        assertEquals("both encounter orders must sort identically, so variant=1 always means " //$NON-NLS-1$
            + "the same fix", order1.get(0), order2.get(0)); //$NON-NLS-1$
        assertEquals("sorted by displayed description: 'Alpha fix' precedes 'Beta fix'", //$NON-NLS-1$
            alpha, order1.get(0));
        assertEquals(beta, order1.get(1));
    }

    @Test
    public void testSortVariantsDeterministicallyFallsBackToDetailsThenPlaceholder()
    {
        FixVariantDescriptor withDetailsOnly = new FixVariantDescriptor("", "Zeta details"); //$NON-NLS-1$ //$NON-NLS-2$
        FixVariantDescriptor unnamed = new FixVariantDescriptor(null, null);

        // "(unnamed fix)" < "Zeta details" lexically, so the unnamed placeholder sorts first -
        // this only needs to be DETERMINISTIC, not any particular order, but pins the behaviour.
        List<FixVariantDescriptor> variants = new ArrayList<>(List.of(withDetailsOnly, unnamed));
        ApplyQuickFixTool.sortVariantsDeterministically(variants);

        assertEquals(unnamed, variants.get(0));
        assertEquals(withDetailsOnly, variants.get(1));
    }

    @Test
    public void testSortVariantsDeterministicallyBreaksTiesOnDetailsWhenDescriptionsMatch()
    {
        // Two variants sharing the SAME non-empty description but different details: the
        // collapsed describe() text used as the sort key would tie them, leaving their relative
        // order exactly as unstable as getApplicableFixVariants()'s own encounter order - the
        // collision the two-level (description, details) comparator exists to close.
        FixVariantDescriptor toB = new FixVariantDescriptor("Rename variable", "to 'b'"); //$NON-NLS-1$ //$NON-NLS-2$
        FixVariantDescriptor toA = new FixVariantDescriptor("Rename variable", "to 'a'"); //$NON-NLS-1$ //$NON-NLS-2$

        List<FixVariantDescriptor> order1 = new ArrayList<>(List.of(toB, toA));
        List<FixVariantDescriptor> order2 = new ArrayList<>(List.of(toA, toB));
        ApplyQuickFixTool.sortVariantsDeterministically(order1);
        ApplyQuickFixTool.sortVariantsDeterministically(order2);

        assertEquals("both encounter orders must sort identically even though describe() text ties", //$NON-NLS-1$
            order1.get(0), order2.get(0));
        assertEquals(toA, order1.get(0));
        assertEquals(toB, order1.get(1));
    }

    // ---- describeForListing: the listing must SHOW what the ordering above relies on ----

    @Test
    public void testDescribeForListingSeparatesVariantsSharingOneDescription()
    {
        // Exactly the collision the comparator above orders by. Sorting alone is not enough: the
        // caller answers the listing with a bare number and the tool then MUTATES the source, so
        // two variants printed identically would be a coin flip.
        FixVariantDescriptor toA = new FixVariantDescriptor("Rename variable", "to 'a'"); //$NON-NLS-1$ //$NON-NLS-2$
        FixVariantDescriptor toB = new FixVariantDescriptor("Rename variable", "to 'b'"); //$NON-NLS-1$ //$NON-NLS-2$

        String labelA = ApplyQuickFixTool.describeForListing(toA);
        String labelB = ApplyQuickFixTool.describeForListing(toB);

        assertNotEquals("variants differing only in details must get distinguishable labels", //$NON-NLS-1$
            labelA, labelB);
        assertTrue("the label must keep the description: " + labelA, //$NON-NLS-1$
            labelA.contains("Rename variable")); //$NON-NLS-1$
        assertTrue("the label must add the details: " + labelA, labelA.contains("to 'a'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescribeForListingDoesNotRepeatDetailsUsedAsTheDescriptionFallback()
    {
        // describe() already falls back to details when the description is empty; appending them
        // again would print the same text twice.
        FixVariantDescriptor detailsOnly = new FixVariantDescriptor("", "Extract method"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Extract method", ApplyQuickFixTool.describeForListing(detailsOnly)); //$NON-NLS-1$
    }

    @Test
    public void testDescribeForListingLeavesADescriptionOnlyVariantUnchanged()
    {
        FixVariantDescriptor descriptionOnly = new FixVariantDescriptor("Remove export", null); //$NON-NLS-1$

        assertEquals("Remove export", ApplyQuickFixTool.describeForListing(descriptionOnly)); //$NON-NLS-1$
    }

    // ---- hasUnresolvableAmbiguity: never hand out an index the caller cannot rely on ----

    @Test
    public void testUnresolvableAmbiguityWhenTwoObjectLevelTargetsDidNotResolve()
    {
        // The hazard: no module position to separate them and no resolved target either, so they
        // print identically AND sort arbitrarily - yet they may be different objects. An index over
        // this batch can select a different one on the retry, and this tool MUTATES what it selects.
        List<MarkerMatch> matches = new ArrayList<>(List.of(
            new MarkerMatch(null, "check-a", null, null, "same message"), //$NON-NLS-1$ //$NON-NLS-2$
            new MarkerMatch(null, "check-a", null, null, "same message"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(ApplyQuickFixTool.hasUnresolvableAmbiguity(matches));
    }

    @Test
    public void testResolvedObjectTargetsAreNotAnUnresolvableAmbiguity()
    {
        // Same shape, but the targets DID resolve: the comparator separates them and the listing
        // names them, so indexing is safe. This is the case a transaction-outcome flag would have
        // got wrong whenever the read merely opened.
        MarkerMatch alpha = new MarkerMatch(null, "check-a", null, null, "same message"); //$NON-NLS-1$ //$NON-NLS-2$
        MarkerMatch beta = new MarkerMatch(null, "check-a", null, null, "same message"); //$NON-NLS-1$ //$NON-NLS-2$
        alpha.objectPresentation = "Catalog.Alpha"; //$NON-NLS-1$
        beta.objectPresentation = "Catalog.Beta"; //$NON-NLS-1$
        List<MarkerMatch> matches = new ArrayList<>(List.of(alpha, beta));
        matches.sort(MarkerMatch.DETERMINISTIC_ORDER);

        assertFalse(ApplyQuickFixTool.hasUnresolvableAmbiguity(matches));
    }

    @Test
    public void testOneUnresolvedObjectMarkerAmongDistinguishableOnesIsNotRefused()
    {
        // Only ONE candidate lacks a module position, so nothing ties: the batch is fully ordered
        // and every label is unique. Refusing here would turn a workable disambiguation into a
        // failed fix for no safety gain.
        List<MarkerMatch> matches = new ArrayList<>(List.of(
            new MarkerMatch(null, "check-a", null, null, "object level"), //$NON-NLS-1$ //$NON-NLS-2$
            new MarkerMatch(null, "check-a", "CommonModules/A/Module.bsl", 10, "first"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new MarkerMatch(null, "check-a", "CommonModules/B/Module.bsl", 3, "second"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        matches.sort(MarkerMatch.DETERMINISTIC_ORDER);

        assertFalse(ApplyQuickFixTool.hasUnresolvableAmbiguity(matches));
    }

    @Test
    public void testTiedBslMarkersAtTheSamePositionAreNotRefused()
    {
        // Two markers tying at the same module:line with the same check and message are the same
        // choice presented twice - the label is identical either way, so picking either is
        // equivalent and there is nothing unsafe to refuse.
        List<MarkerMatch> matches = new ArrayList<>(List.of(
            new MarkerMatch(null, "check-a", "CommonModules/A/Module.bsl", 10, "dup"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new MarkerMatch(null, "check-a", "CommonModules/A/Module.bsl", 10, "dup"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(ApplyQuickFixTool.hasUnresolvableAmbiguity(matches));
    }

    @Test
    public void testSingleMatchIsNeverAnUnresolvableAmbiguity()
    {
        List<MarkerMatch> matches = new ArrayList<>(List.of(
            new MarkerMatch(null, "check-a", null, null, "only one"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(ApplyQuickFixTool.hasUnresolvableAmbiguity(matches));
    }

    @Test
    public void testASuccessfulSourceFixIsNeverRefusedOverSomebodyElsesMetadataExport()
    {
        // The false-refusal guard (#406). A quick fix rewrites BSL source and queues no .mdo
        // export, so it has nothing of its own to wait for. If it inherited the default scope, a
        // SUCCESSFUL source edit could be turned into a 60s "export not confirmed" error because
        // an unrelated metadata export was still draining in the same project - a refusal on a
        // healthy input, which is the most expensive mistake this barrier can make.
        JsonObject applied = new JsonObject();
        applied.addProperty("success", true); //$NON-NLS-1$
        applied.addProperty("action", "applied"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a source fix must await no export at all", //$NON-NLS-1$
            new ApplyQuickFixTool()
                .exportProjectsToAwait(Collections.singletonMap("projectName", "TestConfiguration"), //$NON-NLS-1$ //$NON-NLS-2$
                    applied)
                .isEmpty());
    }
}
