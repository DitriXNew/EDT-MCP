/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ResourceTextSearch}, the one line scanner behind {@code search_in_code} and
 * {@code search_in_dcs}, on a real workspace project (the walk is a resource visitor) and with the
 * line-reader seam where a test needs an unreadable file.
 */
public class ResourceTextSearchTest
{
    private SearchFixtureProject fixture;

    @Before
    public void setUp() throws CoreException
    {
        fixture = SearchFixtureProject.create();
        fixture.file("src/CommonModules/Alpha/Module.bsl", "first\nNEEDLE in bsl\nthe end\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/Reports/Stock/Templates/Main/Template.dcs", //$NON-NLS-1$
            "<a>\n<query>SELECT NEEDLE</query>\n</a>\n"); //$NON-NLS-1$
        fixture.file("src/Reports/Stock/Templates/Main/Settings.dcss", "NEEDLE in dcss\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/CommonTemplates/Analytics/Template.dcs", "needle\nneedle needle\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws CoreException
    {
        Thread.interrupted();
        fixture.close();
    }

    private ResourceTextSearch scan(Pattern pattern, String extension, String fileMask, String folderPrefix,
        int limit, int contextLines, boolean details, ResourceTextSearch.LineReader reader) throws CoreException
    {
        ResourceTextSearch search = reader == null
            ? new ResourceTextSearch(pattern, extension, fileMask, folderPrefix, limit, contextLines, details)
            : new ResourceTextSearch(pattern, extension, fileMask, folderPrefix, limit, contextLines, details,
                reader);
        fixture.project().findMember("src").accept(search); //$NON-NLS-1$
        return search;
    }

    private static Pattern literal(String text, boolean caseSensitive)
    {
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (!caseSensitive)
        {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(Pattern.quote(text), flags);
    }

    @Test
    public void testTheDcsScanSeesOnlyDcsFiles() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", false), ".dcs", null, null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("only the two .dcs files - not the .bsl module and not the .dcss settings", //$NON-NLS-1$
            Arrays.asList("CommonTemplates/Analytics/Template.dcs", "Reports/Stock/Templates/Main/Template.dcs"), //$NON-NLS-1$ //$NON-NLS-2$
            List.copyOf(search.getMatchCountByFile().keySet()));
        assertEquals(3, search.getTotalMatches());
        assertTrue(search.isComplete());
    }

    @Test
    public void testTheBslScanSeesOnlyBslFiles() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", false), ".bsl", null, null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("CommonModules/Alpha/Module.bsl"), //$NON-NLS-1$
            List.copyOf(search.getMatchCountByFile().keySet()));
        assertEquals(1, search.getTotalMatches());
    }

    @Test
    public void testDisplayPathsAndTheFileMaskDropTheSrcPrefix() throws CoreException
    {
        ResourceTextSearch masked = scan(literal("needle", false), ".dcs", "reports/STOCK", null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the mask is a case-insensitive substring of the path without src/", //$NON-NLS-1$
            Arrays.asList("Reports/Stock/Templates/Main/Template.dcs"), //$NON-NLS-1$
            List.copyOf(masked.getMatchCountByFile().keySet()));

        ResourceTextSearch withSrc = scan(literal("needle", false), ".dcs", "src/Reports", null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the mask never sees the src/ prefix", 0, withSrc.getTotalMatchedFiles()); //$NON-NLS-1$
    }

    @Test
    public void testTheFolderPrefixScopesTheWalk() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", false), ".dcs", null, "CommonTemplates/", 100, 0, true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null);

        assertEquals(Arrays.asList("CommonTemplates/Analytics/Template.dcs"), //$NON-NLS-1$
            List.copyOf(search.getMatchCountByFile().keySet()));
    }

    @Test
    public void testCaseSensitivityDecidesTheMatch() throws CoreException
    {
        assertEquals(1, scan(literal("NEEDLE", true), ".dcs", null, null, 100, 0, true, null).getTotalMatches()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, scan(literal("NEEDLE", false), ".dcs", null, null, 100, 0, true, null).getTotalMatches()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testARegexMatchesWhatTheLiteralDoesNot() throws CoreException
    {
        Pattern regex = Pattern.compile("SELECT\\s+N\\w+", Pattern.UNICODE_CHARACTER_CLASS); //$NON-NLS-1$
        assertEquals(1, scan(regex, ".dcs", null, null, 100, 0, true, null).getTotalMatches()); //$NON-NLS-1$
        assertEquals(0, scan(literal("SELECT\\s+N\\w+", true), ".dcs", null, null, 100, 0, true, null) //$NON-NLS-1$ //$NON-NLS-2$
            .getTotalMatches());
    }

    @Test
    public void testALineWithTwoOccurrencesCountsOnce() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", true), ".dcs", "CommonTemplates", null, 100, 0, true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null);

        assertEquals(Integer.valueOf(2), search.getMatchCountByFile().get("CommonTemplates/Analytics/Template.dcs")); //$NON-NLS-1$
    }

    @Test
    public void testTheContextWindowIsClippedAtBothEndsOfTheFile() throws CoreException
    {
        ResourceTextSearch search = scan(literal("e", false), ".bsl", null, null, 100, 5, true, null); //$NON-NLS-1$ //$NON-NLS-2$

        List<ResourceTextSearch.Match> matches = search.getMatchesByFile().get("CommonModules/Alpha/Module.bsl"); //$NON-NLS-1$
        assertEquals("line 1 'first' has no 'e'; lines 2 and 3 do", 2, matches.size()); //$NON-NLS-1$
        List<String> window = Arrays.asList("1: first", "2: NEEDLE in bsl", "3: the end"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, matches.get(0).getLineNumber());
        assertEquals(window, matches.get(0).getContextLines());
        assertEquals(3, matches.get(1).getLineNumber());
        assertEquals("the window stops at the last line, it does not run past it", window, //$NON-NLS-1$
            matches.get(1).getContextLines());
    }

    @Test
    public void testTheLimitCapsDetailsButNeverTheCounts() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", false), ".dcs", null, null, 1, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("every matched line is counted past the limit", 3, search.getTotalMatches()); //$NON-NLS-1$
        assertEquals(2, search.getTotalMatchedFiles());
        assertEquals(1, search.getShownMatches());
        int detailed = search.getMatchesByFile().values().stream().mapToInt(List::size).sum();
        assertEquals(1, detailed);
        assertEquals(Integer.valueOf(1), search.getMatchCountByFile().get("Reports/Stock/Templates/Main/Template.dcs")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), search.getMatchCountByFile().get("CommonTemplates/Analytics/Template.dcs")); //$NON-NLS-1$
    }

    @Test
    public void testWithoutDetailsOnlyTheCountsAreKept() throws CoreException
    {
        ResourceTextSearch search = scan(literal("needle", false), ".dcs", null, null, 100, 2, false, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(search.getMatchesByFile().isEmpty());
        assertEquals(0, search.getShownMatches());
        assertEquals(3, search.getTotalMatches());
        assertEquals(2, search.getMatchCountByFile().size());
    }

    @Test
    public void testAnUnreadableFileIsSkippedCountedAndMakesTheScanIncomplete() throws CoreException
    {
        ResourceTextSearch.LineReader reader = file -> {
            if (file.getProjectRelativePath().toString().startsWith("src/CommonTemplates/")) //$NON-NLS-1$
            {
                throw new IllegalStateException("unreadable for the test"); //$NON-NLS-1$
            }
            return BslModuleUtils.readFileLines(file);
        };
        ResourceTextSearch search = scan(literal("needle", false), ".dcs", null, null, 100, 0, true, reader); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, search.getSkippedFiles());
        assertFalse(search.isComplete());
        assertFalse(search.wasInterrupted());
        Map<String, Integer> counts = search.getMatchCountByFile();
        assertEquals("the other file is still scanned", //$NON-NLS-1$
            Arrays.asList("Reports/Stock/Templates/Main/Template.dcs"), List.copyOf(counts.keySet())); //$NON-NLS-1$
    }

    @Test
    public void testAnInterruptedThreadStopsTheWalkAndMakesTheScanIncomplete() throws CoreException
    {
        Thread.currentThread().interrupt();
        ResourceTextSearch search;
        try
        {
            search = scan(literal("needle", false), ".dcs", null, null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Thread.interrupted();
        }

        assertTrue(search.wasInterrupted());
        assertFalse(search.isComplete());
        assertEquals("the walk stops before reading any file", 0, search.getTotalMatches()); //$NON-NLS-1$
        assertEquals(0, search.getSkippedFiles());
    }

    @Test
    public void testACompleteScanIsComplete() throws CoreException
    {
        ResourceTextSearch search = scan(literal("absent-text", false), ".dcs", null, null, 100, 0, true, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(search.isComplete());
        assertEquals(0, search.getTotalMatches());
    }

    @Test
    public void testFileMaskIgnoresCaseIndependentlyOfTheDefaultLocale() throws CoreException
    {
        // Turkish lower-cases 'I' to a dotless i: a default-locale comparison would drop this file.
        fixture.file("src/InformationRegisters/Prices/Templates/Main/Template.dcs", "needle\n"); //$NON-NLS-1$ //$NON-NLS-2$
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); //$NON-NLS-1$
        try
        {
            ResourceTextSearch search = scan(literal("needle", false), ".dcs", "informationregisters", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                100, 0, true, null);
            assertEquals(1, search.getTotalMatchedFiles());
        }
        finally
        {
            java.util.Locale.setDefault(previous);
        }
    }
}
