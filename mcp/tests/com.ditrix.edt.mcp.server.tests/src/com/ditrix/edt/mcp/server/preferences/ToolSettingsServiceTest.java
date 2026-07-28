/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/**
 * Tests for {@link ToolSettingsService} static utility methods.
 * Tests the parse/serialize logic without requiring Eclipse runtime.
 */
public class ToolSettingsServiceTest
{
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
}
