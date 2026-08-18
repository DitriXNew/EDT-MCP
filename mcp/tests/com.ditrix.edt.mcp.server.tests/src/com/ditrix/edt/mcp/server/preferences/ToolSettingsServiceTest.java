/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/**
 * Tests for {@link ToolSettingsService} static utility methods.
 * Tests the parse/serialize logic without requiring Eclipse runtime.
 */
public class ToolSettingsServiceTest
{
    private static final Set<String> ANALYSIS_ONLY_V4_ADDITIONS = Set.of(
        "adopt_metadata_object", //$NON-NLS-1$
        "build_external_objects", //$NON-NLS-1$
        "set_infobase_credentials", //$NON-NLS-1$
        "stop_profiling", //$NON-NLS-1$
        "get_outgoing_structures"); //$NON-NLS-1$

    private static final Set<String> CODE_REVIEW_V4_ADDITIONS = Set.of(
        "adopt_metadata_object", //$NON-NLS-1$
        "build_external_objects", //$NON-NLS-1$
        "set_infobase_credentials", //$NON-NLS-1$
        "stop_profiling"); //$NON-NLS-1$

    private static final Set<String> DEVELOPMENT_V4_ADDITIONS = Set.of(
        "stop_profiling"); //$NON-NLS-1$

    /* Independent first-release fixtures: never derive these from the production constants. */
    private static final Set<String> FIRST_RELEASE_ANALYSIS_ONLY_SHAPE = Set.of(
        "debug_launch", //$NON-NLS-1$
        "debug_status", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        "evaluate_expression", //$NON-NLS-1$
        "get_applications", //$NON-NLS-1$
        "get_form_screenshot", //$NON-NLS-1$
        "get_method_call_hierarchy", //$NON-NLS-1$
        "get_module_structure", //$NON-NLS-1$
        "get_profiling_results", //$NON-NLS-1$
        "get_symbol_info", //$NON-NLS-1$
        "get_variables", //$NON-NLS-1$
        "go_to_definition", //$NON-NLS-1$
        "list_breakpoints", //$NON-NLS-1$
        "list_modules", //$NON-NLS-1$
        "read_method_source", //$NON-NLS-1$
        "read_module_source", //$NON-NLS-1$
        "remove_breakpoint", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "resume", //$NON-NLS-1$
        "run_yaxunit_tests", //$NON-NLS-1$
        "search_in_code", //$NON-NLS-1$
        "set_breakpoint", //$NON-NLS-1$
        "start_profiling", //$NON-NLS-1$
        "step", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "validate_query", //$NON-NLS-1$
        "wait_for_break", //$NON-NLS-1$
        "write_module_source"); //$NON-NLS-1$

    private static final Set<String> FIRST_RELEASE_CODE_REVIEW_SHAPE = Set.of(
        "debug_launch", //$NON-NLS-1$
        "debug_status", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        "evaluate_expression", //$NON-NLS-1$
        "get_applications", //$NON-NLS-1$
        "get_profiling_results", //$NON-NLS-1$
        "get_variables", //$NON-NLS-1$
        "list_breakpoints", //$NON-NLS-1$
        "remove_breakpoint", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "resume", //$NON-NLS-1$
        "run_yaxunit_tests", //$NON-NLS-1$
        "set_breakpoint", //$NON-NLS-1$
        "start_profiling", //$NON-NLS-1$
        "step", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "wait_for_break", //$NON-NLS-1$
        "write_module_source"); //$NON-NLS-1$

    private static final Set<String> FIRST_RELEASE_DEVELOPMENT_SHAPE = Set.of(
        "debug_status", //$NON-NLS-1$
        "debug_yaxunit_tests", //$NON-NLS-1$
        "evaluate_expression", //$NON-NLS-1$
        "get_profiling_results", //$NON-NLS-1$
        "get_variables", //$NON-NLS-1$
        "list_breakpoints", //$NON-NLS-1$
        "remove_breakpoint", //$NON-NLS-1$
        "resume", //$NON-NLS-1$
        "set_breakpoint", //$NON-NLS-1$
        "start_profiling", //$NON-NLS-1$
        "step", //$NON-NLS-1$
        "wait_for_break"); //$NON-NLS-1$

    // === parseDisabledTools ===

    @Test
    public void testParseEmpty()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools("");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseNull()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseBlank()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools("   ");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseSingleTool()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools("get_edt_version");
        assertEquals(1, result.size());
        assertTrue(result.contains("get_edt_version"));
    }

    @Test
    public void testParseMultipleTools()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools(
            "get_edt_version,list_projects,set_breakpoint");
        assertEquals(3, result.size());
        assertTrue(result.contains("get_edt_version"));
        assertTrue(result.contains("list_projects"));
        assertTrue(result.contains("set_breakpoint"));
    }

    @Test
    public void testParseTrimsWhitespace()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools(
            " get_edt_version , list_projects ");
        assertEquals(2, result.size());
        assertTrue(result.contains("get_edt_version"));
        assertTrue(result.contains("list_projects"));
    }

    @Test
    public void testParseSkipsEmptyEntries()
    {
        Set<String> result = ToolSettingsService.parseDisabledTools(
            "get_edt_version,,list_projects,");
        assertEquals(2, result.size());
        assertTrue(result.contains("get_edt_version"));
        assertTrue(result.contains("list_projects"));
    }

    // === serializeDisabledTools ===

    @Test
    public void testSerializeEmpty()
    {
        String result = ToolSettingsService.serializeDisabledTools(Collections.emptySet());
        assertEquals("", result);
    }

    @Test
    public void testSerializeNull()
    {
        String result = ToolSettingsService.serializeDisabledTools(null);
        assertEquals("", result);
    }

    @Test
    public void testSerializeSingleTool()
    {
        String result = ToolSettingsService.serializeDisabledTools(Set.of("get_edt_version"));
        assertEquals("get_edt_version", result);
    }

    @Test
    public void testSerializeMultipleToolsSorted()
    {
        String result = ToolSettingsService.serializeDisabledTools(
            Set.of("set_breakpoint", "get_edt_version", "list_projects"));
        assertEquals("get_edt_version,list_projects,set_breakpoint", result);
    }

    // === Roundtrip ===

    @Test
    public void testRoundtripEmpty()
    {
        Set<String> original = Set.of();
        String serialized = ToolSettingsService.serializeDisabledTools(original);
        Set<String> parsed = ToolSettingsService.parseDisabledTools(serialized);
        assertEquals(original, parsed);
    }

    @Test
    public void testRoundtripMultiple()
    {
        Set<String> original = Set.of("get_edt_version", "list_projects", "set_breakpoint");
        String serialized = ToolSettingsService.serializeDisabledTools(original);
        Set<String> parsed = ToolSettingsService.parseDisabledTools(serialized);
        assertEquals(original, parsed);
    }

    @Test
    public void testRoundtripPresetDisabledTools()
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue;
            }
            String serialized = ToolSettingsService.serializeDisabledTools(disabled);
            Set<String> parsed = ToolSettingsService.parseDisabledTools(serialized);
            assertEquals("Roundtrip failed for preset " + preset.name(), disabled, parsed);
        }
    }

    @Test
    public void testMigrationAddsTheDefaultOffToolToAnExistingStoredList()
    {
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(
            Set.of("debug_launch", "run_yaxunit_tests")); //$NON-NLS-1$ //$NON-NLS-2$

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("the migration must add git: " + disabled, disabled.contains("git")); //$NON-NLS-1$
        assertTrue("it must keep what the user chose: " + disabled,
            disabled.contains("debug_launch") && disabled.contains("run_yaxunit_tests")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testAskWorkmateIsOffByDefaultAndOnUpgrade()
    {
        assertTrue("the shipped default must disable ask_workmate",
            ToolSettingsService.parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS)
                .contains("ask_workmate")); //$NON-NLS-1$

        PreferenceStore store = storedDisabledTools(Set.of("debug_launch"), 2); //$NON-NLS-1$

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("the upgrade must disable ask_workmate: " + disabled,
            disabled.contains("ask_workmate")); //$NON-NLS-1$
        assertFalse("the earlier git migration must not rerun: " + disabled,
            disabled.contains("git")); //$NON-NLS-1$
    }

    @Test
    public void testMigrationToVersion2DoesNotReAddGitRemovedAfterVersion1()
    {
        PreferenceStore store = storedDisabledTools(Set.of("debug_launch"), 1); //$NON-NLS-1$

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertFalse("a git choice made after version 1 must survive: " + disabled,
            disabled.contains("git")); //$NON-NLS-1$
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testMigrationDoesNotTouchAStoreAlreadyAtCurrentVersion()
    {
        Set<String> selection = Set.of("debug_launch"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(selection,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);

        ToolSettingsService.ensureMigratedForTest(store);

        assertEquals(selection, disabledTools(store));
    }

    @Test
    public void testVersion2MigrationLeavesAnOverlappingCustomSelectionWithoutQuickFix()
    {
        PreferenceStore store = storedDisabledTools(
            Set.of("debug_launch", "run_yaxunit_tests"), 1); //$NON-NLS-1$ //$NON-NLS-2$

        ToolSettingsService.ensureMigratedForTest(store);

        assertFalse("a partial overlap must not gain apply_quick_fix: " + disabledTools(store),
            disabledTools(store).contains("apply_quick_fix")); //$NON-NLS-1$
    }

    @Test
    public void testVersion2RecognizesFirstReleaseCodeReviewAtVersion1()
    {
        Set<String> afterVersion1 = new HashSet<>(FIRST_RELEASE_CODE_REVIEW_SHAPE);
        afterVersion1.add("git"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(afterVersion1, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 2 must add apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("version 2 must preserve git from version 1: " + disabled,
            disabled.contains("git")); //$NON-NLS-1$
    }

    @Test
    public void testVersion2RecognizesFirstReleaseAnalysisOnlyAtVersion1()
    {
        Set<String> afterVersion1 = new HashSet<>(FIRST_RELEASE_ANALYSIS_ONLY_SHAPE);
        afterVersion1.add("git"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(afterVersion1, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        assertTrue("version 2 must add apply_quick_fix to Analysis Only: "
            + disabledTools(store), disabledTools(store).contains("apply_quick_fix")); //$NON-NLS-1$
    }

    @Test
    public void testVersion2RecognizesCodeReviewTheUserTightenedFurther()
    {
        Set<String> tightened = new HashSet<>(FIRST_RELEASE_CODE_REVIEW_SHAPE);
        tightened.add("get_form_screenshot"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(tightened, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("a tightened Code Review profile must gain apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("the user's extra disabled tool must survive: " + disabled,
            disabled.contains("get_form_screenshot")); //$NON-NLS-1$
    }

    @Test
    public void testVersion2RecognizesAnalysisOnlyTheUserTightenedFurther()
    {
        Set<String> tightened = new HashSet<>(FIRST_RELEASE_ANALYSIS_ONLY_SHAPE);
        tightened.add("get_markers"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(tightened, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("a tightened Analysis Only profile must gain apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("the user's extra disabled tool must survive: " + disabled,
            disabled.contains("get_markers")); //$NON-NLS-1$
    }

    @Test
    public void testVersion2LeavesASelectionMissingOneRecognitionToolAlone()
    {
        Set<String> almostCodeReview = new HashSet<>(FIRST_RELEASE_CODE_REVIEW_SHAPE);
        almostCodeReview.remove("wait_for_break"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(almostCodeReview, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        assertFalse("a selection short of the frozen shape must not gain apply_quick_fix: "
            + disabledTools(store), disabledTools(store).contains("apply_quick_fix")); //$NON-NLS-1$
    }

    @Test
    public void testVersion4ChecksAnalysisOnlyBeforeCodeReview()
    {
        PreferenceStore store = storedDisabledTools(FIRST_RELEASE_ANALYSIS_ONLY_SHAPE, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        assertTrue("the most-specific match must add the Analysis Only-only addition",
            disabledTools(store).contains("get_outgoing_structures")); //$NON-NLS-1$
    }

    @Test
    public void testMigrationRecognitionIgnoresStaleStoredNames()
    {
        Set<String> withStaleName = new HashSet<>(FIRST_RELEASE_CODE_REVIEW_SHAPE);
        withStaleName.add("removed_historical_tool"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(withStaleName, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("a stale name must not prevent recognition: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("migration must preserve stale stored names: " + disabled,
            disabled.contains("removed_historical_tool")); //$NON-NLS-1$
    }

    @Test
    public void testFirstReleaseCodeReviewDisablesDangerousToolsAcrossEveryMigration()
    {
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(
            FIRST_RELEASE_CODE_REVIEW_SHAPE);
        assertFalse(store.contains(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 1 must add git: " + disabled, disabled.contains("git")); //$NON-NLS-1$
        assertTrue("version 2 must add apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("version 3 must add ask_workmate: " + disabled,
            disabled.contains("ask_workmate")); //$NON-NLS-1$
        assertTrue("version 4 must add every Code Review addition: " + disabled,
            disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        // matchPreset is deliberately not asserted: migration is minimal and the live preset has
        // grown, so this safely migrated first-release store is legitimately CUSTOM.
    }

    @Test
    public void testFirstReleaseAnalysisOnlyDisablesDangerousToolsAcrossEveryMigration()
    {
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(
            FIRST_RELEASE_ANALYSIS_ONLY_SHAPE);
        assertFalse(store.contains(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 1 must add git: " + disabled, disabled.contains("git")); //$NON-NLS-1$
        assertTrue("version 2 must add apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("version 3 must add ask_workmate: " + disabled,
            disabled.contains("ask_workmate")); //$NON-NLS-1$
        assertTrue("version 4 must add every Analysis Only addition: " + disabled,
            disabled.containsAll(ANALYSIS_ONLY_V4_ADDITIONS));
        // matchPreset is deliberately not asserted: migration is minimal and the live preset has
        // grown, so this safely migrated first-release store is legitimately CUSTOM.
    }

    @Test
    public void testFirstReleaseDevelopmentDisablesDangerousToolsAcrossEveryMigration()
    {
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(
            FIRST_RELEASE_DEVELOPMENT_SHAPE);
        assertFalse(store.contains(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 1 must add git: " + disabled, disabled.contains("git")); //$NON-NLS-1$
        assertTrue("version 3 must add ask_workmate: " + disabled,
            disabled.contains("ask_workmate")); //$NON-NLS-1$
        assertTrue("version 4 must add every Development addition: " + disabled,
            disabled.containsAll(DEVELOPMENT_V4_ADDITIONS));
        assertFalse("Development is writable, so quick fixes stay enabled: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        // matchPreset is deliberately not asserted: migration is minimal and the live preset has
        // grown, so this safely migrated first-release store is legitimately CUSTOM.
    }

    @Test
    public void testVersion4RecognizesCodeReviewStoreFromBeforeSetVariable()
    {
        assertFalse(FIRST_RELEASE_CODE_REVIEW_SHAPE.contains("set_variable")); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(FIRST_RELEASE_CODE_REVIEW_SHAPE, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        assertTrue("a pre-set_variable Code Review store must gain every version 4 addition",
            disabledTools(store).containsAll(CODE_REVIEW_V4_ADDITIONS));
    }

    @Test
    public void testVersion4RecognizesCodeReviewWhenUserReEnabledApplyQuickFix()
    {
        Set<String> beforeVersion4 = currentPresetBeforeVersion4(
            ToolPreset.CODE_REVIEW, CODE_REVIEW_V4_ADDITIONS);
        beforeVersion4.remove("apply_quick_fix"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(beforeVersion4, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 4 additions must still be applied: " + disabled,
            disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        assertFalse("version 4 must not re-disable apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
    }

    @Test
    public void testVersion4LeavesOverlappingSelectionWithoutAnyFrozenShapeUntouched()
    {
        Set<String> overlap = new HashSet<>(FIRST_RELEASE_CODE_REVIEW_SHAPE);
        overlap.remove("wait_for_break"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(overlap, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        assertEquals(overlap, disabledTools(store));
    }

    @Test
    public void testVersion4RestoresCurrentAnalysisOnlyPreset()
    {
        assertVersion4RestoresCurrentPreset(
            ToolPreset.ANALYSIS_ONLY, ANALYSIS_ONLY_V4_ADDITIONS);
    }

    @Test
    public void testVersion4RestoresCurrentCodeReviewPreset()
    {
        assertVersion4RestoresCurrentPreset(
            ToolPreset.CODE_REVIEW, CODE_REVIEW_V4_ADDITIONS);
    }

    @Test
    public void testVersion4RestoresCurrentDevelopmentPreset()
    {
        assertVersion4RestoresCurrentPreset(
            ToolPreset.DEVELOPMENT, DEVELOPMENT_V4_ADDITIONS);
    }

    @Test
    public void testVersion4RecognizesCodeReviewWithDefaultDisabledGitEnabled()
    {
        Set<String> beforeVersion4 = currentPresetBeforeVersion4(
            ToolPreset.CODE_REVIEW, CODE_REVIEW_V4_ADDITIONS);
        beforeVersion4.remove("git"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(beforeVersion4, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue(disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        assertFalse("the migration must preserve the user's enabled git choice: " + disabled,
            disabled.contains("git")); //$NON-NLS-1$
    }

    @Test
    public void testFrozenRecognitionShapesRemainSubsetsOfCurrentPresets()
    {
        assertRecognitionShapeStillDisabled(ToolPreset.ANALYSIS_ONLY,
            ToolSettingsService.ANALYSIS_ONLY_RECOGNITION_SHAPE);
        assertRecognitionShapeStillDisabled(ToolPreset.CODE_REVIEW,
            ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE);
        assertRecognitionShapeStillDisabled(ToolPreset.DEVELOPMENT,
            ToolSettingsService.DEVELOPMENT_RECOGNITION_SHAPE);
    }

    private static void assertVersion4RestoresCurrentPreset(ToolPreset preset,
        Set<String> version4Additions)
    {
        Set<String> beforeVersion4 = currentPresetBeforeVersion4(preset, version4Additions);
        PreferenceStore store = storedDisabledTools(beforeVersion4, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertEquals("version 4 must restore the current disabled set for " + preset,
            preset.getDisabledTools(), disabled);
        assertEquals("the restored set must match " + preset,
            preset, ToolPreset.matchPreset(disabled));
    }

    private static Set<String> currentPresetBeforeVersion4(ToolPreset preset,
        Set<String> version4Additions)
    {
        Set<String> beforeVersion4 = new HashSet<>(preset.getDisabledTools());
        beforeVersion4.removeAll(version4Additions);
        return beforeVersion4;
    }

    private static void assertRecognitionShapeStillDisabled(ToolPreset preset,
        Set<String> recognitionShape)
    {
        Set<String> offending = new TreeSet<>(recognitionShape);
        offending.removeAll(preset.getDisabledTools());
        assertTrue("frozen recognition shape for " + preset
            + " contains tools the current preset no longer disables: " + offending,
            offending.isEmpty());
    }

    private static PreferenceStore storedDisabledTools(Set<String> disabled, int migrationVersion)
    {
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(disabled);
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, migrationVersion);
        return store;
    }

    private static PreferenceStore storedDisabledToolsWithoutMigrationKey(Set<String> disabled)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(disabled));
        return store;
    }

    private static Set<String> disabledTools(PreferenceStore store)
    {
        return ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
