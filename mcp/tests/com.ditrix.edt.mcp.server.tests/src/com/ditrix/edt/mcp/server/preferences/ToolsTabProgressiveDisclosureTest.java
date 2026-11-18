/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;

/** The Progressive disclosure checkbox on the real Tools tab: shown, pending until OK, reset by defaults. */
public class ToolsTabProgressiveDisclosureTest
{
    private PreferenceStore store;
    private Field plugin;
    private Object previousPlugin;
    private Display display;
    private boolean ownsDisplay;
    private Shell shell;
    private ToolsTab tab;

    @Before
    public void setUp() throws Exception
    {
        assumeTrue("SWT UI tests require Windows or an available display", //$NON-NLS-1$
            System.getProperty("os.name").startsWith("Windows") //$NON-NLS-1$ //$NON-NLS-2$
                || (System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank()) //$NON-NLS-1$ //$NON-NLS-2$
                || (System.getenv("WAYLAND_DISPLAY") != null //$NON-NLS-1$
                    && !System.getenv("WAYLAND_DISPLAY").isBlank())); //$NON-NLS-1$
        store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        new PreferenceInitializer().initializeDefaultPreferences();
        display = Display.getCurrent();
        ownsDisplay = display == null;
        if (ownsDisplay)
        {
            display = new Display();
        }
        shell = new Shell(display);
        tab = new ToolsTab(shell);
    }

    @After
    public void tearDown() throws Exception
    {
        if (tab != null)
        {
            tab.dispose();
        }
        if (shell != null)
        {
            shell.dispose();
        }
        if (ownsDisplay && display != null)
        {
            display.dispose();
        }
        if (plugin != null)
        {
            plugin.set(null, previousPlugin);
        }
    }

    @Test
    public void theCheckboxIsShownOffAndSavesOnlyOnOk() throws Exception
    {
        Button check = checkbox();
        assertNotNull(check);
        assertEquals(Messages.ToolsTab_ProgressiveDisclosure, check.getText());
        assertFalse("disclosure ships off", check.getSelection()); //$NON-NLS-1$

        check.setSelection(true);
        check.notifyListeners(SWT.Selection, new Event());
        assertFalse("a tick alone must not write preferences", //$NON-NLS-1$
            store.getBoolean(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE));
        tab.performOk();
        assertTrue(store.getBoolean(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE));

        tab.performDefaults();
        assertFalse("Restore Defaults unticks it", check.getSelection()); //$NON-NLS-1$
        tab.performOk();
        assertFalse(store.getBoolean(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE));
    }

    @Test
    public void theCheckboxShowsTheStoredValue() throws Exception
    {
        tab.dispose();
        shell.dispose();
        store.setValue(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE, true);
        shell = new Shell(display);
        tab = new ToolsTab(shell);
        assertTrue(checkbox().getSelection());
    }

    private Button checkbox() throws Exception
    {
        Field field = ToolsTab.class.getDeclaredField("progressiveDisclosureCheck"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Button)field.get(tab);
    }
}
