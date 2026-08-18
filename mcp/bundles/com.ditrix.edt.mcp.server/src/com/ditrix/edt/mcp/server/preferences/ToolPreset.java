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
    ALL_TOOLS("All Tools", "All tools enabled, including the ones off by default", //$NON-NLS-1$ //$NON-NLS-2$
        Collections.emptySet()),

    ANALYSIS_ONLY("Analysis Only", //$NON-NLS-1$
        "Read-only analysis - no code changes, no debugging", //$NON-NLS-1$
        buildAnalysisOnlyDisabled()),

    CODE_REVIEW("Code Review", //$NON-NLS-1$
        "Analysis + BSL code reading (no writing)", //$NON-NLS-1$
        buildCodeReviewDisabled()),

    DEVELOPMENT("Development", //$NON-NLS-1$
        "Full development without debugging", //$NON-NLS-1$
        disabledFor(ToolGroup.DEBUG)),

    CUSTOM("Custom", "Manually configured", null); //$NON-NLS-1$ //$NON-NLS-2$

    private final String displayName;
    private final String description;
    private final Set<String> disabledTools;

    ToolPreset(String displayName, String description, Set<String> disabledTools)
    {
        this.displayName = displayName;
        this.description = description;
        this.disabledTools = disabledTools;
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
     * Builds the Code Review preset: disable apps (including external-object builds and credential
     * writes), debug, refactoring (including adoption into an extension), translation,
     * write_module_source, apply_quick_fix, and the state-mutating workspace export/import tools.
     * {@code export_common_picture} remains enabled: despite its name, it is a pure model read that
     * returns the selected PNG as base64 and never writes a file.
     */
    private static Set<String> buildCodeReviewDisabled()
    {
        Set<String> disabled = withDefaultsOff();
        disabled.addAll(ToolGroup.APPLICATIONS.getToolNames());
        disabled.addAll(ToolGroup.DEBUG.getToolNames());
        disabled.addAll(ToolGroup.REFACTORING.getToolNames());
        disabled.addAll(ToolGroup.TRANSLATION.getToolNames());
        disabled.add("write_module_source"); //$NON-NLS-1$
        disabled.add("export_configuration_to_xml"); //$NON-NLS-1$
        disabled.add("import_configuration_from_xml"); //$NON-NLS-1$
        disabled.add("apply_quick_fix"); //$NON-NLS-1$
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * Builds the Analysis Only preset: disable apps (including external-object builds and credential
     * writes), debug, the entire BSL Code group (both read and write tools — analysis is metadata-
     * and error-level only), refactoring (including adoption into an extension), apply_quick_fix,
     * the state-mutating workspace export/import tools, and the LanguageTool translation tools.
     * {@code export_common_picture} remains enabled because it only reads model content and returns
     * base64; it does not export to the filesystem.
     */
    private static Set<String> buildAnalysisOnlyDisabled()
    {
        Set<String> disabled = withDefaultsOff();
        disabled.addAll(ToolGroup.APPLICATIONS.getToolNames());
        disabled.addAll(ToolGroup.DEBUG.getToolNames());
        disabled.addAll(ToolGroup.BSL_CODE.getToolNames());
        disabled.addAll(ToolGroup.REFACTORING.getToolNames());
        disabled.addAll(ToolGroup.TRANSLATION.getToolNames());
        disabled.add("export_configuration_to_xml"); //$NON-NLS-1$
        disabled.add("import_configuration_from_xml"); //$NON-NLS-1$
        disabled.add("apply_quick_fix"); //$NON-NLS-1$
        return Collections.unmodifiableSet(disabled);
    }
}
