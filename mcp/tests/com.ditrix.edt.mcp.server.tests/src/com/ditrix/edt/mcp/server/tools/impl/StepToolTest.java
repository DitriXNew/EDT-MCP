/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Tests for {@link StepTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live debug-model access. threadId/kind validation is
 * pure; the "stale threadId" branch is reachable headlessly because
 * {@code DebugSessionRegistry} is an in-memory singleton whose
 * {@code getThread(id)} is a map lookup returning {@code null} on an empty
 * registry. Actual stepping needs a suspended debug session and is covered by
 * the E2E suite.
 */
public class StepToolTest
{
    @Test
    public void testName()
    {
        assertEquals("step", new StepTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(StepTool.NAME, new StepTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new StepTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new StepTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new StepTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"kind\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingThreadId()
    {
        Map<String, String> params = new HashMap<>();
        params.put("kind", "over"); //$NON-NLS-1$ //$NON-NLS-2$
        // threadId omitted -> defaults to -1 -> "threadId is required"
        String result = new StepTool().execute(params);
        assertTrue(result.contains("threadId is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingKind()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new StepTool().execute(params);
        assertTrue(result.contains("kind is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "over"); //$NON-NLS-1$ //$NON-NLS-2$
        // No suspended session registered -> the in-memory registry returns a null
        // thread -> stale-frame/thread error before any live DebugPlugin access.
        String result = new StepTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }

    // ==================== Timeout clamping (pure, no live debug session) ====================

    @Test
    public void testClampTimeoutNormalValuePassesThrough()
    {
        assertEquals(20, StepTool.clampTimeout(20));
    }

    @Test
    public void testClampTimeoutAboveMaxIsCapped()
    {
        // Unbounded value would block a worker thread for hours -> capped to 600s.
        assertEquals(600, StepTool.clampTimeout(999999));
    }

    @Test
    public void testClampTimeoutAtMaxIsUnchanged()
    {
        assertEquals(600, StepTool.clampTimeout(600));
    }

    @Test
    public void testClampTimeoutBelowOneIsRaisedToOne()
    {
        assertEquals(1, StepTool.clampTimeout(0));
        assertEquals(1, StepTool.clampTimeout(-5));
    }

    // ==================== Timeout response shape (mocked suspended thread) ====================

    /** Keep the shared singleton registry clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    @Test
    public void testTimeoutResponseCarriesApplicationIdAndThreadId()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // A steppable suspended thread registered in the in-memory registry. The
        // stubbed stepOver() is a no-op, so no new SUSPEND ever arrives and the
        // 1-second wait times out — headlessly exercising the timeout response.
        IThread thread = mock(IThread.class, withSettings().extraInterfaces(IStep.class));
        when(((IStep) thread).canStepOver()).thenReturn(true);
        String appId = "launch:StepCfg"; //$NON-NLS-1$
        registry.injectSuspend(appId, thread);
        long threadId = registry.getSnapshot(appId).threadId;

        Map<String, String> params = new HashMap<>();
        params.put("threadId", Long.toString(threadId)); //$NON-NLS-1$
        params.put("kind", "over"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("timeout", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new StepTool().execute(params);

        assertTrue(result.contains("\"hit\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"reason\":\"timeout\"")); //$NON-NLS-1$
        // Both fields are declared in the output schema and are how callers
        // correlate the timeout with the session/thread they stepped.
        assertTrue(result.contains("\"applicationId\":\"launch:StepCfg\"")); //$NON-NLS-1$
        assertTrue(result.contains("\"threadId\":" + threadId)); //$NON-NLS-1$
    }
}
