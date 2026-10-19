/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.SearchFixtureProject;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link SearchInDcsTool}: the contract, the argument errors, the path-to-template-FQN
 * rule, and the scan itself on a real workspace project (only {@code *.dcs}, paths without
 * {@code src/}, the template FQN per file, and an empty result that never claims absence on an
 * incomplete scan).
 */
public class SearchInDcsToolTest
{
    private static final String STOCK = "Reports/Stock/Templates/Main/Template.dcs"; //$NON-NLS-1$
    private static final String ANALYTICS = "CommonTemplates/Analytics/Template.dcs"; //$NON-NLS-1$
    private static final String ODD = "Odd/Thing.dcs"; //$NON-NLS-1$
    private static final String MARK = "E2E_MARK"; //$NON-NLS-1$

    /** {@code КоличествоОстаток}, a DCS resource name. */
    private static final String QUANTITY_BALANCE =
        "КоличествоОстаток"; //$NON-NLS-1$

    private SearchFixtureProject fixture;

    @After
    public void tearDown() throws CoreException
    {
        if (fixture != null)
        {
            fixture.close();
        }
    }

    private SearchFixtureProject project() throws CoreException
    {
        fixture = SearchFixtureProject.create();
        fixture.file("src/" + STOCK, "<dataSets>\n<query>SELECT " + MARK + " FROM X</query>\n<field>" //$NON-NLS-1$ //$NON-NLS-2$
            + QUANTITY_BALANCE + "</field>\n</dataSets>\n"); //$NON-NLS-1$
        fixture.file("src/" + ANALYTICS, "<a>" + MARK + "</a>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/" + ODD, MARK + "\n"); //$NON-NLS-1$
        // Not DCS schemas: never searched.
        fixture.file("src/Reports/Stock/ObjectModule.bsl", "// " + MARK + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        fixture.file("src/Reports/Stock/Templates/Main/Settings.dcss", MARK + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return fixture;
    }

    private static Map<String, String> params(String projectName, String query, String... more)
    {
        Map<String, String> params = new HashMap<>();
        if (projectName != null)
        {
            params.put("projectName", projectName); //$NON-NLS-1$
        }
        if (query != null)
        {
            params.put("query", query); //$NON-NLS-1$
        }
        for (int i = 0; i < more.length; i += 2)
        {
            params.put(more[i], more[i + 1]);
        }
        return params;
    }

    private String search(String query, String... more)
    {
        return new SearchInDcsTool().execute(params(fixture.name(), query, more));
    }

    // ==================== contract ====================

    @Test
    public void testNameAndResponseType()
    {
        SearchInDcsTool tool = new SearchInDcsTool();
        assertEquals("search_in_dcs", tool.getName()); //$NON-NLS-1$
        assertEquals(SearchInDcsTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNamesTheScopeAndTheGuide()
    {
        String description = new SearchInDcsTool().getDescription();
        assertTrue(description, description.contains("*.dcs")); //$NON-NLS-1$
        assertTrue(description, description.contains("dcs")); //$NON-NLS-1$
        assertTrue(description, description.contains("get_tool_guide('search_in_dcs')")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresExactlyTheContract()
    {
        JsonObject schema = JsonParser.parseString(new SearchInDcsTool().getInputSchema()).getAsJsonObject();
        Set<String> properties = new TreeSet<>(schema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
        assertEquals(new TreeSet<>(Set.of("projectName", "query", "caseSensitive", "isRegex", "limit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "contextLines", "fileMask", "outputMode")), properties); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Set<String> required = new TreeSet<>();
        for (JsonElement element : schema.getAsJsonArray("required")) //$NON-NLS-1$
        {
            required.add(element.getAsString());
        }
        assertEquals(new TreeSet<>(Set.of("projectName", "query")), required); //$NON-NLS-1$ //$NON-NLS-2$

        Set<String> modes = new TreeSet<>();
        for (JsonElement element : schema.getAsJsonObject("properties").getAsJsonObject("outputMode") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonArray("enum")) //$NON-NLS-1$
        {
            modes.add(element.getAsString());
        }
        assertEquals(new TreeSet<>(Set.of("full", "count", "files")), modes); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testResultFileName()
    {
        assertEquals("search-dcs-stock-balance.md", //$NON-NLS-1$
            new SearchInDcsTool().getResultFileName(params(null, "Stock Balance"))); //$NON-NLS-1$
        assertEquals("search-dcs-results.md", new SearchInDcsTool().getResultFileName(params(null, null))); //$NON-NLS-1$
    }

    // ==================== argument errors ====================

    @Test
    public void testMissingArgumentsAreNamed()
    {
        assertTrue(new SearchInDcsTool().execute(params(null, "x")).contains("projectName is required")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new SearchInDcsTool().execute(params("P", null)).contains("query is required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnknownOutputModeIsRejected()
    {
        String result = new SearchInDcsTool().execute(params("P", "x", "outputMode", "Sideways")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(result, result.contains("outputMode must be 'full', 'count', or 'files'")); //$NON-NLS-1$
    }

    @Test
    public void testAnInvalidRegexNamesThePatternAndTheLiteralFallback()
    {
        String result = new SearchInDcsTool().execute(params("NoSuchProject541", "Sum(", "isRegex", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("Invalid regex pattern 'Sum('")); //$NON-NLS-1$
        assertTrue(result, result.contains("isRegex=false")); //$NON-NLS-1$
    }

    @Test
    public void testTheSameTextIsALiteralWithoutIsRegex() throws CoreException
    {
        project();
        fixture.file("src/Reports/Paren/Templates/T/Template.dcs", "Sum(Amount)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = search("Sum(", "outputMode", "count"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result, result.contains("**Total matches:** 1 in **1** files")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownProjectIsNamed()
    {
        String result = new SearchInDcsTool().execute(params("NoSuchProject541", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("Project not found: NoSuchProject541")); //$NON-NLS-1$
    }

    // ==================== template FQN ====================

    @Test
    public void testTemplateFqnOfAnObjectOwnedTemplate()
    {
        assertEquals("Report.Stock.Template.Main", SearchInDcsTool.templateFqn(STOCK)); //$NON-NLS-1$
        assertEquals("DataProcessor.Tool.Template.Schema", //$NON-NLS-1$
            SearchInDcsTool.templateFqn("DataProcessors/Tool/Templates/Schema/Template.dcs")); //$NON-NLS-1$
        assertEquals("Report.Остатки.Template.Main", //$NON-NLS-1$
            SearchInDcsTool.templateFqn("Reports/Остатки/Templates/Main/Template.dcs")); //$NON-NLS-1$
    }

    @Test
    public void testTemplateFqnOfACommonTemplate()
    {
        assertEquals("CommonTemplate.Analytics", SearchInDcsTool.templateFqn(ANALYTICS)); //$NON-NLS-1$
    }

    @Test
    public void testAnUnrecognisedLayoutHasNoTemplateFqn()
    {
        assertNull("unknown folder", SearchInDcsTool.templateFqn(ODD)); //$NON-NLS-1$
        assertNull("unknown type folder", SearchInDcsTool.templateFqn("Foo/X/Templates/Y/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a type that cannot own templates", //$NON-NLS-1$
            SearchInDcsTool.templateFqn("CommonModules/X/Templates/Y/Template.dcs")); //$NON-NLS-1$
        assertNull("a folder name is exact, not an alias", //$NON-NLS-1$
            SearchInDcsTool.templateFqn("reports/X/Templates/Y/Template.dcs")); //$NON-NLS-1$
        assertNull("a singular is not a folder", SearchInDcsTool.templateFqn("Report/X/Templates/Y/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("not the template body", SearchInDcsTool.templateFqn("Reports/X/Templates/Y/Other.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("not under Templates", SearchInDcsTool.templateFqn("Reports/X/Forms/Y/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("too shallow", SearchInDcsTool.templateFqn("Reports/X/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("too deep", SearchInDcsTool.templateFqn("Reports/X/Templates/Y/Z/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a common template has no Templates folder", //$NON-NLS-1$
            SearchInDcsTool.templateFqn("CommonTemplates/X/Templates/Y/Template.dcs")); //$NON-NLS-1$
        assertNull("an empty segment", SearchInDcsTool.templateFqn("Reports//Templates/Y/Template.dcs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SearchInDcsTool.templateFqn(null));
    }

    // ==================== the scan ====================

    @Test
    public void testFullModeFindsOnlyDcsAndNamesTheTemplate() throws CoreException
    {
        project();
        String result = search(MARK);

        assertTrue(result, result.startsWith("## Search Results for \"" + MARK + "\"\n\n**Total:** 3 matches in 3 files\n\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("### " + STOCK + "\n\n**Template:** `Report.Stock.Template.Main`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "**Line 2:**\n```xml\n1: <dataSets>\n2: <query>SELECT " + MARK + " FROM X</query>\n3: <field>")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("### " + ANALYTICS + "\n\n**Template:** `CommonTemplate.Analytics`\n\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unrecognised layout is shown by path only", //$NON-NLS-1$
            result.contains("### " + ODD + "\n\n**Line 1:**")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("paths are shown without src/", result.contains("src/")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a BSL module is not searched", result.contains("ObjectModule.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("settings variants are not searched", result.contains(".dcss")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.contains("```bsl")); //$NON-NLS-1$
    }

    @Test
    public void testFilesModeListsEachFileOnceWithItsTemplate() throws CoreException
    {
        project();
        String result = search(MARK, "outputMode", "files"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("## Search Files for \"" + MARK + "\"\n\n**Total matches:** 3 in **3** files\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.tableHeader("File", "Template", "Matches") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + MarkdownUtils.tableRow(ANALYTICS, "CommonTemplate.Analytics", "1") //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.tableRow(ODD, "-", "1") //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.tableRow(STOCK, "Report.Stock.Template.Main", "1"), result); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheCountIsExactWhateverTheLimit() throws CoreException
    {
        project();
        assertEquals("## Search Count for \"" + MARK + "\"\n\n**Total matches:** 3 in **3** files\n", //$NON-NLS-1$ //$NON-NLS-2$
            search(MARK, "outputMode", "count", "limit", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String full = search(MARK, "limit", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(full, full.contains("**Total:** 3 matches in 3 files (showing 1 of 3)")); //$NON-NLS-1$
        assertEquals("one detailed match only", 1, full.split("\\*\\*Line ", -1).length - 1); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheFileMaskIsAPathSubstringWithoutSrc() throws CoreException
    {
        project();
        String stock = search(MARK, "outputMode", "files", "fileMask", "reports/stock"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(stock, stock.contains("**Total matches:** 1 in **1** files")); //$NON-NLS-1$
        assertTrue(stock, stock.contains(STOCK));

        String withSrc = search(MARK, "outputMode", "count", "fileMask", "src/Reports"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(withSrc, withSrc.contains("**Total matches:** 0 in **0** files")); //$NON-NLS-1$
    }

    @Test
    public void testCaseSensitivityOnACyrillicName() throws CoreException
    {
        project();
        String lower = QUANTITY_BALANCE.toLowerCase();
        assertTrue(search(lower, "outputMode", "count").contains("**Total matches:** 1 in **1** files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(search(lower, "outputMode", "count", "caseSensitive", "true") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .contains("**Total matches:** 0 in **0** files")); //$NON-NLS-1$
    }

    @Test
    public void testARegexQuery() throws CoreException
    {
        project();
        String result = search("SELECT\\s+E2E_\\w+", "isRegex", "true", "outputMode", "files"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(result, result.contains("**Total matches:** 1 in **1** files")); //$NON-NLS-1$
        assertTrue(result, result.contains(STOCK));
    }

    @Test
    public void testACompleteScanWithoutMatchesSaysSo() throws CoreException
    {
        project();
        String result = search("absent-text"); //$NON-NLS-1$
        assertTrue(result, result.endsWith("\n\nNo matches found.\n")); //$NON-NLS-1$
        assertFalse(result, result.contains("INCOMPLETE")); //$NON-NLS-1$
    }

    @Test
    public void testAnIncompleteScanNeverClaimsNoMatches() throws CoreException
    {
        project();
        fixture.deleteOnDiskOnly("src/" + ANALYTICS); //$NON-NLS-1$

        for (String mode : new String[] { "full", "files" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String result = search("absent-text", "outputMode", mode); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result, result.contains("could not be read")); //$NON-NLS-1$
            assertTrue(result, result.contains("No matches in the files that were read, but the scan is INCOMPLETE " //$NON-NLS-1$
                + "(1 file(s) could not be read), so this is not proof that the text is absent")); //$NON-NLS-1$
            assertFalse(result, result.contains("No matches found.")); //$NON-NLS-1$
        }

        String hits = search(MARK, "outputMode", "count"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the readable files are still scanned: " + hits, //$NON-NLS-1$
            hits.contains("**Total matches:** 2 in **2** files\n**Warning:** 1 file(s) could not be read\n")); //$NON-NLS-1$
    }
}
