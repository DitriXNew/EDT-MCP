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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Tests for {@link ResumeTool}.
 * <p>
 * Covers tool metadata, the input schema, and the single headless-reachable
 * validation branch: with a positive threadId but no suspended session, the
 * in-memory {@code DebugSessionRegistry} returns a null thread and the tool
 * reports a stale threadId before any live DebugPlugin access (the listener
 * registration it performs first is null-safe headlessly). The
 * applicationId-based resume paths query the live launch manager and are
 * covered by the E2E suite.
 */
public class ResumeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("resume", new ResumeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ResumeTool.NAME, new ResumeTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ResumeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ResumeTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ResumeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresResumeFields()
    {
        String schema = new ResumeTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"resumed\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"scope\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"autoResolved\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"serverTarget\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    /** Keep the shared singleton registry clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ResumeTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }

    @Test
    public void testNoArgumentsWithNoActiveSessionIsError()
    {
        // No threadId, no applicationId, and no auto-resolvable session headless:
        // the tool reports the "provide threadId or applicationId" error.
        DebugSessionRegistry.get().clear();
        Map<String, String> params = new HashMap<>();
        String result = new ResumeTool().execute(params);
        assertTrue(result.contains("Provide threadId or applicationId")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownExplicitApplicationIdIsError()
    {
        // A concrete, non-matching applicationId resolves to null headless and must
        // NOT fall back to a lone session — it reports the specific not-found error.
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "launch:DoesNotExist"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ResumeTool().execute(params);
        assertTrue(result.contains("no active debug target for applicationId")); //$NON-NLS-1$
        assertTrue(result.contains("launch:DoesNotExist")); //$NON-NLS-1$
    }

    @Test
    public void testThreadThatCannotResumeIsError()
    {
        // A registered thread that reports canResume()==false drives the
        // "thread cannot resume" guard — reachable headlessly via the in-memory
        // registry without any live DebugPlugin target.
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        when(thread.canResume()).thenReturn(false);
        when(thread.isSuspended()).thenReturn(false);
        String appId = "launch:NoResumeCfg"; //$NON-NLS-1$
        registry.injectSuspend(appId, thread);
        long threadId = registry.getSnapshot(appId).threadId;

        Map<String, String> params = new HashMap<>();
        params.put("threadId", Long.toString(threadId)); //$NON-NLS-1$
        String result = new ResumeTool().execute(params);

        assertTrue(result.contains("thread cannot resume")); //$NON-NLS-1$
        // The state hint reflects the stubbed running thread.
        assertTrue(result.contains("running")); //$NON-NLS-1$
    }
}
