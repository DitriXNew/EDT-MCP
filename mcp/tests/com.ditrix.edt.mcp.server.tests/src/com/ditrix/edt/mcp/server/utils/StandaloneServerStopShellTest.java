/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Shell;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ShellProbe;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ShellProbeOutcome;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.StartClaim;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecoveryTest.FakeServer;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecoveryTest.ServerBackedApplication;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;

/** Both stop paths must reach cleanup even when the UI never answers the shell probe. */
public class StandaloneServerStopShellTest
{
    private static final String APPLICATION_ID = "ServerApplication.Test";

    @Test(timeout = 10_000L)
    public void testBoundedStopContinuesWithoutShellAfterUiTimeout()
    {
        assertStop(false, true);
    }

    @Test(timeout = 10_000L)
    public void testEnclosingBoundStopContinuesWithoutShellAfterUiTimeout()
    {
        assertStop(true, true);
    }

    @Test(timeout = 10_000L)
    public void testBoundedStopAlsoWorksWhenUiAnswersWithoutShell()
    {
        assertStop(false, false);
    }

    @Test(timeout = 10_000L)
    public void testEnclosingBoundStopAlsoWorksWhenUiAnswersWithoutShell()
    {
        assertStop(true, false);
    }

    private static void assertStop(boolean withinBound, boolean hungUi)
    {
        LongFunction<ShellProbe> originalProbe = StandaloneServerStateRecovery.stopShellProbe;
        CountDownLatch answered = new CountDownLatch(hungUi ? 1 : 0);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("StopShell_" + withinBound + "_" + hungUi);
        FakeServer server = new FakeServer(2, null);
        IApplication application = Mockito.mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
        IApplicationType type = Mockito.mock(IApplicationType.class);
        Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
        Mockito.when(application.getType()).thenReturn(type);
        IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        Mockito.doAnswer(invocation -> {
            ExecutionContext context = invocation.getArgument(1);
            assertTrue("a shell timeout must still dispatch cleanup without a shell",
                context.getProperty(ExecutionContext.ACTIVE_SHELL_NAME).isEmpty());
            cleanups.incrementAndGet();
            server.setState(4);
            return null;
        }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
            Mockito.any(IProgressMonitor.class));
        StandaloneServerStateRecovery.stopShellProbe = timeoutMs -> {
            probes.incrementAndGet();
            assertTrue("the optional UI read must have a short positive budget",
                timeoutMs > 0 && timeoutMs <= 250L);
            long start = System.nanoTime();
            // The production wait, with the UI answer deliberately never delivered.
            ShellProbe probe = LaunchLifecycleUtils.awaitShellAnswer(answered, new Shell[1], timeoutMs);
            assertEquals(hungUi ? ShellProbeOutcome.TIMED_OUT : ShellProbeOutcome.NO_SHELL,
                probe.outcome());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("UI wait exceeded its budget: " + elapsedMs, elapsedMs <= timeoutMs + 1_000L);
            return probe;
        };
        try
        {
            long start = System.nanoTime();
            if (withinBound)
            {
                StandaloneServerStateRecovery.ensureStartableWithinBound(project, application,
                    server, APPLICATION_ID, manager, new NullProgressMonitor(), null, null);
            }
            else
            {
                StandaloneServerStateRecovery.ensureStartable(project, application,
                    APPLICATION_ID, manager);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals("the real stop path must use the bounded shell probe", 1, probes.get());
            assertEquals("UI timeout must not skip or duplicate cleanup", 1, cleanups.get());
            assertEquals(4, server.getServerState());
            assertTrue("hung UI must not hold recovery open: " + elapsedMs, elapsedMs < 3_000L);
            assertEquals("the UI remains unresponsive after the stop completes", hungUi ? 1L : 0L,
                answered.getCount());
            try (StartClaim claim = StandaloneServerStateRecovery.claimStartWithinBound(
                project, APPLICATION_ID, 0L, null))
            {
                assertNotNull("the completed stop must release recovery ownership", claim);
            }
        }
        finally
        {
            StandaloneServerStateRecovery.stopShellProbe = originalProbe;
        }
    }
}
