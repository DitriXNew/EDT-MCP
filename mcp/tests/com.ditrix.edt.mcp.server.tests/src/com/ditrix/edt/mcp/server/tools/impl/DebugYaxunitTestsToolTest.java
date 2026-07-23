/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link DebugYaxunitTestsTool}.
 *
 * Mirrors {@link RunYaxunitTestsToolTest} — verifies tool identity, response
 * type, schema, and parameter validation at the entry point. The actual
 * Eclipse launch path is out of scope (needs runtime).
 */
public class DebugYaxunitTestsToolTest
{
    @Test
    public void testToolName()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        assertEquals("debug_yaxunit_tests", tool.getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        // Deprecated alias now forwards to run_yaxunit_tests(debug=true), which is
        // a MARKDOWN tool, so the alias inherits the default MARKDOWN response type.
        DebugYaxunitTestsTool tool = new DebugYaxunitTestsTool();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsFalseByDefault()
    {
        // #270: this class stays on the IMcpTool default (false) rather than overriding
        // connectsToInfobase(). This is intentional, not an oversight: execute() forwards
        // synchronously to a RunYaxunitTestsTool instance whose OWN Job body already
        // brackets InfobaseAuthDialogSuppressor.markActivityStart()/markActivityEnd()
        // around the connection-reaching work (see RunYaxunitTestsTool.runPrepJobBody), so
        // the auth-dialog suppression is covered by the delegate regardless of what this
        // alias's own dispatch-level flag says.
        DebugYaxunitTestsTool tool = new DebugYaxunitTestsTool();
        assertFalse(tool.connectsToInfobase());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue("description should not be empty", desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresExpectedFields()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"applicationId\""));
        assertTrue(schema.contains("\"extensions\""));
        assertTrue(schema.contains("\"modules\""));
        assertTrue(schema.contains("\"tests\""));
        assertTrue("schema must include updateBeforeLaunch (auto-chain switch)",
            schema.contains("\"updateBeforeLaunch\""));
        // Parity with run_yaxunit_tests: the deprecated alias declares (and forwards)
        // updateScope so callers can narrow the pre-launch recompute to a specific
        // extension. The production code already declares+forwards it; pin it here.
        assertTrue("schema must include updateScope (parity with run_yaxunit_tests)",
            schema.contains("\"updateScope\""));
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String guide = tool.getGuide();
        assertNotNull(guide);
        assertTrue("guide should be non-empty", guide.length() > 0);
        // Detail moved out of description/schema must live in the guide.
        assertTrue("guide must explain the wait_for_break next step",
            guide.contains("wait_for_break"));
        assertTrue("guide must document the updateBeforeLaunch auto-chain",
            guide.contains("updateBeforeLaunch"));
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "some-app-id");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue("must mention projectName", result.contains("projectName"));
        // JSON tool: error field is "error", not "**Error:**"
        assertTrue("must indicate error", result.contains("\"error\""));
    }

    @Test
    public void testExecuteMissingApplicationId()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue("must mention applicationId", result.contains("applicationId"));
        assertTrue("must indicate error", result.contains("\"error\""));
    }

    @Test
    public void testExecuteEmptyParams()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue("must indicate error", result.contains("\"error\""));
    }

    @Test
    public void testDeprecatedAliasPointsToRunTool()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        assertTrue("class must be annotated @Deprecated",
            tool.getClass().isAnnotationPresent(Deprecated.class));
        assertTrue("description must steer callers to run_yaxunit_tests",
            tool.getDescription().contains("run_yaxunit_tests"));
        assertTrue("guide must document the run_yaxunit_tests(debug=true) replacement",
            tool.getGuide().contains("debug=true"));
    }
}
