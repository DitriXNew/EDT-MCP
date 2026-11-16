/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.debug.core.model.IDebugTarget;
import org.junit.Test;
import com._1c.g5.v8.dt.profiling.core.IProfileTarget;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest;
import com.ditrix.edt.mcp.server.tools.impl.StopProfilingTool;

public class ProfilingSupportTest
{
    @Test public void oldResultsAreNeverNamed() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult old = GetProfilingResultsToolTest.measurement("Old", 1, List.of());
        when(service.getResults()).thenReturn(List.of(old));
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service,
            Set.of(old.getUuid()), null, "New name", 0).toJson();
        assertTrue(output, output.contains("No measurement was named") && output.contains("mode=list"));
        verify(service, never()).setResultName(any(), anyString());
    }
    @Test public void newUuidIsNamedAndEchoed() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Original", 2, List.of());
        when(service.getResults()).thenReturn(List.of(result));
        doAnswer(call -> { when(result.getName()).thenReturn("After"); return null; }).when(service).setResultName(result, "After");
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), result.getUuid(), "After", 0).toJson();
        assertTrue(output, output.contains(result.getUuid().toString()) && output.contains("\"name\":\"After\""));
        verify(service).setResultName(result, "After");
    }
    @Test public void delayedArrivalIsObserved() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Arrival", 2, List.of());
        when(service.getResults()).thenReturn(List.of(), List.of(result));
        assertSame(result, ProfilingSupport.awaitNewResult(service, Set.of(), null, 500));
    }
    @Test public void concurrentArrivalsAreNotGuessed() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult one = GetProfilingResultsToolTest.measurement("One", 1, List.of());
        IProfilingResult two = GetProfilingResultsToolTest.measurement("Two", 2, List.of());
        when(service.getResults()).thenReturn(List.of(one, two));
        assertNull(ProfilingSupport.awaitNewResult(service, Set.of(), null, 0));
    }
    @Test public void staggeredForeignArrivalIsSkippedForTheOwnSession() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult foreign = GetProfilingResultsToolTest.measurement("Foreign", 1, List.of());
        IProfilingResult own = GetProfilingResultsToolTest.measurement("Own", 2, List.of());
        when(service.getResults()).thenReturn(List.of(foreign), List.of(foreign, own));
        assertSame(own, ProfilingSupport.awaitNewResult(service, Set.of(), own.getUuid(), 500));
    }
    @Test public void foreignArrivalIsNeverNamedWithoutASessionId() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult foreign = GetProfilingResultsToolTest.measurement("Foreign", 1, List.of());
        when(service.getResults()).thenReturn(List.of(foreign));
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), null, "Mine", 0).toJson();
        assertTrue(output, output.contains("\"success\":false") && output.contains("none was named"));
        verify(service, never()).setResultName(any(), anyString());
    }
    @Test public void sessionIdIsReadFromTheServiceSessionMap()
    {
        IDebugTarget target = mock(IDebugTarget.class, withSettings().extraInterfaces(IProfileTarget.class));
        IDebugTarget other = mock(IDebugTarget.class, withSettings().extraInterfaces(IProfileTarget.class));
        ServiceWithSessions service = mock(ServiceWithSessions.class, withSettings().useConstructor());
        UUID id = UUID.randomUUID();
        service.targets.put(UUID.randomUUID(), (IProfileTarget)other);
        service.targets.put(id, (IProfileTarget)target);
        assertEquals(id, ProfilingSupport.sessionId(service, target));
        assertNull("A service without the map gives no ownership", ProfilingSupport.sessionId(mock(IProfilingService.class), target));
    }
    /** Mirrors the field EDT's ProfilingService keeps. */
    public abstract static class ServiceWithSessions implements IProfilingService
    {
        final Map<UUID, IProfileTarget> targets = new ConcurrentHashMap<>();
    }
    @Test public void noNameStillReturnsNewIdentity() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Arrival", 2, List.of());
        when(service.getResults()).thenReturn(List.of(result));
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), null, null, 0).toJson();
        assertTrue(output, output.contains(result.getUuid().toString()) && output.contains("Arrival"));
        verify(service, never()).setResultName(any(), anyString());
    }
    @Test public void renameFailureIsReportedHonestly() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Original", 2, List.of());
        when(service.getResults()).thenReturn(List.of(result));
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), result.getUuid(), "After", 0).toJson();
        assertTrue(output, output.contains("EDT did not apply") && output.contains("\"success\":false"));
    }
    @Test public void invalidStopNameRefusedBeforeInactiveShortcut()
    {
        var errors = new java.util.concurrent.CopyOnWriteArrayList<org.eclipse.core.runtime.IStatus>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getSeverity() == org.eclipse.core.runtime.IStatus.ERROR
                && status.getMessage().contains("stop_profiling")) { errors.add(status); }
        };
        org.eclipse.core.runtime.Platform.addLogListener(listener);
        try
        {
            String output = new StopProfilingTool().execute(java.util.Map.of("applicationId", "inactive", "name", "../escape"));
            assertTrue(output, output.contains("Invalid measurement name") && output.contains("\"success\":false"));
            assertTrue("Expected validation refusals must not log platform errors: " + errors, errors.isEmpty());
        }
        finally { org.eclipse.core.runtime.Platform.removeLogListener(listener); }
    }
    @Test public void unitsAndZeroBaselineAreExplicit()
    {
        assertEquals("0.001 ms", ProfilingReport.ms(0.001));
        assertEquals("-5.000 ms (-50.00%)", ProfilingReport.change(10, 5));
        assertTrue(ProfilingReport.change(0, 1).contains("base is zero"));
    }

    @Test public void listenerFailureAfterRenameUsesFreshUuidReadback() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult original = GetProfilingResultsToolTest.measurement("Original", 2, List.of());
        IProfilingResult persisted = GetProfilingResultsToolTest.measurement("After", 2, List.of());
        var uuid = original.getUuid();
        when(persisted.getUuid()).thenReturn(uuid);
        when(service.getResults()).thenReturn(List.of(original));
        doAnswer(call -> {
            when(service.getResults()).thenReturn(List.of(persisted));
            throw new NullPointerException("No active profiling window");
        }).when(service).setResultName(original, "After");
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), uuid, "After", 0).toJson();
        assertTrue(output, output.contains("\"success\":true") && output.contains("\"name\":\"After\""));
        assertTrue(output, output.contains("profiling view may need a refresh"));
        assertEquals("The original object deliberately stays stale", "Original", original.getName());
    }

    @Test public void listenerFailureWithoutPersistedRenameIsAnError() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Original", 2, List.of());
        when(service.getResults()).thenReturn(List.of(result));
        doThrow(new IllegalStateException("Save refused")).when(service).setResultName(result, "After");
        String output = StopProfilingTool.describeMeasurement(ToolResult.success(), service, Set.of(), result.getUuid(), "After", 0).toJson();
        assertTrue(output, output.contains("\"success\":false") && output.contains("Save refused"));
        assertFalse(output, output.contains("profiling view may need a refresh"));
    }

    @Test public void mutationIsDispatchedThroughTheUiExecutorAndVerified()
    {
        var onUi = new java.util.concurrent.atomic.AtomicBoolean();
        var saved = new java.util.concurrent.atomic.AtomicBoolean();
        BoundedUiCall.UiExecutor ui = new BoundedUiCall.UiExecutor()
        {
            @Override public boolean isCurrentThread() { return false; }
            @Override public void asyncExec(Runnable work) { onUi.set(true); try { work.run(); } finally { onUi.set(false); } }
        };
        assertEquals("", ProfilingSupport.applyObserved(ui, () -> {
            assertTrue("Mutation must execute through UI dispatch", onUi.get()); saved.set(true);
        }, saved::get, "Test rename", 1000));
    }

    @Test public void uiTimeoutCancelsAQueuedMutation()
    {
        var queued = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        BoundedUiCall.UiExecutor ui = new BoundedUiCall.UiExecutor()
        {
            @Override public boolean isCurrentThread() { return false; }
            @Override public void asyncExec(Runnable work) { queued.set(work); }
        };
        try { ProfilingSupport.applyObserved(ui, writes::incrementAndGet, () -> false, "Test rename", 0); fail("Timeout must refuse"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("not observed")); }
        queued.get().run();
        assertEquals("Timed-out queued work must never write later", 0, writes.get());
    }
}
