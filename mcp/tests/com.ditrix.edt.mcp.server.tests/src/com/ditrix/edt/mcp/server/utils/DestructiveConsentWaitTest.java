/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.preferences.PreferenceInitializer;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentArbiter;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;

/**
 * Issue #997: the consent wait is the "Confirmation wait" preference (45 s by default, 15..600 s),
 * the gate reads it, and a dialog whose call has already timed out is never shown.
 */
public class DestructiveConsentWaitTest
{
    private PreferenceStore store;
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        new PreferenceInitializer().initializeDefaultPreferences();
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void theDefaultWaitIs45Seconds()
    {
        assertEquals(45, PreferenceConstants.DEFAULT_DESTRUCTIVE_CONSENT_WAIT_SECONDS);
        assertEquals(45, store.getDefaultInt(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS));
        assertEquals(45, ConsentSettingsService.getInstance().getPromptWaitSeconds());
        assertEquals(45, DestructiveConsentGate.promptWaitSeconds());
    }

    @Test
    public void theGateWaitsWhatThePreferenceSays()
    {
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 90);
        assertEquals(90, ConsentSettingsService.getInstance().getPromptWaitSeconds());
        assertEquals(90, DestructiveConsentGate.promptWaitSeconds());
    }

    @Test
    public void aStoredWaitIsClampedToItsBounds()
    {
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 10);
        assertEquals(15, DestructiveConsentGate.promptWaitSeconds());
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 9999);
        assertEquals(600, DestructiveConsentGate.promptWaitSeconds());
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, "garbage"); //$NON-NLS-1$
        assertEquals(45, DestructiveConsentGate.promptWaitSeconds());
    }

    @Test
    public void theClampKeepsInRangeValuesAndMovesTheRestToTheNearerBound()
    {
        assertEquals(15, ConsentSettingsService.clampPromptWaitSeconds("15")); //$NON-NLS-1$
        assertEquals(30, ConsentSettingsService.clampPromptWaitSeconds(" 30 ")); //$NON-NLS-1$
        assertEquals(600, ConsentSettingsService.clampPromptWaitSeconds("600")); //$NON-NLS-1$
        assertEquals(15, ConsentSettingsService.clampPromptWaitSeconds("14")); //$NON-NLS-1$
        assertEquals(15, ConsentSettingsService.clampPromptWaitSeconds("-5")); //$NON-NLS-1$
        assertEquals(600, ConsentSettingsService.clampPromptWaitSeconds("601")); //$NON-NLS-1$
        assertEquals(600, ConsentSettingsService.clampPromptWaitSeconds("99999999999999")); //$NON-NLS-1$
        assertEquals(45, ConsentSettingsService.clampPromptWaitSeconds("")); //$NON-NLS-1$
        assertEquals(45, ConsentSettingsService.clampPromptWaitSeconds(null));
        assertEquals(45, ConsentSettingsService.clampPromptWaitSeconds("1.5")); //$NON-NLS-1$
    }

    @Test
    public void withoutAPreferenceStoreTheDefaultApplies() throws Exception
    {
        plugin.set(null, null);
        assertEquals(45, ConsentSettingsService.getInstance().getPromptWaitSeconds());
    }

    @Test
    public void theTimeoutTextNamesTheWaitThatElapsed()
    {
        String message = DestructiveConsentGate.timeoutMessage("delete_metadata", 17, false); //$NON-NLS-1$
        assertTrue(message, message.startsWith(
            "Destructive operation 'delete_metadata' was not confirmed within 17 s: ")); //$NON-NLS-1$
    }

    @Test
    public void aDialogThatOpensLateShowsOnlyTheTimeThatIsLeft()
    {
        long now = 1_000_000_000_000L;
        assertEquals(15, DestructiveConsentGate.remainingSeconds(now + 15_000_000_000L, now));
        assertEquals(15, DestructiveConsentGate.remainingSeconds(now + 14_200_000_000L, now));
        assertEquals(1, DestructiveConsentGate.remainingSeconds(now + 400_000_000L, now));
        assertEquals(0, DestructiveConsentGate.remainingSeconds(now, now));
        assertEquals(0, DestructiveConsentGate.remainingSeconds(now - 3_000_000_000L, now));
    }

    @Test
    public void aDialogIsNotShownOnceItsCallHasTimedOut()
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        assertTrue("an undecided prompt may show its dialog", DestructiveConsentGate.mayOpenDialog(false, arbiter)); //$NON-NLS-1$
        assertFalse("not over a disposed parent", DestructiveConsentGate.mayOpenDialog(true, arbiter)); //$NON-NLS-1$
        assertTrue(arbiter.tryDecide(ConsentDecision.TIMEOUT));
        assertFalse("a dialog that opens after the timeout would answer nobody", //$NON-NLS-1$
            DestructiveConsentGate.mayOpenDialog(false, arbiter));
    }
}
