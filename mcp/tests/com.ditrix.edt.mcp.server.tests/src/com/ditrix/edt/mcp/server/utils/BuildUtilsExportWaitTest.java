/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the accumulation limit on the disk-export wait (issue #406).
 * <p>
 * The platform's {@code waitComputation} takes no progress monitor, so the {@code BoundedJob}
 * deadline around it frees the CALLER but cannot stop the WAIT. Since the barrier runs after every
 * successful metadata write, a wedged pipeline would otherwise get a fresh unstoppable Job per
 * request until the workbench ran out of them. The limit is "at most one outstanding wait per
 * project at a time" - and it has two failure modes worth pinning, not one: letting too many
 * through, and shutting a project out forever.
 */
public class BuildUtilsExportWaitTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String OTHER = "OtherProject"; //$NON-NLS-1$
    private static final long DEADLINE_MS = 60_000L;

    @Before
    public void reset()
    {
        BuildUtils.forgetExportWaits();
    }

    @Test
    public void testASecondWaitIsRefusedWhileTheFirstHasNotReturned()
    {
        assertNotNull("the first wait must be allowed to start", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));

        // The first waiter has not come back - starting another would add a second Job that
        // nothing can cancel, which is exactly the pile-up being prevented.
        assertNull("a second wait must not start while the first is still outstanding", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
        assertNull("and it must keep being refused, not just the first time", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
    }

    @Test
    public void testTheSlotReopensOnceTheWaitReturns()
    {
        AtomicBoolean first = BuildUtils.beginExportWait(PROJECT, DEADLINE_MS);
        assertNotNull(first);
        assertNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));

        // This is what the job's finally-block does when the platform call finally comes back.
        first.set(true);

        AtomicBoolean second = BuildUtils.beginExportWait(PROJECT, DEADLINE_MS);
        assertNotNull("a returned wait must reopen the slot, or the barrier would refuse forever " //$NON-NLS-1$
            + "after one slow export", second); //$NON-NLS-1$
        assertNotSame("each run needs its own flag, or the next run would inherit the last verdict", //$NON-NLS-1$
            first, second);
    }

    @Test
    public void testAClaimThatIsNeverReturnedStillLapses() throws Exception
    {
        // The other failure mode. A wait that never comes back - the caller interrupted before the
        // job entered, a job the manager dropped - must not shut this project out for the rest of
        // the session. The hold is a multiple of the deadline, so a near-zero deadline lapses at
        // once; the flag is deliberately never set, which is the whole point of the case.
        AtomicBoolean abandoned = BuildUtils.beginExportWait(PROJECT, 1L);
        assertNotNull(abandoned);
        assertNull("still held while the (tiny) hold lasts", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, 1L));

        Thread.sleep(25);

        AtomicBoolean next = BuildUtils.beginExportWait(PROJECT, DEADLINE_MS);
        assertNotNull("an expired claim must not keep refusing: nothing would ever reopen it", next); //$NON-NLS-1$
        assertNotSame(abandoned, next);
    }

    @Test
    public void testTheLimitIsPerProjectAndNotGlobal()
    {
        // A stuck export in one project must not block writes to an unrelated one: the limit
        // exists to bound stuck Jobs, not to serialize the whole workspace.
        assertNotNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
        assertNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));

        assertNotNull("an unrelated project must still be allowed to wait", //$NON-NLS-1$
            BuildUtils.beginExportWait(OTHER, DEADLINE_MS));
    }

    @Test
    public void testConcurrentClaimsOnTheSameProjectYieldExactlyOneWinner() throws Exception
    {
        // The limit is only worth anything under concurrency - which is precisely when a
        // check-then-act (get() then put()) would let several callers through, i.e. it would hold
        // only when it was not needed. All threads are released together to make that overlap real.
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();

        for (int i = 0; i < threads; i++)
        {
            new Thread(() -> {
                try
                {
                    start.await(5, TimeUnit.SECONDS);
                    if (BuildUtils.beginExportWait(PROJECT, DEADLINE_MS) != null)
                    {
                        granted.incrementAndGet();
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue("the claiming threads must finish", done.await(20, TimeUnit.SECONDS)); //$NON-NLS-1$

        assertEquals("exactly one concurrent caller may start an export wait for a project", //$NON-NLS-1$
            1, granted.get());
    }
}
