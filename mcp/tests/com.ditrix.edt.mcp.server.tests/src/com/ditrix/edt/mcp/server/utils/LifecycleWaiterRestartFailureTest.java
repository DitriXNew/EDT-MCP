/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;

/** A restart can fail before it stops every member of its scope. */
public class LifecycleWaiterRestartFailureTest
{
    @Test
    public void testAFailedRestartAbortsTheStoppedPhaseWithoutSpendingTheBudget()
    {
        IServicesOrchestrator services = mock(IServicesOrchestrator.class);
        ProjectRestartWaiter waiter = new ProjectRestartWaiter(services, "NeverStopped");
        RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        when(failure.hasFailed()).thenReturn(true);
        try
        {
            long start = System.nanoTime();
            assertFalse(waiter.await(1_200, failure));
            assertTrue("a known failure before STOPPED must abort immediately",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
        }
        finally
        {
            waiter.cleanup();
        }
        ArgumentCaptor<IServiceContextLifecycleListener> listener =
            ArgumentCaptor.forClass(IServiceContextLifecycleListener.class);
        verify(services).addListener(listener.capture());
        verify(services, times(1)).removeListener(listener.getValue());
    }
}
