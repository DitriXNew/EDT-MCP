/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

/**
 * The one lock behind this server's infobase-binding changes and checkouts (#656), and the record of this server's
 * branch changes that are still running after the call that made them stopped waiting.
 * <p>
 * A detach by {@code set_branch_infobase} decides what EDT's write may delete from the branch each project reads
 * now: EDT removes a binding from a context other than the one a project reads by deleting that context's whole
 * binding file. A checkout between that decision and EDT's write changes the branch a project reads, so the
 * binding changes of {@code set_branch_infobase} and {@code create_git_branch}'s attach, and every checkout this
 * server makes, take this lock: {@code switch_git_branch} and {@code create_git_branch}'s checkout
 * ({@link GitCheckoutSupport#checkout}), and {@code git}'s {@code checkout} / {@code switch} / {@code branch} /
 * {@code pull} / {@code stash branch} (a {@code pull --rebase} detaches HEAD while it runs; {@code stash branch}
 * checks a new branch out). A {@code git} command of these that rewrites the work tree keeps the lock through the
 * workspace refresh that follows it, where EDT's workspace catches up with the new branch. The lock is held
 * around the checkout or the binding write itself, and never across a wait for a human (the consent dialog) or
 * for EDT's reload of the project.
 * <p>
 * Nor is it held across a job that may never end: a checkout Job that outlived its bounded wait, or the refresh
 * after a {@code git} checkout that outlived its own, is recorded here as still running
 * ({@link #awaitOrTrack}) before its caller releases the lock, and stays recorded until the job itself ends.
 * While one is recorded for a project or for one of its dependent extension projects, a detach refuses
 * ({@link #inFlightAmong}, {@link #inFlightRefusal}) - a hung job then blocks only those detaches, never every
 * binding write.
 * <p>
 * What nothing here can cover - the declared boundary: a checkout or a binding change made outside these tools
 * (EDT's own UI, git run outside this server, the binding writes of {@code create_infobase} and
 * {@code delete_infobase}), and EDT's own background reaction to a branch change (its reload of the project and
 * of the branch contexts). For those {@code set_branch_infobase} re-reads everything a detach relied on right
 * before EDT's write; the window left is between that re-check's last read and EDT's write.
 * <p>
 * One lock for the whole workspace, not one per project: a detach also deletes binding files of the project's
 * dependent extension projects, and a checkout of one repository changes the branch every project in it reads.
 */
public final class BranchBindingLock
{
    /** How long a call waits for another binding change or checkout before it refuses. */
    public static final long WAIT_SECONDS = 60;

    private static final ReentrantLock LOCK = new ReentrantLock(true);

    /**
     * This server's branch changes still running after their caller stopped waiting, by a token per change. Written
     * under {@link #LOCK} when a change is recorded, removed by the job's {@code done} on whatever thread runs it.
     */
    private static final Map<Object, InFlight> IN_FLIGHT = new ConcurrentHashMap<>();

    private BranchBindingLock()
    {
        // Utility class
    }

    /** The projects one recorded branch change may move: named ones, or every project when they were unknown. */
    private static final class InFlight
    {
        final Set<String> projects;

        final boolean everyProject;

        InFlight(Set<String> projects, boolean everyProject)
        {
            this.projects = projects;
            this.everyProject = everyProject;
        }

        boolean covers(String project)
        {
            return everyProject || projects.contains(project);
        }
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

    /**
     * Waits at most {@code waitMillis} for {@code job} - a branch change of this server, already scheduled, by a
     * caller that holds the lock - to end. When it has not ended (the wait ran out, {@code waitMillis <= 0} waits
     * not at all, or the waiting thread was interrupted) the job is recorded as still running for the projects
     * {@code projects} names, before this returns and so before the caller releases the lock, until the job's own
     * {@code done}; a job that ended in the meantime is not left recorded.
     *
     * @param job the scheduled job whose end is awaited
     * @param waitMillis how long to wait
     * @param projects names the projects whose branch the job may change; asked only when the job is recorded, and
     *            a failure to name them (an exception or {@code null}) records the job for every project
     * @return whether the job ended within the wait
     * @throws InterruptedException when the waiting thread was interrupted; the job is recorded first
     */
    public static boolean awaitOrTrack(Job job, long waitMillis, Supplier<? extends Collection<String>> projects)
        throws InterruptedException
    {
        try
        {
            if (waitMillis > 0 && job.join(waitMillis, null))
            {
                return true;
            }
        }
        catch (InterruptedException | RuntimeException e)
        {
            track(job, projects);
            throw e;
        }
        track(job, projects);
        return false;
    }

    /** Records {@code job} as still running until its {@code done}; see {@link #awaitOrTrack}. */
    private static void track(Job job, Supplier<? extends Collection<String>> projects)
    {
        Object token = new Object();
        IN_FLIGHT.put(token, inFlight(projects));
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                IN_FLIGHT.remove(token);
                job.removeJobChangeListener(this);
            }
        });
        // A job that ended before the listener was added delivers no done to it.
        if (job.getState() == Job.NONE)
        {
            IN_FLIGHT.remove(token);
        }
    }

    private static InFlight inFlight(Supplier<? extends Collection<String>> projects)
    {
        try
        {
            Collection<String> named = projects.get();
            if (named != null)
            {
                Set<String> names = new HashSet<>();
                for (String name : named)
                {
                    if (name != null)
                    {
                        names.add(name);
                    }
                }
                return new InFlight(Set.copyOf(names), false);
            }
        }
        catch (RuntimeException e) // NOSONAR fail closed: a change whose projects are unknown may move any of them
        {
            // Recorded for every project below.
        }
        return new InFlight(Set.of(), true);
    }

    /** @return whether any branch change of this server is recorded as still running */
    public static boolean anyInFlight()
    {
        return !IN_FLIGHT.isEmpty();
    }

    /**
     * @param projects project names ({@code null} entries are skipped)
     * @return the first of {@code projects} that a branch change of this server still running may move, or
     *         {@code null} when none
     */
    public static String inFlightAmong(Collection<String> projects)
    {
        for (String project : projects)
        {
            if (project == null)
            {
                continue;
            }
            for (InFlight change : IN_FLIGHT.values())
            {
                if (change.covers(project))
                {
                    return project;
                }
            }
        }
        return null;
    }

    /**
     * Why a detach refuses while a branch change of this server is still running for {@code project}: {@code "a
     * branch change made by this server is still running for '<project>' - retry once it ends"}, and why.
     *
     * @param project the project named, or {@code null} for one this call could not rule out
     */
    public static String inFlightRefusal(String project)
    {
        String which = project == null ? "a project this detach could not rule out (its dependent extension " //$NON-NLS-1$
            + "projects could not be listed)" : "'" + project + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return "Did not detach: a branch change made by this server is still running for " + which //$NON-NLS-1$
            + " - retry once it ends. It is a checkout, or the workspace refresh after one, that outlived its bounded " //$NON-NLS-1$
            + "wait; when it lands it changes the branch EDT reads, and with it what EDT's write deletes. Nothing was " //$NON-NLS-1$
            + "changed."; //$NON-NLS-1$
    }
}
