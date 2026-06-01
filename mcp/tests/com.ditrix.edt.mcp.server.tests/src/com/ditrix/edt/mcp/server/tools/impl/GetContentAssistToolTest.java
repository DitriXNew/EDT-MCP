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
 * Tests for {@link GetContentAssistTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live EDT access. {@code execute()} validates
 * projectName, filePath, and the numeric/positive line+column before the first
 * {@code ResourcesPlugin.getWorkspace()} / {@code PlatformUI.getWorkbench()}
 * call. The actual content-assist invocation needs a live workbench/editor and
 * is covered by the E2E suite.
 */
public class GetContentAssistToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_content_assist", new GetContentAssistTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetContentAssistTool.NAME, new GetContentAssistTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetContentAssistTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetContentAssistTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetContentAssistTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"column\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFilePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("filePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericLineOrColumn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // line/column omitted -> parse fails -> "Invalid line or column number"
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testLineColumnMustBePositive()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "src/Foo.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        // Source message is "Line and column must be >= 1"; assert on the prefix
        // without '>': ToolResult.toJson() (Gson) escapes '>' as > in JSON.
        String result = new GetContentAssistTool().execute(params);
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }
}
