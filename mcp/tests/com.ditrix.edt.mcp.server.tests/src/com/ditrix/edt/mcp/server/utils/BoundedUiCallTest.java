/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class BoundedUiCallTest
{
    private static class Queue implements BoundedUiCall.UiExecutor
    {
        Runnable queued;

        @Override
        public boolean isCurrentThread()
        {
            return false;
        }

        @Override
        public void asyncExec(Runnable work)
        {
            queued = work;
        }
    }

    @Test
    public void cancelsQueuedWorkOnTimeout() throws Exception
    {
        Queue queue = new Queue();
        AtomicBoolean opened = new AtomicBoolean();
        TimeoutException failure = assertThrows(TimeoutException.class,
            () -> BoundedUiCall.call(queue, active -> { opened.set(true); return null; }, 20));
        assertTrue(failure.getMessage().contains("Check EDT before retrying"));
        queue.queued.run();
        assertFalse("Late UI dispatch must not open anything", opened.get());
    }

    @Test
    public void returnsUiResult() throws Exception
    {
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                work.run();
            }
        };
        assertEquals("opened", BoundedUiCall.call(queue, active -> {
            assertTrue(active.getAsBoolean());
            return "opened";
        }, 1000));
    }

    @Test
    public void propagatesUiFailure() throws Exception
    {
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                work.run();
            }
        };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> BoundedUiCall.call(queue, active -> { throw new IllegalArgumentException("bad FQN"); }, 1000));
        assertEquals("bad FQN", failure.getMessage());
    }

    @Test
    public void refusesUiThreadInsteadOfBlockingIt() throws Exception
    {
        Queue queue = new Queue()
        {
            @Override
            public boolean isCurrentThread()
            {
                return true;
            }
        };
        assertThrows(IllegalStateException.class,
            () -> BoundedUiCall.call(queue, active -> failOperation(), 1000));
        assertNull(queue.queued);
    }

    private static Void failOperation()
    {
        fail("Operation must not run");
        return null;
    }

    @Test
    public void interruptionCancelsQueuedWorkAndPreservesFlag() throws Exception
    {
        Queue queue = new Queue();
        try
        {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,
                () -> BoundedUiCall.call(queue, active -> failOperation(), 1000));
            assertTrue(Thread.currentThread().isInterrupted());
            queue.queued.run();
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void runningWorkObservesExpiryWithoutInterruptingUi() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean activeAfterTimeout = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                Thread thread = new Thread(work, "fake-edt-ui");
                worker.set(thread);
                thread.start();
                try
                {
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        try
        {
            assertThrows(TimeoutException.class, () -> BoundedUiCall.call(queue, active -> {
                started.countDown();
                try
                {
                    release.await(2, TimeUnit.SECONDS);
                    activeAfterTimeout.set(active.getAsBoolean());
                    interrupted.set(Thread.currentThread().isInterrupted());
                    return null;
                }
                finally
                {
                    finished.countDown();
                }
            }, 50));
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            worker.get().join(1000);
        }
        assertFalse(activeAfterTimeout.get());
        assertFalse(interrupted.get());
    }

    @Test
    public void headlessRefusesWithoutCreatingDisplay() throws Exception
    {
        assertNull(LaunchLifecycleUtils.workbenchDisplayOrNull());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> BoundedUiCall.call(active -> failOperation(), 1000));
        assertTrue(failure.getMessage().contains("headless"));
        assertTrue(failure.getMessage().contains("Start EDT with a workbench"));
    }

    @Test
    public void sharedDispatchAnswersInlineEvenWithZeroBudget()
    {
        Queue queue = new Queue()
        {
            @Override
            public boolean isCurrentThread()
            {
                return true;
            }
        };
        AtomicBoolean ran = new AtomicBoolean();
        BoundedUiCall.Dispatch result = BoundedUiCall.dispatch(queue, () -> ran.set(true),
            0, BoundedUiCall.UiThreadPolicy.RUN_INLINE);
        assertEquals(BoundedUiCall.Outcome.COMPLETED, result.outcome());
        assertTrue(ran.get());
        assertNull(queue.queued);
    }

    @Test
    public void sharedDispatchDistinguishesMissingUiAndFailedSubmission()
    {
        assertEquals(BoundedUiCall.Outcome.NO_UI,
            BoundedUiCall.dispatch(null, () -> failOperation(), 1000,
                BoundedUiCall.UiThreadPolicy.RUN_INLINE).outcome());
        RuntimeException failure = new IllegalStateException("disposed display");
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                throw failure;
            }
        };
        BoundedUiCall.Dispatch result = BoundedUiCall.dispatch(queue, () -> failOperation(),
            1000, BoundedUiCall.UiThreadPolicy.RUN_INLINE);
        assertEquals(BoundedUiCall.Outcome.SUBMISSION_FAILED, result.outcome());
        assertSame(failure, result.failure());
    }

    @Test
    public void sharedRunRetainsLateWorkAfterZeroWait()
    {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();
        assertFalse(BoundedUiCall.run(queued::set, () -> ran.set(true), 0));
        assertNotNull(queued.get());
        assertFalse(ran.get());
        queued.get().run();
        assertTrue(ran.get());
    }

    @Test
    public void sharedSubmissionSignalsCompletionAndPreservesWorkFailure()
    {
        CountDownLatch completed = new CountDownLatch(1);
        RuntimeException failure = new IllegalArgumentException("UI work failed");
        RuntimeException caught = assertThrows(RuntimeException.class,
            () -> BoundedUiCall.submit(Runnable::run, () -> { throw failure; }, completed));
        assertSame(failure, caught);
        assertEquals(0, completed.getCount());
    }

    @Test
    public void sharedWaitSupportsCallerOwnedInterruptionDecision()
    {
        try
        {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,
                () -> BoundedUiCall.awaitInterruptibly(new CountDownLatch(1), 1000));
            assertFalse("The caller, such as Workmate's commit arbiter, owns restoring the flag",
                Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }
}
