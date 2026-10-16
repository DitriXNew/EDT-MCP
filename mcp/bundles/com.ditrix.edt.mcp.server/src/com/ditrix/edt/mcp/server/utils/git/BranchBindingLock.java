/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one lock behind this server's infobase-binding changes and checkouts (#656).
 * <p>
 * A detach by {@code set_branch_infobase} decides what EDT's write may delete from the branch each project reads
 * now: EDT removes a binding from a context other than the one a project reads by deleting that context's whole
 * binding file. A checkout between that decision and EDT's write changes the branch a project reads, so the
 * binding changes of {@code set_branch_infobase} and {@code create_git_branch}'s attach, and every checkout this
 * server makes, take this lock: {@code switch_git_branch} and {@code create_git_branch}'s checkout
 * ({@link GitCheckoutSupport#checkout}), and {@code git}'s {@code checkout} / {@code switch} / {@code branch} /
 * {@code pull} (a {@code pull --rebase} detaches HEAD while it runs). It is held around the checkout or the binding
 * write itself, and never across a wait for a human (the consent dialog) or for EDT's reload of the project.
 * <p>
 * What no lock here can cover: a checkout or a binding change made outside these tools - EDT's own UI, git run
 * outside this server, the binding writes of {@code create_infobase} and {@code delete_infobase} - and a
 * checkout Job that outlived its bounded 120 s wait and was cancelled. For those
 * {@code set_branch_infobase} re-reads everything a detach relied on right before EDT's write; the window left is
 * between that re-check's last read and EDT's write.
 * <p>
 * One lock for the whole workspace, not one per project: a detach also deletes binding files of the project's
 * dependent extension projects, and a checkout of one repository changes the branch every project in it reads.
 */
public final class BranchBindingLock
{
    /** How long a call waits for another binding change or checkout before it refuses. */
    public static final long WAIT_SECONDS = 60;

    private static final ReentrantLock LOCK = new ReentrantLock(true);

    private BranchBindingLock()
    {
        // Utility class
    }

    /**
     * Takes the lock, waiting at most {@link #WAIT_SECONDS}. A {@code true} result must be paired with
     * {@link #unlock()} in a {@code finally}.
     *
     * @return {@code false} when another binding change or checkout held it for the whole wait, or when the waiting
     *         thread was interrupted (its interrupt flag is then restored)
     */
    public static boolean tryLock()
    {
        try
        {
            return LOCK.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Releases the lock taken by {@link #tryLock()}. */
    public static void unlock()
    {
        LOCK.unlock();
    }

    /** @return whether the calling thread holds the lock */
    public static boolean isHeldByCurrentThread()
    {
        return LOCK.isHeldByCurrentThread();
    }

    /**
     * Why a call that could not take the lock did not run, for its answer: {@code "another infobase-binding change
     * or checkout is still running (waited 60 s)"}.
     */
    public static String busyReason()
    {
        return "another infobase-binding change or checkout is still running (waited " + WAIT_SECONDS + " s)"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
