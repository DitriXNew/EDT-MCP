/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/**
 * Lightweight contract tests for {@link ApplyQuickFixTool}: tool metadata and JSON schema,
 * without the Eclipse/EDT runtime. The actual fix behaviour (resolve marker by id -&gt; prepare
 * -&gt; variants -&gt; execute) needs a live workbench + marker manager + IFixManager, so it is
 * covered by the E2E suite (test_apply_quick_fix.py).
 */
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
}
