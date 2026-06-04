/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link DeleteFormItemTool} that exercise tool metadata,
 * the JSON schema and the pure pre-model-access guards without needing the
 * Eclipse/EDT runtime.
 * <p>
 * The full {@code execute()} path requires a live workbench (it marshals onto the
 * SWT UI thread), a BM model and a managed form, so the CONFIRM-PREVIEW branch (a
 * preview needs the model to walk the item's descendant subtree), the actual
 * delete, the not-found branches and the on-disk persistence are covered by the
 * E2E suite instead. The headless-reachable branches asserted here are the
 * required-arg guards (projectName / formPath / itemId), which run BEFORE any
 * project / model access. They are invoked through {@code executeOnUiThread}
 * directly (same package). The confirm-preview semantics themselves are NOT
 * reachable headlessly (they need the form model), so they are e2e-only.
 * <p>
 * {@link #testResponseType()} also guards the inheritance from
 * {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must report JSON.
 */
public class DeleteFormItemToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_form_item", new DeleteFormItemTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteFormItemTool.NAME, new DeleteFormItemTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new DeleteFormItemTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DeleteFormItemTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToFormStructureAndGuide()
    {
        // The always-loaded description must steer to get_form_structure (the form
        // reader that lists the addressing item ids) and to the on-demand guide, and
        // must flag the two-phase (confirm) workflow since this is destructive.
        String desc = new DeleteFormItemTool().getDescription();
        assertTrue("description should steer to get_form_structure", //$NON-NLS-1$
            desc.contains("get_form_structure")); //$NON-NLS-1$
        assertTrue("description should steer to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide")); //$NON-NLS-1$
        assertTrue("description should mention the confirm workflow", //$NON-NLS-1$
            desc.contains("confirm")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new DeleteFormItemTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formPath must be required", tail.contains("\"formPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemId must be required", tail.contains("\"itemId\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfirmNotRequired()
    {
        String schema = new DeleteFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("confirm must NOT be required (preview is the safe default)", //$NON-NLS-1$
            tail.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaIsPermissiveSuccessEnvelope()
    {
        String schema = new DeleteFormItemTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must describe success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Both branches' fields must be in the union schema.
        assertTrue("outputSchema must echo action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must surface confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue("outputSchema must echo itemId", schema.contains("\"itemId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must surface descendantCount", //$NON-NLS-1$
            schema.contains("\"descendantCount\"")); //$NON-NLS-1$
        assertTrue("outputSchema must echo persisted", schema.contains("\"persisted\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("outputSchema must stay permissive (no additionalProperties:false)", //$NON-NLS-1$
            schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail (the destructive/cascade caveat, the two-phase confirm
        // workflow, itemId addressing via get_form_structure, persistence) lives in the
        // on-demand guide.
        String guide = new DeleteFormItemTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide should explain the confirm-preview two-phase workflow", //$NON-NLS-1$
            guide.contains("confirm") && guide.toLowerCase().contains("preview")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should warn that deleting a container cascades its subtree", //$NON-NLS-1$
            guide.toLowerCase().contains("subtree")); //$NON-NLS-1$
        assertTrue("guide should explain itemId addressing via get_form_structure", //$NON-NLS-1$
            guide.contains("itemId") && guide.contains("get_form_structure")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === pre-model-access guards (headless-reachable via executeOnUiThread) ===

    private static Map<String, String> params(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void testMissingProjectNameIsError()
    {
        // projectName omitted -> the FIRST required guard fires before any model access.
        // No confirm, so this is a preview call shape, but the guard short-circuits first.
        String out = new DeleteFormItemTool().executeOnUiThread(
            params("formPath", "CommonForm.Form", "itemId", "OK")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("missing projectName must error", out.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || out.contains("\"success\": false")); //$NON-NLS-1$
        assertTrue("error must name projectName", out.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingFormPathIsError()
    {
        // formPath omitted (projectName present) -> the SECOND required guard fires.
        String out = new DeleteFormItemTool().executeOnUiThread(
            params("projectName", "P", "itemId", "OK")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("error must name formPath", out.contains("formPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingItemIdIsError()
    {
        // itemId omitted (projectName + formPath present) -> the THIRD required guard.
        String out = new DeleteFormItemTool().executeOnUiThread(
            params("projectName", "P", "formPath", "CommonForm.Form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("error must name itemId", out.contains("itemId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
        // The itemId guard must steer the caller to get_form_structure to list ids.
        assertTrue("itemId guard should steer to get_form_structure", //$NON-NLS-1$
            out.contains("get_form_structure")); //$NON-NLS-1$
    }
}
