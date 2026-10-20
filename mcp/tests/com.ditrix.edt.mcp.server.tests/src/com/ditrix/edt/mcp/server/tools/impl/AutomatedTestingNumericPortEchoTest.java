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

/**
 * The protocol handler hands a JSON number to the tool as {@code Double.toString} of Gson's double
 * ({@code 65536} arrives as {@code "65536.0"}); the refusal must echo the integer the caller sent.
 */
@RunWith(Parameterized.class)
public class AutomatedTestingNumericPortEchoTest
{
    @Parameters(name = "port={0}")
    public static Object[][] ports()
    {
        return new Object[][]{{Double.toString(0), "0"}, {Double.toString(-1), "-1"}, {Double.toString(65536), "65536"},
            {Double.toString(2147483648L), "2147483648"}};
    }

    private final String port;
    private final String echoed;

    public AutomatedTestingNumericPortEchoTest(String port, String echoed)
    {
        this.port = port;
        this.echoed = echoed;
    }

    @Test
    public void testLaunchEchoesTheNumberAsSent()
    {
        String result = new LaunchTool().execute(Map.of(
            "launchConfigurationName", "Missing testing configuration",
            "restartIfRunning", "true",
            "automatedTestingMode", "TESTCLIENT",
            "automatedTestingPort", port));
        errorMessage(result, "Invalid automatedTestingPort: '" + echoed + "'.", "Use an integer from 1 to 65535");
    }

    @Test
    public void testCreateEchoesTheNumberAsSent()
    {
        String result = new CreateLaunchConfigTool().execute(Map.of(
            "projectName", "Missing testing project",
            "automatedTestingMode", "TESTCLIENT",
            "automatedTestingPort", port));
        errorMessage(result, "Invalid automatedTestingPort: '" + echoed + "'.", "Use an integer from 1 to 65535");
    }
}
