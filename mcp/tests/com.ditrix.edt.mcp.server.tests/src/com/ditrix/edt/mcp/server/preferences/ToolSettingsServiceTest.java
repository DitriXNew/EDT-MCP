/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * The migration is the ONLY thing that keeps a default-off tool disabled on an EXISTING
     * installation: a stored list from before that tool existed does not contain it, and the shipped
     * default no longer applies once anything was stored. Without this test a regression would
     * silently enable the raw git tool for every upgrading user.
     */
    @Test
    public void testMigrationAddsTheDefaultOffToolToAnExistingStoredList()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // An installation that stored its own selection before 'git' existed.
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "debug_launch,run_yaxunit_tests");

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("the migration must add the default-off tool: " + disabled,
            disabled.contains("git"));
        assertTrue("it must keep what the user had chosen: " + disabled,
            disabled.contains("debug_launch") && disabled.contains("run_yaxunit_tests"));
        assertEquals("the migration must be recorded so it runs once",
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    /**
     * ask_workmate hands the question to an external plugin that reaches a cloud service and
     * can then change the configuration with its own tools, so it must arrive DISABLED both on
     * a fresh install (the shipped default) and on an upgrade (the version 3 migration).
     */
    @Test
    public void testAskWorkmateIsOffByDefaultAndOnUpgrade()
    {
        assertTrue("the shipped default must disable ask_workmate",
            ToolSettingsService.parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS)
                .contains("ask_workmate"));

        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // A store saved before ask_workmate existed, already past the earlier migrations.
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "debug_launch");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 2);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("the upgrade must disable ask_workmate: " + disabled,
            disabled.contains("ask_workmate"));
        assertTrue("it must keep what the user had chosen: " + disabled,
            disabled.contains("debug_launch"));
        // The earlier steps are past their own thresholds and must NOT re-run.
        assertFalse("a git choice made earlier must survive: " + disabled,
            disabled.contains("git"));
    }

    @Test
    public void testMigrationDoesNotReAddAToolTheUserDeliberatelyEnabled()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "debug_launch");
        // Already migrated: the user has since ENABLED git on purpose.
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse("a one-time migration must not fight the user's choice: " + disabled,
            disabled.contains("git"));
    }

    /**
     * The regression this guards: the OLD code gated BOTH the v1 (git) and v2 (apply_quick_fix)
     * steps behind a single "storedVersion &lt; CURRENT(=2)" check. For a store already at
     * version 1 - meaning the v1 git-migration ALREADY ran once, and the user has SINCE
     * deliberately removed git again - upgrading to a build with version 2 would re-enter that
     * shared block and unconditionally re-add git, silently fighting the user's later choice.
     * Each step must instead be gated by its OWN threshold ({@code storedVersion < 1} for git),
     * so a store already past that threshold never re-runs it just because a LATER, unrelated
     * migration also needs to apply.
     */
    @Test
    public void testMigrationToVersion2DoesNotReAddGitRemovedAfterVersion1()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // An arbitrary custom selection WITHOUT git - the user's choice, made after the version 1
        // migration (which this store already passed through) had added it.
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "debug_launch");
        // Already at version 1 (NOT the current version, and NOT 0) - this is the exact
        // intermediate state the bug required: past v1, still owing v2.
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse("a git choice made after version 1 must survive the version 2 migration: "
            + disabled, disabled.contains("git"));
        assertEquals("the store must still be recorded as fully migrated",
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    /**
     * The companion of the regression test above: a store at version 1 whose selection was NOT
     * touched since (still exactly the Code Review shape minus apply_quick_fix, git included -
     * what the version 1 migration itself would have produced under Code Review) must still gain
     * apply_quick_fix normally when the version 2 migration runs. Confirms the per-step version
     * gating does not accidentally suppress a migration that legitimately still applies.
     */
    @Test
    public void testMigrationToVersion2StillAppliesWhenNothingElseChangedSinceVersion1()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        Set<String> afterV1 = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        afterV1.remove("apply_quick_fix"); // not migrated in yet - that's version 2's own job
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(afterV1));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("git must still be present (untouched since version 1): " + disabled,
            disabled.contains("git"));
        assertTrue("version 2's own migration must still apply normally: " + disabled,
            disabled.contains("apply_quick_fix"));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    /**
     * apply_quick_fix is a normal (default-ON) tool, unlike git - so the migration must not add it
     * to every stored list, only to one that, once it gains apply_quick_fix, becomes EXACTLY a
     * read-only preset's current shape: the signature of a store saved by a build that predates the
     * tool, under Code Review or Analysis Only, before it existed to be excluded from them.
     */
    @Test
    public void testMigrationAddsApplyQuickFixToAPreExistingCodeReviewPreset()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // What an installation running Code Review would have persisted BEFORE apply_quick_fix
        // was added to the preset's definition.
        Set<String> oldShape = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        oldShape.remove("apply_quick_fix");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(oldShape));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("a stored Code Review preset must gain apply_quick_fix on migration: " + disabled,
            disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testMigrationAddsApplyQuickFixToAPreExistingAnalysisOnlyPreset()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        Set<String> oldShape = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        oldShape.remove("apply_quick_fix");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(oldShape));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("a stored Analysis Only preset must gain apply_quick_fix on migration: " + disabled,
            disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testMigrationDoesNotAddApplyQuickFixToACustomSelection()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // A genuinely custom selection that merely OVERLAPS a preset in a couple of tools must not
        // be mistaken for one: the migration asks whether the stored list CONTAINS a whole
        // read-only preset, and two tools out of that preset is not that preset.
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "debug_launch,run_yaxunit_tests");

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse("a custom selection must not gain apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testMigrationDoesNotFightAUserWhoAlreadyEnabledApplyQuickFix()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // Already migrated once (version stored == current): a user who has since deliberately
        // re-enabled apply_quick_fix under an otherwise Code-Review-shaped selection must be left
        // alone by a later migration run.
        Set<String> shape = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        shape.remove("apply_quick_fix");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(shape));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse("an already-migrated store must not be touched again: " + disabled,
            disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testMigrationAddsApplyQuickFixToAReadOnlyPresetTheUserTightenedFurther()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // The ordinary upgrade path an EXACT-match migration missed: picked Code Review, then
        // unticked one more tool. The stored list is a strict SUPERSET of the preset, so it is
        // still a no-write profile - the write-capable quick-fix tool must not arrive enabled in it.
        Set<String> tightened = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        tightened.remove("apply_quick_fix");
        tightened.add("get_form_screenshot");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(tightened));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("a Code Review preset the user tightened further is still a no-write profile "
            + "and must gain apply_quick_fix: " + disabled, disabled.contains("apply_quick_fix"));
        assertTrue("the migration must not drop the user's own extra selection: " + disabled,
            disabled.contains("get_form_screenshot"));
    }

    @Test
    public void testMigrationAddsApplyQuickFixToAnAnalysisOnlyPresetTheUserTightenedFurther()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        Set<String> tightened = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        tightened.remove("apply_quick_fix");
        tightened.add("get_markers");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(tightened));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertTrue("an Analysis Only preset the user tightened further must gain apply_quick_fix: "
            + disabled, disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testMigrationLeavesAPresetItIsMissingOneToolFromAlone()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setDefault(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        // The boundary on the other side: one tool SHORT of a read-only preset is not that preset,
        // however close it looks. Migrating here would disable a tool in a profile that never
        // expressed the no-write intent - so the superset test must be a real containment check,
        // not "mostly overlaps".
        Set<String> almost = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        almost.remove("apply_quick_fix");
        almost.remove("write_module_source");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(almost));

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
        assertFalse("a selection one tool short of the preset must not be migrated: " + disabled,
            disabled.contains("apply_quick_fix"));
    }

    @Test
    public void testVersion1HistoricalCodeReviewMigratesThroughVersions2And4()
    {
        Set<String> historicalShape = historicalPresetShapeAtVersion1(
            ToolPreset.CODE_REVIEW, CODE_REVIEW_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(historicalShape, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 2 must add apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("version 4 must add every Code Review addition: " + disabled,
            disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        assertEquals(ToolPreset.CODE_REVIEW, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testVersion1HistoricalAnalysisOnlyMigratesThroughVersions2And4()
    {
        Set<String> historicalShape = historicalPresetShapeAtVersion1(
            ToolPreset.ANALYSIS_ONLY, ANALYSIS_ONLY_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(historicalShape, 1);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue("version 2 must add apply_quick_fix: " + disabled,
            disabled.contains("apply_quick_fix")); //$NON-NLS-1$
        assertTrue("version 4 must add every Analysis Only addition: " + disabled,
            disabled.containsAll(ANALYSIS_ONLY_V4_ADDITIONS));
        assertEquals(ToolPreset.ANALYSIS_ONLY, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testVersion0HistoricalCodeReviewMigratesThroughEveryVersion()
    {
        Set<String> historicalShape = historicalPresetShapeAtVersion1(
            ToolPreset.CODE_REVIEW, CODE_REVIEW_V4_ADDITIONS);
        historicalShape.remove("git"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledToolsWithoutMigrationKey(historicalShape);
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
        assertEquals(ToolPreset.CODE_REVIEW, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testVersion2PresetPredicatesStayFrozenAtTheirShippedShapes()
    {
        Set<String> codeReviewShape =
            ToolSettingsService.version2PresetShapeForTest(ToolPreset.CODE_REVIEW);
        Set<String> analysisOnlyShape =
            ToolSettingsService.version2PresetShapeForTest(ToolPreset.ANALYSIS_ONLY);

        assertTrue("the version 2 Code Review predicate must not require version 4 additions",
            Collections.disjoint(codeReviewShape, CODE_REVIEW_V4_ADDITIONS));
        assertTrue("the version 2 Analysis Only predicate must not require version 4 additions",
            Collections.disjoint(analysisOnlyShape, ANALYSIS_ONLY_V4_ADDITIONS));
        // The shapes that shipped at version 2, frozen by NAME rather than by size. A size alone
        // survives a net-zero edit (one tool added, another removed) and, when it does fail, says
        // nothing about which tool moved; comparing the sorted names makes the assertion message
        // itself the diff. Deriving either side from the live presets would defeat the ratchet:
        // when a preset expands, production must extend PRESET_ADDITIONS_AFTER_V2 so these
        // historical shapes stay exactly as they are.
        assertEquals("extend PRESET_ADDITIONS_AFTER_V2 for later Code Review additions",
            VERSION_2_CODE_REVIEW_SHAPE, sortedNames(codeReviewShape));
        assertEquals("extend PRESET_ADDITIONS_AFTER_V2 for later Analysis Only additions",
            VERSION_2_ANALYSIS_ONLY_SHAPE, sortedNames(analysisOnlyShape));
    }

    @Test
    public void testMigrationToVersion4RestoresAnalysisOnlyPreset()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.ANALYSIS_ONLY,
            ANALYSIS_ONLY_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(oldShape, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue(disabled.containsAll(ANALYSIS_ONLY_V4_ADDITIONS));
        assertEquals(ToolPreset.ANALYSIS_ONLY, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMigrationToVersion4RestoresCodeReviewPreset()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.CODE_REVIEW,
            CODE_REVIEW_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(oldShape, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue(disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        assertEquals(ToolPreset.CODE_REVIEW, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMigrationToVersion4RestoresDevelopmentPreset()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.DEVELOPMENT,
            DEVELOPMENT_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(oldShape, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue(disabled.containsAll(DEVELOPMENT_V4_ADDITIONS));
        assertEquals(ToolPreset.DEVELOPMENT, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMigrationToVersion4ChecksAnalysisOnlyBeforeCodeReview()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.ANALYSIS_ONLY,
            ANALYSIS_ONLY_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(oldShape, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        assertTrue(disabledTools(store).contains("get_outgoing_structures")); //$NON-NLS-1$
    }

    @Test
    public void testMigrationToVersion4LeavesOverlappingCustomSelectionUntouched()
    {
        Set<String> overlap = Set.of(
            "debug_launch", //$NON-NLS-1$
            "write_module_source"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(overlap, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        assertEquals(overlap, disabledTools(store));
    }

    @Test
    public void testMigrationDoesNotTouchAStoreAlreadyAtVersion4()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.ANALYSIS_ONLY,
            ANALYSIS_ONLY_V4_ADDITIONS);
        PreferenceStore store = storedDisabledTools(oldShape, 4);

        ToolSettingsService.ensureMigratedForTest(store);

        assertEquals(oldShape, disabledTools(store));
    }

    @Test
    public void testMigrationToVersion4RecognizesCodeReviewWithGitEnabled()
    {
        Set<String> oldShape = oldPresetShape(ToolPreset.CODE_REVIEW,
            CODE_REVIEW_V4_ADDITIONS);
        oldShape.remove("git"); //$NON-NLS-1$
        PreferenceStore store = storedDisabledTools(oldShape, 3);

        ToolSettingsService.ensureMigratedForTest(store);

        Set<String> disabled = disabledTools(store);
        assertTrue(disabled.containsAll(CODE_REVIEW_V4_ADDITIONS));
        assertFalse(disabled.contains("git")); //$NON-NLS-1$
    }

    private static Set<String> oldPresetShape(ToolPreset preset, Set<String> additions)
    {
        Set<String> oldShape = new HashSet<>(preset.getDisabledTools());
        oldShape.removeAll(additions);
        return oldShape;
    }

    /**
     * The Code Review predicate exactly as version 2 shipped it. Frozen here so a preset change
     * that leaks into an older migration names itself in the failure instead of moving a count.
     */
    private static final String VERSION_2_CODE_REVIEW_SHAPE =
        "create_infobase, create_launch_config, create_metadata, debug_launch, debug_status"
        + ", debug_yaxunit_tests, delete_infobase, delete_launch_config, delete_metadata"
        + ", evaluate_expression, export_configuration_to_xml, generate_translation_strings"
        + ", get_applications, get_profiling_results, get_translation_project_info, get_variables"
        + ", import_configuration_from_xml, list_breakpoints, list_configurations, modify_metadata"
        + ", remove_breakpoint, rename_metadata_object, resume, run_yaxunit_tests, set_breakpoint"
        + ", set_variable, start_profiling, step, terminate_launch, translate_configuration"
        + ", update_database, wait_for_break, write_module_source";

    /** The Analysis Only predicate exactly as version 2 shipped it; see the field above. */
    private static final String VERSION_2_ANALYSIS_ONLY_SHAPE =
        "create_infobase, create_launch_config, create_metadata, debug_launch, debug_status"
        + ", debug_yaxunit_tests, delete_infobase, delete_launch_config, delete_metadata"
        + ", evaluate_expression, export_configuration_to_xml, generate_translation_strings"
        + ", get_applications, get_form_layout_snapshot, get_form_screenshot, get_method_call_hierarchy"
        + ", get_module_structure, get_profiling_results, get_symbol_info, get_template_screenshot"
        + ", get_translation_project_info, get_variables, go_to_definition, import_configuration_from_xml"
        + ", list_breakpoints, list_configurations, list_modules, modify_metadata, read_method_source"
        + ", read_module_source, remove_breakpoint, rename_metadata_object, resume, run_yaxunit_tests"
        + ", search_in_code, set_breakpoint, set_variable, start_profiling, step, terminate_launch"
        + ", translate_configuration, update_database, validate_query, wait_for_break"
        + ", write_module_source";

    /** The set as a stable, sorted, comma-separated string, so a mismatch reads as a diff. */
    private static String sortedNames(Set<String> names)
    {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }

    private static Set<String> historicalPresetShapeAtVersion1(ToolPreset preset,
        Set<String> version4Additions)
    {
        Set<String> historicalShape = oldPresetShape(preset, version4Additions);
        historicalShape.remove("apply_quick_fix"); //$NON-NLS-1$
        historicalShape.remove("ask_workmate"); //$NON-NLS-1$
        return historicalShape;
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
