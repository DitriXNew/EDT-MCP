/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PiiRedactionSettings.PiiRedactionGate;

/**
 * Tests for {@link PiiRedactionSettings} — the default-OFF master toggle and the
 * {@code EDT_MCP_PII_REDACTION} env kill-switch that WINS over the preference.
 * Exercises the pure {@link PiiRedactionGate} classifiers / decision without a
 * live preference store or the process environment (mirrors the
 * {@code DestructiveConsentGate} env-classifier tests). The env-wins decision is
 * asserted through the pure {@link PiiRedactionGate#resolve(String, boolean)}.
 */
public class PiiRedactionSettingsTest
{
    @Test
    public void testDefaultIsOff()
    {
        assertFalse("PII redaction must default OFF (byte-identical output when off)", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_PII_REDACTION_ENABLED);
    }

    @Test
    public void testNoEnvOverrideDefersToPreference()
    {
        // Absent an env override, resolve() returns the preference value unchanged.
        assertTrue(PiiRedactionGate.resolve(null, true));
        assertFalse(PiiRedactionGate.resolve(null, false));
        assertTrue(PiiRedactionGate.resolve("", true)); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.resolve("   ", false)); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.resolve("bogus", true)); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.resolve("bogus", false)); //$NON-NLS-1$
    }

    @Test
    public void testEnvOnWinsOverPreferenceOff()
    {
        // Env forces ON even though the stored preference is OFF.
        assertTrue(PiiRedactionGate.resolve("on", false)); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.resolve("true", false)); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.resolve("1", false)); //$NON-NLS-1$
    }

    @Test
    public void testEnvOffWinsOverPreferenceOn()
    {
        // Env forces OFF even though the stored preference is ON (the kill-switch).
        assertFalse(PiiRedactionGate.resolve("off", true)); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.resolve("false", true)); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.resolve("0", true)); //$NON-NLS-1$
    }

    @Test
    public void testEnvForcesOnFamilyCaseAndWhitespaceInsensitive()
    {
        assertTrue(PiiRedactionGate.envForcesOn("on")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOn("ON")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOn("  On  ")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOn("true")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOn("yes")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOn("enabled")); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.envForcesOn(null));
        assertFalse(PiiRedactionGate.envForcesOn("off")); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.envForcesOn("bogus")); //$NON-NLS-1$
    }

    @Test
    public void testEnvForcesOffFamilyCaseAndWhitespaceInsensitive()
    {
        assertTrue(PiiRedactionGate.envForcesOff("off")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOff("OFF")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOff("  Off  ")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOff("false")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOff("no")); //$NON-NLS-1$
        assertTrue(PiiRedactionGate.envForcesOff("disabled")); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.envForcesOff(null));
        assertFalse(PiiRedactionGate.envForcesOff("on")); //$NON-NLS-1$
        assertFalse(PiiRedactionGate.envForcesOff("bogus")); //$NON-NLS-1$
    }
}
