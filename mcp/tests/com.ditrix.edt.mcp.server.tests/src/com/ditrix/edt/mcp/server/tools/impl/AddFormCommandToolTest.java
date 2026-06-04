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
 * Lightweight tests for {@link AddFormCommandTool} that exercise tool metadata,
 * JSON schema and the (shared) identifier validation without needing the
 * Eclipse/EDT runtime. The {@code execute()} path requires a live workbench (it
 * marshals onto the SWT UI thread), a BM model and a managed form, so the model
 * write, the required-arg / duplicate / not-found branches and the on-disk
 * persistence are covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the inheritance from
 * {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must report JSON. Mirrors {@code AddFormAttributeToolTest} (the proven sibling).
 */
public class AddFormCommandToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_form_command", new AddFormCommandTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddFormCommandTool.NAME, new AddFormCommandTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddFormCommandTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddFormCommandTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToFormStructureAndGuide()
    {
        // The always-loaded description must steer to get_form_structure (the form
        // reader) and to the on-demand guide.
        String desc = new AddFormCommandTool().getDescription();
        assertTrue("description should steer to get_form_structure", //$NON-NLS-1$
            desc.contains("get_form_structure")); //$NON-NLS-1$
        assertTrue("description should steer to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddFormCommandTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        // Optional title/language/action.
        assertTrue(schema.contains("\"title\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"action\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddFormCommandTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formPath must be required", tail.contains("\"formPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTitleLanguageAndActionAreOptional()
    {
        String schema = new AddFormCommandTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertFalse("title must NOT be required", tail.contains("\"title\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("action must NOT be required", tail.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissiveSuccessEnvelope()
    {
        String schema = new AddFormCommandTool().getOutputSchema();
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
        // The exhaustive detail (the formCommands target, the action-reservation note,
        // the button-out-of-scope note, bilingual + persistence notes, worked examples)
        // lives in the on-demand guide.
        String guide = new AddFormCommandTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // It must document the reserved action/handler and that binding to a button is
        // out of scope.
        assertTrue("guide should document the reserved action/handler", //$NON-NLS-1$
            guide.toLowerCase().contains("action")); //$NON-NLS-1$
        assertTrue("guide should say binding to a button is out of scope", //$NON-NLS-1$
            guide.toLowerCase().contains("button")); //$NON-NLS-1$
        // The bilingual title-by-code rule must be documented.
        assertTrue("guide should document the language CODE keying", //$NON-NLS-1$
            guide.contains("language CODE") || guide.contains("language code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidIdentifierAccepted()
    {
        // The command name is validated by the shared AddFormAttributeTool.isValidIdentifier.
        assertTrue(AddFormAttributeTool.isValidIdentifier("Refresh")); //$NON-NLS-1$
        assertTrue(AddFormAttributeTool.isValidIdentifier("_hidden")); //$NON-NLS-1$
        assertTrue(AddFormAttributeTool.isValidIdentifier("Cmd_1")); //$NON-NLS-1$
        // Cyrillic letters are valid 1C identifier characters. The literal below uses
        // unicode escapes per repo convention (rule 7) and spells the Russian word for
        // "command" (Komanda).
        assertTrue(AddFormAttributeTool.isValidIdentifier(
            "\u041a\u043e\u043c\u0430\u043d\u0434\u0430")); //$NON-NLS-1$
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
