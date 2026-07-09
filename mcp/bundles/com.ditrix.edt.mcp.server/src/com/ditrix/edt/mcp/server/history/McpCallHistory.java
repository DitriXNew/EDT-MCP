/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Bounded, thread-safe in-memory ring of {@link McpCallRecord} exchanges captured
 * at the MCP transport choke point.
 *
 * <p><b>Invariants.</b> {@link #record} is O(1), non-blocking, thread-safe under
 * many concurrent producer (transport) threads plus a single UI reader, and
 * <em>never</em> throws to its caller — recording history must never perturb the
 * request that is being served. Memory is bounded by the cached buffer size times
 * a per-record payload cap: each payload string is truncated (with a trailing
 * {@link #TRUNCATION_MARKER}) <em>before</em> a short append critical section, so
 * the lock is held only for the O(1) deque eviction + add. {@link #snapshot}
 * returns a defensive copy, and history listeners are stored in a
 * {@link CopyOnWriteArrayList} and fired outside the buffer lock with each callback
 * guarded so a faulty listener cannot break recording.</p>
 *
 * <p>The recording flag and buffer size are cached fields, refreshed only when
 * preferences change (via {@link #setRecordingEnabled} / {@link #setBufferSize});
 * {@code record} never reads the preference store, keeping the hot path allocation-
 * and I/O-free.</p>
 */
public final class McpCallHistory // NOSONAR intentional singleton (getInstance); a single shared ring is by design
{
    /**
     * Per-payload character cap. Each of {@code requestJson} / {@code responseJson}
     * is truncated to at most this many characters (plus the short
     * {@link #TRUNCATION_MARKER}) before being stored, bounding the ring's memory to
     * roughly {@code bufferSize * 2 * MAX_PAYLOAD_CHARS} characters.
     */
    static final int MAX_PAYLOAD_CHARS = 20_000;

    /** Stable ASCII marker appended to a payload that was truncated to the cap. */
    static final String TRUNCATION_MARKER = "...[truncated]"; //$NON-NLS-1$

    /** Guards lazy creation of {@link #instance}. */
    static final Object INSTANCE_LOCK = new Object();

    private static McpCallHistory instance;

    /**
     * Ordered ring of records, oldest first. A {@link ConcurrentLinkedDeque} (not an
     * {@code ArrayDeque}) so that even a mis-guarded future read cannot raise a
     * {@link java.util.ConcurrentModificationException}. <b>Invariant:</b> every
     * mutation ({@code pollFirst}/{@code addLast}/{@code clear}) MUST hold
     * {@link #bufferLock} and keep {@link #bufferCount} in step; the concurrent type
     * is a safety net, not a licence to mutate without the lock.
     */
    private final Deque<McpCallRecord> buffer = new ConcurrentLinkedDeque<>();

    /**
     * Live element count of {@link #buffer}, kept in step with it under
     * {@link #bufferLock}. Tracked explicitly because {@link ConcurrentLinkedDeque#size()}
     * is O(n); this keeps the {@code record} hot path and {@link #size()} O(1).
     */
    private final AtomicInteger bufferCount = new AtomicInteger();

    /** Exclusive monitor for {@link #buffer}; held only for O(1) evict+add / copy. */
    private final Object bufferLock = new Object();

    /** Change listeners, fired (guarded) after a record is appended. */
    private final CopyOnWriteArrayList<HistoryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Cached ring capacity. {@code volatile} so a producer reads the latest value
     * without locking; refreshed from preferences via {@link #setBufferSize}.
     */
    private volatile int bufferSize = HistoryConfig.DEFAULT_BUFFER_SIZE;

    /**
     * Cached recording flag. {@code volatile} for the same reason as
     * {@link #bufferSize}; when {@code false}, {@link #record} is a cheap no-op.
     */
    private volatile boolean recordingEnabled = HistoryConfig.DEFAULT_RECORD;

    /**
     * Package-private so production uses {@link #getInstance()} while tests can
     * create isolated, non-shared instances.
     */
    McpCallHistory()
    {
        // No preference read here: callers configure the cached fields explicitly.
    }

    /**
     * Returns the shared history ring, creating it on first use.
     *
     * @return the singleton instance
     */
    public static McpCallHistory getInstance()
    {
        synchronized (INSTANCE_LOCK)
        {
            if (instance == null)
            {
                instance = new McpCallHistory();
            }
            return instance;
        }
    }

    /**
     * Records one request/response exchange. Truncates each payload to
     * {@link #MAX_PAYLOAD_CHARS} outside the lock, then appends inside a short
     * critical section that evicts the oldest record(s) so the ring never exceeds
     * the cached buffer size. When recording is disabled this returns immediately.
     *
     * <p>This method NEVER throws to its caller: any unexpected failure (including a
     * faulty listener) is swallowed, because history capture must not disturb the
     * request being served.</p>
     *
     * @param method the JSON-RPC method (may be {@code null})
     * @param toolName the tool name for a {@code tools/call}, else {@code null}
     * @param requestJson the raw request body (may be {@code null})
     * @param responseJson the raw response body (may be {@code null})
     * @param durationMs the exchange duration in milliseconds
     */
    public void record(String method, String toolName, String requestJson, String responseJson,
        long durationMs)
    {
        try
        {
            if (!recordingEnabled)
            {
                return;
            }

            // Cap payloads BEFORE taking the buffer lock so the critical section is
            // strictly the O(1) evict + add (no substring/allocation under the lock).
            McpCallRecord entry = new McpCallRecord(System.currentTimeMillis(), method, toolName,
                capPayload(requestJson), capPayload(responseJson), durationMs);

            int cap = clampCap(bufferSize);
            synchronized (bufferLock)
            {
                while (bufferCount.get() >= cap && buffer.pollFirst() != null)
                {
                    bufferCount.decrementAndGet();
                }
                buffer.addLast(entry);
                bufferCount.incrementAndGet();
            }

            fireRecorded(entry);
        }
        catch (RuntimeException e) // NOSONAR record() must never throw to the served request
        {
            // Intentionally swallowed: history capture must never disturb the caller.
        }
    }

    /**
     * Returns a defensive copy of the current ring contents, oldest first. The
     * returned list is caller-owned and detached from the ring; the records
     * themselves are immutable and safe to share.
     *
     * @return a snapshot copy of the recorded exchanges
     */
    public List<McpCallRecord> snapshot()
    {
        synchronized (bufferLock)
        {
            return new ArrayList<>(buffer);
        }
    }

    /**
     * @return the number of records currently retained in the ring
     */
    public int size()
    {
        return bufferCount.get();
    }

    /**
     * Removes every recorded exchange from the ring.
     */
    public void clear()
    {
        synchronized (bufferLock)
        {
            buffer.clear();
            bufferCount.set(0);
        }
    }

    /**
     * @return the cached ring capacity
     */
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Updates the cached ring capacity and trims the ring immediately if it now
     * holds more than the new capacity. The value is clamped to the supported range
     * so the ring stays bounded even for a mistyped preference.
     *
     * @param newSize the requested capacity
     */
    public void setBufferSize(int newSize)
    {
        int cap = clampCap(newSize);
        bufferSize = cap;
        synchronized (bufferLock)
        {
            while (bufferCount.get() > cap && buffer.pollFirst() != null)
            {
                bufferCount.decrementAndGet();
            }
        }
    }

    /**
     * @return whether recording is currently enabled
     */
    public boolean isRecordingEnabled()
    {
        return recordingEnabled;
    }

    /**
     * Enables or disables recording. When disabled, {@link #record} is a no-op; the
     * already-recorded contents are left untouched.
     *
     * @param enabled the new recording flag
     */
    public void setRecordingEnabled(boolean enabled)
    {
        this.recordingEnabled = enabled;
    }

    /**
     * Refreshes the cached recording flag and ring capacity from the preference
     * store so the user's "Record" toggle and "History size" actually take effect —
     * both at server startup and live whenever a history preference changes. Reads
     * through {@link HistoryConfig} (which tolerates a {@code null} store by returning
     * the documented defaults) and routes through the same validating
     * {@link #setRecordingEnabled} / {@link #setBufferSize} setters, so the ring stays
     * bounded. This is the ONLY production path that reconfigures the recorder; it is
     * off the {@link #record} hot path.
     *
     * @param store the preference store to read (may be {@code null})
     */
    public void applyPreferences(IPreferenceStore store)
    {
        setRecordingEnabled(HistoryConfig.isRecordEnabled(store));
        setBufferSize(HistoryConfig.getBufferSize(store));
    }

    /**
     * Registers a history listener (no-op for {@code null} or a duplicate).
     *
     * @param listener the listener to add
     */
    public void addListener(HistoryListener listener)
    {
        if (listener != null)
        {
            listeners.addIfAbsent(listener);
        }
    }

    /**
     * Unregisters a previously added history listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(HistoryListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * Fires the {@code onRecord} callback for every listener, guarding each call so
     * one faulty listener cannot break recording or leak an exception to the
     * producer thread. Runs outside {@link #bufferLock}.
     *
     * @param entry the record just appended
     */
    private void fireRecorded(McpCallRecord entry)
    {
        for (HistoryListener listener : listeners)
        {
            try
            {
                listener.onRecord(entry);
            }
            catch (RuntimeException e) // NOSONAR a faulty listener must not break recording
            {
                // Intentionally swallowed per the record() no-throw invariant.
            }
        }
    }

    /**
     * Truncates {@code payload} to {@link #MAX_PAYLOAD_CHARS} characters, appending
     * {@link #TRUNCATION_MARKER} and the omitted-character count when it is longer.
     * A {@code null} or already-short payload is returned unchanged. Exposed
     * (package-private) so the cap behaviour can be unit-tested directly.
     *
     * @param payload the raw payload (may be {@code null})
     * @return the capped payload, or the same reference when within the cap
     */
    static String capPayload(String payload)
    {
        if (payload == null || payload.length() <= MAX_PAYLOAD_CHARS)
        {
            return payload;
        }
        int omitted = payload.length() - MAX_PAYLOAD_CHARS;
        return payload.substring(0, MAX_PAYLOAD_CHARS) + TRUNCATION_MARKER + " (" //$NON-NLS-1$
            + omitted + " chars omitted)"; //$NON-NLS-1$
    }

    /**
     * Clamps a capacity to a sane positive range so the ring is always bounded.
     *
     * @param raw the requested capacity
     * @return the clamped capacity in {@code [MIN_BUFFER_SIZE, MAX_BUFFER_SIZE]}
     */
    private static int clampCap(int raw)
    {
        if (raw < HistoryConfig.MIN_BUFFER_SIZE)
        {
            return HistoryConfig.MIN_BUFFER_SIZE;
        }
        if (raw > HistoryConfig.MAX_BUFFER_SIZE)
        {
            return HistoryConfig.MAX_BUFFER_SIZE;
        }
        return raw;
    }

    /**
     * Listener notified when a new exchange is recorded, so the UI history view can
     * refresh incrementally. Callbacks run on the producer (transport) thread and
     * are exception-guarded by the ring, so an implementation must be fast and must
     * marshal any UI work onto the display thread itself.
     */
    public interface HistoryListener
    {
        /**
         * Invoked after {@code record} has appended a new record.
         *
         * @param record the newly appended, immutable record
         */
        void onRecord(McpCallRecord record);
    }
}
