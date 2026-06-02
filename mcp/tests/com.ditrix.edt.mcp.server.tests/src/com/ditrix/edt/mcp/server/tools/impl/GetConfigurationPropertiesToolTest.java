/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetConfigurationPropertiesTool}.
 * <p>
 * {@code projectName} is optional (when absent the tool falls back to the first
 * configuration project), so there is no argument-validation branch that returns
 * before live access — {@code execute()} goes straight to the UI thread / DT
 * project manager. The headless surface is therefore the static contract (name,
 * response type, schema); the properties payload is covered by the E2E suite.
 */
public class GetConfigurationPropertiesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_configuration_properties", new GetConfigurationPropertiesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetConfigurationPropertiesTool.NAME, new GetConfigurationPropertiesTool().getName());
    }

    @Test
    public void testResponseTypeYaml()
    {
        // The tool emits a human-readable YAML body, so the response type is YAML
        // (the protocol maps it to a text/yaml resource that agrees with the .yaml
        // file name). Errors still travel as structured JSON via the protocol
        // diversion. A MARKDOWN type would mis-advertise the body as text/markdown.
        assertEquals(ResponseType.YAML, new GetConfigurationPropertiesTool().getResponseType());
    }

    @Test
    public void testResultFileNameIsYaml()
    {
        assertEquals("configuration-properties.yaml", //$NON-NLS-1$
            new GetConfigurationPropertiesTool().getResultFileName(new java.util.HashMap<>()));
    }

    @Test
    public void testResponseTypeAndFileNameAgreeOnYaml()
    {
        // The three metadata pieces must agree: a YAML response type, a .yaml
        // resource URI, and (via the protocol) a YAML mimeType.
        GetConfigurationPropertiesTool tool = new GetConfigurationPropertiesTool();
        assertEquals(ResponseType.YAML, tool.getResponseType());
        assertTrue(tool.getResultFileName(new java.util.HashMap<>()).endsWith(".yaml")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetConfigurationPropertiesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetConfigurationPropertiesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }
}
