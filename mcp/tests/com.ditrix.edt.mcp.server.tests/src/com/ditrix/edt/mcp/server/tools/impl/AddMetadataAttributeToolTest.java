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
 * metadata and JSON schema without needing the Eclipse/EDT runtime. The
 * {@code execute()} path requires a live workbench and BM model, so it is
 * covered by the E2E suite instead.
 * <p>
 * {@link #testResponseType()} also guards the refactoring that moved
 * {@code getResponseType()} into {@link AbstractMetadataWriteTool}: the tool
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
        assertTrue(schema.contains("\"parentFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"attributeName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AddMetadataAttributeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("parentFqn must be required", tail.contains("\"parentFqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("attributeName must be required", tail.contains("\"attributeName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
