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
 * Lightweight tests for {@link AddFormItemTool} that exercise tool metadata, the JSON
 * schema (including the {@code itemType} enum and the {@code required[]} set), the guide,
 * and the headless-reachable validation branches (missing required args, the bad-itemType
 * enum, and the reserved field/button rejection) WITHOUT needing the Eclipse/EDT runtime.
 * <p>
 * The {@code execute()} path requires a live workbench (it marshals onto the SWT UI
 * thread), a BM model and a managed form, so the model write (group/decoration creation,
 * parentId nesting, the not-a-container rejection, duplicate-name, not-found) and the
 * on-disk persistence are covered by the E2E suite instead. Only branches that fire BEFORE
 * any model access are asserted here. Mirrors {@code AddFormCommandToolTest} (the proven
 * sibling).
 * <p>
 * NOTE: the missing-required, invalid-identifier, bad-enum and reserved-itemType branches
 * are asserted by calling {@code executeOnUiThread(params)} DIRECTLY (not the
 * {@code execute()} wrapper, which marshals onto the SWT UI thread). Each of these branches
 * returns a {@link com.ditrix.edt.mcp.server.protocol.ToolResult} error BEFORE
 * {@code resolveProjectAndConfig} / any BM-model access, so no workbench is required - which
 * is exactly the contract this test pins (validation happens before the model is touched).
 */
public class AddFormItemToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_form_item", new AddFormItemTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddFormItemTool.NAME, new AddFormItemTool().getName());
    }

    @Test
    public void testResponseType()
    {
        // Inherited from AbstractMetadataWriteTool - must be JSON.
        assertEquals(ResponseType.JSON, new AddFormItemTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddFormItemTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToFormStructureAndGuide()
    {
        // The always-loaded description must steer to get_form_structure (the form reader)
        // and to the on-demand guide.
        String desc = new AddFormItemTool().getDescription();
        assertTrue("description should steer to get_form_structure", //$NON-NLS-1$
            desc.contains("get_form_structure")); //$NON-NLS-1$
        assertTrue("description should steer to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide")); //$NON-NLS-1$
    }

    // === Schema ===

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddFormItemTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"itemType\"")); //$NON-NLS-1$
        // Optional parentId/title/language + the reserved attributeName/commandName.
        assertTrue(schema.contains("\"parentId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"title\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"commandName\"")); //$NON-NLS-1$
    }

    @Test
    public void testItemTypeIsAClosedEnum()
    {
        // itemType must be a JSON Schema enum listing all four accepted values (group +
        // decoration supported; field + button reserved but still declared so the schema
        // documents them and the bad-enum branch can echo the accepted set).
        String schema = new AddFormItemTool().getInputSchema();
        assertTrue("itemType must be an enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enum must list group", schema.contains("\"group\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enum must list decoration", schema.contains("\"decoration\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enum must list field", schema.contains("\"field\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enum must list button", schema.contains("\"button\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formPath must be required", tail.contains("\"formPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("itemType must be required", tail.contains("\"itemType\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersAreNotRequired()
    {
        String schema = new AddFormItemTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertFalse("parentId must NOT be required", tail.contains("\"parentId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("title must NOT be required", tail.contains("\"title\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("attributeName must NOT be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("commandName must NOT be required", tail.contains("\"commandName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissiveSuccessEnvelope()
    {
        String schema = new AddFormItemTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must describe success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo persisted", schema.contains("\"persisted\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo itemType", schema.contains("\"itemType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo parentId", schema.contains("\"parentId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("outputSchema must stay permissive (no additionalProperties:false)", //$NON-NLS-1$
            schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
    }

    // === Guide ===

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        String guide = new AddFormItemTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // Implemented itemTypes are documented.
        assertTrue("guide should document the group itemType", //$NON-NLS-1$
            guide.contains("group")); //$NON-NLS-1$
        assertTrue("guide should document the decoration itemType", //$NON-NLS-1$
            guide.contains("decoration")); //$NON-NLS-1$
        // Reserved itemTypes are documented as reserved.
        assertTrue("guide should document the reserved field/button", //$NON-NLS-1$
            guide.toLowerCase().contains("reserved")); //$NON-NLS-1$
        // parentId nesting is documented.
        assertTrue("guide should document parentId nesting", //$NON-NLS-1$
            guide.contains("parentId")); //$NON-NLS-1$
        // The bilingual title-by-code rule must be documented.
        assertTrue("guide should document the language CODE keying", //$NON-NLS-1$
            guide.contains("language CODE") || guide.contains("language code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === Pre-model validation branches (reachable headless) ===

    @Test
    public void testMissingProjectNameIsError()
    {
        // requireArgument fires before any model access. The error names the missing param
        // and steers with a usage example. (Gson HTML-escapes apostrophes/'<' in the JSON
        // text, so we match delimiter-free substrings only.)
        String json = new AddFormItemTool().executeOnUiThread(params(
            "formPath", "CommonForm.MyForm", "name", "G1", "itemType", "group")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("should be an error", json.contains("\"isError\"") //$NON-NLS-1$ //$NON-NLS-2$
            || json.contains("error")); //$NON-NLS-1$
        assertTrue("error should name projectName", json.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say required", json.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingFormPathIsError()
    {
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "name", "G1", "itemType", "group")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("error should name formPath", json.contains("formPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say required", json.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingNameIsError()
    {
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "itemType", "group")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("error should name 'name'", json.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say required", json.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingItemTypeIsError()
    {
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "G1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue("error should name itemType", json.contains("itemType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say required", json.contains("required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidIdentifierNameIsError()
    {
        // isValidIdentifier fires before any model access. A name starting with a digit is
        // rejected with the actionable rule text.
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "1bad", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "itemType", "group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should explain the identifier rule", //$NON-NLS-1$
            json.contains("must start with a letter")); //$NON-NLS-1$
    }

    @Test
    public void testBadItemTypeEnumValueIsError()
    {
        // An itemType outside the schema enum is rejected BEFORE any model access; the
        // message echoes the bad value and lists the accepted set.
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "X1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "itemType", "spaceship")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should echo the bad itemType value", json.contains("spaceship")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should list the accepted itemTypes", json.contains("group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should list the accepted itemTypes", json.contains("decoration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testReservedFieldItemTypeIsRejected()
    {
        // A 'field' is a declared enum value but RESERVED: it is rejected BEFORE any model
        // access with a clear message that names the type and steers to the EDT editor.
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "F1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "itemType", "field")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say field is reserved", json.contains("reserved")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should name the field type", json.contains("field")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should steer to the EDT form editor", //$NON-NLS-1$
            json.contains("EDT form editor")); //$NON-NLS-1$
    }

    @Test
    public void testReservedButtonItemTypeIsRejected()
    {
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "B1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "itemType", "button")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should say button is reserved", json.contains("reserved")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error should name the button type", json.contains("button")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testItemTypeEnumIsCaseInsensitiveBeforeReservedCheck()
    {
        // 'FIELD' (mixed/upper case) normalizes to the reserved 'field' and is rejected the
        // same way - the normalization happens before the reserved check, not after.
        String json = new AddFormItemTool().executeOnUiThread(params(
            "projectName", "P", "formPath", "CommonForm.MyForm", "name", "F2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "itemType", "FIELD")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("uppercase FIELD must still be treated as the reserved field type", //$NON-NLS-1$
            json.contains("reserved")); //$NON-NLS-1$
    }

    @Test
    public void testValidIdentifierSharedRule()
    {
        // The item name uses the shared AddFormAttributeTool.isValidIdentifier rule.
        assertTrue(AddFormItemTool.isValidIdentifier("MainGroup")); //$NON-NLS-1$
        assertTrue(AddFormItemTool.isValidIdentifier("_g")); //$NON-NLS-1$
        assertFalse(AddFormItemTool.isValidIdentifier("1bad")); //$NON-NLS-1$
        assertFalse(AddFormItemTool.isValidIdentifier("has space")); //$NON-NLS-1$
        assertFalse(AddFormItemTool.isValidIdentifier(null));
        // Cyrillic letters are valid 1C identifier characters (spells "Gruppa").
        assertTrue(AddFormItemTool.isValidIdentifier(
            "\u0413\u0440\u0443\u043f\u043f\u0430")); //$NON-NLS-1$
    }

    private static Map<String, String> params(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
