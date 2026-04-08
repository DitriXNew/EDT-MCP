/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests for {@link JUnitXmlParser}.
 */
public class JUnitXmlParserTest
{
    private static JUnitTestResults parseXml(String xml) throws Exception
    {
        return JUnitXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testAllPassed() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuite name=\"All\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "  <testcase classname=\"OM_a\" name=\"t1\"/>"
                + "  <testcase classname=\"OM_a\" name=\"t2\"/>"
                + "  <testcase classname=\"OM_a\" name=\"t3\"/>"
                + "</testsuite>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(3, r.getTotal());
        assertEquals(0, r.getFailures());
        assertEquals(0, r.getErrors());
        assertEquals(0, r.getSkipped());
        assertEquals(3, r.getPassed());
        assertTrue(r.isPassed());
        assertTrue(r.getFailureDetails().isEmpty());
        assertTrue(r.getErrorDetails().isEmpty());
        assertTrue(r.getSkippedDetails().isEmpty());
    }

    @Test
    public void testFailureCapturedWithMessageAndTrace() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuite name=\"S\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">"
                + "  <testcase classname=\"OM_a\" name=\"t1\">"
                + "    <failure message=\"expected 1 got 2\">at line 42\nat line 43</failure>"
                + "  </testcase>"
                + "</testsuite>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(1, r.getTotal());
        assertEquals(1, r.getFailures());
        assertEquals(0, r.getPassed());
        assertFalse(r.isPassed());
        assertEquals(1, r.getFailureDetails().size());
        JUnitTestResults.TestCase tc = r.getFailureDetails().get(0);
        assertEquals("OM_a.t1", tc.name);
        assertEquals("expected 1 got 2", tc.message);
        assertNotNull(tc.trace);
        assertTrue(tc.trace.contains("line 42"));
    }

    @Test
    public void testErrorCaptured() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuite name=\"S\" tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\">"
                + "  <testcase classname=\"OM_a\" name=\"t1\">"
                + "    <error message=\"NullReferenceException\">stack here</error>"
                + "  </testcase>"
                + "</testsuite>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(1, r.getErrors());
        assertEquals(1, r.getErrorDetails().size());
        assertEquals("NullReferenceException", r.getErrorDetails().get(0).message);
        assertFalse(r.isPassed());
    }

    @Test
    public void testSkippedCaptured() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuite name=\"S\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"1\">"
                + "  <testcase classname=\"OM_a\" name=\"t1\">"
                + "    <skipped message=\"not implemented\"/>"
                + "  </testcase>"
                + "</testsuite>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(1, r.getSkipped());
        assertEquals(1, r.getSkippedDetails().size());
        assertEquals("not implemented", r.getSkippedDetails().get(0).message);
        assertTrue("skipped only is still 'passed' (no failures/errors)", r.isPassed());
    }

    @Test
    public void testMultipleTestSuitesAggregated() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuites>"
                + "  <testsuite name=\"A\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\">"
                + "    <testcase classname=\"OM_a\" name=\"t1\"/>"
                + "    <testcase classname=\"OM_a\" name=\"t2\">"
                + "      <failure message=\"x\">trace</failure>"
                + "    </testcase>"
                + "  </testsuite>"
                + "  <testsuite name=\"B\" tests=\"3\" failures=\"0\" errors=\"1\" skipped=\"1\">"
                + "    <testcase classname=\"OM_b\" name=\"t1\"/>"
                + "    <testcase classname=\"OM_b\" name=\"t2\">"
                + "      <error message=\"err\">trace</error>"
                + "    </testcase>"
                + "    <testcase classname=\"OM_b\" name=\"t3\">"
                + "      <skipped message=\"skip\"/>"
                + "    </testcase>"
                + "  </testsuite>"
                + "</testsuites>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(5, r.getTotal());
        assertEquals(1, r.getFailures());
        assertEquals(1, r.getErrors());
        assertEquals(1, r.getSkipped());
        assertEquals(2, r.getPassed());
        assertFalse(r.isPassed());
    }

    @Test
    public void testStandaloneTestcasesCounted() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<root>"
                + "  <testcase classname=\"OM_a\" name=\"t1\"/>"
                + "  <testcase classname=\"OM_a\" name=\"t2\"/>"
                + "</root>";

        JUnitTestResults r = parseXml(xml);

        assertEquals(2, r.getTotal());
        assertTrue(r.isPassed());
    }

    @Test
    public void testTestcaseWithoutClassname() throws Exception
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                + "  <testcase name=\"plain\">"
                + "    <failure message=\"oops\">trace</failure>"
                + "  </testcase>"
                + "</testsuite>";

        JUnitTestResults r = parseXml(xml);

        assertEquals("plain", r.getFailureDetails().get(0).name);
    }

    @Test
    public void testDoctypeIsRejected()
    {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite name=\"x\" tests=\"0\"/>";

        try
        {
            parseXml(xml);
            fail("expected exception when DOCTYPE is present (XXE protection)");
        }
        catch (Exception expected)
        {
            // ok
        }
    }
}
