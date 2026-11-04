/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;
import com.ditrix.edt.mcp.server.Activator;

public class PreferenceInitializerTest
{
    @Test
    public void defaultEnablesHttpForExistingInstall() throws Exception
    {
        PreferenceStore store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        Field plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        Object previous = plugin.get(null);
        try
        {
            plugin.set(null, activator);
            new PreferenceInitializer().initializeDefaultPreferences();
            assertTrue(PreferenceConstants.DEFAULT_HTTP_ENABLED);
            assertTrue(store.isDefault(PreferenceConstants.PREF_HTTP_ENABLED));
            assertTrue(store.getDefaultBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
            assertTrue(store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED));
        }
        finally { plugin.set(null, previous); }
    }
}
