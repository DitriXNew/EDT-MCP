/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.errorMessage;

import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** Malformed ports must identify the caller's value before either tool resolves its target. */
@RunWith(Parameterized.class)
public class AutomatedTestingPortDiagnosticTest
{
    @Parameters(name = "port={0}")
    public static Object[] ports()
    {
        return new Object[]{"abc", "1.5", "2147483648", "", "true", "null", "NaN"};
    }

    private final String port;

    public AutomatedTestingPortDiagnosticTest(String port)
    {
        this.port = port;
    }

    @Test
    public void testLaunchNamesTheMalformedPort()
    {
        String result = new LaunchTool().execute(Map.of(
            "launchConfigurationName", "Missing testing configuration",
            "restartIfRunning", "true",
            "automatedTestingMode", "TESTCLIENT",
            "automatedTestingPort", port));
        assertMalformedPort(result);
    }

    @Test
    public void testCreateNamesTheMalformedPort()
    {
        String result = new CreateLaunchConfigTool().execute(Map.of(
            "projectName", "Missing testing project",
            "automatedTestingMode", "TESTCLIENT",
            "automatedTestingPort", port));
        assertMalformedPort(result);
    }

    private void assertMalformedPort(String result)
    {
        errorMessage(result, "Invalid automatedTestingPort: '" + port + "'.",
            "Use an integer from 1 to 65535", "automatedTestingMode=TESTCLIENT");
    }
}
