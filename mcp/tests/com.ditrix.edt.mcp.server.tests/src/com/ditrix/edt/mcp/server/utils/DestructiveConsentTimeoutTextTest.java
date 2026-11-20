/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

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
import com.ditrix.edt.mcp.server.preferences.PreferenceInitializer;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;

/**
 * Issue #997: the consent gate's TIMEOUT text has to reach the caller, so the wait it names must
 * fit the request budget of common MCP clients (about 60 s), and the text must say what happened
 * to the operation and how to go on. Uses only the gate's public text method, against a real
 * preference store with the plugin's defaults.
 */
public class DestructiveConsentTimeoutTextTest
{
    private static final String TOOL = "delete_project"; //$NON-NLS-1$

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

    private static String timeout()
    {
        return DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
    }

    @Test
    public void theDefaultWaitIsBelowTheCommonClientBudget()
    {
        String message = timeout();
        assertTrue("the default wait must be 45 s: " + message, message.contains("within 45 s")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theWaitPreferenceIsTheWaitTheTextNames()
    {
        store.setValue("mcpDestructiveConsentWaitSeconds", "20"); //$NON-NLS-1$ //$NON-NLS-2$
        String message = timeout();
        assertTrue("the text must name the configured wait: " + message, message.contains("within 20 s")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theTextSaysTheDialogWasClosedAndNothingWasApplied()
    {
        String message = timeout();
        // True whether the dialog was shown and closed or never got to open while EDT's UI was busy.
        assertTrue(message, message.contains("nobody answered the confirmation dialog in EDT")); //$NON-NLS-1$
        assertTrue(message, message.contains("the dialog does not stay open")); //$NON-NLS-1$
        assertTrue(message, message.contains("nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void theTextTellsTheAgentToRepeatTheSameCallWhileTheUserWatches()
    {
        String message = timeout();
        assertTrue(message, message.contains("Ask the user to watch the EDT window")); //$NON-NLS-1$
        assertTrue(message, message.contains("repeat the same call - the dialog opens again")); //$NON-NLS-1$
    }

    @Test
    public void theTextDoesNotPromiseADialogALateSessionAllowanceWouldSkip()
    {
        // An "Allow for session" can land after this text is built, in this dialog after the cutoff or
        // in another request's (#997 review): the promise of another dialog must carry that exception.
        String message = timeout();
        assertTrue(message, message.contains("unless '" + TOOL + "' is allowed for this EDT session before the repeat")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("then it runs without one")); //$NON-NLS-1$
    }

    @Test
    public void theTextNamesTheWaitSettingAndTheClientTimeout()
    {
        String message = timeout();
        assertTrue(message, message.contains("'Confirmation wait'")); //$NON-NLS-1$
        assertTrue(message, message.contains("keep it below your MCP client's request timeout")); //$NON-NLS-1$
    }

    @Test
    public void theTextKeepsTheWaysToProceedWithoutTheDialog()
    {
        String message = timeout();
        assertTrue(message, message.contains("Preferences -> MCP Server -> Destructive operations")); //$NON-NLS-1$
        assertTrue(message, message.contains("allow '" + TOOL + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("EDT_MCP_DESTRUCTIVE_CONSENT=allow")); //$NON-NLS-1$
        assertFalse("the former 120 s must be gone: " + message, message.contains("120")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
