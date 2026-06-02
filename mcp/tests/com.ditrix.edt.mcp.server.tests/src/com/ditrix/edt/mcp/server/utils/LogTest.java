/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.junit.Test;

/**
 * Smoke tests for {@link Log}: the logging facade resolves the bundle ILog via
 * Platform.getLog and must never throw, regardless of plugin activation state
 * (including a {@code null} exception). Logging output itself is verified live
 * (EDT .metadata/.log), not headlessly.
 */
public class LogTest
{
    @Test
    public void testLogMethodsDoNotThrow()
    {
        Log.info("LogTest info"); //$NON-NLS-1$
        Log.warning("LogTest warning"); //$NON-NLS-1$
        Log.debug("LogTest debug"); //$NON-NLS-1$
        Log.error("LogTest error with cause", new RuntimeException("LogTest cause")); //$NON-NLS-1$ //$NON-NLS-2$
        Log.error("LogTest error null cause", null); //$NON-NLS-1$
    }
}
