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
 * Tests for {@link GetVariablesTool}.
 * <p>
 * This tool has no argument-validation branch that returns before touching the
 * live {@code DebugSessionRegistry}: every error path is gated behind a frame/
 * thread/snapshot lookup that needs a suspended debug session. So the headless
 * surface is limited to the static contract (name, response type, schema); the
 * error and success paths are covered by the E2E suite.
 */
public class GetVariablesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_variables", new GetVariablesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetVariablesTool.NAME, new GetVariablesTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetVariablesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetVariablesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetVariablesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"frameRef\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"frameIndex\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expandPath\"")); //$NON-NLS-1$
    }
}
