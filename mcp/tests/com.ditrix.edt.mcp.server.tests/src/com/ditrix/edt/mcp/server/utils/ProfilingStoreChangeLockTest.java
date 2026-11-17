/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest;
import com.ditrix.edt.mcp.server.tools.impl.StopProfilingTool;

/**
 * Our check-then-write changes to the profiling measurement store are serialized by a lock of our own,
 * not by the monitor of EDT's profiling service: EDT holds that monitor in {@code toggleProfiling}
 * across a debug-server command (and in its first cache load), so a rename that synchronized on the
 * service waited for it - on the UI thread when dispatched there.
 */
public class ProfilingStoreChangeLockTest
{
    @Test(timeout = 30_000)
    public void testARenameDoesNotWaitForTheServiceMonitorEdtHolds() throws Exception
    {
        IProfilingService service = mock(IProfilingService.class);
        IProfilingResult result = GetProfilingResultsToolTest.measurement("Original", 2, List.of()); //$NON-NLS-1$
        when(service.getResults()).thenReturn(List.of(result));
        doAnswer(call -> {
            when(result.getName()).thenReturn("After"); //$NON-NLS-1$
            return null;
        }).when(service).setResultName(result, "After"); //$NON-NLS-1$

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Stands for EDT's synchronized toggleProfiling waiting on the debug server.
        Thread edt = new Thread(() -> {
            synchronized (service)
            {
                held.countDown();
                try
                {
                    release.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }, "edt-toggle-profiling"); //$NON-NLS-1$
        edt.setDaemon(true);
        edt.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            Future<String> rename = worker.submit(() -> StopProfilingTool.describeMeasurement(ToolResult.success(),
                service, Set.of(), result.getUuid(), "After", 0).toJson()); //$NON-NLS-1$
            String output = rename.get(5, TimeUnit.SECONDS);
            assertTrue(output, output.contains("\"name\":\"After\"")); //$NON-NLS-1$
            verify(service).setResultName(result, "After"); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            worker.shutdownNow();
            edt.join(5_000);
        }
    }

    @Test(timeout = 30_000)
    public void testOurStoreChangesStillExcludeEachOther() throws Exception
    {
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstDone = new AtomicBoolean();
        AtomicBoolean secondRan = new AtomicBoolean();
        AtomicBoolean overlapped = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> first = pool.submit(() -> ProfilingSupport.applyObserved(null, () -> {
                firstInside.countDown();
                try
                {
                    releaseFirst.await(20, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                firstDone.set(true);
            }, () -> true, "First change", 0)); //$NON-NLS-1$
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));
            Future<String> second = pool.submit(() -> ProfilingSupport.applyObserved(null, () -> {
                overlapped.set(!firstDone.get());
                secondRan.set(true);
            }, () -> true, "Second change", 0)); //$NON-NLS-1$
            Thread.sleep(300);
            assertFalse("a second store change entered while the first was still inside", secondRan.get()); //$NON-NLS-1$
            releaseFirst.countDown();
            assertEquals("", first.get(5, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertEquals("", second.get(5, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertTrue(secondRan.get());
            assertFalse("the second change overlapped the first", overlapped.get()); //$NON-NLS-1$
        }
        finally
        {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }
}
