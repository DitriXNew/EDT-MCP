/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link ToolGroup} enum.
 * Verifies tool grouping, membership, and lookup.
 */
public class ToolGroupTest
{
    // === Group definitions ===

    @Test
    public void testAllGroupsHaveTools()
    {
        for (ToolGroup group : ToolGroup.values())
        {
            assertFalse("Group " + group.getId() + " should have tools",
                group.getToolNames().isEmpty());
        }
    }

    @Test
    public void testAllGroupsHaveDisplayName()
    {
        for (ToolGroup group : ToolGroup.values())
        {
            assertNotNull("Group should have display name", group.getDisplayName());
            assertFalse("Display name should not be empty", group.getDisplayName().isEmpty());
        }
    }

    @Test
    public void testAllGroupsHaveDescription()
    {
        for (ToolGroup group : ToolGroup.values())
        {
            assertNotNull("Group should have description", group.getDescription());
            assertFalse("Description should not be empty", group.getDescription().isEmpty());
        }
    }

    @Test
    public void testAllGroupsHaveUniqueIds()
    {
        Set<String> ids = new HashSet<>();
        for (ToolGroup group : ToolGroup.values())
        {
            assertTrue("Duplicate group ID: " + group.getId(), ids.add(group.getId()));
        }
    }

    @Test
    public void testNineGroups()
    {
        assertEquals("Should have 9 tool groups", 9, ToolGroup.values().length);
    }

    // === Tool membership ===

    @Test
    public void testNoToolInMultipleGroups()
    {
        Set<String> allTools = new HashSet<>();
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                assertTrue("Tool '" + toolName + "' is in multiple groups", allTools.add(toolName));
            }
        }
    }

    @Test
    public void testTotalToolCount()
    {
        int count = 0;
        for (ToolGroup group : ToolGroup.values())
        {
            count += group.getToolNames().size();
        }
        assertEquals("Total tool count should match getTotalToolCount()",
            count, ToolGroup.getTotalToolCount());
        assertTrue("Total tool count should be greater than zero", count > 0);
    }

    @Test
    public void testToolNamesAreUnmodifiable()
    {
        List<String> names = ToolGroup.CORE.getToolNames();
        try
        {
            names.add("hacked"); //$NON-NLS-1$
            fail("Tool names list should be unmodifiable");
        }
        catch (UnsupportedOperationException e)
        {
            // Expected
        }
    }

    // === Reverse lookup ===

    @Test
    public void testGetGroupForToolFound()
    {
        assertEquals(ToolGroup.CORE, ToolGroup.getGroupForTool("get_edt_version"));
        assertEquals(ToolGroup.PROBLEMS, ToolGroup.getGroupForTool("get_project_errors"));
        assertEquals(ToolGroup.APPLICATIONS, ToolGroup.getGroupForTool("list_configurations"));
        assertEquals(ToolGroup.DEBUG, ToolGroup.getGroupForTool("set_breakpoint"));
        assertEquals(ToolGroup.BSL_CODE, ToolGroup.getGroupForTool("read_module_source"));
        assertEquals(ToolGroup.REFACTORING, ToolGroup.getGroupForTool("rename_metadata_object"));
    }

    @Test
    public void testGetGroupForToolNotFound()
    {
        assertNull(ToolGroup.getGroupForTool("nonexistent_tool"));
    }

    @Test
    public void testGetGroupForToolNull()
    {
        assertNull(ToolGroup.getGroupForTool(null));
    }

    @Test
    public void testEveryToolHasReverseLookup()
    {
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                assertEquals("Tool '" + toolName + "' reverse lookup should return its group",
                    group, ToolGroup.getGroupForTool(toolName));
            }
        }
    }

    // === Specific group content ===

    @Test
    public void testCoreGroupContents()
    {
        List<String> tools = ToolGroup.CORE.getToolNames();
        assertTrue(tools.contains("get_edt_version"));
        assertTrue(tools.contains("list_projects"));
        assertTrue(tools.contains("get_configuration_properties"));
        assertEquals(6, tools.size());
    }

    @Test
    public void testDebugGroupContents()
    {
        List<String> tools = ToolGroup.DEBUG.getToolNames();
        assertTrue(tools.contains("set_breakpoint"));
        assertTrue(tools.contains("resume"));
        assertTrue(tools.contains("get_variables"));
        assertEquals(12, tools.size());
    }

    @Test
    public void testRefactoringGroupContents()
    {
        List<String> tools = ToolGroup.REFACTORING.getToolNames();
        assertTrue(tools.contains("rename_metadata_object"));
        assertTrue(tools.contains("delete_metadata_object"));
        assertTrue(tools.contains("add_metadata_attribute"));
        assertEquals(3, tools.size());
    }
}
