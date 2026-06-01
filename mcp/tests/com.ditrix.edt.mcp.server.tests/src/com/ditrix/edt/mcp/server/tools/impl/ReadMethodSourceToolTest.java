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
 * Tests for {@link ReadMethodSourceTool}.
 * <p>
 * Covers tool metadata, the input schema, and the three required-argument
 * validation branches (projectName, modulePath, methodName) that return before
 * the first {@code PlatformUI.getWorkbench()} call. Method extraction needs a
 * live workbench and the loaded BSL model and is covered by the E2E suite.
 */
public class ReadMethodSourceToolTest
{
    @Test
    public void testName()
    {
        assertEquals("read_method_source", new ReadMethodSourceTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ReadMethodSourceTool.NAME, new ReadMethodSourceTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ReadMethodSourceTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ReadMethodSourceTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ReadMethodSourceTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"methodName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue(result.contains("methodName is required")); //$NON-NLS-1$
    }
}
