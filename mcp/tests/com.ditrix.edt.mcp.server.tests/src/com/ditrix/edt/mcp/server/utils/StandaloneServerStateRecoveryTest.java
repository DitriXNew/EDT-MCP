/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.tools.impl.LaunchTool;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;

/**
 * Tests for {@link StandaloneServerStateRecovery}: recognising EDT's stale standalone-server
 * refusal, and saying something actionable about it.
 *
 * <p>The failure shape here is the real one — EDT reports the refusal as an
 * {@code ApplicationException} whose status says only "An internal error occurred during:
 * "Starting Standalone server for X"", with the sentence that names the reason three hops down in
 * an {@link IllegalStateException}. A recogniser that looks at the headline sees nothing.
 */
public class StandaloneServerStateRecoveryTest
{
    private static final String PLUGIN = "com.ditrix.edt.mcp.server";

    /** The standalone-server application every recovery test addresses. */
    private static final String APPLICATION_ID = "ServerApplication.Test";

    private static final String HEADLINE =
        "An internal error occurred during: \"Starting Standalone server for TestBase\".";

    /** The refusal exactly as EDT's standalone-server behaviour delegate phrases it. */
    private static Throwable refusal(int state)
    {
        IllegalStateException reason = new IllegalStateException(
            "Can only start server that is stopped but current server state is " + state);
        CoreException inner = new CoreException(new Status(IStatus.ERROR, PLUGIN, HEADLINE, reason));
        return new ApplicationException(new Status(IStatus.ERROR, PLUGIN, HEADLINE, inner));
    }

    @Test
    public void testRefusalIsFoundThroughTheWholeWrapping()
    {
        String message = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        assertNotNull("the refusal is three hops below the headline and must still be found",
            message);
        assertTrue(message.contains("current server state is 2"));
    }

    @Test
    public void testTheHeadlineAloneIsNotARefusal()
    {
        // The guard that keeps the recovery from firing on every failed server start.
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(
            new ApplicationException(new Status(IStatus.ERROR, PLUGIN, HEADLINE))));
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(null));
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(
            new IllegalStateException("Server \"S\" start attempt failed.")));
    }

    @Test
    public void testRefusalIsFoundInsideAChildStatus()
    {
        // EDT aggregates publish results into a MultiStatus; the refusal can arrive as a child.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.INFO, PLUGIN, "Publishing configuration"));
        status.add(new Status(IStatus.ERROR, PLUGIN,
            "Can only start server that is stopped but current server state is 2"));
        assertTrue(StandaloneServerStateRecovery.isStaleServerState(
            new ApplicationException(status)));
    }

    @Test
    public void testStateNamesFollowWst()
    {
        assertEquals("STARTED", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(2))));
        assertEquals("STARTING", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(1))));
        assertEquals("STOPPING", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(3))));
        assertEquals("UNKNOWN", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(0))));
    }

    @Test
    public void testUnreadableStateIsNotInvented()
    {
        assertNull(StandaloneServerStateRecovery.refusedStateName(null));
        assertNull("a message without the marker names no state",
            StandaloneServerStateRecovery.refusedStateName("Server start attempt failed."));
        assertNull("a marker with no number following it names no state",
            StandaloneServerStateRecovery.refusedStateName(
                "Can only start server that is stopped but current server state is unclear"));
    }

    @Test
    public void testErrorForTheStuckServerNamesWhatWasDoneAndWhatIsLeft()
    {
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.stopped(), "ports 8429, 8420 are busy");
        assertTrue("names the application", message.contains("ServerApplication.Test"));
        assertTrue("names the state EDT refused on", message.contains("STARTED"));
        assertTrue("reports that the retry happened", message.contains("retried once"));
        assertTrue("carries the retry's own reason", message.contains("ports 8429, 8420 are busy"));
        assertFalse("never renders as the literal null", message.contains("null"));
    }

    @Test
    public void testErrorWhenTheServerCouldNotBeStoppedTellsTheUserWhatToDo()
    {
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.failed("the EDT application manager "
                + "is not available"), null);
        assertTrue(message.contains("the EDT application manager is not available"));
        assertTrue("points at the manual way out", message.contains("Servers view"));
        assertFalse("nothing was retried, so it must not claim it was",
            message.contains("retried once"));
    }

    @Test
    public void testATransientStateIsReportedAsSuchAndNotAsAStuckServer()
    {
        // STARTING/STOPPING belong to an operation still in flight. Advising a stop there would
        // tell the caller to break someone else's start.
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(1));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.failed("unused"), null);
        assertTrue(message.contains("STARTING"));
        assertTrue("says to wait it out", message.contains("retry"));
        assertFalse("must not claim EDT lost the launch", message.contains("outlives the launch"));
        assertFalse("must not leak the unused recovery detail", message.contains("unused"));
    }

    @Test
    public void testStopWithoutATargetFailsInsteadOfThrowing()
    {
        StandaloneServerStateRecovery.Recovery recovery =
            StandaloneServerStateRecovery.stopStaleServer(null, null);
        assertFalse(recovery.recovered());
        assertNotNull("a failed recovery always says why", recovery.detail());
    }

    @Test
    public void testStateNameFallsBackToTheNumberForAStateWstDoesNotDefine()
    {
        assertEquals("9", StandaloneServerStateRecovery.stateName(9));
    }

    // ==================== pre-flight (ensureStartable) ====================

    @Test
    public void testAStartedServerWithALiveLaunchIsLeftAlone()
    {
        // EDT's own shortcut: state STARTED + a live launch = already running, nothing to do.
        // Stopping it here would kill a server somebody is using.
        assertSame(StandaloneServerStateRecovery.Preflight.PROCEED,
            StandaloneServerStateRecovery.decide(Integer.valueOf(2), Boolean.TRUE));
    }

    @Test
    public void testAStartedServerWhoseLaunchIsGoneIsTheStuckOne()
    {
        assertSame(StandaloneServerStateRecovery.Preflight.STOP_STALE,
            StandaloneServerStateRecovery.decide(Integer.valueOf(2), Boolean.FALSE));
    }

    @Test
    public void testAnUnreadableLaunchIsNotTreatedAsADeadOne()
    {
        // The whole risk of a pre-flight is stopping a HEALTHY server. "Could not tell" must
        // therefore mean "leave it", never "stop it".
        assertSame(StandaloneServerStateRecovery.Preflight.PROCEED,
            StandaloneServerStateRecovery.decide(Integer.valueOf(2), null));
    }

    @Test
    public void testATransitionalStateIsWaitedForRatherThanStopped()
    {
        assertSame("a server somebody else is starting must not be stopped",
            StandaloneServerStateRecovery.Preflight.WAIT_SETTLE,
            StandaloneServerStateRecovery.decide(Integer.valueOf(1), Boolean.FALSE));
        assertSame(StandaloneServerStateRecovery.Preflight.WAIT_SETTLE,
            StandaloneServerStateRecovery.decide(Integer.valueOf(3), Boolean.FALSE));
    }

    @Test
    public void testAStoppedOrUnknownServerNeedsNothing()
    {
        assertSame(StandaloneServerStateRecovery.Preflight.PROCEED,
            StandaloneServerStateRecovery.decide(Integer.valueOf(4), Boolean.FALSE));
        assertSame(StandaloneServerStateRecovery.Preflight.PROCEED,
            StandaloneServerStateRecovery.decide(Integer.valueOf(0), Boolean.FALSE));
    }

    @Test
    public void testAnUnreadableStateNeverActs()
    {
        assertSame(StandaloneServerStateRecovery.Preflight.PROCEED,
            StandaloneServerStateRecovery.decide(null, Boolean.FALSE));
    }

    @Test
    public void testTheServerStateIsReadReflectively()
    {
        assertEquals(Integer.valueOf(2),
            StandaloneServerStateRecovery.serverState(new FakeServer(2, null)));
        assertNull("an object that is not a WST server reads as unknown, not as a state",
            StandaloneServerStateRecovery.serverState(new Object()));
    }

    @Test
    public void testAMissingLaunchIsReportedAsNoLaunchAndAnUnknownOneAsUnknown()
    {
        assertSame(Boolean.FALSE,
            StandaloneServerStateRecovery.hasLiveLaunch(new FakeServer(2, null)));
        assertNull("a launch of an unexpected type must not be read as terminated",
            StandaloneServerStateRecovery.hasLiveLaunch(new FakeServer(2, "not a launch")));
        assertNull(StandaloneServerStateRecovery.hasLiveLaunch(new Object()));
    }

    @Test
    public void testALiveLaunchIsDistinguishedFromATerminatedOne()
    {
        assertSame(Boolean.TRUE,
            StandaloneServerStateRecovery.hasLiveLaunch(new FakeServer(2, launch(false))));
        assertSame(Boolean.FALSE,
            StandaloneServerStateRecovery.hasLiveLaunch(new FakeServer(2, launch(true))));
    }

    @Test
    public void testThePreflightIsANoOpForAnythingButAStandaloneServer()
    {
        // Every launch goes through it, so a client launch must pay nothing and risk nothing.
        StandaloneServerStateRecovery.ensureStartable(null, null, "InfobaseApplication.Test");
        StandaloneServerStateRecovery.ensureStartable(null, null, "ServerApplication.Test");
        StandaloneServerStateRecovery.ensureStartable(null, null, null);
    }

    @Test
    public void testStalePreflightReusesResolvedApplicationForStateRecheckAndStop()
        throws Exception
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("TestProject"); //$NON-NLS-1$
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        IApplicationType type = Mockito.mock(IApplicationType.class);
        Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
        IApplication application = Mockito.mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        Mockito.when(application.getType()).thenReturn(type);
        Mockito.when(((ServerBackedApplication)application).getServer())
            .thenReturn(new FakeServer(2, null));

        StandaloneServerStateRecovery.ensureStartable(project, application,
            "ServerApplication.Test", manager); //$NON-NLS-1$

        Mockito.verify(manager, Mockito.never()).getApplication(project,
            "ServerApplication.Test"); //$NON-NLS-1$
        Mockito.verify(manager).cleanup(Mockito.same(application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
    }

    @Test
    public void testAStopThatMayStillBeRunningIsNotTheSameAsOneThatNeverRan()
    {
        // The distinction the pre-flight turns on: after a stop that was merely ASKED to stop,
        // starting the server can be undone by it, so the operation must not proceed. After one
        // that never ran, nothing is in flight and the operation still may.
        assertFalse(StandaloneServerStateRecovery.Recovery.stopped().stopStillInFlight());
        assertFalse(StandaloneServerStateRecovery.Recovery.failed("it was refused outright")
            .stopStillInFlight());
        assertTrue(StandaloneServerStateRecovery.Recovery.failedInFlight("did not finish within 60s")
            .stopStillInFlight());
        assertFalse("an in-flight stop is still a failed recovery",
            StandaloneServerStateRecovery.Recovery.failedInFlight("x").recovered());
    }

    @Test
    public void testNoRestorationMessageWhenThisOperationStoppedNothing()
    {
        int[] restores = new int[1];
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            String message = StandaloneServerStateRecovery.appendRestoration("operation failed", //$NON-NLS-1$
                "Standalone", applicationId -> { //$NON-NLS-1$
                    restores[0]++;
                    return null;
                });
            assertNull(message);
            assertEquals(0, restores[0]);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testSuccessfulRestoreIsAppendedExactly()
    {
        int[] restores = new int[1];
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            String message = StandaloneServerStateRecovery.appendRestoration("operation failed.", //$NON-NLS-1$
                "Standalone", applicationId -> { //$NON-NLS-1$
                    restores[0]++;
                    return null;
                });
            assertEquals("operation failed. The standalone server 'ServerApplication.Test' was " //$NON-NLS-1$
                + "stopped for this operation and has been started again.", message); //$NON-NLS-1$
            assertEquals(1, restores[0]);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationSkipsAServerNowOwnedByAnotherLaunch()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("TestProject"); //$NON-NLS-1$
        FakeServer server = new FakeServer(2, launch(false));
        IApplication application = Mockito.mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        IApplicationType type = Mockito.mock(IApplicationType.class);
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
        Mockito.when(application.getType()).thenReturn(type);
        Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
        AtomicInteger normalizingStops = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            normalizingStops.incrementAndGet();
            server.setState(4);
            return null;
        }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
            Mockito.any(IProgressMonitor.class));
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project, application, server,
                    "ServerApplication.Test", manager, 5_000L, () -> { //$NON-NLS-1$
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals("a live launch must prevent stale-state normalization", //$NON-NLS-1$
                0, normalizingStops.get());
            assertEquals("another launch's server must not be started again", 0, restores.get()); //$NON-NLS-1$
            assertTrue(message.contains("is already running under another launch")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationNormalizesAStaleStartedServerBeforeRestartingIt()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("StaleRestorationProject"); //$NON-NLS-1$
        FakeServer server = new FakeServer(2, null);
        IApplication application = Mockito.mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        IApplicationType type = Mockito.mock(IApplicationType.class);
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
        Mockito.when(application.getType()).thenReturn(type);
        Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
        AtomicInteger order = new AtomicInteger();
        AtomicInteger normalizingStops = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            assertTrue("normalization must be the first lifecycle action", //$NON-NLS-1$
                order.compareAndSet(0, 1));
            normalizingStops.incrementAndGet();
            server.setState(4);
            return null;
        }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
            Mockito.any(IProgressMonitor.class));

        StandaloneServerStateRecovery.restoreIfStillUnowned(project, application, server,
            "ServerApplication.Test", manager, 10_000L, () -> { //$NON-NLS-1$
                assertTrue("restoration must run only after stale-state normalization", //$NON-NLS-1$
                    order.compareAndSet(1, 2));
                restores.incrementAndGet();
                return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
            });

        assertEquals("the stale tuple must be normalized exactly once", //$NON-NLS-1$
            1, normalizingStops.get());
        assertEquals("the normalized server must be restored exactly once", 1, restores.get()); //$NON-NLS-1$
        assertEquals(2, order.get());
    }

    @Test
    public void testRestorationDispatchRequiresAnObservableRemainingWait()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("SharedDeadlineProject"); //$NON-NLS-1$
        FakeServer normalizedServer = new FakeServer(2, null);
        long[] normalizationAllowance = new long[1];
        AtomicInteger shortWindowDispatches = new AtomicInteger();

        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project, normalizedServer,
                    "ServerApplication.Test", 4_000L, normalizationTimeoutMs -> { //$NON-NLS-1$
                        normalizationAllowance[0] = normalizationTimeoutMs;
                        normalizedServer.setState(4);
                        return StandaloneServerStateRecovery.Recovery.stopped();
                    }, restorationTimeoutMs -> {
                        shortWindowDispatches.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue("normalization must still receive the full remaining window", //$NON-NLS-1$
                normalizationAllowance[0] >= 3_000L);
            assertEquals("a start with less than the observable minimum must not be dispatched", //$NON-NLS-1$
                0, shortWindowDispatches.get());
            assertTrue(message.contains("was stopped for this operation")); //$NON-NLS-1$
            assertTrue("the skip must name the actionable tool", //$NON-NLS-1$
                message.contains("launch")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }

        AtomicInteger observableWindowDispatches = new AtomicInteger();
        StandaloneServerStateRecovery.restoreIfStillUnowned(project,
            new FakeServer(4, null), "ServerApplication.Test", 10_000L, //$NON-NLS-1$
            normalizationTimeoutMs -> {
                throw new AssertionError("a confirmed STOPPED server must not be normalized"); //$NON-NLS-1$
            }, restorationTimeoutMs -> {
                observableWindowDispatches.incrementAndGet();
                return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
            });
        assertEquals("a start with an observable remaining wait must dispatch exactly once", //$NON-NLS-1$
            1, observableWindowDispatches.get());
    }

    @Test
    public void testRestorationSkipsWhenNormalizationDoesNotReachStopped()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("NormalizationTimeoutProject"); //$NON-NLS-1$
        FakeServer server = new FakeServer(2, null);
        long timeoutMs = 2_400L;
        AtomicInteger restores = new AtomicInteger();

        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project, server,
                    "ServerApplication.Test", timeoutMs, normalizationTimeoutMs -> { //$NON-NLS-1$
                        return StandaloneServerStateRecovery.Recovery.failedInFlight(
                            "stopping it did not finish within its allowance"); //$NON-NLS-1$
                    }, restorationTimeoutMs -> {
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals("normalization that does not confirm STOPPED must prevent restoration", //$NON-NLS-1$
                0, restores.get());
            assertEquals("failed normalization must leave the stale tuple", //$NON-NLS-1$
                2, server.getServerState());
            assertTrue(message.contains("could not be normalized to STOPPED")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationSkipsAnUnknownServerState()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("UnknownStateProject"); //$NON-NLS-1$
        AtomicInteger restores = new AtomicInteger();
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project,
                    new FakeServer(0, null), "ServerApplication.Test", () -> { //$NON-NLS-1$
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals("UNKNOWN is not a confirmed stopped state", 0, restores.get()); //$NON-NLS-1$
            assertTrue(message.contains("current state is UNKNOWN, not STOPPED")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationSkipsWhenTheRecoveryLockTimesOut() throws Exception
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("LockTimeoutProject"); //$NON-NLS-1$
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger restores = new AtomicInteger();
        Thread holder = new Thread(() -> StandaloneServerStateRecovery.holdRecoveryLockForTest(
            project, "ServerApplication.Test", () -> { //$NON-NLS-1$
                locked.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }), "test: held standalone recovery lock"); //$NON-NLS-1$
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project,
                    new FakeServer(4, null), "ServerApplication.Test", 25L, () -> { //$NON-NLS-1$
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals("an unavailable recovery lock must prevent restoration", //$NON-NLS-1$
                0, restores.get());
            assertTrue("the caller must be told why ownership was unconfirmed", //$NON-NLS-1$
                message.contains("recovery guard did not become available")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
            release.countDown();
            holder.join(5_000L);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    public void testRestorationSkipsAClaimedButNotYetStartedServer()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("ClaimedStartProject"); //$NON-NLS-1$
        AtomicInteger restores = new AtomicInteger();
        StandaloneServerStateRecovery.StartClaim claim =
            StandaloneServerStateRecovery.claimStartWithinBound(project,
                "ServerApplication.Test", 1_000L, null); //$NON-NLS-1$
        assertNotNull(claim);
        try
        {
            StandaloneServerStateRecovery.restoreIfStillUnowned(project,
                new FakeServer(4, null), "ServerApplication.Test", () -> { //$NON-NLS-1$
                    restores.incrementAndGet();
                    return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                });

            assertEquals("a claimed start must be visible before WST changes state", //$NON-NLS-1$
                0, restores.get());
        }
        finally
        {
            claim.close();
        }
    }

    @Test
    public void testRestorationStillStartsAnUnownedServer()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("TestProject"); //$NON-NLS-1$
        AtomicInteger restores = new AtomicInteger();
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(project,
                    new FakeServer(4, null), "ServerApplication.Test", () -> { //$NON-NLS-1$
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals("an unowned server must still be restored exactly once", 1, restores.get()); //$NON-NLS-1$
            assertEquals("operation failed. The standalone server 'ServerApplication.Test' was " //$NON-NLS-1$
                + "stopped for this operation and has been started again.", message); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testFailedRestoreIsAppendedExactly()
    {
        int[] restores = new int[1];
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            String message = StandaloneServerStateRecovery.appendRestoration("operation failed.", //$NON-NLS-1$
                "Standalone for Test", applicationId -> { //$NON-NLS-1$
                    restores[0]++;
                    return "ports are busy"; //$NON-NLS-1$
                });
            assertEquals("operation failed. The standalone server 'ServerApplication.Test' was " //$NON-NLS-1$
                + "stopped for this operation and could NOT be started again: ports are busy. " //$NON-NLS-1$
                + "Start it with launch(launchConfigurationName='Standalone for Test').", message); //$NON-NLS-1$
            assertEquals(1, restores[0]);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationSchedulingFailureKeepsTheOriginalOperationFailure()
    {
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            AtomicReference<String> launchName =
                new AtomicReference<>("Standalone for Test"); //$NON-NLS-1$
            StandaloneServerStateRecovery.RestorationAttempt attempt =
                StandaloneServerStateRecovery.runRestorationAttempt(launchName, () -> {
                    throw new IllegalStateException("Eclipse Job manager is shutting down"); //$NON-NLS-1$
                });

            String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                "database update failed", attempt.launchConfigurationName, //$NON-NLS-1$
                applicationId -> attempt.outcome);

            assertTrue("the operation failure must remain the response headline", //$NON-NLS-1$
                message.startsWith("database update failed. ")); //$NON-NLS-1$
            assertTrue("the scheduling failure must be subordinate restoration detail", //$NON-NLS-1$
                message.contains("could NOT be started again: " //$NON-NLS-1$
                    + "Eclipse Job manager is shutting down")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testInconclusiveRestoreChecksStateBeforeRecommendingAnyStart()
    {
        String[] reasons = {
            "starting it did not finish within 60s and may still be running", //$NON-NLS-1$
            "the wait for the standalone-server start was interrupted; " //$NON-NLS-1$
                + "the start may still be running" //$NON-NLS-1$
        };
        for (String reason : reasons)
        {
            StandaloneServerStateRecovery.beginOperation();
            try
            {
                StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
                String message = StandaloneServerStateRecovery.appendRestorationOutcome(
                    "operation failed.", "Standalone", //$NON-NLS-1$ //$NON-NLS-2$
                    applicationId -> new StandaloneServerStateRecovery.RestorationStartOutcome(
                        reason, false));

                assertTrue(message.contains("may still be running")); //$NON-NLS-1$
                assertTrue(message.contains("check debug_status")); //$NON-NLS-1$
                assertTrue(message.contains("before starting anything")); //$NON-NLS-1$
                assertFalse("an in-flight restoration must not be reported as a definite failure", //$NON-NLS-1$
                    message.contains("could NOT be started again")); //$NON-NLS-1$
                assertFalse("an in-flight restoration must not recommend an immediate restart", //$NON-NLS-1$
                    message.contains("Start it with launch")); //$NON-NLS-1$
            }
            finally
            {
                StandaloneServerStateRecovery.endOperation();
            }
        }
    }

    @Test
    public void testStoppedServerRecordDoesNotLeakIntoTheNextOperation()
    {
        StandaloneServerStateRecovery.beginOperation();
        StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.First"); //$NON-NLS-1$
        StandaloneServerStateRecovery.endOperation();

        StandaloneServerStateRecovery.beginOperation();
        try
        {
            String message = StandaloneServerStateRecovery.appendRestoration("second failed", //$NON-NLS-1$
                "Second", applicationId -> null); //$NON-NLS-1$
            assertNull(message);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    @Test
    public void testRestorationKeepsThePortConfirmerArmedAroundTheStart()
        throws Exception
    {
        int originalPortArms = LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest();
        int originalConflictWatches = conflictWatchCount();
        AtomicInteger inFlight = authInFlightCounter();
        int originalAuth = inFlight.get();
        LaunchUpdateDialogAutoConfirmer.armPortConflictForTest(
            StandaloneServerPortConflictPolicy.REASSIGN,
            "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            String result = StandaloneServerStateRecovery.startRestorationWithPortGuard(
                new FailingRestorationService(), new Object(), "ServerApplication.Test", //$NON-NLS-1$
                null, null);

            assertEquals("start failed", result); //$NON-NLS-1$
            assertEquals(
                "a conclusive restoration must close its conflict watch before returning", //$NON-NLS-1$
                originalConflictWatches, conflictWatchCount());
            assertEquals("a conclusive restoration must release its auth guard before returning", //$NON-NLS-1$
                originalAuth, inFlight.get());
            assertEquals("headless cleanup must not release another invocation's port guard", //$NON-NLS-1$
                originalPortArms + 1,
                LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest());
        }
        finally
        {
            while (LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest() > originalPortArms)
            {
                LaunchUpdateDialogAutoConfirmer.disarmPortConflictForTest(
                    StandaloneServerPortConflictPolicy.REASSIGN,
                    "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @Test
    public void testInconclusiveRestorationKeepsThePortConfirmerArmedUntilJobCompletion()
        throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicInteger inFlight = authInFlightCounter();
        int originalAuth = inFlight.get();
        int originalPortArms = LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest();
        int originalConflictWatches = conflictWatchCount();
        // Seed another invocation's port arm; the headless acquisition reports false, so neither
        // eager nor deferred cleanup may consume this marker.
        LaunchUpdateDialogAutoConfirmer.armPortConflictForTest(
            StandaloneServerPortConflictPolicy.REASSIGN,
            "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$

        Thread caller = new Thread(() -> {
            try
            {
                answer.set(StandaloneServerStateRecovery.startRestorationWithPortGuard(
                    new BlockingRestorationService(started, release), new Object(),
                    "ServerApplication.Test", null, null, 100L, 5_000L)); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                callerFailure.set(t);
            }
        }, "test: bounded restoration caller"); //$NON-NLS-1$

        caller.start();
        try
        {
            assertTrue("the restoration start must be running before its bounded wait returns", //$NON-NLS-1$
                started.await(5, TimeUnit.SECONDS));
            caller.join(5_000L);
            assertFalse("the bounded restoration caller must have returned", caller.isAlive()); //$NON-NLS-1$
            assertNull(callerFailure.get());
            assertNotNull(answer.get());
            assertTrue(answer.get(), answer.get().contains(
                "starting it did not finish within 100ms and may still be running")); //$NON-NLS-1$
            assertFalse(answer.get(), answer.get().contains("within 60s")); //$NON-NLS-1$
            assertEquals("the missing nested auth guard must be present while restoration is in flight", //$NON-NLS-1$
                originalAuth + 1, inFlight.get());
            assertEquals(
                "the old eager conflict-watch close must be absent while restoration is in flight", //$NON-NLS-1$
                originalConflictWatches + 1, conflictWatchCount());
            assertEquals("headless cleanup must not release another invocation's port guard", //$NON-NLS-1$
                originalPortArms + 1,
                LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest());

            release.countDown();
            long deadline = System.currentTimeMillis() + 5_000L;
            while ((inFlight.get() != originalAuth
                || conflictWatchCount() != originalConflictWatches)
                && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(10L);
            }
            assertEquals("Job completion must release the restoration auth guard", //$NON-NLS-1$
                originalAuth, inFlight.get());
            assertEquals("Job completion must close the restoration conflict watch", //$NON-NLS-1$
                originalConflictWatches, conflictWatchCount());
            assertEquals("Job completion must leave another invocation's port guard armed", //$NON-NLS-1$
                originalPortArms + 1,
                LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest());
        }
        finally
        {
            release.countDown();
            caller.join(5_000L);
            long cleanupDeadline = System.currentTimeMillis() + 5_000L;
            while ((inFlight.get() != originalAuth
                || conflictWatchCount() != originalConflictWatches)
                && System.currentTimeMillis() < cleanupDeadline)
            {
                Thread.sleep(10L);
            }
            while (LaunchUpdateDialogAutoConfirmer.portConflictArmsForTest() > originalPortArms)
            {
                LaunchUpdateDialogAutoConfirmer.disarmPortConflictForTest(
                    StandaloneServerPortConflictPolicy.REASSIGN,
                    "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @Test
    public void testRestorationApplicationLookupReturnsOnItsDeadline() throws Exception
    {
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        IProject project = Mockito.mock(IProject.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Mockito.when(manager.getApplication(project, "ServerApplication.Test")) //$NON-NLS-1$
            .thenAnswer(invocation -> {
                started.countDown();
                try
                {
                    release.await(30, TimeUnit.SECONDS);
                    return Optional.<IApplication>empty();
                }
                finally
                {
                    finished.countDown();
                }
            });

        try
        {
            StandaloneServerSupport.ApplicationLookup lookup =
                StandaloneServerSupport.lookupApplicationBounded(manager, project,
                    "ServerApplication.Test", 250L); //$NON-NLS-1$

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertNull(lookup.application());
            assertNotNull(lookup.failure());
            assertTrue(lookup.failure().contains("did not finish within 250ms")); //$NON-NLS-1$
            assertTrue(lookup.failure().contains("may still be running")); //$NON-NLS-1$
            assertFalse("the old unbounded restoration lookup must not report measured absence", //$NON-NLS-1$
                lookup.failure().contains("was not found")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRestorationReportsAPortConflictInsteadOfATimeout()
    {
        String result = StandaloneServerStateRecovery.startRestorationWithPortGuard(
            new PortConflictingRestorationService(), new Object(), "ServerApplication.Test", //$NON-NLS-1$
            null, null);

        assertNotNull(result);
        assertTrue(result.contains("network ports are already in use")); //$NON-NLS-1$
        assertFalse(result.contains("did not finish within")); //$NON-NLS-1$
    }

    @Test
    public void testAttributedAbandonmentRestoresAStoppedServerBeforeTheRecordIsCleared()
        throws Exception
    {
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.getName()).thenReturn("Cancelled launch"); //$NON-NLS-1$
        Mockito.when(config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, "")) //$NON-NLS-1$
            .thenReturn(""); //$NON-NLS-1$
        Mockito.when(config.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, "")) //$NON-NLS-1$
            .thenReturn("InfobaseApplication.Test"); //$NON-NLS-1$
        Mockito.doAnswer(invocation -> {
            StandaloneServerStateRecovery.recordStoppedServer("ServerApplication.Test"); //$NON-NLS-1$
            ((IProgressMonitor)invocation.getArgument(1)).setCanceled(true);
            return Mockito.mock(ILaunch.class);
        }).when(config).launch(Mockito.eq(ILaunchManager.DEBUG_MODE),
            Mockito.any(IProgressMonitor.class));

        try
        {
            StandaloneServerStateRecovery.launchWithRecovery(config, ILaunchManager.DEBUG_MODE,
                new NullProgressMonitor(), () -> "launch was abandoned"); //$NON-NLS-1$
            throw new AssertionError("an attributed cancellation must fail the launch"); //$NON-NLS-1$
        }
        catch (CoreException failure)
        {
            assertTrue(StandaloneServerStateRecovery.isAbandonedLaunch(failure));
            assertTrue(failure.getMessage().contains("launch was abandoned")); //$NON-NLS-1$
            assertTrue(failure.getMessage().contains(
                "was stopped for this operation and could NOT be started again")); //$NON-NLS-1$
        }
    }

    private ILaunchConfiguration cancelledConfiguration(String name) throws Exception
    {
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.getName()).thenReturn(name);
        Mockito.doAnswer(invocation -> {
            ((IProgressMonitor)invocation.getArgument(1)).setCanceled(true);
            return Mockito.mock(ILaunch.class);
        }).when(config).launch(Mockito.eq(ILaunchManager.DEBUG_MODE),
            Mockito.any(IProgressMonitor.class));
        return config;
    }

    private CoreException plainCancellation() throws Exception
    {
        try
        {
            StandaloneServerStateRecovery.launchWithRecovery(cancelledConfiguration("Cancelled launch"),
                ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
            throw new AssertionError("the delegate cancelled the launch");
        }
        catch (CoreException failure)
        {
            assertTrue(StandaloneServerStateRecovery.isAbandonedLaunch(failure));
            return failure;
        }
    }

    @Test
    public void testDefaultAbandonedMessageDoesNotReferToAnUnloggedError() throws Exception
    {
        assertEquals("Launch of 'Cancelled launch' was abandoned by EDT "
            + "(the launch delegate cancelled it); no reason was logged.", plainCancellation().getMessage());
    }

    private void assertRestorationCancellationIsError(
        StandaloneServerStateRecovery.RestorationStartOutcome outcome, String expected) throws Exception
    {
        CoreException cancelled = plainCancellation();
        String restored;
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer(APPLICATION_ID);
            restored = StandaloneServerStateRecovery.appendRestorationOutcome(cancelled.getMessage(),
                "Standalone", applicationId -> outcome);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
        assertTrue(restored, restored.contains(expected));
        Method failureStatus = StandaloneServerStateRecovery.class.getDeclaredMethod(
            "failureStatus", String.class, Throwable.class);
        failureStatus.setAccessible(true);
        CoreException failure = new CoreException((IStatus)failureStatus.invoke(null, restored, cancelled));
        assertTrue("restoration must retain the broad abandonment classification",
            StandaloneServerStateRecovery.isAbandonedLaunch(failure));
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.getName()).thenReturn("Restoration cancellation");
        Mockito.when(config.launch(Mockito.eq(ILaunchManager.DEBUG_MODE),
            Mockito.any(IProgressMonitor.class))).thenThrow(failure);
        assertCancellationJobIsError(config, expected);
    }

    private void assertCancellationJobIsError(ILaunchConfiguration config, String expected) throws Exception
    {
        List<IStatus> logs = new ArrayList<>();
        ILogListener listener = (status, plugin) -> {
            if (PLUGIN.equals(status.getPlugin()) && status.getMessage().startsWith("launch "))
            {
                logs.add(status);
            }
        };
        Method job = LaunchTool.class.getDeclaredMethod("runLaunchJobBody",
            ILaunchConfiguration.class, boolean.class, ExternalInfobaseChangesPolicy.class,
            IProgressMonitor.class);
        job.setAccessible(true);
        IStatus status;
        Platform.addLogListener(listener);
        try
        {
            status = (IStatus)job.invoke(null, config, false, ExternalInfobaseChangesPolicy.DEFAULT,
                new NullProgressMonitor());
        }
        finally
        {
            Platform.removeLogListener(listener);
        }
        assertEquals(status.getMessage(), IStatus.ERROR, status.getSeverity());
        assertTrue(status.getMessage(), status.getMessage().contains(expected));
        assertEquals(1, logs.size());
        assertEquals(IStatus.ERROR, logs.get(0).getSeverity());
        assertEquals("launch failed asynchronously: " + status.getMessage(), logs.get(0).getMessage());
        assertNotNull(logs.get(0).getException());
        assertTrue(AsyncLaunchOutcomes.recent().stream().anyMatch(outcome ->
            config.getName().equals(outcome.launchConfiguration())
                && status.getMessage().equals(outcome.message())));
    }

    @Test
    public void testCancellationWithFailedRestorationStaysError() throws Exception
    {
        assertRestorationCancellationIsError(
            new StandaloneServerStateRecovery.RestorationStartOutcome("restoration failed", true),
            "could NOT be started again: restoration failed");
    }

    @Test
    public void testCancellationWithInconclusiveRestorationStaysError() throws Exception
    {
        assertRestorationCancellationIsError(
            new StandaloneServerStateRecovery.RestorationStartOutcome("restoration deadline elapsed", false),
            "its restoration start did not finish conclusively: restoration deadline elapsed");
    }

    @Test
    public void testCancelledLaunchJobWithAnOperationStopStaysError() throws Exception
    {
        ILaunchConfiguration config = cancelledConfiguration("Stopped server cancellation");
        Mockito.doAnswer(invocation -> {
            StandaloneServerStateRecovery.recordStoppedServer(APPLICATION_ID);
            ((IProgressMonitor)invocation.getArgument(1)).setCanceled(true);
            return Mockito.mock(ILaunch.class);
        }).when(config).launch(Mockito.eq(ILaunchManager.DEBUG_MODE),
            Mockito.any(IProgressMonitor.class));
        assertCancellationJobIsError(config, "was stopped for this operation and could NOT be started again");
    }

    // ==================== a stop that outlives the wait for it (#623) ====================

    @Test
    public void testAStopStillInFlightRefusesAnUnrelatedLaterStart() throws Exception
    {
        WedgedStop wedged = new WedgedStop("InFlightStopProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            assertEquals("the wedged cleanup reached STOPPED before it stopped answering", //$NON-NLS-1$
                4, wedged.server.getServerState());

            // The unrelated later operation. It knows nothing about the wedged cleanup, and the
            // state it reads is a perfectly startable one - so only the guard can refuse it.
            assertRefusedWhileStopping(wedged);

            assertEquals("the refusal must come from the guard, not from a second cleanup", //$NON-NLS-1$
                1, wedged.cleanups.get());
        }
        finally
        {
            wedged.close();
        }
    }

    @Test
    public void testTheBoundedPreflightAlsoRefusesWhileAStopMayStillBeRunning() throws Exception
    {
        WedgedStop wedged = new WedgedStop("InFlightStopBoundedProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            try
            {
                StandaloneServerStateRecovery.ensureStartableWithinBound(wedged.project,
                    wedged.application, wedged.server, APPLICATION_ID, wedged.manager, null, null,
                    null);
                throw new AssertionError("the bounded pre-flight must refuse a start while a " //$NON-NLS-1$
                    + "dispatched stop of the same server may still be running"); //$NON-NLS-1$
            }
            catch (ApplicationException refused)
            {
                assertTrue(refused.getMessage(),
                    refused.getMessage().contains("an earlier stop of it started")); //$NON-NLS-1$
            }
            assertEquals(1, wedged.cleanups.get());
        }
        finally
        {
            wedged.close();
        }
    }

    @Test
    public void testAStopStillInFlightRefusesTheBoundedStartClaim() throws Exception
    {
        WedgedStop wedged = new WedgedStop("InFlightStopClaimProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            assertNull("a start claim is ownership, and the wedged cleanup still holds it", //$NON-NLS-1$
                StandaloneServerStateRecovery.claimStartWithinBound(wedged.project,
                    APPLICATION_ID, 1_000L, null));

            wedged.finishCleanup();
            assertTrue("the claim must be grantable again once the cleanup finished", //$NON-NLS-1$
                wedged.awaitOwnershipReleased(10_000L));
        }
        finally
        {
            wedged.close();
        }
    }

    @Test
    public void testRestorationSkipsAServerWhoseStopMayStillBeRunning() throws Exception
    {
        WedgedStop wedged = new WedgedStop("InFlightStopRestorationProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            AtomicInteger restores = new AtomicInteger();
            String message;
            StandaloneServerStateRecovery.beginOperation();
            try
            {
                StandaloneServerStateRecovery.recordStoppedServer(APPLICATION_ID);
                StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                    StandaloneServerStateRecovery.restoreIfStillUnowned(wedged.project,
                        new FakeServer(4, null), APPLICATION_ID, () -> {
                            restores.incrementAndGet();
                            return new StandaloneServerStateRecovery.RestorationStartOutcome(null,
                                true);
                        });
                message = StandaloneServerStateRecovery.appendRestorationOutcome(
                    "operation failed.", "Standalone", applicationId -> outcome); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                StandaloneServerStateRecovery.endOperation();
            }

            assertEquals("restoring would start the very server the wedged cleanup takes down", //$NON-NLS-1$
                0, restores.get());
            assertTrue(message, message.contains("an earlier stop of it started")); //$NON-NLS-1$
        }
        finally
        {
            wedged.close();
        }
    }

    @Test
    public void testTheStopRecordIsReleasedWhenTheWedgedCleanupFinishes() throws Exception
    {
        WedgedStop wedged = new WedgedStop("InFlightStopReleaseProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            // Proves the release below is not vacuous: without this, a guard that never took
            // ownership at all would pass the rest of this test.
            assertRefusedWhileStopping(wedged);

            wedged.finishCleanup();
            // Stale again, so a guard that HAS handed ownership back has visible work to do.
            wedged.server.setState(2);

            long deadline = System.currentTimeMillis() + 10_000L;
            String lastRefusal = null;
            boolean proceeded = false;
            while (System.currentTimeMillis() < deadline)
            {
                try
                {
                    StandaloneServerStateRecovery.ensureStartable(wedged.project,
                        wedged.application, APPLICATION_ID, wedged.manager);
                    proceeded = true;
                    break;
                }
                catch (ApplicationException stillRefused)
                {
                    lastRefusal = stillRefused.getMessage();
                    Thread.sleep(20L);
                }
            }

            assertTrue("a finished cleanup must stop refusing later starts, or one hung stop " //$NON-NLS-1$
                + "refuses this application until EDT restarts: " + lastRefusal, proceeded); //$NON-NLS-1$
            assertEquals("the released guard must let the later start run its own stale stop", //$NON-NLS-1$
                2, wedged.cleanups.get());
            assertEquals(4, wedged.server.getServerState());
        }
        finally
        {
            wedged.close();
        }
    }

    @Test
    public void testTheStopRecordCannotOutliveItsHardCap() throws Exception
    {
        long originalCap = StandaloneServerStateRecovery.stopInFlightCapMs;
        StandaloneServerStateRecovery.stopInFlightCapMs = 150L;
        WedgedStop wedged = new WedgedStop("InFlightStopCapProject"); //$NON-NLS-1$
        try
        {
            wedged.dispatchAndAbandon();
            // The cleanup is still wedged and nothing will ever report it finished. A record that
            // waited only for that report would refuse every start of this application until EDT
            // restarts - worse than the defect it closes.
            assertTrue("the hard cap must hand ownership back with the cleanup still wedged", //$NON-NLS-1$
                wedged.awaitOwnershipReleased(10_000L));
        }
        finally
        {
            StandaloneServerStateRecovery.stopInFlightCapMs = originalCap;
            wedged.close();
        }
    }

    /** Asserts the pre-flight refuses an unrelated start, and says why and what to do. */
    private static void assertRefusedWhileStopping(WedgedStop wedged)
    {
        try
        {
            StandaloneServerStateRecovery.ensureStartable(wedged.project, wedged.application,
                APPLICATION_ID, wedged.manager);
            throw new AssertionError("a start must not proceed while a dispatched stop of the " //$NON-NLS-1$
                + "same server may still be running"); //$NON-NLS-1$
        }
        catch (ApplicationException refused)
        {
            assertTrue(refused.getMessage(),
                refused.getMessage().contains("an earlier stop of it started")); //$NON-NLS-1$
            assertTrue("the refusal must name the way out", //$NON-NLS-1$
                refused.getMessage().contains("Wait for it to finish")); //$NON-NLS-1$
        }
    }

    /**
     * The #623 fixture: a stale server whose stop is dispatched, gets as far as {@code STOPPED},
     * and then stops answering - so the bounded wait for it returns while the cleanup runs on.
     */
    private static final class WedgedStop implements AutoCloseable
    {
        final IProject project;
        final IApplication application;
        final FakeServer server = new FakeServer(2, null);
        final IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        final AtomicInteger cleanups = new AtomicInteger();
        private final CountDownLatch dispatched = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        WedgedStop(String projectName)
        {
            project = Mockito.mock(IProject.class);
            Mockito.when(project.getName()).thenReturn(projectName);
            IApplicationType type = Mockito.mock(IApplicationType.class);
            Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
            application = Mockito.mock(IApplication.class,
                Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
            Mockito.when(application.getType()).thenReturn(type);
            Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
            Mockito.doAnswer(invocation -> {
                server.setState(4);
                if (cleanups.incrementAndGet() == 1)
                {
                    dispatched.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
                return null;
            }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
                Mockito.any(IProgressMonitor.class));
        }

        /**
         * Runs the real guarded stop and makes its bounded wait give up while the cleanup runs on.
         *
         * <p>The wait is ABANDONED rather than timed out, and on its own thread: a short deadline
         * would race the Job scheduler, and a job the deadline caught before it started is a
         * conclusive outcome that reproduces nothing. Waiting for the cleanup to enter and then
         * interrupting the waiter reproduces the same in-flight outcome with no timing assumption,
         * and leaves no interrupt flag on the thread running the test.
         */
        void dispatchAndAbandon() throws InterruptedException
        {
            Thread dispatcher = new Thread(
                () -> StandaloneServerStateRecovery.restoreIfStillUnowned(project, application,
                    server, APPLICATION_ID, manager, 30_000L, () -> {
                        throw new AssertionError(
                            "an unfinished stop must not reach the restoration start"); //$NON-NLS-1$
                    }),
                "test: abandoned standalone-server stop"); //$NON-NLS-1$
            dispatcher.setDaemon(true);
            dispatcher.start();
            assertTrue("the wedged cleanup must be running by now", //$NON-NLS-1$
                dispatched.await(10, TimeUnit.SECONDS));
            // Re-interrupted until it lands: an interrupt raised in a window where the waiter is
            // not blocked yet only sets a flag the platform's join may clear again.
            long deadline = System.currentTimeMillis() + 30_000L;
            while (dispatcher.isAlive() && System.currentTimeMillis() < deadline)
            {
                dispatcher.interrupt();
                dispatcher.join(200L);
            }
            assertFalse("the bounded caller must have given up on the wedged cleanup", //$NON-NLS-1$
                dispatcher.isAlive());
        }

        /** Lets EDT's cleanup return at last. */
        void finishCleanup()
        {
            release.countDown();
        }

        /** Whether the guard hands a start claim out again within {@code timeoutMs}. */
        boolean awaitOwnershipReleased(long timeoutMs) throws InterruptedException
        {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline)
            {
                StandaloneServerStateRecovery.StartClaim claim =
                    StandaloneServerStateRecovery.claimStartWithinBound(project, APPLICATION_ID,
                        200L, null);
                if (claim != null)
                {
                    claim.close();
                    return true;
                }
                Thread.sleep(20L);
            }
            return false;
        }

        @Override
        public void close() throws InterruptedException
        {
            finishCleanup();
            awaitOwnershipReleased(10_000L);
        }
    }

    // ==================== explicit stop, hold and start (#890) ====================

    @Test
    public void testTheExplicitStopDecisionTable()
    {
        StandaloneServerStateRecovery.StopPolicy running =
            StandaloneServerStateRecovery.StopPolicy.RUNNING;
        assertEquals(StandaloneServerStateRecovery.StopDecision.STOP,
            StandaloneServerStateRecovery.decideStop(2, Boolean.TRUE, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.STOP,
            StandaloneServerStateRecovery.decideStop(2, Boolean.FALSE, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.STOP,
            StandaloneServerStateRecovery.decideStop(2, null, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.WAIT_SETTLE,
            StandaloneServerStateRecovery.decideStop(1, Boolean.TRUE, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.WAIT_SETTLE,
            StandaloneServerStateRecovery.decideStop(3, Boolean.FALSE, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOOP,
            StandaloneServerStateRecovery.decideStop(4, Boolean.FALSE, running));
        assertEquals(StandaloneServerStateRecovery.StopDecision.UNREADABLE,
            StandaloneServerStateRecovery.decideStop(null, Boolean.TRUE, running));
    }

    @Test
    public void testTheStaleOnlyPolicyIsExactlyTheRecoveryDecision()
    {
        StandaloneServerStateRecovery.StopPolicy stale =
            StandaloneServerStateRecovery.StopPolicy.STALE_ONLY;
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOOP,
            StandaloneServerStateRecovery.decideStop(2, Boolean.TRUE, stale));
        assertEquals(StandaloneServerStateRecovery.StopDecision.STOP,
            StandaloneServerStateRecovery.decideStop(2, Boolean.FALSE, stale));
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOOP,
            StandaloneServerStateRecovery.decideStop(2, null, stale));
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOOP,
            StandaloneServerStateRecovery.decideStop(null, Boolean.FALSE, stale));
        assertEquals(StandaloneServerStateRecovery.StopDecision.WAIT_SETTLE,
            StandaloneServerStateRecovery.decideStop(1, Boolean.FALSE, stale));
    }

    @Test
    public void testTheStaleOnlyStopLeavesAHealthyServerAlone()
    {
        ServerApp app = new ServerApp("StaleOnlyHealthyProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
            StandaloneServerStateRecovery.StopPolicy.STALE_ONLY);
        assertTrue(recovery.recovered());
        assertFalse(recovery.alreadyStopped());
        assertEquals("a server with a live launch is somebody's healthy server", 0, app.cleanups.get()); //$NON-NLS-1$
        assertNull("the recovery never holds a server", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testTheExplicitStopHoldsTheServerBeforeTheCleanupRuns() throws Exception
    {
        ServerApp app = new ServerApp("ExplicitStopHoldProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        AtomicReference<String> heldDuringCleanup = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            heldDuringCleanup.set(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            app.cleanups.incrementAndGet();
            app.server.setState(4);
            return null;
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        try
        {
            StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
                app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
                StandaloneServerStateRecovery.StopPolicy.RUNNING);

            assertTrue(recovery.detail(), recovery.recovered());
            assertEquals("a live launch does not protect a server from an explicit stop", 1, //$NON-NLS-1$
                app.cleanups.get());
            assertNotNull("the hold must stand BEFORE EDT's cleanup runs", heldDuringCleanup.get()); //$NON-NLS-1$
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            assertHeldRefusal(() -> StandaloneServerStateRecovery.ensureStartable(app.project,
                app.application, APPLICATION_ID, app.manager));
            assertHeldRefusal(() -> StandaloneServerStateRecovery.ensureStartableWithinBound(app.project,
                app.application, app.server, APPLICATION_ID, app.manager, null, null, null));
            assertHeldRefusal(() -> StandaloneServerStateRecovery.claimStartWithinBound(app.project,
                APPLICATION_ID, 1_000L, null));

            assertTrue(StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID));
            StandaloneServerStateRecovery.ensureStartable(app.project, app.application, APPLICATION_ID,
                app.manager);
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnAlreadyStoppedServerIsHeldWithoutACleanup()
    {
        ServerApp app = new ServerApp("ExplicitStopNoopProject", new FakeServer(4, null)); //$NON-NLS-1$
        try
        {
            StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
                app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
                StandaloneServerStateRecovery.StopPolicy.RUNNING);
            assertTrue(recovery.recovered());
            assertTrue(recovery.alreadyStopped());
            assertEquals(0, app.cleanups.get());
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAConclusivelyFailedStopLeavesNoHoldBehind()
    {
        ServerApp app = new ServerApp("ExplicitStopFailsProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.doThrow(new ApplicationException("EDT refused the stop")).when(app.manager).cleanup( //$NON-NLS-1$
            Mockito.same(app.application), Mockito.any(ExecutionContext.class),
            Mockito.any(IProgressMonitor.class));
        StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING);
        assertFalse(recovery.recovered());
        assertFalse(recovery.stopStillInFlight());
        assertNull("nothing was stopped, so nothing may stay held", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testAnUnfinishedExplicitStopKeepsTheHoldAndItsInFlightRecord() throws Exception
    {
        ServerApp app = new ServerApp("ExplicitStopWedgedProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            app.cleanups.incrementAndGet();
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            app.server.setState(4);
            return null;
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        AtomicReference<StandaloneServerStateRecovery.Recovery> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> outcome.set(StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 30_000L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING)), "test: abandoned explicit stop"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        try
        {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 30_000L;
            while (caller.isAlive() && System.currentTimeMillis() < deadline)
            {
                caller.interrupt();
                caller.join(200L);
            }
            assertFalse(caller.isAlive());
            assertTrue(outcome.get().stopStillInFlight());
            assertNotNull("a stop that may still run keeps the server held", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            assertFalse("an unfinished stop must not let an observer release its hold", //$NON-NLS-1$
                StandaloneServerStateRecovery.releaseHoldIfStartedOutside(app.project,
                    APPLICATION_ID, app.server));
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            StandaloneServerStateRecovery.Recovery second = StandaloneServerStateRecovery.stopGuarded(
                app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
                StandaloneServerStateRecovery.StopPolicy.RUNNING);
            assertFalse(second.recovered());
            assertTrue(second.detail(), second.detail().contains("an earlier stop of it started")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline)
            {
                StandaloneServerStateRecovery.StartClaim claim =
                    StandaloneServerStateRecovery.claimStartWithinBound(app.project, APPLICATION_ID, 200L,
                        null);
                if (claim != null)
                {
                    claim.close();
                    break;
                }
                Thread.sleep(20L);
            }
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
        assertEquals(1, app.cleanups.get());
    }

    @Test
    public void testAHoldIsReleasedWhenTheServerIsFoundRunningUnderALiveLaunch()
    {
        ServerApp app = new ServerApp("HoldReleasedByEdtStartProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        StandaloneServerStateRecovery.ensureStartable(app.project, app.application, APPLICATION_ID,
            app.manager);
        assertNull("somebody started it from EDT, so the hold no longer describes anything", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        assertEquals(0, app.cleanups.get());
    }

    @Test
    public void testRestorationSkipsAHeldServer()
    {
        ServerApp app = new ServerApp("HeldRestorationProject", new FakeServer(4, null)); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        AtomicInteger restores = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.RestorationStartOutcome outcome =
                StandaloneServerStateRecovery.restoreIfStillUnowned(app.project, app.application,
                    app.server, APPLICATION_ID, app.manager, 10_000L, () -> {
                        restores.incrementAndGet();
                        return new StandaloneServerStateRecovery.RestorationStartOutcome(null, true);
                    });
            StandaloneServerStateRecovery.beginOperation();
            String message;
            try
            {
                StandaloneServerStateRecovery.recordStoppedServer(APPLICATION_ID);
                message = StandaloneServerStateRecovery.appendRestorationOutcome("operation failed.", //$NON-NLS-1$
                    "Standalone", applicationId -> outcome); //$NON-NLS-1$
            }
            finally
            {
                StandaloneServerStateRecovery.endOperation();
            }
            assertEquals(0, restores.get());
            assertTrue(message, message.contains("holds it stopped")); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnExplicitStartReleasesTheHoldOnlyWhenItSucceeds()
    {
        ServerApp app = new ServerApp("ExplicitStartProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            StandaloneServerStateRecovery.StartOutcome failed = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager, new FailingRestorationService(),
                StandaloneServerPortConflictPolicy.CANCEL, 30_000L);
            assertEquals(StandaloneServerStateRecovery.StartKind.FAILED, failed.kind());
            assertTrue(failed.detail(), failed.detail().contains("start failed")); //$NON-NLS-1$
            assertNotNull("a failed start keeps the hold", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));

            StandaloneServerStateRecovery.StartOutcome started = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager,
                new StartingService(app.server), StandaloneServerPortConflictPolicy.CANCEL, 30_000L);
            assertEquals(started.detail(), StandaloneServerStateRecovery.StartKind.STARTED, started.kind());
            assertNull("a successful start releases the hold", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            assertEquals(2, app.server.getServerState());
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAHoldOnlyRequestNeverStopsAndRefusesAStartedServer()
    {
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOT_STOPPED,
            StandaloneServerStateRecovery.decideStop(2, Boolean.TRUE,
                StandaloneServerStateRecovery.StopPolicy.HOLD_ONLY));
        assertEquals(StandaloneServerStateRecovery.StopDecision.NOOP,
            StandaloneServerStateRecovery.decideStop(4, Boolean.FALSE,
                StandaloneServerStateRecovery.StopPolicy.HOLD_ONLY));
        ServerApp app = new ServerApp("HoldOnlyStartedProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
            StandaloneServerStateRecovery.StopPolicy.HOLD_ONLY);
        assertFalse(recovery.recovered());
        assertEquals("a hold-only request must never end anybody's sessions", 0, app.cleanups.get()); //$NON-NLS-1$
        assertNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testNoReleaseWhileTheExplicitStopIsInFlight() throws Exception
    {
        ServerApp app = new ServerApp("ReleaseDuringStopProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            app.cleanups.incrementAndGet();
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            app.server.setState(4);
            return null;
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        AtomicReference<StandaloneServerStateRecovery.Recovery> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> outcome.set(StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 30_000L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING)), "test: explicit stop in flight"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        try
        {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            // STARTED under a live launch - exactly what a start from EDT's Servers view leaves.
            assertFalse("a hold must never be released while its stop is in flight", //$NON-NLS-1$
                StandaloneServerStateRecovery.releaseHoldIfStartedOutside(app.project,
                    APPLICATION_ID, app.server));
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            release.countDown();
            caller.join(30_000L);
        }
        try
        {
            assertTrue(outcome.get().recovered());
            assertNotNull("the stop concluded and the server is held", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testARecoveredStopPutsBackAHoldClearedDuringIt()
    {
        ServerApp app = new ServerApp("HoldClearedDuringStopProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.doAnswer(invocation -> {
            app.cleanups.incrementAndGet();
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
            app.server.setState(4);
            return null;
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        try
        {
            assertTrue(StandaloneServerStateRecovery.stopGuarded(app.project, app.application,
                APPLICATION_ID, app.manager, 10_000L,
                StandaloneServerStateRecovery.StopPolicy.RUNNING).recovered());
            assertNotNull("a concluded explicit stop leaves the server held", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAStopThatThrowsPutsThePreviousHoldBack()
    {
        ServerApp app = new ServerApp("ThrowingStopProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.doThrow(new NoClassDefFoundError("simulated")).when(app.manager).cleanup( //$NON-NLS-1$
            Mockito.same(app.application), Mockito.any(ExecutionContext.class),
            Mockito.any(IProgressMonitor.class));
        try
        {
            StandaloneServerStateRecovery.stopGuarded(app.project, app.application, APPLICATION_ID,
                app.manager, 10_000L, StandaloneServerStateRecovery.StopPolicy.RUNNING);
        }
        catch (Throwable expected) // NOSONAR the stop may rethrow its Job's Error
        {
            // Either shape is fine; what matters is the hold below.
        }
        assertNull("nothing was stopped, so the hold must be put back as it was", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testAFailedStopThatLeftTheServerStoppedKeepsTheHold()
    {
        ServerApp app = new ServerApp("FailedButStoppedProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.doAnswer(invocation -> {
            app.server.setState(4);
            throw new ApplicationException("stopped, then failed"); //$NON-NLS-1$
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        try
        {
            StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
                app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
                StandaloneServerStateRecovery.StopPolicy.RUNNING);
            assertFalse(recovery.recovered());
            assertTrue(recovery.dispatchedAndFailed());
            assertNotNull("a server that reads STOPPED must not be left unheld", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAStartWithoutAResolvedApplicationStillReleasesAHeldRunningServer()
    {
        ServerApp app = new ServerApp("HeldLookupProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.when(app.manager.getApplication(app.project, APPLICATION_ID))
            .thenReturn(Optional.of(app.application));
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        StandaloneServerStateRecovery.ensureStartable(app.project, null, APPLICATION_ID, app.manager);
        assertNull("launchWithRecovery and the default-application pre-flight pass no application", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testAnUnresolvableServerKeepsTheHoldRefusing()
    {
        ServerApp app = new ServerApp("HeldNoLookupProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        Mockito.when(app.manager.getApplication(app.project, APPLICATION_ID))
            .thenReturn(Optional.empty());
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            assertHeldRefusal(() -> StandaloneServerStateRecovery.ensureStartable(app.project, null,
                APPLICATION_ID, app.manager));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testTheServerAwareClaimReleasesAHeldRunningServer()
    {
        ServerApp app = new ServerApp("HeldClaimProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        StandaloneServerStateRecovery.StartClaim claim = StandaloneServerStateRecovery
            .claimStartWithinBound(app.project, APPLICATION_ID, app.server, 1_000L, null);
        try
        {
            assertNotNull(claim);
            assertNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            if (claim != null)
            {
                claim.close();
            }
        }
    }

    @Test
    public void testTheRetryAfterAStaleStopRefusesAHeldServer()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("HeldRetryProject"); //$NON-NLS-1$
        assertTrue(StandaloneServerStateRecovery.stopForRetry(project, APPLICATION_ID,
            StandaloneServerStateRecovery.Recovery::stopped).recovered());
        StandaloneServerStateRecovery.holdStopped(project, APPLICATION_ID);
        try
        {
            assertHeldRefusal(() -> StandaloneServerStateRecovery.stopForRetry(project, APPLICATION_ID,
                StandaloneServerStateRecovery.Recovery::stopped));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(project, APPLICATION_ID);
        }
    }

    @Test
    public void testNoObserverReleaseWhileAStartIsClaimed()
    {
        ServerApp app = new ServerApp("ClaimedStartReleaseProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.StartClaim claim =
            StandaloneServerStateRecovery.claimStartWithinBound(app.project, APPLICATION_ID, 1_000L, null);
        assertNotNull(claim);
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            assertFalse("a start that still claims the server may still be running; only its own " //$NON-NLS-1$
                + "conclusive end releases the hold", //$NON-NLS-1$
                StandaloneServerStateRecovery.releaseHoldIfStartedOutside(app.project,
                    APPLICATION_ID, app.server));
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            claim.close();
            assertTrue(StandaloneServerStateRecovery.releaseHoldIfStartedOutside(app.project,
                APPLICATION_ID, app.server));
        }
        finally
        {
            claim.close();
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnOkStartThatLeavesTheServerStoppedKeepsTheHold()
    {
        ServerApp app = new ServerApp("OkButStoppedStartProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager,
                new StateSettingService(app.server, 4), StandaloneServerPortConflictPolicy.CANCEL, 30_000L);
            assertEquals(StandaloneServerStateRecovery.StartKind.FAILED, outcome.kind());
            assertTrue("STOPPED after an OK start is a start that did not take", outcome.conclusive()); //$NON-NLS-1$
            assertTrue(outcome.detail(), outcome.detail().contains("reads STOPPED")); //$NON-NLS-1$
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnOkStartStillStartingIsInconclusiveAndKeepsTheHold()
    {
        ServerApp app = new ServerApp("OkButStartingStartProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager,
                new StateSettingService(app.server, 1), StandaloneServerPortConflictPolicy.CANCEL, 30_000L);
            assertEquals(StandaloneServerStateRecovery.StartKind.FAILED, outcome.kind());
            assertFalse("STARTING may still become a running server", outcome.conclusive()); //$NON-NLS-1$
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testTheApplicationLockIsHeldAcrossTheWorkAndRefusesWhenTaken() throws Exception
    {
        String project = "ApplicationLockProject"; //$NON-NLS-1$
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            LaunchLifecycleUtils.LaunchLock lock = LaunchLifecycleUtils.lockFor(project, APPLICATION_ID);
            if (lock.tryAcquire(5_000L, null).acquired())
            {
                try
                {
                    held.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    lock.unlock();
                }
            }
        }, "test: application lock holder"); //$NON-NLS-1$
        holder.setDaemon(true);
        holder.start();
        AtomicInteger ran = new AtomicInteger();
        try
        {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            try
            {
                StandaloneServerStateRecovery.runUnderApplicationLock(project, APPLICATION_ID, 200L,
                    null, "Nothing was built.", ran::incrementAndGet); //$NON-NLS-1$
                throw new AssertionError("a taken application lock must refuse the work"); //$NON-NLS-1$
            }
            catch (ApplicationException refused)
            {
                assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was built.")); //$NON-NLS-1$
            }
            assertEquals(0, ran.get());
        }
        finally
        {
            release.countDown();
            holder.join(10_000L);
        }
        AtomicReference<Boolean> heldDuringWork = new AtomicReference<>();
        StandaloneServerStateRecovery.runUnderApplicationLock(project, APPLICATION_ID, 5_000L, null,
            "Nothing was built.", () -> heldDuringWork.set(Boolean.valueOf( //$NON-NLS-1$
                LaunchLifecycleUtils.lockFor(project, APPLICATION_ID).isHeldByCurrentThread())));
        assertEquals(Boolean.TRUE, heldDuringWork.get());
    }

    @Test
    public void testTheDefaultApplicationPreflightAndWorkRunUnderItsLock()
    {
        ServerApp app = new ServerApp("DefaultAppLockProject", new FakeServer(4, null)); //$NON-NLS-1$
        Mockito.when(app.manager.getApplication(app.project, APPLICATION_ID))
            .thenReturn(Optional.of(app.application));
        AtomicReference<Boolean> lockedDuringWork = new AtomicReference<>();
        StandaloneServerStateRecovery.defaultApplicationStartableThen(app.project, app.manager,
            APPLICATION_ID, 5_000L, null, "Nothing was built.", () -> lockedDuringWork.set( //$NON-NLS-1$
                Boolean.valueOf(LaunchLifecycleUtils.lockFor("DefaultAppLockProject", APPLICATION_ID) //$NON-NLS-1$
                    .isHeldByCurrentThread())));
        assertEquals("the dump that prepares the application must run under its infobase lock", //$NON-NLS-1$
            Boolean.TRUE, lockedDuringWork.get());

        // ...and the pre-flight inside that lock still refuses a held server.
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        AtomicInteger ran = new AtomicInteger();
        try
        {
            assertHeldRefusal(() -> StandaloneServerStateRecovery.defaultApplicationStartableThen(
                app.project, app.manager, APPLICATION_ID, 5_000L, null, "Nothing was built.", //$NON-NLS-1$
                ran::incrementAndGet));
            assertEquals(0, ran.get());
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnUnfinishedExplicitStopKeepsItsRecordPastTheRecoveryCap() throws Exception
    {
        long originalCap = StandaloneServerStateRecovery.stopInFlightCapMs;
        StandaloneServerStateRecovery.stopInFlightCapMs = 150L;
        ServerApp app = new ServerApp("ExplicitStopNoCapProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            app.server.setState(4);
            return null;
        }).when(app.manager).cleanup(Mockito.same(app.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        Thread caller = new Thread(() -> StandaloneServerStateRecovery.stopGuarded(app.project,
            app.application, APPLICATION_ID, app.manager, 30_000L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING), "test: abandoned explicit stop"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        try
        {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 30_000L;
            while (caller.isAlive() && System.currentTimeMillis() < deadline)
            {
                caller.interrupt();
                caller.join(200L);
            }
            assertFalse(caller.isAlive());
            Thread.sleep(1_000L);
            assertNotNull("a still-running explicit stop must not be dropped by a time cap", //$NON-NLS-1$
                StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID));
        }
        finally
        {
            release.countDown();
            StandaloneServerStateRecovery.stopInFlightCapMs = originalCap;
        }
        long deadline = System.currentTimeMillis() + 10_000L;
        while (StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID) != null
            && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        try
        {
            assertNull("the record ends when EDT's cleanup Job ends", //$NON-NLS-1$
                StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnUnfinishedExplicitStartKeepsItsClaimPastTheGuardCap() throws Exception
    {
        long originalCap = StandaloneServerStateRecovery.explicitStartGuardCapMs;
        StandaloneServerStateRecovery.explicitStartGuardCapMs = 150L;
        ServerApp app = new ServerApp("ExplicitStartNoCapProject", new FakeServer(4, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager,
                new BlockingRestorationService(started, release), StandaloneServerPortConflictPolicy.CANCEL,
                6_000L);
            assertFalse("the start did not finish within its bound", outcome.conclusive()); //$NON-NLS-1$
            Thread.sleep(1_000L);
            // The start reaches RUNNING under a live launch while its Job is still going.
            app.server.setState(2);
            assertNotNull("an unfinished explicit start keeps its claim past the guard cap", //$NON-NLS-1$
                StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID));
            assertFalse("only the start's own conclusive end may release the hold", //$NON-NLS-1$
                StandaloneServerStateRecovery.releaseHoldIfStartedOutside(app.project, APPLICATION_ID,
                    app.server));
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            release.countDown();
            StandaloneServerStateRecovery.explicitStartGuardCapMs = originalCap;
        }
        long deadline = System.currentTimeMillis() + 10_000L;
        while (StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID) != null
            && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
        }
        try
        {
            assertNull("the claim ends when the start Job ends", //$NON-NLS-1$
                StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testTheStartObservationStaysInsideTheWholeStartBound()
    {
        ServerApp app = new ServerApp("BoundedObservationProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        try
        {
            long begin = System.nanoTime();
            StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
                app.project, app.application, APPLICATION_ID, app.manager,
                new SlowStateSettingService(app.server, 1, 5_500L), StandaloneServerPortConflictPolicy.CANCEL,
                6_500L);
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
            assertEquals(StandaloneServerStateRecovery.StartKind.FAILED, outcome.kind());
            assertFalse("an unobserved STARTED is inconclusive", outcome.conclusive()); //$NON-NLS-1$
            assertTrue("the observation must fit the 6.5s bound, not add 5s after it: " + elapsedMs, //$NON-NLS-1$
                elapsedMs < 8_500L);
            assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnExplicitStopASuspendedManagerHoldsKeepsItsRecordUntilTheJobEnds() throws Exception
    {
        ServerApp app = new ServerApp("SuspendedStopProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
        try
        {
            jobs.suspend();
            try
            {
                StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
                    app.project, app.application, APPLICATION_ID, app.manager, 10_000L,
                    StandaloneServerStateRecovery.StopPolicy.RUNNING);
                assertFalse(recovery.recovered());
                assertTrue("a queued cleanup may still run after the resume: " + recovery.detail(), //$NON-NLS-1$
                    recovery.stopStillInFlight());
                assertNotNull("its ownership must outlive the bounded wait", //$NON-NLS-1$
                    StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID));
                assertNotNull("and the server stays held", //$NON-NLS-1$
                    StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
                assertEquals(0, app.cleanups.get());
            }
            finally
            {
                jobs.resume();
            }
            long deadline = System.currentTimeMillis() + 10_000L;
            while (StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID) != null
                && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(20L);
            }
            assertNull("the record ends with the Job's terminal notification", //$NON-NLS-1$
                StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID));
            assertEquals("the queued cleanup ran after the resume", 1, app.cleanups.get()); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnExplicitStartASuspendedManagerHoldsKeepsItsClaimUntilTheJobEnds() throws Exception
    {
        ServerApp app = new ServerApp("SuspendedStartProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
        try
        {
            jobs.suspend();
            try
            {
                StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
                    app.project, app.application, APPLICATION_ID, app.manager,
                    new StartingService(app.server), StandaloneServerPortConflictPolicy.CANCEL, 30_000L);
                assertEquals(StandaloneServerStateRecovery.StartKind.FAILED, outcome.kind());
                assertFalse("a queued start may still run after the resume: " + outcome.detail(), //$NON-NLS-1$
                    outcome.conclusive());
                assertNotNull("its claim must outlive the bounded wait", //$NON-NLS-1$
                    StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID));
                assertNotNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
            }
            finally
            {
                jobs.resume();
            }
            long deadline = System.currentTimeMillis() + 10_000L;
            while (StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID) != null
                && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(20L);
            }
            assertNull("the claim ends with the Job's terminal notification", //$NON-NLS-1$
                StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID));
            assertEquals("the queued start ran after the resume", 2, app.server.getServerState()); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testAnInconclusiveDefaultApplicationLookupRefusesTheWork()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("InconclusiveDefaultAppProject"); //$NON-NLS-1$
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        AtomicInteger ran = new AtomicInteger();
        try
        {
            StandaloneServerStateRecovery.defaultApplicationLookupThen(project, manager,
                new LaunchLifecycleUtils.DefaultApplicationLookup(null,
                    "the EDT default-application lookup did not finish within 30s"), //$NON-NLS-1$
                5_000L, null, "Nothing was built.", ran::incrementAndGet); //$NON-NLS-1$
            throw new AssertionError("an unknown default application must not be prepared blind"); //$NON-NLS-1$
        }
        catch (ApplicationException refused)
        {
            String message = refused.getMessage();
            assertTrue(message, message.contains("could not be determined")); //$NON-NLS-1$
            assertTrue(message, message.contains("did not finish within 30s")); //$NON-NLS-1$
            assertTrue(message, message.contains("Nothing was built.")); //$NON-NLS-1$
            assertTrue(message, message.contains("get_applications")); //$NON-NLS-1$
        }
        assertEquals(0, ran.get());
        // A CONCLUDED "no default application" has no server to start: the work runs as before.
        StandaloneServerStateRecovery.defaultApplicationLookupThen(project, manager,
            new LaunchLifecycleUtils.DefaultApplicationLookup(null, null), 5_000L, null,
            "Nothing was built.", ran::incrementAndGet); //$NON-NLS-1$
        assertEquals(1, ran.get());
    }

    @Test
    public void testAStopWhosePreDispatchReadSpendsTheDeadlineIsNotDispatched()
    {
        ServerApp app = new ServerApp("SpentDeadlineStopProject", new FakeServer(2, launch(false))); //$NON-NLS-1$
        StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 1_500L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING, server -> {
                try
                {
                    Thread.sleep(2_000L);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });
        assertFalse(recovery.recovered());
        assertFalse("nothing was dispatched, so nothing is in flight", recovery.stopStillInFlight()); //$NON-NLS-1$
        assertFalse(recovery.dispatchedAndFailed());
        assertEquals("the cleanup must not run on a spent deadline", 0, app.cleanups.get()); //$NON-NLS-1$
        assertNull(StandaloneServerStateRecovery.stopInFlightSeconds(app.project, APPLICATION_ID));
        assertNull("a stop that never ran leaves no hold on a running server", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    @Test
    public void testTheStartSettleWaitStaysInsideTheWholeStartBound()
    {
        ServerApp app = new ServerApp("SettleBoundStartProject", new FakeServer(1, launch(true))); //$NON-NLS-1$
        long begin = System.nanoTime();
        StandaloneServerStateRecovery.StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(
            app.project, app.application, APPLICATION_ID, app.manager, new StartingService(app.server),
            StandaloneServerPortConflictPolicy.CANCEL, 2_000L);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
        assertEquals(StandaloneServerStateRecovery.StartKind.REFUSED, outcome.kind());
        assertTrue(outcome.detail(), outcome.detail().contains("did not settle")); //$NON-NLS-1$
        assertTrue("a 2s start must not wait the 30s settle bound: " + elapsedMs, elapsedMs < 10_000L); //$NON-NLS-1$
        assertEquals("nothing was started", 1, app.server.getServerState()); //$NON-NLS-1$
    }

    @Test
    public void testAQueuedReassignStartReportsItsPortOutcomeUnknown() throws Exception
    {
        ServerApp app = new ServerApp("QueuedReassignProject", new FakeServer(4, launch(true))); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(app.project, APPLICATION_ID);
        org.eclipse.core.runtime.jobs.IJobManager jobs = org.eclipse.core.runtime.jobs.Job.getJobManager();
        try
        {
            StandaloneServerStateRecovery.StartOutcome outcome;
            jobs.suspend();
            try
            {
                outcome = StandaloneServerStateRecovery.startExplicitly(app.project, app.application,
                    APPLICATION_ID, app.manager, new StartingService(app.server),
                    StandaloneServerPortConflictPolicy.REASSIGN, 30_000L);
            }
            finally
            {
                jobs.resume();
            }
            assertFalse(outcome.conclusive());
            assertFalse(outcome.portsReassigned());
            assertTrue("a start that may still run may still move the ports", //$NON-NLS-1$
                outcome.portReassignmentOutcomeUnknown());
            long deadline = System.currentTimeMillis() + 10_000L;
            while (StandaloneServerStateRecovery.startClaimSeconds(app.project, APPLICATION_ID) != null
                && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(20L);
            }
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(app.project, APPLICATION_ID);
        }
    }

    @Test
    public void testWithoutAnApplicationManagerAHeldProjectRefusesTheWork()
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("NoManagerHeldProject"); //$NON-NLS-1$
        IProject other = Mockito.mock(IProject.class);
        Mockito.when(other.getName()).thenReturn("NoManagerHeldProjectOther"); //$NON-NLS-1$
        AtomicInteger ran = new AtomicInteger();
        // No hold anywhere in this project (a hold of a similarly named project does not count):
        // the work runs exactly as it always did.
        StandaloneServerStateRecovery.holdStopped(other, APPLICATION_ID);
        try
        {
            StandaloneServerStateRecovery.withoutApplicationManagerThen(project, "Nothing was built.", //$NON-NLS-1$
                ran::incrementAndGet);
            assertEquals(1, ran.get());

            StandaloneServerStateRecovery.holdStopped(project, APPLICATION_ID);
            try
            {
                StandaloneServerStateRecovery.withoutApplicationManagerThen(project,
                    "Nothing was built.", ran::incrementAndGet); //$NON-NLS-1$
                throw new AssertionError("a held server of the project must not be started blind"); //$NON-NLS-1$
            }
            catch (ApplicationException refused)
            {
                String message = refused.getMessage();
                assertTrue(message, message.contains(APPLICATION_ID));
                assertTrue(message, message.contains("Nothing was built.")); //$NON-NLS-1$
                assertTrue(message, message.contains("standalone_server(action='start'")); //$NON-NLS-1$
            }
            assertEquals("nothing was built", 1, ran.get()); //$NON-NLS-1$
        }
        finally
        {
            StandaloneServerStateRecovery.releaseHold(project, APPLICATION_ID);
            StandaloneServerStateRecovery.releaseHold(other, APPLICATION_ID);
        }
    }

    @Test
    public void testTheStopSettleWaitStaysInsideTheStopBound()
    {
        ServerApp app = new ServerApp("SettleBoundStopProject", new FakeServer(1, launch(false))); //$NON-NLS-1$
        long begin = System.nanoTime();
        StandaloneServerStateRecovery.Recovery recovery = StandaloneServerStateRecovery.stopGuarded(
            app.project, app.application, APPLICATION_ID, app.manager, 2_000L,
            StandaloneServerStateRecovery.StopPolicy.RUNNING);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
        assertFalse(recovery.recovered());
        assertFalse(recovery.stopStillInFlight());
        assertTrue(recovery.detail(), recovery.detail().contains("did not settle")); //$NON-NLS-1$
        assertTrue("a 2s stop must not wait the 30s settle bound: " + elapsedMs, elapsedMs < 10_000L); //$NON-NLS-1$
        assertEquals(0, app.cleanups.get());
        assertNull(StandaloneServerStateRecovery.heldSince(app.project, APPLICATION_ID));
    }

    /** Runs {@code action} and requires the explicit-stop hold refusal. */
    private static void assertHeldRefusal(Runnable action)
    {
        try
        {
            action.run();
            throw new AssertionError("a held server must refuse a plugin-driven start"); //$NON-NLS-1$
        }
        catch (ApplicationException refused)
        {
            assertTrue(refused.getMessage(),
                refused.getMessage().contains("held stopped by standalone_server(action='stop')")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains("action='start'")); //$NON-NLS-1$
        }
    }

    /** A wst-server application over a fake server, with a mocked manager counting cleanups. */
    private static final class ServerApp
    {
        final IProject project = Mockito.mock(IProject.class);
        final IApplication application;
        final FakeServer server;
        final IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        final AtomicInteger cleanups = new AtomicInteger();

        ServerApp(String projectName, FakeServer server)
        {
            this.server = server;
            Mockito.when(project.getName()).thenReturn(projectName);
            IApplicationType type = Mockito.mock(IApplicationType.class);
            Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
            application = Mockito.mock(IApplication.class,
                Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
            Mockito.when(application.getType()).thenReturn(type);
            Mockito.when(application.getId()).thenReturn(APPLICATION_ID);
            Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
            Mockito.doAnswer(invocation -> {
                cleanups.incrementAndGet();
                server.setState(4);
                return null;
            }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
                Mockito.any(IProgressMonitor.class));
        }
    }

    /** A service that reports the start OK and leaves the fake server in a chosen state. */
    public static final class StateSettingService
    {
        private final FakeServer server;
        private final int state;

        StateSettingService(FakeServer server, int state)
        {
            this.server = server;
            this.state = state;
        }

        public IStatus startServer(Object passedServer, String launchMode, Object monitor)
        {
            server.setState(state);
            return Status.OK_STATUS;
        }
    }

    /** A service that takes a while, then reports OK and leaves the fake server in a chosen state. */
    public static final class SlowStateSettingService
    {
        private final FakeServer server;
        private final int state;
        private final long delayMs;

        SlowStateSettingService(FakeServer server, int state, long delayMs)
        {
            this.server = server;
            this.state = state;
            this.delayMs = delayMs;
        }

        public IStatus startServer(Object passedServer, String launchMode, Object monitor)
        {
            try
            {
                Thread.sleep(delayMs);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            server.setState(state);
            return Status.OK_STATUS;
        }
    }

    /** A standalone-server service whose start brings the fake server to STARTED. */
    public static final class StartingService
    {
        private final FakeServer server;

        StartingService(FakeServer server)
        {
            this.server = server;
        }

        public IStatus startServer(Object passedServer, String launchMode, Object monitor)
        {
            server.setState(2);
            return Status.OK_STATUS;
        }
    }

    /** A WST server as the pre-flight addresses it: by the two public accessors it reads. */
    public static final class FakeServer
    {
        private volatile int state;
        private final Object launch;

        FakeServer(int state, Object launch)
        {
            this.state = state;
            this.launch = launch;
        }

        public int getServerState()
        {
            return state;
        }

        void setState(int state)
        {
            this.state = state;
        }

        public Object getLaunch()
        {
            return launch;
        }
    }

    /** The reflective server accessor exposed by EDT's standalone-server application. */
    public interface ServerBackedApplication
    {
        Object getServer();
    }

    /** A restoration service that exposes the port conflict captured by its guarded window. */
    public static final class PortConflictingRestorationService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            LaunchUpdateDialogAutoConfirmer.recordPortConflictForTest(
                "8429 - HTTP gate port"); //$NON-NLS-1$
            return new Status(IStatus.ERROR, PLUGIN,
                "starting it did not finish within 60s"); //$NON-NLS-1$
        }
    }

    /** A conclusive restoration failure used to verify immediate composite-guard cleanup. */
    public static final class FailingRestorationService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            return new Status(IStatus.ERROR, PLUGIN, "start failed"); //$NON-NLS-1$
        }
    }

    /** A restoration start that ignores cancellation until the test releases it. */
    public static final class BlockingRestorationService
    {
        private final CountDownLatch started;
        private final CountDownLatch release;

        BlockingRestorationService(CountDownLatch started, CountDownLatch release)
        {
            this.started = started;
            this.release = release;
        }

        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            started.countDown();
            try
            {
                release.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return Status.OK_STATUS;
        }
    }

    private static AtomicInteger authInFlightCounter() throws Exception
    {
        Field field = InfobaseAuthDialogSuppressor.class.getDeclaredField("IN_FLIGHT"); //$NON-NLS-1$
        field.setAccessible(true);
        return (AtomicInteger)field.get(null);
    }

    private static int conflictWatchCount() throws Exception
    {
        Field lockField = LaunchUpdateDialogAutoConfirmer.class.getDeclaredField("LOCK"); //$NON-NLS-1$
        lockField.setAccessible(true);
        Object lock = lockField.get(null);
        Field watchesField = LaunchUpdateDialogAutoConfirmer.class.getDeclaredField(
            "CONFLICT_WATCHES"); //$NON-NLS-1$
        watchesField.setAccessible(true);
        synchronized (lock)
        {
            return ((java.util.List<?>)watchesField.get(null)).size();
        }
    }

    /**
     * An {@link ILaunch} that only answers {@code isTerminated()} — the one thing the pre-flight
     * asks of it. A dynamic proxy rather than a stub subclass: the interface carries two dozen
     * methods none of which this decision touches.
     *
     * @param terminated what the launch reports
     * @return the launch
     */
    private static ILaunch launch(boolean terminated)
    {
        return (ILaunch)Proxy.newProxyInstance(ILaunch.class.getClassLoader(),
            new Class<?>[] { ILaunch.class }, (proxy, method, args) -> {
                if ("isTerminated".equals(method.getName()))
                {
                    return Boolean.valueOf(terminated);
                }
                if ("hashCode".equals(method.getName()))
                {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(method.getName()))
                {
                    return Boolean.valueOf(proxy == args[0]);
                }
                if ("toString".equals(method.getName()))
                {
                    return "ILaunch(terminated=" + terminated + ")";
                }
                return null;
            });
    }
}
