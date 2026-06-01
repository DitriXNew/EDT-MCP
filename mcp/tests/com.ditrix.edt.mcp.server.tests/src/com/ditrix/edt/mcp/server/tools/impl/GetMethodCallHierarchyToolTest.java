/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetMethodCallHierarchyTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live EDT access. {@code execute()} validates
 * projectName, modulePath, methodName and the direction value before the first
 * {@code PlatformUI.getWorkbench()} call, so those returns are reachable
 * headlessly. Project/module resolution and the reference-finder walk need a
 * live workbench and are covered by the E2E suite.
 */
public class GetMethodCallHierarchyToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_method_call_hierarchy", new GetMethodCallHierarchyTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMethodCallHierarchyTool.NAME, new GetMethodCallHierarchyTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMethodCallHierarchyTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMethodCallHierarchyTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMethodCallHierarchyTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"methodName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"direction\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidDirection()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        // A non-empty direction that is neither "callers" nor "callees" is rejected
        // before any workbench access (an empty/missing direction defaults to callers).
        params.put("direction", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMethodCallHierarchyTool().execute(params);
        assertTrue(result.contains("direction must be")); //$NON-NLS-1$
    }

    // ==================== Call-qualifier derivation (pure logic) ====================

    @Test
    public void testExtractModuleNameCommonModule()
    {
        // The qualifier used in "Module.Method(...)" calls is the metadata object name,
        // i.e. the folder above the .bsl file.
        assertEquals("AccountingClientServer", //$NON-NLS-1$
            GetMethodCallHierarchyTool.extractModuleName("CommonModules/AccountingClientServer/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModuleNameManagerModule()
    {
        assertEquals("SalesOrder", //$NON-NLS-1$
            GetMethodCallHierarchyTool.extractModuleName("Documents/SalesOrder/ManagerModule.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModuleNameDegenerate()
    {
        assertNull(GetMethodCallHierarchyTool.extractModuleName(null));
        assertNull(GetMethodCallHierarchyTool.extractModuleName("Module.bsl")); //$NON-NLS-1$
    }
}
