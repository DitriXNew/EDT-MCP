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
 * Lightweight tests for {@link SetFormItemPropertyTool} that exercise tool
 * metadata, the JSON schema and the pure pre-model-access guards without needing
 * the Eclipse/EDT runtime.
 * <p>
 * The full {@code execute()} path requires a live workbench (it marshals onto the
 * SWT UI thread), a BM model and a managed form, so the model write, the
 * not-found / readOnly-not-settable branches and the on-disk persistence are
 * covered by the E2E suite instead. The headless-reachable branches asserted here
 * are: the required-arg guards (projectName / formPath / itemId) and the
 * "at least one of title / visible / readOnly" guard, all of which run BEFORE any
 * project / model access. They are invoked through {@code executeOnUiThread}
 * directly (same package), which is what the at-least-one guard short-circuits on.
 * <p>
 * {@link #testResponseType()} also guards the inheritance from
 * {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must report JSON.
 */
public class SetFormItemPropertyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_form_item_property", new SetFormItemPropertyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetFormItemPropertyTool.NAME, new SetFormItemPropertyTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new SetFormItemPropertyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetFormItemPropertyTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToFormStructureAndGuide()
    {
        // The always-loaded description must steer to get_form_structure (the form
        // reader that lists the addressing item ids) and to the on-demand guide.
        String desc = new SetFormItemPropertyTool().getDescription();
        assertTrue("description should steer to get_form_structure", //$NON-NLS-1$
            desc.contains("get_form_structure")); //$NON-NLS-1$
        assertTrue("description should steer to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new SetFormItemPropertyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemId\"")); //$NON-NLS-1$
        // Optional title/language/visible/readOnly.
        assertTrue(schema.contains("\"title\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"visible\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"readOnly\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new SetFormItemPropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formPath must be required", tail.contains("\"formPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemId must be required", tail.contains("\"itemId\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalPropertiesAreNotRequired()
    {
        String schema = new SetFormItemPropertyTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertFalse("title must NOT be required", tail.contains("\"title\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("visible must NOT be required", tail.contains("\"visible\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("readOnly must NOT be required", tail.contains("\"readOnly\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissiveSuccessEnvelope()
    {
        String schema = new SetFormItemPropertyTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must describe success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo persisted", schema.contains("\"persisted\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo itemId", schema.contains("\"itemId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("outputSchema must stay permissive (no additionalProperties:false)", //$NON-NLS-1$
            schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail (scope, itemId addressing, the bilingual title rule,
        // the readOnly-not-on-every-type note, persistence, worked examples) lives in
        // the on-demand guide.
        String guide = new SetFormItemPropertyTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // It must explain itemId addressing via get_form_structure.
        assertTrue("guide should explain itemId addressing", //$NON-NLS-1$
            guide.contains("itemId") && guide.contains("get_form_structure")); //$NON-NLS-1$ //$NON-NLS-2$
        // The bilingual title-by-code rule must be documented.
        assertTrue("guide should document the language CODE keying", //$NON-NLS-1$
            guide.contains("language CODE") || guide.contains("language code")); //$NON-NLS-1$ //$NON-NLS-2$
        // The readOnly-not-on-every-item-type caveat must be present.
        assertTrue("guide should note readOnly is not on every item type", //$NON-NLS-1$
            guide.toLowerCase().contains("readonly")); //$NON-NLS-1$
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
        String out = new SetFormItemPropertyTool().executeOnUiThread(
            params("formPath", "CommonForm.Form", "itemId", "OK", "title", "X")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("missing projectName must error", out.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || out.contains("\"success\": false")); //$NON-NLS-1$
        assertTrue("error must name projectName", out.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingFormPathIsError()
    {
        // formPath omitted (projectName present) -> the SECOND required guard fires.
        String out = new SetFormItemPropertyTool().executeOnUiThread(
            params("projectName", "P", "itemId", "OK", "title", "X")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("error must name formPath", out.contains("formPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingItemIdIsError()
    {
        // itemId omitted (projectName + formPath present) -> the THIRD required guard.
        String out = new SetFormItemPropertyTool().executeOnUiThread(
            params("projectName", "P", "formPath", "CommonForm.Form", "title", "X")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("error must name itemId", out.contains("itemId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must say it is required", out.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoEditablePropertyIsError()
    {
        // All three required args present, but NONE of title / visible / readOnly is
        // supplied -> the at-least-one guard fires BEFORE any project / model access,
        // and the message must name the three options so the caller knows what to add.
        String out = new SetFormItemPropertyTool().executeOnUiThread(
            params("projectName", "P", "formPath", "CommonForm.Form", "itemId", "OK")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("no-property call must error", out.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || out.contains("\"success\": false")); //$NON-NLS-1$
        assertTrue("error must mention title", out.contains("title")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention visible", out.contains("visible")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention readOnly", out.contains("readOnly")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyTitleAloneIsTreatedAsNotProvided()
    {
        // An EMPTY title is "not provided" (not a clear), so with no visible/readOnly
        // either the at-least-one guard must STILL fire (i.e. an empty title does not
        // satisfy "at least one property"). This pins the empty-string semantics.
        String out = new SetFormItemPropertyTool().executeOnUiThread(
            params("projectName", "P", "formPath", "CommonForm.Form", "itemId", "OK", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "title", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty title must not satisfy the at-least-one guard", //$NON-NLS-1$
            out.contains("\"success\":false") || out.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention the property options", out.contains("title")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
