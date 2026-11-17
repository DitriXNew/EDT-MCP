/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * The bound on ONE {@code find_references} search: how many references it may collect and how long it
 * may run. It bounds the TRAVERSAL, not the output: once one reference more than {@link #limit()} has
 * been collected - proof that more exist - or the time budget has run out, the collector stops walking
 * back references and the Xtext reference finder stops walking its index (through {@link
 * #monitor(IntSupplier)}, which the finder polls per candidate resource). Before issue #931 the limit
 * was applied as a {@code limit*10} cap inside a traversal that always ran to the end, and every BSL
 * reference collected under that cap paid a parse of its module to resolve its line.
 * <p>
 * Only the diagnostic {@code find_references} path is bounded. The strict scans that guard a mutation
 * ({@code delete_metadata}, {@code clone_metadata_object}) run {@link #unbounded()}: a search stopped by
 * a clock could otherwise turn an unseen reference into "no references", which their fail-closed
 * contract forbids.
 * <p>
 * Not thread-safe: one search runs on one thread.
 */
public final class ReferenceSearchBound
{
    /** Why a bounded search stopped before its natural end. */
    public enum StopReason
    {
        /** The search ran to its end (or is unbounded). */
        NONE,
        /** One reference more than the limit was collected: more exist, the total is unknown. */
        LIMIT,
        /** The time budget ran out: more may exist, the total is unknown. */
        TIME_BUDGET
    }

    /** Default {@code limit} of {@code find_references}. */
    public static final int DEFAULT_LIMIT = 100;

    /** Upper clamp of {@code find_references}'s {@code limit}. */
    public static final int MAX_LIMIT = 500;

    /**
     * Wall-clock budget of one {@code find_references} call, counted from the call's start. Same number
     * and reasoning as {@code GetMethodCallHierarchyTool.TRANSITIVE_TIME_BUDGET_MS}: an MCP client cuts a
     * call at its own transport timeout - around 60 seconds for the clients this server is driven by - so
     * a search allowed to run past that returns nothing the caller ever sees, while stopping at 45 s
     * returns a labelled partial answer. The budget cannot preempt a single module parse already under
     * way, so a call may overrun it by that one parse.
     */
    public static final long TIME_BUDGET_MS = 45_000L;

    private static final ReferenceSearchBound UNBOUNDED =
        new ReferenceSearchBound(false, Integer.MAX_VALUE, 0L, 0L, () -> 0L);

    private final boolean bounded;
    private final int limit;
    private final long budgetMillis;
    private final long deadlineNanos;
    private final LongSupplier nanoClock;
    private StopReason stopReason = StopReason.NONE;

    private ReferenceSearchBound(boolean bounded, int limit, long budgetMillis, long deadlineNanos,
        LongSupplier nanoClock)
    {
        this.bounded = bounded;
        this.limit = limit;
        this.budgetMillis = budgetMillis;
        this.deadlineNanos = deadlineNanos;
        this.nanoClock = nanoClock;
    }



    /**
     * A bound whose time budget starts now on the given clock.
     *
     * @param limit the number of references the search reports; at least 1
     * @param budgetMillis the wall-clock budget, counted from this call
     * @param nanoClock a monotonic nanosecond clock ({@link System#nanoTime} in production)
     * @return the bound
     */
    public static ReferenceSearchBound of(int limit, long budgetMillis, LongSupplier nanoClock)
    {
        if (limit < 1)
        {
            throw new IllegalArgumentException("a bounded reference search reports at least 1 reference, got " //$NON-NLS-1$
                + limit);
        }
        long deadline = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        return new ReferenceSearchBound(true, limit, budgetMillis, deadline, nanoClock);
    }

    /** The bound of a strict scan: it never stops early. */
    public static ReferenceSearchBound unbounded()
    {
        return UNBOUNDED;
    }

    /** @return whether this bound can stop a search at all */
    public boolean isBounded()
    {
        return bounded;
    }

    /** @return the number of references a bounded search reports */
    public int limit()
    {
        return limit;
    }

    /** @return the time budget in milliseconds */
    public long budgetMillis()
    {
        return budgetMillis;
    }

    /**
     * Decides whether the search must stop, given how many unique references it holds. The first reason
     * found sticks: once stopped, a search stays stopped for the same reason.
     *
     * @param collected the number of unique references collected so far
     * @return {@code true} once the search must not do any more work
     */
    public boolean shouldStop(int collected)
    {
        if (!bounded)
        {
            return false;
        }
        if (stopReason != StopReason.NONE)
        {
            return true;
        }
        if (collected > limit)
        {
            stopReason = StopReason.LIMIT;
            return true;
        }
        if (nanoClock.getAsLong() - deadlineNanos >= 0)
        {
            stopReason = StopReason.TIME_BUDGET;
            return true;
        }
        return false;
    }

    /**
     * Records a newly collected reference. The reference one past the limit marks the search stopped AT
     * ONCE, not at the next poll: when it is the finder's last result the finder returns without polling
     * again, and the report must still show {@link #limit()} rows and say that more exist.
     *
     * @param collected the number of unique references collected, the new one included
     */
    public void recordCollected(int collected)
    {
        if (bounded && stopReason == StopReason.NONE && collected > limit)
        {
            stopReason = StopReason.LIMIT;
        }
    }

    /** @return whether a bounded search has stopped early */
    public boolean isStopped()
    {
        return stopReason != StopReason.NONE;
    }

    /** @return why the search stopped early, or {@link StopReason#NONE} */
    public StopReason stopReason()
    {
        return stopReason;
    }

    /**
     * A progress monitor that reports "cancelled" once this bound says stop. The Xtext reference finder
     * polls it before every candidate resource and every reference description and then throws {@link
     * org.eclipse.core.runtime.OperationCanceledException}, which is how the index walk itself - not only
     * the collection - ends at the bound. An unbounded search gets a plain monitor that never cancels.
     *
     * @param collected how many unique references the search holds right now
     * @return the monitor to hand to the finder
     */
    public IProgressMonitor monitor(IntSupplier collected)
    {
        if (!bounded)
        {
            return new NullProgressMonitor();
        }
        return new NullProgressMonitor()
        {
            @Override
            public boolean isCanceled()
            {
                return super.isCanceled() || shouldStop(collected.getAsInt());
            }
        };
    }
}
