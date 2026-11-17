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
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.junit.Test;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetVariablesTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * "stale" branches: with a positive {@code frameRef} (or {@code threadId}) but
 * no suspended session, the in-memory {@code DebugSessionRegistry} returns a
 * null frame/thread via a plain map lookup, so the tool reports the stale
 * reference before any live {@code DebugPlugin} access. The auto-resolution
 * fallback (neither frameRef nor threadId) goes through the live launch manager and
 * is covered by the E2E suite. Stubbed frames cover pending and settled variable reads.
 */
public class GetVariablesToolTest
{
    @Test
    public void waitsForPendingEvaluationThenSerializesRealVariables() throws Exception
    {
        var frame = mock(IStackFrame.class);
        var pending = mock(IBslVariable.class);
        when(pending.isEvaluated()).thenReturn(false);
        var real = mock(IBslVariable.class);
        when(real.isEvaluated()).thenReturn(true);
        when(real.getName()).thenReturn("Answer");
        when(frame.getVariables()).thenReturn(
            new IVariable[] {pending},
            new IVariable[] {real});
        String result = GetVariablesTool.serializeVariables(
            DebugSessionRegistry.get(), frame, null, 1_000);
        var json = JsonParser.parseString(result).getAsJsonObject();
        assertEquals(1, json.get("count").getAsInt());
        assertEquals("Answer", json.getAsJsonArray("variables").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(json.has("pending"));
        verify(frame, times(2)).getVariables();
        verify(pending, never()).getName();
    }

    @Test
    public void stillPendingReturnsRetryHintInsteadOfFakeVariableEvenForExpandPath() throws Exception
    {
        var frame = mock(IStackFrame.class);
        var pending = mock(IBslVariable.class);
        when(pending.isEvaluated()).thenReturn(false);
        when(frame.getVariables()).thenReturn(new IVariable[] {pending});
        for (String path : new String[] {null, "Answer.Child"})
        {
            String result = GetVariablesTool.serializeVariables(
                DebugSessionRegistry.get(), frame, path, 30);
            var json = JsonParser.parseString(result).getAsJsonObject();
            assertTrue(json.get("pending").getAsBoolean());
            assertEquals(0, json.getAsJsonArray("variables").size());
            assertEquals(0, json.get("count").getAsInt());
            assertTrue(json.get("message").getAsString().contains("Retry get_variables"));
        }
        verify(frame, atLeast(2)).getVariables();
        verify(pending, never()).getName();
    }

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

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testStaleFrameRefWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        // frameRef > 0 but no suspended session: the in-memory registry returns a
        // null frame, so the tool reports a stale frameRef before any live access.
        String result = new GetVariablesTool().execute(params);
        assertTrue(result.contains("stale frameRef")); //$NON-NLS-1$
    }

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        // threadId > 0 (no frameRef): the in-memory registry returns a null thread,
        // so the tool reports a stale threadId before any live access.
        String result = new GetVariablesTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }
}
