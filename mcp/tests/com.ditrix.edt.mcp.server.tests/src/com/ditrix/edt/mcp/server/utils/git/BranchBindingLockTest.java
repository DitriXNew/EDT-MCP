/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
