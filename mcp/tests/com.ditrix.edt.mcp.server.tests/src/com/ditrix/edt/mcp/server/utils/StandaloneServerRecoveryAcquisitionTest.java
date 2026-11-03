/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.RestorationStartOutcome;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.StartClaim;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecoveryTest.FakeServer;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecoveryTest.ServerBackedApplication;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;

/** Recovery must reuse launch acquisition semantics and preserve each failure's diagnosis. */
public class StandaloneServerRecoveryAcquisitionTest
{
    private static final String APPLICATION_ID = "ServerApplication.Test";

    @Test
    public void testInterruptedFreeGuardStillRestores()
    {
        assertInterruptedRestoration(project("InterruptedFreeRecovery"));
    }

    @Test
    public void testInterruptedReentrantGuardStillRestores()
    {
        IProject project = project("InterruptedReentrantRecovery");
        StandaloneServerStateRecovery.holdRecoveryLockForTest(project, APPLICATION_ID,
            () -> assertInterruptedRestoration(project));
        assertClaimAvailable(project);
    }

    private static void assertInterruptedRestoration(IProject project)
    {
        AtomicInteger restores = new AtomicInteger();
        try
        {
            Thread.currentThread().interrupt();
            restore(project, 10_000L, () -> {
                assertTrue("the guarded work must see the restored interrupt flag",
                    Thread.currentThread().isInterrupted());
                restores.incrementAndGet();
                return new RestorationStartOutcome(null, true);
            });
            assertEquals("a pending interrupt must not skip protected restoration", 1, restores.get());
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
        assertClaimAvailable(project);
    }

    @Test
    public void testInterruptedFreeGuardStillGrantsStartClaim()
    {
        assertInterruptedClaim(project("InterruptedFreeClaim"));
    }

    @Test
    public void testInterruptedReentrantGuardStillGrantsStartClaim()
    {
        IProject project = project("InterruptedReentrantClaim");
        StandaloneServerStateRecovery.holdRecoveryLockForTest(project, APPLICATION_ID,
            () -> assertInterruptedClaim(project));
        assertClaimAvailable(project);
    }

    private static void assertInterruptedClaim(IProject project)
    {
        try
        {
            Thread.currentThread().interrupt();
            assertClaimAvailable(project);
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void testRestorationInterruptedDuringAcquisitionNamesInterruption() throws Exception
    {
        assertInterruptedWait("InterruptedRestorationWait", project -> restore(project, 60_000L,
            () -> { throw new AssertionError("restoration must not run without the guard"); }));
    }

    @Test
    public void testStartClaimInterruptedDuringAcquisitionNamesInterruption() throws Exception
    {
        assertInterruptedWait("InterruptedClaimWait", project -> {
            try (StartClaim claim = StandaloneServerStateRecovery.claimStartWithinBound(
                project, APPLICATION_ID, 60_000L, null))
            {
                throw new AssertionError("an interrupted acquisition must not grant a claim");
            }
            catch (ApplicationException failure)
            {
                return failure.getMessage();
            }
        });
    }

    @Test
    public void testBoundedStopInterruptedDuringAcquisitionNamesInterruption() throws Exception
    {
        assertInterruptedWait("InterruptedBoundedStop", project -> stopFailure(project, false, null));
    }

    @Test
    public void testEnclosingBoundStopInterruptedDuringAcquisitionNamesInterruption() throws Exception
    {
        assertInterruptedWait("InterruptedEnclosingStop", project -> stopFailure(project, true, null));
    }

    @Test
    public void testCancelledEnclosingBoundStopNamesCancellation() throws Exception
    {
        NullProgressMonitor monitor = new NullProgressMonitor();
        assertFailedWait("CancelledStop", project -> stopFailure(project, true, monitor),
            () -> monitor.setCanceled(true));
    }

    private static String stopFailure(IProject project, boolean withinBound,
        NullProgressMonitor monitor)
    {
        FakeServer server = new FakeServer(2, null);
        IApplication application = Mockito.mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
        IApplicationType type = Mockito.mock(IApplicationType.class);
        Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
        Mockito.when(application.getType()).thenReturn(type);
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        try
        {
            if (withinBound)
            {
                StandaloneServerStateRecovery.ensureStartableWithinBound(project, application,
                    server, APPLICATION_ID, manager, monitor, null, null);
            }
            else
            {
                StandaloneServerStateRecovery.ensureStartable(project, application,
                    APPLICATION_ID, manager);
            }
            throw new AssertionError("a failed acquisition must refuse the stop");
        }
        catch (ApplicationException failure)
        {
            Mockito.verifyZeroInteractions(manager);
            return failure.getMessage();
        }
    }

    @Test
    public void testCancelledStartClaimNamesCancellation()
    {
        assertCancelledStartClaim();
    }

    private static void assertCancelledStartClaim()
    {
        IProject project = project("CancelledClaim");
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        try
        {
            StandaloneServerStateRecovery.claimStartWithinBound(project, APPLICATION_ID, 0L, monitor);
            fail("a cancelled claim must report its acquisition failure");
        }
        catch (ApplicationException failure)
        {
            assertTrue(failure.getMessage(), failure.getMessage().contains("was cancelled"));
            assertFalse(failure.getMessage().contains("deadline"));
            assertTrue(failure.getMessage().contains("Check debug_status before retrying launch"));
        }
        assertClaimAvailable(project);
    }

    @Test
    public void testCancelledStartClaimPreservesPendingInterrupt()
    {
        try
        {
            Thread.currentThread().interrupt();
            assertCancelledStartClaim();
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }

    @Test
    public void testContendedStartClaimNamesDeadline() throws Exception
    {
        try (HeldRecovery held = new HeldRecovery("ContendedClaim"))
        {
            try
            {
                StandaloneServerStateRecovery.claimStartWithinBound(
                    held.project, APPLICATION_ID, 25L, null);
                fail("a contended guard must not grant a claim");
            }
            catch (ApplicationException failure)
            {
                assertTrue(failure.getMessage(), failure.getMessage().contains(
                    "recovery guard did not become available before the deadline"));
                assertFalse(failure.getMessage().contains("interrupted"));
                assertFalse(failure.getMessage().contains("cancelled"));
            }
        }
    }

    private static void assertInterruptedWait(String name,
        Function<IProject, String> action) throws Exception
    {
        assertFailedWait(name, action, null);
    }

    private static void assertFailedWait(String name,
        Function<IProject, String> action, Runnable cancellation) throws Exception
    {
        try (HeldRecovery held = new HeldRecovery(name))
        {
            AtomicReference<String> message = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread waiter = new Thread(() -> {
                try
                {
                    message.set(action.apply(held.project));
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
                catch (Throwable e)
                {
                    failure.set(e);
                }
            }, "test: recovery acquisition waiter");
            waiter.setDaemon(true);
            waiter.start();
            try
            {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (waiter.isAlive() && waiter.getState() != Thread.State.TIMED_WAITING
                    && System.nanoTime() < deadline)
                {
                    Thread.sleep(1L);
                }
                assertEquals("cancel only after timed lock acquisition has begun",
                    Thread.State.TIMED_WAITING, waiter.getState());
                if (cancellation == null)
                {
                    waiter.interrupt();
                }
                else
                {
                    cancellation.run();
                }
                waiter.join(5_000L);
                assertFalse("cancellation must finish the acquisition promptly", waiter.isAlive());
                if (failure.get() != null)
                {
                    throw new AssertionError("recovery acquisition failed", failure.get());
                }
                assertEquals("the wait must preserve the interrupt flag",
                    cancellation == null, interrupted.get());
                assertNotNull(message.get());
                assertTrue(message.get(), message.get().contains(
                    cancellation == null ? "the wait for its recovery guard was interrupted"
                        : "acquiring its recovery guard was cancelled"));
                assertFalse(message.get(), message.get().contains("deadline"));
                assertFalse(message.get(), message.get().contains("That stop may still be running"));
            }
            finally
            {
                held.release.countDown();
                waiter.interrupt();
                waiter.join(5_000L);
            }
        }
    }

    private static String restore(IProject project, long timeoutMs,
        Supplier<RestorationStartOutcome> restarter)
    {
        StandaloneServerStateRecovery.beginOperation();
        try
        {
            StandaloneServerStateRecovery.recordStoppedServer(APPLICATION_ID);
            RestorationStartOutcome outcome = StandaloneServerStateRecovery.restoreIfStillUnowned(
                project, new FakeServer(4, null), APPLICATION_ID, timeoutMs, restarter);
            return StandaloneServerStateRecovery.appendRestorationOutcome(
                "operation failed.", "Standalone", applicationId -> outcome);
        }
        finally
        {
            StandaloneServerStateRecovery.endOperation();
        }
    }

    private static void assertClaimAvailable(IProject project)
    {
        try (StartClaim claim = StandaloneServerStateRecovery.claimStartWithinBound(
            project, APPLICATION_ID, 0L, null))
        {
            assertNotNull("every acquired hold and claim must be released", claim);
        }
    }

    private static IProject project(String name)
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn(name);
        return project;
    }

    private static final class HeldRecovery implements AutoCloseable
    {
        final IProject project;
        final CountDownLatch release = new CountDownLatch(1);
        private final Thread holder;

        HeldRecovery(String name) throws InterruptedException
        {
            project = project(name);
            CountDownLatch acquired = new CountDownLatch(1);
            holder = new Thread(() -> StandaloneServerStateRecovery.holdRecoveryLockForTest(
                project, APPLICATION_ID, () -> {
                    acquired.countDown();
                    try
                    {
                        release.await(15, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }), "test: held recovery guard");
            holder.setDaemon(true);
            holder.start();
            assertTrue("the contender must really hold the recovery guard",
                acquired.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws InterruptedException
        {
            release.countDown();
            holder.join(5_000L);
            assertFalse(holder.isAlive());
            assertClaimAvailable(project);
        }
    }
}
