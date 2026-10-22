/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Actual historical snapshots and the one-shot v13 recognition contract. */
public class ToolPresetLegacyRecognitionTest
{
    private static final String PRESET_ID = "mcpToolPresetId";

    // The v4 Code Review builder evaluated with ToolGroup and defaults from commit 0dccaed3.
    // Keep these literals independent of the live groups, live presets and migration maps.
    private static final Set<String> CODE_REVIEW_V4 = Set.of(
        "adopt_metadata_object", "apply_quick_fix", "ask_workmate",
        "build_external_objects", "create_infobase", "create_launch_config",
        "create_metadata", "debug_launch", "debug_status",
        "debug_yaxunit_tests", "delete_infobase", "delete_launch_config",
        "delete_metadata", "evaluate_expression", "export_configuration_to_xml",
        "generate_translation_strings", "get_applications", "get_profiling_results",
        "get_translation_project_info", "get_variables", "git",
        "import_configuration_from_xml", "list_breakpoints", "list_configurations",
        "modify_metadata", "remove_breakpoint", "rename_metadata_object",
        "resume", "run_yaxunit_tests", "set_breakpoint",
        "set_infobase_credentials", "set_variable", "start_profiling",
        "step", "stop_profiling", "terminate_launch",
        "translate_configuration", "update_database", "wait_for_break",
        "write_module_source");

    // The v7 Analysis Only builder evaluated with ToolGroup and defaults from commit 42be37ae.
    private static final Set<String> ANALYSIS_ONLY_V7 = Set.of(
        "adopt_metadata_object", "apply_quick_fix", "ask_workmate",
        "build_external_objects", "cancel_job",
        "create_infobase", "create_launch_config", "create_metadata",
        "dcs", "debug_status", "debug_yaxunit_tests",
        "delete_infobase", "delete_launch_config", "delete_metadata",
        "delete_project", "evaluate_expression", "export_configuration_to_xml",
        "generate_translation_strings", "get_applications", "get_form_layout_snapshot",
        "get_form_screenshot", "get_job_status", "get_method_call_hierarchy",
        "get_module_structure", "get_outgoing_structures", "get_profiling_results",
        "get_symbol_info", "get_template_screenshot", "get_translation_project_info",
        "get_variables", "git", "go_to_definition",
        "import_configuration_from_xml", "launch", "list_breakpoints",
        "list_configurations", "list_modules", "merge_rules",
        "modify_metadata", "read_method_source", "read_module_source",
        "remove_breakpoint", "rename_metadata_object", "resume",
        "run_yaxunit_tests", "search_in_code", "set_breakpoint",
        "set_error_breakpoint", "set_infobase_credentials", "set_variable",
        "start_profiling", "step", "stop_profiling",
        "terminate_launch", "translate_configuration", "update_database",
        "validate_query", "wait_for_break", "write_module_source");

    @Test
    public void testRealVersion4CodeReviewReceivesOwedAdditionsAndIdentity()
    {
        assertFalse(CODE_REVIEW_V4.contains("dcs"));
        assertFalse(CODE_REVIEW_V4.contains("get_job_status"));
        PreferenceStore store = stored(CODE_REVIEW_V4, 4);
        ToolSettingsService.ensureMigratedForTest(store);
        Set<String> expected = new HashSet<>(CODE_REVIEW_V4);
        expected.remove("debug_launch");
        expected.addAll(Set.of("launch", "set_error_breakpoint", "merge_rules", "delete_project",
            "infobase_sessions", "cancel_job", "delete_infobase", "delete_metadata", "debug_pause",
            "import_project_from_file", "export_configuration_to_file"));
        expected.addAll(Set.of("switch_git_branch", "create_git_branch", "set_branch_infobase",
            "create_project", "resync_to_disk", "clean_project", "get_form_screenshot",
            "business_process_route_map"));
        assertEquals(expected, disabled(store));
        assertEquals("code-review", store.getString(PRESET_ID));
        assertFalse(disabled(store).contains("dcs"));
        assertFalse(disabled(store).contains("get_job_status"));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testRealVersion7AnalysisOnlyWithoutGroupGrowthKeepsIdentity()
    {
        assertFalse(ANALYSIS_ONLY_V7.contains("validate_form_model"));
        PreferenceStore store = stored(ANALYSIS_ONLY_V7, 7);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("analysis-only", store.getString(PRESET_ID));
        assertFalse(disabled(store).contains("validate_form_model"));
        assertTrue(disabled(store).contains("export_configuration_to_file"));
        assertTrue(disabled(store).contains("switch_git_branch"));
    }

    @Test
    public void testIssueExampleWithoutCancelJobRecordsCodeReviewAndChangesNoTools()
    {
        Set<String> before = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        before.remove("cancel_job");
        assertStampWithOnlyDefaultOffAddition(before, "code-review");
    }

    @Test
    public void testBareNestedShapeRecordsAnalysisOnlyBeforeCodeReview()
    {
        assertStampThenOneOwedWrite(ToolSettingsService.ANALYSIS_ONLY_RECOGNITION_SHAPE, "analysis-only");
    }

    @Test
    public void testBareCodeReviewShapeWithStaleNameIsRecognized()
    {
        Set<String> before = new HashSet<>(ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE);
        before.add("obsolete_disabled_tool");
        assertStampThenOneOwedWrite(before, "code-review");
    }

    @Test
    public void testBareDevelopmentShapeWithStaleNameIsRecognized()
    {
        Set<String> before = new HashSet<>(ToolSettingsService.DEVELOPMENT_RECOGNITION_SHAPE);
        before.add("renamed_away_tool");
        assertStampWithOnlyDefaultOffAddition(before, "development");
    }

    @Test
    public void testEmptyLegacySetHasNoPresetPromise()
    {
        assertStampWithOnlyDefaultOffAddition(Set.of(), "custom");
    }

    @Test
    public void testIncompleteShapeHasNoPresetPromise()
    {
        Set<String> before = new HashSet<>(ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE);
        before.remove("wait_for_break");
        assertStampWithOnlyDefaultOffAddition(before, "custom");
    }

    @Test
    public void testFirstReleaseSnapshotIsRecognizedAfterLaunchRename()
    {
        PreferenceStore store = stored(CODE_REVIEW_V4, 0);
        store.setToDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertFalse(disabled(store).contains("debug_launch"));
        assertTrue(disabled(store).contains("launch"));
    }

    @Test
    public void testExistingIdentityIsNeverOverwrittenByTheStamp()
    {
        PreferenceStore store = stored(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), 12);
        store.setValue(PRESET_ID, "custom");
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("custom", store.getString(PRESET_ID));
    }

    private static void assertStampThenOneOwedWrite(Set<String> before, String id)
    {
        PreferenceStore store = stored(before, 12);
        Set<String> expected = new HashSet<>(before);
        expected.addAll(Set.of("switch_git_branch", "create_git_branch", "set_branch_infobase",
            "create_project", "resync_to_disk", "clean_project", "get_form_screenshot",
            "business_process_route_map"));
        int[] disabledWrites = {0};
        store.addPropertyChangeListener(event -> {
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                assertEquals("provenance must be stamped before writing the owed tools",
                    id, store.getString(PRESET_ID));
                disabledWrites[0]++;
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(id, store.getString(PRESET_ID));
        assertEquals(expected, disabled(store));
        assertEquals(1, disabledWrites[0]);
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        int[] writes = {0};
        store.addPropertyChangeListener(event -> writes[0]++);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("the stamp and owed additions are one-shot", 0, writes[0]);
    }

    private static void assertStampWithOnlyDefaultOffAddition(Set<String> before, String id)
    {
        PreferenceStore store = stored(before, 12);
        Set<String> expected = new HashSet<>(before);
        expected.add("get_form_screenshot");
        String serialized = ToolSettingsService.serializeDisabledTools(expected);
        int[] disabledWrites = {0};
        store.addPropertyChangeListener(event -> {
            if (PreferenceConstants.PREF_DISABLED_TOOLS.equals(event.getProperty()))
            {
                disabledWrites[0]++;
            }
        });
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals(id, store.getString(PRESET_ID));
        assertEquals(serialized, store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertEquals(expected, disabled(store));
        assertEquals("only the universal v15 addition may write this set",
            expected.equals(before) ? 0 : 1, disabledWrites[0]);
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        int[] writes = {0};
        store.addPropertyChangeListener(event -> writes[0]++);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("the stamp is one-shot", 0, writes[0]);
    }

    private static PreferenceStore stored(Set<String> disabled, int version)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(disabled));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, version);
        return store;
    }

    private static Set<String> disabled(PreferenceStore store)
    {
        return ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
