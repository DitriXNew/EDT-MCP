package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Both response paths describe the configuration dispatched, without claiming socket readiness. */
@RunWith(Parameterized.class)
public class LaunchStoredTestingModeTest
{
    @Parameters(name = "{0}")
    public static Collection<Object[]> paths()
    {
        return Arrays.asList(new Object[][]{{"byName"}, {"projectApplication"}});
    }

    private final String path;

    public LaunchStoredTestingModeTest(String path)
    {
        this.path = path;
    }

    @Test
    public void storedClientReportsSavedPort() throws Exception
    {
        assertSettings(response("TESTCLIENT", 1550, null, null), "TESTCLIENT", 1550);
    }

    @Test
    public void storedClientWithoutPortReportsDefault() throws Exception
    {
        assertSettings(response("TESTCLIENT", null, null, null), "TESTCLIENT", 1538);
    }

    @Test
    public void overrideWinsOverStoredModeAndPort() throws Exception
    {
        assertSettings(response("TESTMANAGER", 1550, "TESTCLIENT", 1540), "TESTCLIENT", 1540);
    }

    @Test
    public void managerOverrideSuppressesStoredClientPort() throws Exception
    {
        assertSettings(response("TESTCLIENT", 1550, "TESTMANAGER", null), "TESTMANAGER", null);
    }

    @Test
    public void storedManagerHasNoPort() throws Exception
    {
        assertSettings(response("TESTMANAGER", 1550, null, null), "TESTMANAGER", null);
    }

    @Test
    public void noModeOmitsBothFieldsEvenWithSavedPort() throws Exception
    {
        JsonObject result = response(null, 1550, null, null);
        assertFalse(result.toString(), result.has("automatedTestingMode"));
        assertFalse(result.toString(), result.has("automatedTestingPort"));
    }

    private JsonObject response(String storedMode, Integer storedPort, String mode, Integer port)
        throws Exception
    {
        Map<String, Object> saved = new HashMap<>();
        if (storedMode != null)
        {
            saved.put(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_MODE, storedMode);
        }
        if (storedPort != null)
        {
            saved.put(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_PORT_NUMBER, storedPort);
        }
        Map<String, Object> attributes = new HashMap<>(saved);
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        when(copy.getName()).thenReturn("Testing client");
        when(copy.getAttribute(anyString(), anyString())).thenAnswer(call ->
            attributes.getOrDefault(call.getArgument(0), call.getArgument(1)));
        when(copy.getAttribute(anyString(), anyInt())).thenAnswer(call ->
            attributes.getOrDefault(call.getArgument(0), call.getArgument(1)));
        doAnswer(call -> {
            attributes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(copy).setAttribute(anyString(), anyString());
        doAnswer(call -> {
            attributes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(copy).setAttribute(anyString(), anyInt());
        when(copy.getWorkingCopy()).thenReturn(copy);
        LaunchOverrides request = LaunchOverrides.of(null, null, null, mode, port);
        LaunchOverrides.Prepared prepared = request.prepare();
        assertNull(prepared.errorJson);
        LaunchOverrides.Applied applied = prepared.applyTo(copy, false);
        assertNull(applied.errorJson);
        String json;
        if ("byName".equals(path))
        {
            Method build = LaunchTool.class.getDeclaredMethod("buildLaunchSuccess", ILaunchConfiguration.class,
                String.class, boolean.class, String.class, String.class, LaunchOverrides.class,
                LaunchOverrides.Prepared.class, String.class);
            build.setAccessible(true);
            json = (String)build.invoke(new LaunchTool(), applied.config, LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID,
                false, "TestingProject", "TestingApplication", request, prepared, "debug");
        }
        else
        {
            Method echo = LaunchTool.class.getDeclaredMethod("echoOverrides", ToolResult.class,
                LaunchOverrides.class, LaunchOverrides.Prepared.class, ILaunchConfiguration.class);
            echo.setAccessible(true);
            json = ((ToolResult)echo.invoke(null, ToolResult.success().put("status", "launching"),
                request, prepared, applied.config)).toJson();
        }
        verify(copy, never()).doSave();
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(result.toString(), true, result.get("success").getAsBoolean());
        assertEquals("launching", result.get("status").getAsString());
        return result;
    }

    private static void assertSettings(JsonObject result, String mode, Integer port)
    {
        assertEquals(result.toString(), mode, result.has("automatedTestingMode")
            ? result.get("automatedTestingMode").getAsString() : null);
        if (port == null)
        {
            assertFalse(result.toString(), result.has("automatedTestingPort"));
        }
        else
        {
            assertEquals(result.toString(), port, result.has("automatedTestingPort")
                ? Integer.valueOf(result.get("automatedTestingPort").getAsInt()) : null);
        }
    }
}
