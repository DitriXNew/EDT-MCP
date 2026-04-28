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
 * Lightweight tests for {@link GetTranslationProjectInfoTool} that exercise
 * tool metadata and JSON schema without needing the Eclipse runtime.
 */
public class GetTranslationProjectInfoToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_translation_project_info", new GetTranslationProjectInfoTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new GetTranslationProjectInfoTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetTranslationProjectInfoTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsProjectName()
    {
        String schema = new GetTranslationProjectInfoTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameRequired()
    {
        String schema = new GetTranslationProjectInfoTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"projectName\"")); //$NON-NLS-1$
    }
}
