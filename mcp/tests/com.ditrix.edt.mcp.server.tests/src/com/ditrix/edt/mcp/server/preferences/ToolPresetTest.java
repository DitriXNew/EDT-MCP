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
        assertTrue("Should disable adopt_metadata_object", //$NON-NLS-1$
            disabled.contains("adopt_metadata_object")); //$NON-NLS-1$
        assertTrue("Should disable build_external_objects", //$NON-NLS-1$
            disabled.contains("build_external_objects")); //$NON-NLS-1$
        assertTrue("Should disable set_infobase_credentials", //$NON-NLS-1$
            disabled.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertTrue("Should disable destructive cancel_job", //$NON-NLS-1$
            disabled.contains("cancel_job")); //$NON-NLS-1$
        assertTrue("Should disable get_job_status with its disabled job owners", //$NON-NLS-1$
            disabled.contains("get_job_status")); //$NON-NLS-1$

        // Should NOT disable core, problems, code intelligence, tags
        assertFalse("Should not disable get_edt_version", disabled.contains("get_edt_version"));
        assertFalse("Should not disable get_project_errors", disabled.contains("get_project_errors"));
        assertFalse("Should not disable get_metadata_objects", disabled.contains("get_metadata_objects"));
        assertFalse("Should not disable get_tags", disabled.contains("get_tags"));
        assertFalse("Should not disable the read-only export_common_picture", //$NON-NLS-1$
            disabled.contains("export_common_picture")); //$NON-NLS-1$
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
        assertTrue("Should disable adopt_metadata_object", //$NON-NLS-1$
            disabled.contains("adopt_metadata_object")); //$NON-NLS-1$
        assertTrue("Should disable build_external_objects", //$NON-NLS-1$
            disabled.contains("build_external_objects")); //$NON-NLS-1$
        assertTrue("Should disable set_infobase_credentials", //$NON-NLS-1$
            disabled.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertTrue("Should disable destructive cancel_job", //$NON-NLS-1$
            disabled.contains("cancel_job")); //$NON-NLS-1$
        assertTrue("Should disable get_job_status with its disabled job owners", //$NON-NLS-1$
            disabled.contains("get_job_status")); //$NON-NLS-1$
        assertFalse("Should not disable the read-only export_common_picture", //$NON-NLS-1$
            disabled.contains("export_common_picture")); //$NON-NLS-1$
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
        assertTrue("Should disable stop_profiling", //$NON-NLS-1$
            disabled.contains("stop_profiling")); //$NON-NLS-1$

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

    /**
     * apply_quick_fix mutates BSL source (it's the headless "Quick Fix" action), even though it sits
     * in the PROBLEMS group alongside read-only tools like get_project_errors. Both read-only-ish
     * presets must disable it explicitly, or picking them would still leave source mutation callable.
     */
    @Test
    public void testAnalysisOnlyAndCodeReviewDisableApplyQuickFix()
    {
        assertTrue("ANALYSIS_ONLY should disable apply_quick_fix",
            ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains("apply_quick_fix"));
        assertTrue("CODE_REVIEW should disable apply_quick_fix",
            ToolPreset.CODE_REVIEW.getDisabledTools().contains("apply_quick_fix"));

        // Sibling read-only tools in the same PROBLEMS group must stay enabled.
        assertFalse("ANALYSIS_ONLY should not disable get_project_errors",
            ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains("get_project_errors"));
        assertFalse("CODE_REVIEW should not disable get_project_errors",
            ToolPreset.CODE_REVIEW.getDisabledTools().contains("get_project_errors"));
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

    /**
     * A tool that ships DISABLED must stay disabled when a preset is applied: picking "Analysis Only"
     * means "less than the default", so it cannot be the act that switches on the raw git command
     * tool. {@link ToolPreset#ALL_TOOLS} is the single deliberate exception - its name says so.
     */
    @Test
    public void testOnlyAllToolsPresetEnablesTheDefaultOffTools()
    {
        Set<String> defaultOff =
            ToolSettingsService.parseDisabledTools(PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        assertFalse("this test is meaningless without a default-off tool", defaultOff.isEmpty()); //$NON-NLS-1$

        for (ToolPreset preset : ToolPreset.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null || preset == ToolPreset.ALL_TOOLS)
            {
                continue;
            }
            assertTrue(preset.getDisplayName() + " must keep the default-off tools disabled", //$NON-NLS-1$
                disabled.containsAll(defaultOff));
        }
    }
}
