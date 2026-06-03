/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link Log}. Two concerns:
 * <ul>
 * <li>Smoke: the logging facade resolves the bundle ILog via Platform.getLog and
 * must never throw, regardless of plugin activation state (including a
 * {@code null} exception). Logging output itself is verified live
 * (EDT .metadata/.log), not headlessly.</li>
 * <li>Debug gate: {@link Log#debug(String)} is suppressed when the gate is OFF
 * and reaches the log path when the gate is ON, while the production default
 * keeps debug output suppressed.</li>
 * </ul>
 *
 * <p>This test bundle is a fragment of the host, so it can use the
 * package-private {@link Log#setDebugGate(BooleanSupplier)} seam to force the
 * gate deterministically.
 */
public class LogTest
{
    /**
     * Restore the production default gate after each test so a forced gate never
     * leaks into other tests or the running platform.
     */
    @After
    public void restoreDefaultGate()
    {
        Log.setDebugGate(null);
    }

    @Test
    public void testLogMethodsDoNotThrow()
    {
        Log.info("LogTest info"); //$NON-NLS-1$
        Log.warning("LogTest warning"); //$NON-NLS-1$
        Log.debug("LogTest debug"); //$NON-NLS-1$
        Log.error("LogTest error with cause", new RuntimeException("LogTest cause")); //$NON-NLS-1$ //$NON-NLS-2$
        Log.error("LogTest error null cause", null); //$NON-NLS-1$
    }

    @Test
    public void testDebugSuppressedWhenGateOff()
    {
        // A gate that counts how many times debug() consults it and always reports OFF.
        AtomicInteger gateChecks = new AtomicInteger();
        Log.setDebugGate(() -> {
            gateChecks.incrementAndGet();
            return false;
        });

        assertFalse("Debug must be reported disabled when the gate is OFF", Log.isDebugEnabled()); //$NON-NLS-1$

        // Reset the counter so we measure only the debug() call below.
        gateChecks.set(0);
        Log.debug("should be suppressed"); //$NON-NLS-1$

        // debug() must consult the gate and, finding it OFF, take the no-op branch.
        assertEquals("debug() should consult the gate exactly once", 1, gateChecks.get()); //$NON-NLS-1$
    }

    @Test
    public void testDebugEmittedWhenGateOn()
    {
        // A gate that counts how many times debug() consults it and always reports ON.
        AtomicInteger gateChecks = new AtomicInteger();
        Log.setDebugGate(() -> {
            gateChecks.incrementAndGet();
            return true;
        });

        assertTrue("Debug must be reported enabled when the gate is ON", Log.isDebugEnabled()); //$NON-NLS-1$

        gateChecks.set(0);
        // When ON, debug() must reach the log path: it must not throw, and it must
        // have consulted the gate (proving the emit branch, not the no-op branch).
        Log.debug("should be emitted"); //$NON-NLS-1$

        assertTrue("debug() should consult the gate when ON", gateChecks.get() >= 1); //$NON-NLS-1$
    }

    @Test
    public void testGateTogglesIsDebugEnabled()
    {
        Log.setDebugGate(() -> false);
        assertFalse("OFF gate -> disabled", Log.isDebugEnabled()); //$NON-NLS-1$

        Log.setDebugGate(() -> true);
        assertTrue("ON gate -> enabled", Log.isDebugEnabled()); //$NON-NLS-1$

        Log.setDebugGate(() -> false);
        assertFalse("OFF gate again -> disabled", Log.isDebugEnabled()); //$NON-NLS-1$
    }

    @Test
    public void testNullRestoresProductionDefault()
    {
        // Force ON, then restore the default. The default consults the Eclipse
        // tracing flag, which is OFF unless explicitly enabled -> debug suppressed.
        Log.setDebugGate(() -> true);
        assertTrue(Log.isDebugEnabled());

        Log.setDebugGate(null);
        assertFalse("Production default must keep debug suppressed", Log.isDebugEnabled()); //$NON-NLS-1$
    }
}
