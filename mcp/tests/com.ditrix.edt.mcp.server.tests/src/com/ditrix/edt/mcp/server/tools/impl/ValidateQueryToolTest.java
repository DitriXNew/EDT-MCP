/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ValidateQueryTool}.
 * <p>
 * Covers tool metadata (name/constant/responseType/description/input+output schema/guide),
 * the argument-validation branches in {@code execute(...)} that return BEFORE the first
 * {@code ProjectContext.of(...)} → {@code ResourcesPlugin.getWorkspace()} access (the EDT
 * boundary). Shared validation and result formatting are covered by QueryValidationServiceTest;
 * the live native QL/DBView path is covered by the e2e suite.
 */
public class ValidateQueryToolTest
{
    @Test
    public void testName()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        assertEquals("validate_query", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ValidateQueryTool.NAME, new ValidateQueryTool().getName());
    }

    @Test
    public void testResponseType()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        assertEquals(ResponseType.JSON, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndMentionsQuery()
    {
        String desc = new ValidateQueryTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The description must advertise what is validated (query / QL).
        assertTrue("description must mention query validation", //$NON-NLS-1$
            desc.toLowerCase().contains("query")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresResultFields()
    {
        String schema = new ValidateQueryTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare valid", schema.contains("\"valid\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare dcsMode", schema.contains("\"dcsMode\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare validationScope", schema.contains("\"validationScope\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare errorCount", schema.contains("\"errorCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare warningCount", schema.contains("\"warningCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infoCount", schema.contains("\"infoCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(schema.contains("\"validationState\""));
        assertTrue(schema.contains("\"validationComplete\""));
        assertTrue(schema.contains("\"unresolvedSources\""));
        assertTrue("outputSchema must declare issues", schema.contains("\"issues\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaContainsRequiredParameters()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"queryText\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"dcsMode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\""));
        assertTrue(schema.contains("\"method\""));
        assertTrue(schema.contains("\"required\":[\"projectName\"]")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        String guide = tool.getGuide();

        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        // Detail migrated out of the slimmed description/schema must live in the guide.
        assertTrue(guide.contains("dcsMode")); //$NON-NLS-1$
        assertTrue(guide.contains("Data Composition")); //$NON-NLS-1$
        // The slim description now points callers at the guide channel.
        assertTrue(tool.getDescription().contains("get_tool_guide('validate_query')")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        ValidateQueryTool tool = new ValidateQueryTool();

        Map<String, String> params = new HashMap<>();
        params.put("queryText", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingQueryText()
    {
        ValidateQueryTool tool = new ValidateQueryTool();

        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("exactly one of queryText or modulePath")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectNameCarriesDiscoveryHint()
    {
        // The shared required-argument guard adds a list_projects discovery hint for projectName.
        Map<String, String> params = new HashMap<>();
        params.put("queryText", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateQueryTool().execute(params);
        assertTrue("projectName error must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBothMissingReportsProjectNameFirst()
    {
        // requireArguments(projectName, queryText) checks in order: projectName is reported first.
        Map<String, String> params = new HashMap<>();
        String result = new ValidateQueryTool().execute(params);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("projectName must be the first reported missing argument", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("queryText error must not appear while projectName is also missing", //$NON-NLS-1$
            result.contains("queryText is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBlankProjectNameIsError()
    {
        // A present-but-blank value is treated as missing by the required-argument guard.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("queryText", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ValidateQueryTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteNullParamsMissingProjectName()
    {
        // extractStringArgument/requireArguments are null-safe: a null map yields the
        // projectName-required error, never an NPE (the EDT boundary is never reached).
        String result = new ValidateQueryTool().execute(null);
        assertNotNull(result);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void rejectsBothInputModesBeforeProjectResolution()
    {
        for (String query : new String[] { "SELECT 1", "" })
        {
            String result = new ValidateQueryTool().execute(Map.of("projectName", "AnyProject", "queryText", query,
                "modulePath", "CommonModules/Calc/Module.bsl"));
            assertTrue(result.contains("\"success\":false"));
            assertTrue(result.contains("exactly one of queryText or modulePath"));
            assertTrue(result.contains("list_modules"));
        }
    }

    @Test
    public void rejectsMethodWithoutModulePathEvenWithQueryText()
    {
        for (Map<String, String> params : java.util.List.of(
            Map.of("projectName", "AnyProject", "method", "Queries"),
            Map.of("projectName", "AnyProject", "method", "Запросы", "queryText", "SELECT 1")))
        {
            String result = new ValidateQueryTool().execute(params);
            assertTrue(result.contains("\"success\":false"));
            assertTrue(result.contains("method requires modulePath"));
            assertTrue(result.contains("list_modules"));
        }
    }

    @Test
    public void rejectsBlankMethodAndModulePath()
    {
        String result = new ValidateQueryTool().execute(Map.of("projectName", "AnyProject", "method", " ",
            "modulePath", "CommonModules/Calc/Module.bsl"));
        assertTrue(result.contains("method must be a non-blank"));
        assertTrue(result.contains("get_module_structure"));
        result = new ValidateQueryTool().execute(Map.of("projectName", "AnyProject", "modulePath", " "));
        assertTrue(result.contains("exactly one of queryText or modulePath"));
    }

    @Test
    public void compactSchemaKeepsConditionalContracts()
    {
        var schema = com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor.compact("validate_query",
            com.google.gson.JsonParser.parseString(new ValidateQueryTool().getInputSchema()));
        var properties = schema.getAsJsonObject().getAsJsonObject("properties");
        assertTrue(properties.getAsJsonObject("queryText").get("description").getAsString().contains("exactly one"));
        assertTrue(properties.getAsJsonObject("method").get("description").getAsString().contains("only with modulePath"));
        assertTrue(properties.getAsJsonObject("modulePath").get("description").getAsString().contains("CommonModules/"));
    }

}
