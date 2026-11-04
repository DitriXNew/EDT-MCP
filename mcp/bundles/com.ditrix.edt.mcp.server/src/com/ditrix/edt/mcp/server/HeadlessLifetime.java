// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Connection admission, tool leases and idle expiry share one lock. */
public final class HeadlessLifetime implements AutoCloseable
{
    public interface Timer { AutoCloseable schedule(Runnable task, long delayNanos); }
    public interface Lease extends AutoCloseable { @Override void close(); }
    private final long idleNanos;
    private final Timer timer;
    private final Runnable shutdown;
    private final ScheduledExecutorService scheduler;
    private AutoCloseable deadline;
    private long generation;
    private int clients;
    private int tools;
    private boolean stopping;

    public HeadlessLifetime(Duration idle, Runnable shutdown)
    {
        this(idle, null, shutdown);
    }

    public HeadlessLifetime(Duration idle, Timer timer, Runnable shutdown)
    {
        if (idle.isNegative()) { throw new IllegalArgumentException("Idle duration must be nonnegative"); }
        idleNanos = idle.toNanos();
        this.shutdown = shutdown;
        scheduler = timer == null ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "edt-mcp-headless-idle"); thread.setDaemon(true); return thread;
        }) : null;
        this.timer = timer != null ? timer : (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(task, delay, TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        };
        arm();
    }

    public synchronized Lease connect() { return acquire(true); }
    public synchronized Lease tool() { return acquire(false); }

    private Lease acquire(boolean client)
    {
        if (stopping) { return null; }
        cancel();
        if (client) { clients++; } else { tools++; }
        return new Lease() {
            private boolean released;
            @Override public void close() {
                synchronized (HeadlessLifetime.this) {
                    if (released) { return; }
                    released = true;
                    if (client) { clients--; } else { tools--; }
                    arm();
                }
            }
        };
    }

    private synchronized void arm()
    {
        if (stopping || idleNanos == 0 || clients != 0 || tools != 0) { return; }
        cancel();
        long expected = generation;
        deadline = timer.schedule(() -> expire(expected), idleNanos);
    }

    private void expire(long expected)
    {
        synchronized (this) {
            if (stopping || expected != generation || clients != 0 || tools != 0) { return; }
            stopping = true;
        }
        shutdown.run();
    }

    private void cancel()
    {
        generation++;
        if (deadline != null) {
            try { deadline.close(); } catch (Exception e) { Activator.logError("Cannot cancel headless idle timer", e); }
            deadline = null;
        }
    }

    @Override public synchronized void close()
    {
        stopping = true;
        cancel();
        if (scheduler != null) { scheduler.shutdownNow(); }
    }
}
