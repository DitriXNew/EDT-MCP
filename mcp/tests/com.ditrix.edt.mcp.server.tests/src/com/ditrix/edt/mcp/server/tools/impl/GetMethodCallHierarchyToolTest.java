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
        // The slim description points at the on-demand guide channel.
        assertTrue(desc.contains("get_tool_guide('get_method_call_hierarchy')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // Exhaustive detail moved out of the description/schema and into getGuide().
        String guide = new GetMethodCallHierarchyTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("callers")); //$NON-NLS-1$
        assertTrue(guide.contains("callees")); //$NON-NLS-1$
        // The caller-discovery rationale (resolved feature entries) now lives only here.
        assertTrue(guide.contains("feature entry")); //$NON-NLS-1$
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

    // ==================== getResultFileName (pure, no live workbench) ====================

    @Test
    public void testResultFileNameWithMethodAndDirection()
    {
        // methodName + an explicit direction → "call-hierarchy-<method>-<direction>.md",
        // with the method name lower-cased and the direction appended verbatim.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-dowork-callees.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameWithMethodDefaultsDirectionToCallers()
    {
        // methodName present but NO direction argument → the direction segment
        // defaults to "callers" (the (direction != null ? direction : KEY_CALLERS) branch).
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "Calculate"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-calculate-callers.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameLowercasesMethodName()
    {
        // The method-name segment is always lower-cased, so a mixed-case ru/en spelling
        // produces a stable, case-insensitive file name.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "ПолучитьДанные"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "callers"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-получитьданные-callers.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameEmptyDirectionIsAppendedVerbatim()
    {
        // The direction segment guards on null, NOT on emptiness: an explicitly empty
        // direction is appended as-is (it does not fall back to "callers"). Pins the
        // exact (direction != null ? direction : KEY_CALLERS) semantics.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "DoWork"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy-dowork-.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenMethodNameMissing()
    {
        // No methodName argument → the generic fallback file name (the direction is
        // irrelevant when there is no method to name).
        Map<String, String> params = new HashMap<>();
        params.put("direction", "callees"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenMethodNameEmpty()
    {
        // An explicitly empty methodName also falls back to the generic name
        // (the !methodName.isEmpty() guard).
        Map<String, String> params = new HashMap<>();
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call-hierarchy.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameDefaultWhenParamsEmpty()
    {
        // The no-arguments case (neither methodName nor direction) returns the constant fallback.
        assertEquals("call-hierarchy.md", //$NON-NLS-1$
            new GetMethodCallHierarchyTool().getResultFileName(new HashMap<>()));
    }
}
