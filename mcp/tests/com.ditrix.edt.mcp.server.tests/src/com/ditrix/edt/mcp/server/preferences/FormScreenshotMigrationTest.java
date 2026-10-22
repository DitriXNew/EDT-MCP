/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** The v15 opt-in applies once to every saved profile, after the frozen v14 step. */
public class FormScreenshotMigrationTest
{
    private static final String SCREENSHOT = "get_form_screenshot";

    @Test
    public void testVersion14CustomStoreGainsScreenshotWithoutLosingOwnerEdits()
    {
        PreferenceStore store = stored(Set.of("owner_disabled_tool"), 14, "custom");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool", SCREENSHOT), disabled(store));
        assertEquals(15, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        assertEquals("custom", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    @Test
    public void testVersion14AllToolsStoreAlsoGainsScreenshot()
    {
        PreferenceStore store = stored(Set.of(), 14, "all-tools");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of(SCREENSHOT), disabled(store));
        assertEquals("all-tools", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    @Test
    public void testVersion15RunsAfterVersion14InOnePassAndOneSetWrite()
    {
        PreferenceStore store = stored(Set.of("owner_disabled_tool"), 13, "code-review");
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool", SCREENSHOT, "switch_git_branch", "create_git_branch",
            "set_branch_infobase", "create_project", "resync_to_disk", "clean_project"), disabled(store));
        assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
        assertEquals(15, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testVersion15RunsOnlyOnce()
    {
        PreferenceStore store = stored(Set.of("owner_disabled_tool"), 14, "custom");
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(disabled(store).contains(SCREENSHOT));
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue("already stamped reads must be write-free", writes.isEmpty());
    }

    @Test
    public void testReenabledScreenshotStaysEnabledAfterVersion15Stamp()
    {
        PreferenceStore store = stored(Set.of("owner_disabled_tool"), 14, "custom");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(15, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled_tool");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool"), disabled(store));
    }

    @Test
    public void testOlderOptInsAreNotReappliedByVersion15()
    {
        PreferenceStore store = stored(Set.of("owner_disabled_tool"), 14, "development");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool", SCREENSHOT), disabled(store));
        assertEquals("development", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    @Test
    public void testFreshStoreUsesDefaultWithoutPersistingAList()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("git", "ask_workmate", SCREENSHOT), disabled(store));
        assertTrue(store.isDefault(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse(store.contains(PreferenceConstants.PREF_TOOL_PRESET_ID));
        assertEquals(15, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testOnlyAllToolsPresetOptsIntoScreenshot()
    {
        for (ToolPreset preset : List.of(ToolPreset.ANALYSIS_ONLY, ToolPreset.CODE_REVIEW, ToolPreset.DEVELOPMENT))
        {
            assertTrue(preset.name(), preset.getDisabledTools().contains(SCREENSHOT));
        }
        assertFalse(ToolPreset.ALL_TOOLS.getDisabledTools().contains(SCREENSHOT));
    }

    private static PreferenceStore stored(Set<String> disabled, int version, String preset)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, ToolSettingsService.serializeDisabledTools(disabled));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, preset);
        return store;
    }

    private static Set<String> disabled(PreferenceStore store)
    {
        return new HashSet<>(ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)));
    }
}
