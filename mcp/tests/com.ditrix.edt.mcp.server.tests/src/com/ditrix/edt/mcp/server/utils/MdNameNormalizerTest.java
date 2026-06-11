/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tests for {@link MdNameNormalizer}: the "ё"->"е" / "Ё"->"Е" transformation
 * and the {@link MdNameNormalizer.Report} accumulator.
 */
public class MdNameNormalizerTest
{
    // ===== normalizeYo =====

    @Test
    public void testNullReturnsNull()
    {
        assertNull(MdNameNormalizer.normalizeYo(null));
    }

    @Test
    public void testEmptyReturnsEmpty()
    {
        assertEquals("", MdNameNormalizer.normalizeYo("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLowercaseYoReplaced()
    {
        // "ёлка" -> "елка"
        assertEquals("елка", //$NON-NLS-1$
            MdNameNormalizer.normalizeYo("ёлка")); //$NON-NLS-1$
    }

    @Test
    public void testUppercaseYoReplaced()
    {
        // "Ёж" -> "Еж"
        assertEquals("Еж", MdNameNormalizer.normalizeYo("Ёж")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMixedYoReplaced()
    {
        // "ТёщаЁ" -> "ТещаЕ"
        String input = "ТёщаЁ"; //$NON-NLS-1$
        String expected = "ТещаЕ"; //$NON-NLS-1$
        assertEquals(expected, MdNameNormalizer.normalizeYo(input));
    }

    @Test
    public void testRealWorldSynonym()
    {
        // "Тест отчёт" -> "Тест отчет".
        String input = "Тест отчёт"; //$NON-NLS-1$
        String expected = "Тест отчет"; //$NON-NLS-1$
        assertEquals(expected, MdNameNormalizer.normalizeYo(input));
    }

    @Test
    public void testNoYoReturnsSameInstance()
    {
        // No "yo": the original instance is returned (cheap "unchanged" check).
        String input = "Catalog Продукты"; //$NON-NLS-1$
        assertSame(input, MdNameNormalizer.normalizeYo(input));
    }

    @Test
    public void testOtherCharactersPreserved()
    {
        // Only the two yo code points change; everything else is preserved.
        String input = "a1_-ё Ё.X"; //$NON-NLS-1$
        String expected = "a1_-е Е.X"; //$NON-NLS-1$
        assertEquals(expected, MdNameNormalizer.normalizeYo(input));
    }

    @Test
    public void testLatinENotAffected()
    {
        // The Latin 'e'/'E' must not be touched.
        assertSame("Edition", MdNameNormalizer.normalizeYo("Edition")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ===== containsYo =====

    @Test
    public void testContainsYo()
    {
        assertTrue(MdNameNormalizer.containsYo("ё")); //$NON-NLS-1$
        assertTrue(MdNameNormalizer.containsYo("Ё")); //$NON-NLS-1$
        assertFalse(MdNameNormalizer.containsYo("еЕ")); //$NON-NLS-1$
        assertFalse(MdNameNormalizer.containsYo(null));
        assertFalse(MdNameNormalizer.containsYo("")); //$NON-NLS-1$
    }

    // ===== Report =====

    @Test
    public void testReportEnabledRecordsChangedFields()
    {
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        // name has no yo, synonym has yo.
        assertEquals("Report", report.apply("name", "Report")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String synonym = report.apply("synonym", "Отчёт"); // "Отчёт" //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Отчет", synonym); // "Отчет" //$NON-NLS-1$

        assertTrue(report.hasChanges());
        List<String> fields = report.normalizedFields();
        assertEquals(1, fields.size());
        assertEquals("synonym", fields.get(0)); //$NON-NLS-1$

        // addTo surfaces the rewritten fields as the 'normalized' result field.
        ToolResult result = ToolResult.success();
        report.addTo(result);
        String json = result.toJson();
        assertTrue(json.contains("\"normalized\"")); //$NON-NLS-1$
        assertTrue(json.contains("synonym")); //$NON-NLS-1$
    }

    @Test
    public void testReportDisabledLeavesTextAndRecordsNothing()
    {
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(false);
        String input = "Отчёт"; // "Отчёт" //$NON-NLS-1$
        // Disabled: value is returned untouched, nothing recorded.
        assertSame(input, report.apply("synonym", input)); //$NON-NLS-1$
        assertFalse(report.hasChanges());
        assertTrue(report.normalizedFields().isEmpty());
        ToolResult result = ToolResult.success();
        report.addTo(result);
        assertFalse(result.toJson().contains("\"normalized\"")); //$NON-NLS-1$
    }

    @Test
    public void testReportNullValueHandled()
    {
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        assertNull(report.apply("comment", null)); //$NON-NLS-1$
        assertFalse(report.hasChanges());
    }

    @Test
    public void testReportNoChangeWhenNoYo()
    {
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        String input = "Products"; //$NON-NLS-1$
        assertSame(input, report.apply("name", input)); //$NON-NLS-1$
        assertFalse(report.hasChanges());
        ToolResult result = ToolResult.success();
        report.addTo(result);
        assertFalse(result.toJson().contains("\"normalized\"")); //$NON-NLS-1$
    }

    @Test
    public void testReportOrderPreserved()
    {
        MdNameNormalizer.Report report = new MdNameNormalizer.Report(true);
        report.apply("name", "Имёна"); // has yo //$NON-NLS-1$ //$NON-NLS-2$
        report.apply("synonym", "Сёмга"); // has yo //$NON-NLS-1$ //$NON-NLS-2$
        List<String> fields = report.normalizedFields();
        assertEquals(2, fields.size());
        assertEquals("name", fields.get(0)); //$NON-NLS-1$
        assertEquals("synonym", fields.get(1)); //$NON-NLS-1$
    }
}
