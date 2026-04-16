/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Service managing tool enablement state.
 * Reads and writes disabled tool names to the preference store.
 * Thread-safe: the disabled set is parsed on each access from the volatile preference store.
 */
public final class ToolSettingsService
{
    private static final ToolSettingsService INSTANCE = new ToolSettingsService();

    private ToolSettingsService()
    {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static ToolSettingsService getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the set of disabled tool names from preferences.
     */
    public Set<String> getDisabledTools()
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return Collections.emptySet();
        }
        String value = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
        return parseDisabledTools(value);
    }

    /**
     * Saves the set of disabled tool names to preferences.
     */
    public void setDisabledTools(Set<String> disabledTools)
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return;
        }
        String value = serializeDisabledTools(disabledTools);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, value);
    }

    /**
     * Checks whether a specific tool is enabled.
     */
    public boolean isToolEnabled(String toolName)
    {
        return !getDisabledTools().contains(toolName);
    }

    /**
     * Sets the enabled state for a specific tool.
     */
    public void setToolEnabled(String toolName, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.remove(toolName);
        }
        else
        {
            disabled.add(toolName);
        }
        setDisabledTools(disabled);
    }

    /**
     * Checks whether all tools in a group are enabled.
     */
    public boolean isGroupFullyEnabled(ToolGroup group)
    {
        Set<String> disabled = getDisabledTools();
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether at least one (but not all) tools in a group are enabled.
     */
    public boolean isGroupPartiallyEnabled(ToolGroup group)
    {
        Set<String> disabled = getDisabledTools();
        boolean hasEnabled = false;
        boolean hasDisabled = false;
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                hasDisabled = true;
            }
            else
            {
                hasEnabled = true;
            }
            if (hasEnabled && hasDisabled)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Enables or disables all tools in a group.
     */
    public void setGroupEnabled(ToolGroup group, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.removeAll(group.getToolNames());
        }
        else
        {
            disabled.addAll(group.getToolNames());
        }
        setDisabledTools(disabled);
    }

    /**
     * Applies a preset by setting the disabled tools to the preset's definition.
     */
    public void applyPreset(ToolPreset preset)
    {
        Set<String> disabledTools = preset.getDisabledTools();
        if (disabledTools != null)
        {
            setDisabledTools(disabledTools);
        }
    }

    /**
     * Returns the count of currently enabled tools.
     * Only counts known tools (those belonging to a ToolGroup) to avoid
     * incorrect counts from obsolete tool names left in preferences.
     */
    public int getEnabledToolCount()
    {
        Set<String> disabled = getDisabledTools();
        int enabled = 0;
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (!disabled.contains(toolName))
                {
                    enabled++;
                }
            }
        }
        return enabled;
    }

    /**
     * Parses a comma-separated string of disabled tool names.
     */
    static Set<String> parseDisabledTools(String value)
    {
        if (value == null || value.isBlank())
        {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(",")) //$NON-NLS-1$
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Serializes a set of disabled tool names to a comma-separated string.
     */
    static String serializeDisabledTools(Set<String> disabledTools)
    {
        if (disabledTools == null || disabledTools.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return disabledTools.stream()
            .sorted()
            .collect(Collectors.joining(",")); //$NON-NLS-1$
    }

    private IPreferenceStore getStore()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }
}
