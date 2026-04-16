/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings.ParameterDef;

/**
 * Tests for {@link ToolParameterSettings}.
 * Tests parameter definitions and key building (no Eclipse runtime needed).
 */
public class ToolParameterSettingsTest
{
    private final ToolParameterSettings settings = ToolParameterSettings.getInstance();

    // === Singleton ===

    @Test
    public void testSingleton()
    {
        assertSame(ToolParameterSettings.getInstance(), ToolParameterSettings.getInstance());
    }

    // === Parameter definitions ===

    @Test
    public void testConfigurableToolsExist()
    {
        List<String> tools = settings.getConfigurableToolNames();
        assertFalse("Should have configurable tools", tools.isEmpty());
    }

    @Test
    public void testGetProjectErrorsHasLimitParam()
    {
        List<ParameterDef> params = settings.getParametersForTool("get_project_errors");
        assertFalse("get_project_errors should have parameters", params.isEmpty());
        assertEquals("limit", params.get(0).getName());
    }

    @Test
    public void testSearchInCodeHasTwoParams()
    {
        List<ParameterDef> params = settings.getParametersForTool("search_in_code");
        assertEquals("search_in_code should have 2 parameters", 2, params.size());

        boolean hasMaxResults = false;
        boolean hasContextLines = false;
        for (ParameterDef p : params)
        {
            if ("maxResults".equals(p.getName()))
            {
                hasMaxResults = true;
            }
            if ("contextLines".equals(p.getName()))
            {
                hasContextLines = true;
            }
        }
        assertTrue("Should have maxResults param", hasMaxResults);
        assertTrue("Should have contextLines param", hasContextLines);
    }

    @Test
    public void testNonConfigurableToolReturnsEmpty()
    {
        List<ParameterDef> params = settings.getParametersForTool("get_edt_version");
        assertNotNull(params);
        assertTrue("get_edt_version should have no configurable params", params.isEmpty());
    }

    @Test
    public void testUnknownToolReturnsEmpty()
    {
        List<ParameterDef> params = settings.getParametersForTool("nonexistent_tool");
        assertNotNull(params);
        assertTrue(params.isEmpty());
    }

    // === ParameterDef validation ===

    @Test
    public void testAllParametersHaveValidRanges()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : settings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                assertTrue("Min should be <= max for " + entry.getKey() + "." + param.getName(),
                    param.getMinValue() <= param.getMaxValue());
                assertTrue("Default should be >= min for " + entry.getKey() + "." + param.getName(),
                    param.getDefaultValue() >= param.getMinValue());
                assertTrue("Default should be <= max for " + entry.getKey() + "." + param.getName(),
                    param.getDefaultValue() <= param.getMaxValue());
            }
        }
    }

    @Test
    public void testAllParametersHaveDisplayName()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : settings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                assertNotNull("Display name should not be null for " + param.getName(),
                    param.getDisplayName());
                assertFalse("Display name should not be empty for " + param.getName(),
                    param.getDisplayName().isEmpty());
            }
        }
    }

    @Test
    public void testAllParametersHaveDescription()
    {
        for (Map.Entry<String, List<ParameterDef>> entry : settings.getAllParameters().entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                assertNotNull("Description should not be null for " + param.getName(),
                    param.getDescription());
                assertFalse("Description should not be empty for " + param.getName(),
                    param.getDescription().isEmpty());
            }
        }
    }

    // === Key building ===

    @Test
    public void testBuildKey()
    {
        assertEquals("tool.search_in_code.maxResults",
            ToolParameterSettings.buildKey("search_in_code", "maxResults"));
    }

    @Test
    public void testBuildKeyWithLimit()
    {
        assertEquals("tool.get_project_errors.limit",
            ToolParameterSettings.buildKey("get_project_errors", "limit"));
    }

    // === All configurable tools belong to groups ===

    @Test
    public void testAllConfigurableToolsBelongToGroups()
    {
        for (String toolName : settings.getConfigurableToolNames())
        {
            assertNotNull("Configurable tool '" + toolName + "' should belong to a group",
                ToolGroup.getGroupForTool(toolName));
        }
    }
}
