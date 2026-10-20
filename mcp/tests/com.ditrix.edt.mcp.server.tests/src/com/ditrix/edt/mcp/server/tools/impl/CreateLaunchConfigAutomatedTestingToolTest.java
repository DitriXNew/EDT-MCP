/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.MODE_ATTRIBUTE;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.PORT_ATTRIBUTE;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.assertTestingSchema;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.errorMessage;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.overrides;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchOverrides;

/** Validation is shared with launch; new configurations persist native, typed testing attributes. */
public class CreateLaunchConfigAutomatedTestingToolTest
{
    @Test
    public void testInputSchemaDeclaresNativeModeEnumAndIntegerPort()
    {
        assertTestingSchema(new CreateLaunchConfigTool().getInputSchema());
    }

    @Test
    public void testPopulateWorkingCopyStampsModeAndIntegerPortBeforeSave() throws Exception
    {
        assertPopulated("TESTCLIENT", 1540, "TESTCLIENT");
    }

    @Test
    public void testNewClientWithoutPortReliesOnEdtDefault() throws Exception
    {
        assertPopulated("TESTCLIENT", null, "TESTCLIENT");
    }

    @Test
    public void testNewManagerStampsModeWithoutPort() throws Exception
    {
        assertPopulated("TESTMANAGER", null, "TESTMANAGER");
    }

    @Test
    public void testNewConfigurationNormalizesLowercaseMode() throws Exception
    {
        assertPopulated("testclient", 1540, "TESTCLIENT");
    }

    @Test
    public void testUnknownModeIsRejectedBeforeProjectLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingMode", "UNKNOWN");
        errorMessage(new CreateLaunchConfigTool().execute(params), "automatedTestingMode", "UNKNOWN",
            "TESTCLIENT", "TESTMANAGER");
    }

    @Test
    public void testPortWithoutModeIsRejectedBeforeProjectLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingPort", "1540");
        errorMessage(new CreateLaunchConfigTool().execute(params), "automatedTestingPort", "TESTCLIENT", "omit");
    }

    @Test
    public void testManagerPortIsRejectedBeforeProjectLookup()
    {
        Map<String, String> params = params();
        params.put("automatedTestingMode", "TESTMANAGER");
        params.put("automatedTestingPort", "1540");
        errorMessage(new CreateLaunchConfigTool().execute(params), "automatedTestingPort", "TESTCLIENT", "omit");
    }

    @Test
    public void testInvalidPortsAreRejectedBeforeProjectLookup()
    {
        for (String port : new String[]{"0", "-1", "65536", "not-a-port", "1.5", "2147483648", ""})
        {
            Map<String, String> params = params();
            params.put("automatedTestingMode", "TESTCLIENT");
            params.put("automatedTestingPort", port);
            errorMessage(new CreateLaunchConfigTool().execute(params), "automatedTestingPort", "1 to 65535");
        }
    }

    private static Map<String, String> params()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject654");
        return params;
    }

    private static void assertPopulated(String mode, Integer port, String expectedMode) throws Exception
    {
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        IProject project = mock(IProject.class);
        LaunchOverrides.Prepared prepared = overrides(null, mode, port).prepare();
        assertNull(prepared.errorJson);
        Method populate;
        try
        {
            populate = CreateLaunchConfigTool.class.getDeclaredMethod("populateWorkingCopy",
                ILaunchConfigurationWorkingCopy.class, IProject.class, String.class, String.class,
                String.class, LaunchOverrides.Prepared.class);
        }
        catch (NoSuchMethodException e)
        {
            // Run the original population seam on the base; the missing attributes must fail below.
            populate = CreateLaunchConfigTool.class.getDeclaredMethod("populateWorkingCopy",
                ILaunchConfigurationWorkingCopy.class, IProject.class, String.class, String.class, String.class);
            populate.setAccessible(true);
            populate.invoke(null, copy, project, "Project654", "App654", CreateLaunchConfigTool.COMPONENT_TYPE_THIN);
            verify(copy).setAttribute(MODE_ATTRIBUTE, expectedMode);
            return;
        }
        populate.setAccessible(true);
        populate.invoke(null, copy, project, "Project654", "App654", CreateLaunchConfigTool.COMPONENT_TYPE_THIN,
            prepared);
        verify(copy).setAttribute(MODE_ATTRIBUTE, expectedMode);
        if (port == null)
        {
            verify(copy, never()).setAttribute(eq(PORT_ATTRIBUTE), anyInt());
        }
        else
        {
            verify(copy).setAttribute(PORT_ATTRIBUTE, port.intValue());
        }
        verify(copy, never()).doSave();
    }
}
