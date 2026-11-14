/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.BslStandaloneSetup;

/**
 * Stored modules are judged by the syntax errors EDT itself reports (#921). The platform does not require a
 * semicolon after the last statement of a block before a preprocessor end, and EDT's BSL resource drops the raw
 * grammar's "expected ';'" there, so such a module must be scanned, not refused at the region line.
 */
public class ModuleQueryExtractorPreprocessorTest
{
    @BeforeClass
    public static void registerBsl()
    {
        // Registers the BSL resource service provider that the production lookup resolves.
        new BslStandaloneSetup().createInjectorAndDoEMFRegistration();
    }

    private static String lines(String... lines)
    {
        return String.join("\n", lines) + "\n";
    }

    private static final String RUSSIAN = lines(
        "#Область ПрограммныйИнтерфейс",
        "",
        "Процедура Запросы921() Экспорт",
        "\t#Область ТекстЗапроса",
        "\tТекст = \"ВЫБРАТЬ",
        "\t|\t1 КАК Число\"",
        "\t#КонецОбласти",
        "КонецПроцедуры",
        "",
        "#КонецОбласти",
        "",
        "Процедура Условная921()",
        "\t#Если Сервер Тогда",
        "\tТ = \"ВЫБРАТЬ 2 КАК Ч\"",
        "\t#КонецЕсли",
        "КонецПроцедуры");

    private static final String ENGLISH = lines(
        "#Region Public",
        "Procedure Queries921() Export",
        "\t#Region QueryText",
        "\tText = \"SELECT",
        "\t|\t1 AS Number\"",
        "\t#EndRegion",
        "EndProcedure",
        "#EndRegion");

    @Test
    public void missingSemicolonBeforeRussianPreprocessorEndsIsNotASyntaxError()
    {
        List<ModuleQueryExtractor.Literal> literals = ModuleQueryExtractor.extract(RUSSIAN, null);
        assertEquals(2, literals.size());
        var query = literals.get(0);
        assertNull(query.reason());
        assertEquals(String.join(System.lineSeparator(), "ВЫБРАТЬ", "\t1 КАК Число"), query.queryText());
        assertEquals(Map.of("method", "Запросы921", "line", 5, "column", 10), query.location());
        var conditional = literals.get(1);
        assertNull(conditional.reason());
        assertEquals("ВЫБРАТЬ 2 КАК Ч", conditional.queryText());
        assertEquals(Map.of("method", "Условная921", "line", 14, "column", 6), conditional.location());
    }

    @Test
    public void missingSemicolonBeforeEnglishEndRegionIsNotASyntaxError()
    {
        List<ModuleQueryExtractor.Literal> literals = ModuleQueryExtractor.extract(ENGLISH, null);
        assertEquals(1, literals.size());
        assertNull(literals.get(0).reason());
        assertEquals(String.join(System.lineSeparator(), "SELECT", "\t1 AS Number"), literals.get(0).queryText());
        assertEquals(Map.of("method", "Queries921", "line", 4, "column", 9), literals.get(0).location());
    }

    @Test
    public void methodScopeInsideRegionsFindsOnlyThatMethod()
    {
        var literals = ModuleQueryExtractor.extract(RUSSIAN, "зАПРОСЫ921");
        assertEquals(1, literals.size());
        assertEquals(5, literals.get(0).location().get("line"));
        assertEquals(1, ModuleQueryExtractor.extract(ENGLISH, "QUERIES921").size());
    }

    @Test
    public void aRegionThatClosesInsideAMethodIsStillRefusedAtItsLine()
    {
        assertRefusedAt(lines("#Область А", "Процедура А()", "\tЗ = \"ВЫБРАТЬ 1\";", "#КонецОбласти",
            "КонецПроцедуры"), 4);
    }

    @Test
    public void aStatementAfterTheRegionStillNeedsTheSemicolonBeforeIt()
    {
        // Once the region lines are gone the two statements would touch, so EDT reports the missing ';'.
        assertRefusedAt(lines("Процедура А()", "\t#Область Р", "\tЗ = \"ВЫБРАТЬ 1\"", "\t#КонецОбласти",
            "\tБ = З;", "КонецПроцедуры"), 4);
    }

    @Test
    public void aMissingSemicolonBetweenStatementsIsStillRefused()
    {
        assertRefusedAt(lines("Procedure A()", "\tQ = \"SELECT 1\"", "\tB = 2;", "EndProcedure"), 3);
    }

    private static void assertRefusedAt(String source, int line)
    {
        try
        {
            ModuleQueryExtractor.extract(source, null);
            fail("EDT reports a syntax error here; the module must not be scanned");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("syntax error at line " + line));
            assertTrue(e.getMessage(), e.getMessage().contains("queryText"));
        }
    }
}
