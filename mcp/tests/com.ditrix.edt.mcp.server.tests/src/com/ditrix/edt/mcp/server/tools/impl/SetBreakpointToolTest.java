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

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SetBreakpointTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation
 * branches that return (as {@code ToolResult.error(...).toJson()}) before any
 * live workspace/debug access. Project/file resolution and breakpoint creation
 * need a live workspace and are covered by the E2E suite.
 */
public class SetBreakpointToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_breakpoint", new SetBreakpointTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetBreakpointTool.NAME, new SetBreakpointTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetBreakpointTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetBreakpointTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SetBreakpointTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"module\"")); // legacy alias //$NON-NLS-1$
        assertTrue(schema.contains("\"lineNumber\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingModule()
    {
        Map<String, String> params = new HashMap<>();
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingLineNumber()
    {
        Map<String, String> params = new HashMap<>();
        params.put("module", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // lineNumber omitted -> defaults to -1 -> "lineNumber must be >= 1".
        // Assert on a prefix without '>': Gson escapes '>' as \u003e in the
        // serialized JSON, so matching the literal ">=" would never hit.
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("lineNumber must be")); //$NON-NLS-1$
    }

    @Test
    public void testModuleRelativePathRequiresProjectName()
    {
        Map<String, String> params = new HashMap<>();
        // a module-relative (non-absolute) path with no projectName, via the legacy 'module' alias
        params.put("module", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("projectName is required when modulePath is given as an EDT module path")); //$NON-NLS-1$
    }

    @Test
    public void testModulePathPrimaryIsRead()
    {
        Map<String, String> params = new HashMap<>();
        // canonical 'modulePath' (module-relative) with no projectName reaches the same
        // projectName-required guard, proving modulePath is read as the primary param.
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("projectName is required when modulePath is given as an EDT module path")); //$NON-NLS-1$
    }
}
