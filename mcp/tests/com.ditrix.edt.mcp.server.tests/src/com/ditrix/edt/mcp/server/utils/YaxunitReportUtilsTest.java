/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;

/** Tests the shared report path, including the filter context saved beside JUnit XML. */
public class YaxunitReportUtilsTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path report(int total, Map<String, List<String>> filter) throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("junit.xml"),
            "<testsuite tests=\"" + total + "\"/>", StandardCharsets.UTF_8);
        if (filter != null)
        {
            Files.writeString(directory.resolve("xUnitParams.json"),
                GsonProvider.toJson(Map.of("filter", filter)), StandardCharsets.UTF_8);
        }
        return directory;
    }

    private void assertNoMatch(String family, String value) throws Exception
    {
        Path directory = report(0, Map.of(family, List.of(value)));
        String markdown = YaxunitReportUtils.renderAndSave(directory.resolve("junit.xml").toFile());
        assertTrue(markdown.contains("**WARNING: The filter matched no tests (Total=0).**"));
        assertTrue(markdown.contains("| " + family + " |"));
        assertTrue(markdown.contains(value));
        assertTrue(markdown.contains("**Result: NO TESTS MATCHED**"));
        assertFalse(markdown.contains("**Result: PASSED**"));
        assertFalse(markdown.contains("**Result: FAILED**"));
        String saved = Files.readString(directory.resolve("report.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.startsWith(saved));
    }

    @Test
    public void testZeroTestsWithModuleFilterWarnsInBothLanguages() throws Exception
    {
        assertNoMatch("modules", "MissingModule");
        assertNoMatch("modules", "Тест_Несуществующий");
    }

    @Test
    public void testZeroTestsWithTestFilterWarnsInBothLanguages() throws Exception
    {
        assertNoMatch("tests", "Module.MissingTest");
        assertNoMatch("tests", "Тест_ДымовыеТесты.Несуществующий");
    }

    @Test
    public void testZeroTestsNamesEverySelectedModuleAndTest() throws Exception
    {
        Path directory = report(0, Map.of("modules", List.of("MissingA", "Тест_Б"),
            "tests", List.of("MissingA.One", "Тест_Б.Два")));
        String markdown = YaxunitReportUtils.renderAndSave(directory.resolve("junit.xml").toFile());
        assertTrue(markdown.contains("MissingA, Тест_Б"));
        assertTrue(markdown.contains("MissingA.One, Тест_Б.Два"));
        assertTrue(markdown.indexOf("WARNING") < markdown.indexOf("## Summary"));
    }

    @Test
    public void testWarningEscapesFilterValuesForMarkdownTable() throws Exception
    {
        Path directory = report(0, Map.of("modules", List.of("Missing|Module`")));
        String markdown = YaxunitReportUtils.renderAndSave(directory.resolve("junit.xml").toFile());
        assertTrue(markdown.contains("| modules | `` Missing\\|Module` `` |"));
    }

    @Test
    public void testAbsentOrEmptyModuleAndTestFiltersKeepTheOriginalSummary() throws Exception
    {
        List<Map<String, List<String>>> filters = List.of(Map.of(),
            Map.of("modules", List.of()), Map.of("tests", List.of()),
            Map.of("tags", List.of("smoke")), Map.of("extensions", List.of("Ext")));
        for (Map<String, List<String>> filter : filters)
        {
            assertOriginalSummary(report(0, filter));
        }
        assertOriginalSummary(report(0, null));
    }

    @Test
    public void testNonzeroFilteredRunKeepsTheOriginalSummary() throws Exception
    {
        assertOriginalSummary(report(1, Map.of("modules", List.of("Тест_ДымовыеТесты"),
            "tests", List.of("Тест_ДымовыеТесты.Проверка"))));
    }

    private void assertOriginalSummary(Path directory) throws Exception
    {
        String markdown = YaxunitReportUtils.renderAndSave(directory.resolve("junit.xml").toFile());
        String original = JUnitMarkdownFormatter.format(JUnitXmlParser.parse(
            directory.resolve("junit.xml").toFile()));
        assertTrue(markdown.startsWith(original));
        assertFalse(markdown.contains("WARNING"));
    }

    @Test
    public void testZeroTestsWarningDoesNotHideReportedErrors() throws Exception
    {
        Path directory = report(0, Map.of("modules", List.of("Module")));
        Files.writeString(directory.resolve("junit.xml"),
            "<testsuite tests=\"0\" errors=\"1\"/>", StandardCharsets.UTF_8);
        String markdown = YaxunitReportUtils.renderAndSave(directory.resolve("junit.xml").toFile());
        assertTrue(markdown.contains("WARNING"));
        assertTrue(markdown.contains("**Result: FAILED**"));
        assertFalse(markdown.contains("**Result: NO TESTS MATCHED**"));
    }

    @Test
    public void testPartialReportDoesNotClaimTheFilterMatchedNothing() throws Exception
    {
        Path directory = report(0, Map.of("modules", List.of("Module")));
        assertEquals(JUnitMarkdownFormatter.format(JUnitXmlParser.parse(
            directory.resolve("junit.xml").toFile())), YaxunitReportUtils.renderIfUsable(directory));
    }
}
