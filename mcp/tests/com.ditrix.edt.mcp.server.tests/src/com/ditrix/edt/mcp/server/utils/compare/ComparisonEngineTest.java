/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.settings.model.RestoredMergeSettings;

/**
 * Unit tests for {@link ComparisonEngine}, the read-only facade over EDT's comparison engine.
 * <p>
 * Everything here runs headlessly against a recording fake of {@code ComparisonEngine.Backend} —
 * the package-scoped, merge-free shape of {@code IComparisonManager}. That is not a convenience: a
 * fake of {@code IComparisonManager} itself would have to declare its merging methods, whose types
 * live in a package this bundle deliberately does not import, so the interface that keeps merging
 * unreachable in production is the same one that makes these tests possible.
 */
public class ComparisonEngineTest
{
    /** Every backend call the engine made, in order, by method name. */
    private static final class RecordingBackend
        implements ComparisonEngine.Backend
    {
        final List<String> calls = new ArrayList<>();
        final List<ComparisonProcessHandle> cancelled = new ArrayList<>();
        final List<ComparisonProcessHandle> stopped = new ArrayList<>();
        final List<CompareMergeProcessBatch> started = new ArrayList<>();

        boolean available = true;
        /** Whether the platform can be REACHED at all - the reading half of "service present". */
        boolean reachable = true;
        boolean active;
        ComparisonProcessStatus status = ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_FINISHED;
        RuntimeException statusFailure;
        IOException fileFailure;
        RestoredMergeSettings restored;
        List<ComparisonProcessHandle> handles = Collections.emptyList();

        @Override
        public boolean isAvailable()
        {
            calls.add("isAvailable"); //$NON-NLS-1$
            return available;
        }

        @Override
        public void startComparison(CompareMergeProcessBatch batch)
        {
            calls.add("startComparison"); //$NON-NLS-1$
            started.add(batch);
        }

        @Override
        public void cancel(ComparisonProcessHandle handle)
        {
            calls.add("cancel"); //$NON-NLS-1$
            cancelled.add(handle);
        }

        @Override
        public void stop(ComparisonProcessHandle handle)
        {
            calls.add("stop"); //$NON-NLS-1$
            stopped.add(handle);
        }

        @Override
        public PlatformAnswer<Boolean> hasActiveComparison()
        {
            calls.add("hasActiveComparison"); //$NON-NLS-1$
            return reachable ? PlatformAnswer.of(Boolean.valueOf(active)) : PlatformAnswer.unavailable();
        }

        @Override
        public PlatformAnswer<List<ComparisonProcessHandle>> handles(String projectName)
        {
            calls.add("handles"); //$NON-NLS-1$
            return reachable ? PlatformAnswer.of(handles) : PlatformAnswer.unavailable();
        }

        @Override
        public PlatformAnswer<ComparisonProcessStatus> status(ComparisonProcessHandle handle)
        {
            calls.add("status"); //$NON-NLS-1$
            if (statusFailure != null)
            {
                throw statusFailure;
            }
            return reachable ? PlatformAnswer.of(status) : PlatformAnswer.unavailable();
        }

        @Override
        public PlatformAnswer<IComparisonSession> session(ComparisonProcessHandle handle)
        {
            calls.add("session"); //$NON-NLS-1$
            // A fake of IComparisonSession is deliberately NOT built: it would drag in method
            // signature types from packages this bundle does not import. Every path exercised here
            // is one where EDT has no session for the handle.
            return reachable ? PlatformAnswer.of(null) : PlatformAnswer.unavailable();
        }

        @Override
        public RestoredMergeSettings restoreMergeSettings(ComparisonProcessHandle handle, String fileName)
            throws IOException
        {
            calls.add("restoreMergeSettings"); //$NON-NLS-1$
            if (fileFailure != null)
            {
                throw fileFailure;
            }
            return restored;
        }
    }

    /** The whole of {@code IComparisonDataSourceDescriptor} is one method, so a fake is honest. */
    private static final class FakeDescriptor
        implements IComparisonDataSourceDescriptor
    {
        private final String projectName;

        FakeDescriptor(String projectName)
        {
            this.projectName = projectName;
        }

        @Override
        public String getProjectName()
        {
            return projectName;
        }
    }

    private static ComparisonProcessHandle handle(String main, String other)
    {
        return new ComparisonProcessHandle(new FakeDescriptor(main), new FakeDescriptor(other),
            ComparisonScope.EMPTY_SCOPE);
    }

    private static ComparisonEngine engineOver(RecordingBackend backend)
    {
        return ComparisonEngine.forTesting(backend, ComparisonSessionRegistry.DEFAULT_IDLE_TTL_MILLIS);
    }

    @Test
    public void startLaunchesTheComparisonAndTouchesNothingElse()
    {
        RecordingBackend backend = new RecordingBackend();
        CompareMergeProcessBatch batch = new CompareMergeProcessBatch(Collections.emptyList());

        engineOver(backend).start(batch);

        // The exact call list, not "contains": a launch that also asked EDT to do something else
        // would still contain startComparison.
        assertEquals(Collections.singletonList("startComparison"), backend.calls); //$NON-NLS-1$
        assertSame(batch, backend.started.get(0));
    }

    /**
     * @param handle the comparison the session names
     * @return a session carrying it, built the way the registry builds one
     */
    private static ComparisonSessionRegistry.ComparisonSession session(ComparisonProcessHandle handle)
    {
        return new ComparisonSessionRegistry.ComparisonSession("cmp-1", "Main", handle, //$NON-NLS-1$ //$NON-NLS-2$
            new CompareMergeProcessBatch(Collections.emptyList()), 0L);
    }

    /**
     * ONE hand-back call reaches the platform, and the ending decides only which verb EDT records
     * it under. The exact call list rather than "contains": the previous shape asked the platform
     * to cancel AND then to stop, and the second call always arrived at a session the first had
     * already discarded.
     */
    @Test
    public void aCancelledEndingReachesTheHandleExactlyOnce()
    {
        RecordingBackend backend = new RecordingBackend();
        ComparisonProcessHandle handle = handle("Main", "Other"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).end(session(handle), SlotHandback.Ending.CANCELLED);

        assertEquals(Collections.singletonList("cancel"), backend.calls); //$NON-NLS-1$
        assertSame(handle, backend.cancelled.get(0));
    }

    @Test
    public void aClosedEndingReachesTheHandleExactlyOnce()
    {
        RecordingBackend backend = new RecordingBackend();
        ComparisonProcessHandle handle = handle("Main", "Other"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).end(session(handle), SlotHandback.Ending.CLOSED);

        assertEquals(Collections.singletonList("stop"), backend.calls); //$NON-NLS-1$
        assertSame(handle, backend.stopped.get(0));
    }

    @Test
    public void aNullHandleIsNotForwardedToThePlatform()
    {
        RecordingBackend backend = new RecordingBackend();
        ComparisonEngine engine = engineOver(backend);

        engine.end(null, SlotHandback.Ending.CLOSED);
        engine.end(session(null), SlotHandback.Ending.CANCELLED);

        assertTrue(backend.calls.isEmpty());
    }

    /**
     * The load-bearing one. {@code ComparisonProcessStatus} has no failure literal, so a comparison
     * that died keeps reporting the phase it died in. Here the status is frozen at
     * INITIALIZATION_FINISHED - a perfectly ordinary "still running" reading - while the batch
     * carries a failure. Reading the status alone yields "running"; the engine must say FAILED.
     */
    @Test
    public void aFailedBatchIsReportedAsFailedEvenWhileTheStatusStillSaysRunning()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.status = ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_FINISHED;
        CompareMergeProcessBatch batch = new CompareMergeProcessBatch(Collections.emptyList());
        IllegalStateException cause = new IllegalStateException("revision not found"); //$NON-NLS-1$
        batch.setFailureCause(cause);

        ComparisonEngine.Progress progress = engineOver(backend).progress(batch, handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.FAILED, progress.phase());
        assertSame(cause, progress.failure());
        assertTrue(progress.isTerminal());
        // The raw literal is still reported, so a caller can see WHAT it was doing when it died.
        assertEquals(ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_FINISHED, progress.status());
    }

    @Test
    public void theSameStatusWithoutAFailureIsStillRunning()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.status = ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_FINISHED;
        CompareMergeProcessBatch batch = new CompareMergeProcessBatch(Collections.emptyList());

        ComparisonEngine.Progress progress = engineOver(backend).progress(batch, handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.INITIALIZING, progress.phase());
        assertNull(progress.failure());
        assertFalse(progress.isTerminal());
    }

    @Test
    public void theTerminalStatusesAreMappedApart()
    {
        RecordingBackend backend = new RecordingBackend();
        ComparisonEngine engine = engineOver(backend);
        CompareMergeProcessBatch batch = new CompareMergeProcessBatch(Collections.emptyList());
        ComparisonProcessHandle handle = handle("Main", "Other"); //$NON-NLS-1$ //$NON-NLS-2$

        backend.status = ComparisonProcessStatus.COMPARISON_PROCESS_TOP_OBJECTS_MATCHED;
        assertEquals(ComparisonEngine.Phase.COMPARING, engine.progress(batch, handle).phase());

        backend.status = ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED;
        assertEquals(ComparisonEngine.Phase.FINISHED, engine.progress(batch, handle).phase());

        backend.status = ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED;
        assertEquals(ComparisonEngine.Phase.CANCELLED, engine.progress(batch, handle).phase());
    }

    /**
     * A status literal this feature never produces (they all belong to merging) is reported as
     * UNEXPECTED with the literal attached, rather than folded into a comparison phase. Guessing
     * would turn "somebody else is merging on this handle" into "still comparing".
     */
    @Test
    public void anUnexpectedStatusIsNotFoldedIntoAComparisonPhase()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.status = ComparisonProcessStatus.BEFORE_MERGE_PROCESS_STARTED;

        ComparisonEngine.Progress progress = engineOver(backend).progress(new CompareMergeProcessBatch(Collections.emptyList()),
            handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.UNEXPECTED, progress.phase());
        assertEquals(ComparisonProcessStatus.BEFORE_MERGE_PROCESS_STARTED, progress.status());
    }

    /**
     * "Could not ask" is not "not running", and it is not a platform status either. When the
     * status read throws, the phase is UNKNOWN - an absence that carries the read failure - and
     * NOT UNEXPECTED, which asserts that EDT reported a literal this feature does not handle.
     * The difference is load-bearing rather than cosmetic: a caller refuses a comparison outright
     * on UNEXPECTED and quotes the literal it was given, and here there is no literal to quote.
     */
    @Test
    public void aStatusReadThatThrowsIsAnAbsenceRatherThanAPlatformStatus()
    {
        RecordingBackend backend = new RecordingBackend();
        IllegalStateException readFailure = new IllegalStateException("service went away"); //$NON-NLS-1$
        backend.statusFailure = readFailure;

        ComparisonEngine.Progress progress = engineOver(backend).progress(new CompareMergeProcessBatch(Collections.emptyList()),
            handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.UNKNOWN, progress.phase());
        assertNull(progress.status());
        // The comparison's OWN failure is a different fact, and it is still absent here.
        assertNull(progress.failure());
        // ... while the read failure is carried, so the caller can name it instead of a status.
        assertSame(readFailure, progress.statusReadFailure());
        // Nothing was learned, so nothing is settled: asking again must stay allowed.
        assertFalse(progress.isTerminal());
    }

    /**
     * The same absence arrives without any exception at all: EDT's own manager answers null from
     * getStatus whenever it no longer holds the handle's session, which includes the race in
     * which the handle is still listed. It gets the same phase, for the same reason - reporting
     * it as UNEXPECTED would put a status in EDT's mouth that EDT never gave.
     */
    @Test
    public void aStatusEdtDoesNotAnswerIsUnknownRatherThanUnexpected()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.status = null;

        ComparisonEngine.Progress progress = engineOver(backend).progress(new CompareMergeProcessBatch(Collections.emptyList()),
            handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.UNKNOWN, progress.phase());
        assertNull(progress.status());
        // Nothing threw, so there is no logged failure to name - and none is invented.
        assertNull(progress.statusReadFailure());
        assertFalse(progress.isTerminal());
    }

    @Test
    public void hasActiveComparisonIsAskedOfThePlatformAndChangesNothing()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.active = true;
        ComparisonEngine engine = engineOver(backend);

        assertEquals(Boolean.TRUE, engine.hasActiveComparison().orElse(null));
        // A question about the state must not change it: the sweep that CAN end a session is a
        // separate, named call.
        assertEquals(Collections.singletonList("hasActiveComparison"), backend.calls); //$NON-NLS-1$
    }

    @Test
    public void handlesNeverReturnsNullEvenWhenThePlatformDoes()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.handles = null;

        PlatformAnswer<List<ComparisonProcessHandle>> answer =
            engineOver(backend).handles("SomeProject"); //$NON-NLS-1$

        // ANSWERED, and answered with an empty list: EDT was asked and holds nothing.
        assertTrue(answer.isAnswered());
        assertTrue(answer.orElse(null).isEmpty());
    }

    /**
     * The distinction the whole reading side turns on, pinned as its own fact: "EDT holds nothing
     * for this project" and "EDT could not be asked" are BOTH empty when they are collapsed into a
     * list, and they must not be collapsed. A consumer read the second as the first and dropped a
     * live session without stopping it.
     */
    @Test
    public void anUnreachablePlatformIsNotAnEmptyHandleList()
    {
        RecordingBackend asked = new RecordingBackend();
        asked.handles = Collections.emptyList();
        RecordingBackend unreachable = new RecordingBackend();
        unreachable.reachable = false;

        PlatformAnswer<List<ComparisonProcessHandle>> answered =
            engineOver(asked).handles("SomeProject"); //$NON-NLS-1$
        PlatformAnswer<List<ComparisonProcessHandle>> absent =
            engineOver(unreachable).handles("SomeProject"); //$NON-NLS-1$

        assertTrue("EDT answered, and it answered 'nothing'", answered.isAnswered()); //$NON-NLS-1$
        assertTrue(answered.orElse(null).isEmpty());
        assertTrue("EDT was never asked, so there is no answer to quote", absent.isUnavailable()); //$NON-NLS-1$
        // And the two are told apart WITHOUT looking at a value: the caller that got this wrong
        // was looking at the list.
        assertNotEquals(answered.isAnswered(), absent.isAnswered());
    }

    /**
     * The same distinction on the status read, which decides the poll loop's phase. When the
     * service is gone the platform said nothing because nobody asked it, and a caller that quotes
     * "EDT answered no status" is crediting the platform with a report it never made.
     */
    @Test
    public void aStatusNobodyCouldAskForSaysSoRatherThanReadingAsAnAnsweredNothing()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.reachable = false;
        ComparisonProcessHandle handle = handle("Main", "Other"); //$NON-NLS-1$ //$NON-NLS-2$

        ComparisonEngine.Progress progress = engineOver(backend).progress(null, handle);

        assertEquals(ComparisonEngine.Phase.UNKNOWN, progress.phase());
        assertNull(progress.status());
        assertNull("nothing threw, so there is no read failure to name", //$NON-NLS-1$
            progress.statusReadFailure());
        assertFalse("the platform was never asked", progress.statusWasAsked()); //$NON-NLS-1$
    }

    /** The control for the test above: an answered {@code null} status WAS asked for. */
    @Test
    public void aStatusEdtItselfDeclinedToGiveCountsAsAsked()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.status = null;

        ComparisonEngine.Progress progress =
            engineOver(backend).progress(null, handle("Main", "Other")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonEngine.Phase.UNKNOWN, progress.phase());
        assertTrue("EDT was asked and answered nothing", progress.statusWasAsked()); //$NON-NLS-1$
    }

    /**
     * The facade offers no way to record anything ONTO a comparison: this half reads one, and a
     * merge decision goes into EDT's merge-rules FILE instead. The pin is on the SHAPE rather than
     * on a call, because the defect it guards against is a write path being added back and then
     * kept alive by a test of its own - {@code NoMergeStarterRatchetTest} bans the platform's rule
     * setters inside the facade, and this says the same thing about the surface a tool can see.
     */
    @Test
    public void theFacadeExposesNoWriteOntoAComparison()
    {
        List<String> writers = new ArrayList<>();
        for (Method method : ComparisonEngine.class.getMethods())
        {
            if (isWrite(method.getName()))
            {
                writers.add(method.getName());
            }
        }

        assertEquals("the facade must expose no write onto a comparison: " + writers, //$NON-NLS-1$
            Collections.emptyList(), writers);
    }

    /**
     * The same statement one level down, where a write would actually have to be plumbed: the
     * merge-free backend declares only reads and lifetime calls, so no implementation of it - the
     * production one included - has a platform write to delegate to.
     */
    @Test
    public void theBackendDeclaresNoWriteOntoAComparison()
    {
        List<String> writers = new ArrayList<>();
        for (Method method : ComparisonEngine.Backend.class.getDeclaredMethods())
        {
            if (isWrite(method.getName()))
            {
                writers.add(method.getName());
            }
        }

        assertEquals("the backend must declare no write onto a comparison: " + writers, //$NON-NLS-1$
            Collections.emptyList(), writers);
    }

    /**
     * The names a write would arrive under: the platform's two rule setters, the facade's former
     * wrapper around them, and the platform's rules-file writer, which serialises the decisions
     * recorded on a live comparison and so only has an input if one of the others exists.
     *
     * @param methodName a method name
     * @return whether it names a write onto a comparison
     */
    private static boolean isWrite(String methodName)
    {
        return methodName.startsWith("applyRule") || methodName.startsWith("setMergeRule") //$NON-NLS-1$ //$NON-NLS-2$
            || methodName.startsWith("setCustomMergeSettings") //$NON-NLS-1$
            || methodName.startsWith("saveMergeSettings"); //$NON-NLS-1$
    }

    /**
     * The facade is unreachable until the bundle installs it and again once it uninstalls it, and
     * it is also unreachable while EDT's service is simply not registered. All three read the same
     * way to a tool: {@code get()} is empty, so the tool says "not available" instead of throwing.
     */
    @Test
    public void theFacadeIsUnreachableBeforeInstallWhileTheServiceIsAbsentAndAfterUninstall()
    {
        ComparisonEngine.uninstall();
        assertFalse(ComparisonEngine.get().isPresent());

        ComparisonEngine.install(() -> null);
        assertFalse("a supplier that yields no service must read as unavailable", //$NON-NLS-1$
            ComparisonEngine.get().isPresent());

        ComparisonEngine.uninstall();
        assertFalse(ComparisonEngine.get().isPresent());
    }

    /**
     * Taking the facade down does not merely stop NEW work from finding it: the registry it owned
     * refuses to own anything else from that moment.
     * <p>
     * Clearing the singleton is not enough. {@code BackgroundJobs.close()} waits two seconds and
     * interrupts, and a launch worker stuck in a git revision resolution goes on running with the
     * OLD engine in hand - so it reaches {@code sessions().register(...)} after the shutdown has
     * walked the map. That session would sit in a registry nobody sweeps again, and the comparison
     * it is about to start would hold EDT's single slot until the JVM exits under an id nothing
     * can name. The refusal belongs to the registry so it cannot be lost to timing.
     */
    @Test
    public void aWorkerThatArrivesAfterUninstallCannotRegisterAComparison()
    {
        ComparisonEngine.install(() -> null);
        ComparisonSessionRegistry sessions = ComparisonEngine.installedSessions();
        assertNotNull("the installed facade must expose its registry", sessions); //$NON-NLS-1$

        ComparisonEngine.uninstall();

        try
        {
            sessions.register(handle("Trade", "Trade-other"), //$NON-NLS-1$ //$NON-NLS-2$
                new CompareMergeProcessBatch(Collections.emptyList()));
            fail("a registry whose facade is gone must refuse to own a comparison"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue("the refusal must say nothing was started: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("Nothing was started")); //$NON-NLS-1$
        }
        assertEquals(0, sessions.size());
    }

    /**
     * The three LIFETIME calls, driven against the PRODUCTION backend with no service behind it.
     * <p>
     * Each of them used to return quietly here, and quietly is indistinguishable from success at
     * the call site: a launch that had checked the facade a moment earlier went on to publish
     * "Comparison cmp-N started." for a comparison EDT was never asked to run, and a cancellation
     * answered STOPPED for one that was still running. The service disappearing between the
     * availability check and the call is exactly the window this reproduces.
     */
    @Test
    public void aLifetimeCallThatCannotReachThePlatformSaysSoInsteadOfReturningQuietly()
    {
        ComparisonEngine.Backend backend = ComparisonEngine.managerBackend(() -> null);

        assertServiceUnavailable("startComparison", //$NON-NLS-1$
            () -> backend.startComparison(new CompareMergeProcessBatch(Collections.emptyList())));
        assertServiceUnavailable("cancel", () -> backend.cancel(handle("Main", "Other"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertServiceUnavailable("stop", () -> backend.stop(handle("Main", "Other"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The other half of the same rule, and the control for the test above: a READ that cannot be
     * made ANSWERS instead of throwing, because a throw would turn one unlucky tick into a
     * refusal. What it answers is {@code unavailable} rather than {@code null}/empty - it still
     * does not throw, but it no longer looks like the platform saying "there is nothing there".
     */
    @Test
    public void aReadThatCannotReachThePlatformStillAnswersRatherThanThrowing()
    {
        ComparisonEngine.Backend backend = ComparisonEngine.managerBackend(() -> null);

        assertFalse(backend.isAvailable());
        // Still no throw - and no longer a silent "nothing there" either: every one of them says
        // the question could not be ASKED, which is the fact a caller has to act on.
        assertTrue(backend.hasActiveComparison().isUnavailable());
        assertTrue(backend.handles("SomeProject").isUnavailable()); //$NON-NLS-1$
        assertTrue(backend.status(handle("Main", "Other")).isUnavailable()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.session(handle("Main", "Other")).isUnavailable()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param operation the call being made, for the failure message
     * @param call the call
     */
    private static void assertServiceUnavailable(String operation, Runnable call)
    {
        try
        {
            call.run();
            fail(operation + " must report that it never reached the platform"); //$NON-NLS-1$
        }
        catch (ComparisonEngine.ServiceUnavailableException e)
        {
            assertTrue("the failure must say the call did not reach EDT: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("did not reach the platform")); //$NON-NLS-1$
        }
    }

    /**
     * A rules file that cannot be read is a caller-fixable mistake, so the message must name the
     * FILE. A raw {@code IOException} escaping the facade would reach the caller as "null" or as an
     * absolute path with no explanation of what was being read.
     */
    @Test
    public void anUnreadableRulesFileIsRefusedByName()
    {
        RecordingBackend backend = new RecordingBackend();
        backend.fileFailure = new IOException("rules.xml (The system cannot find the file)"); //$NON-NLS-1$

        try
        {
            engineOver(backend).restoreMergeSettings(handle("Main", "Other"), "rules.xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the unreadable file to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal must name the file: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("rules.xml")); //$NON-NLS-1$
        }
    }
}
