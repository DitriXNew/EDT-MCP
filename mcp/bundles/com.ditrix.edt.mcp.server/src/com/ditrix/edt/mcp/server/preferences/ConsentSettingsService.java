/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Service managing the destructive-operation consent settings.
 * Reads and writes the consent level and the per-tool allow-set to the preference store.
 * Thread-safe: values are parsed on each access from the volatile preference store.
 *
 * @see com.ditrix.edt.mcp.server.preferences.PreferenceConstants#PREF_DESTRUCTIVE_CONSENT_LEVEL
 * @see com.ditrix.edt.mcp.server.preferences.PreferenceConstants#PREF_DESTRUCTIVE_ALLOWED_TOOLS
 */
public final class ConsentSettingsService // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    /**
     * Consent level for destructive MCP writes.
     */
    public enum Level
    {
        /** Always ask the human for consent before a destructive write (default). */
        ASK_ALWAYS(PreferenceConstants.CONSENT_LEVEL_ASK_ALWAYS),
        /** Allow every destructive write without asking. */
        ALLOW_ALL(PreferenceConstants.CONSENT_LEVEL_ALLOW_ALL),
        /** Ask, except for tools explicitly listed in the allow-set. */
        PER_TOOL(PreferenceConstants.CONSENT_LEVEL_PER_TOOL);

        private final String prefValue;

        Level(String prefValue)
        {
            this.prefValue = prefValue;
        }

        /**
         * Returns the preference-store token that persists this level.
         */
        public String getPreferenceValue()
        {
            return prefValue;
        }

        /**
         * Resolves a persisted preference token to a {@link Level}, falling back to
         * {@link #ASK_ALWAYS} for a null/blank/unknown value.
         */
        public static Level fromPreferenceValue(String value)
        {
            if (value != null)
            {
                for (Level level : values())
                {
                    if (level.prefValue.equals(value))
                    {
                        return level;
                    }
                }
            }
            return ASK_ALWAYS;
        }
    }

    private static final ConsentSettingsService INSTANCE = new ConsentSettingsService();

    private ConsentSettingsService()
    {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static ConsentSettingsService getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the current consent level. Defaults to {@link Level#ASK_ALWAYS} in a
     * headless/test context with no Activator or preference store.
     */
    public Level getLevel()
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return Level.ASK_ALWAYS;
        }
        return Level.fromPreferenceValue(store.getString(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_LEVEL));
    }

    /**
     * Persists the consent level.
     */
    public void setLevel(Level level)
    {
        IPreferenceStore store = getStore();
        if (store == null || level == null)
        {
            return;
        }
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_LEVEL, level.getPreferenceValue());
    }

    /**
     * Returns the set of tool names allowed to run destructively without consent
     * (the per-tool allow-set, used only at {@link Level#PER_TOOL}).
     */
    public Set<String> getAllowedTools()
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return new HashSet<>();
        }
        return new HashSet<>(
            ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DESTRUCTIVE_ALLOWED_TOOLS)));
    }

    /**
     * Persists the per-tool allow-set.
     */
    public void setAllowedTools(Set<String> allowedTools)
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return;
        }
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_ALLOWED_TOOLS,
            ToolSettingsService.serializeDisabledTools(allowedTools));
    }

    /**
     * Whether the given tool is allowed to run destructively without consent by the
     * per-tool allow-set. This only reflects the CSV preference; the caller is
     * responsible for applying it only at {@link Level#PER_TOOL}.
     */
    public boolean isToolAllowed(String toolName)
    {
        return toolName != null && getAllowedTools().contains(toolName);
    }

    private IPreferenceStore getStore()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }
}
