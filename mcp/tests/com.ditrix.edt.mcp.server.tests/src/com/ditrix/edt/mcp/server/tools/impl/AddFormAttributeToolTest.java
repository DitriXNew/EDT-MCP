/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link AddFormAttributeTool} that exercise tool metadata,
 * JSON schema and the pure identifier validation without needing the Eclipse/EDT
 * runtime. The {@code execute()} path requires a live workbench (it marshals onto
 * the SWT UI thread), a BM model and a managed form, so the model write, the
 * required-arg / duplicate / not-found branches and the on-disk persistence are
 * covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the inheritance from
 * {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must report JSON.
 */
public class AddFormAttributeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_form_attribute", new AddFormAttributeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddFormAttributeTool.NAME, new AddFormAttributeTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddFormAttributeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddFormAttributeTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToFormStructureAndGuide()
    {
        // The always-loaded description must steer to get_form_structure (the form
        // reader) and to the on-demand guide.
        String desc = new AddFormAttributeTool().getDescription();
        assertTrue("description should steer to get_form_structure", //$NON-NLS-1$
            desc.contains("get_form_structure")); //$NON-NLS-1$
        assertTrue("description should steer to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddFormAttributeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        // Optional type/synonym/language.
        assertTrue(schema.contains("\"type\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"synonym\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddFormAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formPath must be required", tail.contains("\"formPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTypeSynonymAndLanguageAreOptional()
    {
        String schema = new AddFormAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertFalse("type must NOT be required", tail.contains("\"type\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("synonym must NOT be required", tail.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissiveSuccessEnvelope()
    {
        String schema = new AddFormAttributeTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must describe success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo persisted", schema.contains("\"persisted\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must echo name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("outputSchema must stay permissive (no additionalProperties:false)", //$NON-NLS-1$
            schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail (form-vs-object distinction, the type-reservation note,
        // bilingual + persistence notes, worked examples) lives in the on-demand guide.
        String guide = new AddFormAttributeTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // It must distinguish the form attribute from the object attribute and steer to
        // add_metadata_attribute for the latter.
        assertTrue("guide should contrast with add_metadata_attribute", //$NON-NLS-1$
            guide.contains("add_metadata_attribute")); //$NON-NLS-1$
        // The default-type reservation note must be present (type is not yet wired).
        assertTrue("guide should explain the default-type reservation", //$NON-NLS-1$
            guide.toLowerCase().contains("default")); //$NON-NLS-1$
        // The bilingual title-by-code rule must be documented.
        assertTrue("guide should document the language CODE keying", //$NON-NLS-1$
            guide.contains("language CODE") || guide.contains("language code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidIdentifierAccepted()
    {
        assertTrue(AddFormAttributeTool.isValidIdentifier("Total")); //$NON-NLS-1$
        assertTrue(AddFormAttributeTool.isValidIdentifier("_hidden")); //$NON-NLS-1$
        assertTrue(AddFormAttributeTool.isValidIdentifier("Attr_1")); //$NON-NLS-1$
        // Cyrillic letters are valid 1C identifier characters. The literal below uses
        // unicode escapes per repo convention (rule 7) and spells the Russian word for
        // "attribute" (Rekvizit).
        assertTrue(AddFormAttributeTool.isValidIdentifier(
            "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidIdentifierRejected()
    {
        assertFalse("name starting with a digit must be rejected", //$NON-NLS-1$
            AddFormAttributeTool.isValidIdentifier("1Bad")); //$NON-NLS-1$
        assertFalse("name with a space must be rejected", //$NON-NLS-1$
            AddFormAttributeTool.isValidIdentifier("has space")); //$NON-NLS-1$
        assertFalse("empty name must be rejected", //$NON-NLS-1$
            AddFormAttributeTool.isValidIdentifier("")); //$NON-NLS-1$
        assertFalse("null name must be rejected", //$NON-NLS-1$
            AddFormAttributeTool.isValidIdentifier(null));
        assertFalse("name with punctuation must be rejected", //$NON-NLS-1$
            AddFormAttributeTool.isValidIdentifier("Bad-Name")); //$NON-NLS-1$
    }
}
