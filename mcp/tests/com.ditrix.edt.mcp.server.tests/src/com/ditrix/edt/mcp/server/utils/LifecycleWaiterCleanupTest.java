/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;

import com._1c.g5.v8.dt.lifecycle.ILifecycleContext;
import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;

/**
 * Tests {@link ProjectRestartWaiter#cleanup()} idempotency.
 * <p>
 * A caller that abandons the wait (a clean that timed out, an early error) must unregister the
 * lifecycle listener in a {@code finally}, and that finally can run after {@code await()} already
 * unregistered it on the normal path. Both calls must be safe — hence the guard, and hence this
 * pin: it counts REAL {@code removeListener} calls, so removing the guard reddens it.
 */
public class LifecycleWaiterCleanupTest
{
    /** Counts what actually reached the orchestrator; the other methods are never exercised here. */
    private static final class CountingOrchestrator implements IServicesOrchestrator
    {
        final AtomicInteger added = new AtomicInteger();
        final AtomicInteger removed = new AtomicInteger();

        @Override
        public void addListener(IServiceContextLifecycleListener listener)
        {
            added.incrementAndGet();
        }

        @Override
        public void removeListener(IServiceContextLifecycleListener listener)
        {
            removed.incrementAndGet();
        }

        @Override
        public void startServices(ILifecycleContext context, IProgressMonitor monitor)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopServices(ILifecycleContext context, IProgressMonitor monitor)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isInfrastructureReady()
        {
            return true;
        }
    }

    @Test
    public void testCleanupUnregistersTheListenerExactlyOnceHoweverOftenItIsCalled()
    {
        CountingOrchestrator orchestrator = new CountingOrchestrator();
        ProjectRestartWaiter waiter = new ProjectRestartWaiter(orchestrator, "Demo"); //$NON-NLS-1$
        assertEquals("the waiter registers its listener up front", 1, orchestrator.added.get()); //$NON-NLS-1$

        waiter.cleanup();
        assertEquals("the first cleanup unregisters", 1, orchestrator.removed.get()); //$NON-NLS-1$

        // The abandoning caller's finally, arriving after await() already cleaned up.
        waiter.cleanup();
        waiter.cleanup();
        assertEquals("further cleanups must be no-ops, not repeated deregistrations", //$NON-NLS-1$
            1, orchestrator.removed.get());
    }
}
