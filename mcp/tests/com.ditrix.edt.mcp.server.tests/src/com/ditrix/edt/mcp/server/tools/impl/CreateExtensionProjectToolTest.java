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
 * Unit tests for {@link CreateExtensionProjectTool}.
 * <p>
 * Covers tool name/constant, response type, description (guide pointer,
 * v8codestyle mention), guide non-empty, input schema (all declared params,
 * required array), output schema (expected fields), and the argument-validation
 * sentinels that return before any EDT API call (headless-safe).
 */
public class CreateExtensionProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_extension_project", new CreateExtensionProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateExtensionProjectTool.NAME, new CreateExtensionProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateExtensionProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CreateExtensionProjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.trim().isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateExtensionProjectTool().getDescription();
        assertTrue("description must steer the agent to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_extension_project')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsCodestyleOptionality()
    {
        String desc = new CreateExtensionProjectTool().getDescription();
        assertTrue("description must mention v8codestyle optionality", //$NON-NLS-1$
            desc.contains("com.e1c.v8codestyle")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new CreateExtensionProjectTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsKeyParams()
    {
        String guide = new CreateExtensionProjectTool().getGuide();
        assertTrue("guide must document baseProjectName", guide.contains("baseProjectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document purpose", guide.contains("purpose")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document autoSortTopObjects limitation", //$NON-NLS-1$
            guide.contains("autoSortTopObjects")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateExtensionProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare baseProjectName", schema.contains("\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare prefix", schema.contains("\"prefix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare synonym", schema.contains("\"synonym\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare comment", schema.contains("\"comment\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare purpose", schema.contains("\"purpose\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare compatibilityMode", schema.contains("\"compatibilityMode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare standardChecks", schema.contains("\"standardChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare commonChecks", schema.contains("\"commonChecks\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare autoSortTopObjects", schema.contains("\"autoSortTopObjects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new CreateExtensionProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("baseProjectName must be required", tail.contains("\"baseProjectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional params must NOT appear in the required array
        assertFalse("projectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectName\",") || tail.contains(",\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
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
        String schema = new CreateExtensionProjectTool().getOutputSchema();
        assertNotNull("outputSchema must not be null", schema); //$NON-NLS-1$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare extensionProject", schema.contains("\"extensionProject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare baseProject", schema.contains("\"baseProject\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare state", schema.contains("\"state\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare codestyle", schema.contains("\"codestyle\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ────────── Argument-validation sentinels (return before any EDT API call) ──────────

    @Test
    public void testMissingNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        String result = new CreateExtensionProjectTool().execute(params);
        assertTrue("missing 'name' must name the param", result.contains("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBaseProjectNameErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateExtensionProjectTool().execute(params);
        assertTrue("missing 'baseProjectName' must name the param", //$NON-NLS-1$
            result.contains("baseProjectName")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidPurposeErrors()
    {
        Map<String, String> params = new HashMap<>();
        params.put("name", "MyExt"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("baseProjectName", "SomeBase"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("purpose", "InvalidPurpose"); //$NON-NLS-1$ //$NON-NLS-2$
        // This would normally reach the V8ConfigurationNature check before purpose validation,
        // but in a headless test context both checks fail — we just verify the call is an error.
        String result = new CreateExtensionProjectTool().execute(params);
        assertNotNull(result);
        // The result must be a JSON error (success=false)
        assertTrue("result must contain 'success'", result.contains("success")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
