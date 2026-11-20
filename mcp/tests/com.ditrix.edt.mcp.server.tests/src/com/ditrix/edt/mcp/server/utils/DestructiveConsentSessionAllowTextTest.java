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
import com.ditrix.edt.mcp.server.preferences.PreferenceInitializer;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;

/**
 * PR #1002 review: the session allowance is global to the tool, not tied to a prompt - another
 * request's dialog for the same tool may have granted it before this prompt's cutoff. The TIMEOUT
 * text may say what a repeat will do NOW, but never when, or in which dialog, the allowance was given.
 */
public class DestructiveConsentSessionAllowTextTest
{
    private static final String TOOL = "delete_project"; //$NON-NLS-1$

    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        PreferenceStore store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        new PreferenceInitializer().initializeDefaultPreferences();
        DestructiveConsentGate.getInstance().clearSessionAllow();
    }

    @After
    public void tearDown() throws Exception
    {
        DestructiveConsentGate.getInstance().clearSessionAllow();
        DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL); // drop a leftover hand-off
        plugin.set(null, previousPlugin);
    }

    private static String refusal()
    {
        DestructiveConsentGate.finishPrompt(ConsentDecision.TIMEOUT, 15);
        return DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
    }

    @Test
    public void anExistingSessionAllowanceIsNotAttributedToThisPrompt()
    {
        DestructiveConsentGate.getInstance().allowForSession(TOOL);
        String message = refusal();
        assertFalse("the allowance may come from another request's dialog: " + message, //$NON-NLS-1$
            message.contains("after the wait")); //$NON-NLS-1$
        assertFalse(message, message.contains("The user then chose")); //$NON-NLS-1$
        assertTrue(message, message.contains("'" + TOOL + "' is allowed for this EDT session")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("repeating the same call now runs it WITHOUT a dialog")); //$NON-NLS-1$
    }

    @Test
    public void theTextWithoutAnAllowanceMakesNoTimingClaimEither()
    {
        String message = refusal();
        assertFalse(message, message.contains("after the wait")); //$NON-NLS-1$
        assertFalse(message, message.contains("just after")); //$NON-NLS-1$
        // Still conditional: an allowance can be granted before the repeat, from any dialog.
        assertTrue(message, message.contains("the dialog opens again unless '" + TOOL //$NON-NLS-1$
            + "' is allowed for this EDT session before the repeat")); //$NON-NLS-1$
    }

    @Test
    public void bothCasesAreOneTextThatDiffersOnlyInWhatARepeatDoes()
    {
        String without = refusal();
        DestructiveConsentGate.getInstance().allowForSession(TOOL);
        String with = refusal();
        String head = "Destructive operation '" + TOOL + "' was not confirmed within 15 s: "; //$NON-NLS-1$ //$NON-NLS-2$
        String tail = "The wait itself is 'Confirmation wait' on the same page; " //$NON-NLS-1$
            + "keep it below your MCP client's request timeout."; //$NON-NLS-1$
        assertTrue(without, without.startsWith(head));
        assertTrue(with, with.startsWith(head));
        assertTrue(without, without.endsWith(tail));
        assertTrue(with, with.endsWith(tail));
        assertTrue(with, with.contains("nothing was changed")); //$NON-NLS-1$
        assertTrue(with, with.contains("Preferences -> MCP Server -> Destructive operations")); //$NON-NLS-1$
        assertEquals("the head and the ways on are shared", //$NON-NLS-1$
            without.substring(0, without.indexOf("nothing was changed.")), //$NON-NLS-1$
            with.substring(0, with.indexOf("nothing was changed."))); //$NON-NLS-1$
    }
}
