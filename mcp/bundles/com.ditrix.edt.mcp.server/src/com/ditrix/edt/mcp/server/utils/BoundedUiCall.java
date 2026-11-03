/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;

/** Shared UI dispatch and bounded completion waits; callers retain their outcome and late-work policies. */
public final class BoundedUiCall
{
    public interface UiExecutor
    {
        boolean isCurrentThread();
        void asyncExec(Runnable work);
    }

    @FunctionalInterface
    public interface Operation<T>
    {
        T run(BooleanSupplier active) throws Exception;
    }

    enum UiThreadPolicy
    {
        RUN_INLINE,
        REFUSE
    }

    enum Outcome
    {
        COMPLETED,
        NO_UI,
        TIMED_OUT,
        UI_THREAD_REFUSED,
        SUBMISSION_FAILED
    }

    record Dispatch(Outcome outcome, RuntimeException failure)
    {
    }

    private BoundedUiCall()
    {
    }

    static UiExecutor workbenchExecutorOrNull()
    {
        return executorOrNull(LaunchLifecycleUtils.workbenchDisplayOrNull());
    }

    static UiExecutor executorOrNull(Display display)
    {
        if (display == null || display.isDisposed())
        {
            return null;
        }
        return new UiExecutor()
        {
            @Override
            public boolean isCurrentThread()
            {
                return display.getThread() == Thread.currentThread();
            }

            @Override
            public void asyncExec(Runnable work)
            {
                display.asyncExec(work);
            }
        };
    }

    /** Inline work keeps its exceptions; asynchronous posting failures are returned for caller-specific mapping. */
    static Dispatch dispatch(UiExecutor executor, Runnable work, long timeoutMillis, UiThreadPolicy uiThreadPolicy)
    {
        if (executor == null)
        {
            return new Dispatch(Outcome.NO_UI, null);
        }
        if (executor.isCurrentThread())
        {
            if (uiThreadPolicy == UiThreadPolicy.REFUSE)
            {
                return new Dispatch(Outcome.UI_THREAD_REFUSED, null);
            }
            work.run();
            return new Dispatch(Outcome.COMPLETED, null);
        }
        try
        {
            return new Dispatch(run(executor::asyncExec, work, timeoutMillis)
                ? Outcome.COMPLETED : Outcome.TIMED_OUT, null);
        }
        catch (RuntimeException e)
        {
            return new Dispatch(Outcome.SUBMISSION_FAILED, e);
        }
    }

    /** A zero budget still posts; a timed-out task remains queued unless its caller cancels it. */
    static boolean run(Consumer<Runnable> submit, Runnable work, long timeoutMillis)
    {
        CountDownLatch completed = new CountDownLatch(1);
        submit(submit, work, completed);
        return await(completed, timeoutMillis);
    }

    /** Signals completion even when the work throws; commitment decisions stay inside the caller's work. */
    static void submit(Consumer<Runnable> submit, Runnable work, CountDownLatch completed)
    {
        submit.accept(() -> {
            try
            {
                work.run();
            }
            finally
            {
                completed.countDown();
            }
        });
    }

    static boolean awaitInterruptibly(CountDownLatch completed, long timeoutMillis) throws InterruptedException
    {
        return completed.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    static boolean await(CountDownLatch completed, long timeoutMillis)
    {
        try
        {
            return awaitInterruptibly(completed, timeoutMillis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static <T> T call(Operation<T> operation, long timeoutMillis) throws Exception
    {
        return call(workbenchExecutorOrNull(), operation, timeoutMillis);
    }

    static <T> T call(UiExecutor executor, Operation<T> operation, long timeoutMillis) throws Exception
    {
        AtomicBoolean active = new AtomicBoolean(true);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        FutureTask<T> task = new FutureTask<>(() -> operation.run(
            () -> active.get() && System.nanoTime() < deadline));
        try
        {
            Dispatch dispatched = dispatch(executor, task, timeoutMillis, UiThreadPolicy.REFUSE);
            switch (dispatched.outcome())
            {
                case NO_UI:
                    throw new IllegalStateException("EDT UI is unavailable (headless or shutting down). "
                        + "Start EDT with a workbench to use open_editor.");
                case UI_THREAD_REFUSED:
                    throw new IllegalStateException("open_editor must be called from an MCP worker thread "
                        + "so its UI wait can be bounded.");
                case SUBMISSION_FAILED:
                    throw dispatched.failure();
                case TIMED_OUT:
                    if (Thread.currentThread().isInterrupted())
                    {
                        throw new InterruptedException("UI completion wait interrupted");
                    }
                    throw new TimeoutException();
                case COMPLETED:
                    return task.get(0, TimeUnit.MILLISECONDS);
                default:
                    throw new IllegalStateException("Unknown UI dispatch outcome: " + dispatched.outcome());
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof Exception)
            {
                throw (Exception)e.getCause();
            }
            throw e;
        }
        catch (TimeoutException e)
        {
            throw new TimeoutException("EDT UI did not finish open_editor within " + timeoutMillis
                + " ms. Queued work is cancelled; an editor already opening may still finish. "
                + "Check EDT before retrying.");
        }
        finally
        {
            active.set(false);
            task.cancel(false);
        }
    }
}
