// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.operations.WrappingOperation;

/** Failure paths must finish the native operation and release its notifications outside the rule. */
public class WorkspaceModelWriteTest
{
    @Test
    public void failedWriteStillFinishesItsNativeOperationOutsideTheRule() throws Exception
    {
        var workspace = ResourcesPlugin.getWorkspace();
        var manager = mock(IDerivedDataManager.class);
        var scheduled = new AtomicReference<WrappingOperation>();
        var delivered = new AtomicBoolean();
        when(manager.scheduleOperation(any(), any(), eq(true), any())).thenAnswer(call -> {
            scheduled.set(call.getArgument(0));
            return null;
        });
        var failure = new IllegalStateException("mutation failed");
        try
        {
            WorkspaceModelWrite.run(workspace, manager, "test failed write", () -> {
                assertTrue(Job.getJobManager().currentRule().contains(workspace.getRoot()));
                scheduled.get().setAsyncEventScheduler(() -> {
                    assertNull(Job.getJobManager().currentRule());
                    delivered.set(true);
                });
                throw failure;
            });
            fail("The mutation failure must escape");
        }
        catch (IllegalStateException actual) { assertSame(failure, actual); }
        assertTrue("An unfinished important operation can prevent project shutdown", scheduled.get().isFinished());
        assertTrue(delivered.get());
        assertNull(Job.getJobManager().currentRule());
        verify(manager, never()).disableAutoWrapping();
        verify(manager, never()).disableImplicitWaiting();
        verify(manager, never()).implicitDerivedDataWait(any());
    }

    @Test
    public void failedSchedulingCannotEnterTheMutation() throws Exception
    {
        var manager = mock(IDerivedDataManager.class);
        var mutated = new AtomicBoolean();
        var failure = new IllegalStateException("project stopped");
        when(manager.scheduleOperation(any(), any(), eq(true), any())).thenThrow(failure);
        try
        {
            WorkspaceModelWrite.run(ResourcesPlugin.getWorkspace(), manager, "test refused write", () -> {
                mutated.set(true);
                return null;
            });
            fail("The scheduling failure must escape");
        }
        catch (IllegalStateException actual) { assertSame(failure, actual); }
        assertFalse(mutated.get());
        assertNull(Job.getJobManager().currentRule());
    }
}
