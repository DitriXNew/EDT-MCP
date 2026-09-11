/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.LaunchTool;

/** Tests the per-launch EDT error-log window. */
public class LaunchAbortReasonTest
{
    private static final String ALLOWED_PLUGIN = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$

    @Test
    public void testCapturedErrorIncludesItsDistinctCause()
    {
        Throwable cause = new IllegalStateException("database connection was refused"); //$NON-NLS-1$
        IStatus status = new Status(IStatus.ERROR, ALLOWED_PLUGIN,
            "Application update failed", new RuntimeException("update failed", cause)); //$NON-NLS-1$ //$NON-NLS-2$
        try (LaunchAbortReason window = LaunchAbortReason.open())
        {
            log(status);
            String reason = window.reason();
            assertNotNull(reason);
            assertTrue(reason.contains("Application update failed")); //$NON-NLS-1$
            assertTrue(reason.contains("database connection was refused")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOwnPluginErrorIsIgnored()
    {
        try (LaunchAbortReason window = LaunchAbortReason.open())
        {
            log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "echo")); //$NON-NLS-1$
            assertNull(window.reason());
        }
    }

    @Test
    public void testErrorOutsideTheAllowlistIsIgnored()
    {
        try (LaunchAbortReason window = LaunchAbortReason.open())
        {
            log(new Status(IStatus.ERROR, "org.eclipse.core.runtime", "unrelated")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(window.reason());
        }
    }

    @Test
    public void testWarningAndInfoAreIgnored()
    {
        try (LaunchAbortReason window = LaunchAbortReason.open())
        {
            log(new Status(IStatus.WARNING, ALLOWED_PLUGIN, "warning")); //$NON-NLS-1$
            log(new Status(IStatus.INFO, ALLOWED_PLUGIN, "info")); //$NON-NLS-1$
            assertNull(window.reason());
        }
    }

    @Test
    public void testLastRelevantErrorWins()
    {
        try (LaunchAbortReason window = LaunchAbortReason.open())
        {
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN, "first")); //$NON-NLS-1$
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN, "last")); //$NON-NLS-1$
            assertEquals("last", window.reason()); //$NON-NLS-1$
        }
    }

    @Test
    public void testNamedStatusIsOwnedWhileAnotherWindowIsOpen()
    {
        try (LaunchAbortReason mine = LaunchAbortReason.open("Accounting"); //$NON-NLS-1$
            LaunchAbortReason other = LaunchAbortReason.open("Sales")) //$NON-NLS-1$
        {
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN,
                "Error synchronizing application Accounting")); //$NON-NLS-1$

            assertEquals("Error synchronizing application Accounting", mine.reason()); //$NON-NLS-1$
            assertNull(other.reason());
        }
    }

    @Test
    public void testForeignStatusIsNotOwnedWhileAnotherWindowIsOpen()
    {
        try (LaunchAbortReason mine = LaunchAbortReason.open("Accounting"); //$NON-NLS-1$
            LaunchAbortReason other = LaunchAbortReason.open("Sales")) //$NON-NLS-1$
        {
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN,
                "Error synchronizing application Sales")); //$NON-NLS-1$

            assertNull(mine.reason());
            assertEquals("Error synchronizing application Sales", other.reason()); //$NON-NLS-1$
        }
    }

    @Test
    public void testForeignStatusIsOwnedWhenThisIsTheOnlyWindow()
    {
        try (LaunchAbortReason mine = LaunchAbortReason.open("Accounting")) //$NON-NLS-1$
        {
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN,
                "Error synchronizing application Sales")); //$NON-NLS-1$

            assertEquals("Error synchronizing application Sales", mine.reason()); //$NON-NLS-1$
        }
    }

    @Test
    public void testConcurrencyRemainsStickyAfterTheOtherWindowCloses()
    {
        IStatus foreign = new Status(IStatus.ERROR, ALLOWED_PLUGIN,
            "Error synchronizing application Sales"); //$NON-NLS-1$
        try (LaunchAbortReason mine = LaunchAbortReason.open("Accounting")) //$NON-NLS-1$
        {
            try (LaunchAbortReason ignored = LaunchAbortReason.open("Sales")) //$NON-NLS-1$
            {
                // The overlap alone makes ownership checks mandatory for the rest of this window.
            }
            log(foreign);
            assertNull(mine.reason());
        }

        try (LaunchAbortReason alone = LaunchAbortReason.open("Accounting")) //$NON-NLS-1$
        {
            log(foreign);
            assertEquals("Error synchronizing application Sales", alone.reason()); //$NON-NLS-1$
        }
    }

    @Test
    public void testOwnershipFailureUsesTheNoReasonWording()
    {
        try (LaunchAbortReason mine = LaunchAbortReason.open("Accounting"); //$NON-NLS-1$
            LaunchAbortReason other = LaunchAbortReason.open("Sales")) //$NON-NLS-1$
        {
            log(new Status(IStatus.ERROR, ALLOWED_PLUGIN,
                "Error synchronizing application Sales")); //$NON-NLS-1$

            assertEquals("Launch of 'Accounting launch' was abandoned by EDT " //$NON-NLS-1$
                + "(the launch delegate cancelled it); no reason was logged. " //$NON-NLS-1$
                + "Check the EDT error log.", abandonedMessage("Accounting launch", mine.reason())); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testCloseRemovesTheListener()
    {
        LaunchAbortReason window = LaunchAbortReason.open();
        log(new Status(IStatus.ERROR, ALLOWED_PLUGIN, "before close")); //$NON-NLS-1$
        window.close();

        log(new Status(IStatus.ERROR, ALLOWED_PLUGIN, "after close")); //$NON-NLS-1$

        assertEquals("before close", window.reason()); //$NON-NLS-1$
    }

    private static void log(IStatus status)
    {
        Bundle bundle = Platform.getBundle(status.getPlugin());
        assertNotNull(bundle);
        Platform.getLog(bundle).log(status);
    }

    private static String abandonedMessage(String configName, String reason)
    {
        try
        {
            Method method = LaunchTool.class.getDeclaredMethod("abandonedLaunchMessage", //$NON-NLS-1$
                String.class, String.class);
            method.setAccessible(true);
            return (String)method.invoke(null, configName, reason);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }
}
