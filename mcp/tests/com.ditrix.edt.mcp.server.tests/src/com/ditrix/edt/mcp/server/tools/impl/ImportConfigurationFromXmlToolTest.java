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
 * Lightweight tests for {@link ImportConfigurationFromXmlTool} that exercise
 * tool metadata and JSON schema without needing the Eclipse runtime.
 */
public class ImportConfigurationFromXmlToolTest
{
    @Test
    public void testName()
    {
        assertEquals("import_configuration_from_xml", new ImportConfigurationFromXmlTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ImportConfigurationFromXmlTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ImportConfigurationFromXmlTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"importPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectNature\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"xmlVersion\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("importPath must be required", tail.contains("\"importPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("projectNature must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectNature\",") || tail.contains(",\"projectNature\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("xmlVersion must NOT be required", //$NON-NLS-1$
            tail.contains("\"xmlVersion\",") || tail.contains(",\"xmlVersion\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
