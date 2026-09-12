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

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;
import org.mockito.Mockito;

import com.e1c.g5.dt.applications.ApplicationException;

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
    {
        boolean[] armed = new boolean[1];
        StringBuilder order = new StringBuilder();

        String result = StandaloneServerStateRecovery.guardedRestorationStart(() -> {
            assertFalse(armed[0]);
            armed[0] = true;
            order.append('A');
        }, () -> {
            assertTrue("the confirmer must be armed while EDT starts the server", armed[0]);
            order.append('S');
            return "start failed"; //$NON-NLS-1$
        }, () -> {
            assertTrue("failure capture remains inside the armed window", armed[0]);
            order.append('C');
            return null;
        }, () -> {
            assertTrue(armed[0]);
            armed[0] = false;
            order.append('D');
        });

        assertEquals("start failed", result); //$NON-NLS-1$
        assertFalse("the confirmer must be disarmed after the start", armed[0]);
        assertEquals("ASCD", order.toString()); //$NON-NLS-1$
    }

    @Test
    public void testInconclusiveRestorationKeepsThePortConfirmerArmedUntilJobCompletion()
        throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try
            {
                answer.set(StandaloneServerStateRecovery.guardedRestorationStart(() -> {
                    // Headless stand-in for arming the targeted confirmer.
                }, completion -> {
                    BoundedJob.Result bounded = BoundedJob.run("test: blocked restoration", 100L, //$NON-NLS-1$
                        monitor -> {
                            started.countDown();
                            release.await(30, TimeUnit.SECONDS);
                        }, completion);
                    return new StandaloneServerStateRecovery.RestorationStartOutcome(
                        StandaloneServerSupport.startFailureReason(bounded),
                        !BoundedJob.isInconclusive(bounded.getOutcome()));
                }, () -> null, () -> {
                    cleanups.incrementAndGet();
                    cleaned.countDown();
                }, 5_000L));
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
            assertTrue(answer.get().contains("may still be running")); //$NON-NLS-1$
            assertEquals("the old eager-disarm shape must be absent while the start is in flight", //$NON-NLS-1$
                0, cleanups.get());

            release.countDown();
            assertTrue("actual Job completion must release the deferred confirmer", //$NON-NLS-1$
                cleaned.await(5, TimeUnit.SECONDS));
            assertEquals("completion must release the confirmer exactly once", 1, cleanups.get()); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            caller.join(5_000L);
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

    /** A WST server as the pre-flight addresses it: by the two public accessors it reads. */
    public static final class FakeServer
    {
        private final int state;
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

        public Object getLaunch()
        {
            return launch;
        }
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
