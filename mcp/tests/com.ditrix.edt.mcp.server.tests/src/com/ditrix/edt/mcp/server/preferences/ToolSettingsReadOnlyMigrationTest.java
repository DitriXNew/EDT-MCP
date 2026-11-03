/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Version 14 owes six writers to recorded read-only profiles, once, retaining owner edits. */
public class ToolSettingsReadOnlyMigrationTest
{
    private static final Set<String> WRITERS = Set.of(
        "switch_git_branch", "create_git_branch", "set_branch_infobase",
        "create_project", "resync_to_disk", "clean_project");

    @Test
    public void testVersion14AddsTheWorkspaceAndGitWritersToAStoredAnalysisOnlyPreset()
    {
        assertVersion14RestoresCurrentPreset(ToolPreset.ANALYSIS_ONLY);
    }

    @Test
    public void testVersion14AddsTheWorkspaceAndGitWritersToAStoredCodeReviewPreset()
    {
        assertVersion14RestoresCurrentPreset(ToolPreset.CODE_REVIEW);
    }

    @Test
    public void testVersion14HonoursProvenanceDespiteOwnerEdits()
    {
        Set<String> before = previousPreset(ToolPreset.CODE_REVIEW);
        before.remove("apply_quick_fix");
        before.remove("wait_for_break"); // Break the legacy shape: provenance must suffice.
        before.add("get_edt_version");
        before.add("obsolete_owner_disabled_tool");
        PreferenceStore store = stored(before, 13, "code-review");
        ToolSettingsService.ensureMigratedForTest(store);
        Set<String> expected = new HashSet<>(before);
        expected.addAll(WRITERS);
        expected.add("get_form_screenshot"); // The universal v15 addition follows v14.
        ToolMigrationTestSupport.addReadOnlyAdditionsAfter(14, ToolPreset.CODE_REVIEW, expected);
        assertEquals(expected, disabled(store));
        assertEquals("code-review", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    @Test
    public void testVersion14UsesRecordedReadOnlyPromiseWithoutAnyLegacyShape()
    {
        for (String id : List.of("analysis-only", "code-review"))
        {
            Set<String> before = Set.of("owner_disabled_tool");
            PreferenceStore store = stored(before, 13, id);
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = new HashSet<>(before);
            expected.addAll(WRITERS);
            expected.add("get_form_screenshot"); // The universal v15 addition follows v14.
            ToolMigrationTestSupport.addReadOnlyAdditionsAfter(14, ToolPreset.fromId(id), expected);
            assertEquals(id, expected, disabled(store));
        }
    }

    @Test
    public void testVersion14LeavesAStoredDevelopmentProfileAlone()
    {
        assertUnchanged(ToolPreset.DEVELOPMENT.getDisabledTools(), "development");
    }

    @Test
    public void testVersion14LeavesACustomProfileAloneEvenWhenItLooksReadOnly()
    {
        assertUnchanged(previousPreset(ToolPreset.CODE_REVIEW), "custom");
    }

    @Test
    public void testVersion14LeavesAnAllToolsProfileAlone()
    {
        assertUnchanged(Set.of(), "all-tools");
    }

    @Test
    public void testVersion14LeavesAnUnknownProfileAlone()
    {
        assertUnchanged(previousPreset(ToolPreset.ANALYSIS_ONLY), "future-preset");
    }

    @Test
    public void testVersion14LeavesAVersion13StoreWithoutProvenanceAlone()
    {
        assertUnchanged(previousPreset(ToolPreset.ANALYSIS_ONLY), null);
    }

    @Test
    public void testVersion14RunsAfterTheVersion13StampInOnePass()
    {
        for (ToolPreset preset : List.of(ToolPreset.ANALYSIS_ONLY, ToolPreset.CODE_REVIEW))
        {
            Set<String> before = preset == ToolPreset.ANALYSIS_ONLY
                ? ToolSettingsService.ANALYSIS_ONLY_RECOGNITION_SHAPE
                : ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE;
            PreferenceStore store = stored(before, 12, null);
            List<String> writes = new ArrayList<>();
            store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = new HashSet<>(before);
            expected.addAll(WRITERS);
            expected.add("get_form_screenshot"); // The universal v15 addition follows v14.
            ToolMigrationTestSupport.addReadOnlyAdditionsAfter(14, preset, expected);
            assertEquals(preset.name(), expected, disabled(store));
            assertEquals(preset.getId(), store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals("stamp, then exactly one owed set write, then version",
                List.of(PreferenceConstants.PREF_TOOL_PRESET_ID, PreferenceConstants.PREF_DISABLED_TOOLS,
                    PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
            writes.clear();
            ToolSettingsService.ensureMigratedForTest(store);
            assertTrue("second migration must be write-free", writes.isEmpty());
        }
    }

    @Test
    public void testVersion14CombinesLegacyAndNewAdditionsInOneDisabledSetWrite()
    {
        Set<String> before = new HashSet<>(ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE);
        // The frozen v11 guard treats a re-enabled XML importer as an intentional opt-in.
        before.add("import_configuration_from_xml");
        PreferenceStore store = stored(before, 0, null);
        int[] disabledWrites = {0};
        store.addPropertyChangeListener(event -> {
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                disabledWrites[0]++;
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(disabled(store).containsAll(WRITERS));
        assertTrue(disabled(store).containsAll(Set.of("git", "ask_workmate", "apply_quick_fix",
            "debug_pause", "import_project_from_file", "export_configuration_to_file")));
        assertEquals("code-review", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        assertEquals(1, disabledWrites[0]);
    }

    @Test
    public void testVersion14DoesNotRunTwice()
    {
        Set<String> edited = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        edited.remove("switch_git_branch");
        PreferenceStore store = stored(edited, 14, "analysis-only");
        int[] disabledWrites = {0};
        store.addPropertyChangeListener(event -> {
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                disabledWrites[0]++;
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("an already migrated owner re-enable survives this and future migrations",
            edited, disabled(store));
        assertEquals(0, disabledWrites[0]);
    }

    private static void assertVersion14RestoresCurrentPreset(ToolPreset preset)
    {
        Set<String> before = previousPreset(preset);
        PreferenceStore store = stored(before, 13, preset.getId());
        ToolSettingsService.ensureMigratedForTest(store);
        Set<String> expected = new HashSet<>(before);
        expected.addAll(WRITERS);
        expected.add("get_form_screenshot"); // The universal v15 addition follows v14.
        ToolMigrationTestSupport.addReadOnlyAdditionsAfter(14, preset, expected);
        assertEquals(preset.name(), expected, disabled(store));
        assertEquals(preset.getDisabledTools(), disabled(store));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        assertEquals(preset.getId(), store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    private static void assertUnchanged(Set<String> before, String id)
    {
        PreferenceStore store = stored(before, 13, id);
        Set<String> expected = new HashSet<>(before);
        expected.add("get_form_screenshot");
        String serialized = ToolSettingsService.serializeDisabledTools(expected);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(expected, disabled(store));
        assertEquals(serialized, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(id != null, store.contains(PreferenceConstants.PREF_TOOL_PRESET_ID));
        if (id != null)
        {
            assertEquals(id, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        }
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    private static Set<String> previousPreset(ToolPreset preset)
    {
        Set<String> result = new HashSet<>(preset.getDisabledTools());
        result.removeAll(WRITERS);
        result.remove("business_process_route_map");
        result.remove("project_checks");
        return result;
    }

    private static PreferenceStore stored(Set<String> disabled, int version, String id)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(disabled));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        if (id != null)
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
