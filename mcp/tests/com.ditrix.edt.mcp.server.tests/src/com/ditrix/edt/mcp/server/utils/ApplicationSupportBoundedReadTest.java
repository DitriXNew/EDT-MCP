/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ApplicationSupport.BoundedRead;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tests for the shared bounded {@link IApplicationManager} reads (#622): the caller-side deadline
 * that keeps a wedged {@code getApplication} / {@code getDefaultApplication} / {@code getApplications}
 * from holding an unattended MCP call open forever.
 * <p>
 * The wedged manager in these tests waits on a latch with its OWN finite ceiling and is released in
 * a {@code finally}, so an implementation that lost the bound fails an assertion instead of wedging
 * the whole suite — and no abandoned Job leaks into the tests that follow.
 */
public class ApplicationSupportBoundedReadTest
{
    /** Deadline for the wedged-read tests: small, and a round number of ms so the text is pinned. */
    private static final long SHORT_DEADLINE_MS = 250L;

    /** Ceiling on the wedged read — far above the deadline, but finite. */
    private static final long WEDGE_CEILING_SECONDS = 30L;

    /** Deadline for reads that are expected to answer on their own. */
    private static final long GENEROUS_DEADLINE_MS = 60_000L;

    /** Bound the healthy-read tests must come back well inside. */
    private static final long SANE_RETURN_MS = 30_000L;

    private static final String APP_ID = "Infobase.Test"; //$NON-NLS-1$

    private static final String PROJECT_NAME = "TestConfiguration"; //$NON-NLS-1$

    /** The exact deadline sentence for a singular lookup that timed out at {@link #SHORT_DEADLINE_MS}. */
    private static final String EXPECTED_LOOKUP_TIMEOUT =
        "the EDT application lookup for application 'Infobase.Test' did not finish within 250ms " //$NON-NLS-1$
            + "and may still be running, so it may yet change EDT's stored state; an immediate " //$NON-NLS-1$
            + "retry queues behind the same wedge, and if it keeps expiring EDT has to be " //$NON-NLS-1$
            + "restarted"; //$NON-NLS-1$

    // ==================== A wedged read answers on the deadline ====================

    @Test
    public void testWedgedApplicationLookupReturnsTheDeadlineInsteadOfHanging() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplication(project, APP_ID)).thenAnswer(invocation -> {
            entered.countDown();
            try
            {
                release.await(WEDGE_CEILING_SECONDS, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            long startMs = System.currentTimeMillis();
            BoundedRead<Optional<IApplication>> read = ApplicationSupport.getApplicationBounded(
                manager, project, APP_ID, SHORT_DEADLINE_MS);
            long elapsedMs = System.currentTimeMillis() - startMs;

            assertTrue("the read must have entered EDT before this test calls it wedged", //$NON-NLS-1$
                entered.await(5, TimeUnit.SECONDS));
            assertTrue("the caller must not wait out the wedge, only its own deadline", //$NON-NLS-1$
                elapsedMs < SANE_RETURN_MS);
            assertFalse("a wedged read must not be reported as concluded", read.concluded()); //$NON-NLS-1$
            assertEquals("the deadline sentence must name the application and the deadline", //$NON-NLS-1$
                EXPECTED_LOOKUP_TIMEOUT, read.deadlineFailure());
            assertFalse("a wedged read must never be worded as a measured not-found", //$NON-NLS-1$
                read.deadlineFailure().contains("not found")); //$NON-NLS-1$
            assertFalse("a wedged read must never be worded as a measured not-found", //$NON-NLS-1$
                read.deadlineFailure().contains("was not found")); //$NON-NLS-1$
            assertNull("nothing was raised, so there is no failure to report", read.failure()); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue("the abandoned read must not leak into later tests", //$NON-NLS-1$
                finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testDeadlineRefusesToHandOutAValueForAnUnconcludedRead() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplication(project, APP_ID)).thenAnswer(invocation -> {
            try
            {
                release.await(WEDGE_CEILING_SECONDS, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            BoundedRead<Optional<IApplication>> read = ApplicationSupport.getApplicationBounded(
                manager, project, APP_ID, SHORT_DEADLINE_MS);

            // The whole point: "unknown" must not be readable as Optional.empty(), because every
            // caller turns an empty Optional into "Application not found".
            try
            {
                read.valueOrRethrow();
                fail("an expired deadline must not produce a value"); //$NON-NLS-1$
            }
            catch (IllegalStateException expected)
            {
                assertEquals(EXPECTED_LOOKUP_TIMEOUT, expected.getMessage());
            }
        }
        finally
        {
            release.countDown();
            assertTrue("the abandoned read must not leak into later tests", //$NON-NLS-1$
                finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testWedgedApplicationListNamesTheProjectAndTheDeadline() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT_NAME);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplications(project)).thenAnswer(invocation -> {
            try
            {
                release.await(WEDGE_CEILING_SECONDS, TimeUnit.SECONDS);
                return Collections.emptyList();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            BoundedRead<List<IApplication>> read = ApplicationSupport.getApplicationsBounded(
                manager, project, SHORT_DEADLINE_MS);

            assertFalse(read.concluded());
            assertEquals("the EDT application list for project 'TestConfiguration' did not finish " //$NON-NLS-1$
                + "within 250ms and may still be running, so it may yet change EDT's stored " //$NON-NLS-1$
                + "state; an immediate retry queues behind the same wedge, and if it keeps " //$NON-NLS-1$
                + "expiring EDT has to be restarted", read.deadlineFailure()); //$NON-NLS-1$
            assertFalse("a wedged listing must not be worded as an empty project", //$NON-NLS-1$
                read.deadlineFailure().contains("no applications")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testWedgedDefaultApplicationNamesTheProjectAndTheDeadline() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT_NAME);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getDefaultApplication(project)).thenAnswer(invocation -> {
            try
            {
                release.await(WEDGE_CEILING_SECONDS, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            BoundedRead<Optional<IApplication>> read =
                ApplicationSupport.getDefaultApplicationBounded(manager, project, SHORT_DEADLINE_MS);

            assertFalse(read.concluded());
            assertEquals("the EDT default-application lookup for project 'TestConfiguration' did " //$NON-NLS-1$
                + "not finish within 250ms and may still be running, so it may yet change EDT's " //$NON-NLS-1$
                + "stored state; an immediate retry queues behind the same wedge, and if it keeps " //$NON-NLS-1$
                + "expiring EDT has to be restarted", read.deadlineFailure()); //$NON-NLS-1$
            assertFalse("a wedged default lookup must not read as 'there is no default'", //$NON-NLS-1$
                read.deadlineFailure().contains("has no default")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    // ==================== The success path is unchanged ====================

    @Test
    public void testSuccessfulLookupReturnsExactlyWhatTheManagerReturned() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        IApplication application = mock(IApplication.class);
        Optional<IApplication> answer = Optional.of(application);
        when(manager.getApplication(project, APP_ID)).thenReturn(answer);

        long startMs = System.currentTimeMillis();
        BoundedRead<Optional<IApplication>> read = ApplicationSupport.getApplicationBounded(
            manager, project, APP_ID, GENEROUS_DEADLINE_MS);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertTrue("a read that answered must be concluded", read.concluded()); //$NON-NLS-1$
        assertNull("a concluded read has no deadline sentence", read.deadlineFailure()); //$NON-NLS-1$
        assertNull("nothing was raised", read.failure()); //$NON-NLS-1$
        assertSame("the bounded read must hand back the manager's own answer", //$NON-NLS-1$
            answer, read.valueOrRethrow());
        assertSame(application, read.valueOrRethrow().orElse(null));
        // A read that is not blocked must not sit out the deadline: without this, an
        // implementation that always waits timeoutMs would pass every assertion above.
        assertTrue("a healthy read must return immediately, not on the deadline", //$NON-NLS-1$
            elapsedMs < SANE_RETURN_MS);
        verify(manager, times(1)).getApplication(project, APP_ID);
    }

    @Test
    public void testSuccessfulListingReturnsTheSameListInstance() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        IApplication application = mock(IApplication.class);
        List<IApplication> answer = Collections.singletonList(application);
        when(manager.getApplications(project)).thenReturn(answer);

        BoundedRead<List<IApplication>> read =
            ApplicationSupport.getApplicationsBounded(manager, project, GENEROUS_DEADLINE_MS);

        assertTrue(read.concluded());
        assertSame(answer, read.valueOrRethrow());
        verify(manager, times(1)).getApplications(project);
    }

    @Test
    public void testAnEmptyAnswerStaysAMeasuredEmptyAnswer() throws Exception
    {
        // The counterpart of the deadline tests: a read that DID conclude empty must still be
        // readable as empty, or the bound would have destroyed the not-found semantics.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getApplication(project, APP_ID)).thenReturn(Optional.empty());

        BoundedRead<Optional<IApplication>> read = ApplicationSupport.getApplicationBounded(
            manager, project, APP_ID, GENEROUS_DEADLINE_MS);

        assertTrue(read.concluded());
        assertTrue("a measured absence must remain an empty Optional", //$NON-NLS-1$
            read.valueOrRethrow().isEmpty());
    }

    // ==================== A raised read is a conclusion, not a deadline ====================

    @Test
    public void testApplicationExceptionIsSurfacedAndNotConvertedIntoATimeout() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        ApplicationException raised = new ApplicationException("EDT is mid-index"); //$NON-NLS-1$
        when(manager.getApplication(project, APP_ID)).thenThrow(raised);

        BoundedRead<Optional<IApplication>> read = ApplicationSupport.getApplicationBounded(
            manager, project, APP_ID, GENEROUS_DEADLINE_MS);

        assertTrue("a read that raised is a CONCLUSION, not a deadline", read.concluded()); //$NON-NLS-1$
        assertNull("a raised read must not carry a deadline sentence", read.deadlineFailure()); //$NON-NLS-1$
        assertSame("the manager's own exception must be preserved", raised, read.failure()); //$NON-NLS-1$
        try
        {
            read.valueOrRethrow();
            fail("the raised exception must reach the caller's existing handler"); //$NON-NLS-1$
        }
        catch (ApplicationException rethrown)
        {
            assertSame("the SAME exception instance must be rethrown, not a wrapper", //$NON-NLS-1$
                raised, rethrown);
        }
    }

    @Test
    public void testAnAlreadyInterruptedCallerRunsTheReadAndStaysInterrupted() throws Exception
    {
        // Measured against org.eclipse.core.jobs as shipped with EDT 2026.2, not assumed:
        // Semaphore.acquire(long) THROWS on a pending interrupt before waiting, and
        // JobManager.join rethrows it whenever LockManager.canBlock() is true (off the UI thread
        // it always is). Moving the read off the caller's thread would therefore skip the read
        // entirely for an already-interrupted caller — hence the flag is taken before the join and
        // restored after, so the read behaves exactly as the unbounded call did AND the flag
        // survives to arm the caller's own checks (create_infobase's read-back poll is one).
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT_NAME);
        List<IApplication> answer = Collections.emptyList();
        when(manager.getApplications(project)).thenReturn(answer);

        BoundedRead<List<IApplication>> read;
        boolean stillInterrupted;
        Thread.currentThread().interrupt();
        try
        {
            read = ApplicationSupport.getApplicationsBounded(manager, project, GENEROUS_DEADLINE_MS);
            stillInterrupted = Thread.currentThread().isInterrupted();
        }
        finally
        {
            Thread.interrupted(); // clear it so later tests are unaffected
        }

        assertTrue("the caller must come back still interrupted", stillInterrupted); //$NON-NLS-1$
        assertTrue("a pending interrupt must not turn a healthy read into a deadline", //$NON-NLS-1$
            read.concluded());
        assertSame(answer, read.valueOrRethrow());
        verify(manager, times(1)).getApplications(project);
    }

    @Test
    public void testAWaitInterruptedMidFlightIsReportedAsInterruptedNotAsATimeout() throws Exception
    {
        // The other interruption: the flag arrives WHILE the caller is waiting. That is not a
        // deadline, and a caller that separates "cut short" from "failed" depends on the
        // difference - so it gets its own outcome and its own sentence.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT_NAME);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplications(project)).thenAnswer(invocation -> {
            entered.countDown();
            try
            {
                release.await(WEDGE_CEILING_SECONDS, TimeUnit.SECONDS);
                return Collections.emptyList();
            }
            finally
            {
                finished.countDown();
            }
        });
        Thread waiter = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try
            {
                entered.await(5, TimeUnit.SECONDS);
                waiter.interrupt();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }, "bounded-read-interrupter"); //$NON-NLS-1$

        BoundedRead<List<IApplication>> read;
        try
        {
            interrupter.start();
            // A deadline far beyond the test: only the interrupt can end this wait, so an
            // implementation that folded INTERRUPTED into TIMED_OUT cannot pass by timing out.
            read = ApplicationSupport.getApplicationsBounded(manager, project, GENEROUS_DEADLINE_MS);
        }
        finally
        {
            Thread.interrupted(); // clear the flag BoundedJob restored
            release.countDown();
            interrupter.join(5_000L);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }

        assertFalse("an interrupted wait did not conclude", read.concluded()); //$NON-NLS-1$
        assertEquals(BoundedJob.Outcome.INTERRUPTED, read.outcome());
        assertTrue("the interruption must be distinguishable from a deadline", //$NON-NLS-1$
            read.interrupted());
        assertEquals("the wait for the EDT application list for project 'TestConfiguration' was " //$NON-NLS-1$
            + "interrupted and the lookup may still be running", read.deadlineFailure()); //$NON-NLS-1$
        assertFalse("an interruption is not a deadline that expired", //$NON-NLS-1$
            read.deadlineFailure().contains("did not finish within")); //$NON-NLS-1$
    }

    @Test
    public void testAConcludedReadIsNotFlaggedInterrupted() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getApplications(project)).thenReturn(Collections.emptyList());

        BoundedRead<List<IApplication>> read =
            ApplicationSupport.getApplicationsBounded(manager, project, GENEROUS_DEADLINE_MS);

        assertTrue(read.concluded());
        assertFalse("a healthy read must not claim it was interrupted", read.interrupted()); //$NON-NLS-1$
        assertEquals(BoundedJob.Outcome.COMPLETED, read.outcome());
    }

    // ==================== Each non-concluding outcome has its own sentence ====================

    @Test
    public void testTimedOutBeforeStartHasItsOwnMessage()
    {
        String target = ApplicationSupport.applicationTarget(APP_ID);
        String beforeStart = ApplicationSupport.lookupDeadlineFailure(target, 5_000L,
            new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT_BEFORE_START, 5_000L, null));

        assertEquals("the EDT application lookup for application 'Infobase.Test' did not start " //$NON-NLS-1$
            + "within 5s and was cancelled before it began, so it read nothing and changed " //$NON-NLS-1$
            + "nothing; retrying is safe", beforeStart); //$NON-NLS-1$
        // A queued job that was cancelled never ran, so claiming it may still be running would be
        // the exact opposite of the truth — and that is the ordinary-timeout wording.
        assertFalse("a never-started read must not claim it may still be running", //$NON-NLS-1$
            beforeStart.contains("may still be running")); //$NON-NLS-1$
        assertFalse("a never-started read must not use the ordinary timeout wording", //$NON-NLS-1$
            beforeStart.contains("did not finish within")); //$NON-NLS-1$
        // It must not name a condition that cannot produce it either: for a rule-less job the Job
        // queue always yields a worker, so "wait for a responsive Job queue" was advice about a
        // state this outcome never reports.
        assertFalse("the advice must not name the Job queue's responsiveness", //$NON-NLS-1$
            beforeStart.contains("Job queue is responsive")); //$NON-NLS-1$
    }

    @Test
    public void testTheFourNonConcludingOutcomesAreWordedApart()
    {
        String target = ApplicationSupport.applicationTarget(APP_ID);
        String timedOut = ApplicationSupport.lookupDeadlineFailure(target, 5_000L,
            new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT, 5_000L, null));
        String interrupted = ApplicationSupport.lookupDeadlineFailure(target, 5_000L,
            new BoundedJob.Result(BoundedJob.Outcome.INTERRUPTED, 5_000L, null));
        String beforeStart = ApplicationSupport.lookupDeadlineFailure(target, 5_000L,
            new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT_BEFORE_START, 5_000L, null));
        String notRun = ApplicationSupport.lookupDeadlineFailure(target, 5_000L,
            new BoundedJob.Result(BoundedJob.Outcome.NOT_RUN, 5_000L, null));

        assertEquals("the EDT application lookup for application 'Infobase.Test' did not finish " //$NON-NLS-1$
            + "within 5s and may still be running, so it may yet change EDT's stored state; an " //$NON-NLS-1$
            + "immediate retry queues behind the same wedge, and if it keeps expiring EDT has to " //$NON-NLS-1$
            + "be restarted", timedOut); //$NON-NLS-1$
        assertEquals("the wait for the EDT application lookup for application 'Infobase.Test' was " //$NON-NLS-1$
            + "interrupted and the lookup may still be running", interrupted); //$NON-NLS-1$
        assertEquals("the EDT application lookup for application 'Infobase.Test' never ran: it " //$NON-NLS-1$
            + "left EDT's background job queue without entering the work, so something other than " //$NON-NLS-1$
            + "this call's deadline cancelled it and nothing was read", notRun); //$NON-NLS-1$
        assertDistinct(timedOut, beforeStart);
        assertDistinct(timedOut, notRun);
        assertDistinct(timedOut, interrupted);
        assertDistinct(beforeStart, notRun);
        // NOT_RUN is the one outcome our own deadline did NOT cause, so it must not tell the caller
        // to retry with a larger timeout.
        assertFalse("NOT_RUN must not advise waiting for the Job queue", //$NON-NLS-1$
            notRun.contains("Job queue is responsive")); //$NON-NLS-1$
        // Nor may it hand the agent a Java enum constant to read.
        assertFalse("NOT_RUN must not leak its own enum constant into agent prose", //$NON-NLS-1$
            notRun.contains("NOT_RUN")); //$NON-NLS-1$
        // The wedge is a monitor: a retry queues behind the same holder, so the ordinary timeout
        // must name the restart rather than advise a loop that cannot end.
        assertTrue("a timed-out read must name the EDT restart, not just a retry", //$NON-NLS-1$
            timedOut.contains("EDT has to be restarted")); //$NON-NLS-1$
    }

    @Test
    public void testTheInterruptPolicyIsWhetherTheFlagIsTakenBeforeTheJobIsScheduled()
        throws Exception
    {
        // What the two policies DO differ in, deterministically and on every runtime: whether the
        // caller's pending interrupt is still set when the job reaches the scheduler. Everything
        // after that is the platform's decision - org.eclipse.core.jobs 3.15.700's
        // JobManager.join rethrows an InterruptedException only when lockManager.canBlock(), and
        // Semaphore.acquire has already CLEARED the flag by then - so a test that asserted a fast
        // abort would be asserting the runtime's LockListener, not this class.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getApplications(project)).thenReturn(Collections.emptyList());
        AtomicBoolean flagAtSchedule = new AtomicBoolean();

        Thread.currentThread().interrupt();
        try
        {
            ApplicationSupport.readBounded("Interrupt-aware read", "target", //$NON-NLS-1$ //$NON-NLS-2$
                SANE_RETURN_MS, () -> manager.getApplications(project), false, job -> {
                    flagAtSchedule.set(Thread.currentThread().isInterrupted());
                    McpJobs.schedule(job);
                });
        }
        finally
        {
            Thread.interrupted(); // clear it so later tests are unaffected
        }

        assertTrue("an interrupt-aware caller keeps its flag: the platform decides, not us", //$NON-NLS-1$
            flagAtSchedule.get());

        flagAtSchedule.set(true);
        Thread.currentThread().interrupt();
        try
        {
            ApplicationSupport.readBounded("Flag-taking read", "target", //$NON-NLS-1$ //$NON-NLS-2$
                SANE_RETURN_MS, () -> manager.getApplications(project), true, job -> {
                    flagAtSchedule.set(Thread.currentThread().isInterrupted());
                    McpJobs.schedule(job);
                });
        }
        finally
        {
            Thread.interrupted();
        }

        assertFalse("the tool entry points take the flag so the read still runs", //$NON-NLS-1$
            flagAtSchedule.get());
    }

    @Test
    public void testAJobManagerShutdownIsItsOwnNonConcludedRead()
    {
        // JobManager.scheduleInternal throws IllegalStateException once EDT's job manager has been
        // shut down, and BoundedJob rethrows it - so before this, every bounded application read
        // threw during EDT shutdown where the pre-#622 inline read simply worked.
        BoundedRead<String> read = ApplicationSupport.readBounded("Shutdown probe", //$NON-NLS-1$
            "the EDT application lookup for application 'Infobase.Test'", 5_000L, //$NON-NLS-1$
            () -> "unreachable", true, job -> { //$NON-NLS-1$
                throw new IllegalStateException("Job manager has been shut down."); //$NON-NLS-1$
            });

        assertFalse("a refused schedule is not a concluded read", read.concluded()); //$NON-NLS-1$
        assertEquals(BoundedJob.Outcome.NOT_RUN, read.outcome());
        assertEquals("the EDT application lookup for application 'Infobase.Test' could not be " //$NON-NLS-1$
            + "started: EDT's background job manager has been shut down (EDT is stopping), so no " //$NON-NLS-1$
            + "application read can run until EDT is restarted", read.deadlineFailure()); //$NON-NLS-1$
        assertFalse("a refused schedule must not be worded as an expired deadline", //$NON-NLS-1$
            read.deadlineFailure().contains("did not finish within")); //$NON-NLS-1$
    }

    @Test
    public void testSubSecondDeadlinesAreSpelledInMillisecondsAndWholeOnesInSeconds()
    {
        String target = ApplicationSupport.applicationTarget(APP_ID);
        BoundedJob.Result timedOut = new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT, 1L, null);

        String millis = ApplicationSupport.lookupDeadlineFailure(target, 250L, timedOut);
        String seconds = ApplicationSupport.lookupDeadlineFailure(target, 30_000L, timedOut);

        assertTrue(millis.contains("within 250ms")); //$NON-NLS-1$
        assertFalse("250 ms must not be rendered as a fraction of a second", //$NON-NLS-1$
            millis.contains("0s")); //$NON-NLS-1$
        assertTrue(seconds.contains("within 30s")); //$NON-NLS-1$
        assertFalse("a whole-second deadline must not be spelled in milliseconds", //$NON-NLS-1$
            seconds.contains("30000ms")); //$NON-NLS-1$
    }

    // ==================== The promoted wording is still ONE wording ====================

    @Test
    public void testStandaloneServerSupportUsesTheSharedWordingVerbatim()
    {
        // StandaloneServerSupport.applicationLookupFailure predates the shared helper and its text
        // is asserted by LaunchToolTest / StandaloneServerStateRecoveryTest. Promoting the wording
        // must not have moved a single character of it.
        BoundedJob.Result timedOut = new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT, 1L, null);

        assertEquals(
            ApplicationSupport.lookupDeadlineFailure(
                ApplicationSupport.applicationTarget(APP_ID), 250L, timedOut),
            StandaloneServerSupport.applicationLookupFailure(APP_ID, 250L, timedOut));
    }

    // ============ An exhausted budget must not SCHEDULE a read it would abandon ============

    @Test
    public void testAnExhaustedBudgetIsRefusedWithoutEverRunningTheRead()
    {
        // BoundedJob clamps its join to 1 ms, so a caller that arrived with nothing left would
        // still START the read - and none of these reads is side-effect-free: getApplication
        // reaches initIfNeeded, which binds up to a hundred sockets looking for a debug port and
        // PERSISTS the winner. Scheduling one to abandon it a millisecond later is a mutation with
        // no answer, which is strictly worse than not asking.
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean scheduled = new AtomicBoolean();

        BoundedRead<String> read = ApplicationSupport.readBounded("Exhausted budget", //$NON-NLS-1$
            "the EDT application lookup for application 'Infobase.Test'", 0L, //$NON-NLS-1$
            () -> {
                ran.set(true);
                return "unreachable"; //$NON-NLS-1$
            }, true, job -> scheduled.set(true));

        assertFalse("the read must not have run", ran.get()); //$NON-NLS-1$
        assertFalse("and the job must not even have been scheduled", scheduled.get()); //$NON-NLS-1$
        assertFalse("a read that never ran is not a conclusion", read.concluded()); //$NON-NLS-1$
        assertEquals(BoundedJob.Outcome.NOT_RUN, read.outcome());
        assertTrue("it must say nothing was changed: " + read.deadlineFailure(), //$NON-NLS-1$
            read.deadlineFailure().contains("nothing was read and nothing was changed")); //$NON-NLS-1$
        assertFalse("and it must not be worded as an expired deadline", //$NON-NLS-1$
            read.deadlineFailure().contains("did not finish within")); //$NON-NLS-1$
    }

    @Test
    public void testANegativeBudgetIsRefusedTheSameWay()
    {
        AtomicBoolean scheduled = new AtomicBoolean();

        BoundedRead<String> read = ApplicationSupport.readBounded("Negative budget", //$NON-NLS-1$
            "the EDT application list for project 'P'", -5_000L, () -> "unreachable", //$NON-NLS-1$ //$NON-NLS-2$
            true, job -> scheduled.set(true));

        assertFalse(scheduled.get());
        assertFalse(read.concluded());
        assertEquals(ApplicationSupport.exhaustedBudgetFailure(
            "the EDT application list for project 'P'"), read.deadlineFailure()); //$NON-NLS-1$
    }

    @Test
    public void testTheSmallestPositiveBudgetStillReachesTheJobManager()
    {
        // The other edge of the same rule: ONLY zero or less is refused. A caller that still has a
        // millisecond gets the read it asked for.
        AtomicBoolean scheduled = new AtomicBoolean();

        BoundedRead<String> read = ApplicationSupport.readBounded("Minimal budget", //$NON-NLS-1$
            "the EDT application list for project 'P'", 1L, () -> "answered", //$NON-NLS-1$ //$NON-NLS-2$
            true, job -> {
                scheduled.set(true);
                McpJobs.schedule(job);
            });

        assertTrue("a positive budget must still be scheduled", scheduled.get()); //$NON-NLS-1$
        // Whether it CONCLUDES within 1 ms is a race; that it was allowed to try is the contract.
        assertTrue("a positive budget must never get the not-scheduled refusal", //$NON-NLS-1$
            read.concluded()
                || !read.deadlineFailure().contains("nothing was read and nothing was changed")); //$NON-NLS-1$
    }

    // ============ A guard may outlive the caller's bounded wait ============

    @Test
    public void testACompletionCallbackFiresWhenTheJobEndsNotWhenTheCallerStopsWaiting()
        throws Exception
    {
        // The seam an unattended-safety guard needs: the auth-dialog suppression around
        // get_applications' update-state loop must stay armed while the ABANDONED job keeps
        // running, or a dialog raised once the wedge clears has nobody to answer it.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        try
        {
            BoundedRead<String> read = ApplicationSupport.readBounded("Guarded read", //$NON-NLS-1$
                "the EDT application list for project 'Wedged'", 250L, () -> { //$NON-NLS-1$
                    awaitQuietly(release);
                    return "late"; //$NON-NLS-1$
                }, completed::countDown);

            assertFalse("the caller must be answered on its deadline", read.concluded()); //$NON-NLS-1$
            assertFalse("and the guard must NOT have been released yet", //$NON-NLS-1$
                completed.await(200L, TimeUnit.MILLISECONDS));
        }
        finally
        {
            release.countDown();
        }
        assertTrue("the guard is released when the JOB ends", //$NON-NLS-1$
            completed.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testAnUnscheduledReadStillReleasesItsGuard() throws Exception
    {
        // Otherwise the ONE path that never reaches the job manager would hold an armed guard
        // until its safety cap expired, for a read that did nothing at all.
        CountDownLatch completed = new CountDownLatch(1);

        BoundedRead<String> read = ApplicationSupport.readBounded("No budget", //$NON-NLS-1$
            "the EDT application list for project 'P'", 0L, () -> "unreachable", //$NON-NLS-1$ //$NON-NLS-2$
            completed::countDown);

        assertFalse(read.concluded());
        assertTrue("a read that was never scheduled must release the guard at once", //$NON-NLS-1$
            completed.await(1, TimeUnit.SECONDS));
    }

    /** Waits on {@code latch} inside an {@code IApplicationRead}, which may not throw it. */
    private static void awaitQuietly(CountDownLatch latch)
    {
        try
        {
            latch.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertDistinct(String one, String other)
    {
        assertFalse("the two outcomes must not share one sentence: " + other, //$NON-NLS-1$
            one.equals(other));
    }
}
