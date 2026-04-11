/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for {@link JUnitMarkdownFormatter}.
 */
public class JUnitMarkdownFormatterTest
{
    private static JUnitTestResults parse(String xml) throws Exception
    {
        return JUnitXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testHeaderAndSummaryAlwaysPresent() throws Exception
    {
        JUnitTestResults r = parse("<testsuite name=\"S\" tests=\"0\"/>");
        String md = JUnitMarkdownFormatter.format(r);

        assertTrue(md.contains("# YAXUnit Test Results"));
        assertTrue(md.contains("## Summary"));
        assertTrue(md.contains("| Total"));
        assertTrue(md.contains("| Passed"));
        assertTrue(md.contains("| Failed"));
        assertTrue(md.contains("| Errors"));
        assertTrue(md.contains("| Skipped"));
    }

    @Test
    public void testPassedVerdict() throws Exception
    {
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"2\" failures=\"0\" errors=\"0\">"
                        + "<testcase classname=\"OM_a\" name=\"t1\"/>"
                        + "<testcase classname=\"OM_a\" name=\"t2\"/>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertTrue(md.contains("**Result: PASSED**"));
        assertFalse(md.contains("## Failures"));
        assertFalse(md.contains("## Errors"));
        assertFalse(md.contains("## Skipped"));
    }

    @Test
    public void testFailedVerdictWithSections() throws Exception
    {
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"3\" failures=\"1\" errors=\"1\" skipped=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"failTest\">"
                        + "  <failure message=\"expected x\">trace-line</failure>"
                        + "</testcase>"
                        + "<testcase classname=\"OM_a\" name=\"errorTest\">"
                        + "  <error message=\"boom\">error-trace</error>"
                        + "</testcase>"
                        + "<testcase classname=\"OM_a\" name=\"skipTest\">"
                        + "  <skipped message=\"todo\"/>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertTrue(md.contains("**Result: FAILED**"));
        assertTrue(md.contains("## Failures"));
        assertTrue(md.contains("### OM_a.failTest"));
        assertTrue(md.contains("expected x"));
        assertTrue(md.contains("trace-line"));
        assertTrue(md.contains("## Errors"));
        assertTrue(md.contains("### OM_a.errorTest"));
        assertTrue(md.contains("boom"));
        assertTrue(md.contains("## Skipped"));
        assertTrue(md.contains("OM_a.skipTest"));
        assertTrue(md.contains("todo"));
    }

    @Test
    public void testTraceFencedAsCodeBlock() throws Exception
    {
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"x\">multi\nline\ntrace</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertTrue(md.contains("```\nmulti\nline\ntrace\n```"));
    }
}
