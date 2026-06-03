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
 * Lightweight tests for {@link CreateMetadataObjectTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 */
public class CreateMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_metadata_object", new CreateMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateMetadataObjectTool.NAME, new CreateMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionMentionsSupportedTypes()
    {
        String desc = new CreateMetadataObjectTool().getDescription();
        assertTrue("description should mention Catalog", desc.contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description should mention Document", desc.contains("Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"name\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"synonym\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"comment\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testExpectedNotExistsIsOptional()
    {
        // The stale-intent guard is opt-in: declared but never required, so existing
        // callers that omit it keep the current behavior.
        String schema = new CreateMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("expectedNotExists must not be required", //$NON-NLS-1$
            tail.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("metadataType must be required", tail.contains("\"metadataType\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new CreateMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("synonym must not be required", tail.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("comment must not be required", tail.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must not be required", tail.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
