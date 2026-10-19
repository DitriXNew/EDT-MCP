/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

/** Result, failure and caller closure must retain the same publication monitor. */
public class InfobaseSessionPublicationLockTest
{
    @Test(timeout = 10000)
    public void closingCallerExcludesAResultAlreadyWaitingToPublish() throws Exception
    {
        assertLatePublicationExcluded(false);
    }

    @Test(timeout = 10000)
    public void closingCallerExcludesAFailureAlreadyWaitingToPublish() throws Exception
    {
        assertLatePublicationExcluded(true);
    }

    @Test(timeout = 10000)
    public void callerClosureUsesThePublicationMonitor() throws Exception
    {
        InfobaseSessionSupport.CallerState state = new InfobaseSessionSupport.CallerState();
        Object monitor = monitor(state);
        Thread closer = new Thread(() -> InfobaseSessionSupport.markCallerAnswered(state));
        closer.setDaemon(true);
        try
        {
            synchronized (monitor)
            {
                closer.start();
                assertBlockedOn(closer, monitor);
                assertFalse(state.get());
            }
        }
        finally
        {
            closer.join(2000);
        }
        assertFalse(closer.isAlive());
        assertTrue(state.get());
    }

    private static void assertLatePublicationExcluded(boolean throwsFailure) throws Exception
    {
        InfobaseSessionSupport.CallerState state = new InfobaseSessionSupport.CallerState();
        Object monitor = monitor(state);
        AtomicReference<InfobaseSessionSupport.CommandExecution> execution = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread publisher = new Thread(() -> InfobaseSessionSupport.executeAtPidIfActive(42,
            new NullProgressMonitor(), state, execution, failure, pid -> {
                if (throwsFailure)
                {
                    throw new IllegalStateException("late failure");
                }
                return new InfobaseSessionSupport.CommandExecution(0, "done", "", false);
            }));
        publisher.setDaemon(true);
        try
        {
            synchronized (monitor)
            {
                publisher.start();
                assertBlockedOn(publisher, monitor);
                InfobaseSessionSupport.markCallerAnswered(state);
                assertTrue(state.get());
            }
        }
        finally
        {
            publisher.join(2000);
        }
        assertFalse(publisher.isAlive());
        assertNull("closure prevents late result publication", execution.get());
        assertNull("closure prevents late failure publication", failure.get());
    }

    private static Object monitor(InfobaseSessionSupport.CallerState state) throws Exception
    {
        Field field = InfobaseSessionSupport.CallerState.class.getDeclaredField("monitor");
        field.setAccessible(true);
        return field.get(state);
    }

    private static void assertBlockedOn(Thread thread, Object monitor) throws Exception
    {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.isAlive() && System.nanoTime() < deadline)
        {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor))
            {
                return;
            }
            Thread.sleep(1);
        }
        fail("publication must contend on the owner's monitor; state=" + thread.getState());
    }
}
