/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** v16 uses recorded provenance and leaves v15's screenshot opt-in independent. */
public class RouteMapMigrationTest
{
    private static final String TOOL = "business_process_route_map";

    @Test
    public void testAStoreAtVersion15GainsTheToolOnlyForReadOnlyPresets()
    {
        for (String preset : List.of("analysis-only", "code-review", "development", "all-tools", "custom"))
        {
            var store = stored(15, preset);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(preset, preset.equals("analysis-only") || preset.equals("code-review"),
                disabled(store).contains(TOOL));
            assertTrue(disabled(store).contains("owner_disabled_tool"));
            assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            assertFalse("v15 is not rerun", disabled(store).contains("get_form_screenshot"));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        }
    }

    @Test
    public void testVersion16RunsAfterVersion15InOnePassAndOneSetWrite()
    {
        var store = stored(14, "code-review");
        var writes = new ArrayList<String>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool", "get_form_screenshot", TOOL), disabled(store));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        assertEquals(List.of(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.PREF_TOOL_PREFS_MIGRATION), writes);
    }

    @Test
    public void testVersion16RunsOnlyOnce()
    {
        var store = stored(15, "analysis-only");
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(disabled(store).contains(TOOL));
        var writes = new ArrayList<String>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        ToolSettingsService.ensureMigratedForTest(store);
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(writes.isEmpty());
    }

    @Test
    public void testAReenabledRouteMapToolStaysEnabledAfterVersion16()
    {
        var store = stored(15, "code-review");
        ToolSettingsService.ensureMigratedForTest(store);
        assertTrue(disabled(store).contains(TOOL));
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled_tool");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(Set.of("owner_disabled_tool"), disabled(store));
    }

    @Test
    public void testBothMigrationRegistriesDeclareVersion16WithoutChangingOlderSteps()
    {
        assertEquals(Set.of(TOOL), ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION.get(16));
        assertEquals(Set.of(TOOL), ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION.get(16));
        assertEquals(Set.of("get_form_screenshot"),
            ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION.get(15));
        assertEquals(16, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
    }

    private static PreferenceStore stored(int version, String preset)
    {
        var store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled_tool");
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
