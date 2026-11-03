/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.PORT_ATTRIBUTE;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.assertTestingSchema;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.errorMessage;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.overrides;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Schema, early refusals and the effective endpoint reported to automated-testing clients. */
public class LaunchToolAutomatedTestingTest
{
    @Test
    public void testInputSchemaDeclaresNativeModeEnumAndIntegerPort()
    {
        assertTestingSchema(new LaunchTool().getInputSchema());
    }

    @Test
    public void testOutputSchemaDeclaresNativeModeEnumAndIntegerPort()
    {
        assertTestingSchema(new LaunchTool().getOutputSchema());
    }

    @Test
    public void testStandaloneRefusalNamesTestingMode() throws Exception
    {
        errorMessage(LaunchTool.standaloneOverridesRefusal("Standalone654",
            overrides(null, "TESTCLIENT", null)), "automatedTestingMode", "Standalone654",
            "standalone server starts no client", "runtime-client configuration");
    }

    @Test
    public void testStandaloneRefusalNamesTestingPort() throws Exception
    {
        errorMessage(LaunchTool.standaloneOverridesRefusal("Standalone654",
            overrides(null, "TESTCLIENT", 1540)), "automatedTestingMode", "automatedTestingPort",
            "Standalone654", "runtime-client configuration");
    }

    @Test
    public void testUnknownModeIsRejectedBeforeRestartOrConfigurationLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingMode", "UNKNOWN");
        errorMessage(new LaunchTool().execute(params), "automatedTestingMode", "UNKNOWN",
            "TESTCLIENT", "TESTMANAGER");
    }

    @Test
    public void testPortWithoutModeIsRejectedBeforeRestartOrConfigurationLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingPort", "1540");
        errorMessage(new LaunchTool().execute(params), "automatedTestingPort", "TESTCLIENT", "omit");
    }

    @Test
    public void testManagerPortIsRejectedBeforeRestartOrConfigurationLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingMode", "TESTMANAGER");
        params.put("automatedTestingPort", "1540");
        errorMessage(new LaunchTool().execute(params), "automatedTestingPort", "TESTCLIENT", "omit");
    }

    @Test
    public void testInvalidPortsCannotBecomeAnOmittedPort()
    {
        for (String port : new String[]{"0", "-1", "65536", "not-a-port", "1.5", "2147483648", ""})
        {
            Map<String, String> params = params();
            params.put("automatedTestingMode", "TESTCLIENT");
            params.put("automatedTestingPort", port);
            errorMessage(new LaunchTool().execute(params), "automatedTestingPort", "1 to 65535");
        }
    }

    @Test
    public void testClientEchoIncludesDefault1538() throws Exception
    {
        JsonObject result = echo("TESTCLIENT", null, 1538);
        assertEquals("TESTCLIENT", result.get("automatedTestingMode").getAsString());
        assertEquals(1538, result.get("automatedTestingPort").getAsInt());
    }

    @Test
    public void testClientEchoIncludesExplicitPort() throws Exception
    {
        JsonObject result = echo("testclient", 1540, 1550);
        assertEquals("TESTCLIENT", result.get("automatedTestingMode").getAsString());
        assertEquals(1540, result.get("automatedTestingPort").getAsInt());
    }

    @Test
    public void testOmittedPortEchoUsesInheritedConfigurationPort() throws Exception
    {
        JsonObject result = echo("TESTCLIENT", null, 1550);
        assertEquals("TESTCLIENT", result.get("automatedTestingMode").getAsString());
        assertEquals(1550, result.get("automatedTestingPort").getAsInt());
    }

    @Test
    public void testManagerEchoHasNoPort() throws Exception
    {
        JsonObject result = echo("TESTMANAGER", null, 1550);
        assertEquals("TESTMANAGER", result.get("automatedTestingMode").getAsString());
        assertFalse(result.has("automatedTestingPort"));
    }

    private static Map<String, String> params()
    {
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "NoSuchConfiguration654");
        params.put("restartIfRunning", "true");
        return params;
    }

    private static JsonObject echo(String mode, Integer port, int storedPort) throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Client654");
        when(config.getAttribute(PORT_ATTRIBUTE, 1538)).thenReturn(storedPort);
        LaunchOverrides request = overrides(null, mode, port);
        Method method = LaunchTool.class.getDeclaredMethod("buildLaunchSuccess", ILaunchConfiguration.class,
            String.class, boolean.class, String.class, String.class, LaunchOverrides.class,
            LaunchOverrides.Prepared.class, String.class);
        method.setAccessible(true);
        String json = (String)method.invoke(new LaunchTool(), config, LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID,
            false, "Project654", "App654", request, request.prepare(), "debug");
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
