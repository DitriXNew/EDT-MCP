/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The validator's exceptions name real parameters, and the real tools keep their accepted forms. */
public class ToolArgumentValidatorRegistryTest
{
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar.registerAll(registry);
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    @Test
    public void everyExceptionNamesADeclaredParameter()
    {
        List<String> problems = new ArrayList<>();
        Set<String> keys = new TreeSet<>(ToolArgumentValidator.FREE_VALUE);
        keys.addAll(ToolArgumentValidator.EXTRA_ENUM_VALUES.keySet());
        for (String key : keys)
        {
            JsonObject declared = declared(key);
            if (declared == null)
            {
                problems.add(key + " is not a declared parameter"); //$NON-NLS-1$
            }
            else if (ToolArgumentValidator.EXTRA_ENUM_VALUES.containsKey(key) && !declared.has("enum")) //$NON-NLS-1$
            {
                problems.add(key + " has aliases but no enum"); //$NON-NLS-1$
            }
        }
        assertEquals(problems.toString(), 0, problems.size());
    }

    @Test
    public void realToolsKeepTheirDeliberateForms()
    {
        assertNull(call("step", "kind", "return")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("list_configurations", "type", "runtimeClient")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("create_metadata", "callType", "Override")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("get_platform_documentation", "responseFormat", "bogus_fmt")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("rename_metadata_object", "disableIndices", List.of(2.0, 3.0))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(call("get_event_log", "session", 5.0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(call("run_yaxunit_tests", "debug", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("run_yaxunit_tests", "tags", "smoke,nodb")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("enable_toolset", "toolsets", "core")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("modify_metadata", "template", "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("get_comparison_node", "nodeId", "2.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("step", "kind", " OVER ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(call("create_infobase", "mode", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(call("wait_for_break", "timeout", "abc")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(call("step", "kind", "sideways")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String roleProperties = call("modify_metadata", "roleProperties", "setForNewObjects"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(roleProperties, roleProperties.contains("roleProperties must be an object")); //$NON-NLS-1$
    }

    @Test
    public void noToolRefusesAnEmptyCall()
    {
        for (IMcpTool tool : registry.getAllTools())
        {
            assertNull(tool.getName(), ToolArgumentValidator.validate(tool.getName(), tool.getInputSchema(),
                new HashMap<>()));
        }
    }

    private String call(String toolName, String param, Object value)
    {
        IMcpTool tool = registry.getTool(toolName);
        assertNotNull(toolName, tool);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(param, value);
        return ToolArgumentValidator.validate(toolName, tool.getInputSchema(), args);
    }

    private JsonObject declared(String key)
    {
        int dot = key.indexOf('.');
        IMcpTool tool = registry.getTool(key.substring(0, dot));
        if (tool == null)
        {
            return null;
        }
        JsonObject properties = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        return properties == null ? null : properties.getAsJsonObject(key.substring(dot + 1));
    }
}
