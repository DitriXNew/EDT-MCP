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

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Unit tests for {@link CreateProjectTool}.
 * <p>
 * Covers tool name/constant, response type, description (guide pointer,
 * v8codestyle mention), guide non-empty, input schema (all declared params,
 * required array, projectKind enum), output schema (expected fields), and the
 * argument-validation sentinels that return before any EDT API call (headless-safe).
 */
public class CreateProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_project", new CreateProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateProjectTool.NAME, new CreateProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateProjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.trim().isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must steer the agent to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_project')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsCodestyleOptionality()
    {
        String desc = new CreateProjectTool().getDescription();
        assertTrue("description must mention v8codestyle optionality", //$NON-NLS-1$
            desc.contains("com.e1c.v8codestyle")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new CreateProjectTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsKeyParams()
    {
        String guide = new CreateProjectTool().getGuide();
        assertTrue("guide must document projectKind", guide.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document baseProjectName", guide.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document purpose", guide.contains("purpose")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document autoSortTopObjects limitation", //$NON-NLS-1$
            guide.contains("autoSortTopObjects")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare version", schema.contains("\"version\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare baseProjectName", schema.contains("\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare prefix", schema.contains("\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare synonym", schema.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare comment", schema.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare purpose", schema.contains("\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare compatibilityMode", schema.contains("\"compatibilityMode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare scriptVariant", schema.contains("\"scriptVariant\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare standardChecks", schema.contains("\"standardChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare commonChecks", schema.contains("\"commonChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare autoSortTopObjects", schema.contains("\"autoSortTopObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaProjectKindEnum()
    {
        String schema = new CreateProjectTool().getInputSchema();
        // The projectKind enum must declare all three allowed values
        assertTrue("schema must declare 'configuration' kind", schema.contains("\"configuration\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'extension' kind", schema.contains("\"extension\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare 'externalObjects' kind", schema.contains("\"externalObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new CreateProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectKind must be required", tail.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional params must NOT appear in the required array
        assertFalse("projectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectName\",") || tail.contains(",\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("baseProjectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"baseProjectName\",") || tail.contains(",\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("prefix must NOT be required", //$NON-NLS-1$
            tail.contains("\"prefix\",") || tail.contains(",\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("synonym must NOT be required", //$NON-NLS-1$
            tail.contains("\"synonym\",") || tail.contains(",\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("purpose must NOT be required", //$NON-NLS-1$
            tail.contains("\"purpose\",") || tail.contains(",\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new CreateProjectTool().getOutputSchema();
        assertNotNull("outputSchema must not be null", schema); //$NON-NLS-1$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare projectKind", schema.contains("\"projectKind\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare baseProject", schema.contains("\"baseProject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare state", schema.contains("\"state\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare codestyle", schema.contains("\"codestyle\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ────────── Argument-validation sentinels (return before any EDT API call) ──────────

    @Test
    public void testMissingProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'projectKind' must name the param", result.contains("projectKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing 'name' must name the param", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidProjectKindErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "bogusKind"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid projectKind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid projectKind error must name the bad value", result.contains("bogusKind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testVersionRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("version", "8.3.27"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("version for extension kind must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention 'version'", result.contains("version")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBaseProjectNameRejectedForConfiguration()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("baseProjectName for configuration kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testScriptVariantRejectedForExtension()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scriptVariant", "Russian"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("scriptVariant for extension kind must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'scriptVariant'", result.contains("scriptVariant")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBlankNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("blank name must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("blank name error must mention 'name'", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidIdentifierNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "123Bad"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertTrue("invalid identifier must be an error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid identifier error must name the bad value", result.contains("123Bad")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBaseProjectNameForExtensionErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        // No baseProjectName supplied
        String result = new CreateProjectTool().execute(params);
        assertTrue("missing baseProjectName for extension must be an error", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("error must mention 'baseProjectName'", result.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionConfigurationReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExternalObjectsReturnsError()
    {
        // Verifies that execute() with a valid name returns a JSON error in a headless
        // (no EDT workspace) context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "externalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExternal"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHeadlessExecutionExtensionReturnsError()
    {
        // Verifies that execute() with nominally valid params returns a JSON error in a
        // headless context rather than throwing an exception.
        Map<String, String> params = new HashMap<>();
        params.put("projectKind", "extension"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateProjectTool().execute(params);
        assertNotNull(result);
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
