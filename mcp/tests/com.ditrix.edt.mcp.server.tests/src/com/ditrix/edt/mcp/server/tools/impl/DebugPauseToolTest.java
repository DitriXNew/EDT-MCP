/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugPause;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DebugPauseTool}: the static contract, the no-session answers reachable headless,
 * and the exact JSON of every pause outcome (outcomes come from {@link DebugPause} over mocks).
 */
public class DebugPauseToolTest
{
    private static final String APP_ID = "launch:PauseToolCfg"; //$NON-NLS-1$

    private final DebugSessionRegistry registry = DebugSessionRegistry.get();

    @After
    public void clearRegistry()
    {
        registry.clear();
    }

    @Test
    public void testContract()
    {
        DebugPauseTool tool = new DebugPauseTool();
        assertEquals("debug_pause", tool.getName()); //$NON-NLS-1$
        assertEquals(DebugPauseTool.NAME, tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.returnsInfobaseData());
        assertTrue(tool.getDescription().contains("wait_for_break")); //$NON-NLS-1$
        assertTrue(tool.getDescription().endsWith("get_tool_guide('debug_pause').")); //$NON-NLS-1$

        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertEquals(2, properties.size());
        assertEquals("string", properties.getAsJsonObject("applicationId").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("integer", properties.getAsJsonObject("timeout").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Seconds to wait for the pause to take effect (default: 10, max: 600)", //$NON-NLS-1$
            properties.getAsJsonObject("timeout").get("description").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("both parameters are optional", //$NON-NLS-1$
            !schema.has("required") || schema.getAsJsonArray("required").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject output = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        for (String key : new String[] { "success", "paused", "state", "reason", "alreadySuspended", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "message", "applicationId", "autoResolved", "serverTarget", "threadId", "threadName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "frames", "topFrameRef" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("output schema must declare " + key, output.has(key)); //$NON-NLS-1$
        }
        assertFalse("paused is the signal; hit is wait_for_break's", output.has("hit")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== execute(): answers reachable with no live session ====================

    @Test
    public void testNoSessionAndNoIdIsAnActionableError()
    {
        JsonObject result = json(new DebugPauseTool().execute(new HashMap<>()));

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("applicationId is required - no single active debug session is available for " //$NON-NLS-1$
            + "auto-resolution (none or several are running). Use debug_status to list active sessions.", //$NON-NLS-1$
            result.get("error").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testUnknownIdNamesTheValueAndTheListingTool()
    {
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "launch:NoSuchPauseCfg"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("timeout", "abc"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = json(new DebugPauseTool().execute(params));

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("No active debug session for applicationId: launch:NoSuchPauseCfg. Use debug_status " //$NON-NLS-1$
            + "to list active sessions and their applicationId.", result.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== render(): the exact shape of each outcome ====================

    @Test
    public void testPausedResultCarriesTheSnapshotAndNoFailureKeys() throws Exception
    {
        AtomicBoolean suspended = new AtomicBoolean(false);
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenAnswer(invocation -> suspended.get());
        when(thread.getName()).thenReturn("Client"); //$NON-NLS-1$
        IStackFrame frame = mock(IStackFrame.class);
        when(frame.getName()).thenReturn("CommonModule.Calc.Add"); //$NON-NLS-1$
        when(frame.getLineNumber()).thenReturn(2);
        when(frame.getThread()).thenReturn(thread);
        when(thread.getStackFrames()).thenReturn(new IStackFrame[] { frame });
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getThreads()).thenReturn(new IThread[] { thread });
        when(target.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            suspended.set(true);
            return null;
        }).when(target).suspend();
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        JsonObject result = json(DebugPauseTool.render(outcome, APP_ID, true, false, 10, registry));

        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("paused").getAsBoolean()); //$NON-NLS-1$
        assertEquals("suspended", result.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("alreadySuspended").getAsBoolean()); //$NON-NLS-1$
        assertEquals(APP_ID, result.get("applicationId").getAsString()); //$NON-NLS-1$
        assertTrue(result.get("autoResolved").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("serverTarget")); //$NON-NLS-1$
        assertEquals(outcome.snapshot.threadId, result.get("threadId").getAsLong()); //$NON-NLS-1$
        assertEquals("Client", result.get("threadName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject top = result.getAsJsonArray("frames").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("CommonModule.Calc.Add", top.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, top.get("line").getAsInt()); //$NON-NLS-1$
        long topFrameRef = result.get("topFrameRef").getAsLong(); //$NON-NLS-1$
        assertEquals(top.get("frameRef").getAsLong(), topFrameRef); //$NON-NLS-1$
        assertEquals("the frameRef must address the live frame for get_variables", frame, //$NON-NLS-1$
            registry.getFrame(topFrameRef));
        for (String absent : new String[] { "reason", "message", "hit" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse("a paused result must not carry " + absent, result.has(absent)); //$NON-NLS-1$
        }
    }

    @Test
    public void testFrameRefsOfALaunchlessSessionDieWithIt() throws Exception
    {
        // A launchless server session lives under a minted key its frames' launch cannot yield.
        String minted = "ServerApplication.PauseToolApp"; //$NON-NLS-1$
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenReturn(true);
        IStackFrame frame = mock(IStackFrame.class);
        when(frame.getThread()).thenReturn(thread);
        when(thread.getStackFrames()).thenReturn(new IStackFrame[] { frame });
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getThreads()).thenReturn(new IThread[] { thread });
        DebugPause.Outcome outcome = DebugPause.pause(target, minted, registry, 10_000, 10);
        long topFrameRef = json(DebugPauseTool.render(outcome, minted, false, true, 10, registry))
            .get("topFrameRef").getAsLong(); //$NON-NLS-1$

        registry.forgetApplication(minted);

        assertNull("forgetting the session must drop the frameRefs it handed out", //$NON-NLS-1$
            registry.getFrame(topFrameRef));
    }

    @Test
    public void testTimeoutResultSaysTheRequestStaysArmed() throws Exception
    {
        IThread thread = mock(IThread.class);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getThreads()).thenReturn(new IThread[] { thread });
        when(target.canSuspend()).thenReturn(true);
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 50, 10);

        JsonObject result = json(DebugPauseTool.render(outcome, APP_ID, false, true, 3, registry));

        assertTrue("a timeout is a diagnostic result, not an error", result.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("paused").getAsBoolean()); //$NON-NLS-1$
        assertEquals("running", result.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("timeout", result.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Nothing paused within 3s: the session ran no BSL statement in that time (for example, " //$NON-NLS-1$
            + "a client waiting for user input). 1C pauses at the next statement executed, so the request " //$NON-NLS-1$
            + "stays armed - catch the pause with wait_for_break instead of calling debug_pause again.", //$NON-NLS-1$
            result.get("message").getAsString()); //$NON-NLS-1$
        assertEquals(APP_ID, result.get("applicationId").getAsString()); //$NON-NLS-1$
        assertTrue(result.get("serverTarget").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("autoResolved")); //$NON-NLS-1$
        for (String absent : new String[] { "threadId", "frames", "topFrameRef", "alreadySuspended" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse("an unpaused result must not carry " + absent, result.has(absent)); //$NON-NLS-1$
        }
    }

    @Test
    public void testTerminatedResultNamesTheState() throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(true);
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        JsonObject result = json(DebugPauseTool.render(outcome, APP_ID, false, false, 10, registry));

        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("paused").getAsBoolean()); //$NON-NLS-1$
        assertEquals("terminated", result.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("terminated", result.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("The debug session ended before it paused. Start a new one with launch.", //$NON-NLS-1$
            result.get("message").getAsString()); //$NON-NLS-1$
        assertFalse(result.has("threadId")); //$NON-NLS-1$
    }

    @Test
    public void testRefusedRequestIsAnError() throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getThreads()).thenReturn(new IThread[0]);
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        JsonObject result = json(DebugPauseTool.render(outcome, APP_ID, false, false, 10, registry));

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("The debug target of launch:PauseToolCfg accepts no suspend request in its current " //$NON-NLS-1$
            + "state. Check it with debug_status.", result.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject json(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }
}
