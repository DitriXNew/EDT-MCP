/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.SearchFixtureProject;

/**
 * Pins the exact text {@link SearchInCodeTool} returns in all three output modes on a real
 * workspace project. The scanner and the renderer were moved out of the tool into the shared
 * {@code ResourceTextSearch} / {@code SearchResultFormatter} (#541); these literals are the
 * pre-extraction output, so the move cannot change a byte of the {@code search_in_code} wire
 * text: the same test passes against the tool as it was before the move.
 */
public class SearchInCodeRenderingPinTest
{
    private static final String ALPHA = "CommonModules/Alpha/Module.bsl"; //$NON-NLS-1$
    private static final String BETA = "CommonModules/Beta/Module.bsl"; //$NON-NLS-1$
    private static final String DOC = "Documents/Doc/ObjectModule.bsl"; //$NON-NLS-1$

    private SearchFixtureProject fixture;

    @Before
    public void setUp() throws CoreException
    {
        fixture = SearchFixtureProject.create();
        fixture.file("src/" + ALPHA, //$NON-NLS-1$
            "// header\nProcedure Foo() Export\n    Message(\"Needle one\");\nEndProcedure\n"); //$NON-NLS-1$
        fixture.file("src/" + BETA, "Needle\nneedle\nx\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/" + DOC, "Needle here\n"); //$NON-NLS-1$ //$NON-NLS-2$
        // Not BSL: search_in_code must not read these.
        fixture.file("src/Reports/R/Templates/T/Template.dcs", "<needle/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/Configuration/Configuration.mdo", "needle\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws CoreException
    {
        fixture.close();
    }

    private String search(String query, String... more)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", fixture.name()); //$NON-NLS-1$
        params.put("query", query); //$NON-NLS-1$
        params.put("contextLines", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < more.length; i += 2)
        {
            params.put(more[i], more[i + 1]);
        }
        return new SearchInCodeTool().execute(params);
    }

    private static final String ALPHA_SECTION = "### CommonModules/Alpha/Module.bsl\n\n" //$NON-NLS-1$
        + "**Line 3:**\n```bsl\n2: Procedure Foo() Export\n3:     Message(\"Needle one\");\n4: EndProcedure\n```\n\n"; //$NON-NLS-1$

    private static final String BETA_LINE_1 = "**Line 1:**\n```bsl\n1: Needle\n2: needle\n```\n\n"; //$NON-NLS-1$

    @Test
    public void testFullModeText()
    {
        assertEquals("## Search Results for \"needle\"\n\n" //$NON-NLS-1$
            + "**Total:** 4 matches in 3 files\n\n" //$NON-NLS-1$
            + ALPHA_SECTION
            + "### CommonModules/Beta/Module.bsl\n\n" //$NON-NLS-1$
            + BETA_LINE_1
            + "**Line 2:**\n```bsl\n1: Needle\n2: needle\n3: x\n```\n\n" //$NON-NLS-1$
            + "### Documents/Doc/ObjectModule.bsl\n\n" //$NON-NLS-1$
            + "**Line 1:**\n```bsl\n1: Needle here\n```\n\n", //$NON-NLS-1$
            search("needle")); //$NON-NLS-1$
    }

    @Test
    public void testFullModeTextWhenTheLimitTruncates()
    {
        assertEquals("## Search Results for \"needle\"\n\n" //$NON-NLS-1$
            + "**Total:** 4 matches in 3 files (showing 2 of 4)\n\n" //$NON-NLS-1$
            + ALPHA_SECTION
            + "### CommonModules/Beta/Module.bsl\n\n" //$NON-NLS-1$
            + BETA_LINE_1,
            search("needle", "limit", "2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCountModeText()
    {
        assertEquals("## Search Count for \"needle\"\n\n**Total matches:** 4 in **3** files\n", //$NON-NLS-1$
            search("needle", "outputMode", "count")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testFilesModeText()
    {
        assertEquals("## Search Files for \"needle\"\n\n**Total matches:** 4 in **3** files\n\n" //$NON-NLS-1$
            + MarkdownUtils.tableHeader("File", "Matches") //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.tableRow(ALPHA, "1") //$NON-NLS-1$
            + MarkdownUtils.tableRow(BETA, "2") //$NON-NLS-1$
            + MarkdownUtils.tableRow(DOC, "1"), //$NON-NLS-1$
            search("needle", "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testNoMatchText()
    {
        assertEquals("## Search Results for \"absent\"\n\n**Total:** 0 matches in 0 files\n\nNo matches found.\n", //$NON-NLS-1$
            search("absent")); //$NON-NLS-1$
        assertEquals("## Search Files for \"absent\"\n\n**Total matches:** 0 in **0** files\n\nNo matches found.\n", //$NON-NLS-1$
            search("absent", "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testMetadataTypeScopesToItsFolder()
    {
        assertEquals("## Search Count for \"needle\"\n\n**Total matches:** 1 in **1** files\n", //$NON-NLS-1$
            search("needle", "outputMode", "count", "metadataType", "documents")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void testUnreadableFileWarningsInEveryMode()
    {
        fixture.deleteOnDiskOnly("src/" + BETA); //$NON-NLS-1$

        assertEquals("## Search Count for \"needle\"\n\n**Total matches:** 2 in **2** files\n" //$NON-NLS-1$
            + "**Warning:** 1 file(s) could not be read\n", //$NON-NLS-1$
            search("needle", "outputMode", "count")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Files for \"absent\"\n\n**Total matches:** 0 in **0** files\n\n" //$NON-NLS-1$
            + "**Warning:** 1 file(s) could not be read\n\nNo matches found.\n", //$NON-NLS-1$
            search("absent", "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("## Search Results for \"absent\"\n\n**Total:** 0 matches in 0 files\n" //$NON-NLS-1$
            + "**Warning:** 1 file(s) could not be read (check EDT Error Log)\n\nNo matches found.\n", //$NON-NLS-1$
            search("absent")); //$NON-NLS-1$
    }
}
