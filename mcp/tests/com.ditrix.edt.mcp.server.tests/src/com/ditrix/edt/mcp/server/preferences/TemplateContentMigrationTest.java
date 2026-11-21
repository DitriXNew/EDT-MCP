/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Moving a writer between tools preserves recorded read-only policy and existing disables. */
public class TemplateContentMigrationTest
{
    private static final String TOOL = "template_content";

    @Test
    public void version24ReadOnlyProfilesDisableNewWriterWithoutReapplyingOlderMigrations()
    {
        for (ToolPreset preset : List.of(ToolPreset.ANALYSIS_ONLY, ToolPreset.CODE_REVIEW))
        {
            assertTrue(preset.getDisabledTools().contains(TOOL));
            PreferenceStore store = stored(preset.getId(), Set.of("owner_disabled"));
            var writes = new ArrayList<String>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));

            ToolSettingsService.ensureMigratedForTest(store);

            assertEquals(Set.of("owner_disabled", TOOL), disabled(store));
            assertEquals(preset.getId(), store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(25, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
                PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);

            store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled");
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled"), disabled(store));
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version24OtherProfilesOnlyMigrateAnExplicitOldWriterDisable()
    {
        for (String preset : List.of("development", "all-tools", "custom", "future-preset", ""))
        {
            var unrelated = stored(preset, Set.of("owner_disabled"));
            ToolSettingsService.ensureMigratedForTest(unrelated);
            assertEquals(preset, Set.of("owner_disabled"), disabled(unrelated));

            var explicitlyDisabled = stored(preset, Set.of("owner_disabled", "modify_metadata"));
            ToolSettingsService.ensureMigratedForTest(explicitlyDisabled);
            assertEquals(preset, Set.of("owner_disabled", "modify_metadata", TOOL), disabled(explicitlyDisabled));
            assertEquals(preset, explicitlyDisabled.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(25, explicitlyDisabled.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
        assertFalse(ToolPreset.DEVELOPMENT.getDisabledTools().contains(TOOL));
        assertFalse(ToolPreset.ALL_TOOLS.getDisabledTools().contains(TOOL));
    }

    private static PreferenceStore stored(String preset, Set<String> names)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, ToolSettingsService.serializeDisabledTools(names));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 24);
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
