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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;

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
}
