/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertEquals;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

public class McpServerStartupTest
{
    @Test
    public void httpPreferenceAndCiOverrideRetainStdioLaunchSuppression()
    {
        PreferenceStore store = new PreferenceStore();
        for (boolean enabled : new boolean[] {false, true})
        {
            store.setValue(PreferenceConstants.PREF_HTTP_ENABLED, enabled);
            for (boolean auto : new boolean[] {false, true})
            {
                store.setValue(PreferenceConstants.PREF_AUTO_START, auto);
                for (boolean stdio : new boolean[] {false, true})
                {
                    for (String override : new String[] {null, "false", "true", "TRUE"})
                    {
                        boolean forced = "true".equalsIgnoreCase(override);
                        assertEquals("enabled=" + enabled + ", auto=" + auto + ", stdio=" + stdio
                            + ", override=" + override, !stdio && (forced || (enabled && auto)),
                            McpServerStartup.shouldStartHttp(store, override, stdio));
                    }
                }
            }
        }
    }
}
