/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ValidateQueryTool}.
 * <p>
 * Covers tool metadata (name/constant/responseType/description/input+output schema/guide),
 * the argument-validation branches in {@code execute(...)} that return BEFORE the first
 * {@code ProjectContext.of(...)} → {@code ResourcesPlugin.getWorkspace()} access (the EDT
 * boundary), and the pure static {@code buildResult}/{@code QueryIssue} result-formatting
 * surface. The live Xtext validation path needs a workspace and is covered by the e2e suite.
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
        assertTrue("outputSchema must declare errorCount", schema.contains("\"errorCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare warningCount", schema.contains("\"warningCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infoCount", schema.contains("\"infoCount\"")); //$NON-NLS-1$ //$NON-NLS-2$
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
        assertTrue(schema.contains("\"required\":[\"projectName\",\"queryText\"]")); //$NON-NLS-1$
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
        assertTrue(result.contains("queryText is required")); //$NON-NLS-1$
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

    // ==================== buildResult (migrated to ToolResult) ====================
    // The result JSON is built by the shared ToolResult/Gson path. Gson emits
    // compact ("key":value, no spaces) and escapes >, <, & as \\uXXXX, so the
    // assertions below use delimiter-free substrings.

    @Test
    public void testBuildResultValidWhenNoIssues()
    {
        String json = ValidateQueryTool.buildResult(List.of(), false);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"issues\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultReportsError()
    {
        ValidateQueryTool.QueryIssue err =
            new ValidateQueryTool.QueryIssue("ERROR", "Field 'Foo' not found", 1, 8, 7); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(err), true);
        assertTrue(json.contains("\"success\":true")); // tool ran; query is what's invalid //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"ERROR\"")); //$NON-NLS-1$
        // Message text is present; Gson HTML-escapes the apostrophes ('->\\u0027),
        // so assert on delimiter-free fragments rather than the raw message.
        assertTrue(json.contains("Foo")); //$NON-NLS-1$
        assertTrue(json.contains("not found")); //$NON-NLS-1$
        assertTrue(json.contains("\"line\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":8")); //$NON-NLS-1$
        assertTrue(json.contains("\"offset\":7")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultOmitsNonPositiveLocation()
    {
        // A non-located message (line/column <= 0, offset < 0) omits those keys,
        // but any issue at all still makes the query "not valid".
        ValidateQueryTool.QueryIssue warn =
            new ValidateQueryTool.QueryIssue("WARNING", "general warning", -1, -1, -1); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(warn), false);
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"WARNING\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"line\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"offset\":")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultCountsInfoSeverity()
    {
        // An INFO issue increments infoCount only and still makes the query "not valid".
        ValidateQueryTool.QueryIssue info =
            new ValidateQueryTool.QueryIssue("INFO", "informational note", 3, 1, 12); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(info), false);
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"INFO\"")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultCountsMixedSeverities()
    {
        // Mixed bag: two errors, one warning, one info — each bucket counted independently.
        List<ValidateQueryTool.QueryIssue> issues = Arrays.asList(
            new ValidateQueryTool.QueryIssue("ERROR", "e1", 1, 1, 0), //$NON-NLS-1$ //$NON-NLS-2$
            new ValidateQueryTool.QueryIssue("ERROR", "e2", 2, 1, 5), //$NON-NLS-1$ //$NON-NLS-2$
            new ValidateQueryTool.QueryIssue("WARNING", "w1", 3, 1, 9), //$NON-NLS-1$ //$NON-NLS-2$
            new ValidateQueryTool.QueryIssue("INFO", "i1", 4, 1, 13)); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(issues, false);
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":1")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultEmptyWithDcsModeTrue()
    {
        // dcsMode is echoed verbatim independent of the (empty) issue list.
        String json = ValidateQueryTool.buildResult(List.of(), true);
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"issues\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultLineOnlyEmitsLineNotColumnNorOffset()
    {
        // line > 0 but column <= 0 and offset < 0: only the "line" key is emitted.
        ValidateQueryTool.QueryIssue issue =
            new ValidateQueryTool.QueryIssue("ERROR", "located by line only", 42, 0, -1); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(issue), false);
        assertTrue(json.contains("\"line\":42")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"offset\":")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultOffsetZeroIsEmitted()
    {
        // offset uses a >= 0 guard (line/column use > 0): offset 0 IS a valid location and is emitted.
        ValidateQueryTool.QueryIssue issue =
            new ValidateQueryTool.QueryIssue("WARNING", "at start", -1, -1, 0); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(issue), false);
        assertTrue("offset 0 must be emitted (>=0 guard)", json.contains("\"offset\":0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.contains("\"line\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
    }

    @Test
    public void testQueryIssueFieldsArePreserved()
    {
        // The internal carrier keeps every field verbatim for buildResult to read.
        ValidateQueryTool.QueryIssue issue =
            new ValidateQueryTool.QueryIssue("ERROR", "msg", 7, 3, 21); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ERROR", issue.severity); //$NON-NLS-1$
        assertEquals("msg", issue.message); //$NON-NLS-1$
        assertEquals(7, issue.line);
        assertEquals(3, issue.column);
        assertEquals(21, issue.offset);
    }
}
