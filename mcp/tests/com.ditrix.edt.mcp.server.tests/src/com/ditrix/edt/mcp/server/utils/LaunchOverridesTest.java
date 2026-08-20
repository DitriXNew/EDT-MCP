/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link LaunchOverrides} — the per-launch {@code /C} startup option and external
 * data processor / report of {@code debug_launch} (issue #344).
 *
 * <p>Everything asserted here is reachable headlessly: the emptiness contract, the two refusals
 * that precede any model access, and — the one that matters most — that applying an override
 * stamps a WORKING COPY and never saves it. Resolving the external object itself needs a live
 * workspace and is covered by {@code test_debug_launch.py}.</p>
 */
public class LaunchOverridesTest
{
    private static final String STARTUP = "xddRun Loader C:/tests; xddShutdown;"; //$NON-NLS-1$

    @Test
    public void testAbsentOverridesAreEmpty()
    {
        assertTrue(LaunchOverrides.of(null, null, null).isEmpty());
    }

    @Test
    public void testBlankOverridesAreEmpty()
    {
        // Whitespace is not an override: a client that sends "" for an untouched field must get
        // the plain launch, not a working copy stamped with an empty /C.
        assertTrue(LaunchOverrides.of("   ", "", " ").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(LaunchOverrides.blank(" ")); //$NON-NLS-1$
        assertFalse(LaunchOverrides.blank("x")); //$NON-NLS-1$
    }

    @Test
    public void testAnyOverrideIsNotEmpty()
    {
        assertFalse(LaunchOverrides.of(STARTUP, null, null).isEmpty());
        assertFalse(LaunchOverrides.of(null, "ExternalObjects", null).isEmpty()); //$NON-NLS-1$
        assertFalse(LaunchOverrides.of(null, null, "Runner").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testEmptyOverridesLaunchTheSavedConfigurationUntouched() throws Exception
    {
        // The no-override path must not even create a working copy: an ordinary debug_launch
        // keeps launching exactly the object it launched before this feature existed.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        LaunchOverrides.Applied applied = LaunchOverrides.of(null, null, null).prepare().applyTo(config, false);
        assertNull(applied.errorJson);
        assertSame(config, applied.config);
        verify(config, never()).getWorkingCopy();
    }

    @Test
    public void testStartupOptionStampsAWorkingCopyAndNeverSavesIt() throws Exception
    {
        // The acceptance criterion of issue #344 that a review cannot check by reading: the
        // caller's saved EDT launch configuration must come out of this untouched. A working
        // copy IS an ILaunchConfiguration, so it can be launched without doSave() — and doSave()
        // is what would rewrite the user's configuration with one call's arguments.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunchConfigurationWorkingCopy workingCopy = mock(ILaunchConfigurationWorkingCopy.class);
        when(config.getWorkingCopy()).thenReturn(workingCopy);

        LaunchOverrides.Applied applied =
            LaunchOverrides.of(STARTUP, null, null).prepare().applyTo(config, false);

        assertNull(applied.errorJson);
        assertSame(workingCopy, applied.config);
        verify(workingCopy).setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, STARTUP);
        verify(workingCopy, never()).doSave();
        // The external-object attributes are NOT stamped when no object was asked for: leaving a
        // stale trio behind would make the delegate try to run something the caller never named.
        verify(workingCopy, never()).setAttribute(
            eqAttr(LaunchConfigUtils.ATTR_EXTERNAL_OBJECT_NAME), anyString());
    }

    @Test
    public void testAttachConfigurationIsRefusedRatherThanSilentlyIgnored()
    {
        // Only the runtime-client delegate reads these attributes. Storing them on an Attach
        // config would launch happily and run nothing — the exact silent success this guards.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Attach to 1C:Enterprise Debug Server"); //$NON-NLS-1$

        LaunchOverrides.Applied applied =
            LaunchOverrides.of(STARTUP, null, null).prepare().applyTo(config, true);

        assertNotNull("an Attach config must be refused, not stamped", applied.errorJson);
        assertNull(applied.config);
        JsonObject json = JsonParser.parseString(applied.errorJson).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        String message = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("the refusal must name the configuration kind: " + message,
            message.contains("Attach")); //$NON-NLS-1$
        assertTrue("the refusal must name the configuration: " + message,
            message.contains("Attach to 1C:Enterprise Debug Server")); //$NON-NLS-1$
    }

    @Test
    public void testHalfAnExternalObjectAddressNamesTheMissingHalf()
    {
        // Refused by prepare() alone - no launch configuration is involved, which is the point:
        // the caller learns about the typo before anything is resolved, terminated or updated.
        String projectOnly = LaunchOverrides.of(null, "ExternalObjects", null).prepare().errorJson; //$NON-NLS-1$
        assertNotNull(projectOnly);
        assertTrue(messageOf(projectOnly).contains("externalObjectName is missing")); //$NON-NLS-1$

        String objectOnly = LaunchOverrides.of(null, null, "Runner").prepare().errorJson; //$NON-NLS-1$
        assertNotNull(objectOnly);
        assertTrue(messageOf(objectOnly).contains("externalObjectProjectName is missing")); //$NON-NLS-1$
    }

    @Test
    public void testAWellFormedRequestWithNoExternalObjectPreparesCleanly()
    {
        // A /C-only call must not be dragged through external-object resolution (which needs a
        // live workspace): prepare() has nothing to check and says so.
        assertNull(LaunchOverrides.of(STARTUP, null, null).prepare().errorJson);
        assertNull(LaunchOverrides.of(null, null, null).prepare().errorJson);
    }

    @Test
    public void testAccessorsRoundTripTheValues()
    {
        LaunchOverrides overrides = LaunchOverrides.of(STARTUP, "ExtObjects", "Runner"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(STARTUP, overrides.startupOption());
        assertEquals("ExtObjects", overrides.externalObjectProjectName()); //$NON-NLS-1$
        assertEquals("Runner", overrides.externalObjectName()); //$NON-NLS-1$
    }

    /** Mockito matcher sugar keeping the verify() above readable. */
    private static String eqAttr(String attribute)
    {
        return org.mockito.ArgumentMatchers.eq(attribute);
    }

    private static String messageOf(String errorJson)
    {
        return JsonParser.parseString(errorJson).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }
}
