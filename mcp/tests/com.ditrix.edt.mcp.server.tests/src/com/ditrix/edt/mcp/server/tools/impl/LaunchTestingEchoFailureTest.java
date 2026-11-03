/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Response enrichment cannot turn a dispatched launch into a retryable failure. */
public class LaunchTestingEchoFailureTest
{
    @Test
    public void testByNameSuccessSurvivesUnreadableInheritedPort() throws Exception
    {
        ILaunchConfiguration config = unreadablePortConfiguration();
        LaunchOverrides request = LaunchOverrides.of("RunTests", null, null, "TESTCLIENT", null);
        Method build = LaunchTool.class.getDeclaredMethod("buildLaunchSuccess", ILaunchConfiguration.class,
            String.class, boolean.class, String.class, String.class, LaunchOverrides.class,
            LaunchOverrides.Prepared.class, String.class);
        build.setAccessible(true);
        String result = (String)build.invoke(new LaunchTool(), config, LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID,
            false, "TestingProject", "TestingApplication", request, request.prepare(), "debug");
        assertSuccessfulEcho(result);
    }

    @Test
    public void testProjectApplicationSuccessSurvivesUnreadableInheritedPort() throws Exception
    {
        ILaunchConfiguration config = unreadablePortConfiguration();
        LaunchOverrides request = LaunchOverrides.of("RunTests", null, null, "TESTCLIENT", null);
        Method echo = LaunchTool.class.getDeclaredMethod("echoOverrides", ToolResult.class,
            LaunchOverrides.class, LaunchOverrides.Prepared.class, ILaunchConfiguration.class);
        echo.setAccessible(true);
        ToolResult result = (ToolResult)echo.invoke(null,
            ToolResult.success().put("status", "launching").put("mode", "debug"),
            request, request.prepare(), config);
        assertSuccessfulEcho(result.toJson());
    }

    private static ILaunchConfiguration unreadablePortConfiguration() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Testing client");
        when(config.getAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_PORT_NUMBER, 1538))
            .thenThrow(new CoreException(new Status(IStatus.ERROR, "testing",
                "Stored testing port has the wrong attribute type")));
        return config;
    }

    private static void assertSuccessfulEcho(String result)
    {
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(json.toString(), json.get("success").getAsBoolean());
        assertEquals("launching", json.get("status").getAsString());
        assertEquals("debug", json.get("mode").getAsString());
        assertEquals("TESTCLIENT", json.get("automatedTestingMode").getAsString());
        assertEquals("RunTests", json.get("startupOption").getAsString());
        assertFalse("An unreadable port must not be advertised as a known endpoint",
            json.has("automatedTestingPort"));
        assertFalse(json.has("error"));
    }
}
