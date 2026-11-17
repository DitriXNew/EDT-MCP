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

/** Tool contracts and suspend-summary behavior without starting a live debug session. */
public class DebugStatusToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_status", new DebugStatusTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugStatusTool.NAME, new DebugStatusTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugStatusTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugStatusTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugStatusTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    @Test
    public void suspendSummaryIgnoresTerminatedTargetsAndReportsFirstSuspendedFrame() throws Exception
    {
        var stopped = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class);
        org.mockito.Mockito.when(stopped.isTerminated()).thenReturn(true);
        var running = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class);
        var activeThread = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IThread.class);
        var suspendedThread = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IThread.class);
        var frame = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IStackFrame.class);
        org.mockito.Mockito.when(suspendedThread.isSuspended()).thenReturn(true);
        org.mockito.Mockito.when(suspendedThread.getTopStackFrame()).thenReturn(frame);
        org.mockito.Mockito.when(frame.getName()).thenReturn("Calc.Add");
        org.mockito.Mockito.when(frame.getLineNumber()).thenReturn(12);
        org.mockito.Mockito.when(running.getThreads()).thenReturn(new org.eclipse.debug.core.model.IThread[] {
            activeThread, suspendedThread });
        var method = DebugStatusTool.class.getDeclaredMethod("appendSuspendState", java.util.Map.class,
            org.eclipse.debug.core.model.IDebugTarget[].class);
        method.setAccessible(true);
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        method.invoke(null, entry, new org.eclipse.debug.core.model.IDebugTarget[] { null, stopped, running });
        assertEquals(2, entry.get("threadCount"));
        assertEquals(true, entry.get("suspended"));
        assertEquals("Calc.Add @ 12", entry.get("suspendedAt"));
        org.mockito.Mockito.verify(stopped, org.mockito.Mockito.never()).getThreads();
    }

    /** Codex P2 on #965: a server debuggee's threads do not prove that a thin client attached. */
    @Test
    public void clientThreadCountExcludesServerAndTerminatedThreads() throws Exception
    {
        var client = typedThread(com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType.MANAGED_CLIENT, false);
        var finished = typedThread(com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType.MANAGED_CLIENT, true);
        var server = typedThread(com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType.SERVER, false);
        var serverOnly = target(server);
        var mixed = target(server, client, finished);
        var method = DebugStatusTool.class.getDeclaredMethod("appendSuspendState", java.util.Map.class,
            org.eclipse.debug.core.model.IDebugTarget[].class);
        method.setAccessible(true);

        java.util.Map<String, Object> onlyServer = new java.util.LinkedHashMap<>();
        method.invoke(null, onlyServer, new org.eclipse.debug.core.model.IDebugTarget[] { serverOnly });
        assertEquals(1, onlyServer.get("threadCount"));
        assertEquals(0, onlyServer.get("clientThreadCount"));

        java.util.Map<String, Object> both = new java.util.LinkedHashMap<>();
        method.invoke(null, both, new org.eclipse.debug.core.model.IDebugTarget[] { mixed });
        assertEquals(3, both.get("threadCount"));
        assertEquals(1, both.get("clientThreadCount"));
    }

    private static com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread typedThread(
        com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType type, boolean terminated)
    {
        var thread = org.mockito.Mockito.mock(com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread.class);
        org.mockito.Mockito.when(thread.getType()).thenReturn(type);
        org.mockito.Mockito.when(thread.isTerminated()).thenReturn(terminated);
        return thread;
    }

    private static org.eclipse.debug.core.model.IDebugTarget target(org.eclipse.debug.core.model.IThread... threads)
        throws Exception
    {
        var target = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class);
        org.mockito.Mockito.when(target.getThreads()).thenReturn(threads);
        return target;
    }
}
