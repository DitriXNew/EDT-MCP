/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.history.HistoryConfig;

/**
 * Default preference initializer.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(PreferenceConstants.PREF_PORT, PreferenceConstants.DEFAULT_PORT);
        store.setDefault(PreferenceConstants.PREF_AUTO_START, PreferenceConstants.DEFAULT_AUTO_START);
        store.setDefault(PreferenceConstants.PREF_CHECKS_FOLDER, PreferenceConstants.DEFAULT_CHECKS_FOLDER);
        store.setDefault(PreferenceConstants.PREF_PLAIN_TEXT_MODE, PreferenceConstants.DEFAULT_PLAIN_TEXT_MODE);
        
        // Tag decoration preferences
        store.setDefault(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR, 
            PreferenceConstants.DEFAULT_TAGS_SHOW_IN_NAVIGATOR);
        store.setDefault(PreferenceConstants.PREF_TAGS_DECORATION_STYLE, 
            PreferenceConstants.DEFAULT_TAGS_DECORATION_STYLE);

        // Update check interval
        store.setDefault(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL,
            PreferenceConstants.DEFAULT_UPDATE_CHECK_INTERVAL);

        // Tool enablement
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);

        // Progressive tool disclosure (dynamic toolsets)
        store.setDefault(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE,
            PreferenceConstants.DEFAULT_PROGRESSIVE_DISCLOSURE);

        // Security
        store.setDefault(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS,
            PreferenceConstants.DEFAULT_ALLOW_REMOTE_ACCESS);
        store.setDefault(PreferenceConstants.PREF_AUTH_TOKEN,
            PreferenceConstants.DEFAULT_AUTH_TOKEN);

        // Destructive-operation consent (default: always ask)
        store.setDefault(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_LEVEL,
            PreferenceConstants.DEFAULT_DESTRUCTIVE_CONSENT_LEVEL);
        store.setDefault(PreferenceConstants.PREF_DESTRUCTIVE_ALLOWED_TOOLS,
            PreferenceConstants.DEFAULT_DESTRUCTIVE_ALLOWED_TOOLS);

        // MCP request/response history recorder (record ON by default; file log OFF for privacy)
        HistoryConfig.initializeDefaults(store);

        // PII redaction rule table + pseudonymiser salt (the master toggle
        // PREF_PII_REDACTION_ENABLED stays OFF via the boolean default).
        store.setDefault(PreferenceConstants.PREF_PII_RULES_JSON,
            PreferenceConstants.DEFAULT_PII_RULES_JSON);
        store.setDefault(PreferenceConstants.PREF_PII_SALT,
            PreferenceConstants.DEFAULT_PII_SALT);

        // Per-tool parameter defaults
        ToolParameterSettings.getInstance().initializeDefaults(store);
    }
}