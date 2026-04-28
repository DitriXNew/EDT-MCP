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
 * Lightweight tests for {@link TranslateConfigurationTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse runtime.
 */
public class TranslateConfigurationToolTest
{
    @Test
    public void testName()
    {
        assertEquals("translate_configuration", new TranslateConfigurationTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new TranslateConfigurationTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new TranslateConfigurationTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new TranslateConfigurationTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"targetLanguages\"")); //$NON-NLS-1$
    }

    @Test
    public void testBothParamsRequired()
    {
        String schema = new TranslateConfigurationTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("targetLanguages must be required", tail.contains("\"targetLanguages\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
