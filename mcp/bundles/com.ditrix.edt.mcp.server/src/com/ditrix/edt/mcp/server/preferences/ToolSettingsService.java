/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Service managing tool enablement state.
 * Reads and writes disabled tool names to the preference store.
 * Thread-safe: the disabled set is parsed on each access from the volatile preference store.
 */
public final class ToolSettingsService // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
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
     * Returns the set of disabled tool names from preferences, falling back to the SHIPPED defaults
     * when no store is available - never to "nothing is disabled", which would enable a default-off
     * tool.
     */
    public Set<String> getDisabledTools()
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            // FAIL CLOSED: with no preference store (a headless registry, a plugin not started yet)
            // an empty set would advertise and allow EVERY tool, including the ones that ship
            // disabled - the powerful raw git tool among them. Fall back to the shipped defaults.
            return parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        }
        ensureMigrated(store);
        String value = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
        return parseDisabledTools(value);
    }

    /**
     * Applies the tool-enablement preference MIGRATIONS once per store, lazily on the first read.
     * <p>
     * A tool that ships DISABLED by default gets that from {@code DEFAULT_DISABLED_TOOLS} - but only on
     * a store that never persisted its own value. An installation that had already saved the Tools tab
     * (or an "all tools" preset) holds an explicit list that predates the new tool, so without this the
     * powerful {@code git} tool would silently arrive ENABLED on upgrade. Version 1 therefore adds it to
     * such a stored list; the user can still enable it deliberately afterwards.
     * <p>
     * Version 2 covers a narrower case: {@code apply_quick_fix} is a normal (default-ON) tool, so it is
     * NOT added to every stored list the way {@code git} is - only to a list that, once it gains
     * {@code apply_quick_fix}, becomes EXACTLY {@link ToolPreset#CODE_REVIEW}'s or
     * {@link ToolPreset#ANALYSIS_ONLY}'s current disabled set. That is the signature of a store saved
     * by an older build under one of those two read-only presets, before this write-capable tool
     * existed to be excluded from them; a CUSTOM selection that merely overlaps is left untouched.
     *
     * @param store the preference store to migrate (never {@code null} here)
     */
    /**
     * Test seam: runs the migration against a supplied store, so the one mechanism that keeps a
     * default-off tool disabled on upgrade can be verified without an Eclipse runtime.
     *
     * @param store the preference store to migrate
     */
    static void ensureMigratedForTest(IPreferenceStore store)
    {
        INSTANCE.ensureMigrated(store);
    }

    private void ensureMigrated(IPreferenceStore store)
    {
        if (store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION)
            >= PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION)
        {
            return;
        }
        // Only an EXPLICITLY stored list needs fixing; a default-valued store already carries the new
        // default (which includes the tool).
        if (store.contains(PreferenceConstants.PREF_DISABLED_TOOLS)
            && !store.isDefault(PreferenceConstants.PREF_DISABLED_TOOLS))
        {
            Set<String> disabled =
                new LinkedHashSet<>(parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)));
            boolean changed = false;
            // The tool names are used as literals here on purpose: the preferences layer must not
            // depend on tools/impl (see the architecture rules).
            changed |= disabled.add("git"); //$NON-NLS-1$
            changed |= migrateApplyQuickFixIntoReadOnlyPreset(disabled);
            if (changed)
            {
                store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, serializeDisabledTools(disabled));
            }
        }
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
    }

    /**
     * Adds {@code apply_quick_fix} to {@code disabled} when doing so would turn it into EXACTLY the
     * current {@link ToolPreset#CODE_REVIEW} or {@link ToolPreset#ANALYSIS_ONLY} shape - i.e. the
     * stored list already IS one of those two read-only presets, just saved before this tool existed
     * to be excluded from them. Reuses {@link ToolPreset#matchPreset}, which already ignores unknown
     * / stale tool names, so a leftover obsolete entry in the stored list does not defeat the match.
     *
     * @param disabled the mutable stored disabled-tools set; modified in place
     * @return {@code true} when {@code apply_quick_fix} was added
     */
    private static boolean migrateApplyQuickFixIntoReadOnlyPreset(Set<String> disabled)
    {
        if (disabled.contains("apply_quick_fix")) //$NON-NLS-1$
        {
            return false;
        }
        Set<String> withQuickFix = new LinkedHashSet<>(disabled);
        withQuickFix.add("apply_quick_fix"); //$NON-NLS-1$
        ToolPreset matched = ToolPreset.matchPreset(withQuickFix);
        if (matched == ToolPreset.CODE_REVIEW || matched == ToolPreset.ANALYSIS_ONLY)
        {
            return disabled.add("apply_quick_fix"); //$NON-NLS-1$
        }
        return false;
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
