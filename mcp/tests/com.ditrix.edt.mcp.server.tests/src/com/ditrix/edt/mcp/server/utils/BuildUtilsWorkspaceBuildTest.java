/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BuildUtils.BuildWait;

/**
 * Tests for {@link BuildUtils#awaitWorkspaceBuild(long)} and its seam
 * {@link BuildUtils#awaitJobFamilies}: the BOUNDED wait for the workspace build that a rename runs
 * before it collects references, so a module written just before is in the BSL index (#935).
 * <p>
 * The jobs here belong to a family of their own, so nothing in the test runtime's real workspace
 * build can make them pass or fail. Every job's work waits on a latch with a finite ceiling, so a
 * lost bound fails an elapsed-time assertion instead of hanging the suite. The suspended-manager
 * and re-check cases use a stand-in job manager: suspending the real one would stop every job of
 * the test runtime.
 */
public class BuildUtilsWorkspaceBuildTest
{
    private static final long CEILING_MS = 30_000;

    private final Object family = new Object();
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void tearDown()
    {
        release.countDown();
        Job.getJobManager().cancel(family);
    }

    private Job familyJob(long runMs)
    {
        return familyJob(runMs, Status.OK_STATUS);
    }

    private Job familyJob(long runMs, IStatus outcome)
    {
        Job job = new Job("test #935 build stand-in") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    if (runMs >= 0)
                    {
                        Thread.sleep(runMs);
                    }
                    else
                    {
                        release.await(CEILING_MS, TimeUnit.MILLISECONDS);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return outcome;
            }

            @Override
            public boolean belongsTo(Object candidate)
            {
                return candidate == family;
            }
        };
        job.setSystem(true);
        return job;
    }

    @Test
    public void testTheWorkspaceBuildFamiliesAreTheAutoAndManualBuild()
    {
        assertEquals(List.of(ResourcesPlugin.FAMILY_AUTO_BUILD, ResourcesPlugin.FAMILY_MANUAL_BUILD),
            BuildUtils.WORKSPACE_BUILD_FAMILIES);
    }

    @Test
    public void testNoPendingJobReturnsAtOnce()
    {
        long start = System.nanoTime();
        assertEquals(BuildWait.DRAINED, BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), 5_000));
        assertTrue("nothing pending must not wait", elapsedMs(start) < 1_000); //$NON-NLS-1$
    }

    @Test
    public void testASleepingBuildIsWaitedForUntilItHasRun()
    {
        // The auto-build is scheduled with a delay: it is SLEEPING when the write that caused it
        // returns. A wait that skips sleeping jobs is the #935 race all over again.
        Job job = familyJob(300);
        job.schedule(700);
        long start = System.nanoTime();

        BuildWait outcome = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS);

        assertEquals("the build finished well inside the deadline", BuildWait.DRAINED, outcome); //$NON-NLS-1$
        assertNotNull("the wait must not return before the scheduled build has run", job.getResult()); //$NON-NLS-1$
        assertTrue("the wait covered the delay and the run", elapsedMs(start) >= 900); //$NON-NLS-1$
    }

    @Test
    public void testABuildOutlivingTheDeadlineIsReportedNotWaitedOut()
    {
        Job job = familyJob(-1);
        job.schedule();
        long start = System.nanoTime();

        BuildWait outcome = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), 800);

        long elapsed = elapsedMs(start);
        assertEquals("a build still running at the deadline is not a drained build", //$NON-NLS-1$
            BuildWait.TIMED_OUT, outcome);
        assertNull("the build is still running", job.getResult()); //$NON-NLS-1$
        assertTrue("the wait honoured its deadline (" + elapsed + " ms)", //$NON-NLS-1$ //$NON-NLS-2$
            elapsed >= 700 && elapsed < 10_000);
    }

    @Test
    public void testOneDeadlineCoversEveryFamily()
    {
        Object second = new Object();
        Job first = familyJob(-1);
        first.schedule();
        long start = System.nanoTime();

        // The first family never drains; the second must not get a fresh deadline of its own.
        BuildWait outcome = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family, second), 600);

        assertEquals(BuildWait.TIMED_OUT, outcome);
        assertTrue(elapsedMs(start) < 10_000);
    }

    @Test
    public void testAnInterruptedCallerDoesNotWait()
    {
        Job job = familyJob(-1);
        job.schedule();
        Thread.currentThread().interrupt();
        try
        {
            long start = System.nanoTime();
            assertEquals(BuildWait.INTERRUPTED,
                BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS));
            assertTrue(elapsedMs(start) < 5_000);
            assertTrue("the interrupt must be kept for the caller", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void testACanceledBuildIsNotADrainedOne()
    {
        // AutoBuildJob.run returns CANCEL_STATUS for a build canceled outright (an INTERRUPTED one
        // reschedules itself and is waited for): the index it should have written is stale.
        Job job = familyJob(100, Status.CANCEL_STATUS);
        job.schedule();

        assertEquals(BuildWait.FAILED, BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS));
    }

    @Test
    public void testAFailedBuildIsNotADrainedOne()
    {
        // A builder exception ends AutoBuildJob.run with the CoreException's (error) status.
        Job job = familyJob(100, new Status(IStatus.ERROR, "test", "builder failed")); //$NON-NLS-1$ //$NON-NLS-2$
        job.schedule();

        assertEquals(BuildWait.FAILED, BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS));
    }

    @Test
    public void testAWarningBuildIsStillDrained()
    {
        Job job = familyJob(100, new Status(IStatus.WARNING, "test", "builder warned")); //$NON-NLS-1$ //$NON-NLS-2$
        job.schedule();

        assertEquals(BuildWait.DRAINED, BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS));
    }

    @Test
    public void testAPendingBuildUnderASuspendedManagerIsRefusedWithoutJoining()
    {
        // IJobManager.join waits only for RUNNING jobs while the manager is suspended (JobManager
        // selects state mask 4 instead of 7), so joining would return with the build still asleep.
        List<String> calls = new ArrayList<>();
        IJobManager manager = fakeManager(calls, true, Map.of(family, new int[] {1}));

        assertEquals(BuildWait.SUSPENDED, BuildUtils.awaitJobFamilies(manager, List.of(family), CEILING_MS));
        assertTrue("a suspended manager must not be joined: " + calls, !calls.contains("join:A")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testASuspendedManagerWithNothingPendingIsDrained()
    {
        IJobManager manager = fakeManager(new ArrayList<>(), true, Map.of(family, new int[] {0}));

        assertEquals(BuildWait.DRAINED, BuildUtils.awaitJobFamilies(manager, List.of(family), CEILING_MS));
    }

    @Test
    public void testAnEarlierFamilyIsCheckedAgainAfterALaterOneWasJoined()
    {
        // Family A drains, then B is joined - and meanwhile a new build was scheduled in A. One pass
        // in order would return DRAINED with A's build still asleep.
        Object second = new Object();
        List<String> calls = new ArrayList<>();
        // find(A) answers pending, pending again (scheduled while B was joined), then empty.
        IJobManager manager = fakeManager(calls, false,
            Map.of(family, new int[] {1, 1, 0}, second, new int[] {1, 0}));

        assertEquals(BuildWait.DRAINED, BuildUtils.awaitJobFamilies(manager, List.of(family, second), CEILING_MS));
        assertEquals("A must be joined again after B: " + calls, 2, //$NON-NLS-1$
            calls.stream().filter("join:A"::equals).count());
    }

    @Test
    public void testAnInterruptDuringTheJoinIsReportedAsAnInterruptNotATimeout()
    {
        Job pending = familyJob(0);
        IJobManager manager = (IJobManager)Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
            new Class<?>[] {IJobManager.class}, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "find": //$NON-NLS-1$
                    return new Job[] {pending};
                case "isSuspended": //$NON-NLS-1$
                    return false;
                case "join": //$NON-NLS-1$
                    throw new InterruptedException();
                default:
                    throw new UnsupportedOperationException(method.getName());
                }
            });
        try
        {
            assertEquals(BuildWait.INTERRUPTED, BuildUtils.awaitJobFamilies(manager, List.of(family), CEILING_MS));
            assertTrue("the interrupt must be restored for the caller", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
    }

    /**
     * A stand-in job manager: {@code find} answers, per family, the next count of pending jobs from
     * its script (the last entry repeats); {@code join} returns at once and is recorded.
     */
    private IJobManager fakeManager(List<String> calls, boolean suspended, Map<Object, int[]> script)
    {
        Map<Object, int[]> cursor = new java.util.HashMap<>();
        for (Object key : script.keySet())
        {
            cursor.put(key, new int[] {0});
        }
        return (IJobManager)Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
            new Class<?>[] {IJobManager.class}, (proxy, method, args) -> {
                String label = args != null && args.length > 0 && args[0] == family ? "A" : "B"; //$NON-NLS-1$ //$NON-NLS-2$
                switch (method.getName())
                {
                case "find": //$NON-NLS-1$
                {
                    int[] counts = script.get(args[0]);
                    int[] at = cursor.get(args[0]);
                    int n = counts[Math.min(at[0], counts.length - 1)];
                    at[0]++;
                    calls.add("find:" + label + "=" + n); //$NON-NLS-1$ //$NON-NLS-2$
                    Job[] jobs = new Job[n];
                    for (int i = 0; i < n; i++)
                    {
                        jobs[i] = familyJob(0);
                    }
                    return jobs;
                }
                case "join": //$NON-NLS-1$
                    calls.add("join:" + label); //$NON-NLS-1$
                    return null;
                case "isSuspended": //$NON-NLS-1$
                    return suspended;
                default:
                    throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    private static long elapsedMs(long start)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
