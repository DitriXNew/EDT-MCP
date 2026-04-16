/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link ToolPreset} enum.
 * Verifies preset definitions, matching logic, and tool coverage.
 */
public class ToolPresetTest
{
    // === Preset definitions ===

    @Test
    public void testAllPresetsHaveDisplayName()
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            assertNotNull(preset.getDisplayName());
            assertFalse(preset.getDisplayName().isEmpty());
        }
    }

    @Test
    public void testAllPresetsHaveDescription()
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            assertNotNull(preset.getDescription());
            assertFalse(preset.getDescription().isEmpty());
        }
    }

    @Test
    public void testFivePresets()
    {
        assertEquals(5, ToolPreset.values().length);
    }

    // === ALL_TOOLS preset ===

    @Test
    public void testAllToolsPresetDisablesNothing()
    {
        Set<String> disabled = ToolPreset.ALL_TOOLS.getDisabledTools();
        assertNotNull(disabled);
        assertTrue("ALL_TOOLS should have no disabled tools", disabled.isEmpty());
    }

    // === ANALYSIS_ONLY preset ===

    @Test
    public void testAnalysisOnlyDisablesWriteTools()
    {
        Set<String> disabled = ToolPreset.ANALYSIS_ONLY.getDisabledTools();
        assertNotNull(disabled);

        // Should disable applications, debug, BSL code, refactoring
        assertTrue("Should disable debug_launch", disabled.contains("debug_launch"));
        assertTrue("Should disable set_breakpoint", disabled.contains("set_breakpoint"));
        assertTrue("Should disable write_module_source", disabled.contains("write_module_source"));
        assertTrue("Should disable rename_metadata_object", disabled.contains("rename_metadata_object"));

        // Should NOT disable core, problems, code intelligence, tags
        assertFalse("Should not disable get_edt_version", disabled.contains("get_edt_version"));
        assertFalse("Should not disable get_project_errors", disabled.contains("get_project_errors"));
        assertFalse("Should not disable get_metadata_objects", disabled.contains("get_metadata_objects"));
        assertFalse("Should not disable get_tags", disabled.contains("get_tags"));
    }

    // === CODE_REVIEW preset ===

    @Test
    public void testCodeReviewDisablesWriteButNotRead()
    {
        Set<String> disabled = ToolPreset.CODE_REVIEW.getDisabledTools();
        assertNotNull(disabled);

        // Should disable write_module_source but not read
        assertTrue("Should disable write_module_source", disabled.contains("write_module_source"));
        assertFalse("Should not disable read_module_source", disabled.contains("read_module_source"));
        assertFalse("Should not disable search_in_code", disabled.contains("search_in_code"));

        // Should disable refactoring and debug
        assertTrue("Should disable rename_metadata_object", disabled.contains("rename_metadata_object"));
        assertTrue("Should disable set_breakpoint", disabled.contains("set_breakpoint"));
    }

    // === DEVELOPMENT preset ===

    @Test
    public void testDevelopmentDisablesOnlyDebug()
    {
        Set<String> disabled = ToolPreset.DEVELOPMENT.getDisabledTools();
        assertNotNull(disabled);

        // Should disable debug tools
        assertTrue("Should disable set_breakpoint", disabled.contains("set_breakpoint"));
        assertTrue("Should disable resume", disabled.contains("resume"));

        // Should NOT disable BSL code or refactoring
        assertFalse("Should not disable write_module_source", disabled.contains("write_module_source"));
        assertFalse("Should not disable rename_metadata_object", disabled.contains("rename_metadata_object"));
    }

    // === CUSTOM preset ===

    @Test
    public void testCustomPresetHasNullDisabledTools()
    {
        assertNull("CUSTOM preset should have null disabled tools", ToolPreset.CUSTOM.getDisabledTools());
    }

    // === Preset matching ===

    @Test
    public void testMatchPresetAllTools()
    {
        assertEquals(ToolPreset.ALL_TOOLS, ToolPreset.matchPreset(Set.of()));
    }

    @Test
    public void testMatchPresetAnalysisOnly()
    {
        Set<String> disabled = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        assertEquals(ToolPreset.ANALYSIS_ONLY, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMatchPresetDevelopment()
    {
        Set<String> disabled = new HashSet<>(ToolPreset.DEVELOPMENT.getDisabledTools());
        assertEquals(ToolPreset.DEVELOPMENT, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMatchPresetCustomForUnknown()
    {
        Set<String> disabled = Set.of("get_edt_version", "list_projects");
        assertEquals(ToolPreset.CUSTOM, ToolPreset.matchPreset(disabled));
    }

    @Test
    public void testMatchPresetEmptySet()
    {
        assertEquals(ToolPreset.ALL_TOOLS, ToolPreset.matchPreset(new HashSet<>()));
    }

    @Test
    public void testMatchPresetIgnoresStaleToolNames()
    {
        // Simulate stale tool names from an older plugin version
        Set<String> disabled = new HashSet<>();
        disabled.add("obsolete_tool_from_old_version");
        // Empty known tools = should match ALL_TOOLS
        assertEquals(ToolPreset.ALL_TOOLS, ToolPreset.matchPreset(disabled));

        // Stale names mixed with valid preset tools should still match
        Set<String> disabledWithStale = new HashSet<>(ToolPreset.DEVELOPMENT.getDisabledTools());
        disabledWithStale.add("another_obsolete_tool");
        assertEquals(ToolPreset.DEVELOPMENT, ToolPreset.matchPreset(disabledWithStale));
    }

    // === Disabled tools validity ===

    @Test
    public void testAllDisabledToolsBelongToGroups()
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue; // CUSTOM preset
            }
            for (String toolName : disabled)
            {
                assertNotNull("Disabled tool '" + toolName + "' in preset " + preset.name()
                    + " should belong to a group", ToolGroup.getGroupForTool(toolName));
            }
        }
    }

    // === Immutability ===

    @Test
    public void testDisabledToolsAreUnmodifiable()
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue;
            }
            try
            {
                disabled.add("hacked");
                fail("Disabled tools set should be unmodifiable for " + preset.name());
            }
            catch (UnsupportedOperationException e)
            {
                // Expected
            }
        }
    }
}
