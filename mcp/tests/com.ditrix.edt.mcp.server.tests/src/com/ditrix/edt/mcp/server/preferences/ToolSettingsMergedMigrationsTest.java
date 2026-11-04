/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Independent boundary fixtures for the v16-v22 preset migrations. */
public class ToolSettingsMergedMigrationsTest
{
    @Test
    public void version15ReadOnlyStoresGainExactlyAllLaterTools()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(15, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = new HashSet<>(Set.of("owner_disabled", "obsolete_owner_tool",
                "business_process_route_map", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "edt_dialog"));
            if (preset.equals("analysis-only"))
            {
                expected.add("open_editor");
            }
            assertEquals(preset, expected, disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void version16ReadOnlyStoresGainLaterToolsAndKeepRouteMapOptIn()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(16, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = preset.equals("analysis-only")
                ? Set.of("owner_disabled", "obsolete_owner_tool", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "open_editor", "edt_dialog")
                : Set.of("owner_disabled", "obsolete_owner_tool", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "edt_dialog");
            assertEquals(preset, expected, disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void version16ReadOnlyStoresKeepAnExistingRouteMapDisable()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(16, preset, Set.of("owner_disabled", "business_process_route_map"));
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = preset.equals("analysis-only")
                ? Set.of("owner_disabled", "business_process_route_map", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "open_editor", "edt_dialog")
                : Set.of("owner_disabled", "business_process_route_map", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "edt_dialog");
            assertEquals(expected, disabled(store));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void bothRegistriesKeepSeparateLiteralVersion16Through22Obligations()
    {
        assertEquals(22, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
        for (var additions : List.of(ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION,
            ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION))
        {
            assertEquals(Set.of("get_form_screenshot"), additions.get(15));
            assertEquals(Set.of("business_process_route_map"), additions.get(16));
            assertEquals(Set.of("project_checks"), additions.get(17));
            assertEquals(Set.of("infobase_extensions"), additions.get(18));
            assertEquals(Set.of("clone_metadata_object"), additions.get(20));
            assertEquals(Set.of("sort_metadata"), additions.get(21));
            assertEquals(Set.of("edt_dialog"), additions.get(22));
        }
        assertEquals(Set.of("open_editor"),
            ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION.get(19));
        assertFalse(ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION.containsKey(19));
    }

    @Test
    public void version15MigrationsPublishOneCombinedDisabledSetThenCurrentVersion()
    {
        PreferenceStore store = stored(15, "analysis-only", Set.of("owner_disabled"));
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
        assertEquals(List.of(Set.of("owner_disabled", "business_process_route_map", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "open_editor", "edt_dialog")),
            published);
        assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
        assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        writes.clear();
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue("the combined migration is one-shot", writes.isEmpty());
    }

    @Test
    public void anAlreadyCompleteDisabledSetNeedsOnlyTheVersionStamp()
    {
        PreferenceStore store = stored(15, "analysis-only",
            Set.of("owner_disabled", "business_process_route_map", "project_checks", "infobase_extensions", "clone_metadata_object", "sort_metadata", "open_editor", "edt_dialog"));
        String before = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(before, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(List.of(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
        assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void version19PreservesExtensionAndNavigationOptInsAndAddsCloneAndSort()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(19, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled", "obsolete_owner_tool", "clone_metadata_object", "sort_metadata", "edt_dialog"), disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
                PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version20PreservesOlderOptInsAndAddsSortMetadataAndEdtDialog()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(20, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
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
            assertEquals(Set.of("owner_disabled", "obsolete_owner_tool", "sort_metadata", "edt_dialog"), disabled(store));
            assertEquals("edt_dialog,obsolete_owner_tool,owner_disabled,sort_metadata",
                store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
            assertEquals(List.of(Set.of("owner_disabled", "obsolete_owner_tool", "sort_metadata", "edt_dialog")), published);
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
                PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version21AddsOnlyEdtDialogAndRetainsOlderOptIns()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(21, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled", "obsolete_owner_tool", "edt_dialog"), disabled(store));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
                PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version22RetainsOwnerOptInsWithoutWritingAgain()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(22, preset, Set.of("owner_disabled"));
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(Set.of("owner_disabled"), disabled(store));
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version18PreservesExtensionOptInsAndAddsOnlyLaterObligations()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(18, preset, Set.of("owner_disabled", "obsolete_owner_tool"));
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = preset.equals("analysis-only")
                ? Set.of("owner_disabled", "obsolete_owner_tool", "open_editor", "clone_metadata_object", "sort_metadata", "edt_dialog")
                : Set.of("owner_disabled", "obsolete_owner_tool", "clone_metadata_object", "sort_metadata", "edt_dialog");
            assertEquals(preset, expected, disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
                PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    public void version17ReadOnlyStoresKeepExtensionNavigationCloneAndSortDisabled()
    {
        for (String preset : List.of("analysis-only", "code-review"))
        {
            PreferenceStore store = stored(17, preset, Set.of("owner_disabled", "open_editor"));
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(preset, Set.of("owner_disabled", "open_editor", "infobase_extensions", "clone_metadata_object", "sort_metadata", "edt_dialog"), disabled(store));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test
    public void newerStepsLeaveOtherOrMissingPresetPromisesUnchanged()
    {
        for (int version : List.of(15, 16, 17, 18, 19, 20, 21))
        {
            for (String preset : List.of("development", "all-tools", "custom", "future-preset", ""))
            {
                PreferenceStore store = stored(version, preset, Set.of("owner_disabled"));
                ToolSettingsService.ensureMigratedForTest(store);
                assertEquals(preset, Set.of("owner_disabled"), disabled(store));
                assertEquals(!preset.isEmpty(), store.contains(PreferenceConstants.PREF_TOOL_PRESET_ID));
                assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
                assertEquals(22, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            }
        }
    }

    private static PreferenceStore stored(int version, String preset, Set<String> disabled)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, ToolSettingsService.serializeDisabledTools(disabled));
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
