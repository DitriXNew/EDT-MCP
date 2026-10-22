/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Predefined tool presets for quick configuration.
 * Each preset defines a set of tools to disable.
 */
public enum ToolPreset
{
    ALL_TOOLS("all-tools", //$NON-NLS-1$
        "All Tools", "All tools enabled, including the ones off by default", //$NON-NLS-1$ //$NON-NLS-2$
        Collections.emptySet()),

    ANALYSIS_ONLY("analysis-only", //$NON-NLS-1$
        "Analysis Only", //$NON-NLS-1$
        "Read-only analysis - no code changes, no debugging", //$NON-NLS-1$
        buildAnalysisOnlyDisabled()),

    CODE_REVIEW("code-review", //$NON-NLS-1$
        "Code Review", //$NON-NLS-1$
        "Analysis + BSL code reading (no writing)", //$NON-NLS-1$
        buildCodeReviewDisabled()),

    DEVELOPMENT("development", //$NON-NLS-1$
        "Development", //$NON-NLS-1$
        "Full development without debugging", //$NON-NLS-1$
        disabledFor(ToolGroup.DEBUG)),

    CUSTOM("custom", //$NON-NLS-1$
        "Custom", "Manually configured", null); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Explicit exceptions to the write hint: comparison takes EDT's single comparison slot but
     * reads the project; toolset enablement changes session visibility only; revalidation refreshes
     * derived markers without discarding workspace content. The policy ratchet checks each one.
     */
    static final Set<String> READ_ONLY_KEEPS_DESPITE_WRITE_HINT = Set.of(
        "compare_configurations", //$NON-NLS-1$
        "enable_toolset", //$NON-NLS-1$
        "revalidate_objects"); //$NON-NLS-1$

    /** A holder initializes the shared policy before the enum constructors call either builder. */
    static final class ReadOnlyPolicy
    {
        /** Writers in groups that the read-only presets disable only in part. */
        static final Set<String> READ_ONLY_EXCLUDED_BY_NAME = Set.of(
            "export_configuration_to_xml", //$NON-NLS-1$
            "import_configuration_from_xml", //$NON-NLS-1$
            "import_project_from_file", //$NON-NLS-1$
            "apply_quick_fix", //$NON-NLS-1$
            "merge_rules", //$NON-NLS-1$
            "delete_project", //$NON-NLS-1$
            "switch_git_branch", //$NON-NLS-1$
            "create_git_branch", //$NON-NLS-1$
            "set_branch_infobase", //$NON-NLS-1$
            "create_project", //$NON-NLS-1$
            "resync_to_disk", //$NON-NLS-1$
            "project_checks", //$NON-NLS-1$
            "clean_project"); //$NON-NLS-1$

        private ReadOnlyPolicy()
        {
            // Constants only.
        }
    }

    private final String id;
    private final String displayName;
    private final String description;
    private final Set<String> disabledTools;

    ToolPreset(String id, String displayName, String description, Set<String> disabledTools)
    {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.disabledTools = disabledTools;
    }

    /** Returns the stable preference ID, independent of enum order and display text. */
    public String getId()
    {
        return id;
    }

    /** Resolves a stored ID; missing or unknown provenance is custom. */
    public static ToolPreset fromId(String id)
    {
        for (ToolPreset preset : values())
        {
            if (preset.id.equals(id))
            {
                return preset;
            }
        }
        return CUSTOM;
    }

    /** Whether this preset promises read-only operation, even after owner edits. */
    public boolean isReadOnly()
    {
        return this == ANALYSIS_ONLY || this == CODE_REVIEW;
    }

    /** Whether this preset promises no debugging, even after owner edits. */
    public boolean isNoDebug()
    {
        return isReadOnly() || this == DEVELOPMENT;
    }

    /**
     * Returns the human-readable preset name.
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the preset description.
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the set of tool names to disable, or null for CUSTOM preset.
     */
    public Set<String> getDisabledTools()
    {
        return disabledTools;
    }

    /**
     * Finds the preset that matches the given disabled tools set, or CUSTOM if none match.
     * Unknown tool names (e.g. from older plugin versions) are ignored during comparison.
     */
    public static ToolPreset matchPreset(Set<String> disabledTools)
    {
        // Filter out tool names not known to any group (stale after upgrades)
        Set<String> knownDisabled = new HashSet<>();
        for (String tool : disabledTools)
        {
            if (ToolGroup.getGroupForTool(tool) != null)
            {
                knownDisabled.add(tool);
            }
        }

        for (ToolPreset preset : values())
        {
            if (preset == CUSTOM)
            {
                continue;
            }
            if (preset.disabledTools.equals(knownDisabled))
            {
                return preset;
            }
        }
        return CUSTOM;
    }

    /**
     * Collects all tool names from the given groups into a disabled set.
     */
    private static Set<String> disabledFor(ToolGroup... groups)
    {
        Set<String> disabled = withDefaultsOff();
        for (ToolGroup group : groups)
        {
            disabled.addAll(group.getToolNames());
        }
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * Starts a preset's disabled set from the tools that are OFF BY DEFAULT
     * ({@link PreferenceConstants#DEFAULT_DISABLED_TOOLS}).
     * <p>
     * A preset must never silently ENABLE a tool that ships disabled: picking "Analysis Only"
     * expresses "less than the default", so it cannot be the act that switches on the raw
     * {@code git} command tool. The only preset that turns those on is {@link #ALL_TOOLS}, whose
     * name and description say exactly that - otherwise a default-off tool is enabled only by
     * ticking it explicitly.
     *
     * @return a fresh, mutable set holding the default-off tool names
     */
    private static Set<String> withDefaultsOff()
    {
        return new HashSet<>(
            ToolSettingsService.parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS));
    }

    /**
     * Builds Code Review: disable applications, debugging, refactoring, translation and BSL
     * writing, plus the shared workspace, git and comparison writers. CORE and GIT remain partly
     * available for reads, so their writers must be excluded by name.
     * <p>
     * Comparison reads, the in-memory toolset switch and non-discarding revalidation stay enabled
     * under the documented keep-list. {@code export_common_picture} is a pure model read returning
     * PNG bytes, so it also stays enabled.
     */
    private static Set<String> buildCodeReviewDisabled()
    {
        Set<String> disabled = withDefaultsOff();
        disabled.addAll(ToolGroup.APPLICATIONS.getToolNames());
        disabled.addAll(ToolGroup.DEBUG.getToolNames());
        disabled.addAll(ToolGroup.REFACTORING.getToolNames());
        disabled.addAll(ToolGroup.TRANSLATION.getToolNames());
        disabled.add("write_module_source"); //$NON-NLS-1$
        disabled.addAll(ReadOnlyPolicy.READ_ONLY_EXCLUDED_BY_NAME);
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * Builds Analysis Only: the same read-only exclusions as Code Review, with the entire BSL
     * Code group disabled as well. Analysis is limited to metadata and problems. CORE and GIT
     * remain partly available for reads; their workspace and git writers are excluded by name.
     * <p>
     * {@code compare_configurations}, {@code get_comparison_node}, {@code revalidate_objects},
     * {@code enable_toolset} and the model-only {@code export_common_picture} stay enabled.
     */
    private static Set<String> buildAnalysisOnlyDisabled()
    {
        Set<String> disabled = withDefaultsOff();
        disabled.addAll(ToolGroup.APPLICATIONS.getToolNames());
        disabled.addAll(ToolGroup.DEBUG.getToolNames());
        disabled.addAll(ToolGroup.BSL_CODE.getToolNames());
        disabled.addAll(ToolGroup.REFACTORING.getToolNames());
        disabled.addAll(ToolGroup.TRANSLATION.getToolNames());
        disabled.addAll(ReadOnlyPolicy.READ_ONLY_EXCLUDED_BY_NAME);
        return Collections.unmodifiableSet(disabled);
    }
}
