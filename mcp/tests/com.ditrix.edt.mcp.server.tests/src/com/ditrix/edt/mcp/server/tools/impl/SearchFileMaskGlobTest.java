/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ResourceTextSearch;
import com.ditrix.edt.mcp.server.utils.SearchFixtureProject;

/**
 * {@code fileMask} of {@code search_in_code} and {@code search_in_dcs} through the tools on a real
 * workspace project (issue #956): a glob with {@code *}/{@code ?}/{@code **} selects modules
 * instead of silently selecting none, a mask without them stays a substring, and a mask that
 * selects no file says so instead of a bare zero.
 */
public class SearchFileMaskGlobTest
{
    private static final String ALPHA = "CommonModules/Alpha/Module.bsl"; //$NON-NLS-1$
    private static final String BETA = "CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$
    private static final String MANAGER = "Catalogs/Товары/ManagerModule.bsl"; //$NON-NLS-1$

    private static final String NO_FILE_WARNING = "**Warning:** fileMask `*Gamma*` matched none of the 3 file " //$NON-NLS-1$
        + "paths, so nothing was searched (paths look like `Catalogs/Товары/ManagerModule.bsl`)"; //$NON-NLS-1$

    private SearchFixtureProject fixture;

    @Before
    public void setUp() throws CoreException
    {
        fixture = SearchFixtureProject.create();
        fixture.file("src/" + ALPHA, "Needle\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/" + BETA, "Needle\nneedle\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/" + MANAGER, "x\nneedle\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/Reports/Stock/Templates/Main/Template.dcs", "<needle/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/CommonTemplates/Analytics/Template.dcs", "<needle/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws CoreException
    {
        fixture.close();
    }

    private Map<String, String> params(String fileMask, String... more)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", fixture.name()); //$NON-NLS-1$
        params.put("query", "needle"); //$NON-NLS-1$ //$NON-NLS-2$
        if (fileMask != null)
        {
            params.put("fileMask", fileMask); //$NON-NLS-1$
        }
        for (int i = 0; i < more.length; i += 2)
        {
            params.put(more[i], more[i + 1]);
        }
        return params;
    }

    private String code(String fileMask, String... more)
    {
        return new SearchInCodeTool().execute(params(fileMask, more));
    }

    private String count(String fileMask)
    {
        return code(fileMask, "outputMode", "count"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String countText(int matches, int files)
    {
        return "## Search Count for \"needle\"\n\n**Total matches:** " + matches + " in **" + files //$NON-NLS-1$ //$NON-NLS-2$
            + "** files\n"; //$NON-NLS-1$
    }

    // ---- Glob masks select modules ----

    @Test
    public void testAStarNameStarGlobSelectsTheModuleFolder()
    {
        assertEquals(countText(2, 1), count("*МойМодуль*")); //$NON-NLS-1$
        assertEquals("case-insensitive", countText(2, 1), count("*мой*")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testADoubleStarPathGlobSelectsTheExactModule()
    {
        assertEquals(countText(1, 1), count("**/Товары/ManagerModule.bsl")); //$NON-NLS-1$
        assertEquals("the file name is part of the glob", countText(0, 0), //$NON-NLS-1$
            count("**/Товары/ObjectModule.bsl").substring(0, countText(0, 0).length())); //$NON-NLS-1$
    }

    @Test
    public void testAFolderGlobSelectsEverythingUnderIt()
    {
        assertEquals(countText(3, 2), count("CommonModules/*")); //$NON-NLS-1$
        assertEquals(countText(1, 1), count("Catalogs/Товар?")); //$NON-NLS-1$
    }

    @Test
    public void testFullModeListsTheGlobSelectedFileOnly()
    {
        String text = code("*Alpha*"); //$NON-NLS-1$
        assertTrue(text, text.contains("### " + ALPHA)); //$NON-NLS-1$
        assertFalse(text, text.contains(BETA));
        assertTrue(text, text.contains("**Total:** 1 matches in 1 files")); //$NON-NLS-1$
    }

    // ---- Substring masks are unchanged ----

    @Test
    public void testASubstringMaskIsUnchanged()
    {
        assertEquals(countText(3, 2), count("commonmodules")); //$NON-NLS-1$
        assertEquals(countText(1, 1), count("Alpha/Mod")); //$NON-NLS-1$
    }

    @Test
    public void testABackslashSubstringMaskIsReadAsAPath()
    {
        assertEquals(countText(1, 1), count("CommonModules\\Alpha")); //$NON-NLS-1$
    }

    // ---- A mask that selects no file says so ----

    @Test
    public void testCountModeWarnsWhenTheMaskSelectsNoFile()
    {
        assertEquals(countText(0, 0) + NO_FILE_WARNING + "\n", count("*Gamma*")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFullModeWarnsWhenTheMaskSelectsNoFile()
    {
        assertEquals("## Search Results for \"needle\"\n\n**Total:** 0 matches in 0 files\n" //$NON-NLS-1$
            + NO_FILE_WARNING + "\n\nNo matches found.\n", code("*Gamma*")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLinesModeWarnsWhenTheMaskSelectsNoFile()
    {
        assertEquals("## Search Lines for \"needle\"\n\n**Total:** 0 matches in 0 files\n" //$NON-NLS-1$
            + NO_FILE_WARNING + "\n\nNo matches found.\n", //$NON-NLS-1$
            code("*Gamma*", "outputMode", "lines")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testFilesModeWarnsWhenTheMaskSelectsNoFile()
    {
        assertEquals("## Search Files for \"needle\"\n\n**Total matches:** 0 in **0** files\n\n" //$NON-NLS-1$
            + NO_FILE_WARNING + "\n\nNo matches found.\n", //$NON-NLS-1$
            code("*Gamma*", "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testASubstringMaskThatSelectsNoFileWarnsToo()
    {
        assertTrue(count("Gamma").contains("fileMask `Gamma` matched none of the 3 file paths")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoWarningWhenTheMaskSelectedAFileWithoutAMatch()
    {
        String text = code("Alpha", "query", "zzz"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(text, text.contains("No matches found.")); //$NON-NLS-1$
        assertFalse(text, text.contains("fileMask")); //$NON-NLS-1$
    }

    @Test
    public void testNoWarningWhenOnlyTheMetadataTypeExcludesTheSelectedFile()
    {
        String text = code("Alpha", "metadataType", "documents"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(text, text.contains("**Total:** 0 matches in 0 files")); //$NON-NLS-1$
        assertFalse("the mask did select a module; the type filter is another matter", //$NON-NLS-1$
            text.contains("fileMask")); //$NON-NLS-1$
    }

    @Test
    public void testNoWarningWithoutAMask()
    {
        assertFalse(code(null, "query", "zzz").contains("fileMask")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- search_in_dcs shares the mask ----

    @Test
    public void testDcsGlobSelectsTheTemplate()
    {
        String text = new SearchInDcsTool().execute(params("*Stock*", "outputMode", "files")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(text, text.contains("**Total matches:** 1 in **1** files")); //$NON-NLS-1$
        assertTrue(text, text.contains("Reports/Stock/Templates/Main/Template.dcs")); //$NON-NLS-1$
        assertFalse(text, text.contains("CommonTemplates")); //$NON-NLS-1$
    }

    @Test
    public void testDcsWarnsWhenTheMaskSelectsNoFile()
    {
        String text = new SearchInDcsTool().execute(params("Reports/*/Forms/**")); //$NON-NLS-1$
        assertTrue(text, text.contains("**Warning:** fileMask `Reports/*/Forms/**` matched none of the 2 file " //$NON-NLS-1$
            + "paths, so nothing was searched (paths look like `CommonTemplates/Analytics/Template.dcs`)\n")); //$NON-NLS-1$
        assertTrue(text, text.contains("No matches found.")); //$NON-NLS-1$
    }

    @Test
    public void testAnInterruptedWalkDoesNotClaimTheMaskMatchedNothing() throws CoreException
    {
        ResourceTextSearch search = new ResourceTextSearch(Pattern.compile("needle"), ".bsl", "*Gamma*", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            100, 0, false);
        search.visit(fixture.project().findMember("src/" + MANAGER)); //$NON-NLS-1$
        assertTrue("one file met and rejected by the mask", search.fileMaskMatchedNoFile()); //$NON-NLS-1$
        Thread.currentThread().interrupt();
        try
        {
            search.visit(fixture.project().findMember("src/CommonModules")); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
        assertTrue(search.wasInterrupted());
        assertFalse("the walk stopped before it could meet a file the mask selects", //$NON-NLS-1$
            search.fileMaskMatchedNoFile());
    }
}