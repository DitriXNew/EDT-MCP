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
 * Tests for {@link GetEventLogTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, and the argument-validation
 * guards that execute BEFORE any workspace / filesystem access. The real read path
 * (reflective log-directory resolution + parsing an actual {@code 1Cv8Log}) needs a live
 * EDT workspace / a real log and is covered by the e2e suite.
 */
public class GetEventLogToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_event_log", new GetEventLogTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetEventLogTool.NAME, new GetEventLogTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // JSON so the structured events[] land in structuredContent (BuiltInToolOutputSchemaTest).
        assertEquals(ResponseType.JSON, new GetEventLogTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new GetEventLogTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_event_log')")); //$NON-NLS-1$
        assertTrue("description must advertise the PII / infobase-data nature", //$NON-NLS-1$
            desc.toUpperCase().contains("PII"));
    }

    @Test
    public void testInputSchemaDeclaresAllParametersLowerCamelCase()
    {
        String schema = new GetEventLogTool().getInputSchema();
        assertNotNull(schema);
        // Every param read in execute() must be declared (CLAUDE.md don't #6), lowerCamelCase.
        for (String param : new String[] {"projectName", "applicationId", "logDir", "from", "to", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "user", "event", "eventContains", "severity", "commentContains", "metadataContains", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "session", "limit", "offset", "order"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testOrderIsAClosedEnumInSchema()
    {
        String schema = new GetEventLogTool().getInputSchema();
        assertTrue("order must expose its accepted values as an enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("order enum must list date_asc", schema.contains("date_asc")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("order enum must list date_desc", schema.contains("date_desc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoParameterIsRequired()
    {
        // projectName is NOT unconditionally required: logDir is an equal alternative, so the
        // required array must be empty (the tool enforces "one of projectName/logDir" at runtime).
        String schema = new GetEventLogTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertFalse("projectName must NOT be required (logDir is an alternative)", //$NON-NLS-1$
            requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$
        assertFalse("logDir must NOT be required (projectName is an alternative)", //$NON-NLS-1$
            requiredBlock.contains("\"logDir\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new GetEventLogTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "resolvedLogDir", "infobaseType", "format", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "matched", "scanned", "returned", "limit", "offset", "truncated", "events"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testGuideDocumentsFormatSupportAndPii()
    {
        String guide = new GetEventLogTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the legacy text .lgf/.lgp format", //$NON-NLS-1$
            guide.contains("1Cv8.lgf") && guide.toLowerCase().contains(".lgp")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the unsupported SQLite .lgd case", //$NON-NLS-1$
            guide.toLowerCase().contains(".lgd")); //$NON-NLS-1$
        assertTrue("guide must document the SERVER-mode logDir requirement", //$NON-NLS-1$
            guide.toUpperCase().contains("SERVER"));
        assertTrue("guide must flag the PII / personal-data nature", //$NON-NLS-1$
            guide.toUpperCase().contains("PII") || guide.toLowerCase().contains("personal data")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any workspace/file access) ==========

    @Test
    public void testMissingBothProjectAndLogDirIsError()
    {
        // Neither projectName nor logDir -> the tool cannot locate a log; it must error up front
        // (before touching EDT / the filesystem) and name the two ways to satisfy the requirement.
        String result = new GetEventLogTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name logDir", result.contains("logDir")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidOrderIsRejectedActionably()
    {
        // A logDir is supplied so the project/logDir guard passes; the order guard fires BEFORE the
        // locator/reader (which would need a real filesystem), so this stays headless.
        Map<String, String> params = new HashMap<>();
        params.put("logDir", "/tmp/no_such_1cv8log"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("order", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetEventLogTool().execute(params);
        assertTrue("invalid order must echo the bad value", result.contains("sideways")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid order must list date_asc", result.contains("date_asc")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid order must list date_desc", result.contains("date_desc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMalformedPeriodIsRejectedActionably()
    {
        // A malformed from/to bound must NOT escape as an uncaught ISO-parse exception
        // (unattended-safety, CLAUDE.md #8): the tool echoes the bad value and names the accepted
        // ISO-8601 shape. Validated BEFORE the locator/reader, so this stays headless (the logDir
        // only exists to pass the earlier project/logDir guard).
        Map<String, String> params = new HashMap<>();
        params.put("logDir", "/tmp/no_such_1cv8log"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("from", "not-a-date"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetEventLogTool().execute(params);
        assertTrue("malformed period must echo the bad value", result.contains("not-a-date")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("malformed period must name the accepted ISO-8601 shape", //$NON-NLS-1$
            result.contains("ISO-8601")); //$NON-NLS-1$
    }

    @Test
    public void testOmittedPeriodDoesNotThrow()
    {
        // The most common call - a logDir and NO from/to - must not NPE on a null period bound. It
        // proceeds past query-building and returns a normal JSON envelope (an error here only because
        // the throwaway logDir does not exist), never an escaped exception.
        Map<String, String> params = new HashMap<>();
        params.put("logDir", "/tmp/no_such_1cv8log"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetEventLogTool().execute(params);
        assertNotNull("omitted period must not throw; a JSON result is returned", result); //$NON-NLS-1$
        assertTrue("result must be a JSON envelope", result.contains("\"success\"") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericSessionIsRejectedActionably()
    {
        // session is a numeric filter forwarded to EventLogQuery as a Long; a non-numeric value must
        // fail fast with an actionable error that echoes the bad value, NOT silently match nothing. A
        // logDir passes the project/logDir guard and the session parse fires BEFORE the locator/reader
        // (which would need a real filesystem), so this stays headless.
        Map<String, String> params = new HashMap<>();
        params.put("logDir", "/tmp/no_such_1cv8log"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("session", "not-a-number"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetEventLogTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("invalid session must echo the bad value", result.contains("not-a-number")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid session must name the session parameter", //$NON-NLS-1$
            result.toLowerCase().contains("session")); //$NON-NLS-1$
    }
}
