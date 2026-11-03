/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SearchResultFormatter}: what a per-file address and the fail-closed empty result
 * add to the shared rendering, and that {@link SearchResultFormatter#forCode()} adds neither. The
 * byte-for-byte {@code search_in_code} contract itself is pinned through the tool, in
 * {@code SearchInCodeRenderingPinTest}.
 */
public class SearchResultFormatterTest
{
    private static final String ALPHA = "CommonModules/Alpha/Module.bsl"; //$NON-NLS-1$
    private static final String BETA = "CommonModules/Beta/Module.bsl"; //$NON-NLS-1$
    private static final Function<String, String> ADDRESS = path -> ALPHA.equals(path) ? "Addr.Alpha" : null; //$NON-NLS-1$

    private SearchFixtureProject fixture;

    @Before
    public void setUp() throws CoreException
    {
        fixture = SearchFixtureProject.create();
        fixture.file("src/" + ALPHA, "x\nNeedle one\ny\n"); //$NON-NLS-1$ //$NON-NLS-2$
        fixture.file("src/" + BETA, "needle\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void tearDown() throws CoreException
    {
        Thread.interrupted();
        fixture.close();
    }

    private ResourceTextSearch scan(String query, boolean details, ResourceTextSearch.LineReader reader)
        throws CoreException
    {
        Pattern pattern = Pattern.compile(Pattern.quote(query),
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        ResourceTextSearch search = new ResourceTextSearch(pattern, ".bsl", null, null, 100, 1, details, reader); //$NON-NLS-1$
        fixture.project().findMember("src").accept(search); //$NON-NLS-1$
        return search;
    }

    private ResourceTextSearch scan(String query, boolean details) throws CoreException
    {
        return scan(query, details, BslModuleUtils::readFileLines);
    }

    /** Beta cannot be read. */
    private ResourceTextSearch scanWithBetaUnreadable(String query, boolean details) throws CoreException
    {
        return scan(query, details, file -> {
            if (file.getProjectRelativePath().toString().endsWith(BETA))
            {
                throw new IllegalStateException("unreadable for the test"); //$NON-NLS-1$
            }
            return BslModuleUtils.readFileLines(file);
        });
    }

    @Test
    public void testTheAddressIsPrintedUnderItsFileHeadingAndTheFenceIsTheGivenLanguage() throws CoreException
    {
        String text = SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
            .format(SearchResultFormatter.MODE_FULL, "needle", scan("needle", true)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("## Search Results for \"needle\"\n\n" //$NON-NLS-1$
            + "**Total:** 2 matches in 2 files\n\n" //$NON-NLS-1$
            + "### CommonModules/Alpha/Module.bsl\n\n" //$NON-NLS-1$
            + "**Template:** `Addr.Alpha`\n\n" //$NON-NLS-1$
            + "**Line 2:**\n```xml\n1: x\n2: Needle one\n3: y\n```\n\n" //$NON-NLS-1$
            + "### CommonModules/Beta/Module.bsl\n\n" //$NON-NLS-1$
            + "**Line 1:**\n```xml\n1: needle\n```\n\n", text); //$NON-NLS-1$
    }

    @Test
    public void testTheFilesTableGainsAnAddressColumnWithADashForAFileWithoutOne() throws CoreException
    {
        String text = SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
            .format(SearchResultFormatter.MODE_FILES, "needle", scan("needle", false)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("## Search Files for \"needle\"\n\n" //$NON-NLS-1$
            + "**Total matches:** 2 in **2** files\n\n" //$NON-NLS-1$
            + MarkdownUtils.tableHeader("File", "Template", "Matches") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + MarkdownUtils.tableRow(ALPHA, "Addr.Alpha", "1") //$NON-NLS-1$ //$NON-NLS-2$
            + MarkdownUtils.tableRow(BETA, "-", "1"), text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheCountModeIsTheSameWithOrWithoutAnAddress() throws CoreException
    {
        ResourceTextSearch search = scanWithBetaUnreadable("needle", false); //$NON-NLS-1$

        assertEquals(SearchResultFormatter.forCode().format(SearchResultFormatter.MODE_COUNT, "needle", search), //$NON-NLS-1$
            SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
                .format(SearchResultFormatter.MODE_COUNT, "needle", search)); //$NON-NLS-1$
    }

    @Test
    public void testAnIncompleteEmptyScanNeverClaimsNoMatchesWhenFailingClosed() throws CoreException
    {
        ResourceTextSearch search = scanWithBetaUnreadable("absent", true); //$NON-NLS-1$
        SearchResultFormatter dcs = SearchResultFormatter.withAddress("xml", "Template", ADDRESS); //$NON-NLS-1$ //$NON-NLS-2$

        String full = dcs.format(SearchResultFormatter.MODE_FULL, "absent", search); //$NON-NLS-1$
        assertEquals("## Search Results for \"absent\"\n\n" //$NON-NLS-1$
            + "**Total:** 0 matches in 0 files\n" //$NON-NLS-1$
            + "**Warning:** 1 file(s) could not be read (check EDT Error Log)\n\n" //$NON-NLS-1$
            + "No matches in the files that were read, but the scan is INCOMPLETE (1 file(s) could not be read), " //$NON-NLS-1$
            + "so this is not proof that the text is absent - retry the search.\n", full); //$NON-NLS-1$

        String files = dcs.format(SearchResultFormatter.MODE_FILES, "absent", scanWithBetaUnreadable("absent", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(files, files.contains("the scan is INCOMPLETE (1 file(s) could not be read)")); //$NON-NLS-1$
        assertFalse(files, files.contains(SearchResultFormatter.NO_MATCHES));
    }

    @Test
    public void testAnInterruptedEmptyScanNamesTheInterruptionWhenFailingClosed() throws CoreException
    {
        Thread.currentThread().interrupt();
        ResourceTextSearch search;
        try
        {
            search = scan("needle", true); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }

        String full = SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
            .format(SearchResultFormatter.MODE_FULL, "needle", search); //$NON-NLS-1$
        assertTrue(full, full.contains("**Warning:** Search was interrupted, results may be incomplete\n")); //$NON-NLS-1$
        assertTrue(full, full.contains("the scan is INCOMPLETE (the search was interrupted)")); //$NON-NLS-1$
        assertFalse(full, full.contains(SearchResultFormatter.NO_MATCHES));
    }

    @Test
    public void testACompleteEmptyScanPrintsTheSentinel() throws CoreException
    {
        String full = SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
            .format(SearchResultFormatter.MODE_FULL, "absent", scan("absent", true)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("## Search Results for \"absent\"\n\n**Total:** 0 matches in 0 files\n\n" //$NON-NLS-1$
            + SearchResultFormatter.NO_MATCHES, full);
    }

    @Test
    public void testTheCodeRenderingKeepsTheSentinelOnAnIncompleteScan() throws CoreException
    {
        // search_in_code's wire text is unchanged by the shared formatter: it never fails closed.
        String full = SearchResultFormatter.forCode()
            .format(SearchResultFormatter.MODE_FULL, "absent", scanWithBetaUnreadable("absent", true)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(full, full.endsWith("\n\n" + SearchResultFormatter.NO_MATCHES)); //$NON-NLS-1$
        assertFalse(full, full.contains("INCOMPLETE")); //$NON-NLS-1$
    }

    @Test
    public void testAnIncompleteScanWithMatchesShowsTheMatchesAndTheWarning() throws CoreException
    {
        String full = SearchResultFormatter.withAddress("xml", "Template", ADDRESS) //$NON-NLS-1$ //$NON-NLS-2$
            .format(SearchResultFormatter.MODE_FULL, "needle", scanWithBetaUnreadable("needle", true)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(full, full.contains("**Total:** 1 matches in 1 files\n" //$NON-NLS-1$
            + "**Warning:** 1 file(s) could not be read (check EDT Error Log)\n")); //$NON-NLS-1$
        assertTrue(full, full.contains("### CommonModules/Alpha/Module.bsl")); //$NON-NLS-1$
        assertFalse(full, full.contains("INCOMPLETE")); //$NON-NLS-1$
    }
}
