/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter.FrameLocation;

/**
 * Tests the test-fail → debug bridge: the failing-source location the report
 * surfaces for each failure, parsed from the first actionable user stack frame.
 */
public class JUnitMarkdownFormatterLocationTest
{
    /** Two leading YAXUnit-internal frames must be skipped; the first user frame wins. */
    @Test
    public void testFirstUserFrameAfterInternalFrames()
    {
        String trace = String.join("\n",
            "{CommonModule.ЮТУтверждения.Module(120)}:ВызватьИсключение;",
            "{CommonModule.ЮТМетодыСлужебный.Module(45)}:Выполнить(Тест);",
            "{tests CommonModule.tests_SampleTests.Module(51)}:ЮТест.ОжидаетЧто(2 + 2).Равно(5);",
            "{(1)}:Объект.TwoPlusTwoIsFour()");

        FrameLocation loc = JUnitMarkdownFormatter.firstUserFrameLocation(trace);

        assertEquals("CommonModules/tests_SampleTests/Module.bsl", loc.modulePath);
        assertEquals(51, loc.line);
        assertEquals("tests", loc.extension);
    }

    /** Russian platform tokens (ОбщийМодуль/Модуль) still resolve; the file name stays English. */
    @Test
    public void testRussianPlatformTokens()
    {
        FrameLocation loc = JUnitMarkdownFormatter.firstUserFrameLocation(
            "{tests ОбщийМодуль.tests_SampleTests.Модуль(51)}:ЮТест.ОжидаетЧто(2 + 2).Равно(5);");

        assertEquals("CommonModules/tests_SampleTests/Module.bsl", loc.modulePath);
        assertEquals(51, loc.line);
        assertEquals("tests", loc.extension);
    }

    /** A base-configuration frame carries no extension prefix. */
    @Test
    public void testBaseConfigurationFrameHasNoExtension()
    {
        FrameLocation loc = JUnitMarkdownFormatter.firstUserFrameLocation(
            "{CommonModule.Calc.Module(10)}:Возврат а - б;");

        assertEquals("CommonModules/Calc/Module.bsl", loc.modulePath);
        assertEquals(10, loc.line);
        assertNull(loc.extension);
    }

    /** A non-common-module failure frame is skipped for the nearest common-module caller. */
    @Test
    public void testNonCommonModuleFrameFallsThroughToCommonCaller()
    {
        String trace = String.join("\n",
            "{Catalog.Products.ObjectModule(7)}:ВызватьИсключение;",
            "{tests CommonModule.tests_SampleTests.Module(60)}:Catalogs.Products.CreateItem();");

        FrameLocation loc = JUnitMarkdownFormatter.firstUserFrameLocation(trace);

        assertEquals("CommonModules/tests_SampleTests/Module.bsl", loc.modulePath);
        assertEquals(60, loc.line);
    }

    /** No common-module frame at all → no fabricated CommonModules path. */
    @Test
    public void testOnlyNonCommonModuleFrameReturnsNull()
    {
        assertNull(JUnitMarkdownFormatter.firstUserFrameLocation(
            "{Catalog.Products.ManagerModule(3)}:ВызватьИсключение;"));
    }

    @Test
    public void testNullAndNoFrameReturnNull()
    {
        assertNull(JUnitMarkdownFormatter.firstUserFrameLocation(null));
        assertNull(JUnitMarkdownFormatter.firstUserFrameLocation("plain message, no frames"));
    }

    /** A trace with only internal frames yields no actionable location. */
    @Test
    public void testInternalOnlyTraceReturnsNull()
    {
        assertNull(JUnitMarkdownFormatter.firstUserFrameLocation(
            "{CommonModule.ЮТУтверждения.Module(1)}:ВызватьИсключение;"));
    }

    /** The rendered report exposes the location so the caller can chain into set_breakpoint. */
    @Test
    public void testFormatRendersLocationForFailure()
    {
        JUnitTestResults results = new JUnitTestResults();
        results.addToTotals(1, 1, 0, 0);
        results.addFailure(new JUnitTestResults.TestCase(
            "tests_SampleTests.TwoPlusTwoIsFour",
            "Ожидали, что значение `4` равно `5`",
            "{tests CommonModule.tests_SampleTests.Module(51)}:ЮТест.ОжидаетЧто(2 + 2).Равно(5);"));

        String md = JUnitMarkdownFormatter.format(results);

        assertTrue("report must surface the failing source location",
            md.contains("**Location:** CommonModules/tests_SampleTests/Module.bsl:51"));
        assertTrue("location should note the owning extension",
            md.contains("(extension: tests)"));
    }
}
