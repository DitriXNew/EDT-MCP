/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.ActiveToolCall;
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.transport.ToolCallResponder;

/** The in-flight call registry behind {@code get_server_status.activeCalls} (#993). */
public class InFlightToolCallsTest
{
    private final List<InFlightToolCalls.Handle> opened = new ArrayList<>();

    @After
    public void tearDown()
    {
        for (int i = opened.size() - 1; i >= 0; i--)
        {
            opened.get(i).close();
        }
    }

    @Test
    public void testABegunCallIsListedWithItsToolAndProjectAndGoneAfterClose()
    {
        String tool = unique("tool");
        InFlightToolCalls.Handle handle = open(tool, "ProjectA", null);

        InFlightToolCalls.Snapshot listed = only(tool);
        assertEquals(tool, listed.tool());
        assertEquals("ProjectA", listed.projectName());
        assertFalse(listed.responseSent());

        handle.close();
        assertTrue("a closed call must not be listed", named(tool).isEmpty());
    }

    @Test
    public void testASecondCloseIsANoOpAndDoesNotRemoveAnotherCall()
    {
        String tool = unique("tool");
        InFlightToolCalls.Handle first = open(tool, null, null);
        open(tool, null, null);
        first.close();
        first.close();
        assertEquals("the second close of the first call must leave the other call listed", 1,
            named(tool).size());
    }

    @Test
    public void testTheExcludedRegistrationIsLeftOutAndOthersKept()
    {
        String tool = unique("tool");
        InFlightToolCalls.Handle own = open(tool, "Own", null);
        open(tool, "Other", null);

        List<InFlightToolCalls.Snapshot> listed = named(tool, InFlightToolCalls.report(own));
        assertEquals(1, listed.size());
        assertEquals("Other", listed.get(0).projectName());
    }

    @Test
    public void testCurrentIsTheCallOfThisThreadAndNestingRestoresTheOuterOne()
    {
        assertNull("no call executes on the test thread before begin", InFlightToolCalls.current());
        InFlightToolCalls.Handle outer = open(unique("outer"), null, null);
        assertSame(outer, InFlightToolCalls.current());
        InFlightToolCalls.Handle inner = open(unique("inner"), null, null);
        assertSame(inner, InFlightToolCalls.current());
        inner.close();
        assertSame("closing a nested call restores the outer one", outer, InFlightToolCalls.current());
        outer.close();
        assertNull("closing the last call leaves nothing bound to the thread", InFlightToolCalls.current());
    }

    @Test
    public void testAnOuterCallClosedFirstIsNeverRestored()
    {
        InFlightToolCalls.Handle outer = open(unique("outer"), null, null);
        InFlightToolCalls.Handle inner = open(unique("inner"), null, null);
        outer.close();
        assertSame("closing a call that is not the current one leaves the current one", inner,
            InFlightToolCalls.current());
        inner.close();
        assertNull("a closed outer call is skipped, not made current again", InFlightToolCalls.current());
    }

    @Test
    public void testACallOnAnotherThreadIsNotTheCurrentOneOfThisThread() throws Exception
    {
        String tool = unique("tool");
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<InFlightToolCalls.Handle> other = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (InFlightToolCalls.Handle handle = InFlightToolCalls.begin(tool, null, null))
            {
                other.set(handle);
                registered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        worker.setDaemon(true);
        worker.start();
        try
        {
            assertTrue(registered.await(10, TimeUnit.SECONDS));
            assertNull(InFlightToolCalls.current());
            assertEquals("a reader on another thread sees the running call", 1,
                named(tool, InFlightToolCalls.report(InFlightToolCalls.current())).size());
        }
        finally
        {
            release.countDown();
            worker.join(10_000L);
        }
        assertTrue("the worker's close removed its call", named(tool).isEmpty());
    }

    @Test
    public void testCallsAreListedOldestFirst()
    {
        String tool = unique("tool");
        open(tool, "first", null);
        open(tool, "second", null);
        open(tool, "third", null);
        List<InFlightToolCalls.Snapshot> listed = named(tool);
        assertEquals(List.of("first", "second", "third"),
            listed.stream().map(InFlightToolCalls.Snapshot::projectName).toList());
    }

    @Test
    public void testABlankProjectNameIsOmittedAndALongOneIsCut()
    {
        assertNull(InFlightToolCalls.normalizeProjectName(null));
        assertNull(InFlightToolCalls.normalizeProjectName("   "));
        assertEquals("P", InFlightToolCalls.normalizeProjectName("  P  "));
        String longName = "x".repeat(InFlightToolCalls.MAX_PROJECT_NAME_CHARS + 50);
        assertEquals(InFlightToolCalls.MAX_PROJECT_NAME_CHARS,
            InFlightToolCalls.normalizeProjectName(longName).length());

        String tool = unique("tool");
        open(tool, " ", null);
        assertNull("a blank projectName is not reported", only(tool).projectName());
    }

    @Test
    public void testTheCutNeverSplitsASurrogatePair()
    {
        String prefix = "y".repeat(InFlightToolCalls.MAX_PROJECT_NAME_CHARS - 1);
        String cut = InFlightToolCalls.normalizeProjectName(prefix + "😀tail");
        assertEquals(prefix, cut);
    }

    @Test
    public void testResponseSentFollowsTheInvocation()
    {
        String tool = unique("tool");
        ActiveToolCall call = new ActiveToolCall(new ToolCallResponder()
        {
            @Override
            public void send(String response)
            {
                // the signal response goes nowhere in a unit test
            }

            @Override
            public void close()
            {
                // nothing to close
            }
        }, tool, 1, null);
        open(tool, null, call);
        assertFalse("before any response", only(tool).responseSent());
        assertTrue(call.sendSignalResponse(new UserSignal(UserSignal.SignalType.CANCEL, "stop")));
        assertTrue("a call whose client already has an answer still runs and says so",
            only(tool).responseSent());
    }

    @Test
    public void testAFailedInterruptWriteIsNotReportedAsSent()
    {
        String tool = unique("tool");
        ActiveToolCall call = new ActiveToolCall(response -> {
            throw new java.io.IOException("client gone"); //$NON-NLS-1$
        }, tool, 1, null);
        open(tool, null, call);
        assertFalse("the write failed", call.sendSignalResponse(new UserSignal(UserSignal.SignalType.CANCEL, "stop"))); //$NON-NLS-1$
        assertTrue("a response was selected", call.hasResponded());
        assertFalse("but the client has no answer, so the entry must not claim one",
            only(tool).responseSent());
    }

    @Test
    public void testTheReadersOwnUntrackedCallIsNotCounted()
    {
        String tool = unique("tool");
        int baseline = InFlightToolCalls.report(null).untracked();
        int room = InFlightToolCalls.MAX_TRACKED - InFlightToolCalls.report(null).calls().size();
        for (int i = 0; i < room; i++)
        {
            open(tool, null, null);
        }
        InFlightToolCalls.Handle own = open(unique("reader"), null, null);
        assertEquals("the reader's call went over the cap", baseline + 1, InFlightToolCalls.report(null).untracked());
        assertEquals("excluding the reader's own untracked call leaves only the others", baseline,
            InFlightToolCalls.report(own).untracked());
        assertEquals("excluding a listed call does not touch the count", baseline + 1,
            InFlightToolCalls.report(opened.get(0)).untracked());
    }

    @Test
    public void testElapsedGrows()
    {
        String tool = unique("tool");
        open(tool, null, null);
        long first = only(tool).elapsedMs();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long second = first;
        while (second < first + 20 && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
            second = only(tool).elapsedMs();
        }
        assertTrue("elapsedMs must grow while the call runs: " + first + " -> " + second, second >= first + 20);
        long started = only(tool).startedAtMs();
        assertTrue("startedAtMs is wall-clock epoch milliseconds",
            Math.abs(System.currentTimeMillis() - started) < 60_000L);
    }

    @Test
    public void testAFullRegistryCountsTheCallInsteadOfListingIt()
    {
        String tool = unique("tool");
        int baseline = InFlightToolCalls.report(null).untracked();
        int listedBefore = InFlightToolCalls.report(null).calls().size();
        int room = InFlightToolCalls.MAX_TRACKED - listedBefore;
        for (int i = 0; i < room; i++)
        {
            open(tool, null, null);
        }
        assertEquals(InFlightToolCalls.MAX_TRACKED, InFlightToolCalls.report(null).calls().size());

        InFlightToolCalls.Handle overflow = open(tool, "over", null);
        InFlightToolCalls.Report full = InFlightToolCalls.report(null);
        assertEquals("the registry never holds more than its cap", InFlightToolCalls.MAX_TRACKED,
            full.calls().size());
        assertEquals("the call over the cap is counted", baseline + 1, full.untracked());
        assertTrue(full.calls().stream().noneMatch(c -> "over".equals(c.projectName())));

        overflow.close();
        overflow.close();
        assertEquals("closing the counted call takes it back exactly once", baseline,
            InFlightToolCalls.report(null).untracked());
    }

    private InFlightToolCalls.Handle open(String tool, String projectName, ActiveToolCall call)
    {
        InFlightToolCalls.Handle handle = InFlightToolCalls.begin(tool, projectName, call);
        opened.add(handle);
        return handle;
    }

    private static String unique(String prefix)
    {
        return prefix + "_" + UUID.randomUUID();
    }

    private static InFlightToolCalls.Snapshot only(String tool)
    {
        List<InFlightToolCalls.Snapshot> listed = named(tool);
        assertEquals("expected exactly one listed call of " + tool, 1, listed.size());
        return listed.get(0);
    }

    private static List<InFlightToolCalls.Snapshot> named(String tool)
    {
        return named(tool, InFlightToolCalls.report(null));
    }

    private static List<InFlightToolCalls.Snapshot> named(String tool, InFlightToolCalls.Report report)
    {
        return report.calls().stream().filter(c -> tool.equals(c.tool())).toList();
    }
}
