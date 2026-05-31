/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void testInternalYaxunitFramesCollapsed() throws Exception
    {
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"msg\">"
                        + "{YAXUNIT ОбщийМодуль.ЮТУтверждения.Модуль(2239)}:ВызватьИсключение;\n"
                        + "{YAXUNIT ОбщийМодуль.ЮТУтверждения.Модуль(266)}:ПроверитьПредикат;\n"
                        + "{YAXUNIT ОбщийМодуль.ОМ_Тест.Модуль(86)}:ЮТест.ОжидаетЧто(x)\n"
                        + "{(1)}:Объект.МойТест()\n"
                        + "{YAXUNIT ОбщийМодуль.ЮТМетодыСлужебный.Модуль(228)}:Выполнить;\n"
                        + "{YAXUNIT ОбщийМодуль.ЮТИсполнительСлужебныйКлиентСервер.Модуль(330)}:Ошибка;\n"
                        + "{YAXUNIT ОбщийМодуль.ЮТИсполнительСлужебныйВызовСервера.Модуль(46)}:Возврат;"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);

        // YAXUnit-internal framework frames are stripped.
        assertFalse(md.contains("ЮТУтверждения.Модуль"));
        assertFalse(md.contains("ЮТМетодыСлужебный.Модуль"));
        assertFalse(md.contains("ЮТИсполнительСлужебныйКлиентСервер.Модуль"));
        assertFalse(md.contains("ЮТИсполнительСлужебныйВызовСервера.Модуль"));

        // User frames (the test module and the object frame) are kept.
        assertTrue(md.contains("ОМ_Тест.Модуль(86)"));
        assertTrue(md.contains("{(1)}:Объект.МойТест()"));

        // A collapse marker tells the model the trace was trimmed.
        assertTrue(md.contains("internal YAXUnit frames hidden"));
    }

    private static int count(String haystack, String needle)
    {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length()))
        {
            n++;
        }
        return n;
    }

    @Test
    public void testHeadDroppedWhenMessageHasExtraPrefix() throws Exception
    {
        // Error reports restate the message as the first trace line, minus a
        // leading "Исполнения: "-style prefix — so it is NOT an exact match.
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" errors=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<error message=\"Исполнения: DUPME unique payload token\">"
                        + "DUPME unique payload token\n"
                        + "{(1)}:Объект.Тест()\n"
                        + "{YAXUNIT ОбщийМодуль.ЮТМетодыСлужебный.Модуль(228)}:W;"
                        + "</error>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        // The restated head line is gone — the payload now appears only in **Message:**.
        assertEquals(1, count(md, "DUPME unique payload token"));
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
    }

    @Test
    public void testHeadDroppedWhenWrappedAsFailedMarker() throws Exception
    {
        // Assertion failures wrap the message as "[Failed] <message>".
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"ASSERT payload content token\">"
                        + "[Failed] &lt;ASSERT payload content token&gt;\n"
                        + "{(1)}:Объект.Тест()"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertEquals(1, count(md, "ASSERT payload content token"));
        assertFalse(md.contains("[Failed]"));
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
    }

    @Test
    public void testUniqueHeadKept() throws Exception
    {
        // A head that does NOT restate the message must be preserved.
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"the assertion message text\">"
                        + "completely different diagnostic head line\n"
                        + "{(1)}:Объект.Тест()"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertTrue(md.contains("completely different diagnostic head line"));
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
    }

    @Test
    public void testLanguageIndependentOnEnglishPlatform() throws Exception
    {
        // On an English platform the metadata-kind words and the engine error
        // category are localized (CommonModule/Module, EmbeddedLanguageRuntimeError),
        // but the YAXUnit module names stay Cyrillic and the frame structure is
        // unchanged — so trimming must still work.
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"msg\">"
                        + "{YAXUNIT CommonModule.ЮТУтверждения.Module(2239)}:Raise;\n"
                        + "{YAXUNIT CommonModule.ОМ_Тест.Module(86)}:Z\n"
                        + "{(1)}:Object.MyTest()\n"
                        + "{YAXUNIT CommonModule.ЮТИсполнительСлужебныйВызовСервера.Module(46)}:Return;\n"
                        + "\n"
                        + "[EmbeddedLanguageRuntimeError, ExceptionRaisedFromEmbeddedLanguage]{\n"
                        + "\"#value\": \"ENGLISH_DUMP_PAYLOAD\"\n"
                        + "}"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);

        // Internal frames stripped despite the English metadata-kind words.
        assertFalse(md.contains("ЮТУтверждения"));
        assertFalse(md.contains("ЮТИсполнительСлужебныйВызовСервера"));
        // English engine value-dump dropped (cut after the last frame).
        assertFalse(md.contains("ENGLISH_DUMP_PAYLOAD"));
        assertFalse(md.contains("EmbeddedLanguageRuntimeError"));
        // User frames kept.
        assertTrue(md.contains("ОМ_Тест.Module(86)"));
        assertTrue(md.contains("{(1)}:Object.MyTest()"));
        assertTrue(md.contains("internal YAXUnit frames hidden"));
    }

    @Test
    public void testTrailingEngineValueDumpRemoved() throws Exception
    {
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"msg\">"
                        + "{(1)}:Объект.Тест()\n"
                        + "\n"
                        + "[ОшибкаВоВремяВыполненияВстроенногоЯзыка, ИсключениеВызванноеИзВстроенногоЯзыка]{\n"
                        + "\"#value\": \"DUPLICATE_VALUE_PAYLOAD\"\n"
                        + "}"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);

        // The trailing engine value-dump (duplicating the message) is gone.
        assertFalse(md.contains("DUPLICATE_VALUE_PAYLOAD"));
        assertFalse(md.contains("ОшибкаВоВремяВыполненияВстроенногоЯзыка"));

        // The meaningful user frame survives.
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
    }

    @Test
    public void testTrailingEngineMarkerWithoutValueBlockRemoved() throws Exception
    {
        // Variant seen in error reports: the marker appears as a bare line with no { } block.
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" errors=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<error message=\"msg\">"
                        + "{(1)}:Объект.Тест()\n"
                        + "\n"
                        + "[ОшибкаВоВремяВыполненияВстроенногоЯзыка, ИсключениеВызванноеИзВстроенногоЯзыка]"
                        + "</error>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertFalse(md.contains("ОшибкаВоВремяВыполненияВстроенногоЯзыка"));
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
    }

    @Test
    public void testMessageEmbeddedFramesNotTouched() throws Exception
    {
        // Frames embedded inside the message text are NOT YAXUNIT framework frames
        // (no "YAXUNIT" prefix) and must be preserved as part of the error description.
        JUnitTestResults r = parse(
                "<testsuite name=\"S\" tests=\"1\" failures=\"1\">"
                        + "<testcase classname=\"OM_a\" name=\"t\">"
                        + "<failure message=\"msg\">"
                        + "{ОбщийМодуль.APIВебСервисыСлужебный.Модуль(275)}:ВызватьИсключение;\n"
                        + "{(1)}:Объект.Тест()"
                        + "</failure>"
                        + "</testcase>"
                        + "</testsuite>");

        String md = JUnitMarkdownFormatter.format(r);
        assertTrue(md.contains("APIВебСервисыСлужебный.Модуль(275)"));
        assertTrue(md.contains("{(1)}:Объект.Тест()"));
        assertFalse(md.contains("скрыто служебных кадров YAXUnit"));
    }
}
