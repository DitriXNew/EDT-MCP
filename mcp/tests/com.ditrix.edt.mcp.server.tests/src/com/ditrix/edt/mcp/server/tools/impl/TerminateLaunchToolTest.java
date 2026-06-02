/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link TerminateLaunchTool}.
 *
 * Verifies tool identity, schema completeness, and the parameter validation
 * branches in {@code validateSelection}. Does not exercise the actual
 * Eclipse-runtime termination flow.
 */
public class TerminateLaunchToolTest
{
    // === Identity ===

    @Test
    public void testToolName()
    {
        IMcpTool tool = new TerminateLaunchTool();
        assertEquals("terminate_launch", tool.getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        IMcpTool tool = new TerminateLaunchTool();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue("description should not be empty", desc.length() > 0);
        assertTrue("description should mention EDT-only guarantee",
            desc.toLowerCase().contains("edt"));
    }

    // === Schema ===

    @Test
    public void testSchemaDeclaresAllParameters()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"applicationId\""));
        assertTrue(schema.contains("\"all\""));
        assertTrue(schema.contains("\"confirm\""));
        assertTrue(schema.contains("\"force\""));
        assertTrue(schema.contains("\"timeoutSeconds\""));
        assertTrue(schema.contains("\"includeAttach\""));
    }

    @Test
    public void testSchemaHasNoRequiredFields()
    {
        // All selection modes are optional individually — the tool validates
        // their combinations at runtime, not via JSON schema.
        IMcpTool tool = new TerminateLaunchTool();
        String schema = tool.getInputSchema();
        // Required list should be empty (i.e. "required":[])
        assertTrue("required array should be present and empty",
            schema.contains("\"required\":[]"));
    }

    @Test
    public void testFileNameByConfigName()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "My Config / ThinClient");
        String name = tool.getResultFileName(params);
        assertTrue("file name should derive from config name",
            name.startsWith("terminate-") && name.endsWith(".md"));
        // Should be sanitised — no slashes or spaces in the middle
        assertTrue("file name should be sanitised", !name.contains(" "));
        assertTrue("file name should be sanitised", !name.contains("/"));
    }

    @Test
    public void testFileNameForAllMode()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        assertEquals("terminate-all.md", tool.getResultFileName(params));
    }

    @Test
    public void testFileNameForAllModeWithProject()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        params.put("projectName", "MyProject");
        assertEquals("terminate-all-myproject.md", tool.getResultFileName(params));
    }

    // === validateSelection — error messages from execute() ===

    @Test
    public void testEmptyParamsReturnsProvideOneOfError()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue("must be a structured error", result.contains("\"success\":false"));
        assertTrue("must mention 'Provide exactly one of'",
            result.contains("Provide exactly one of"));
    }

    @Test
    public void testApplicationIdWithoutProjectName()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "8e2a-fake-id");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must explain applicationId requires projectName",
            result.contains("`applicationId` requires `projectName`"));
    }

    @Test
    public void testProjectNameAloneIsAmbiguous()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must explain projectName alone is not a selection",
            result.contains("`projectName` alone is not a selection"));
    }

    @Test
    public void testAllWithoutConfirm()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        // The literal `confirm=true` would be unicode-escaped by Gson (it escapes
        // '='), so assert on delimiter-free substrings of the same message.
        assertTrue("must require confirmation",
            result.contains("Confirmation required")
                && result.contains("confirm"));
    }

    @Test
    public void testApplicationIdCannotBeCombinedWithAll()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "8e2a-fake-id");
        params.put("all", "true");
        params.put("confirm", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        // `all=true` contains '=', which Gson unicode-escapes; assert on the
        // delimiter-free leading portion of the same message instead.
        assertTrue("must explain applicationId vs all incompatibility",
            result.contains("`applicationId` cannot be combined with `all"));
    }

    @Test
    public void testNameAndAllAreMutuallyExclusive()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "SomeConfig");
        params.put("all", "true");
        params.put("confirm", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must report mutual exclusivity",
            result.contains("mutually exclusive"));
    }

    @Test
    public void testNameAndProjectAppIdAreMutuallyExclusive()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "SomeConfig");
        params.put("projectName", "Proj");
        params.put("applicationId", "app-id");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must report mutual exclusivity",
            result.contains("mutually exclusive"));
    }
}
