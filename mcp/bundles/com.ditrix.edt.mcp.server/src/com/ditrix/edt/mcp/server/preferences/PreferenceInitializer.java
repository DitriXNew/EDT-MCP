/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

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
        store.setDefault(PreferenceConstants.PREF_DEFAULT_LIMIT, PreferenceConstants.DEFAULT_DEFAULT_LIMIT);
        store.setDefault(PreferenceConstants.PREF_MAX_LIMIT, PreferenceConstants.DEFAULT_MAX_LIMIT);
        store.setDefault(PreferenceConstants.PREF_PLAIN_TEXT_MODE, PreferenceConstants.DEFAULT_PLAIN_TEXT_MODE);
        
        // Tag decoration preferences
        store.setDefault(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR, 
            PreferenceConstants.DEFAULT_TAGS_SHOW_IN_NAVIGATOR);
        store.setDefault(PreferenceConstants.PREF_TAGS_DECORATION_STYLE, 
            PreferenceConstants.DEFAULT_TAGS_DECORATION_STYLE);
    }
}
