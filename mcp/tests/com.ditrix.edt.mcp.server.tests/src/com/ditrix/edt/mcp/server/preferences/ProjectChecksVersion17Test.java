/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Master owns v15, #449 owns v16, and project_checks owns only v17. */
public class ProjectChecksVersion17Test
{
    @Test
    public void version17IsRegisteredWithoutTakingTheVersion16Step()
    {
        assertTrue(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION >= 17);
        for (var additions : List.of(ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION,
            ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION))
        {
            assertEquals(Set.of("get_form_screenshot"), additions.get(15));
            assertFalse("#449 owns v16", additions.getOrDefault(16, Set.of()).contains("project_checks"));
            assertEquals(Set.of("project_checks"), additions.get(17));
        }
    }

    @Test
    public void version16ReadOnlyStoresReceiveProjectChecksAndRetainOwnerEdits()
    {
        migrateReadOnly(16);
    }

    @Test
    public void version15ReadOnlyStoresAlsoReceiveProjectChecks()
    {
        migrateReadOnly(15);
    }

    @Test
    public void version14StoresRetainMastersUniversalScreenshotAddition()
    {
        for (String id : List.of("analysis-only", "development", "custom"))
        {
            PreferenceStore store = stored(id, 14);
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = id.equals("analysis-only")
                ? Set.of("owner_disabled", "get_form_screenshot", "project_checks")
                : Set.of("owner_disabled", "get_form_screenshot");
            assertEquals(expected, disabled(store));
            assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
                store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    private static void migrateReadOnly(int version)
    {
        for (String id : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(id, version);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled", "project_checks"), disabled(store));
            assertEquals(id, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
                store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    private static PreferenceStore stored(String id, int version)
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled");
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, id);
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        return store;
    }

    private static Set<String> disabled(PreferenceStore store)
    {
        return ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
