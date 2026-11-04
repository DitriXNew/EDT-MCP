// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HeadlessLifetime;

public class HeadlessBackgroundActivityTest
{
    @Test public void backgroundWorkerKeepsLeaseUntilItActuallyUnwinds() throws Exception {
        BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>(); AtomicInteger exits = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Field plugin = Activator.class.getDeclaredField("plugin"); plugin.setAccessible(true); Object saved = plugin.get(null);
        try (var lifetime = new HeadlessLifetime(Duration.ofMinutes(30), (task, delay) -> { timers.add(task); return () -> { }; }, exits::incrementAndGet);
             var jobs = new BackgroundJobs(4, 1)) {
            Activator activator = mock(Activator.class); when(activator.getHeadlessLifetime()).thenReturn(lifetime); plugin.set(null, activator);
            Runnable original = timers.take();
            jobs.start("test", 30000, 1, "working", progress -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "done";
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS)); original.run(); assertEquals(0, exits.get());
            assertNull(timers.poll(50, TimeUnit.MILLISECONDS)); release.countDown();
            Runnable after = timers.poll(5, TimeUnit.SECONDS); assertNotNull(after); after.run(); assertEquals(1, exits.get());
        }
        finally { release.countDown(); plugin.set(null, saved); }
    }

    @Test public void scheduledEclipseJobKeepsLeaseUntilDone() throws Exception {
        BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>(); AtomicInteger exits = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Field plugin = Activator.class.getDeclaredField("plugin"); plugin.setAccessible(true); Object saved = plugin.get(null);
        try (var lifetime = new HeadlessLifetime(Duration.ofMinutes(30), (task, delay) -> { timers.add(task); return () -> { }; }, exits::incrementAndGet)) {
            Activator activator = mock(Activator.class); when(activator.getHeadlessLifetime()).thenReturn(lifetime); plugin.set(null, activator);
            Runnable original = timers.take();
            Job job = Job.create("Headless lease test", monitor -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                return Status.OK_STATUS;
            });
            McpJobs.schedule(job); assertTrue(entered.await(3, TimeUnit.SECONDS));
            original.run(); assertEquals(0, exits.get()); assertNull(timers.poll(50, TimeUnit.MILLISECONDS));
            release.countDown(); Runnable after = timers.poll(5, TimeUnit.SECONDS); assertNotNull(after);
            after.run(); assertEquals(1, exits.get()); job.join();
        }
        finally { release.countDown(); plugin.set(null, saved); }
    }
}
