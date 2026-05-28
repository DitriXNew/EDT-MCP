/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Mock-driven tests for {@link LaunchLifecycleUtils#prepareForFreshLaunch}.
 *
 * <p>Covers the auto-chain's behaviour against live launches whose
 * {@code applicationId} does not match the one being prepared — specifically
 * stale Attach launches, which carry a synthetic {@code "attach:<name>"} id
 * that can never equal a runtime client's real application UUID. Before the
 * fix, such launches were silently skipped, leaving the system in a state
 * where the next {@code workingCopy.launch()} would hang on a locked IB.
 */
public class LaunchLifecycleUtilsPrepareTest
{
    private static final String PROJECT_NAME = "MyProject";
    private static final String RUNTIME_APP_ID = "real-app-uuid";

    private static IProject mockOpenProject()
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn(PROJECT_NAME);
        return project;
    }

    /**
     * Wires the given mock launch so {@code isTerminated()} flips to true the
     * moment {@code terminate()} is invoked — required for
     * {@link LaunchLifecycleUtils#terminateAndWait} to observe success without
     * spinning until the polling timeout.
     */
    private static AtomicBoolean wireSelfTerminating(ILaunch launch) throws Exception
    {
        AtomicBoolean terminated = new AtomicBoolean(false);
        when(launch.isTerminated()).thenAnswer(inv -> terminated.get());
        doAnswer(inv -> {
            terminated.set(true);
            return null;
        }).when(launch).terminate();
        return terminated;
    }

    private static ILaunchConfiguration mockAttachConfig(String name, String projectName)
            throws Exception
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(LaunchConfigUtils.TYPE_LOCAL_RUNTIME);

        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getType()).thenReturn(type);
        when(cfg.getName()).thenReturn(name);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), anyString()))
            .thenReturn(projectName);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
            .thenReturn(null);
        return cfg;
    }

    private static ILaunchConfiguration mockRuntimeConfig(String name, String projectName,
            String applicationId) throws Exception
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);

        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getType()).thenReturn(type);
        when(cfg.getName()).thenReturn(name);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), anyString()))
            .thenReturn(projectName);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
            .thenReturn(applicationId);
        return cfg;
    }

    /**
     * Stubs {@code appManager} so that {@link LaunchLifecycleUtils#updateApplicationIfNeeded}
     * short-circuits as a no-op (state already UPDATED). Lets the test focus on
     * the terminate phase without dragging in the update mock surface.
     */
    private static IApplicationManager mockUpToDateAppManager() throws Exception
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(RUNTIME_APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.UPDATED);
        return mgr;
    }

    @Test
    public void testStaleAttachOnSameProjectIsSwept() throws Exception
    {
        // The user's scenario: a previous debug session left an attach launch
        // hanging around. Its applicationId is the synthetic "attach:<name>",
        // which never equals the runtime client's real UUID. Before the fix
        // this launch was silently skipped — and the next workingCopy.launch()
        // would block on the locked IB. After the fix it must be disconnected.
        ILaunchConfiguration attachCfg = mockAttachConfig(
            "Процесс отладки 1С", PROJECT_NAME);
        ILaunch attachLaunch = mock(ILaunch.class);
        when(attachLaunch.getLaunchConfiguration()).thenReturn(attachCfg);
        wireSelfTerminating(attachLaunch);

        ILaunchManager launchManager = mock(ILaunchManager.class);
        when(launchManager.getLaunches()).thenReturn(new ILaunch[] { attachLaunch });

        PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
            launchManager, mockOpenProject(), RUNTIME_APP_ID, mockUpToDateAppManager(), 2);

        assertTrue("auto-chain must succeed: " + result.getError(), result.isOk());
        assertEquals("stale attach must be counted as swept",
            1, result.getTerminatedCount());
        verify(attachLaunch, atLeastOnce()).terminate();
    }

    @Test
    public void testOwnedAttachIsProtected() throws Exception
    {
        // If the lingering attach launch is owned by another MCP tool
        // (registered via registerOwnedLaunch), the auto-chain must refuse to
        // disconnect it — symmetric with the existing protection for owned
        // runtime launches — so concurrent MCP calls don't sabotage each other.
        ILaunchConfiguration attachCfg = mockAttachConfig(
            "Owned Debug Session", PROJECT_NAME);
        ILaunch attachLaunch = mock(ILaunch.class);
        when(attachLaunch.getLaunchConfiguration()).thenReturn(attachCfg);
        // Belt-and-suspenders: even though the implementation must check
        // OWNED_LAUNCHES *before* terminateAndWait (and verify(never).terminate
        // below proves we did), wire self-termination so a future refactor that
        // reorders the checks fails loudly with verify() instead of hanging the
        // suite for `timeoutSeconds` per iteration.
        wireSelfTerminating(attachLaunch);

        ILaunchManager launchManager = mock(ILaunchManager.class);
        when(launchManager.getLaunches()).thenReturn(new ILaunch[] { attachLaunch });

        try
        {
            LaunchLifecycleUtils.registerOwnedLaunch(attachLaunch);

            PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
                launchManager, mockOpenProject(), RUNTIME_APP_ID,
                mockUpToDateAppManager(), 2);

            assertFalse("owned attach must block the chain", result.isOk());
            assertTrue("error must mention the owning launch by name",
                result.getError() != null
                    && result.getError().contains("Owned Debug Session"));
            verify(attachLaunch, never()).terminate();
        }
        finally
        {
            LaunchLifecycleUtils.unregisterOwnedLaunch(attachLaunch);
        }
    }

    @Test
    public void testRuntimeLaunchWithMatchingAppIdStillTerminated() throws Exception
    {
        // Guard against regressions in the original per-applicationId terminate
        // pass: a live runtime launch on the same (project, applicationId) must
        // still be terminated even after we add the attach-sweep second pass.
        ILaunchConfiguration runtimeCfg = mockRuntimeConfig(
            "MyApp.RuntimeClient", PROJECT_NAME, RUNTIME_APP_ID);
        ILaunch runtimeLaunch = mock(ILaunch.class);
        when(runtimeLaunch.getLaunchConfiguration()).thenReturn(runtimeCfg);
        wireSelfTerminating(runtimeLaunch);

        ILaunchManager launchManager = mock(ILaunchManager.class);
        when(launchManager.getLaunches()).thenReturn(new ILaunch[] { runtimeLaunch });

        PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
            launchManager, mockOpenProject(), RUNTIME_APP_ID, mockUpToDateAppManager(), 2);

        assertTrue("auto-chain must succeed: " + result.getError(), result.isOk());
        assertEquals(1, result.getTerminatedCount());
        verify(runtimeLaunch, atLeastOnce()).terminate();
    }

    @Test
    public void testRuntimePlusAttachSweepCountedTogether() throws Exception
    {
        // Both passes should fire and the counter should reflect both — useful
        // signal in the pre-launch summary that something non-trivial was
        // cleaned up before the new spawn.
        ILaunchConfiguration runtimeCfg = mockRuntimeConfig(
            "MyApp.RuntimeClient", PROJECT_NAME, RUNTIME_APP_ID);
        ILaunch runtimeLaunch = mock(ILaunch.class);
        when(runtimeLaunch.getLaunchConfiguration()).thenReturn(runtimeCfg);
        wireSelfTerminating(runtimeLaunch);

        ILaunchConfiguration attachCfg = mockAttachConfig(
            "Process Debug 1С", PROJECT_NAME);
        ILaunch attachLaunch = mock(ILaunch.class);
        when(attachLaunch.getLaunchConfiguration()).thenReturn(attachCfg);
        wireSelfTerminating(attachLaunch);

        ILaunchManager launchManager = mock(ILaunchManager.class);
        when(launchManager.getLaunches())
            .thenReturn(new ILaunch[] { runtimeLaunch, attachLaunch });

        PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
            launchManager, mockOpenProject(), RUNTIME_APP_ID, mockUpToDateAppManager(), 2);

        assertTrue("auto-chain must succeed: " + result.getError(), result.isOk());
        assertEquals("both runtime and attach must be counted",
            2, result.getTerminatedCount());
        verify(runtimeLaunch, atLeastOnce()).terminate();
        verify(attachLaunch, atLeastOnce()).terminate();
    }
}
