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
 * Lightweight tests for {@link AddMetadataAttributeTool} that exercise tool
 * metadata, JSON schema and the pure identifier validation without needing the
 * Eclipse/EDT runtime. The {@code execute()} path requires a live workbench and
 * BM model, so it is covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the refactoring that moved
 * {@code getResponseType()} into {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool}: the tool
 * must still report JSON.
 */
public class AddMetadataAttributeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("add_metadata_attribute", new AddMetadataAttributeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(AddMetadataAttributeTool.NAME, new AddMetadataAttributeTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AddMetadataAttributeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new AddMetadataAttributeTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        // Canonical 'objectFqn' (aligned with find_references/delete/rename) plus the
        // back-compat 'parentFqn' alias — both declared (and both read by execute()).
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"parentFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
        // Optional synonym/language for writing a localized display name.
        assertTrue(schema.contains("\"synonym\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
        // Optional stale-intent guard.
        assertTrue(schema.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testSynonymAndLanguageAreOptional()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertFalse("synonym must NOT be required", tail.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // The stale-intent guard is opt-in: declared but never required.
        assertFalse("expectedNotExists must NOT be required", //$NON-NLS-1$
            tail.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("attributeName must be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // objectFqn / parentFqn are NOT individually required: exactly one of them is
        // required at runtime (the JSON schema cannot express that XOR), so neither
        // appears in the required[] array.
        assertFalse("objectFqn must NOT be in required (runtime XOR with parentFqn)", //$NON-NLS-1$
            tail.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertFalse("parentFqn must NOT be in required (alias of objectFqn)", //$NON-NLS-1$
            tail.contains("\"parentFqn\"")); //$NON-NLS-1$
    }

    @Test
    public void testObjectFqnIsCanonicalWithParentFqnAlias()
    {
        // The schema must declare both the canonical objectFqn and its back-compat
        // alias parentFqn; the SchemaExecuteParamParityTest separately enforces that
        // both are read by execute().
        String schema = new AddMetadataAttributeTool().getInputSchema();
        assertTrue("objectFqn must be declared", schema.contains("\"objectFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("parentFqn alias must be declared", schema.contains("\"parentFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail (supported types, the stale-intent guard, worked
        // examples) moved OUT of getDescription()/getInputSchema() and INTO the
        // on-demand guide. Assert it landed there rather than vanishing.
        String guide = new AddMetadataAttributeTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // A supported type that no longer appears in the slimmed description.
        assertTrue("guide should list supported parent types", //$NON-NLS-1$
            guide.contains("AccumulationRegister")); //$NON-NLS-1$
        // The full stale-intent-guard explanation migrated here.
        assertTrue("guide should explain expectedNotExists", //$NON-NLS-1$
            guide.contains("expectedNotExists")); //$NON-NLS-1$
    }

    @Test
    public void testValidIdentifierAccepted()
    {
        assertTrue(AddMetadataAttributeTool.isValidIdentifier("Weight")); //$NON-NLS-1$
        assertTrue(AddMetadataAttributeTool.isValidIdentifier("_private")); //$NON-NLS-1$
        assertTrue(AddMetadataAttributeTool.isValidIdentifier("Attr_1")); //$NON-NLS-1$
        // Cyrillic letters are valid 1C identifier characters. The literal below
        // uses unicode escapes per repo convention (rule 7) and spells the
        // Russian word for "attribute" (Rekvizit).
        assertTrue(AddMetadataAttributeTool.isValidIdentifier(
            "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidIdentifierRejected()
    {
        assertFalse("name starting with a digit must be rejected", //$NON-NLS-1$
            AddMetadataAttributeTool.isValidIdentifier("1Bad")); //$NON-NLS-1$
        assertFalse("name with a space must be rejected", //$NON-NLS-1$
            AddMetadataAttributeTool.isValidIdentifier("has space")); //$NON-NLS-1$
        assertFalse("empty name must be rejected", //$NON-NLS-1$
            AddMetadataAttributeTool.isValidIdentifier("")); //$NON-NLS-1$
        assertFalse("null name must be rejected", //$NON-NLS-1$
            AddMetadataAttributeTool.isValidIdentifier(null));
        assertFalse("name with punctuation must be rejected", //$NON-NLS-1$
            AddMetadataAttributeTool.isValidIdentifier("Bad-Name")); //$NON-NLS-1$
    }
}
