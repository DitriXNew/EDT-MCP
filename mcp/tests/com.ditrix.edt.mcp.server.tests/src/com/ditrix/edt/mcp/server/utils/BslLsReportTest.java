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

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BslLsReport.Finding;
import com.ditrix.edt.mcp.server.utils.BslLsReport.Severity;

/**
 * Tests for {@link BslLsReport} — the pure JSON-report parser of the BSL Language
 * Server output. The sample below is a trimmed but faithful capture of a real
 * {@code --reporter json} run (see MIGRATION-PLAN §6.7): it exercises the two
 * conversions the parser owns — <b>0-based LSP line/character → 1-based</b> and a
 * {@code file://} URI carrying {@code ../} → a normalized absolute path — plus the
 * severity mapping, the {@code codeDescription.href} extraction and the LSP tags.
 */
public class BslLsReportTest
{
    /**
     * Two file entries: one with a MagicNumber (Information, with href) and an
     * UnusedLocalVariable (Warning, tag Unnecessary), one clean. The first path
     * deliberately carries a {@code ../} segment (the engine builds it relative to its
     * working directory) to pin path normalization.
     */
    private static final String SAMPLE = "{"
        + "\"date\":\"2026-07-10 08:06:47\","
        + "\"fileinfos\":["
        + "  {"
        + "    \"path\":\"file:///D:/GitLab/EDT-MCP/Bsl-gar/../tests/TestConfiguration/src/CommonModules/Calc/Module.bsl\","
        + "    \"mdoRef\":\"CommonModule.Calc\","
        + "    \"diagnostics\":["
        + "      {\"code\":\"MagicNumber\","
        + "       \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/MagicNumber\"},"
        + "       \"message\":\"Assign this magic number to a constant\","
        + "       \"range\":{\"start\":{\"character\":20,\"line\":5},\"end\":{\"character\":21,\"line\":5}},"
        + "       \"relatedInformation\":null,\"severity\":\"Information\",\"source\":\"bsl-language-server\",\"tags\":[]},"
        + "      {\"code\":\"UnusedLocalVariable\","
        + "       \"codeDescription\":{\"href\":\"https://1c-syntax.github.io/bsl-language-server/diagnostics/UnusedLocalVariable\"},"
        + "       \"message\":\"Remove unused variable\","
        + "       \"range\":{\"start\":{\"character\":1,\"line\":5},\"end\":{\"character\":10,\"line\":5}},"
        + "       \"relatedInformation\":null,\"severity\":\"Warning\",\"source\":\"bsl-language-server\",\"tags\":[\"Unnecessary\"]}"
        + "    ],"
        + "    \"metrics\":{\"procedures\":1,\"functions\":1,\"lines\":8,\"ncloc\":6,\"comments\":0,"
        + "       \"statements\":2,\"cognitiveComplexity\":0,\"cyclomaticComplexity\":2}"
        + "  },"
        + "  {"
        + "    \"path\":\"file:///D:/GitLab/EDT-MCP/tests/TestConfiguration/src/CommonModules/OK/Module.bsl\","
        + "    \"mdoRef\":\"CommonModule.OK\","
        + "    \"diagnostics\":[],"
        + "    \"metrics\":{\"procedures\":0,\"functions\":0,\"lines\":1,\"ncloc\":0,\"comments\":0,"
        + "       \"statements\":0,\"cognitiveComplexity\":0,\"cyclomaticComplexity\":0}"
        + "  }"
        + "],"
        + "\"sourceDir\":\"D:\\\\GitLab\\\\EDT-MCP\\\\tests\\\\TestConfiguration\\\\src\"}";

    @Test
    public void testParsesAllFindingsAndSeverityCounts()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        assertEquals(2, report.total());
        assertEquals(1, report.count(Severity.INFORMATION));
        assertEquals(1, report.count(Severity.WARNING));
        assertEquals(0, report.count(Severity.ERROR));
        assertEquals(0, report.count(Severity.HINT));
    }

    @Test
    public void testLineAndColumnConvertedToOneBased()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        Finding magic = findByCode(report, "MagicNumber");
        // LSP 0-based line 5 / character 20 -> 1-based 6 / 21.
        assertEquals(6, magic.line());
        assertEquals(21, magic.column());
    }

    @Test
    public void testSeverityHrefAndMdoRefMapped()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        Finding magic = findByCode(report, "MagicNumber");
        assertEquals(Severity.INFORMATION, magic.severity());
        assertEquals("CommonModule.Calc", magic.mdoRef());
        assertNotNull(magic.href());
        assertTrue(magic.href().contains("MagicNumber"));
    }

    @Test
    public void testTagsParsed()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        Finding unused = findByCode(report, "UnusedLocalVariable");
        assertEquals(Severity.WARNING, unused.severity());
        assertTrue(unused.tags().contains("Unnecessary"));
        assertTrue(findByCode(report, "MagicNumber").tags().isEmpty());
    }

    @Test
    public void testPathNormalizedRemovesDotDotSegments()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        Finding magic = findByCode(report, "MagicNumber");
        assertNotNull(magic.path());
        assertFalse("normalized path must not keep ../ segments: " + magic.path(),
            magic.path().contains(".."));
        assertTrue("path should end at the module file: " + magic.path(),
            magic.path().endsWith("Module.bsl"));
    }

    @Test
    public void testMetricsParsed()
    {
        BslLsReport report = BslLsReport.parse(SAMPLE);
        assertEquals(2, report.metrics().size());
        BslLsReport.FileMetrics calc = report.metrics().get(0);
        assertEquals("CommonModule.Calc", calc.mdoRef());
        assertEquals(2, calc.cyclomaticComplexity());
        assertEquals(6, calc.ncloc());
        assertEquals(1, calc.procedures());
        assertEquals(1, calc.functions());
    }

    @Test
    public void testEmptyReportIsEmptyNotError()
    {
        BslLsReport report = BslLsReport.parse("{\"fileinfos\":[]}");
        assertEquals(0, report.total());
        assertTrue(report.findings().isEmpty());
        assertTrue(report.metrics().isEmpty());
    }

    @Test
    public void testMissingFileinfosKeyIsAReportFormatError()
    {
        // Deliberately the OPPOSITE of what this test used to assert. Tolerating a report with no
        // `fileinfos` turned "the engine wrote JSON that is not an analysis report" - a wrong
        // engine version, or a wrapper on EDT_MCP_BSL_LS_JAR writing its own status object - into
        // "0 findings", i.e. a CLEAN project for a run that analysed nothing. An empty ARRAY is
        // still a legitimate clean result (see testEmptyReportIsEmptyNotError); an absent key is not.
        try
        {
            BslLsReport.parse("{}");
            fail("a report without 'fileinfos' must not be reported as a clean project");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue("the error must name the missing field: " + expected.getMessage(),
                expected.getMessage().contains("fileinfos"));
        }
    }

    @Test
    public void testFileinfosOfTheWrongTypeIsAReportFormatError()
    {
        try
        {
            BslLsReport.parse("{\"fileinfos\":{}}");
            fail("a non-array 'fileinfos' must not be reported as a clean project");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue("the error must name the offending field: " + expected.getMessage(),
                expected.getMessage().contains("fileinfos"));
        }
    }

    @Test
    public void testNonObjectJsonThrows()
    {
        try
        {
            BslLsReport.parse("[]");
            fail("expected IllegalArgumentException for a non-object report");
        }
        catch (IllegalArgumentException expected)
        {
            // ok
        }
    }

    @Test
    public void testSeverityFromTokenFallsBackToInformation()
    {
        assertEquals(Severity.ERROR, Severity.fromToken("Error"));
        assertEquals(Severity.WARNING, Severity.fromToken("Warning"));
        assertEquals(Severity.HINT, Severity.fromToken("Hint"));
        assertEquals(Severity.INFORMATION, Severity.fromToken("Information"));
        assertEquals(Severity.INFORMATION, Severity.fromToken("Whatever"));
        assertEquals(Severity.INFORMATION, Severity.fromToken(null));
    }

    private static Finding findByCode(BslLsReport report, String code)
    {
        List<Finding> all = report.findings();
        for (Finding f : all)
        {
            if (code.equals(f.code()))
            {
                return f;
            }
        }
        fail("no finding with code " + code);
        return null;
    }
}
