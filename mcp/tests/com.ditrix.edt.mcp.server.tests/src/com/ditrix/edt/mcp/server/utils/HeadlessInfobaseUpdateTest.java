// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.*;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.*;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

public class HeadlessInfobaseUpdateTest
{
    @Test public void neverStartedUpdateHasNoUnknownMutationMarker()
    {
        for (boolean cancel : new boolean[] { false, true })
        {
            String result = HeadlessInfobaseUpdate.runBounded(() -> {
                fail("Unscheduled update executed"); return "unexpected";
            }, 30, job -> { job.schedule(60_000); if (cancel) job.cancel(); });
            var response = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
            assertEquals(result, "notStarted", response.get("outcome").getAsString());
            assertFalse(result, response.has("mutationOutcomeUnknown"));
        }
    }

    @Test public void completedUpdateFailureCannotProveRollback()
    {
        String result = HeadlessInfobaseUpdate.runBounded(() -> {
            throw new IllegalStateException("Failure after database work began");
        }, 5000);
        var response = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        assertEquals("failed", response.get("outcome").getAsString());
        assertTrue(result, response.has("mutationOutcomeUnknown"));
        assertTrue(response.get("mutationOutcomeUnknown").getAsBoolean());
        assertTrue(result, result.contains("Inspect the database"));
    }

    @Test public void completedUpdateReturnsItsActualAnswer()
    {
        assertEquals("completed", HeadlessInfobaseUpdate.runBounded(() -> "completed", 5000));
    }

    @Test public void completedStructuredRefusalsKeepTheirProducersMutationEvidence()
    {
        for (var result : new com.ditrix.edt.mcp.server.protocol.ToolResult[] {
            com.ditrix.edt.mcp.server.protocol.ToolResult.error("No target selected"),
            com.ditrix.edt.mcp.server.protocol.ToolResult.errorAfterMutation("Target changed"),
            com.ditrix.edt.mcp.server.protocol.ToolResult.errorWithUnknownMutationOutcome("Native update failed") })
        {
            String answer = result.toJson();
            assertEquals(answer, HeadlessInfobaseUpdate.runBounded(() -> answer, 5000));
        }
    }

    @Test public void blockedPlatformUpdateCannotHoldItsCallerForever() throws Exception
    {
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try
        {
            var pending = executor.submit(() -> HeadlessInfobaseUpdate.runBounded(() -> {
                while (release.getCount() > 0)
                {
                    try { release.await(); }
                    catch (InterruptedException ignored) { }
                }
                return "late update";
            }, 50L));
            String result;
            try { result = pending.get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException e) { throw new AssertionError("The update caller hung", e); }
            assertTrue(result, result.contains("Close active infobase sessions"));
            assertTrue(result, result.contains("unknown"));
            assertTrue(result, com.google.gson.JsonParser.parseString(result).getAsJsonObject()
                .has("mutationOutcomeUnknown"));
            assertTrue(result, com.google.gson.JsonParser.parseString(result).getAsJsonObject()
                .get("mutationOutcomeUnknown").getAsBoolean());
            assertFalse(result, result.contains("late update"));
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void interruptedInFlightUpdateReportsUnknownMutation() throws Exception
    {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread caller = new Thread(() -> result.set(HeadlessInfobaseUpdate.runBounded(() -> {
            started.countDown();
            while (release.getCount() > 0)
            {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return "late";
        }, 30_000L)));
        try
        {
            caller.start();
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            caller.interrupt(); caller.join(5000);
            assertFalse("Caller did not return", caller.isAlive());
            assertTrue(result.get(), com.google.gson.JsonParser.parseString(result.get()).getAsJsonObject()
                .has("mutationOutcomeUnknown"));
            assertTrue(result.get(), com.google.gson.JsonParser.parseString(result.get()).getAsJsonObject()
                .get("mutationOutcomeUnknown").getAsBoolean());
        }
        finally { release.countDown(); caller.interrupt(); caller.join(5000); }
    }

    @Test public void fullAndIncrementalUseCoreCallbacksWithoutAnActiveShell()
    {
        var manager = mock(IInfobaseSynchronizationManager.class);
        var target = mock(IInfobaseApplication.class);
        var project = mock(IProject.class);
        var infobase = mock(InfobaseReference.class);
        when(target.getProject()).thenReturn(project);
        when(target.getInfobase()).thenReturn(infobase);
        when(manager.reloadInfobase(eq(project), eq(infobase), any(), eq(false), any())).thenReturn(Status.OK_STATUS);
        when(manager.updateInfobase(eq(project), eq(infobase), any(), eq(false), any())).thenReturn(Status.OK_STATUS);
        for (var type : ApplicationUpdateType.values())
        {
            assertEquals(ApplicationUpdateState.UPDATED, HeadlessInfobaseUpdate.update(manager, target,
                type, ExternalInfobaseChangesPolicy.OVERRIDE, new NullProgressMonitor()));
        }
        verify(manager).reloadInfobase(eq(project), eq(infobase), any(), eq(false), any());
        verify(manager).updateInfobase(eq(project), eq(infobase), any(), eq(false), any());
    }

    @Test public void callbackConfirmsRestructureAndPreservesAllThreeExternalChangePolicies() throws Exception
    {
        var resolver = mock(IInfobaseUpdateConflictResolver.class);
        var flow = mock(IUpdateInfobaseFlow.class);
        var monitor = new NullProgressMonitor();
        var expected = new InfobaseConflictResolution(InfobaseConflictResolutionResult.IMPORTED);
        when(resolver.resolveConflict(null, null, Set.of(), Set.of(), Set.of(), null, null, flow, monitor))
            .thenReturn(expected);
        when(resolver.overrideConflict(null, null, Set.of(), Set.of(), Set.of(), null, null, flow, monitor))
            .thenReturn(InfobaseConflictResolutionResult.OVERRIDDEN);
        var override = HeadlessInfobaseUpdate.callback(ExternalInfobaseChangesPolicy.OVERRIDE);
        assertTrue(override.onConfirm(null, null, List.of(), monitor));
        assertEquals(InfobaseConflictResolutionResult.OVERRIDDEN, override.resolveInfobaseChanges(null, null,
            Set.of(), Set.of(), Set.of(), null, resolver, null, flow, monitor).getResolutionResult());
        assertSame(expected, HeadlessInfobaseUpdate.callback(ExternalInfobaseChangesPolicy.IMPORT)
            .resolveInfobaseChanges(null, null, Set.of(), Set.of(), Set.of(), null, resolver, null, flow, monitor));
        var cancelled = assertThrows(InfobaseSynchronizationException.class,
            () -> HeadlessInfobaseUpdate.callback(ExternalInfobaseChangesPolicy.CANCEL)
                .resolveInfobaseChanges(null, null, Set.of(), Set.of(), Set.of(), null, resolver, null, flow, monitor));
        assertTrue(cancelled.getStatus().getMessage().contains("externalInfobaseChanges=cancel"));
        verify(resolver).overrideConflict(null, null, Set.of(), Set.of(), Set.of(), null, null, flow, monitor);
        verify(resolver).resolveConflict(null, null, Set.of(), Set.of(), Set.of(), null, null, flow, monitor);
        verifyNoMoreInteractions(resolver);
    }

    @Test public void busyFileInfobaseIsAnActionableError()
    {
        var manager = mock(IInfobaseSynchronizationManager.class);
        var target = mock(IInfobaseApplication.class);
        when(target.getProject()).thenReturn(mock(IProject.class));
        when(target.getInfobase()).thenReturn(mock(InfobaseReference.class));
        when(manager.updateInfobase(any(), any(), any(), eq(false), any()))
            .thenReturn(new Status(org.eclipse.core.runtime.IStatus.ERROR, "platform", "Exclusive access denied"));
        var error = assertThrows(ApplicationException.class, () -> HeadlessInfobaseUpdate.update(manager,
            target, ApplicationUpdateType.INCREMENTAL, ExternalInfobaseChangesPolicy.OVERRIDE, new NullProgressMonitor()));
        assertTrue(error.getMessage(), error.getMessage().contains("Close active infobase sessions"));
        assertTrue(error.getMessage(), error.getMessage().contains("Exclusive access denied"));
    }
}
