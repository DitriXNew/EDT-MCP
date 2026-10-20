/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The on-demand guides must explain testing-mode reset and the unverified web-client path. */
public class AutomatedTestingGuideContractTest
{
    @Test
    public void testCreateGuideExplainsSwitchingAndRecreationToClearMode()
    {
        String guide = new CreateLaunchConfigTool().getGuide();
        assertTrue(guide, guide.contains("switch the saved mode"));
        assertTrue(guide, guide.contains("cannot clear it"));
        assertTrue(guide, guide.contains("recreate the configuration without automated-testing parameters"));
    }

    @Test
    public void testCreateGuideUsesNeutralStartupCommand()
    {
        String guide = new CreateLaunchConfigTool().getGuide();
        assertTrue(guide, guide.contains("startupOption:\"RunTests\""));
        assertFalse(guide, guide.contains("E2E654"));
    }

    @Test
    public void testLaunchGuideUsesNeutralStartupCommand()
    {
        String guide = new LaunchTool().getGuide();
        assertTrue(guide, guide.contains("\"startupOption\":\"RunTests\""));
        assertFalse(guide, guide.contains("E2E654"));
    }

    @Test
    public void testCreateGuideExplainsUnverifiedWebClientTesting()
    {
        assertWebClientLimit(new CreateLaunchConfigTool().getGuide());
    }

    @Test
    public void testLaunchGuideExplainsUnverifiedWebClientTesting()
    {
        assertWebClientLimit(new LaunchTool().getGuide());
    }

    private static void assertWebClientLimit(String guide)
    {
        assertTrue(guide, guide.contains("Web-client test mode is unverified"));
        assertTrue(guide, guide.contains("no automated-testing web-client ID is stamped"));
    }
}
