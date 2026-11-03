/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.eclipse.xtext.parser.IParser;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.BslStandaloneSetup;

/** Exercises real BSL string nodes, not a regex substitute for the parser. */
public class ModuleQueryExtractorTest
{
    private static IParser parser;

    @BeforeClass
    public static void setupParser()
    {
        parser = new BslStandaloneSetup().createInjectorAndDoEMFRegistration().getInstance(IParser.class);
    }

    private static List<ModuleQueryExtractor.Literal> extract(String source, String method)
    {
        return ModuleQueryExtractor.extract(source, method, parser);
    }

    private static String module(String body)
    {
        return "Procedure Queries()\n" + body + "\nEndProcedure\n";
    }

    @Test
    public void unframesContinuationsAndUnescapesQuotes()
    {
        var literals = extract(module("  Q = \"SELECT\n    | Ref, \"\"a\"\" AS Label\n    |FROM Catalog.Catalog\";"), null);
        assertEquals(1, literals.size());
        var query = literals.get(0);
        assertNull(query.reason());
        assertEquals(String.join(System.lineSeparator(), "SELECT", " Ref, \"a\" AS Label", "FROM Catalog.Catalog"),
            query.queryText());
        assertEquals(Map.of("method", "Queries", "line", 2, "column", 7), query.location());
    }

    @Test
    public void ordinaryStringsAreNotValidated()
    {
        var literals = extract(module("A = \"Выбрать файл\"; B = \"SELECT file\"; C = \"Hello SELECT 1\";"
            + " D = \"SELECTED FROM Menu\";"), null);
        assertEquals(2, literals.size());
        assertTrue(literals.stream().allMatch(l -> "no query structure".equals(l.reason())));
    }

    @Test
    public void keywordsInsideQueryStringsAndCommentsDoNotSupplyStructure()
    {
        var literals = extract(module("A = \"SELECT file + \"\"FROM\"\"\"; B = \"SELECT file // FROM menu\";"), null);
        assertEquals(2, literals.size());
        assertTrue(literals.stream().allMatch(l -> "no query structure".equals(l.reason())));
    }

    @Test
    public void quotedProjectionsKeepStructureInBothLanguages()
    {
        for (String query : List.of("SELECT \"value\" AS Label", "SELECT \"value\" FROM Catalog.Catalog",
            "SELECT \"\" AS Label", "SELECT \"a \"\"FROM\"\" value\" AS Label",
            "ВЫБРАТЬ \"значение\" КАК Метка", "ВЫБРАТЬ \"значение\" ИЗ Справочник.Catalog"))
        {
            var literals = extract(module("Q = \"" + query.replace("\"", "\"\"") + "\";"), null);
            assertEquals(query, 1, literals.size());
            assertNull(query, literals.get(0).reason());
            assertEquals(query, literals.get(0).queryText());
        }
    }

    @Test
    public void quotedKeywordsAndCommentsCannotSupplyQueryStructure()
    {
        for (String query : List.of("SELECT \"FROM AS INTO\"", "SELECT \"value\" // FROM Catalog.Catalog",
            "SELECT // \"value\"\nAS Label", "ВЫБРАТЬ \"ИЗ КАК ПОМЕСТИТЬ\""))
        {
            String framed = query.replace("\"", "\"\"").replace("\n", "\n|");
            var literals = extract(module("Q = \"" + framed + "\";"), null);
            assertEquals(query, 1, literals.size());
            assertEquals(query, "no query structure", literals.get(0).reason());
        }
    }

    @Test
    public void requiresProjectionBeforeStructuralKeyword()
    {
        var literals = extract(module("A = \"SELECT FROM Menu\"; B = \"SELECT file.FROM\";"), null);
        assertEquals(2, literals.size());
        assertTrue(literals.stream().allMatch(l -> "no query structure".equals(l.reason())));
    }

    @Test
    public void structuralAndConstantProjectionsAreAcceptedWithoutRequiringValidQl()
    {
        var literals = extract(module("A = \"SELECT Ref FROM Catalog.Catalog\"; B = \"SELECT 1\";"
            + " C = \"SELECT &Value\"; D = \"SELECT *\"; E = \"SELECT Ref INTO Temp\";"
            + " F = \"SELECT Ref AS Value\"; G = \"SELECT Ref FROM\";"), null);
        assertEquals(7, literals.size());
        assertTrue(literals.stream().allMatch(l -> l.reason() == null));
    }

    @Test
    public void bilingualMethodsAndKeywordsAreCaseInsensitive()
    {
        String source = "Функция Запросы()\nА = \"  выбрать Ссылка из Справочник.Catalog\";\n"
            + "Б = \"Выбрать 1 КАК Число\";\nКонецФункции\n"
            + "Procedure English()\nQ = \"select 2 as Value\";\nEndProcedure\n";
        var ru = extract(source, "зАПРОСЫ");
        assertEquals(2, ru.size());
        assertTrue(ru.stream().allMatch(l -> "Запросы".equals(l.method()) && l.reason() == null));
        var en = extract(source, "eNGLISH");
        assertEquals(1, en.size());
        assertEquals("English", en.get(0).method());
        assertEquals(3, extract(source, null).size());
    }

    @Test
    public void concatenationOnEitherSideAndAcrossCommentsIsSkipped()
    {
        var literals = extract(module("A = (\"SELECT Ref FROM Catalog.Catalog\") // suffix\n + Condition;\n"
            + "B = Prefix + \"SELECT 1\";\nC = \"SELECT \" + Fields;\nD = \"SELECT 2\";"), null);
        assertEquals(4, literals.size());
        for (int i = 0; i < 3; i++) assertEquals("dynamic", literals.get(i).reason());
        assertNull(literals.get(3).reason());
        assertEquals(2, literals.get(0).location().get("line"));
    }

    @Test
    public void adjacentLiteralPartsAreAlsoSkipped()
    {
        var literals = extract(module("Q = \"SELECT 1\"\n \" FROM Catalog.Catalog\";"), null);
        assertEquals(1, literals.size());
        assertEquals("dynamic", literals.get(0).reason());
    }

    @Test
    public void severalQueriesInOneMethodKeepTheirOwnLocations()
    {
        var literals = extract(module(" A = \"SELECT 1\"; B = \"SELECT 2\";\n C = \"SELECT 3\";"), "QUERIES");
        assertEquals(3, literals.size());
        assertEquals(Map.of("method", "Queries", "line", 2, "column", 6), literals.get(0).location());
        assertEquals(Map.of("method", "Queries", "line", 2, "column", 22), literals.get(1).location());
        assertEquals(Map.of("method", "Queries", "line", 3, "column", 6), literals.get(2).location());
    }

    @Test
    public void mapsDiagnosticLinesAndColumnsAfterFramingEscapesAndCrLf()
    {
        String source = module(" Q = \"SELECT\n  | \"\"text\"\", Missing AS Value\n  |FROM Catalog.Catalog\";")
            .replace("\n", "\r\n");
        var literal = extract(source, null).get(0);
        int queryOffset = literal.queryText().indexOf("Missing");
        var mapped = literal.mapIssue(Map.of("severity", "ERROR", "message", "missing", "line", 2,
            "column", 10, "offset", queryOffset));
        assertEquals(3, mapped.get("line"));
        assertEquals(15, mapped.get("column"));
        assertEquals(source.indexOf("Missing"), mapped.get("offset"));
        assertEquals(2, mapped.get("queryLine"));
        assertEquals(queryOffset, mapped.get("queryOffset"));
        var byLine = literal.mapIssue(Map.of("line", 3, "column", 6));
        assertEquals(4, byLine.get("line"));
        assertEquals(9, byLine.get("column"));
    }

    @Test
    public void mappingPreservesPhysicalGapsBetweenContinuationTokens()
    {
        String source = module("Q = \"SELECT\n// stored comment\n |Missing AS Value\";");
        var literal = extract(source, null).get(0);
        assertEquals(4, literal.mapIssue(Map.of("line", 2, "column", 1)).get("line"));
    }

    @Test
    public void missingLocationsStayUnlocated()
    {
        var literal = extract(module("Q = \"SELECT 1\";"), null).get(0);
        assertEquals(Map.of("message", "unlocated"), literal.mapIssue(Map.of("message", "unlocated")));
    }

    @Test
    public void ignoresCommentsAndReportsNoCandidatesHonestly()
    {
        assertTrue(extract(module("// Q = \"SELECT 1\";\nA = \"Pick a file\";"), null).isEmpty());
    }

    @Test
    public void moduleLevelLiteralsHaveNoMethodAndAreExcludedByMethodScope()
    {
        String source = module("Q = \"SELECT 1\";") + "ModuleQuery = \"SELECT 2\";\n";
        var literals = extract(source, null);
        assertEquals(2, literals.size());
        assertNull(literals.get(1).method());
        assertEquals(4, literals.get(1).location().get("line"));
        assertEquals(1, extract(source, "Queries").size());
    }

    @Test
    public void legalQueriesOutsideTheNarrowRuleAreExplicitlySkipped()
    {
        var literals = extract(module("A = \"SELECT TRUE\"; B = \"SELECT NULL\"; C = \"SELECT \"\"value\"\"\";"), null);
        assertEquals(3, literals.size());
        assertTrue(literals.stream().allMatch(l -> "no query structure".equals(l.reason())));
    }

    @Test
    public void unknownMethodIsActionable()
    {
        try
        {
            extract(module("Q = \"SELECT 1\";"), "Missing538");
            fail("Missing method must not scan the whole module");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("Missing538"));
            assertTrue(e.getMessage().contains("get_module_structure"));
        }
    }

    @Test
    public void brokenBslIsNotSilentlyScannedAsCompleteLiterals()
    {
        try
        {
            extract(module("Q = \"SELECT 1;"), null);
            fail("Incomplete BSL must be refused");
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("syntax error"));
            assertTrue(e.getMessage().contains("queryText"));
        }
    }
}
