/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.SseStreamRegistry;

/**
 * Preset provenance regressions using in-memory stores, with no running EDT or preference files.
 * The persisted tokens are literal assertions so this class also compiles and fails on the base.
 */
public class ToolSettingsProvenanceTest
{
    private static final String PRESET_ID = "mcpToolPresetId"; //$NON-NLS-1$
    private static final String EXPORT = "export_configuration_to_file"; //$NON-NLS-1$

    // Independent additions for a synthetic compatibility matrix; real historical fixtures live
    // in ToolPresetLegacyRecognitionTest and must not be derived from the live presets.
    private static final Map<Integer, Set<String>> CODE_REVIEW_ADDITIONS = Map.ofEntries(
        Map.entry(1, Set.of("git")),
        Map.entry(2, Set.of("apply_quick_fix")),
        Map.entry(3, Set.of("ask_workmate")),
        Map.entry(4, Set.of("adopt_metadata_object", "build_external_objects",
            "set_infobase_credentials", "stop_profiling")),
        Map.entry(6, Set.of("set_error_breakpoint")),
        Map.entry(7, Set.of("merge_rules", "delete_project")),
        Map.entry(8, Set.of("infobase_sessions")),
        Map.entry(9, Set.of("cancel_job", "delete_infobase", "delete_metadata")),
        Map.entry(10, Set.of("debug_pause")),
        Map.entry(11, Set.of("import_project_from_file")),
        Map.entry(12, Set.of(EXPORT)),
        Map.entry(14, Set.of("switch_git_branch", "create_git_branch", "set_branch_infobase",
            "create_project", "resync_to_disk", "clean_project")),
        Map.entry(15, Set.of("get_form_screenshot")),
        Map.entry(16, Set.of("business_process_route_map")));

    private final ToolSettingsService service = ToolSettingsService.getInstance();
    private PreferenceStore store;
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        store = stored(Set.of("git", "ask_workmate"), 12);
        store.setValue(PRESET_ID, "custom");
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void testApplyingPresetsPersistsStableIdsAndTheirSets()
    {
        Map<ToolPreset, String> ids = Map.of(
            ToolPreset.ALL_TOOLS, "all-tools",
            ToolPreset.ANALYSIS_ONLY, "analysis-only",
            ToolPreset.CODE_REVIEW, "code-review",
            ToolPreset.DEVELOPMENT, "development");
        for (Map.Entry<ToolPreset, String> entry : ids.entrySet())
        {
            service.applyPreset(entry.getKey());
            assertEquals(entry.getKey().name(), entry.getValue(), store.getString(PRESET_ID));
            assertEquals(entry.getKey().getDisabledTools(), disabled(store));
        }
    }

    @Test
    public void testSelectingCustomClearsIdentityWithoutChangingTheSet()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.applyPreset(ToolPreset.CUSTOM);
        assertEquals("custom", store.getString(PRESET_ID));
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), disabled(store));
    }

    @Test
    public void testManualTighteningKeepsIdentityEvenWhileContainingThePresetShape()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.setToolEnabled("get_edt_version", false);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertTrue(disabled(store).containsAll(ToolPreset.CODE_REVIEW.getDisabledTools()));
    }

    @Test
    public void testManualReenableKeepsIdentity()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.setToolEnabled("apply_quick_fix", true);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertFalse(disabled(store).contains("apply_quick_fix"));
    }

    @Test
    public void testGroupEditKeepsIdentity()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.setGroupEnabled(ToolGroup.DEBUG, true);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertTrue(service.isGroupFullyEnabled(ToolGroup.DEBUG));
    }

    @Test
    public void testUnchangedSavePreservesIdentityAndNotifiesNobody()
    {
        store.setValue(PRESET_ID, "code-review");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(ToolPreset.CODE_REVIEW.getDisabledTools()));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SseStreamRegistry.SseStream stream = SseStreamRegistry.getInstance().register(sink);
        try
        {
            service.setDisabledTools(new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools()));
            assertEquals("code-review", store.getString(PRESET_ID));
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        }
        finally
        {
            SseStreamRegistry.getInstance().unregister(stream);
        }
    }

    @Test
    public void testManualSetMatchingAPresetDoesNotAcquireItsIdentity()
    {
        service.setDisabledTools(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        assertEquals("custom", store.getString(PRESET_ID));
        assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), disabled(store));
    }

    @Test
    public void testReselectingPresetChangesOnlyProvenanceWithoutANotification()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.applyPreset(ToolPreset.CUSTOM);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SseStreamRegistry.SseStream stream = SseStreamRegistry.getInstance().register(sink);
        try
        {
            service.applyPreset(ToolPreset.CODE_REVIEW);
            assertEquals("code-review", store.getString(PRESET_ID));
            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        }
        finally
        {
            SseStreamRegistry.getInstance().unregister(stream);
        }
    }

    @Test
    public void testVersion13StampsLegacyPresetsOnceWithoutChangingTheirSets()
    {
        Map<ToolPreset, String> ids = Map.of(
            ToolPreset.ANALYSIS_ONLY, "analysis-only",
            ToolPreset.CODE_REVIEW, "code-review",
            ToolPreset.DEVELOPMENT, "development");
        for (Map.Entry<ToolPreset, String> entry : ids.entrySet())
        {
            PreferenceStore legacy = stored(entry.getKey().getDisabledTools(), 12);
            ToolSettingsService.ensureMigratedForTest(legacy);
            assertEquals(entry.getValue(), legacy.getString(PRESET_ID));
            assertEquals(entry.getKey().getDisabledTools(), disabled(legacy));
            assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
                legacy.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
            int[] writes = {0};
            legacy.addPropertyChangeListener(event -> writes[0]++);
            ToolSettingsService.ensureMigratedForTest(legacy);
            assertEquals("second migration must perform no writes", 0, writes[0]);
        }
    }

    @Test
    public void testLegacySupersetRecordsIdentityAfterReceivingOwedSafetyMigration()
    {
        Set<String> edited = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        edited.remove(EXPORT);
        edited.add("get_edt_version");
        PreferenceStore legacy = stored(edited, 11);
        ToolSettingsService.ensureMigratedForTest(legacy);
        edited.add(EXPORT);
        assertEquals(edited, disabled(legacy));
        assertEquals("code-review", legacy.getString(PRESET_ID));
    }

    @Test
    public void testLegacyStaleNamesDoNotBlockProvenance()
    {
        Set<String> edited = new HashSet<>(ToolPreset.DEVELOPMENT.getDisabledTools());
        edited.add("obsolete_user_disabled_tool");
        PreferenceStore legacy = stored(edited, 12);
        ToolSettingsService.ensureMigratedForTest(legacy);
        assertEquals(edited, disabled(legacy));
        assertEquals("development", legacy.getString(PRESET_ID));
    }

    @Test
    public void testLegacyReenabledAdditionDoesNotBlockProvenance()
    {
        Set<String> edited = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        edited.remove("apply_quick_fix");
        PreferenceStore legacy = stored(edited, 12);
        ToolSettingsService.ensureMigratedForTest(legacy);
        assertEquals(edited, disabled(legacy));
        assertEquals("code-review", legacy.getString(PRESET_ID));
    }

    @Test
    public void testSyntheticCurrentPresetMinusAdditionsKeepsLegacyMigrations()
    {
        for (int version = 0; version <= 12; version++)
        {
            Set<String> before = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
            for (Map.Entry<Integer, Set<String>> entry : CODE_REVIEW_ADDITIONS.entrySet())
            {
                if (entry.getKey() > version)
                {
                    before.removeAll(entry.getValue());
                }
            }
            if (version < 5)
            {
                before.remove("launch");
                before.add("debug_launch");
            }
            PreferenceStore legacy = stored(before, version);
            ToolSettingsService.ensureMigratedForTest(legacy);
            assertEquals("legacy version " + version,
                ToolPreset.CODE_REVIEW.getDisabledTools(), disabled(legacy));
            assertEquals("legacy version " + version, "code-review", legacy.getString(PRESET_ID));
        }
    }

    @Test
    public void testFreshDefaultsStayDefaultValuedAndCustom()
    {
        PreferenceStore fresh = new PreferenceStore();
        fresh.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        ToolSettingsService.ensureMigratedForTest(fresh);
        assertEquals(Set.of("git", "ask_workmate", "get_form_screenshot"), disabled(fresh));
        assertTrue(fresh.isDefault(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse(fresh.contains(PRESET_ID));
    }

    @Test
    public void testPreVersion13CustomKeepsFrozenLegacyMigrations()
    {
        assertPreVersion13IdentityLeavesLegacyStepsFrozen("custom");
    }

    @Test
    public void testPreVersion13UnknownIdKeepsFrozenLegacyMigrations()
    {
        assertPreVersion13IdentityLeavesLegacyStepsFrozen("future-preset-id");
    }

    @Test
    public void testPreVersion13AllToolsIdKeepsFrozenLegacyMigrations()
    {
        assertPreVersion13IdentityLeavesLegacyStepsFrozen("all-tools");
    }

    @Test
    public void testPreVersion13RecordedIdentityDoesNotReplaceLegacyRecognition()
    {
        PreferenceStore recorded = stored(Set.of("user_disabled_tool"), 1);
        recorded.setValue(PRESET_ID, "code-review");
        ToolSettingsService.ensureMigratedForTest(recorded);
        // Recorded identity drives v14/v16; the unrecognized shape gets no v1-v12 preset additions.
        Set<String> expected = new HashSet<>(Set.of("user_disabled_tool", "ask_workmate", "get_form_screenshot"));
        expected.addAll(CODE_REVIEW_ADDITIONS.get(14));
        expected.addAll(CODE_REVIEW_ADDITIONS.get(16));
        assertEquals(expected, disabled(recorded));
        assertEquals("code-review", recorded.getString(PRESET_ID));
    }

    @Test
    public void testPreVersion13DevelopmentIdentityDoesNotReplaceLegacyRecognition()
    {
        PreferenceStore recorded = stored(Set.of("user_disabled_tool"), 3);
        recorded.setValue(PRESET_ID, "development");
        ToolSettingsService.ensureMigratedForTest(recorded);
        assertEquals(Set.of("user_disabled_tool", "get_form_screenshot"), disabled(recorded));
        assertEquals("development", recorded.getString(PRESET_ID));
    }

    @Test
    public void testOwnerEditRetainsIdentityWhenLegacyMigrationIsReplayed()
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        service.setToolEnabled("get_edt_version", false);
        Set<String> edited = new HashSet<>(disabled(store));
        edited.remove(EXPORT);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(edited));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 11);
        ToolSettingsService.ensureMigratedForTest(store);
        edited.add(EXPORT);
        assertEquals(edited, disabled(store));
        assertEquals("code-review", store.getString(PRESET_ID));
    }

    @Test
    public void testCurrentStoreWithoutIdNeverRunsLegacyInferenceAgain()
    {
        PreferenceStore current = stored(ToolPreset.CODE_REVIEW.getDisabledTools(), 13);
        ToolSettingsService.ensureMigratedForTest(current);
        assertFalse(current.contains(PRESET_ID));
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), disabled(current));
    }

    @Test
    public void testProvenanceAccessorHandlesSelectionsUnknownIdsAndNoStore() throws Exception
    {
        assertEquals("custom", readPresetId());
        service.applyPreset(ToolPreset.DEVELOPMENT);
        assertEquals("development", readPresetId());
        store.setValue(PRESET_ID, "unknown-future-id");
        assertEquals("custom", readPresetId());
        plugin.set(null, null);
        assertEquals("custom", readPresetId());
    }

    @Test
    public void testExplicitPresetRecordsChoiceIndependentlyOfDisabledSet() throws Exception
    {
        service.applyPreset(ToolPreset.CODE_REVIEW);
        Set<String> edited = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        edited.add("get_edt_version");
        try
        {
            ToolSettingsService.class.getMethod("setDisabledTools", Set.class, ToolPreset.class)
                .invoke(service, edited, ToolPreset.CODE_REVIEW);
        }
        catch (NoSuchMethodException e)
        {
            throw new AssertionError("An explicit preset write must record the chosen provenance", e);
        }
        assertEquals(edited, disabled(store));
        assertEquals("code-review", store.getString(PRESET_ID));
    }

    /** Reflection lets the regression compile on base, where the new accessor is absent. */
    private String readPresetId() throws Exception
    {
        try
        {
            return (String)ToolSettingsService.class.getMethod("getPresetId").invoke(service);
        }
        catch (NoSuchMethodException e)
        {
            throw new AssertionError("Preset provenance must have a service accessor", e);
        }
    }

    private static void assertPreVersion13IdentityLeavesLegacyStepsFrozen(String id)
    {
        Set<String> before = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        before.remove(EXPORT);
        before.remove("debug_pause");
        before.remove("import_project_from_file");
        before.add("get_edt_version");
        PreferenceStore recorded = stored(before, 9);
        recorded.setValue(PRESET_ID, id);
        ToolSettingsService.ensureMigratedForTest(recorded);
        Set<String> expected = new HashSet<>(before);
        expected.addAll(Set.of("debug_pause", "import_project_from_file", EXPORT));
        assertEquals("legacy steps must remain frozen for " + id, expected, disabled(recorded));
        assertEquals(id, recorded.getString(PRESET_ID));
    }

    private static PreferenceStore stored(Set<String> disabled, int version)
    {
        PreferenceStore result = new PreferenceStore();
        result.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        result.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(disabled));
        result.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        return result;
    }

    private static Set<String> disabled(PreferenceStore preferences)
    {
        return ToolSettingsService.parseDisabledTools(
            preferences.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
