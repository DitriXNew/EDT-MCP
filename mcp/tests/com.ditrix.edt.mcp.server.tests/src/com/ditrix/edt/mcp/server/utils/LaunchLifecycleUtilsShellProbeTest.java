/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Shell;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ShellProbe;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ShellProbeOutcome;

/** Exercises the production shell-probe adapter against the shared dispatcher without an SWT event loop. */
public class LaunchLifecycleUtilsShellProbeTest
{
    private static class Queue implements BoundedUiCall.UiExecutor
    {
        boolean inline;
        Runnable queued;

        @Override
        public boolean isCurrentThread()
        {
            return inline;
        }

        @Override
        public void asyncExec(Runnable work)
        {
            queued = work;
        }
    }

    @Test
    public void missingUiNeverRunsTheRead()
    {
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(null,
            () -> { fail("Headless probe must not read SWT"); return null; }, 30_000);
        assertEquals(ShellProbeOutcome.NO_SHELL, probe.outcome());
        assertNull(probe.shell());
    }

    @Test
    public void inlineShellIsReturnedEvenWithNoBudget()
    {
        Queue queue = new Queue();
        queue.inline = true;
        Shell shell = mock(Shell.class);
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> shell, 0);
        assertEquals(ShellProbeOutcome.SHELL, probe.outcome());
        assertSame(shell, probe.shell());
        assertNull(queue.queued);
    }

    @Test
    public void inlineNoShellIsAnAnswerEvenWithNoBudget()
    {
        Queue queue = new Queue();
        queue.inline = true;
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> null, 0);
        assertEquals(ShellProbeOutcome.NO_SHELL, probe.outcome());
        assertNull(queue.queued);
    }

    @Test
    public void completedAsyncShellIsReturned()
    {
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                work.run();
            }
        };
        Shell shell = mock(Shell.class);
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> shell, 1000);
        assertEquals(ShellProbeOutcome.SHELL, probe.outcome());
        assertSame(shell, probe.shell());
    }

    @Test
    public void completedAsyncNoShellIsNotATimeout()
    {
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                work.run();
            }
        };
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> null, 1000);
        assertEquals(ShellProbeOutcome.NO_SHELL, probe.outcome());
    }

    @Test
    public void unansweredAsyncReadTimesOutAndLateAnswerDoesNotChangeTheVerdict()
    {
        Queue queue = new Queue();
        Shell shell = mock(Shell.class);
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> shell, 20);
        assertEquals(ShellProbeOutcome.TIMED_OUT, probe.outcome());
        queue.queued.run();
        assertEquals(ShellProbeOutcome.TIMED_OUT, probe.outcome());
        assertNull(probe.shell());
    }

    @Test
    public void displayDisposedDuringSubmissionMeansNoShell()
    {
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                throw new IllegalStateException("Display disposed during submission");
            }
        };
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue,
            () -> { fail("Submission failed"); return null; }, 1000);
        assertEquals(ShellProbeOutcome.NO_SHELL, probe.outcome());
    }

    @Test
    public void inlineReadFailureKeepsItsOriginalException()
    {
        Queue queue = new Queue();
        queue.inline = true;
        RuntimeException failure = new IllegalStateException("UI read failed");
        RuntimeException caught = assertThrows(RuntimeException.class,
            () -> LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> { throw failure; }, 1000));
        assertSame(failure, caught);
    }

    @Test
    public void asyncReadFailureStillSignalsAnAnsweredProbe() throws Exception
    {
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Queue queue = new Queue()
        {
            @Override
            public void asyncExec(Runnable work)
            {
                Thread thread = new Thread(work, "shell-probe-fake-ui");
                thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));
                worker.set(thread);
                thread.start();
            }
        };
        RuntimeException failure = new IllegalStateException("UI read failed");
        ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(queue, () -> { throw failure; }, 1000);
        worker.get().join(2000);
        assertEquals(ShellProbeOutcome.NO_SHELL, probe.outcome());
        assertSame(failure, uncaught.get());
    }

    @Test
    public void interruptedProbeMeansTimedOutAndPreservesFlag()
    {
        try
        {
            Thread.currentThread().interrupt();
            ShellProbe probe = LaunchLifecycleUtils.grabActiveShellWithin(new Queue(), () -> null, 1000);
            assertEquals(ShellProbeOutcome.TIMED_OUT, probe.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }
}
