/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** The project-checks step follows v14 locally and is independently renumberable at merge. */
public class ProjectChecksMigrationTest
{
    @Test
    public void testAlreadyPreviousVersionReadOnlyStoresGainProjectChecksAndKeepOwnerEdits()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(preset, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION - 1);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled_tool", "project_checks"), disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
                store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void testBothCurrentReadOnlyPresetsExcludeProjectChecksByName()
    {
        assertTrue(ToolPreset.ReadOnlyPolicy.READ_ONLY_EXCLUDED_BY_NAME.contains("project_checks"));
        assertTrue(ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains("project_checks"));
        assertTrue(ToolPreset.CODE_REVIEW.getDisabledTools().contains("project_checks"));
        assertFalse(ToolPreset.DEVELOPMENT.getDisabledTools().contains("project_checks"));
    }

    @Test
    public void testTheStepIsRegisteredInBothHistoricalAdditionMaps()
    {
        int current = PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION;
        assertTrue(ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION.get(current)
            .contains("project_checks"));
        assertTrue(ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION.get(current)
            .contains("project_checks"));
    }

    @Test
    public void testDevelopmentCustomUnknownAndMissingProvenanceAreUnchanged()
    {
        for (String preset : List.of("development", "custom", "future-preset", "all-tools", ""))
        {
            PreferenceStore store = stored(preset, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION - 1);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled_tool"), disabled(store));
        }
    }

    @Test
    public void testAnAlreadyMigratedStoreRetainsOwnerReenableAndDoesNotWriteAgain()
    {
        PreferenceStore store = stored("code-review", PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
        int[] writes = {0};
        store.addPropertyChangeListener(event -> writes[0]++);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool"), disabled(store));
        assertEquals(0, writes[0]);
    }

    @Test
    public void testMigrationWritesTheDisabledSetOnceAndIsIdempotent()
    {
        PreferenceStore store = stored("analysis-only", PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION - 1);
        int[] writes = {0};
        store.addPropertyChangeListener(event -> {
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                writes[0]++;
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        Set<String> migrated = new HashSet<>(disabled(store));
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(migrated.contains("project_checks"));
        assertEquals(migrated, disabled(store));
        assertEquals(1, writes[0]);
    }

    private static PreferenceStore stored(String preset, int version)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled_tool");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        if (!preset.isEmpty())
        {
            store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, preset);
        }
        return store;
    }

    private static Set<String> disabled(PreferenceStore store)
    {
        return ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
