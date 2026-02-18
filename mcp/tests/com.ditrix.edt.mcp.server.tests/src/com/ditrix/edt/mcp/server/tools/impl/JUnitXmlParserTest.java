/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tests for {@link JUnitXmlParser}.
 */
public class JUnitXmlParserTest
{
    private File tempDir;

    @Before
    public void setUp() throws IOException
    {
        tempDir = Files.createTempDirectory("junit-parser-test").toFile(); //$NON-NLS-1$
    }

    @After
    public void tearDown()
    {
        if (tempDir != null)
        {
            File[] files = tempDir.listFiles();
            if (files != null)
            {
                for (File f : files)
                {
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    @Test
    public void testParseAllPassed() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"FeatureSuite\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1.5\">\n"
            + "  <testcase name=\"Test1\" classname=\"Suite.Feature\" time=\"0.5\"/>\n"
            + "  <testcase name=\"Test2\" classname=\"Suite.Feature\" time=\"0.5\"/>\n"
            + "  <testcase name=\"Test3\" classname=\"Suite.Feature\" time=\"0.5\"/>\n"
            + "</testsuite>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        assertNotNull(result);
        assertTrue(result.has("summary")); //$NON-NLS-1$
        assertTrue(result.has("failures")); //$NON-NLS-1$
        assertTrue(result.has("tests")); //$NON-NLS-1$

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals(3, summary.get("totalTests").getAsInt()); //$NON-NLS-1$
        assertEquals(3, summary.get("passed").getAsInt()); //$NON-NLS-1$
        assertEquals(0, summary.get("failed").getAsInt()); //$NON-NLS-1$
        assertEquals(0, summary.get("errors").getAsInt()); //$NON-NLS-1$
        assertEquals(0, summary.get("skipped").getAsInt()); //$NON-NLS-1$
        assertTrue(summary.get("allPassed").getAsBoolean()); //$NON-NLS-1$

        JsonArray failures = result.getAsJsonArray("failures"); //$NON-NLS-1$
        assertEquals(0, failures.size());

        JsonArray tests = result.getAsJsonArray("tests"); //$NON-NLS-1$
        assertEquals(3, tests.size());
        assertEquals("passed", tests.get(0).getAsJsonObject().get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseWithFailures() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"FeatureSuite\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"2.0\">\n"
            + "  <testcase name=\"PassingTest\" classname=\"Suite.Feature\" time=\"1.0\"/>\n"
            + "  <testcase name=\"FailingTest\" classname=\"Suite.Feature\" time=\"1.0\">\n"
            + "    <failure message=\"Expected true but got false\">Detailed stack trace here</failure>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals(2, summary.get("totalTests").getAsInt()); //$NON-NLS-1$
        assertEquals(1, summary.get("passed").getAsInt()); //$NON-NLS-1$
        assertEquals(1, summary.get("failed").getAsInt()); //$NON-NLS-1$
        assertFalse(summary.get("allPassed").getAsBoolean()); //$NON-NLS-1$

        JsonArray failures = result.getAsJsonArray("failures"); //$NON-NLS-1$
        assertEquals(1, failures.size());

        JsonObject failure = failures.get(0).getAsJsonObject();
        assertEquals("FailingTest", failure.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Expected true but got false", failure.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Detailed stack trace here", failure.get("details").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseWithErrors() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"Suite\" tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\" time=\"0.1\">\n"
            + "  <testcase name=\"ErrorTest\" classname=\"Suite.Feature\" time=\"0.1\">\n"
            + "    <error message=\"RuntimeError\">Error details</error>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals(1, summary.get("errors").getAsInt()); //$NON-NLS-1$
        assertFalse(summary.get("allPassed").getAsBoolean()); //$NON-NLS-1$

        JsonArray tests = result.getAsJsonArray("tests"); //$NON-NLS-1$
        assertEquals("error", tests.get(0).getAsJsonObject().get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonArray failures = result.getAsJsonArray("failures"); //$NON-NLS-1$
        assertEquals(1, failures.size());
        assertEquals("RuntimeError", failures.get(0).getAsJsonObject().get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseWithSkipped() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"Suite\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"1\" time=\"0.5\">\n"
            + "  <testcase name=\"RunTest\" classname=\"Suite.Feature\" time=\"0.5\"/>\n"
            + "  <testcase name=\"SkippedTest\" classname=\"Suite.Feature\" time=\"0.0\">\n"
            + "    <skipped/>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals(1, summary.get("skipped").getAsInt()); //$NON-NLS-1$
        assertEquals(1, summary.get("passed").getAsInt()); //$NON-NLS-1$
        assertTrue(summary.get("allPassed").getAsBoolean()); //$NON-NLS-1$

        JsonArray tests = result.getAsJsonArray("tests"); //$NON-NLS-1$
        assertEquals(2, tests.size());
        assertEquals("skipped", tests.get(1).getAsJsonObject().get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseTestSuitesRoot() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuites>\n"
            + "  <testsuite name=\"Suite1\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1.0\">\n"
            + "    <testcase name=\"Test1\" classname=\"Suite1.Feature\" time=\"1.0\"/>\n"
            + "  </testsuite>\n"
            + "  <testsuite name=\"Suite2\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"0.5\">\n"
            + "    <testcase name=\"Test2\" classname=\"Suite2.Feature\" time=\"0.5\">\n"
            + "      <failure message=\"fail\">details</failure>\n"
            + "    </testcase>\n"
            + "  </testsuite>\n"
            + "</testsuites>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals(2, summary.get("totalTests").getAsInt()); //$NON-NLS-1$
        assertEquals(1, summary.get("failed").getAsInt()); //$NON-NLS-1$
        assertFalse(summary.get("allPassed").getAsBoolean()); //$NON-NLS-1$

        JsonArray tests = result.getAsJsonArray("tests"); //$NON-NLS-1$
        assertEquals(2, tests.size());

        // Check suites are tracked
        assertEquals("Suite1", tests.get(0).getAsJsonObject().get("suite").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Suite2", tests.get(1).getAsJsonObject().get("suite").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseDurationFormat() throws Exception
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"Suite\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"12.345\">\n"
            + "  <testcase name=\"Test1\" classname=\"Suite.Feature\" time=\"12.345\"/>\n"
            + "</testsuite>\n";

        File xmlFile = writeXml(xml);
        JsonObject result = JUnitXmlParser.parse(xmlFile);

        JsonObject summary = result.getAsJsonObject("summary"); //$NON-NLS-1$
        assertEquals("12.35s", summary.get("duration").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private File writeXml(String content) throws IOException
    {
        File xmlFile = new File(tempDir, "junit-report.xml"); //$NON-NLS-1$
        try (FileWriter writer = new FileWriter(xmlFile, StandardCharsets.UTF_8))
        {
            writer.write(content);
        }
        return xmlFile;
    }
}
