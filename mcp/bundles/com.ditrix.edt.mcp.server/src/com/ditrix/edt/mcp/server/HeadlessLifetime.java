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
    private Runnable afterDrain;

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

    /**
     * Admits a new top-level tool call. Refused once the drain has begun.
     *
     * @return the lease, or {@code null} when the call arrived after the drain started
     */
    public synchronized Lease tool() { return acquire(false); }

    /**
     * Admits tool work started by an operation that is already running - the follow-up a tool
     * schedules after its own commit. While any lease is held the drain is still waiting for the
     * work in flight, so this nested work must be admitted or the operation fails halfway; once
     * every lease is released the drain completes and nothing further is admitted.
     *
     * @return the lease, or {@code null} when no operation is in flight
     */
    public synchronized Lease nestedTool()
    {
        // Normally a nested lease is just a lease. It only needs the drain exception once the
        // shutdown has begun, and then only while an operation is still in flight - the work it is
        // finishing. With nothing in flight there is nothing for it to belong to.
        if (!stopping) { return acquire(false); }
        return tools == 0 ? null : acquire(false, true);
    }

    private Lease acquire(boolean client) { return acquire(client, false); }

    private Lease acquire(boolean client, boolean nested)
    {
        // New work is refused once the drain began; a nested lease is the work the drain is
        // waiting for, so it is admitted while an operation is still in flight.
        if (stopping && (!nested || tools == 0)) { return null; }
        cancel();
        if (client) { clients++; } else { tools++; }
        return new Lease() {
            private boolean released;
            @Override public void close() {
                Runnable ready = null;
                synchronized (HeadlessLifetime.this) {
                    if (released) { return; }
                    released = true;
                    if (client) { clients--; } else { tools--; }
                    if (tools == 0 && afterDrain != null) {
                        ready = afterDrain;
                        afterDrain = null;
                    }
                    arm();
                }
                if (ready != null) { ready.run(); }
            }
        };
    }

    /**
     * Stop admitting connections and new top-level operations, but let the operations already
     * running finish - including the nested leases they take for their own follow-up work.
     */
    public void requestShutdown(Runnable drained)
    {
        synchronized (this) {
            if (stopping) { return; }
            stopping = true;
            cancel();
            if (tools != 0) { afterDrain = drained; return; }
        }
        drained.run();
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
        afterDrain = null;
        cancel();
        if (scheduler != null) { scheduler.shutdownNow(); }
    }
}
