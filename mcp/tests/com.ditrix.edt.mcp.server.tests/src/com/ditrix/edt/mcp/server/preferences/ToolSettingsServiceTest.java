/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Set;

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
}
