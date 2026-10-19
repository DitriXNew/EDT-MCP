/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.GuideLoader;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.SearchFixtureProject;

/** Regression coverage for the DCS search review, using only temporary workspace fixtures. */
public class SearchInDcsReviewRegressionTest
{
    private static final String DCS_PATH = "Reports/Stock/Templates/Main/Template.dcs"; //$NON-NLS-1$
    private static final String BSL_PATH = "CommonModules/Alpha/Module.bsl"; //$NON-NLS-1$
    private SearchFixtureProject fixture;

    @Before
    public void setUp() throws CoreException
    {
        fixture = SearchFixtureProject.create();
    }

    @After
    public void tearDown() throws CoreException
    {
        fixture.close();
    }

    private Map<String, String> params(String query, String... more)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", fixture.name()); //$NON-NLS-1$
        params.put("query", query); //$NON-NLS-1$
        for (int i = 0; i < more.length; i += 2)
        {
            params.put(more[i], more[i + 1]);
        }
        return params;
    }

    private String search(String query, String... more)
    {
        return new SearchInDcsTool().execute(params(query, more));
    }

    private void schema(String text) throws CoreException
    {
        fixture.file("src/" + DCS_PATH, text); //$NON-NLS-1$
    }

    private void assertCount(String query, int count, String... more)
    {
        Map<String, String> params = params(query, more);
        params.put("outputMode", "count"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("## Search Count for \"" + query + "\"\n\n**Total matches:** " + count //$NON-NLS-1$ //$NON-NLS-2$
            + " in **" + (count == 0 ? 0 : 1) + "** files\n", new SearchInDcsTool().execute(params)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindsXmlEscapedEnglishAndRussianParameterNames() throws CoreException
    {
        schema("<query>SELECT &amp;Period, &amp;ТекущийПериод</query>\n"); //$NON-NLS-1$
        assertCount("&Period", 1); //$NON-NLS-1$
        assertCount("&ТекущийПериод", 1); //$NON-NLS-1$
    }

    @Test
    public void testFindsXmlEscapedComparisonOperatorsLiterallyAndByRegex() throws CoreException
    {
        schema("<query>SELECT A WHERE A &gt; 0 AND A &lt; 10</query>\n"); //$NON-NLS-1$
        assertCount("> 0", 1); //$NON-NLS-1$
        assertCount("< 10", 1); //$NON-NLS-1$
        assertCount("A\\s+>\\s+0", 1, "isRegex", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testFindsXmlEscapedQuotesAndApostrophes() throws CoreException
    {
        schema("<query>SELECT &quot;Quoted&quot;, &apos;Single&apos;</query>\n"); //$NON-NLS-1$
        assertCount("\"Quoted\"", 1); //$NON-NLS-1$
        assertCount("'Single'", 1); //$NON-NLS-1$
    }

    @Test
    public void testFindsDecimalAndHexCharacterReferences() throws CoreException
    {
        schema("<query>&#38;Period A &#x3E; 0 &#x422;екст &#128512; &#x1F600;</query>\n"); //$NON-NLS-1$
        assertCount("&Period", 1); //$NON-NLS-1$
        assertCount("> 0", 1); //$NON-NLS-1$
        assertCount("Текст", 1); //$NON-NLS-1$
        assertCount("\uD83D\uDE00 \uD83D\uDE00", 1); //$NON-NLS-1$
    }

    @Test
    public void testDecodedSearchCountsLinesInEveryModeBeyondTheDetailLimit() throws CoreException
    {
        schema("<schema>\n<query>&amp;Period &amp;Period</query>\n<query>&#38;Period</query>\n</schema>\n"); //$NON-NLS-1$
        String full = search("&Period", "limit", "1", "contextLines", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(full, full.contains("**Total:** 2 matches in 1 files (showing 1 of 2)")); //$NON-NLS-1$
        assertTrue(full, full.contains("**Line 2:**\n```xml\n1: <schema>\n" //$NON-NLS-1$
            + "2: <query>&amp;Period &amp;Period</query>\n3: <query>&#38;Period</query>\n```")); //$NON-NLS-1$
        assertEquals(1, full.split("\\*\\*Line ", -1).length - 1); //$NON-NLS-1$
        assertCount("&Period", 2, "limit", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Files for \"&Period\"\n\n**Total matches:** 2 in **1** files\n\n" //$NON-NLS-1$
            + MarkdownUtils.tableHeader("File", "Template", "Matches") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + MarkdownUtils.tableRow(DCS_PATH, "Report.Stock.Template.Main", "2"), //$NON-NLS-1$ //$NON-NLS-2$
            search("&Period", "outputMode", "files", "limit", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void testDecodingPreservesSourceLinesAndDoesNotMatchAcrossThem() throws CoreException
    {
        schema("<schema>\n<query>&amp;Period&#10;tail</query>\nfirst\nsecond\n</schema>\n"); //$NON-NLS-1$
        String full = search("&Period\ntail", "contextLines", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(full, full.contains("**Total:** 1 matches in 1 files")); //$NON-NLS-1$
        assertTrue(full, full.contains("**Line 2:**\n```xml\n2: <query>&amp;Period&#10;tail</query>\n```")); //$NON-NLS-1$
        assertCount("first\nsecond", 0); //$NON-NLS-1$
    }

    @Test
    public void testEntitiesAreDecodedOnlyOnce() throws CoreException
    {
        schema("<query>Double &amp;lt; 0</query>\n"); //$NON-NLS-1$
        assertCount("Double &lt; 0", 1); //$NON-NLS-1$
        assertCount("Double < 0", 0); //$NON-NLS-1$
    }

    @Test
    public void testUnknownMalformedAndInvalidReferencesStayLiteral() throws CoreException
    {
        String[] references = { "&unknown;", "&AMP;", "&amp", "&#-1;", "&#xZZ;", "&#0;", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "&#xD800;", "&#x110000;", "&#99999999999999999999;" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        schema("<query>" + String.join(" ", references) + "</query>\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String reference : references)
        {
            assertCount(reference, 1);
        }
    }

    @Test
    public void testCodeSearchKeepsEntityTextByteForByteInEveryMode() throws CoreException
    {
        fixture.file("src/" + BSL_PATH, "// &amp;Period\n"); //$NON-NLS-1$ //$NON-NLS-2$
        schema("<query>&amp;Period</query>\n"); //$NON-NLS-1$
        SearchInCodeTool code = new SearchInCodeTool();
        assertEquals("## Search Results for \"&amp;Period\"\n\n**Total:** 1 matches in 1 files\n\n" //$NON-NLS-1$
            + "### " + BSL_PATH + "\n\n**Line 1:**\n```bsl\n1: // &amp;Period\n```\n\n", //$NON-NLS-1$ //$NON-NLS-2$
            code.execute(params("&amp;Period"))); //$NON-NLS-1$
        assertEquals("## Search Count for \"&amp;Period\"\n\n**Total matches:** 1 in **1** files\n", //$NON-NLS-1$
            code.execute(params("&amp;Period", "outputMode", "count"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Files for \"&amp;Period\"\n\n**Total matches:** 1 in **1** files\n\n" //$NON-NLS-1$
            + MarkdownUtils.tableHeader("File", "Matches") + MarkdownUtils.tableRow(BSL_PATH, "1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            code.execute(params("&amp;Period", "outputMode", "files"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Results for \"&Period\"\n\n**Total:** 0 matches in 0 files\n\nNo matches found.\n", //$NON-NLS-1$
            code.execute(params("&Period"))); //$NON-NLS-1$
        assertEquals("## Search Count for \"&Period\"\n\n**Total matches:** 0 in **0** files\n", //$NON-NLS-1$
            code.execute(params("&Period", "outputMode", "count"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Files for \"&Period\"\n\n**Total matches:** 0 in **0** files\n\nNo matches found.\n", //$NON-NLS-1$
            code.execute(params("&Period", "outputMode", "files"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMissingSourceFolderErrorExplainsProjectTypeAndState()
    {
        String result = search("x"); //$NON-NLS-1$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains(fixture.name()));
        assertTrue(result, result.contains("has no src/ folder")); //$NON-NLS-1$
        assertTrue(result, result.contains("configuration or extension project")); //$NON-NLS-1$
        assertTrue(result, result.contains("not loaded")); //$NON-NLS-1$
        assertTrue(result, result.contains("list_projects")); //$NON-NLS-1$
        assertTrue(result, result.contains("type and state")); //$NON-NLS-1$
    }

    @Test
    public void testTemplateFqnPromiseIsLimitedToRecognisedLayouts() throws CoreException
    {
        String description = new SearchInDcsTool().getDescription();
        assertTrue(description, description.contains("template FQN when the layout is recognised")); //$NON-NLS-1$
        fixture.file("src/Odd/Other.dcs", "<query>needle</query>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = search("needle"); //$NON-NLS-1$
        assertTrue(result, result.contains("### Odd/Other.dcs")); //$NON-NLS-1$
        assertFalse(result, result.contains("**Template:**")); //$NON-NLS-1$
    }

    @Test
    public void testGuideExplainsIncompleteFullFilesAndCountModes() throws CoreException
    {
        String guide = GuideLoader.load(SearchInDcsTool.NAME);
        assertTrue(guide, guide.contains("In `full` and `files` modes,")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("`count` mode prints the warnings with the totals")); //$NON-NLS-1$
        schema("<query>x</query>\n"); //$NON-NLS-1$
        fixture.deleteOnDiskOnly("src/" + DCS_PATH); //$NON-NLS-1$
        String count = search("absent", "outputMode", "count"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(count, count.contains("**Total matches:** 0 in **0** files\n**Warning:** 1 file(s) could not be read")); //$NON-NLS-1$
        assertFalse(count, count.contains("No matches found.")); //$NON-NLS-1$
        assertFalse(count, count.contains("INCOMPLETE")); //$NON-NLS-1$
    }
}
