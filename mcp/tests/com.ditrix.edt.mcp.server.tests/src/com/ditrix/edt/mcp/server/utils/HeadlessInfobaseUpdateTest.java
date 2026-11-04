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
            assertFalse(result, result.contains("late update"));
        }
        finally { release.countDown(); executor.shutdownNow(); }
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
