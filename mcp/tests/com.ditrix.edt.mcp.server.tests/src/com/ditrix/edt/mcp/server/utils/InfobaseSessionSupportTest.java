/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.TerminationResult;
import com.e1c.g5.dt.applications.IApplication;

/** Tests parsing and the structural readable-empty versus unreachable invariant. */
public class InfobaseSessionSupportTest
{
    @Test
    public void parsesIbcmdBlocksAndMarksDesignerApplicationKind()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n" //$NON-NLS-1$
            + "session-id: 7\n" //$NON-NLS-1$
            + "app-id: Designer\n" //$NON-NLS-1$
            + "user-name: Agent\n" //$NON-NLS-1$
            + "host: edt-host\n" //$NON-NLS-1$
            + "started-at: 2026-01-01T10:00:00\n" //$NON-NLS-1$
            + "last-active-at: 2026-01-01T10:01:00\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n" //$NON-NLS-1$
            + "session-id: 42\n" //$NON-NLS-1$
            + "app-id: 1CV8C\n" //$NON-NLS-1$
            + "user-name: User\n" //$NON-NLS-1$
            + "host: desk\n"; //$NON-NLS-1$

        List<SessionInfo> sessions = InfobaseSessionSupport.parseSessions(output).sessions();

        assertEquals(2, sessions.size());
        assertEquals(Long.valueOf(7), sessions.get(0).sessionNumber());
        assertEquals("Designer", sessions.get(0).applicationKind()); //$NON-NLS-1$
        assertTrue(sessions.get(0).applicationKindIsDesigner());
        assertEquals(Long.valueOf(42), sessions.get(1).sessionNumber());
        assertFalse(sessions.get(1).applicationKindIsDesigner());
        assertEquals("", sessions.get(1).lastActiveAt()); //$NON-NLS-1$
    }

    @Test
    public void parsesTheColumnPaddedOutputARealServerEmits()
    {
        String output = "session                          : fe63b824-630f-4c3e-b5e9-08a9e5edae74\n" //$NON-NLS-1$
            + "session-id                       : 5\n" //$NON-NLS-1$
            + "infobase                         : 8ef869af-9785-4d3c-9094-e1c434068afe\n" //$NON-NLS-1$
            + "connection                       : \n" //$NON-NLS-1$
            + "process                          : \n" //$NON-NLS-1$
            + "user-name                        : DefUser\n" //$NON-NLS-1$
            + "host                             : \n" //$NON-NLS-1$
            + "app-id                           : Designer\n" //$NON-NLS-1$
            + "locale                           : ru\n" //$NON-NLS-1$
            + "started-at                       : 2026-09-11T19:30:35\n" //$NON-NLS-1$
            + "last-active-at                   : 2026-09-11T21:40:35\n" //$NON-NLS-1$
            + "hibernate                        : no\n" //$NON-NLS-1$
            + "db-proc-took-at                  : 0001-01-01T00:00:00\n"; //$NON-NLS-1$

        List<SessionInfo> sessions = InfobaseSessionSupport.parseSessions(output).sessions();

        assertEquals("padded keys must still yield one session", 1, sessions.size()); //$NON-NLS-1$
        assertEquals("fe63b824-630f-4c3e-b5e9-08a9e5edae74", sessions.get(0).sessionId()); //$NON-NLS-1$
        assertEquals(Long.valueOf(5), sessions.get(0).sessionNumber());
        assertEquals("Designer", sessions.get(0).applicationKind()); //$NON-NLS-1$
        assertTrue("the raw Designer application kind must be recognised", //$NON-NLS-1$
            sessions.get(0).applicationKindIsDesigner());
        assertEquals("a timestamp value must keep the time after its first colon", //$NON-NLS-1$
            "2026-09-11T19:30:35", sessions.get(0).startedAt()); //$NON-NLS-1$
    }

    @Test
    public void preservesTheOriginalSessionNumberTokenForErrorValidation()
    {
        String output = "session: 22222222-2222-2222-2222-222222222222\n" //$NON-NLS-1$
            + "session-id: +42\n"; //$NON-NLS-1$

        SessionInfo session = InfobaseSessionSupport.parseSessions(output).sessions().get(0);

        assertEquals(Long.valueOf(42), session.sessionNumber());
        assertEquals("+42", session.sessionNumberText()); //$NON-NLS-1$
    }

    @Test
    public void readableEmptyIsNotUnreachable()
    {
        ReadResult readable = ReadResult.readable(List.of());
        ReadResult unreachable = ReadResult.unreachable("The standalone server is not running."); //$NON-NLS-1$

        assertTrue(readable.isReadable());
        assertTrue(readable.sessions().isEmpty());
        assertNull(readable.unreachableReason());
        assertFalse(unreachable.isReadable());
        assertNull(unreachable.sessions());
        assertEquals("The standalone server is not running.", unreachable.unreachableReason()); //$NON-NLS-1$
    }

    @Test
    public void preparationRunsInsideTheBoundedWorkerForBothEntryPoints()
    {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> listPreparationThread = new AtomicReference<>();
        IApplication listApplication = mock(IApplication.class);
        when(listApplication.getType()).thenAnswer(ignored ->
        {
            listPreparationThread.set(Thread.currentThread());
            return null;
        });

        assertFalse(InfobaseSessionSupport.listSessions(listApplication).isReadable());
        assertTrue("list preparation must run on the bounded worker", //$NON-NLS-1$
            listPreparationThread.get() != null && listPreparationThread.get() != caller);

        AtomicReference<Thread> terminatePreparationThread = new AtomicReference<>();
        IApplication terminateApplication = mock(IApplication.class);
        when(terminateApplication.getType()).thenAnswer(ignored ->
        {
            terminatePreparationThread.set(Thread.currentThread());
            return null;
        });

        assertFalse(InfobaseSessionSupport.terminateSession(terminateApplication, null, null)
            .terminated());
        assertTrue("terminate preparation must run on the bounded worker", //$NON-NLS-1$
            terminatePreparationThread.get() != null && terminatePreparationThread.get() != caller);
    }

    @Test
    public void boundedPreparationFailureDoesNotReportUnknownMutationOutcome()
    {
        IApplication application = mock(IApplication.class);
        when(application.getType()).thenThrow(new IllegalStateException("preparation failed")); //$NON-NLS-1$

        TerminationResult result = InfobaseSessionSupport.terminateSession(application, null, null);

        assertFalse(result.terminated());
        assertFalse(result.mutationOutcomeUnknown());
        assertTrue(result.unreachableReason().contains("preparation failed")); //$NON-NLS-1$
    }

    @Test
    public void missingStandaloneFeatureLinkageFailureBecomesPreparationError()
    {
        IApplication application = mock(IApplication.class);
        when(application.getType()).thenThrow(
            new NoClassDefFoundError("missing optional package")); //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.listSessions(application);

        assertFalse(result.isReadable());
        assertEquals("Infobase session commands are not available because the EDT " //$NON-NLS-1$
            + "standalone-server feature is not installed.", result.unreachableReason()); //$NON-NLS-1$
    }

    @Test
    public void partialParseWithholdsRawOutputAndNamesTheUnreadableBlockCount()
    {
        String output = "user-name: Ivanov\n" //$NON-NLS-1$
            + "host: WKS-01\n" //$NON-NLS-1$
            + "client-ip: 10.0.0.5\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);
        String reason = result.unreachableReason();

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertEquals("ibcmd session list returned 1 unreadable session block. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", reason); //$NON-NLS-1$
        assertFalse(reason.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(reason.contains("WKS-01")); //$NON-NLS-1$
        assertFalse(reason.contains("10.0.0.5")); //$NON-NLS-1$
        assertFalse(reason.contains("user-name")); //$NON-NLS-1$
        assertFalse(reason.contains("host")); //$NON-NLS-1$
        assertFalse(reason.contains("client-ip")); //$NON-NLS-1$
    }

    @Test
    public void nonzeroExitWithholdsRawCommandOutput() throws Exception
    {
        Class<?> commandType = Class.forName(
            InfobaseSessionSupport.class.getName() + "$CommandExecution"); //$NON-NLS-1$
        Constructor<?> constructor = commandType.getDeclaredConstructor(int.class, String.class,
            String.class, boolean.class);
        constructor.setAccessible(true); // NOSONAR production command state remains private
        Object command = constructor.newInstance(23, "stdout secret", //$NON-NLS-1$
            "user-name: Ivanov\nhost: WKS-01\nclient-ip: 10.0.0.5", false); //$NON-NLS-1$
        Method commandFailure = InfobaseSessionSupport.class.getDeclaredMethod("commandFailure", //$NON-NLS-1$
            String.class, commandType);
        commandFailure.setAccessible(true); // NOSONAR production failure handling remains private

        String reason = (String)commandFailure.invoke(null, "list", command); //$NON-NLS-1$

        assertEquals("ibcmd session list failed with exit code 23. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", reason); //$NON-NLS-1$
        assertFalse(reason.contains("stdout secret")); //$NON-NLS-1$
        assertFalse(reason.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(reason.contains("WKS-01")); //$NON-NLS-1$
        assertFalse(reason.contains("10.0.0.5")); //$NON-NLS-1$
        assertFalse(reason.contains("user-name")); //$NON-NLS-1$
        assertFalse(reason.contains("host")); //$NON-NLS-1$
        assertFalse(reason.contains("client-ip")); //$NON-NLS-1$
    }

    @Test
    public void twoUsableBlocksAreReadableWithBothSessions()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue(result.isReadable());
        assertEquals(2, result.sessions().size());
    }

    @Test
    public void validSessionPlusColonlessRecordIsUnreachable()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n" //$NON-NLS-1$
            + "app-id: Designer\n\n" //$NON-NLS-1$
            + "foreign client record\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertEquals("ibcmd session list returned 1 unreadable session block. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", result.unreachableReason()); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("foreign client record")); //$NON-NLS-1$
    }

    /** Output that parses to nothing at all must still be UNREACHABLE and leak none of itself. */
    @Test
    public void whollyUnrecognizedOutputIsUnreachableAndWithholdsItself()
    {
        String output = "ibcmd: could not reach the server for user Ivanov at WKS-01\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertFalse(result.unreachableReason().contains("Ivanov")); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("WKS-01")); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("could not reach the server")); //$NON-NLS-1$
    }

    @Test
    public void wellFormedOutputWithTrailingBlankLinesRemainsReadable()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n\n\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue(result.isReadable());
        assertEquals(2, result.sessions().size());
        assertNull(result.unreachableReason());
    }

    @Test
    public void cleanupCallbackDeliveredAfterCancellationRunsNoCommandAndPublishesNothing()
    {
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        AtomicBoolean commandRan = new AtomicBoolean();
        AtomicReference<InfobaseSessionSupport.CommandExecution> execution = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        InfobaseSessionSupport.executeAtPidIfActive(42L, monitor, new AtomicBoolean(), execution,
            failure, pid ->
            {
                commandRan.set(true);
                return new InfobaseSessionSupport.CommandExecution(0, "done", "", false); //$NON-NLS-1$ //$NON-NLS-2$
            });

        assertFalse("a proceedSessionCleanup callback delivered after the deadline must not issue " //$NON-NLS-1$
            + "ibcmd", commandRan.get()); //$NON-NLS-1$
        assertNull("a cancelled callback must not publish a command result", execution.get()); //$NON-NLS-1$
        assertNull("skipping cancelled work is not a callback failure", failure.get()); //$NON-NLS-1$
    }

    @Test
    public void resultFinishingAfterTheCallerAnsweredIsNotPublished()
    {
        NullProgressMonitor monitor = new NullProgressMonitor();
        AtomicBoolean callerAnswered = new AtomicBoolean();
        AtomicBoolean commandRan = new AtomicBoolean();
        AtomicReference<InfobaseSessionSupport.CommandExecution> execution = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        InfobaseSessionSupport.executeAtPidIfActive(42L, monitor, callerAnswered, execution,
            failure, pid ->
            {
                commandRan.set(true);
                callerAnswered.set(true);
                return new InfobaseSessionSupport.CommandExecution(0, "late", "", false); //$NON-NLS-1$ //$NON-NLS-2$
            });

        assertTrue("positive control: the command began while the caller was still waiting", //$NON-NLS-1$
            commandRan.get());
        assertNull("a result completed after the caller gave up must not be published", //$NON-NLS-1$
            execution.get());
        assertNull(failure.get());
    }

    @Test
    public void activeCleanupCallbackRunsTheCommandAndPublishesItsResult()
    {
        NullProgressMonitor monitor = new NullProgressMonitor();
        AtomicReference<InfobaseSessionSupport.CommandExecution> execution = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InfobaseSessionSupport.CommandExecution expected =
            new InfobaseSessionSupport.CommandExecution(0, "done", "", false); //$NON-NLS-1$ //$NON-NLS-2$

        InfobaseSessionSupport.executeAtPidIfActive(42L, monitor, new AtomicBoolean(), execution,
            failure, pid -> expected);

        assertSame("an active callback must still publish the command result", expected, //$NON-NLS-1$
            execution.get());
        assertNull(failure.get());
    }

    @Test
    public void markCallerAnsweredIsNotBlockedByAStalledProcessStart() throws Exception
    {
        AtomicBoolean callerAnswered = new AtomicBoolean();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch callerMarked = new CountDownLatch(1);
        AtomicBoolean workerFinished = new AtomicBoolean();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Process process = mock(Process.class);
        Thread worker = new Thread(() ->
        {
            try
            {
                InfobaseSessionSupport.runCommand(null, new NullProgressMonitor(), callerAnswered,
                    () ->
                    {
                        startEntered.countDown();
                        try
                        {
                            releaseStart.await();
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            throw new IOException("test process start interrupted", e); //$NON-NLS-1$
                        }
                        return process;
                    });
            }
            catch (Throwable t) // NOSONAR transferred to the test thread for assertion
            {
                workerFailure.set(t);
            }
            finally
            {
                workerFinished.set(true);
            }
        }, "stalled ibcmd starter"); //$NON-NLS-1$
        Thread marker = new Thread(() ->
        {
            InfobaseSessionSupport.markCallerAnswered(callerAnswered);
            callerMarked.countDown();
        }, "caller answer marker"); //$NON-NLS-1$

        worker.start();
        try
        {
            assertTrue("positive control: process start must reach its stall", //$NON-NLS-1$
                startEntered.await(1, TimeUnit.SECONDS));
            marker.start();
            assertTrue("markCallerAnswered must finish while process start is still stalled", //$NON-NLS-1$
                callerMarked.await(1, TimeUnit.SECONDS));
            assertFalse("the process starter must remain stalled until the test releases it", //$NON-NLS-1$
                workerFinished.get());
        }
        finally
        {
            releaseStart.countDown();
            worker.join(1_000L);
            marker.join(1_000L);
        }

        assertFalse("the worker must finish after the stalled starter is released", //$NON-NLS-1$
            worker.isAlive());
        assertNull(workerFailure.get());
    }

    @Test
    public void processReturnedAfterCallerAnsweredIsKilledBeforeStreamsAreRead() throws Exception
    {
        AtomicBoolean callerAnswered = new AtomicBoolean();
        Process process = mock(Process.class);

        InfobaseSessionSupport.CommandExecution result = InfobaseSessionSupport.runCommand(null,
            new NullProgressMonitor(), callerAnswered, () ->
            {
                InfobaseSessionSupport.markCallerAnswered(callerAnswered);
                return process;
            });

        assertNull(result);
        verify(process).destroyForcibly();
        verify(process, never()).getInputStream();
        verify(process, never()).getErrorStream();
        verify(process, never()).getOutputStream();
    }

    @Test(expected = IllegalArgumentException.class)
    public void unreadableResultCannotCarryAnEmptyList()
    {
        new ReadResult(InfobaseSessionSupport.Reachability.UNREACHABLE, List.of(), "offline"); //$NON-NLS-1$
    }
    /**
     * The fence, not the Job state, is the correctness boundary: BoundedJob says whether the
     * JOB concluded, not whether the COMMAND did. A deadline landing inside the Job's terminal
     * transition must not discard an outcome the worker already published.
     */
    @Test
    public void publishedOutcomeSurvivesADeadlineThatBeatTheJobTransition()
    {
        InfobaseSessionSupport.CommandExecution published =
            new InfobaseSessionSupport.CommandExecution(0, "done", "", false); //$NON-NLS-1$
        BoundedJob.Result timedOut = new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT, 15_000L,
            null);

        assertFalse("a published outcome must answer the caller, not the bounded timeout", //$NON-NLS-1$
            InfobaseSessionSupport.boundedFailureOverridesPublished(timedOut, published));
    }

    @Test
    public void boundedTimeoutStillWinsWhenNothingWasPublished()
    {
        BoundedJob.Result timedOut = new BoundedJob.Result(BoundedJob.Outcome.TIMED_OUT, 15_000L,
            null);

        assertTrue("with nothing published the bounded failure is all there is", //$NON-NLS-1$
            InfobaseSessionSupport.boundedFailureOverridesPublished(timedOut, null));
    }

    @Test
    public void aThrownFailureIsNeverMaskedByAPartialPublication()
    {
        InfobaseSessionSupport.CommandExecution published =
            new InfobaseSessionSupport.CommandExecution(0, "done", "", false); //$NON-NLS-1$
        BoundedJob.Result threw = new BoundedJob.Result(BoundedJob.Outcome.COMPLETED, 12L,
            new IllegalStateException("ibcmd blew up")); //$NON-NLS-1$

        // Reporting the publication here would both mis-answer the caller and lose the log entry
        // boundedFailure writes for the throwable.
        assertTrue("a thrown failure proves nothing about the command and must stay reported", //$NON-NLS-1$
            InfobaseSessionSupport.boundedFailureOverridesPublished(threw, published));
    }
    /**
     * A terminate whose process was never created changed nothing. Reporting an unknown
     * mutation there tells the caller a session may be gone when the evidence says it is not.
     */
    @Test
    public void aStartThatThrowsNeverCrossesTheMutationBoundary() throws Exception
    {
        AtomicBoolean mutated = new AtomicBoolean();
        InfobaseSessionSupport.ProcessStarter starter = InfobaseSessionSupport.mutatingStarter(
            () -> {
                throw new IOException("ibcmd is not executable"); //$NON-NLS-1$
            }, mutated);

        try
        {
            starter.start();
            fail("the start failure must propagate"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The propagation is the point; the flag is what this test pins.
        }
        assertFalse("a process that was never created cannot have mutated anything", //$NON-NLS-1$
            mutated.get());
    }

    @Test
    public void aCancelledCallNeverCrossesTheMutationBoundaryEither() throws Exception
    {
        AtomicBoolean mutated = new AtomicBoolean();
        AtomicBoolean callerAnswered = new AtomicBoolean(true);

        InfobaseSessionSupport.CommandExecution result = InfobaseSessionSupport.runCommand(null,
            new NullProgressMonitor(), callerAnswered,
            InfobaseSessionSupport.mutatingStarter(() -> {
                throw new IllegalStateException("the pre-start check must return first"); //$NON-NLS-1$
            }, mutated));

        assertNull("the pre-start check must refuse before starting anything", result); //$NON-NLS-1$
        assertFalse("a call answered before the start cannot have mutated anything", //$NON-NLS-1$
            mutated.get());
    }
}
