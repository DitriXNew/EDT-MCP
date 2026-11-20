/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.ActiveToolCall;

/**
 * The {@code tools/call} invocations whose tool is executing right now, whatever transport they
 * arrived on (HTTP, IPC, the in-process Workmate bridge): every call passes
 * {@code McpProtocolHandler.executeToolTimed}, which registers it before {@code execute} and removes
 * it in the {@code finally} after. {@code get_server_status} reads it, so an agent can see what the
 * server is busy with while a call is still running - the call history records a call only when it
 * has answered.
 * <p>
 * <b>What an entry means.</b> The tool's EDT work has started and has not returned. An entry stays
 * until that work ends, not until a response is sent: a call whose response a user interrupt already
 * delivered stays listed (with {@code responseSent}) while its operation keeps running. A listed call
 * is RUNNING, which is not the same as healthy - a wedged call and a long one look alike.
 * <p>
 * <b>What it holds.</b> The tool name, the call's {@code projectName} argument (cut to
 * {@value #MAX_PROJECT_NAME_CHARS} characters) and two clocks. No other argument value is kept.
 * <p>
 * <b>Bounded and leak-free.</b> An entry exists only between {@link #begin} and the
 * {@link Handle#close} in the caller's {@code finally}, so the registry never holds more entries than
 * there are threads inside a tool's {@code execute} - a thrown exception, an interruption or an
 * expired deadline all unwind through that {@code finally}. On top of that the number of entries is
 * capped at {@value #MAX_TRACKED}; a call over the cap still runs, is not listed, and is counted in
 * {@link Report#untracked()} so a reader never mistakes a capped list for a complete one.
 * <p>
 * <b>Concurrency.</b> Many request threads register and remove at once and a reader snapshots at any
 * moment. The entries live in a concurrent set and the counters are atomics; nothing here takes a lock,
 * so a reader can never wait on a running call and a running call never waits on a reader. A snapshot
 * is weakly consistent: a call registered before the snapshot starts and removed after it ends is
 * listed; one that begins or ends while the snapshot runs may or may not be. {@link Handle#close} is
 * idempotent and removes exactly the entry {@link #begin} added.
 */
public final class InFlightToolCalls
{
    /** Most entries held at once; calls beyond it are counted, not listed. */
    static final int MAX_TRACKED = 256;

    /** Longest {@code projectName} kept, in UTF-16 units; a cut never splits a surrogate pair. */
    static final int MAX_PROJECT_NAME_CHARS = 200;

    /** One running call as a reader sees it. */
    public record Snapshot(String tool, String projectName, long startedAtMs, long elapsedMs,
        boolean responseSent)
    {
    }

    /**
     * The running calls, oldest first, and how many more are running without being listed because
     * the registry was full when they began.
     */
    public record Report(List<Snapshot> calls, int untracked)
    {
    }

    /** Removes the registration {@link #begin} made; idempotent, never throws. */
    public interface Handle extends AutoCloseable
    {
        @Override
        void close();
    }

    private static final Set<Entry> ENTRIES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger TRACKED = new AtomicInteger();
    private static final AtomicInteger UNTRACKED = new AtomicInteger();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * The entry of the call executing on this thread, so a tool can leave its own call out of what
     * it reports. A tool that dispatches another call on the same thread nests: the inner
     * registration replaces the outer one here and restores it when it closes.
     */
    private static final ThreadLocal<Registration> CURRENT = new ThreadLocal<>();

    private InFlightToolCalls()
    {
        // static registry
    }

    /**
     * Registers the call about to execute on this thread. Never throws: nothing here can fail but
     * an allocation, so a registration can never disturb the call it describes.
     *
     * @param tool the tool name
     * @param projectName the call's {@code projectName} argument, or {@code null}; a blank value is
     *            dropped and a long one cut to {@value #MAX_PROJECT_NAME_CHARS} characters
     * @param call the transport invocation, read for {@code responseSent}; {@code null} for a call
     *            that has no interruptible response (the in-process bridge)
     * @return the handle whose {@link Handle#close} removes the registration; close it in a
     *         {@code finally} on this same thread
     */
    public static Handle begin(String tool, String projectName, ActiveToolCall call)
    {
        Registration previous = CURRENT.get();
        Registration handle;
        if (TRACKED.incrementAndGet() > MAX_TRACKED)
        {
            TRACKED.decrementAndGet();
            UNTRACKED.incrementAndGet();
            handle = new Untracked(previous);
        }
        else
        {
            Entry entry = new Entry(SEQUENCE.incrementAndGet(), tool, normalizeProjectName(projectName),
                System.currentTimeMillis(), System.nanoTime(), call, previous);
            ENTRIES.add(entry);
            handle = entry;
        }
        CURRENT.set(handle);
        return handle;
    }

    /**
     * The registration of the call executing on this thread.
     *
     * @return the innermost open handle of this thread, or {@code null} when no call executes here
     */
    public static Handle current()
    {
        return CURRENT.get();
    }

    /**
     * The calls running now, oldest first.
     *
     * @param exclude a registration to leave out - the reader's own call - or {@code null}
     * @return the snapshot; never {@code null}
     */
    public static Report report(Handle exclude)
    {
        long nowNanos = System.nanoTime();
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : ENTRIES)
        {
            if (entry != exclude)
            {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparingLong(entry -> entry.sequence));
        List<Snapshot> calls = new ArrayList<>(entries.size());
        for (Entry entry : entries)
        {
            calls.add(entry.snapshot(nowNanos));
        }
        // The reader's own call is left out of the count too when it is one of the untracked ones.
        int untracked = UNTRACKED.get();
        if (exclude instanceof Untracked own && own.isOpen())
        {
            untracked--;
        }
        return new Report(calls, untracked);
    }

    static String normalizeProjectName(String projectName)
    {
        if (projectName == null)
        {
            return null;
        }
        String trimmed = projectName.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        if (trimmed.length() <= MAX_PROJECT_NAME_CHARS)
        {
            return trimmed;
        }
        int end = MAX_PROJECT_NAME_CHARS;
        if (Character.isHighSurrogate(trimmed.charAt(end - 1)))
        {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * One registration of this thread, linked to the one it replaced as the thread's current call.
     * Closing the current one makes the nearest still-open outer one current again; a handle closed
     * out of order (an outer one before an inner one) is skipped then, never restored.
     */
    private abstract static class Registration implements Handle
    {
        private final Registration previous;

        Registration(Registration previous)
        {
            this.previous = previous;
        }

        abstract boolean isOpen();

        /** Runs once, after this registration closed; only on the thread that registered it. */
        final void restoreCurrent()
        {
            if (CURRENT.get() != this)
            {
                return;
            }
            Registration outer = previous;
            while (outer != null && !outer.isOpen())
            {
                outer = outer.previous;
            }
            if (outer == null)
            {
                CURRENT.remove();
            }
            else
            {
                CURRENT.set(outer);
            }
        }
    }

    /** A listed call. Identity equality: two calls with equal fields are still two calls. */
    private static final class Entry extends Registration
    {
        private final long sequence;
        private final String tool;
        private final String projectName;
        private final long startedAtMs;
        private final long startNanos;
        private final ActiveToolCall call;

        Entry(long sequence, String tool, String projectName, long startedAtMs, long startNanos,
            ActiveToolCall call, Registration previous)
        {
            super(previous);
            this.sequence = sequence;
            this.tool = tool;
            this.projectName = projectName;
            this.startedAtMs = startedAtMs;
            this.startNanos = startNanos;
            this.call = call;
        }

        @Override
        boolean isOpen()
        {
            return ENTRIES.contains(this);
        }

        Snapshot snapshot(long nowNanos)
        {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nowNanos - startNanos));
            return new Snapshot(tool, projectName, startedAtMs, elapsedMs,
                call != null && call.isResponseDelivered());
        }

        /** Idempotent by the set itself: only the first close finds the entry to remove. */
        @Override
        public void close()
        {
            if (ENTRIES.remove(this))
            {
                TRACKED.decrementAndGet();
                restoreCurrent();
            }
        }
    }

    /** A call that began while the registry was full: counted, never listed. */
    private static final class Untracked extends Registration
    {
        private final AtomicBoolean closed = new AtomicBoolean();

        Untracked(Registration previous)
        {
            super(previous);
        }

        @Override
        boolean isOpen()
        {
            return !closed.get();
        }

        @Override
        public void close()
        {
            if (closed.compareAndSet(false, true))
            {
                UNTRACKED.decrementAndGet();
                restoreCurrent();
            }
        }
    }
}
