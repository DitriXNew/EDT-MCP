/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

/**
 * Tests for the launch-mode predicate that decides whether a launch counts as an
 * active DEBUG session ({@link DebugSessionRegistry#isActiveDebugLaunch}).
 * <p>
 * A RUN-mode launch must be excluded (audit A13): it has no debug target and never
 * suspends, so auto-resolving a debug operation onto it could never succeed.
 * The launch-manager-walking methods around it call {@code DebugPlugin.getDefault()}
 * (a live workbench) and are covered E2E; the pure predicate is unit-tested here.
 */
public class DebugSessionRegistryTest
{
    @Test
    public void testActiveDebugLaunchIsTrueForRunningDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.DEBUG_MODE);
        assertTrue(DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForRunMode()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.RUN_MODE);
        assertFalse("a RUN-mode launch must not count as an active debug session", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForTerminatedDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(true);
        assertFalse("a terminated launch must not count as active", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForNull()
    {
        assertFalse(DebugSessionRegistry.isActiveDebugLaunch(null));
    }
}
