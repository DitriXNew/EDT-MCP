/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;

/**
 * Tests {@link UpdateWatchdog#runWithTimeout} via injected {@link java.util.concurrent.Callable}s,
 * so the timing/unwrapping logic is verified without a live EDT.
 */
public class UpdateWatchdogTest
{
    @Test
    public void testFastCallReturnsItsState() throws Exception
    {
        ApplicationUpdateState state = UpdateWatchdog.runWithTimeout(
            () -> ApplicationUpdateState.UPDATED, 5);
        assertEquals(ApplicationUpdateState.UPDATED, state);
    }

    @Test
    public void testTimeoutReturnsBeingUpdated() throws Exception
    {
        // The callable blocks well past the 1s window; the watchdog must return
        // BEING_UPDATED rather than wait for it. The abandoned daemon thread is
        // harmless (it just sleeps out).
        ApplicationUpdateState state = UpdateWatchdog.runWithTimeout(() -> {
            Thread.sleep(5000L);
            return ApplicationUpdateState.UPDATED;
        }, 1);
        assertEquals(ApplicationUpdateState.BEING_UPDATED, state);
    }

    @Test
    public void testApplicationExceptionIsRethrown() throws Exception
    {
        ApplicationException boom = new ApplicationException("update boom");
        try
        {
            UpdateWatchdog.runWithTimeout(() -> {
                throw boom;
            }, 5);
            fail("expected the ApplicationException to propagate");
        }
        catch (ApplicationException e)
        {
            assertSame("the original ApplicationException must propagate unwrapped", boom, e);
        }
    }

    @Test
    public void testRuntimeExceptionIsRethrown() throws Exception
    {
        RuntimeException boom = new IllegalStateException("kaboom");
        try
        {
            UpdateWatchdog.runWithTimeout(() -> {
                throw boom;
            }, 5);
            fail("expected the RuntimeException to propagate");
        }
        catch (RuntimeException e)
        {
            assertSame("the original RuntimeException must propagate unwrapped", boom, e);
        }
    }

    @Test
    public void testOnCompleteRunsBeforeFastReturn() throws Exception
    {
        // On a fast update, onComplete (the guard's disarm) must have run by the time
        // the value is returned — the task's finally executes before the future completes.
        CountDownLatch completed = new CountDownLatch(1);
        ApplicationUpdateState state = UpdateWatchdog.runWithTimeout(
            () -> ApplicationUpdateState.UPDATED, 5, null, completed::countDown);
        assertEquals(ApplicationUpdateState.UPDATED, state);
        assertEquals("onComplete must have run by the time a fast update returns",
            0, completed.getCount());
    }

    @Test
    public void testOnCompleteRunsWhenUpdateFinishesEvenAfterTimeout() throws Exception
    {
        // The fix for finding #3: the guard must stay armed until the update TRULY
        // finishes. onStart runs up front; runWithTimeout returns BEING_UPDATED at the
        // 1s timeout while the 1.5s update is still running (onComplete not yet fired);
        // onComplete fires only once the background update completes.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        ApplicationUpdateState state = UpdateWatchdog.runWithTimeout(() -> {
            Thread.sleep(1500L);
            return ApplicationUpdateState.UPDATED;
        }, 1, started::countDown, completed::countDown);

        assertEquals("a slow update must time out to BEING_UPDATED",
            ApplicationUpdateState.BEING_UPDATED, state);
        assertEquals("onStart must have run before the update started", 0, started.getCount());
        assertEquals("onComplete must NOT have run yet — the update is still in the background",
            1, completed.getCount());
        assertTrue("onComplete must fire once the background update finishes",
            completed.await(3, TimeUnit.SECONDS));
    }
}
