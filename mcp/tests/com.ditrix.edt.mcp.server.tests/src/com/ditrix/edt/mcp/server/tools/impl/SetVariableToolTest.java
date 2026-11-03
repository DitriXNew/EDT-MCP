/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.google.gson.JsonParser;

/**
 * Tests for {@link SetVariableTool}.
 * <p>
 * Covers tool metadata, the input schema, and the early-validation branches that
 * are reachable headlessly (without a live suspended debug session): the required
 * {@code variableName}/{@code value} guards, and the shared frame resolver's
 * "stale frameRef" branch — with a positive {@code frameRef} but no suspended
 * session the in-memory {@code DebugSessionRegistry} returns a null frame, so the
 * tool reports the stale reference before any live {@code DebugPlugin} access. The
 * actual value modification (supportsValueModification / verifyValue / setValue)
 * needs a real suspended frame and is covered by the E2E suite.
 */
public class SetVariableToolTest
{
    @Test
    public void testSetFailurePrefersStatusExceptionMessage() throws Exception
    {
        IStatus status = new Status(IStatus.ERROR, "test", "Generic debug failure",
            new IllegalStateException("Variable expired; call wait_for_break again."));
        assertEquals("Variable expired; call wait_for_break again.", setFailure(status));
    }

    @Test
    public void testSetFailureFallsBackToStatusMessage() throws Exception
    {
        assertEquals("Refresh the suspended frame and retry.",
            setFailure(new Status(IStatus.ERROR, "test", "Refresh the suspended frame and retry.")));
    }

    @Test
    public void testSetFailureUsesStatusChildren() throws Exception
    {
        MultiStatus status = new MultiStatus("test", 0, "Generic debug failure", null);
        status.add(new Status(IStatus.ERROR, "test", "Variable expired; call wait_for_break again."));
        assertEquals("Variable expired; call wait_for_break again.", setFailure(status));
    }

    @Test
    public void testTextlessSetFailureNamesTypeAndSeverity() throws Exception
    {
        String message = setFailure(new Status(IStatus.ERROR, "test", ""));
        assertTrue(message, message.contains("DebugException"));
        assertTrue(message, message.contains("ERROR"));
    }

    private String setFailure(IStatus status) throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IStackFrame frame = mock(IStackFrame.class);
        IVariable variable = mock(IVariable.class);
        when(frame.getVariables()).thenReturn(new IVariable[] {variable});
        when(variable.getName()).thenReturn("Counter");
        when(variable.supportsValueModification()).thenReturn(true);
        when(variable.verifyValue("42")).thenReturn(true);
        doThrow(new DebugException(status)).when(variable).setValue("42");
        registry.clear();
        try
        {
            long frameRef = registry.registerFrame(frame, "set-variable-test");
            String result = new SetVariableTool().execute(Map.of("frameRef", Long.toString(frameRef),
                "variableName", "Counter", "value", "42"));
            verify(variable).setValue("42");
            return JsonParser.parseString(result).getAsJsonObject().get("error").getAsString();
        }
        finally
        {
            registry.clear();
        }
    }

    @Test
    public void testName()
    {
        assertEquals("set_variable", new SetVariableTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetVariableTool.NAME, new SetVariableTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetVariableTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetVariableTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SetVariableTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"frameRef\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"frameIndex\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"variableName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"value\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingVariableNameIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("value", "42"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("variableName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingValueIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("variableName", "\u041c\u043e\u044f\u041f\u0435\u0440\u0435\u043c\u0435\u043d\u043d\u0430\u044f"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("value is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaleFrameRefWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("variableName", "\u041c\u043e\u044f\u041f\u0435\u0440\u0435\u043c\u0435\u043d\u043d\u0430\u044f"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("value", "42"); //$NON-NLS-1$ //$NON-NLS-2$
        // frameRef > 0 but no suspended session: the shared DebugFrameResolver reads a
        // null frame from the in-memory registry and reports a stale frameRef before
        // any live access.
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("stale frameRef")); //$NON-NLS-1$
    }
}
