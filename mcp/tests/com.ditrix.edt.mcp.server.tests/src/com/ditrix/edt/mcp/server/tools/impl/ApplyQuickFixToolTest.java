/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

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
}
