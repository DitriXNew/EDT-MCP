/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Literal v20 boundary fixtures keep the new obligation independent of preset-derived sets. */
public class CloneMetadataObjectMigrationTest
{
    @Test
    public void version20RegistersOnlyTheCloneToolAndKeepsPriorSteps()
    {
        assertEquals(22, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
        for (var additions : List.of(ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION,
            ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION))
        {
            assertEquals(Set.of("business_process_route_map"), additions.get(16));
            assertEquals(Set.of("project_checks"), additions.get(17));
            assertEquals(Set.of("infobase_extensions"), additions.get(18));
            assertEquals(Set.of("clone_metadata_object"), additions.get(20));
        }
    }

    @Test
    public void version19ReadOnlyStoresGainCloneAndSortToolsAndRetainOwnerEdits()
    {
        for (String id : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(19, id, Set.of("owner_disabled", "obsolete_owner_tool"));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled", "obsolete_owner_tool", "clone_metadata_object", "sort_metadata", "edt_dialog"), disabled(store));
            assertEquals(id, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void version18PublishesTheCombinedVersion19Through22AdditionsOnce()
    {
        PreferenceStore store = stored(18, "analysis-only", Set.of("owner_disabled"));
        List<String> writes = new ArrayList<>();
        List<Set<String>> published = new ArrayList<>();
        store.addPropertyChangeListener(event -> {
            writes.add(event.getProperty());
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                published.add(Set.copyOf(disabled(store)));
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(List.of(Set.of("owner_disabled", "open_editor", "clone_metadata_object", "sort_metadata", "edt_dialog")), published);
        assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
        assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        writes.clear();
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void nonReadOnlyAndMissingProvenanceNeverInferAPresetFromItsShape()
    {
        for (String id : List.of("development", "all-tools", "custom", "future-preset", ""))
        {
            PreferenceStore store = stored(19, id, ToolSettingsService.ANALYSIS_ONLY_RECOGNITION_SHAPE);
            String before = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(before, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
            assertEquals(!id.isEmpty(), store.contains(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(id, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void ownerReenableAtVersion22SurvivesWithoutAnyFurtherWrites()
    {
        for (String id : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(19, id, Set.of("owner_disabled"));
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue(disabled(store).contains("clone_metadata_object"));
            assertTrue(disabled(store).contains("sort_metadata"));
            store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "obsolete_owner_tool, owner_disabled");
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals("obsolete_owner_tool, owner_disabled", store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
            assertTrue(writes.isEmpty());
            assertEquals(id, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        }
    }

    @Test
    public void alreadyDisabledLaterToolsNeedOnlyTheVersionStamp()
    {
        PreferenceStore store = stored(19, "analysis-only", Set.of("owner_disabled", "clone_metadata_object", "sort_metadata", "edt_dialog"));
        String before = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(before, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(List.of(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
        assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void defaultValuedStoreDoesNotMaterializeAnExplicitDisabledList()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "analysis-only");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 19);
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(store.isDefault(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(PreferenceConstants.DEFAULT_DISABLED_TOOLS, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(List.of(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
    }

    private static PreferenceStore stored(int version, String id, Set<String> disabled)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, ToolSettingsService.serializeDisabledTools(disabled));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        if (!id.isEmpty())
        {
            store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, id);
        }
        return store;
    }

    private static Set<String> disabled(PreferenceStore store)
    {
        return ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
