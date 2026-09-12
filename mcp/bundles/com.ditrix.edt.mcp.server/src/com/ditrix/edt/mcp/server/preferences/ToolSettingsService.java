/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Service managing tool enablement state.
 * Reads and writes disabled tool names to the preference store.
 * Thread-safe: the disabled set is parsed on each access from the volatile preference store.
 */
public final class ToolSettingsService // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    private static final Set<String> STORED_PROFILE_V1_ADDITIONS = Set.of(
        "git"); //$NON-NLS-1$

    private static final Set<String> READ_ONLY_V2_ADDITIONS = Set.of(
        "apply_quick_fix"); //$NON-NLS-1$

    private static final Set<String> STORED_PROFILE_V3_ADDITIONS = Set.of(
        "ask_workmate"); //$NON-NLS-1$

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

    private static final Set<String> NO_DEBUG_V6_ADDITIONS = Set.of(
        "set_error_breakpoint"); //$NON-NLS-1$

    /*
     * Version 6: the two destructive tools a stored read-only profile could not have excluded.
     * merge_rules is new (its write half creates and REPLACES files); delete_project is not new at
     * all - it sits in ToolGroup.CORE, which no preset disables, so it has been enabled in both
     * read-only presets since they existed. Both read-only presets get the same two names, so one
     * set answers for both.
     */
    private static final Set<String> READ_ONLY_V7_ADDITIONS = Set.of(
        "merge_rules", //$NON-NLS-1$
        "delete_project"); //$NON-NLS-1$

    private static final Set<String> READ_ONLY_V8_ADDITIONS = Set.of(
        "infobase_sessions"); //$NON-NLS-1$

    /*
     * Historical order in which these entered the read-only presets: delete_metadata (e5d2012a),
     * delete_infobase (543a7d4a), cancel_job (bd9cb8e8). Frozen: future tools belong to a later
     * migration, never this list.
     */
    static final List<String> READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER = List.of(
        "delete_metadata", //$NON-NLS-1$
        "delete_infobase", //$NON-NLS-1$
        "cancel_job"); //$NON-NLS-1$

    private static final Set<String> READ_ONLY_V9_ADDITIONS =
        Set.copyOf(READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER);

    /** Actual disabled-name additions registered for each Analysis Only migration. */
    static final Map<Integer, Set<String>> ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION = Map.of(
        1, STORED_PROFILE_V1_ADDITIONS,
        2, READ_ONLY_V2_ADDITIONS,
        3, STORED_PROFILE_V3_ADDITIONS,
        4, ANALYSIS_ONLY_V4_ADDITIONS,
        6, NO_DEBUG_V6_ADDITIONS,
        7, READ_ONLY_V7_ADDITIONS,
        8, READ_ONLY_V8_ADDITIONS,
        9, READ_ONLY_V9_ADDITIONS);

    /** Actual disabled-name additions registered for each Code Review migration. */
    static final Map<Integer, Set<String>> CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION = Map.of(
        1, STORED_PROFILE_V1_ADDITIONS,
        2, READ_ONLY_V2_ADDITIONS,
        3, STORED_PROFILE_V3_ADDITIONS,
        4, CODE_REVIEW_V4_ADDITIONS,
        6, NO_DEBUG_V6_ADDITIONS,
        7, READ_ONLY_V7_ADDITIONS,
        8, READ_ONLY_V8_ADDITIONS,
        9, READ_ONLY_V9_ADDITIONS);

    /*
     * Frozen recognition shapes: what any historical stored profile of this preset must contain.
     * Never derive them from the live preset, which has grown over time. A shape is only ever
     * extended after checking that every supported historical store still contains the new name.
     *
     * These are deliberately per-preset rather than a shared union. They omit the default-disabled
     * tools, so a user who enabled git is still recognized, and apply_quick_fix is in none of them,
     * so version 4 still recognizes a read-only store whose user deliberately re-enabled it.
     *
     * They name {@code launch}, not the historical {@code debug_launch}, ONLY because the version 5
     * rename step runs before every shape check, so a store reaching one already spells the new
     * name. A RENAMED tool must be handled that way - by migrating the stored list first - never by
     * editing a shape to match the live preset.
     */
    static final Set<String> ANALYSIS_ONLY_RECOGNITION_SHAPE = Set.of(
        "launch", //$NON-NLS-1$
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

    static final Set<String> CODE_REVIEW_RECOGNITION_SHAPE = Set.of(
        "launch", //$NON-NLS-1$
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

    static final Set<String> DEVELOPMENT_RECOGNITION_SHAPE = Set.of(
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

    private static final ToolSettingsService INSTANCE = new ToolSettingsService();

    private ToolSettingsService()
    {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static ToolSettingsService getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the set of disabled tool names from preferences, falling back to the SHIPPED defaults
     * when no store is available - never to "nothing is disabled", which would enable a default-off
     * tool.
     */
    public Set<String> getDisabledTools()
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            // FAIL CLOSED: with no preference store (a headless registry, a plugin not started yet)
            // an empty set would advertise and allow EVERY tool, including the ones that ship
            // disabled - the powerful raw git tool among them. Fall back to the shipped defaults.
            return parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        }
        ensureMigrated(store);
        String value = store.getString(PreferenceConstants.PREF_DISABLED_TOOLS);
        return parseDisabledTools(value);
    }

    /**
     * Test seam: runs the migration against a supplied store, so the one mechanism that keeps a
     * default-off tool disabled on upgrade can be verified without an Eclipse runtime.
     *
     * @param store the preference store to migrate
     */
    static void ensureMigratedForTest(IPreferenceStore store)
    {
        INSTANCE.ensureMigrated(store);
    }

    /**
     * Applies the tool-enablement preference MIGRATIONS once per store, lazily on the first read.
     * <p>
     * A tool that ships DISABLED by default gets that from {@code DEFAULT_DISABLED_TOOLS} - but only on
     * a store that never persisted its own value. An installation that had already saved the Tools tab
     * (or an "all tools" preset) holds an explicit list that predates the new tool, so without this the
     * powerful {@code git} tool would silently arrive ENABLED on upgrade. Version 1 therefore adds it to
     * such a stored list; the user can still enable it deliberately afterwards.
     * <p>
     * Version 2 covers a narrower case: {@code apply_quick_fix} is a normal (default-ON) tool, so it is
     * NOT added to every stored list the way {@code git} is - only to a list that already CONTAINS
     * the frozen recognition shape of Code Review or Analysis Only. That containment is the
     * signature of a store saved by an older build under one of those two read-only presets, before
     * this write-capable tool existed to be excluded from them - and it still holds for a user who
     * tightened such a preset further. A selection that merely OVERLAPS one (without covering it)
     * is left untouched. See
     * {@link #migrateApplyQuickFixIntoReadOnlyPreset} for why containment rather than equality.
     * <p>
     * Version 6 is the version 2 case again, one tool generation later: a stored read-only profile
     * is recognized by CONTAINMENT of its frozen shape - never by equality, which would miss the
     * ordinary user who tightened the preset further - and gains the two destructive tools it
     * could not have excluded. {@code merge_rules} did not exist when the profile was saved;
     * {@code delete_project} did, but it lives in a group no preset disables and was never named
     * by hand, so no stored read-only profile has it. Neither is re-added to a profile that
     * already carries it, and a selection that merely OVERLAPS a shape is left alone.
     * <p>
     * Version 4 repairs stored preset shapes after previously ungrouped tools joined groups that
     * those presets disable. It recognizes an older preset by containment of the frozen historical
     * shape, which omits both names the store may predate and default-disabled names the user may
     * intentionally have enabled. Because the recognized shapes are nested, it tests the most
     * restrictive shape first; otherwise a broader match would add only part of the tools owed to
     * an Analysis Only store.
     *
     * @param store the preference store to migrate (never {@code null} here)
     */
    private void ensureMigrated(IPreferenceStore store)
    {
        int storedVersion = store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION);
        if (storedVersion >= PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION)
        {
            return;
        }
        // Only an EXPLICITLY stored list needs fixing; a default-valued store already carries the new
        // default (which includes the tool).
        if (store.contains(PreferenceConstants.PREF_DISABLED_TOOLS)
            && !store.isDefault(PreferenceConstants.PREF_DISABLED_TOOLS))
        {
            Set<String> disabled =
                new LinkedHashSet<>(parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)));
            boolean changed = false;
            // Each step is gated by its OWN version threshold, NOT merely "storedVersion is below
            // the CURRENT version" - an installation already at version 1 (git migrated in) that has
            // since deliberately RE-ENABLED git must not have version 2's migration run re-silently
            // re-disable it: bumping TOOL_PREFS_MIGRATION_VERSION for a NEW migration must never
            // re-run an EARLIER one an installation already passed through.
            // The tool names are used as literals here on purpose: the preferences layer must not
            // depend on tools/impl (see the architecture rules).
            // FIRST, before any shape-based step: the rename debug_launch -> launch. Every stored
            // list predating this build spells the tool the old way, while the frozen recognition
            // shapes below and every later lookup spell it the new way, so renaming here is what
            // keeps both a deliberate disable AND preset recognition working across the rename.
            if (storedVersion < 5 && disabled.remove("debug_launch")) //$NON-NLS-1$
            {
                disabled.add("launch"); //$NON-NLS-1$
                changed = true;
            }
            if (storedVersion < 1)
            {
                changed |= disabled.addAll(STORED_PROFILE_V1_ADDITIONS);
            }
            if (storedVersion < 2)
            {
                changed |= migrateApplyQuickFixIntoReadOnlyPreset(disabled);
            }
            if (storedVersion < 3)
            {
                // ask_workmate ships OFF: it hands the question to an external plugin that
                // reaches a cloud service and may then change the configuration with its
                // own tools. That is a decision to opt into, not to inherit on upgrade.
                changed |= disabled.addAll(STORED_PROFILE_V3_ADDITIONS);
            }
            if (storedVersion < 4)
            {
                // The v4 shapes intentionally omit apply_quick_fix, so this step recognizes a store
                // whose user deliberately re-enabled it after version 2 without adding it back.
                changed |= migrateRegroupedToolsIntoPresets(disabled);
            }
            if (storedVersion < 6)
            {
                // set_error_breakpoint is new, so a preset saved before it existed cannot name it.
                // Without this step an upgraded store that chose a NO-DEBUG preset would silently
                // gain a debugging tool - the same hazard version 2 and version 4 exist for.
                changed |= migrateErrorBreakpointIntoNoDebugPresets(disabled);
            }
            if (storedVersion < 7)
            {
                // Its own version rather than sharing 6: a store that already ran the step above
                // records 6, and reusing that number would skip this one on exactly those stores.
                // Order against it does not matter - every step here only ADDS to the disabled
                // list, and the recognition below is a containment test, which more disabled
                // entries can never break.
                changed |= migrateDestructiveToolsIntoReadOnlyPresets(disabled);
            }
            if (storedVersion < 8)
            {
                // infobase_sessions is new and destructive, so a stored read-only profile must
                // not gain it merely because the Applications group now contains it.
                changed |= migrateInfobaseSessionsIntoReadOnlyPresets(disabled);
            }
            if (storedVersion < 9)
            {
                // These older tools declare themselves destructive but missed read-only migrations.
                changed |= migrateLegacyDestructiveToolsIntoReadOnlyPresets(disabled);
            }
            if (changed)
            {
                store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, serializeDisabledTools(disabled));
            }
        }
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
    }

    /**
     * Adds {@code apply_quick_fix} to {@code disabled} when the stored list already expresses a
     * no-write profile - i.e. it CONTAINS the frozen recognition shape of Code Review or Analysis
     * Only, whether or not it disables more on top.
     * <p>
     * A SUPERSET test, not an exact match, on purpose. Exact matching (via
     * {@link ToolPreset#matchPreset}) misses the ordinary case of someone who picked a read-only
     * preset and then unticked another tool or two: their stored list is then a strict superset,
     * {@code matchPreset} reports {@code CUSTOM}, and this write-capable tool would silently arrive
     * ENABLED in a profile the user built to be read-only. There is no user intent to respect in
     * the other direction either - the migration only ever runs against a store saved BEFORE
     * {@code apply_quick_fix} existed, so nobody could have deliberately enabled it. And the two
     * failure modes are not symmetric: one extra disabled tool is a checkbox away, whereas a
     * metadata-MUTATING tool quietly live in a no-write profile is the exact hazard this migration
     * exists to prevent.
     * <p>
     * Deliberately NOT done by loosening {@code matchPreset} itself: that method also decides which
     * preset the preferences Tools tab shows as active ({@code ToolsTab}), where superset matching
     * would make a hand-tuned CUSTOM selection claim to be "Code Review".
     * <p>
     * Stale/unknown names left in the stored list cannot defeat the check, which asks only whether
     * the frozen historical shape is present.
     *
     * @param disabled the mutable stored disabled-tools set; modified in place
     * @return {@code true} when {@code apply_quick_fix} was added
     */
    private static boolean migrateApplyQuickFixIntoReadOnlyPreset(Set<String> disabled)
    {
        if (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE))
        {
            return disabled.addAll(READ_ONLY_V2_ADDITIONS);
        }
        if (disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE))
        {
            return disabled.addAll(READ_ONLY_V2_ADDITIONS);
        }
        return false;
    }

    /**
     * Adds {@code set_error_breakpoint} to a store that already expresses a NO-DEBUG profile.
     * <p>
     * The tool did not exist when those presets were saved, so their stored denylist cannot name
     * it - and a denylist is an allow-by-default list: without this step, upgrading would hand a
     * debugging switch to a profile that promised none. Recognition is by containment of the same
     * frozen historical shapes the earlier migrations use, so a hand-tuned custom selection and
     * All Tools are left alone.
     *
     * @param disabled the mutable stored disabled-tools set; modified in place
     * @return {@code true} when the tool was added
     */
    private static boolean migrateErrorBreakpointIntoNoDebugPresets(Set<String> disabled)
    {
        if (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE)
            || disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE)
            || disabled.containsAll(DEVELOPMENT_RECOGNITION_SHAPE))
        {
            return disabled.addAll(NO_DEBUG_V6_ADDITIONS);
        }
        return false;
    }

    /**
     * Adds the version 4 group-inherited disabled names to the first stored preset shape that
     * contains all of its older asserted tools.
     *
     * @param disabled the mutable stored disabled-tools set; modified in place
     * @return {@code true} when at least one newly inherited name was added
     */
    private static boolean migrateRegroupedToolsIntoPresets(Set<String> disabled)
    {
        if (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE))
        {
            return disabled.addAll(ANALYSIS_ONLY_V4_ADDITIONS);
        }
        if (disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE))
        {
            return disabled.addAll(CODE_REVIEW_V4_ADDITIONS);
        }
        if (disabled.containsAll(DEVELOPMENT_RECOGNITION_SHAPE))
        {
            return disabled.addAll(DEVELOPMENT_V4_ADDITIONS);
        }
        return false;
    }

    /**
     * Adds the version 6 destructive names to a stored list that already expresses a read-only
     * profile - i.e. one CONTAINING the frozen recognition shape of Analysis Only or Code Review.
     * <p>
     * The same containment argument as version 2, and for the same reason: exact matching would
     * miss the user who picked a read-only preset and then unticked another tool or two, and a
     * write-capable tool silently live in a profile built to be read-only is exactly the hazard
     * this step exists to prevent. Unlike version 4 the two shapes are NOT tried in order of
     * restrictiveness, because both read-only presets are owed the SAME two names - so whichever
     * shape matches first gives the right answer, and a store matching both gets it once.
     * <p>
     * Containment tolerates TIGHTENING, not loosening: a store that re-enabled any shape member -
     * {@code get_applications} included - is not recognized and keeps both tools exactly as it has
     * them today, still behind the consent gate. The shape is the preset's history, not a judgement
     * of which members are reads, and is deliberately not filtered by the {@code readOnlyHint}
     * prefix heuristic (see {@code ToolPresetTest}).
     *
     * @param disabled the mutable stored disabled-tools set; modified in place
     * @return {@code true} when at least one name was added
     */
    private static boolean migrateDestructiveToolsIntoReadOnlyPresets(Set<String> disabled)
    {
        if (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE)
            || disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE))
        {
            return disabled.addAll(READ_ONLY_V7_ADDITIONS);
        }
        return false;
    }

    /** Adds the v8 session tool only to stored profiles recognized as read-only. */
    private static boolean migrateInfobaseSessionsIntoReadOnlyPresets(Set<String> disabled)
    {
        if (disabled.containsAll(READ_ONLY_V7_ADDITIONS)
            && (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE)
                || disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE)))
        {
            return disabled.addAll(READ_ONLY_V8_ADDITIONS);
        }
        return false;
    }

    /** Adds the v9 destructive tools only to stored profiles still recognized as read-only. */
    private static boolean migrateLegacyDestructiveToolsIntoReadOnlyPresets(Set<String> disabled)
    {
        // A pristine v8 store has both prior safety sets; a missing name records a re-enable.
        if (disabled.containsAll(READ_ONLY_V7_ADDITIONS)
            && disabled.containsAll(READ_ONLY_V8_ADDITIONS)
            && (disabled.containsAll(ANALYSIS_ONLY_RECOGNITION_SHAPE)
                || disabled.containsAll(CODE_REVIEW_RECOGNITION_SHAPE)))
        {
            List<String> absentV9Names = READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER.stream()
                .filter(toolName -> !disabled.contains(toolName))
                .collect(Collectors.toList());
            int suffixStart = READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER.size() - absentV9Names.size();
            if (!READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER
                .subList(suffixStart, READ_ONLY_V9_ADDITIONS_IN_JOIN_ORDER.size())
                .equals(absentV9Names))
            {
                return false;
            }
            // A suffix absence stays ambiguous; resolved toward the preset safety promise, because
            // losing an enable is visible, while a destructive tool left enabled here is not.
            return disabled.addAll(READ_ONLY_V9_ADDITIONS);
        }
        return false;
    }

    /**
     * Saves the set of disabled tool names to preferences.
     */
    public void setDisabledTools(Set<String> disabledTools)
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return;
        }
        String value = serializeDisabledTools(disabledTools);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, value);
    }

    /**
     * Checks whether a specific tool is enabled.
     */
    public boolean isToolEnabled(String toolName)
    {
        return !getDisabledTools().contains(toolName);
    }

    /**
     * Sets the enabled state for a specific tool.
     */
    public void setToolEnabled(String toolName, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.remove(toolName);
        }
        else
        {
            disabled.add(toolName);
        }
        setDisabledTools(disabled);
    }

    /**
     * Checks whether all tools in a group are enabled.
     */
    public boolean isGroupFullyEnabled(ToolGroup group)
    {
        Set<String> disabled = getDisabledTools();
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether at least one (but not all) tools in a group are enabled.
     */
    public boolean isGroupPartiallyEnabled(ToolGroup group)
    {
        Set<String> disabled = getDisabledTools();
        boolean hasEnabled = false;
        boolean hasDisabled = false;
        for (String toolName : group.getToolNames())
        {
            if (disabled.contains(toolName))
            {
                hasDisabled = true;
            }
            else
            {
                hasEnabled = true;
            }
            if (hasEnabled && hasDisabled)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Enables or disables all tools in a group.
     */
    public void setGroupEnabled(ToolGroup group, boolean enabled)
    {
        Set<String> disabled = new HashSet<>(getDisabledTools());
        if (enabled)
        {
            disabled.removeAll(group.getToolNames());
        }
        else
        {
            disabled.addAll(group.getToolNames());
        }
        setDisabledTools(disabled);
    }

    /**
     * Applies a preset by setting the disabled tools to the preset's definition.
     */
    public void applyPreset(ToolPreset preset)
    {
        Set<String> disabledTools = preset.getDisabledTools();
        if (disabledTools != null)
        {
            setDisabledTools(disabledTools);
        }
    }

    /**
     * Returns the count of currently enabled tools.
     * Only counts known tools (those belonging to a ToolGroup) to avoid
     * incorrect counts from obsolete tool names left in preferences.
     */
    public int getEnabledToolCount()
    {
        Set<String> disabled = getDisabledTools();
        int enabled = 0;
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (!disabled.contains(toolName))
                {
                    enabled++;
                }
            }
        }
        return enabled;
    }

    /**
     * Parses a comma-separated string of disabled tool names.
     */
    static Set<String> parseDisabledTools(String value)
    {
        if (value == null || value.isBlank())
        {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(",")) //$NON-NLS-1$
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Serializes a set of disabled tool names to a comma-separated string.
     */
    static String serializeDisabledTools(Set<String> disabledTools)
    {
        if (disabledTools == null || disabledTools.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return disabledTools.stream()
            .sorted()
            .collect(Collectors.joining(",")); //$NON-NLS-1$
    }

    private IPreferenceStore getStore()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }
}
