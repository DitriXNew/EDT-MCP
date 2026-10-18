/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Tests {@link BranchBindingLock}, the lock {@code set_branch_infobase}'s binding changes and this server's checkouts
 * share (#656). The waiting itself is pinned where the lock is taken: {@code SetBranchInfobaseToolTest},
 * {@code GitCheckoutSupportTest} and {@code GitToolProcessIntegrationTest}.
 */
public class BranchBindingLockTest
{
    @Test
    public void theLockIsHeldByTheThreadThatTookItUntilItIsReleased() throws InterruptedException
    {
        assertFalse(BranchBindingLock.isHeldByCurrentThread());
        assertTrue(BranchBindingLock.tryLock());
        try
        {
            assertTrue(BranchBindingLock.isHeldByCurrentThread());
            AtomicBoolean heldElsewhere = new AtomicBoolean(true);
            Thread other = new Thread(() -> heldElsewhere.set(BranchBindingLock.isHeldByCurrentThread()));
            other.start();
            other.join(10_000);
            assertFalse("only the thread that took the lock holds it", heldElsewhere.get()); //$NON-NLS-1$
        }
        finally
        {
            BranchBindingLock.unlock();
        }
        assertFalse(BranchBindingLock.isHeldByCurrentThread());
    }

    @Test
    public void anInterruptedWaitReturnsFalseAndKeepsTheInterrupt()
    {
        Thread.currentThread().interrupt();
        try
        {
            assertFalse("an interrupted thread does not take the lock", BranchBindingLock.tryLock()); //$NON-NLS-1$
            assertTrue("the interrupt flag is restored", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void theBusyReasonNamesWhatHoldsTheLockAndTheWait()
    {
        assertEquals("another infobase-binding change or checkout is still running (waited " //$NON-NLS-1$
            + BranchBindingLock.WAIT_SECONDS + " s)", BranchBindingLock.busyReason()); //$NON-NLS-1$
        assertEquals(60, BranchBindingLock.WAIT_SECONDS);
    }

    // ==================== #656 round 5: this server's branch changes that outlive their wait ====================

    @Test(timeout = 60_000)
    public void aJobThatEndsWithinTheWaitIsNotRecorded() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        Thread finisher = new Thread(() -> {
            try
            {
                Thread.sleep(100);
                job.finish();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        finisher.start();
        assertTrue("the job ended within the wait", //$NON-NLS-1$
            BranchBindingLock.awaitOrTrack(job, 10_000, () -> List.of("Ended"))); //$NON-NLS-1$
        finisher.join(10_000);
        assertNull(BranchBindingLock.inFlightAmong(List.of("Ended"))); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void aJobThatOutlivesTheWaitIsRecordedForItsProjectsUntilItEnds() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        try
        {
            assertTrue(BranchBindingLock.tryLock());
            try
            {
                assertFalse("the job outlived the wait", //$NON-NLS-1$
                    BranchBindingLock.awaitOrTrack(job, 100, () -> List.of("Base", "Ext"))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                BranchBindingLock.unlock();
            }
            assertTrue(BranchBindingLock.anyInFlight());
            assertEquals("a project the job may move is named", "Ext", //$NON-NLS-1$ //$NON-NLS-2$
                BranchBindingLock.inFlightAmong(Arrays.asList(null, "Other", "Ext"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull("a project the job does not move is not", //$NON-NLS-1$
                BranchBindingLock.inFlightAmong(List.of("Other"))); //$NON-NLS-1$
        }
        finally
        {
            job.finish();
        }
        BlockingJob.awaitNotInFlight("Base"); //$NON-NLS-1$
        BlockingJob.awaitNotInFlight("Ext"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void aZeroWaitRecordsARunningJobAtOnce() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        try
        {
            assertFalse(BranchBindingLock.awaitOrTrack(job, 0, () -> List.of("ZeroWait"))); //$NON-NLS-1$
            assertEquals("ZeroWait", BranchBindingLock.inFlightAmong(List.of("ZeroWait"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            job.finish();
        }
        BlockingJob.awaitNotInFlight("ZeroWait"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void aJobWhoseProjectsCannotBeNamedIsRecordedForEveryProject() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        try
        {
            assertFalse(BranchBindingLock.awaitOrTrack(job, 0, () -> {
                throw new IllegalStateException("the workspace is closing"); //$NON-NLS-1$
            }));
            assertEquals("unknown projects fail closed", "Anything", //$NON-NLS-1$ //$NON-NLS-2$
                BranchBindingLock.inFlightAmong(List.of("Anything"))); //$NON-NLS-1$
        }
        finally
        {
            job.finish();
        }
        BlockingJob.awaitNotInFlight("Anything"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void anInterruptedWaitRecordsTheJobAndRethrows() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        try
        {
            Thread.currentThread().interrupt();
            try
            {
                BranchBindingLock.awaitOrTrack(job, 10_000, () -> List.of("Interrupted")); //$NON-NLS-1$
                fail("an interrupted wait must throw"); //$NON-NLS-1$
            }
            catch (InterruptedException expected)
            {
                // The caller restores the flag and answers 'interrupted'.
            }
            finally
            {
                Thread.interrupted();
            }
            assertEquals("Interrupted", BranchBindingLock.inFlightAmong(List.of("Interrupted"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            job.finish();
        }
        BlockingJob.awaitNotInFlight("Interrupted"); //$NON-NLS-1$
    }

    @Test
    public void theInFlightRefusalNamesTheProjectAndTheWayOut()
    {
        assertEquals("Did not detach: a branch change made by this server is still running for 'Base' - retry once " //$NON-NLS-1$
            + "it ends. It is a checkout, or the workspace refresh after one, that outlived its bounded wait; when it " //$NON-NLS-1$
            + "lands it changes the branch EDT reads, and with it what EDT's write deletes. Nothing was changed.", //$NON-NLS-1$
            BranchBindingLock.inFlightRefusal("Base")); //$NON-NLS-1$
        assertTrue(BranchBindingLock.inFlightRefusal(null).contains("still running for a project this detach could " //$NON-NLS-1$
            + "not rule out (its dependent extension projects could not be listed) - retry once it ends.")); //$NON-NLS-1$
    }
}
