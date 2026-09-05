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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        /** The path the platform was last handed to restore from. */
        String restoredFrom;
        /** The entry names the file on THAT path held at the moment the platform opened it. */
        List<String> entriesSeen = Collections.emptyList();
        /** Whether that path was a regular file then - what EDT's own reader branches on. */
        boolean handedARegularFile;
        /** How big the file on that path was - the copy this launch made, when there was one. */
        long snapshotBytes = -1L;
        /** The text of the first entry in it, so an empty copy is not mistaken for a faithful one. */
        String firstEntryText = ""; //$NON-NLS-1$
        /** Stands in for another process replacing the caller's file while the platform reads. */
        Runnable beforeReading;
        /** Whether the platform's failure quotes the path it was handed, as a real one would. */
        boolean failNamingThePathItWasHanded;

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
            restoredFrom = fileName;
            if (beforeReading != null)
            {
                beforeReading.run();
            }
            // Read where EDT reads: inside the call, from the path it was handed. Recording the
            // path alone would not tell a snapshot from the caller's file after a replacement -
            // both are "a path" - and it is the CONTENT the platform matches its entry against.
            handedARegularFile = Files.isRegularFile(Paths.get(fileName));
            entriesSeen = entryNamesOf(fileName);
            snapshotBytes = sizeOf(fileName);
            firstEntryText = firstEntryTextOf(fileName);
            if (failNamingThePathItWasHanded)
            {
                throw new IOException("could not read '" + fileName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (fileFailure != null)
            {
                throw fileFailure;
            }
            return restored;
        }
    }

    /**
     * A descriptor that throws when asked for its project name.
     * <p>
     * The one thing the entry id is built out of, refusing to answer - which is what a handle whose
     * descriptors are not what it claims does.
     */
    private static final class UnnameableDescriptor
        implements IComparisonDataSourceDescriptor
    {
        @Override
        public String getProjectName()
        {
            throw new IllegalStateException("this descriptor has no project"); //$NON-NLS-1$
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

    // ======= A zip of merge settings is addressed by the three PROJECT NAMES =======

    /**
     * The defect these tests pin: {@code mergeRulesFile} was checked by EXTENSION alone, so a zip
     * saved from a different comparison passed every check, the comparison started, and the
     * platform - which restores the entry named after the comparison's own project triple and
     * ignores an archive that has none - applied nothing and logged a warning nobody sees. The
     * parameter and the guide meanwhile said the decisions were in place before the comparison
     * opened. That is a report of work that never happened.
     * <p>
     * Measured from {@code ComparisonManager.getComparisonSessionStringId} and
     * {@code deserializeMergeSettingsFromZipFile}, identical in {@code com._1c.g5.v8.dt.compare}
     * 28.0.1 (EDT 2026.1.2) and 29.0.0 (EDT 2026.2.0).
     */
    @Test
    public void mergeRulesEntryIdNamesTheThreeProjectsOfAThreeWayComparison()
    {
        assertEquals("Main_Other_Ancestor", //$NON-NLS-1$
            ComparisonEngine.mergeRulesEntryId(threeWayHandle("Main", "Other", "Ancestor"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The literal is the platform's: a two-way comparison formats {@code NONE} into the ancestor's
     * slot. An id built any other way would not be the one an archive was named after, so this
     * pins the spelling rather than the shape.
     */
    @Test
    public void mergeRulesEntryIdSpellsAMissingAncestorAsNone()
    {
        assertEquals("Main_Other_NONE", //$NON-NLS-1$
            ComparisonEngine.mergeRulesEntryId(handle("Main", "Other"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The separator is not injective, and the surface has to stop claiming that it is.
     * <p>
     * {@code String.format("%s_%s_%s", ...)} joins with {@code _}, which is itself a legal
     * character in an Eclipse project name, so two DIFFERENT triples produce the same entry name:
     * a zip written for one of them is restored by a comparison over the other. Every place that
     * used to say "only a comparison over a different set of projects finds nothing here" was
     * asserting the converse of what this construction supports.
     * <p>
     * Pinned as an equality between two computed ids rather than against a literal, so the pin
     * survives any future change of the format string that keeps the collision, and fails the
     * moment the collision is actually removed - which would be a change of the entry name and
     * therefore a change EDT has to make first.
     */
    @Test
    public void mergeRulesEntryIdCollidesBetweenTwoDifferentProjectTriples()
    {
        String first = ComparisonEngine.mergeRulesEntryId(
            threeWayHandle("A_B", "C", "D")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String second = ComparisonEngine.mergeRulesEntryId(
            threeWayHandle("A", "B_C", "D")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("two different triples spell one entry name", first, second); //$NON-NLS-1$
        assertEquals("A_B_C_D", first); //$NON-NLS-1$
    }

    /**
     * And it is not a SET either: the three names are positional, so swapping main and other
     * addresses a different entry. "The three project names" reads like an unordered collection,
     * which is why the surface now says the exact string instead.
     */
    @Test
    public void mergeRulesEntryIdChangesWhenMainAndOtherAreSwapped()
    {
        assertNotEquals("main and other are positions, not members of a set", //$NON-NLS-1$
            ComparisonEngine.mergeRulesEntryId(threeWayHandle("Alpha", "Beta", "Base")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ComparisonEngine.mergeRulesEntryId(threeWayHandle("Beta", "Alpha", "Base"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aZipAddressedToAnotherComparisonNeverReachesThePlatform() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("foreign.zip", "X_Y_Z.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                zip.toString());
            fail("a zip that addresses another comparison must be refused, not silently ignored"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            // The message is pinned separately; what this test is about is the call list below.
        }

        // The whole point of refusing HERE: the platform is not asked, so the batch that would
        // take EDT's single comparison slot is never built. An empty list, not "does not contain":
        // a refusal that had already spoken to EDT would still satisfy the weaker assertion.
        assertEquals("nothing may reach EDT once the file is known not to address this comparison", //$NON-NLS-1$
            Collections.emptyList(), backend.calls);
    }

    @Test
    public void theRefusalNamesTheEntryItLookedForAndWhatTheArchiveHolds() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("foreign.zip", "X_Y_Z.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                zip.toString());
            fail("expected the unaddressed archive to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            String message = e.getMessage();
            assertTrue("the refusal must name the file: " + message, //$NON-NLS-1$
                message.contains(zip.toString()));
            assertTrue("it must name the entry EDT would look for: " + message, //$NON-NLS-1$
                message.contains("Main_Other_Ancestor")); //$NON-NLS-1$
            assertTrue("it must name what the archive holds instead: " + message, //$NON-NLS-1$
                message.contains("X_Y_Z.xml")); //$NON-NLS-1$
            assertTrue("it must name the way out through merge_rules: " + message, //$NON-NLS-1$
                message.contains("merge_rules")); //$NON-NLS-1$
        }
    }

    /**
     * The way out this refusal offers has to be one that WORKS on the EDT the caller is running.
     * <p>
     * It used to end with "mode 'write' produces '.xml'", which was true of the tool and useless
     * on EDT 2026.2: that version's {@code deserializeMergeSettings} asserts the file is a zip and
     * reads nothing else, so the advice sent the caller from an archive the launch ignores to a
     * file the launch refuses. The advice is now the zip this comparison would accept, and its
     * entry name is quoted so the caller can see what has to match.
     */
    @Test
    public void theRefusalOffersAZipAddressedToThisComparisonRatherThanAnXmlFile() throws IOException
    {
        Path zip = zipHolding("foreign.zip", "X_Y_Z.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the unaddressed archive to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            String message = e.getMessage();
            assertTrue("the advice must be the zip this comparison would read: " + message, //$NON-NLS-1$
                message.contains("produces a zip whose entry is 'Main_Other_Ancestor'")); //$NON-NLS-1$
            // Pinned as an ABSENCE: the old sentence names a container EDT 2026.2 does not read,
            // and a message that merely GAINED the zip advice while keeping it would still send
            // half its readers to a file their platform refuses.
            assertFalse("it must not promise an '.xml' file: " + message, //$NON-NLS-1$
                message.contains("mode 'write' produces '.xml'")); //$NON-NLS-1$
        }
    }

    /**
     * The other half, and the one a lookup rebuilt from guesswork would break: a zip that DOES
     * carry this comparison's entry is legitimate - it is what the comparison editor saves - and
     * must pass through untouched. A false refusal here would cost the caller the one file that
     * actually works.
     */
    @Test
    public void aZipSavedFromThisComparisonIsNotRefused() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertEquals(Collections.singletonList("restoreMergeSettings"), backend.calls); //$NON-NLS-1$
    }

    /**
     * An {@code .xml} file is the settings document itself and carries no address, so there is
     * nothing about it to disprove and its path is unchanged. Pinned as an ABSENCE - the file does
     * not exist on disk, and a check that judged xml files by their content would have to open it.
     */
    @Test
    public void anXmlRulesFileIsNotJudgedByEntryName()
    {
        RecordingBackend backend = new RecordingBackend();

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "nowhere/rules.xml"); //$NON-NLS-1$

        assertEquals(Collections.singletonList("restoreMergeSettings"), backend.calls); //$NON-NLS-1$
    }

    /**
     * A zip this process could not open is one nothing was learnt about, and "not read" is not
     * "does not address this comparison". The platform opens the same path with its own
     * {@code ZipFile} and fails the launch naming the file, so the honest answer here is to claim
     * nothing and let that happen.
     */
    @Test
    public void aZipThatCannotBeOpenedIsLeftToThePlatform()
    {
        RecordingBackend backend = new RecordingBackend();

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "nowhere/missing.zip"); //$NON-NLS-1$

        assertEquals(Collections.singletonList("restoreMergeSettings"), backend.calls); //$NON-NLS-1$
    }

    // ==== the check and the restore read ONE file, not one path twice ====

    /**
     * The finding: {@code zipHoldsNothingFor} opened the caller's path, satisfied itself that the
     * archive held this comparison's entry, and closed it - and the platform then opened the SAME
     * PATH again. A process replacing the file in between got a comparison that restored NOTHING
     * and said nothing about it, which is the exact failure the check exists to prevent, reached
     * straight through the check.
     * <p>
     * The assertion is on what the platform READ, not on the path it was handed: a path is a path
     * either way, and only the content tells a private snapshot from the caller's file after
     * somebody else has written over it.
     */
    @Test
    public void aZipReplacedAfterItsCheckIsStillTheOneThePlatformReads() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        backend.beforeReading = replaceWith(zip, "X_Y_Z.xml"); //$NON-NLS-1$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertEquals("the platform must read the archive that was checked, not whatever is on " //$NON-NLS-1$
            + "the caller's path by the time it opens it", //$NON-NLS-1$
            List.of("Main_Other_Ancestor.xml"), backend.entriesSeen); //$NON-NLS-1$
    }

    /**
     * And the snapshot is the platform's to read on its own terms: EDT's reader takes the path as a
     * REGULAR FILE and asserts its extension is {@code zip} before it opens anything (measured from
     * {@code ComparisonManager.deserializeMergeSettings}, {@code com._1c.g5.v8.dt.compare} 29.0.0).
     * A temporary named anything else would fail the launch on a file the caller never chose.
     */
    @Test
    public void theFileHandedToThePlatformIsARegularZip() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertTrue("EDT asserts the extension before it opens anything: " + backend.restoredFrom, //$NON-NLS-1$
            backend.restoredFrom.endsWith(".zip")); //$NON-NLS-1$
        assertTrue("and reads a directory instead when the path is not a regular file: " //$NON-NLS-1$
            + backend.restoredFrom, backend.handedARegularFile);
    }

    /**
     * The snapshot is a copy and not the caller's own file - pinned separately because the content
     * assertion above would also pass if the caller's file simply had not been replaced yet, and
     * this one would not.
     */
    @Test
    public void theFileHandedToThePlatformIsNotTheCallersOwnPath() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertNotEquals("the platform reads the snapshot; the caller's path is only named", //$NON-NLS-1$
            zip.toString(), backend.restoredFrom);
    }

    /** A snapshot that outlived its restore would be litter, one file per launch. */
    @Test
    public void theSnapshotIsRemovedOnceTheDecisionsAreRestored() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertFalse("the snapshot must not outlive the call that took it: " + backend.restoredFrom, //$NON-NLS-1$
            Files.exists(Paths.get(backend.restoredFrom)));
    }

    /**
     * And on the REFUSING path, which is the one a clean-up written after the restore would miss:
     * the platform is never asked there, so nothing records the temporary's name and only the
     * directory can be asked whether one was left behind.
     */
    @Test
    public void theSnapshotIsRemovedWhenTheArchiveIsRefused() throws IOException
    {
        Path zip = zipHolding("foreign.zip", "X_Y_Z.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> before = snapshotsInTheTempArea();

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the unaddressed archive to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            // The refusal is pinned elsewhere; what is left in the temp area is the point.
        }

        List<String> left = snapshotsInTheTempArea();
        left.removeAll(before);
        assertEquals("a refused write must not leave its snapshot behind", List.of(), left); //$NON-NLS-1$
    }

    /**
     * A DIRECTORY named {@code .zip} is not an archive, and it must reach the platform as the
     * caller's own path - the pinned "not read is not a refusal" answer.
     * <p>
     * The reason this needs its own pin: {@code Files.copy} calls a directory copied once it has
     * created an EMPTY DIRECTORY of the target's name, so a snapshot taken that way "succeeds",
     * the lookup then fails open on it, and EDT is handed a directory - which its reader lists for
     * archives, finds none in, and restores nothing from, silently. That is the failure the check
     * exists against, produced by the check's own snapshot.
     */
    @Test
    public void aZipPathThatIsADirectoryIsLeftToThePlatformAsTheCallersOwnPath() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path dir = Files.createTempDirectory("comparison-engine-test"); //$NON-NLS-1$
        dir.toFile().deleteOnExit();
        Path notAnArchive = dir.resolve("rules.zip"); //$NON-NLS-1$
        Files.createDirectory(notAnArchive);
        notAnArchive.toFile().deleteOnExit();

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            notAnArchive.toString());

        assertEquals("nothing was snapshotted and nothing was learnt, so the caller's own path " //$NON-NLS-1$
            + "is what the platform is asked about", //$NON-NLS-1$
            notAnArchive.toString(), backend.restoredFrom);
    }

    /**
     * The platform is reading a temporary, so what it says about a failure carries the
     * temporary's name - and a caller told "could not read /tmp/edt-mcp-merge-rules1234.zip" has
     * been handed the name of a file they never chose, cannot inspect, and by then no longer
     * exists. The snapshot's name is taken back out of the message.
     */
    @Test
    public void aPlatformFailureIsReportedAgainstTheCallersFileAndNotTheSnapshot() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        backend.failNamingThePathItWasHanded = true;
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                zip.toString());
            fail("expected the platform's failure to be reported"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertFalse("the temporary is not a file the caller can act on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("edt-mcp-merge-rules")); //$NON-NLS-1$
        }
    }

    /**
     * The other half of that substitution, in its own method: the caller's path has to be there
     * TWICE over - as the file this call was about, and as the file the platform failed on - so a
     * substitution that simply deleted the temporary's name would be caught here.
     */
    @Test
    public void aPlatformFailureQuotesTheCallersPathWhereItNamedTheSnapshot() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        backend.failNamingThePathItWasHanded = true;
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                zip.toString());
            fail("expected the platform's failure to be reported"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the platform's own sentence must name the caller's file: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("could not read '" + zip + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ==== the snapshot is of the ENTRY that will be read, not of the archive around it ====
    //
    // The copy was a byte-for-byte transferTo of the whole file, which put no bound at all on what
    // a launch wrote into the system temporary directory: a valid archive holding this
    // comparison's small settings entry beside a multi-gigabyte unrelated one was duplicated in
    // full before anything was even looked up, and neither the codec's document bound nor its
    // entry-count bound covers a raw copy. The platform reads exactly one entry - measured from
    // ComparisonManager.deserializeMergeSettingsFromZipFile, which returns at the FIRST entry whose
    // name minus its extension is the comparison's id and opens no other - so the copy is of that
    // entry, and the snapshot's size is the size of what will actually be read.

    /** Big enough that a whole-archive copy is unmistakable, small enough to write in a test. */
    private static final int BULKY_ENTRY_BYTES = 2 * 1024 * 1024;

    /** What a one-entry archive of "<Settings/>" comfortably fits in. */
    private static final int A_SMALL_ARCHIVE = 4096;

    @Test
    public void theSnapshotHoldsOnlyTheEntryThisComparisonRestoresFrom() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipWithABulkyNeighbour("mixed.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            "bulk.bin", BULKY_ENTRY_BYTES); //$NON-NLS-1$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertEquals("the platform opens ONE entry and no other, so that is the whole of what " //$NON-NLS-1$
            + "the private copy has to hold", //$NON-NLS-1$
            List.of("Main_Other_Ancestor.xml"), backend.entriesSeen); //$NON-NLS-1$
    }

    /**
     * And the SIZE, in its own method: an implementation that copied the archive whole would still
     * satisfy nothing above about bytes, and the bytes are what the finding is about.
     */
    @Test
    public void theSnapshotIsTheSizeOfWhatIsReadAndNotOfTheArchive() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipWithABulkyNeighbour("mixed.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            "bulk.bin", BULKY_ENTRY_BYTES); //$NON-NLS-1$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertTrue("the copy is proportional to the entry that will be read, not to the archive " //$NON-NLS-1$
            + "it came out of - archive " + Files.size(zip) + " bytes, copy " //$NON-NLS-1$ //$NON-NLS-2$
            + backend.snapshotBytes, backend.snapshotBytes < A_SMALL_ARCHIVE);
    }

    /**
     * The positive control for both: the entry has to arrive with its CONTENT. A copy that wrote
     * the name and no bytes would be small, hold one entry, and restore nothing - which is the
     * silent no-op this whole area exists against.
     */
    @Test
    public void theCopiedEntryCarriesTheBytesItHadInTheArchive() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipWithABulkyNeighbour("mixed.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            "bulk.bin", BULKY_ENTRY_BYTES); //$NON-NLS-1$

        engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            zip.toString());

        assertEquals("the entry the platform reads is the entry the caller's archive held", //$NON-NLS-1$
            "<Settings/>", backend.firstEntryText); //$NON-NLS-1$
    }

    // ==== "could not be READ" and "could not be SNAPSHOTTED" are different answers ====
    //
    // The first claims nothing: the platform opens the caller's own path with its own ZipFile and
    // fails the launch naming their file, and a refusal invented here would be about an archive
    // this code never managed to look at (pinned by aZipThatCannotBeOpenedIsLeftToThePlatform).
    // The second is this server establishing that it cannot keep the promise the copy makes.
    // Falling back to the caller's path there would restore from a file nothing checked - the very
    // race the snapshot closes - and would make the bound on the copy avoidable by exceeding it.

    @Test
    public void anEntryTooLargeToCopyRefusesInsteadOfFallingBackToTheCallersPath() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipWhoseEntryExpandsTo("huge.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesCodec.MAX_DOCUMENT_BYTES + 1);

        try
        {
            engineOver(backend).restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                zip.toString());
            fail("a snapshot that could not be taken must refuse, not hand over the unchecked path"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            // The message is pinned below; what this one is about is the call list.
        }

        assertEquals("nothing may reach EDT once this server knows it cannot verify what it " //$NON-NLS-1$
            + "would be launching from", Collections.emptyList(), backend.calls); //$NON-NLS-1$
    }

    @Test
    public void theRefusalForAnUncopiableEntrySaysWhatWasRefusedAndWhy() throws IOException
    {
        Path zip = zipWhoseEntryExpandsTo("huge.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesCodec.MAX_DOCUMENT_BYTES + 1);

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the uncopiable entry to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            String message = e.getMessage();
            assertTrue("the refusal must name the caller's file: " + message, //$NON-NLS-1$
                message.contains(zip.toString()));
            assertTrue("it must name the entry that could not be copied: " + message, //$NON-NLS-1$
                message.contains("Main_Other_Ancestor.xml")); //$NON-NLS-1$
            assertTrue("it must say what the bound was: " + message, //$NON-NLS-1$
                message.contains("past 16 MB")); //$NON-NLS-1$
            assertTrue("and it must say that nothing was applied, so a caller is not left " //$NON-NLS-1$
                + "wondering whether some decisions were: " + message, //$NON-NLS-1$
                message.contains("No decision was applied")); //$NON-NLS-1$
        }
    }

    /**
     * The other end of the same distinction, as an ABSENCE: a refusal about a snapshot must not be
     * worded as a refusal about the ARCHIVE'S ADDRESSING. The archive here addresses this
     * comparison perfectly well, and telling the caller to go and find a different zip would send
     * them after a file that is not the problem.
     */
    @Test
    public void theRefusalForAnUncopiableEntryDoesNotCallTheArchiveUnaddressed() throws IOException
    {
        Path zip = zipWhoseEntryExpandsTo("huge.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesCodec.MAX_DOCUMENT_BYTES + 1);

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the uncopiable entry to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertFalse("the archive holds exactly what this comparison looks for: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("holds nothing for THIS comparison")); //$NON-NLS-1$
        }
    }

    /** A refused snapshot leaves no half-written temporary behind either. */
    @Test
    public void theSnapshotIsRemovedWhenTheEntryCouldNotBeCopied() throws IOException
    {
        Path zip = zipWhoseEntryExpandsTo("huge.zip", "Main_Other_Ancestor.xml", //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesCodec.MAX_DOCUMENT_BYTES + 1);
        List<String> before = snapshotsInTheTempArea();

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("expected the uncopiable entry to be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            // What is left in the temp area is the point.
        }

        List<String> left = snapshotsInTheTempArea();
        left.removeAll(before);
        assertEquals("a refused snapshot must not leave its temporary behind", List.of(), left); //$NON-NLS-1$
    }

    /**
     * The entry id is read off the HANDLE, and reading it can throw - so it is read before
     * anything is created. Nothing between here and the temporary's own {@code finally} is
     * inside any cleanup, so a temporary created first would be left in the system temp area by
     * an exception thrown after it.
     *
     * @throws IOException when the temp area cannot be listed
     */
    @Test
    public void aHandleThatCannotBeNamedLeavesNoTemporaryBehind() throws IOException
    {
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonProcessHandle unnameable = new ComparisonProcessHandle(new FakeDescriptor("Main"), //$NON-NLS-1$
            new UnnameableDescriptor(), new FakeDescriptor("Ancestor"), ComparisonScope.EMPTY_SCOPE); //$NON-NLS-1$
        List<String> before = snapshotsInTheTempArea();

        try
        {
            engineOver(new RecordingBackend()).restoreMergeSettings(unnameable, zip.toString());
            fail("a handle that cannot be named cannot be launched from"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            // The platform's own failure is left as the platform threw it; the temp area is the
            // point of this test.
        }

        List<String> left = snapshotsInTheTempArea();
        left.removeAll(before);
        assertEquals("nothing may be created before the id that decides what to copy is known", //$NON-NLS-1$
            List.of(), left);
    }

    // ==== the answer follows what was READ, not what happened to run first ====
    //
    // The temporary used to be created BEFORE the copy, which is the first statement that
    // touches the source at all. So a temp area that could not take a file answered with a
    // refusal for an archive this process had never tried to open - and when that archive was
    // itself missing, the caller was refused over a file whose real problem was that it does
    // not exist. Two states decided by statement order rather than by evidence.

    /** A temp area that takes nothing. The copy is unreachable and says so if it is reached. */
    private static final ComparisonEngine.SnapshotIo NO_TEMP_AREA = new ComparisonEngine.SnapshotIo()
    {
        @Override
        public Path createTemporary() throws IOException
        {
            throw new IOException("No space left on device"); //$NON-NLS-1$
        }

        @Override
        public MergeRulesCodec.AddressedEntryCopy copyInto(Path source, String entryId, Path target)
        {
            throw new IllegalStateException("nothing may be copied into a file that was never made"); //$NON-NLS-1$
        }
    };

    /**
     * @param backend the backend to drive
     * @param io how the snapshot is made
     * @return the facade over both
     */
    private static ComparisonEngine engineOver(RecordingBackend backend, ComparisonEngine.SnapshotIo io)
    {
        return ComparisonEngine.forTesting(backend, ComparisonSessionRegistry.DEFAULT_IDLE_TTL_MILLIS,
            io);
    }

    /**
     * A source that could not be READ is left to the platform even when a snapshot could not
     * have been TAKEN either. Both things are wrong at once, and only one of them is this
     * server's to report: nothing was learnt about the archive, so nothing is claimed about it,
     * and the platform opens the caller's own path and fails the launch naming their file.
     * <p>
     * This is the ordering pin. With the temporary created first, the refusal was reached
     * without the source having been touched at all.
     */
    @Test
    public void aZipThatCannotBeOpenedIsLeftToThePlatformEvenWithNowhereToPutASnapshot()
    {
        RecordingBackend backend = new RecordingBackend();

        engineOver(backend, NO_TEMP_AREA).restoreMergeSettings(
            threeWayHandle("Main", "Other", "Ancestor"), "nowhere/missing.zip"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("an archive nothing managed to open is the platform's to fail on", //$NON-NLS-1$
            Collections.singletonList("restoreMergeSettings"), backend.calls); //$NON-NLS-1$
        assertEquals("and it is the CALLER'S path that goes there, there being no snapshot", //$NON-NLS-1$
            "nowhere/missing.zip", backend.restoredFrom); //$NON-NLS-1$
    }

    /**
     * The other half of the same order, as an ABSENCE: an archive that WAS read and holds this
     * comparison's entry, with nowhere to put the copy, is still a refusal. The fix above must
     * not have turned "could not be TAKEN" into "claims nothing" - that would hand the platform
     * a path nothing checked, which is what the snapshot exists to prevent.
     *
     * @throws IOException when the archive cannot be written
     */
    @Test
    public void aReadableArchiveWithNowhereToPutTheSnapshotIsStillRefused() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend, NO_TEMP_AREA).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a snapshot that could not be taken must refuse, not hand over the path"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal must name the caller's file: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(zip.toString()));
        }

        assertEquals("nothing may reach EDT once this server knows it cannot verify what it " //$NON-NLS-1$
            + "would be launching from", Collections.emptyList(), backend.calls); //$NON-NLS-1$
    }

    /**
     * And an archive that holds nothing for this comparison is refused with no temporary made
     * for it at all - the read settles it, so there is nothing to copy.
     *
     * @throws IOException when the archive cannot be written
     */
    @Test
    public void anArchiveThatAddressesAnotherComparisonIsRefusedWithoutMakingATemporary()
        throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("foreign.zip", "Someone_Else_Entirely.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend, NO_TEMP_AREA).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a zip that addresses another comparison must be refused"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal is about the ADDRESSING, which the read settled: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("holds nothing for THIS comparison")); //$NON-NLS-1$
        }
    }

    // ==== after the probe has accepted the archive, a failed copy REFUSES ====
    //
    // The hole the split left. With the probe moved ahead of the temporary, the copy became the
    // SECOND statement to touch the source - and its catch still answered "nothing established"
    // and let the caller's own path go to the platform. By then the probe had already opened the
    // archive and found this comparison's entry in it, so a read that fails now says the source
    // changed underneath us between two opens: the very race the snapshot exists to remove,
    // answered by handing the platform the mutable path.

    /**
     * @param failure what the copy raises instead of copying
     * @return a real temp file, and a copy into it that fails the way a vanished source does
     */
    private static ComparisonEngine.SnapshotIo copyThatFails(IOException failure)
    {
        return new ComparisonEngine.SnapshotIo()
        {
            @Override
            public Path createTemporary() throws IOException
            {
                return ComparisonEngine.SnapshotIo.REAL.createTemporary();
            }

            @Override
            public MergeRulesCodec.AddressedEntryCopy copyInto(Path source, String entryId,
                Path target) throws IOException
            {
                throw failure;
            }
        };
    }

    /**
     * An archive the probe ACCEPTED and whose copy then failed is refused, and the refusal names
     * what happened.
     * <p>
     * The distinction this pins is the one the method exists to make, and it is drawn by EVIDENCE
     * rather than by which statement failed: before the probe succeeds an unreadable source has
     * established nothing and is the platform's to fail on
     * ({@link #aZipThatCannotBeOpenedIsLeftToThePlatform}); after it succeeds the archive has been
     * established as readable AND as addressing this comparison, so a later failure to snapshot it
     * is this server unable to keep the promise the copy makes.
     *
     * @throws IOException when the archive cannot be written
     */
    @Test
    public void aCopyThatFailsAfterTheProbeAcceptedTheArchiveIsRefusedAndNotFallenBackFrom()
        throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend, copyThatFails(new IOException("the archive was replaced"))) //$NON-NLS-1$
                .restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    zip.toString());
            fail("a source established by the probe and then unreadable must refuse"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal must name the caller's file: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(zip.toString()));
            assertTrue("and must say WHAT happened, not merely that something did: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("could not be read again")); //$NON-NLS-1$
        }
    }

    /**
     * The same failure, pinned as the ABSENCE that is the whole point: the platform is not asked,
     * and the caller's own mutable path is not what it would have been asked about.
     * <p>
     * Its own {@code @Test} because JUnit stops a method at its first failed assertion, so an
     * absence sharing a method with the wording above would only ever be reached while the
     * wording still held - and the fall-back this pins against is exactly the state in which it
     * does not.
     *
     * @throws IOException when the archive cannot be written
     */
    @Test
    public void aCopyThatFailsAfterTheProbeAcceptedTheArchiveReachesThePlatformNotAtAll()
        throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            engineOver(backend, copyThatFails(new IOException("the archive was replaced"))) //$NON-NLS-1$
                .restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    zip.toString());
        }
        catch (IllegalStateException e)
        {
            // The refusal is this test's precondition; its wording is pinned above.
        }

        assertEquals("a refusal must not reach EDT at all", Collections.emptyList(), //$NON-NLS-1$
            backend.calls);
        assertNull("and the caller's own path is the one thing that must NOT be handed over - " //$NON-NLS-1$
            + "the copy failed because the source changed, so that path is the least trustworthy " //$NON-NLS-1$
            + "thing this call has", backend.restoredFrom); //$NON-NLS-1$
    }

    /**
     * And the refusal leaves no temporary behind. The refusing return runs through the same
     * {@code finally} the successful one skips, and a snapshot that has already been created when
     * the copy fails would otherwise stay in the system temp area for good.
     *
     * @throws IOException when the archive cannot be written or the temp area cannot be listed
     */
    @Test
    public void aCopyThatFailsAfterTheProbeLeavesNoTemporaryBehind() throws IOException
    {
        RecordingBackend backend = new RecordingBackend();
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> before = snapshotsInTheTempArea();

        try
        {
            engineOver(backend, copyThatFails(new IOException("the archive was replaced"))) //$NON-NLS-1$
                .restoreMergeSettings(threeWayHandle("Main", "Other", "Ancestor"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    zip.toString());
        }
        catch (IllegalStateException e)
        {
            // Expected; this test is about what is left on disk afterwards.
        }

        List<String> left = snapshotsInTheTempArea();
        left.removeAll(before);
        assertEquals("a refused copy must not leave its snapshot behind", List.of(), left); //$NON-NLS-1$
    }

    // ==== an Error is not a return, and the temporary is removed on every way out ====

    /** Stands in for the {@code OutOfMemoryError} that buffering the entry can raise. */
    private static final class SimulatedVmError
        extends Error
    {
        private static final long serialVersionUID = 1L;

        SimulatedVmError()
        {
            super("out of memory while the entry was buffered"); //$NON-NLS-1$
        }
    }

    /**
     * An {@code Error} out of the copy leaves no temporary in the system temp area, and reaches
     * the caller as it was thrown.
     * <p>
     * The window: {@code snapshotOfZip} runs BEFORE the caller's own {@code try}/{@code finally},
     * and its own {@code catch} is {@code IOException | RuntimeException} - so an {@code Error}
     * passed through with the file already created and nothing to remove it. The realistic one is
     * an {@code OutOfMemoryError} while up to 16 MB of entry is buffered. The removal is a
     * {@code finally} rather than a call on each exit precisely because the exits are not all
     * returns; catching the {@code Error} would be the other, wrong, way to close it.
     *
     * @throws IOException when the archive cannot be written or the temp area cannot be listed
     */
    @Test
    public void anErrorOutOfTheCopyLeavesNoTemporaryBehindAndStillReachesTheCaller()
        throws IOException
    {
        Path zip = zipHolding("own.zip", "Main_Other_Ancestor.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        SimulatedVmError thrown = new SimulatedVmError();
        List<String> before = snapshotsInTheTempArea();
        Error caught = null;

        try
        {
            engineOver(new RecordingBackend(), copyThatThrows(thrown)).restoreMergeSettings(
                threeWayHandle("Main", "Other", "Ancestor"), zip.toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (SimulatedVmError e)
        {
            caught = e;
        }

        assertSame("an Error is the VM's to report and must propagate, not be turned into an " //$NON-NLS-1$
            + "answer", thrown, caught); //$NON-NLS-1$
        List<String> left = snapshotsInTheTempArea();
        left.removeAll(before);
        assertEquals("an Error may not leave the temporary in the temp area either", //$NON-NLS-1$
            List.of(), left);
    }

    /**
     * @param error what the copy raises
     * @return a real temp file, and a copy into it that fails the way the VM does
     */
    private static ComparisonEngine.SnapshotIo copyThatThrows(Error error)
    {
        return new ComparisonEngine.SnapshotIo()
        {
            @Override
            public Path createTemporary() throws IOException
            {
                // The REAL one: the file this test is about has to actually exist, in the place
                // the production code puts it, or there is nothing to be left behind.
                return ComparisonEngine.SnapshotIo.REAL.createTemporary();
            }

            @Override
            public MergeRulesCodec.AddressedEntryCopy copyInto(Path source, String entryId,
                Path target)
            {
                throw error;
            }
        };
    }

    /**
     * @return the names of the snapshots this class's own prefix owns in the system temp area
     * @throws IOException when the temp area cannot be listed
     */
    private static List<String> snapshotsInTheTempArea() throws IOException
    {
        Path temp = Paths.get(System.getProperty("java.io.tmpdir")); //$NON-NLS-1$
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> list = Files.list(temp))
        {
            for (Path each : list.toList())
            {
                String name = each.getFileName().toString();
                if (name.startsWith("edt-mcp-merge-rules")) //$NON-NLS-1$
                {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Stands in for the other process: writes a different one-entry archive over an existing path.
     * <p>
     * A seam rather than a second thread: the window between the check and the platform's own open
     * is microseconds wide and nothing blocks in it, so a racing thread would occupy it by luck or
     * not at all - and a test that reproduces a defect by luck proves nothing on the run where it
     * loses.
     *
     * @param target the path to overwrite
     * @param entryName the entry the replacement holds
     * @return the interference
     */
    private static Runnable replaceWith(Path target, String entryName)
    {
        return () -> {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target)))
            {
                out.putNextEntry(new ZipEntry(entryName));
                out.write("<Settings/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                out.closeEntry();
            }
            catch (IOException e)
            {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    /**
     * @param fileName the path to read
     * @return the entry names the archive on it holds, or an empty list when there is no readable
     *         archive there - which is what an {@code .xml} path and a missing file both are
     */
    private static List<String> entryNamesOf(String fileName)
    {
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(fileName))
        {
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements())
            {
                names.add(entries.nextElement().getName());
            }
        }
        catch (IOException | RuntimeException e)
        {
            return List.of();
        }
        return names;
    }

    /**
     * @param fileName the path to measure
     * @return its size in bytes, or {@code -1} when there is nothing there to measure
     */
    private static long sizeOf(String fileName)
    {
        try
        {
            return Files.size(Paths.get(fileName));
        }
        catch (IOException | RuntimeException e)
        {
            return -1L;
        }
    }

    /**
     * @param fileName the archive to read
     * @return the text of its first entry, or an empty string when there is no readable archive
     *         there or it holds nothing
     */
    private static String firstEntryTextOf(String fileName)
    {
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(fileName))
        {
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            if (!entries.hasMoreElements())
            {
                return ""; //$NON-NLS-1$
            }
            try (java.io.InputStream in = archive.getInputStream(entries.nextElement()))
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        catch (IOException | RuntimeException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Writes a VALID archive holding this comparison's small settings entry beside one large
     * unrelated entry - the shape a whole-archive copy duplicates in full.
     * <p>
     * The neighbour is filled with pseudo-random bytes so DEFLATE cannot shrink it: the point of
     * the fixture is that the archive on disk really is large, and an incompressible neighbour is
     * the only way to be sure of that whatever the compression method.
     *
     * @param fileName the archive's name
     * @param entryName the entry this comparison restores from
     * @param neighbourName the unrelated entry's name
     * @param neighbourBytes how many bytes to give the unrelated entry
     * @return the archive
     * @throws IOException when it cannot be written
     */
    private static Path zipWithABulkyNeighbour(String fileName, String entryName,
        String neighbourName, int neighbourBytes) throws IOException
    {
        Path dir = Files.createTempDirectory("comparison-engine-test"); //$NON-NLS-1$
        dir.toFile().deleteOnExit();
        Path zip = dir.resolve(fileName);
        zip.toFile().deleteOnExit();
        byte[] bulk = new byte[neighbourBytes];
        new java.util.Random(42).nextBytes(bulk);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            out.putNextEntry(new ZipEntry(entryName));
            out.write("<Settings/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            out.closeEntry();
            out.putNextEntry(new ZipEntry(neighbourName));
            out.write(bulk);
            out.closeEntry();
        }
        return zip;
    }

    /**
     * Writes an archive whose addressed entry EXPANDS past a given size while the archive itself
     * stays small - zeroes, so the file on disk is a few kilobytes.
     *
     * @param fileName the archive's name
     * @param entryName the entry this comparison restores from
     * @param expandedBytes how many bytes the entry expands to
     * @return the archive
     * @throws IOException when it cannot be written
     */
    private static Path zipWhoseEntryExpandsTo(String fileName, String entryName, int expandedBytes)
        throws IOException
    {
        Path dir = Files.createTempDirectory("comparison-engine-test"); //$NON-NLS-1$
        dir.toFile().deleteOnExit();
        Path zip = dir.resolve(fileName);
        zip.toFile().deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(new byte[expandedBytes]);
            out.closeEntry();
        }
        return zip;
    }

    private static ComparisonProcessHandle threeWayHandle(String main, String other, String ancestor)
    {
        return new ComparisonProcessHandle(new FakeDescriptor(main), new FakeDescriptor(other),
            new FakeDescriptor(ancestor), ComparisonScope.EMPTY_SCOPE);
    }

    /**
     * Writes a one-entry zip into a temporary directory that is deleted when the JVM exits.
     *
     * @param fileName the archive's name
     * @param entryName the single entry to put in it
     * @return the archive
     * @throws IOException when it cannot be written
     */
    private static Path zipHolding(String fileName, String entryName) throws IOException
    {
        Path dir = Files.createTempDirectory("comparison-engine-test"); //$NON-NLS-1$
        dir.toFile().deleteOnExit();
        Path zip = dir.resolve(fileName);
        zip.toFile().deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            out.putNextEntry(new ZipEntry(entryName));
            out.write("<Settings/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            out.closeEntry();
        }
        return zip;
    }
}
