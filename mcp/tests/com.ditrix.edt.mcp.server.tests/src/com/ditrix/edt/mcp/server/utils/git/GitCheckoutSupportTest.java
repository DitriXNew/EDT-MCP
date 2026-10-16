/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.api.CheckoutResult;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.git.GitCheckoutSupport.CheckoutOutcome;

/**
 * Tests {@link CheckoutOutcome}: the pure value object carrying a bounded checkout's result. Built
 * directly via the package-private test factory {@link CheckoutOutcome#forTest} (added for exactly this
 * purpose - issue #171 coverage), bypassing {@link GitCheckoutSupport#checkout} entirely, so NO {@link
 * org.eclipse.core.runtime.jobs.Job} and NO live checkout is ever run here - only the accessor/derived-flag
 * logic on already-built outcomes.
 */
public class GitCheckoutSupportTest
{
    @Test
    public void testRanToCompletionOutcomeExposesResultAndOptionalRefreshWarning()
    {
        CheckoutOutcome outcome =
            CheckoutOutcome.forTest(CheckoutResult.NOT_TRIED_RESULT, "workspace refresh failed", false, false, null); //$NON-NLS-1$

        assertTrue("a normal outcome ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertSame("the result must round-trip unchanged", CheckoutResult.NOT_TRIED_RESULT, outcome.result()); //$NON-NLS-1$
        assertEquals("workspace refresh failed", outcome.refreshWarning()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertNull(outcome.jobError());
    }

    @Test
    public void testRanToCompletionOutcomeWithNoRefreshWarningIsNull()
    {
        CheckoutOutcome outcome = CheckoutOutcome.forTest(CheckoutResult.ERROR_RESULT, null, false, false, null);

        assertTrue(outcome.ranToCompletion());
        assertSame(CheckoutResult.ERROR_RESULT, outcome.result());
        assertNull("no refresh warning means no refresh failure occurred", outcome.refreshWarning()); //$NON-NLS-1$
    }

    @Test
    public void testTimedOutOutcomeDidNotRunToCompletionAndCarriesNoResult()
    {
        CheckoutOutcome outcome = CheckoutOutcome.forTest(null, null, true, false, null);

        assertFalse("a timed-out Job never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertTrue(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertNull(outcome.jobError());
        assertNull("no CheckoutResult is available when the Job timed out", outcome.result()); //$NON-NLS-1$
    }

    @Test
    public void testInterruptedOutcomeDidNotRunToCompletion()
    {
        CheckoutOutcome outcome = CheckoutOutcome.forTest(null, null, false, true, null);

        assertFalse("an interrupted wait never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertTrue(outcome.interrupted());
        assertNull(outcome.jobError());
    }

    @Test
    public void testJobErrorOutcomeDidNotRunToCompletionAndCarriesTheException()
    {
        Exception boom = new IllegalStateException("checkout blew up"); //$NON-NLS-1$
        CheckoutOutcome outcome = CheckoutOutcome.forTest(null, null, false, false, boom);

        assertFalse("a Job that threw never ran to completion", outcome.ranToCompletion()); //$NON-NLS-1$
        assertFalse(outcome.timedOut());
        assertFalse(outcome.interrupted());
        assertSame("the thrown exception must round-trip unchanged", boom, outcome.jobError()); //$NON-NLS-1$
    }

    /**
     * A checkout takes {@link BranchBindingLock}, the lock {@code set_branch_infobase}'s binding changes hold (#656):
     * while another thread holds it, the checkout waits instead of running. Interrupting the wait ends it before
     * anything is built, so no checkout Job ever runs here (the repository is {@code null}: a checkout that did not
     * wait would fail at once instead of still waiting).
     */
    @Test(timeout = 60_000)
    public void aCheckoutWaitsWhileABindingChangeHoldsTheSharedLock() throws Exception
    {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread binding = new Thread(() -> {
            if (BranchBindingLock.tryLock())
            {
                try
                {
                    held.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    BranchBindingLock.unlock();
                }
            }
        });
        binding.start();
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread checkout = new Thread(() -> {
            try
            {
                outcome.set(GitCheckoutSupport.checkout(null, null, "refs/heads/feature/x")); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                outcome.set(e);
            }
        });
        try
        {
            assertTrue("the binding change must take the lock", held.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            checkout.start();
            checkout.join(500);
            assertTrue("the checkout must wait while a binding change holds the lock: " + outcome.get(), //$NON-NLS-1$
                checkout.isAlive());
            checkout.interrupt();
            checkout.join(10_000);
            assertFalse(checkout.isAlive());
            assertTrue("an interrupted wait is reported as such: " + outcome.get(), //$NON-NLS-1$
                outcome.get() instanceof CheckoutOutcome && ((CheckoutOutcome)outcome.get()).interrupted());
            assertFalse(((CheckoutOutcome)outcome.get()).busy());
            assertFalse(((CheckoutOutcome)outcome.get()).ranToCompletion());
        }
        finally
        {
            release.countDown();
            checkout.interrupt();
            binding.join(10_000);
        }
    }
}
