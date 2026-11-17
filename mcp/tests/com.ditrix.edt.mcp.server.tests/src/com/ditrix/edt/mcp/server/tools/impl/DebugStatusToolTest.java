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
}
