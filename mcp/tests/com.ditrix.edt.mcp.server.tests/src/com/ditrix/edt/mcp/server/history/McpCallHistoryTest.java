/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/**
 * Tests for {@link McpCallHistory}: the bounded ring evicts oldest-first at
 * capacity, caps oversized payloads with a truncation marker, records concurrently
 * without loss or a {@link java.util.ConcurrentModificationException}, and hands out
 * defensive snapshot copies. Each test uses a fresh, non-shared instance (the
 * package-private constructor is reachable via the Fragment-Host classloader).
 */
public class McpCallHistoryTest
{
    @Test
    public void testRingEvictsOldestAtCapacity()
    {
        McpCallHistory history = new McpCallHistory();
        history.setBufferSize(3);

        for (int i = 0; i < 5; i++)
        {
            history.record("tools/call", "t" + i, "r" + i, null, i); //$NON-NLS-1$
        }

        List<McpCallRecord> snapshot = history.snapshot();
        assertEquals("ring must be capped at the buffer size", 3, snapshot.size());
        assertEquals("size() must agree with the snapshot", 3, history.size());
        // Oldest-first: r0 and r1 evicted, r2..r4 retained in order.
        assertEquals("oldest surviving record", "r2", snapshot.get(0).getRequestJson());
        assertEquals("middle record", "r3", snapshot.get(1).getRequestJson());
        assertEquals("newest record", "r4", snapshot.get(2).getRequestJson());
    }

    @Test
    public void testShrinkingBufferSizeTrimsImmediately()
    {
        McpCallHistory history = new McpCallHistory();
        history.setBufferSize(10);
        for (int i = 0; i < 6; i++)
        {
            history.record("tools/call", null, "r" + i, null, i); //$NON-NLS-1$
        }
        assertEquals(6, history.size());

        history.setBufferSize(2);
        assertEquals("shrinking must trim the ring immediately", 2, history.size());
        List<McpCallRecord> snapshot = history.snapshot();
        assertEquals("only the two newest survive a shrink", "r4", snapshot.get(0).getRequestJson());
        assertEquals("r5", snapshot.get(1).getRequestJson());
    }

    @Test
    public void testOversizedPayloadIsTruncatedWithMarker()
    {
        McpCallHistory history = new McpCallHistory();
        String big = "x".repeat(McpCallHistory.MAX_PAYLOAD_CHARS + 500);

        history.record("tools/call", "big", big, big, 1L); //$NON-NLS-1$

        McpCallRecord record = history.snapshot().get(0);
        String storedRequest = record.getRequestJson();
        assertTrue("truncated payload must carry the marker",
            storedRequest.contains(McpCallHistory.TRUNCATION_MARKER));
        assertTrue("truncated payload must start with the original prefix",
            storedRequest.startsWith("x".repeat(McpCallHistory.MAX_PAYLOAD_CHARS)));
        assertTrue("truncated payload must be bounded near the cap",
            storedRequest.length() < McpCallHistory.MAX_PAYLOAD_CHARS + 100);
        // The response payload is capped by the same rule.
        assertTrue("response payload must also be truncated",
            record.getResponseJson().contains(McpCallHistory.TRUNCATION_MARKER));
    }

    @Test
    public void testShortPayloadIsStoredVerbatim()
    {
        // A within-cap payload must not be marked/rebuilt: capPayload returns the
        // same reference so recording a small body is allocation-free.
        String small = "{\"ok\":true}";
        assertSame("a short payload must be returned unchanged",
            small, McpCallHistory.capPayload(small));
        assertSame("a null payload must be tolerated", null, McpCallHistory.capPayload(null));
    }

    @Test
    public void testSnapshotIsADefensiveCopy()
    {
        McpCallHistory history = new McpCallHistory();
        history.record("tools/call", "a", "r0", null, 0L); //$NON-NLS-1$
        history.record("tools/call", "b", "r1", null, 0L); //$NON-NLS-1$

        List<McpCallRecord> snapshot = history.snapshot();
        assertEquals(2, snapshot.size());

        // Mutating the ring afterwards must not change an already-taken snapshot.
        history.record("tools/call", "c", "r2", null, 0L); //$NON-NLS-1$
        assertEquals("earlier snapshot must be isolated from later records", 2, snapshot.size());
        assertEquals("the live ring did grow", 3, history.size());
    }

    @Test
    public void testRecordNeverThrowsOnNullArguments()
    {
        McpCallHistory history = new McpCallHistory();
        // All-null arguments (a notification with no response, no tool) must be
        // recorded without throwing to the caller.
        history.record(null, null, null, null, 0L);
        assertEquals(1, history.size());
        McpCallRecord record = history.snapshot().get(0);
        assertEquals("null payloads stored verbatim", null, record.getRequestJson());
    }

    @Test
    public void testDisabledRecordingIsANoOp()
    {
        McpCallHistory history = new McpCallHistory();
        history.setRecordingEnabled(false);
        history.record("tools/call", "t", "r", null, 0L); //$NON-NLS-1$
        assertEquals("disabled recording must not append", 0, history.size());
        assertFalse(history.isRecordingEnabled());

        history.setRecordingEnabled(true);
        history.record("tools/call", "t", "r", null, 0L); //$NON-NLS-1$
        assertEquals("re-enabled recording appends again", 1, history.size());
    }

    @Test
    public void testApplyPreferencesTracksTheStore()
    {
        // Regression guard for the dead-preferences blocker: the recorder must follow
        // the preference store, so the "Record" toggle and "History size" are not inert.
        // applyPreferences is the single production seam McpServerStartup drives at
        // startup and HistoryTab.performOk drives on every preference change.
        McpCallHistory history = new McpCallHistory();
        PreferenceStore store = new PreferenceStore();
        HistoryConfig.initializeDefaults(store);

        history.applyPreferences(store);
        assertTrue("recording must follow the store default (ON)", history.isRecordingEnabled());
        assertEquals("buffer size must follow the store default",
            HistoryConfig.DEFAULT_BUFFER_SIZE, history.getBufferSize());

        // Unchecking 'Record MCP request/response history' must actually turn it off.
        store.setValue(HistoryConfig.KEY_RECORD, false);
        history.applyPreferences(store);
        assertFalse("unchecking 'Record' must disable recording", history.isRecordingEnabled());

        // Re-checking it must turn recording back on.
        store.setValue(HistoryConfig.KEY_RECORD, true);
        history.applyPreferences(store);
        assertTrue("re-checking 'Record' must re-enable recording", history.isRecordingEnabled());

        // Changing 'History size' must resize the ring, not stay pinned at the default.
        store.setValue(HistoryConfig.KEY_BUFFER_SIZE, 7);
        history.applyPreferences(store);
        assertEquals("history size must follow the pref", 7, history.getBufferSize());

        // A null store (shutdown race / headless) falls back to the documented defaults.
        history.applyPreferences(null);
        assertTrue("a null store falls back to the record default", history.isRecordingEnabled());
        assertEquals("a null store falls back to the size default",
            HistoryConfig.DEFAULT_BUFFER_SIZE, history.getBufferSize());
    }

    @Test
    public void testListenerIsFiredAndAFaultyListenerCannotBreakRecording()
    {
        McpCallHistory history = new McpCallHistory();
        AtomicInteger fired = new AtomicInteger();
        // A faulty listener throwing must be swallowed so recording keeps working.
        history.addListener(record -> { throw new IllegalStateException("boom"); });
        history.addListener(record -> fired.incrementAndGet());

        history.record("tools/call", "t", "r", null, 0L); //$NON-NLS-1$

        assertEquals("the good listener still fires despite a faulty peer", 1, fired.get());
        assertEquals("the record was appended despite the faulty listener", 1, history.size());
    }

    @Test
    public void testConcurrentRecordHasNoLossAndNoConcurrentModification() throws Exception
    {
        McpCallHistory history = new McpCallHistory();
        int threads = 8;
        int perThread = 500;
        int total = threads * perThread;
        history.setBufferSize(total); // large enough that nothing is evicted

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();

        // A concurrent reader that keeps taking + iterating snapshots. A shared
        // ring would throw ConcurrentModificationException here; a defensive copy
        // must not.
        AtomicBoolean stopReader = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            try
            {
                while (!stopReader.get())
                {
                    long sum = 0;
                    for (McpCallRecord record : history.snapshot())
                    {
                        sum += record.getDurationMs();
                    }
                    // Touch the sum so the loop is not optimized away.
                    if (sum < 0)
                    {
                        failures.add(new AssertionError("impossible negative sum"));
                    }
                }
            }
            catch (RuntimeException e)
            {
                failures.add(e);
            }
        }, "history-reader");
        reader.start();

        for (int t = 0; t < threads; t++)
        {
            final int base = t * perThread;
            new Thread(() -> {
                try
                {
                    start.await();
                    for (int i = 0; i < perThread; i++)
                    {
                        history.record("tools/call", "t", "r" + (base + i), null, base + i); //$NON-NLS-1$
                    }
                }
                catch (Throwable e)
                {
                    failures.add(e);
                }
                finally
                {
                    done.countDown();
                }
            }, "history-producer-" + t).start();
        }

        start.countDown();
        assertTrue("all producers must finish", done.await(30, TimeUnit.SECONDS));
        stopReader.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(10));

        assertTrue("no producer/reader failure: " + failures, failures.isEmpty());
        assertEquals("every concurrently recorded exchange must be retained (no loss)",
            total, history.size());
    }
}
