/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetServerStatusTool}.
 * <p>
 * The tool takes no parameters and reads its snapshot from the registry,
 * preferences and system properties. The headless surface is the static
 * contract plus the null-safe {@code execute()}: with no {@link
 * com.ditrix.edt.mcp.server.Activator} and no running server it must still
 * return a well-formed success JSON (degrading port/flags to defaults rather
 * than throwing), and it must never leak a secret. The live values (real port,
 * EDT version, auth flag) are covered by the E2E suite.
 */
public class GetServerStatusToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_server_status", new GetServerStatusTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetServerStatusTool.NAME, new GetServerStatusTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetServerStatusTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetServerStatusTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaIsEmptyObject()
    {
        String schema = new GetServerStatusTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
        // No parameters: the tool reads nothing from params.
        assertFalse(schema.contains("\"properties\":{\"")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteHeadlessReturnsSuccessJson()
    {
        // Activator is null in the unit runtime: execute() must degrade
        // gracefully (defaults, no NPE) and still emit a success payload.
        String json = new GetServerStatusTool().execute(java.util.Collections.emptyMap());
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("protocolVersion")); //$NON-NLS-1$
        assertTrue(json.contains("pluginVersion")); //$NON-NLS-1$
        assertTrue(json.contains("totalTools")); //$NON-NLS-1$
        assertTrue(json.contains("enabledTools")); //$NON-NLS-1$
        assertTrue(json.contains("plainTextMode")); //$NON-NLS-1$
        assertTrue(json.contains("checksFolderConfigured")); //$NON-NLS-1$
        assertTrue(json.contains("authEnabled")); //$NON-NLS-1$
        assertTrue(json.contains("formRenderFlags")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteNeverLeaksChecksFolderPathOrToken()
    {
        // Only booleans are exposed for the secret/path-bearing prefs: the
        // raw preference KEYS (which would be present if the path/token were
        // serialized under their own keys) must not appear.
        String json = new GetServerStatusTool().execute(java.util.Collections.emptyMap());
        assertNotNull(json);
        assertFalse(json.contains("authToken")); //$NON-NLS-1$
        assertFalse(json.contains("checksFolder\"")); //$NON-NLS-1$
    }
}
