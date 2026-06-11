/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless tests for the pure-string portions of {@link LaunchConfigUtils} — the
 * synthetic application-id contract that lets {@code debug_status} address debug
 * sessions started from the EDT UI (not only via MCP). The config-reading paths
 * need a live Eclipse {@code ILaunchConfiguration} and are covered E2E.
 */
public class LaunchConfigUtilsSyntheticIdTest
{
    @Test
    public void testAttachPrefixValue()
    {
        assertEquals("attach:", LaunchConfigUtils.ATTACH_APP_ID_PREFIX); //$NON-NLS-1$
    }

    @Test
    public void testLaunchPrefixValue()
    {
        assertEquals("launch:", LaunchConfigUtils.LAUNCH_APP_ID_PREFIX); //$NON-NLS-1$
    }

    @Test
    public void testSyntheticAttachId()
    {
        assertTrue(LaunchConfigUtils.isSyntheticApplicationId("attach:My Attach Config")); //$NON-NLS-1$
    }

    @Test
    public void testSyntheticLaunchId()
    {
        assertTrue(LaunchConfigUtils.isSyntheticApplicationId("launch:Sample Thin Client")); //$NON-NLS-1$
    }

    @Test
    public void testSyntheticServerApplicationId()
    {
        // ServerApplication.<app> is minted by DebugServerTargetSupport for 1C debug-server
        // targets; per the contract it cannot be resolved through IApplicationManager, so the
        // single classification authority must know this prefix too (the DB-update preflight
        // must skip it instead of failing with 'Application not found').
        assertTrue(LaunchConfigUtils.isSyntheticApplicationId(
            DebugServerTargetSupport.SERVER_APP_ID_PREFIX + "SampleConfiguration")); //$NON-NLS-1$
        assertTrue(LaunchConfigUtils.isSyntheticApplicationId("ServerApplication.App")); //$NON-NLS-1$
        // A name merely CONTAINING a prefix token is not synthetic.
        assertFalse(LaunchConfigUtils.isSyntheticApplicationId("my-launch:thing")); //$NON-NLS-1$
    }

    @Test
    public void testRealApplicationIdIsNotSynthetic()
    {
        // A real ATTR_APPLICATION_ID looks like a UUID/opaque token — not synthetic.
        assertFalse(LaunchConfigUtils.isSyntheticApplicationId("3f6c0b1e-9d28-49db-9273-2903d2ab859a")); //$NON-NLS-1$
    }

    @Test
    public void testNullIsNotSynthetic()
    {
        assertFalse(LaunchConfigUtils.isSyntheticApplicationId(null));
    }

    @Test
    public void testEmptyIsNotSynthetic()
    {
        assertFalse(LaunchConfigUtils.isSyntheticApplicationId("")); //$NON-NLS-1$
    }
}
