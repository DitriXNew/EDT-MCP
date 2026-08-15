/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ReadMethodSourceTool}.
 * <p>
 * Covers tool metadata, the input schema, the on-demand guide, the pure
 * {@code getResultFileName} helper, and the three required-argument validation
 * branches (projectName, modulePath, methodName) — including blank values,
 * check ordering, discovery hints, and the error-JSON envelope — that return
 * before the first {@code PlatformUI.getWorkbench()} call (the EDT boundary).
 * Method extraction needs a live workbench and the loaded BSL model and is
 * covered by the E2E suite.
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

    @Test
    public void testBlankProjectNameIsError()
    {
        // A blank value is treated like a missing one by the required-argument guard.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoStuff"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank projectName must be rejected", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankModulePathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", "DoStuff"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank modulePath must be rejected", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankMethodNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("blank methodName must be rejected", //$NON-NLS-1$
            result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationChecksProjectNameFirst()
    {
        // requireArguments checks projectName, then modulePath, then methodName in order and
        // returns the FIRST failure. With ALL three missing, the error must name projectName only.
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("first failure must be projectName", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("must not report modulePath before projectName is satisfied", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testValidationChecksModulePathBeforeMethodName()
    {
        // projectName satisfied, modulePath and methodName both missing → modulePath first.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("second failure must be modulePath", //$NON-NLS-1$
            result.contains("modulePath is required")); //$NON-NLS-1$
        assertFalse("must not report methodName before modulePath is satisfied", //$NON-NLS-1$
            result.contains("methodName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("projectName error must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePathErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("modulePath error must steer to list_modules", //$NON-NLS-1$
            result.contains("list_modules")); //$NON-NLS-1$
    }

    @Test
    public void testMissingMethodNameErrorCarriesDiscoveryHint()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("methodName error must steer to get_module_structure", //$NON-NLS-1$
            result.contains("get_module_structure")); //$NON-NLS-1$
    }

    @Test
    public void testValidationErrorIsStructuredFailureJson()
    {
        // The required-argument guard returns the structured error envelope.
        Map<String, String> params = new HashMap<>();
        String result = new ReadMethodSourceTool().execute(params);
        assertTrue("error payload must carry success:false", //$NON-NLS-1$
            result.contains("\"success\":false") || result.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error payload must carry an error field", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== getResultFileName (pure helper, no workbench) ====================

    @Test
    public void testResultFileNameWithMethodName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "MyMethod"); //$NON-NLS-1$ //$NON-NLS-2$
        // Name is lower-cased and wrapped as method-<name>.md.
        assertEquals("method-mymethod.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameLowercasesMixedCase()
    {
        Map<String, String> params = new HashMap<>();
        params.put("methodName", "CamelCaseName"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("method-camelcasename.md", //$NON-NLS-1$
            new ReadMethodSourceTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameWithoutMethodName()
    {
        Map<String, String> params = new HashMap<>();
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithBlankMethodName()
    {
        // An empty methodName is treated as absent → the generic file name.
        Map<String, String> params = new HashMap<>();
        params.put("methodName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameWithNullParamsIsSafe()
    {
        // extractStringArgument tolerates a null params map → generic file name, no NPE.
        assertEquals("method-source.md", new ReadMethodSourceTool().getResultFileName(null)); //$NON-NLS-1$
    }

    // ==================== Schema / metadata details ====================

    @Test
    public void testSchemaMarksAllThreeParametersRequired()
    {
        String schema = new ReadMethodSourceTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("modulePath must be required", tail.contains("\"modulePath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("methodName must be required", tail.contains("\"methodName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionSteersToReadModuleSource()
    {
        // The single-method tool advertises its whole-module sibling.
        String desc = new ReadMethodSourceTool().getDescription();
        assertTrue("description must point to read_module_source", //$NON-NLS-1$
            new ReadMethodSourceTool().getGuide().contains("read_module_source")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // MARKDOWN tools return content, not structuredContent → no output schema.
        assertNull(new ReadMethodSourceTool().getOutputSchema());
    }

    @Test
    public void testGuideIsServedAndDocumentsContentHashRoundTrip()
    {
        String guide = new ReadMethodSourceTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must not be empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide must explain the contentHash round-trip", //$NON-NLS-1$
            guide.contains("contentHash")); //$NON-NLS-1$
        assertTrue("guide must note case-insensitive method matching", //$NON-NLS-1$
            guide.toLowerCase().contains("case-insensitive")); //$NON-NLS-1$
    }
}
