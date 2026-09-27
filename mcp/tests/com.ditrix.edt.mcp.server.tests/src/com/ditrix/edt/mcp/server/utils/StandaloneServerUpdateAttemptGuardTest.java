/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.AttemptRefusedException;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.Recovery;
import com.e1c.g5.dt.applications.ApplicationUpdateState;

/**
 * The pre-attempt guard of {@code StandaloneServerStateRecovery.updateWithRecovery} (#459): it runs
 * before EVERY update attempt, the stale-server retry included, and a refusal is never retried.
 */
public class StandaloneServerUpdateAttemptGuardTest
{
    private static final String STALE =
        "Can only start server that is stopped but current server state is STARTED"; //$NON-NLS-1$

    @Test
    public void testGuardRefusalBeforeTheFirstAttemptRunsNoUpdate()
    {
        AtomicInteger updates = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.attemptUpdates(() -> {
                updates.incrementAndGet();
                return ApplicationUpdateState.UPDATED;
            }, () -> "refused", refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a guard refusal must throw"); //$NON-NLS-1$
        }
        catch (AttemptRefusedException e)
        {
            assertEquals("refused", e.getMessage()); //$NON-NLS-1$
        }
        assertEquals(0, updates.get());
    }

    @Test
    public void testGuardRunsAgainBeforeTheRecoveryRetryAndRefusesIt()
    {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.attemptUpdates(() -> {
                updates.incrementAndGet();
                throw new IllegalStateException(STALE);
            }, () -> checks.incrementAndGet() == 1 ? null : "branch switched", //$NON-NLS-1$
                refusal -> {
                    recoveries.incrementAndGet();
                    return Recovery.stopped();
                }, "app"); //$NON-NLS-1$
            fail("the retry must be refused"); //$NON-NLS-1$
        }
        catch (AttemptRefusedException e)
        {
            assertEquals("branch switched", e.getMessage()); //$NON-NLS-1$
        }
        assertEquals("the guard runs before both attempts", 2, checks.get()); //$NON-NLS-1$
        assertEquals("the refused retry never reaches the update", 1, updates.get()); //$NON-NLS-1$
        assertEquals(1, recoveries.get());
    }

    @Test
    public void testPassingGuardLetsTheRetrySucceed()
    {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        ApplicationUpdateState state = StandaloneServerStateRecovery.attemptUpdates(() -> {
            if (updates.incrementAndGet() == 1)
            {
                throw new IllegalStateException(STALE);
            }
            return ApplicationUpdateState.UPDATED;
        }, () -> {
            checks.incrementAndGet();
            return null;
        }, refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$
        assertSame(ApplicationUpdateState.UPDATED, state);
        assertEquals(2, checks.get());
        assertEquals(2, updates.get());
    }

    @Test
    public void testNullGuardKeepsTheUnguardedBehaviour()
    {
        ApplicationUpdateState state = StandaloneServerStateRecovery.attemptUpdates(
            () -> ApplicationUpdateState.UPDATED, null, refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$
        assertSame(ApplicationUpdateState.UPDATED, state);
    }

    private static final class RecordingGuard implements StandaloneServerStateRecovery.AttemptGuard
    {
        final AtomicInteger entered = new AtomicInteger();
        final java.util.function.IntFunction<String> answer;
        final AtomicInteger checks = new AtomicInteger();

        RecordingGuard(java.util.function.IntFunction<String> answer)
        {
            this.answer = answer;
        }

        @Override
        public String refusalOrNull()
        {
            return answer.apply(checks.incrementAndGet());
        }

        @Override
        public void updateEntered()
        {
            entered.incrementAndGet();
        }
    }

    @Test
    public void testUpdateEnteredIsReportedOnlyForAttemptsThatReachTheUpdate()
    {
        RecordingGuard guard = new RecordingGuard(n -> n == 1 ? null : "branch switched"); //$NON-NLS-1$
        AtomicInteger updates = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.attemptUpdates(() -> {
                updates.incrementAndGet();
                throw new IllegalStateException(STALE);
            }, guard, refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$
            fail("the retry must be refused"); //$NON-NLS-1$
        }
        catch (AttemptRefusedException expected)
        {
            // refused before the second attempt
        }
        assertEquals(1, updates.get());
        assertEquals("only the attempt that ran is reported as entered", 1, guard.entered.get()); //$NON-NLS-1$
    }

    @Test
    public void testRefusalBeforeTheFirstAttemptReportsNothingEntered()
    {
        RecordingGuard guard = new RecordingGuard(n -> "refused"); //$NON-NLS-1$
        try
        {
            StandaloneServerStateRecovery.attemptUpdates(() -> ApplicationUpdateState.UPDATED, guard,
                refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$
            fail("must refuse"); //$NON-NLS-1$
        }
        catch (AttemptRefusedException expected)
        {
            // refused
        }
        assertEquals(0, guard.entered.get());
    }

    @Test
    public void testAThrowingGuardOnTheRetryIsARefusalNotAnEscapingFailure()
    {
        RecordingGuard guard = new RecordingGuard(n -> {
            if (n == 2)
            {
                throw new IllegalStateException("store closed"); //$NON-NLS-1$
            }
            return null;
        });
        AtomicInteger updates = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.attemptUpdates(() -> {
                updates.incrementAndGet();
                throw new IllegalStateException(STALE);
            }, guard, refusal -> Recovery.stopped(), "app"); //$NON-NLS-1$
            fail("a failing check must refuse"); //$NON-NLS-1$
        }
        catch (AttemptRefusedException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("store closed")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was updated")); //$NON-NLS-1$
        }
        assertEquals(1, updates.get());
    }
}
