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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Ratchet tests for {@link ValidateXdtoPackageTool}.
 * <p>
 * Covers tool metadata (name/constant, response type, description-steers-to-guide, input
 * schema with schema&lt;-&gt;execute parameter parity + the lowerCamelCase convention, the
 * required array, the null output schema for a MARKDOWN tool, the guide), and the
 * Display-free error paths {@code execute(Map)} reaches BEFORE any live EDT/BM access:
 * missing {@code projectName}/{@code fqn} (required-argument guard) and an unknown project
 * (value-naming "Project not found" via
 * {@code ProjectContext}). The FQN-resolves-but-is-not-an-XDTOPackage path and the
 * happy-path pass/fail verdict need a live configuration and are covered by the E2E suite.
 */
public class ValidateXdtoPackageToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS = {"projectName", "fqn", "limit"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    // ==================== Metadata: name / response type ====================

    @Test
    public void testName()
    {
        assertEquals("validate_xdto_package", new ValidateXdtoPackageTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ValidateXdtoPackageTool.NAME, new ValidateXdtoPackageTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        // Same shape as get_project_errors (a problem table), so it stays MARKDOWN (the
        // IMcpTool default): no round-trip ID / structured position this tool adds itself.
        assertEquals(ResponseType.MARKDOWN, new ValidateXdtoPackageTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsFalse()
    {
        // Reads EDT/workspace validation markers only - never opens an infobase connection.
        assertFalse(new ValidateXdtoPackageTool().connectsToInfobase());
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        assertNull(new ValidateXdtoPackageTool().getOutputSchema());
    }

    // ==================== Metadata: description ====================

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ValidateXdtoPackageTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        String desc = new ValidateXdtoPackageTool().getDescription();
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('validate_xdto_package')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsGetProjectErrors()
    {
        // It is documented as a thin wrapper - the description should say so.
        String desc = new ValidateXdtoPackageTool().getDescription();
        assertTrue("description should reference get_project_errors (the reuse point)", //$NON-NLS-1$
            desc.contains("get_project_errors")); //$NON-NLS-1$
    }

    // ==================== Metadata: input schema ====================

    @Test
    public void testSchemaDeclaresAllExecuteParams()
    {
        String schema = new ValidateXdtoPackageTool().getInputSchema();
        assertNotNull(schema);
        for (String param : EXECUTE_PARAMS)
        {
            assertTrue("input schema must declare execute() param: " + param, //$NON-NLS-1$
                schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testSchemaDeclaresNoExtraParams()
    {
        // Parity (schema -> execute): every property name in the schema must be one execute() reads.
        String schema = new ValidateXdtoPackageTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("input schema declares a param execute() does not read: " + declared, //$NON-NLS-1$
                contains(EXECUTE_PARAMS, declared));
        }
    }

    @Test
    public void testAllParamsLowerCamelCase()
    {
        String schema = new ValidateXdtoPackageTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("param must be lowerCamelCase: " + declared, isLowerCamelCase(declared)); //$NON-NLS-1$
        }
    }

    @Test
    public void testRequiredArrayHoldsOnlyProjectNameAndFqn()
    {
        String schema = new ValidateXdtoPackageTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", requiredBlock.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaHasNoSeverityFilter()
    {
        // The verdict reports ALL severities (a severity-filtered "no matches" is not a validity
        // guarantee), so the tool intentionally exposes no 'severity' parameter.
        String schema = new ValidateXdtoPackageTool().getInputSchema();
        assertFalse("severity must be absent from the schema", schema.contains("\"severity\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Metadata: guide ====================

    @Test
    public void testGuideIsNonEmpty()
    {
        String guide = new ValidateXdtoPackageTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide should explain the pass/fail verdict shape", //$NON-NLS-1$
            guide.contains("is valid")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any UI / BM access) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("fqn", "XDTOPackage.Orders"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateXdtoPackageTool().execute(params);
        assertTrue("missing projectName must produce a 'projectName is required' error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("error payload must be a failure JSON", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingFqnIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateXdtoPackageTool().execute(params);
        assertTrue("missing fqn must produce a 'fqn is required' error", //$NON-NLS-1$
            result.contains("fqn is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyParamsReportsFirstMissingArgument()
    {
        // requireArguments checks in order: projectName before fqn.
        Map<String, String> params = new HashMap<>();
        String result = new ValidateXdtoPackageTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownProjectIsNamedError()
    {
        // A nonexistent project resolves (via ProjectContext) to a value-naming "Project not found"
        // error that names the bad value AND points at list_projects - reached before any FQN
        // resolution / BM access.
        String badProject = "NoSuchXdtoValidateProject_" + System.nanoTime(); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", badProject); //$NON-NLS-1$
        params.put("fqn", "XDTOPackage.Orders"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateXdtoPackageTool().execute(params);
        assertTrue("error must name the bad project value", result.contains(badProject)); //$NON-NLS-1$
        assertTrue("error must indicate failure", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer the caller to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== helpers ====================

    /** Extracts the property names declared under the schema's {@code "properties"} object. */
    private static List<String> declaredPropertyNames(String schema)
    {
        List<String> names = new java.util.ArrayList<>();
        int propsIdx = schema.indexOf("\"properties\""); //$NON-NLS-1$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (propsIdx < 0)
        {
            return names;
        }
        // The "properties" object precedes "required" in JsonSchemaBuilder.build(); scope the scan.
        String propsBlock = requiredIdx > propsIdx ? schema.substring(propsIdx, requiredIdx)
            : schema.substring(propsIdx);
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\\{").matcher(propsBlock); //$NON-NLS-1$
        boolean first = true;
        while (matcher.find())
        {
            if (first)
            {
                // Skip the leading "properties":{ match itself.
                first = false;
                continue;
            }
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean isLowerCamelCase(String name)
    {
        return name.matches("[a-z][a-zA-Z0-9]*"); //$NON-NLS-1$
    }

    private static boolean contains(String[] array, String value)
    {
        for (String item : array)
        {
            if (item.equals(value))
            {
                return true;
            }
        }
        return false;
    }
}
