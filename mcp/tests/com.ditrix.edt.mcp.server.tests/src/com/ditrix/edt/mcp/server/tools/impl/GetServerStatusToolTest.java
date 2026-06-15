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

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    @Test
    public void testDescriptionNamesKeyDiagnostics()
    {
        // The description is the tool's self-advertised diagnostic vocabulary; pin the
        // form-render flags and the never-leaks contract it promises.
        String desc = new GetServerStatusTool().getDescription();
        assertTrue(desc.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertTrue(desc.contains("nativeFormLayoutRender")); //$NON-NLS-1$
        assertTrue(desc.toLowerCase().contains("plaintextmode")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresAllFields()
    {
        String schema = new GetServerStatusTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"success\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"port\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"running\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"protocolVersion\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"pluginVersion\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"edtVersion\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"enabledTools\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"totalTools\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"plainTextMode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"checksFolderConfigured\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"authEnabled\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formRenderFlags\"")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteIsWellFormedJsonObject()
    {
        // The snapshot is delivered as a JSON object; parse it to prove it is not a
        // truncated/plain string and that the documented numeric/object fields are present.
        String json = new GetServerStatusTool().execute(java.util.Collections.emptyMap());
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("success must be true", obj.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("port must be a number", obj.get("port").getAsJsonPrimitive().isNumber()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("formRenderFlags must be an object", //$NON-NLS-1$
            obj.get("formRenderFlags").isJsonObject()); //$NON-NLS-1$
    }

    @Test
    public void testHeadlessPortAndRunningDegradeToDefaults()
    {
        // With no live McpServer the status still serialises: a numeric port field
        // and running=false (the server is not up in the headless test runtime).
        String json = new GetServerStatusTool().execute(java.util.Collections.emptyMap());
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("port must be a non-negative int", obj.get("port").getAsInt() >= 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no live server => running must be false", obj.get("running").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormRenderFlagsReflectSystemProperties()
    {
        // The two form-render flags are read from System properties via Boolean.parseBoolean:
        // set => true, absent => false. Restore the prior values to keep the test isolated.
        String bufferedKey = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$
        String nativeKey = "nativeFormLayoutRender"; //$NON-NLS-1$
        String savedBuffered = System.getProperty(bufferedKey);
        String savedNative = System.getProperty(nativeKey);
        try
        {
            System.setProperty(bufferedKey, "true"); //$NON-NLS-1$
            System.clearProperty(nativeKey);

            JsonObject flags = JsonParser
                .parseString(new GetServerStatusTool().execute(java.util.Collections.emptyMap()))
                .getAsJsonObject().getAsJsonObject("formRenderFlags"); //$NON-NLS-1$
            assertTrue("buffered flag set => true", flags.get(bufferedKey).getAsBoolean()); //$NON-NLS-1$
            assertFalse("native flag absent => false", flags.get(nativeKey).getAsBoolean()); //$NON-NLS-1$
        }
        finally
        {
            restoreProperty(bufferedKey, savedBuffered);
            restoreProperty(nativeKey, savedNative);
        }
    }

    private static void restoreProperty(String key, String saved)
    {
        if (saved == null)
        {
            System.clearProperty(key);
        }
        else
        {
            System.setProperty(key, saved);
        }
    }
}
