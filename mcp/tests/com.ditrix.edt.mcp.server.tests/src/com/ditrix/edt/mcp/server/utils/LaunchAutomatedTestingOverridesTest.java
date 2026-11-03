/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.MODE_ATTRIBUTE;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.PORT_ATTRIBUTE;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.errorMessage;
import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.overrides;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

/** Native testing mode/port must reach an unsaved working copy, never the saved config. */
public class LaunchAutomatedTestingOverridesTest
{
    @Test
    public void testExplicitClientModeAndIntegerPortAreAppliedWithoutSaving() throws Exception
    {
        assertApplied(null, "TESTCLIENT", 1540, "TESTCLIENT");
    }

    @Test
    public void testClientWithoutPortLeavesEdtDefaultAttributeUnset() throws Exception
    {
        assertApplied(null, "TESTCLIENT", null, "TESTCLIENT");
    }

    @Test
    public void testManagerModeHasNoPortAttribute() throws Exception
    {
        assertApplied(null, "TESTMANAGER", null, "TESTMANAGER");
    }

    @Test
    public void testLowercaseModeIsNormalizedToEdtExactSpelling() throws Exception
    {
        assertApplied(null, "testclient", 1540, "TESTCLIENT");
        assertApplied(null, "testmanager", null, "TESTMANAGER");
    }

    @Test
    public void testPortRangeIncludesBothEndpoints() throws Exception
    {
        assertApplied(null, "TESTCLIENT", 1, "TESTCLIENT");
        assertApplied(null, "TESTCLIENT", 65535, "TESTCLIENT");
    }

    @Test
    public void testStartupCommandAndTestingModeAreBothApplied() throws Exception
    {
        assertApplied("E2E654", "TESTCLIENT", 1540, "TESTCLIENT");
    }

    @Test
    public void testTestingModeIsNotAnEmptyOverride() throws Exception
    {
        assertFalse(overrides(null, "TESTCLIENT", null).isEmpty());
        assertFalse(overrides(null, "TESTMANAGER", null).isEmpty());
    }

    @Test
    public void testPortAloneIsNotAnEmptyOverride() throws Exception
    {
        assertFalse(overrides(null, null, 1540).isEmpty());
    }

    @Test
    public void testUnknownModeNamesTheNativeValues() throws Exception
    {
        errorMessage(overrides(null, "UNKNOWN", null).prepare().errorJson,
            "automatedTestingMode", "UNKNOWN", "TESTCLIENT", "TESTMANAGER");
    }

    @Test
    public void testZeroPortIsRefused() throws Exception
    {
        assertBadPort(0);
    }

    @Test
    public void testNegativePortIsRefused() throws Exception
    {
        assertBadPort(-1);
    }

    @Test
    public void testPortAbove65535IsRefused() throws Exception
    {
        assertBadPort(65536);
    }

    @Test
    public void testPortWithoutModeIsRefused() throws Exception
    {
        errorMessage(overrides(null, null, 1540).prepare().errorJson,
            "automatedTestingPort applies to automatedTestingMode=TESTCLIENT only", "omit");
    }

    @Test
    public void testManagerWithPortIsRefused() throws Exception
    {
        errorMessage(overrides(null, "TESTMANAGER", 1540).prepare().errorJson,
            "automatedTestingPort applies to automatedTestingMode=TESTCLIENT only", "omit");
    }

    @Test
    public void testAttachRefusalHappensBeforeGettingAWorkingCopy() throws Exception
    {
        for (String mode : new String[]{"TESTCLIENT", "TESTMANAGER"})
        {
            ILaunchConfiguration config = mock(ILaunchConfiguration.class);
            when(config.getName()).thenReturn("Attach654");
            LaunchOverrides.Prepared prepared = overrides(null, mode, null).prepare();
            assertNull(prepared.errorJson);
            errorMessage(prepared.attachRefusalOrNull(config, true),
                "Attach654", "Attach", "automatedTestingMode", "runtime-client configuration");
            LaunchOverrides.Applied applied = prepared.applyTo(config, true);
            errorMessage(applied.errorJson, "Attach654", "Attach");
            assertNull(applied.config);
            verify(config, never()).getWorkingCopy();
        }
    }

    private static void assertBadPort(int port) throws Exception
    {
        errorMessage(overrides(null, "TESTCLIENT", port).prepare().errorJson,
            "automatedTestingPort", Integer.toString(port), "1 to 65535", "TESTCLIENT");
    }

    private static void assertApplied(String startup, String mode, Integer port, String expectedMode)
        throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        when(config.getWorkingCopy()).thenReturn(copy);
        LaunchOverrides.Prepared prepared = overrides(startup, mode, port).prepare();
        assertNull(prepared.errorJson);
        LaunchOverrides.Applied applied = prepared.applyTo(config, false);
        assertNull(applied.errorJson);
        assertSame(copy, applied.config);
        verify(copy).setAttribute(MODE_ATTRIBUTE, expectedMode);
        if (port == null)
        {
            verify(copy, never()).setAttribute(eq(PORT_ATTRIBUTE), anyInt());
        }
        else
        {
            verify(copy).setAttribute(PORT_ATTRIBUTE, port.intValue());
        }
        if (startup != null)
        {
            verify(copy).setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startup);
        }
        verify(copy, never()).doSave();
    }
}
