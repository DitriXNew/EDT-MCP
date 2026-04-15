/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Predefined tool presets for quick configuration.
 * Each preset defines a set of tools to disable.
 */
public enum ToolPreset
{
    ALL_TOOLS("All Tools", "All tools enabled", //$NON-NLS-1$ //$NON-NLS-2$
        Collections.emptySet()),

    ANALYSIS_ONLY("Analysis Only", //$NON-NLS-1$
        "Read-only analysis - no code changes, no debugging", //$NON-NLS-1$
        disabledFor(ToolGroup.APPLICATIONS, ToolGroup.DEBUG, ToolGroup.BSL_CODE, ToolGroup.REFACTORING)),

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
     */
    public static ToolPreset matchPreset(Set<String> disabledTools)
    {
        for (ToolPreset preset : values())
        {
            if (preset == CUSTOM)
            {
                continue;
            }
            if (preset.disabledTools.equals(disabledTools))
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
        Set<String> disabled = new HashSet<>();
        for (ToolGroup group : groups)
        {
            disabled.addAll(group.getToolNames());
        }
        return Collections.unmodifiableSet(disabled);
    }

    /**
     * Builds the Code Review preset: disable apps, debug, refactoring, and write_module_source.
     */
    private static Set<String> buildCodeReviewDisabled()
    {
        Set<String> disabled = new HashSet<>();
        disabled.addAll(ToolGroup.APPLICATIONS.getToolNames());
        disabled.addAll(ToolGroup.DEBUG.getToolNames());
        disabled.addAll(ToolGroup.REFACTORING.getToolNames());
        disabled.add("write_module_source"); //$NON-NLS-1$
        return Collections.unmodifiableSet(disabled);
    }
}
