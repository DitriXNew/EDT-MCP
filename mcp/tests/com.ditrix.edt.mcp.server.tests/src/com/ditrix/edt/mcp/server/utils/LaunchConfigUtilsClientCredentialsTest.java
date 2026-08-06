/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

/**
 * Tests for the client-credential half of {@link LaunchConfigUtils} (issue #359): the attribute
 * mapping {@link LaunchConfigUtils#clientCredentialAttributes} and the write
 * {@link LaunchConfigUtils#applyClientCredentials} that puts it on a launch configuration.
 * <p>
 * Why this exists at all: the infobase access settings {@code set_infobase_credentials} used to
 * write are read by the designer AGENT. The 1C CLIENT a launch starts is a different process and
 * reads its user from the launch configuration's own attributes, so the agent-only write left the
 * client popping the platform's "Infobase access" dialog at every launch while the tool reported
 * success.
 * <p>
 * The mapping is a pure function so the part that actually decides WHICH radio of the launch
 * dialog's client-user section ends up selected is pinnable without a live Eclipse; the write is
 * driven against a mocked {@link ILaunchConfiguration} so "the map is right but nothing reaches the
 * configuration" cannot pass.
 */
public class LaunchConfigUtilsClientCredentialsTest
{
    private static final String USER = "Admin";

    private static final String PASSWORD = "s3cret";

    private static final String CONFIG_NAME = "TestConfiguration - thin client";

    // ==================== The pure attribute mapping ====================

    @Test
    public void explicitUserTakesTheClientOffTheInfobaseAccessSettings()
    {
        // The whole point of the fix: an explicit user must switch OFF "use the infobase access
        // settings". Leave that radio on and the client keeps reading the settings only the
        // designer agent consumes - which is exactly the bug.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, false);

        assertEquals("an explicit user must not defer to the infobase access settings",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
        assertEquals("an explicit user is not OS authentication",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
        assertEquals(USER, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals(PASSWORD, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void osAuthSelectsOsAndClearsTheUserAndPassword()
    {
        // The three radios are mutually exclusive: selecting OS authentication must not leave a
        // stale user/password behind that a later edit of the dialog would resurrect.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, true);

        assertEquals("OS authentication must not defer to the infobase access settings",
            Boolean.FALSE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
        assertEquals(Boolean.TRUE, attributes.get(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
        assertEquals("OS authentication carries no user - the old one must be cleared",
            "", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals("OS authentication carries no password - the old one must be cleared",
            "", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void nullsBecomeEmptyStringsInsteadOfBlowingUp()
    {
        // user/password are optional on the wire, so both arrive null routinely.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(null, null, false);

        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
    }

    @Test
    public void anEmptyPasswordIsWrittenRatherThanOmitted()
    {
        // A demo base's user legitimately has an EMPTY password, and "empty" has to be a value that
        // overwrites whatever the configuration held - not a key left out, which would keep a stale
        // password and authenticate as somebody the caller did not ask for.
        Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, "", false);

        assertTrue("an empty password must still be written",
            attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        assertEquals("", attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        assertEquals("an empty password must not blank the user too", USER,
            attributes.get(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
    }

    @Test
    public void everyClientAttributeIsAlwaysWritten()
    {
        // All four attributes on every path: a partially-written section leaves the dialog in a
        // state nobody asked for (e.g. OS off, use-settings off, and no user at all).
        for (boolean osAuth : new boolean[] { false, true })
        {
            Map<String, Object> attributes = LaunchConfigUtils.clientCredentialAttributes(USER, PASSWORD, osAuth);
            assertEquals("osAuth=" + osAuth + ": every client attribute must be written", 4,
                attributes.size());
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME));
            assertTrue(attributes.containsKey(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD));
        }
    }

    @Test
    public void theAttributeNamesAreThePlatformsOwn()
    {
        // These four names are the launch dialog's client-user section. A typo here writes an
        // attribute nothing reads, and the tool would report success while the client still asks
        // for a password - the exact failure this fix is about, made silent again.
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME",
            LaunchConfigUtils.ATTR_LAUNCH_USER_NAME);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_PASSWORD",
            LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS",
            LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS);
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS",
            LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS);
    }

    // ==================== The write ====================

    @Test
    public void applyWritesEveryAttributeOnTheWorkingCopyAndSaves() throws CoreException
    {
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getWorkingCopy()).thenReturn(copy);

        assertNull("a clean write reports no error",
            LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false));

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, false);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, false);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_NAME, USER);
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_USER_PASSWORD, PASSWORD);
        // Without doSave() the working copy is thrown away and nothing persists - the tool would
        // report success over a configuration it never actually changed.
        verify(copy).doSave();
    }

    @Test
    public void applyWritesTheBooleanAttributesAsBooleansNotStrings() throws CoreException
    {
        // The launch dialog reads these two through getAttribute(String, boolean); stored as the
        // STRING "false" they read back as the default and the radio never moves.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getWorkingCopy()).thenReturn(copy);

        LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, true);

        verify(copy).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, true);
        verify(copy, never()).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, "true");
        verify(copy, never()).setAttribute(LaunchConfigUtils.ATTR_LAUNCH_OS_INFOBASE_ACCESS, "false");
    }

    @Test
    public void applyReportsAFailureInsteadOfThrowing() throws CoreException
    {
        // A launch configuration can refuse the save (read-only file, deleted underneath us). The
        // tool must be able to say the client was NOT configured, not blow up mid-call.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getWorkingCopy()).thenReturn(copy);
        doThrow(new CoreException(new Status(IStatus.ERROR, "test", "launch config is read-only")))
            .when(copy).doSave();

        String error = LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        assertNotNull("a failed save must be reported, not swallowed", error);
        assertTrue("the reason must reach the caller: " + error, error.contains("read-only"));
    }

    @Test
    public void applyReportsAMissingConfigurationInsteadOfNpe()
    {
        String error = LaunchConfigUtils.applyClientCredentials(null, USER, PASSWORD, false);

        assertNotNull("a null configuration must be reported", error);
        assertTrue("the reason must name the configuration: " + error,
            error.contains("launch configuration"));
    }

    @Test
    public void applyNeverTouchesTheOriginalConfiguration() throws CoreException
    {
        // Eclipse launch configurations are immutable; the edit has to go through a working copy.
        ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn(CONFIG_NAME);
        when(config.getWorkingCopy()).thenReturn(copy);

        LaunchConfigUtils.applyClientCredentials(config, USER, PASSWORD, false);

        verify(config).getWorkingCopy();
    }
}
