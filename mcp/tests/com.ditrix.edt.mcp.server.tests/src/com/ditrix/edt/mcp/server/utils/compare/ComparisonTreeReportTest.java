/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Locale;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

/**
 * Pins the rendered three-way report over a stubbed node set.
 *
 * <p>Three of these tests exist to fail on one specific mutation each, because a suite that only
 * checked "some text came out" would stay green on either side of the defect:</p>
 * <ul>
 * <li>{@link #testRequestedScopeIsNeverTheEngineExtendedScope()} fails if {@code getInputScope}
 * is swapped for {@code getScope} in the renderer - the two differ only once the engine has
 * extended the scope, which is exactly the case built here;</li>
 * <li>{@link #testAnUnfinishedNodeIsNeverRenderedAsAnAbsenceOfDifferences()} fails if an
 * unfinished node is decoded as identical or filtered away, which is how a lazily-compared
 * subtree turns into a false "nothing changed";</li>
 * <li>{@link #testAnEmptyTreeIsNeverReportedAsAnAbsenceOfDifferences()} fails if the empty-page
 * branch asks only whether something is still unfinished: with no node seen at all both
 * counters are zero, so that question turns an absence of data into a claim of equality.</li>
 * </ul>
 *
 * <p>The Cyrillic object name is written as escapes on purpose: one row exists to show that a
 * name is carried through the report VERBATIM, and a literal would make that assertion depend
 * on the file's encoding surviving the build (CLAUDE.md don't #7).</p>
 */
public class ComparisonTreeReportTest
{
    /** A Cyrillic 1C object name (Catalog + the Russian word for goods), escaped per don't #7. */
    private static final String CATALOG_GOODS =
        "Catalog.\u0422\u043E\u0432\u0430\u0440\u044B"; //$NON-NLS-1$

    private static final String CATALOG_WAREHOUSES = "Catalog.Warehouses"; //$NON-NLS-1$

    private static final String DOCUMENT_ORDER = "Document.Order"; //$NON-NLS-1$

    private static final String ABSENT_CELL = "—"; //$NON-NLS-1$

    @Test
    public void testConflictOneSidedAndUnfinishedMarkersAreRendered()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(conflicting(11L, CATALOG_GOODS));
        collector.accept(oneSided(12L, "Report.NewOne", ComparisonSide.OTHER, false)); //$NON-NLS-1$
        collector.accept(oneSided(13L, DOCUMENT_ORDER, ComparisonSide.MAIN, true));
        collector.accept(unfinished(14L, "CommonModule.Slow")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertContains(report, "| 11 |"); //$NON-NLS-1$
        assertContains(report, CATALOG_GOODS);
        assertContains(report, "CONFLICT (changed on both sides)"); //$NON-NLS-1$
        assertContains(report, "added on other"); //$NON-NLS-1$
        assertContains(report, "deleted on other"); //$NON-NLS-1$
        assertContains(report, "not compared yet"); //$NON-NLS-1$
        assertEquals(4, collector.getTotal());
        assertEquals(1, collector.getConflicts());
        assertEquals(1, collector.getNotCompared());
        // The unfinished node is NOT counted as a difference: it is not an answer at all.
        assertEquals(3, collector.getDiffering());
    }

    @Test
    public void testChangeRelativeToTheAncestorIsNamedPerSide()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(changed(21L, "Catalog.OnMain", true, false)); //$NON-NLS-1$
        collector.accept(changed(22L, "Catalog.OnOther", false, true)); //$NON-NLS-1$
        collector.accept(changed(23L, "Catalog.OnBoth", true, true)); //$NON-NLS-1$
        collector.accept(identical(24L, "Catalog.Same")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertContains(report, "changed on main"); //$NON-NLS-1$
        assertContains(report, "changed on other"); //$NON-NLS-1$
        assertContains(report, "changed on both sides"); //$NON-NLS-1$
        assertContains(report, "identical"); //$NON-NLS-1$
        assertEquals(3, collector.getDiffering());
        // Both sides changed is NOT a conflict unless the platform itself said so.
        assertEquals(0, collector.getConflicts());
    }

    @Test
    public void testRequestedScopeIsNeverTheEngineExtendedScope()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        scope.extendScope(CATALOG_WAREHOUSES, "referenced by " + CATALOG_GOODS, //$NON-NLS-1$
            ComparisonSide.MAIN);

        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(conflicting(31L, CATALOG_GOODS));
        String report = render(collector, scope);

        // Searched inside the Scope section only: the header table has a "main" row of its
        // own (which side the working tree is), and matching that one would test nothing.
        String scopeSection = section(report, "## Scope", "## Top objects"); //$NON-NLS-1$ //$NON-NLS-2$
        String mainRow = rowStartingWith(scopeSection, "| main |"); //$NON-NLS-1$
        assertContains(cell(mainRow, 2), CATALOG_GOODS);
        // THE mutation this pins: getScope() also carries the engine's own addition, so
        // rendering it as "Requested" would present an object the caller never named as one
        // the caller chose.
        assertFalse("the Requested column must not contain what the engine added: " + mainRow, //$NON-NLS-1$
            cell(mainRow, 2).contains(CATALOG_WAREHOUSES));
        assertContains(cell(mainRow, 3), CATALOG_WAREHOUSES);
        assertContains(report, "Why the engine added a qualified name of its own"); //$NON-NLS-1$
        assertContains(report, "referenced by " + CATALOG_GOODS); //$NON-NLS-1$

        // The engine extended only the main side, and the report says exactly that.
        String otherRow = rowStartingWith(scopeSection, "| other |"); //$NON-NLS-1$
        assertFalse("only the main side was extended: " + otherRow, //$NON-NLS-1$
            otherRow.contains(CATALOG_WAREHOUSES));
    }

    @Test
    public void testAnEmptyRequestedScopeIsReportedAsTheWholeConfiguration()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(conflicting(41L, CATALOG_GOODS));

        String report = render(collector, emptyScope());

        // An omitted scope is a whole-configuration comparison, not an empty one; the report
        // has to say which, because both would otherwise render as an empty cell.
        assertContains(report, "whole configuration (nothing requested)"); //$NON-NLS-1$
    }

    @Test
    public void testAnUnfinishedNodeIsNeverRenderedAsAnAbsenceOfDifferences()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(unfinished(51L, "CommonModule.Slow")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertContains(report, "not compared yet"); //$NON-NLS-1$
        assertFalse("an uncompared subtree must never be described as equal: " + report, //$NON-NLS-1$
            report.toLowerCase(Locale.ROOT).contains("no differences")); //$NON-NLS-1$
    }

    @Test
    public void testUnfinishedNodesSurviveTheChangedOnlyFilter()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(identical(61L, "Catalog.Same")); //$NON-NLS-1$
        collector.accept(unfinished(62L, "CommonModule.Slow")); //$NON-NLS-1$

        assertEquals(2, collector.getTotal());
        // Filtered out: exactly the identical one. Dropping the unfinished one would turn
        // "not answered yet" into "answered: equal".
        assertEquals(1, collector.getMatching());
        assertEquals(1, collector.getRows().size());
        assertEquals(62L, collector.getRows().get(0).getNodeId());
    }

    @Test
    public void testTruncationKeepsTheCountersWhole()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(2, true);
        for (int i = 0; i < 5; i++)
        {
            collector.accept(conflicting(70L + i, "Catalog.C" + i)); //$NON-NLS-1$
        }

        String report = render(collector, emptyScope());

        assertEquals(5, collector.getTotal());
        assertEquals(5, collector.getConflicts());
        assertEquals(5, collector.getMatching());
        assertEquals(2, collector.getRows().size());
        assertContains(report, "**Total:** 5 top nodes"); //$NON-NLS-1$
        assertContains(report, "5 conflicts"); //$NON-NLS-1$
        assertContains(report, "(showing 2 of 5)"); //$NON-NLS-1$
    }

    @Test
    public void testAnAbsentSideIsRenderedAsAMissingCellNotAnEmptyName()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(oneSided(81L, "Report.NewOne", ComparisonSide.OTHER, false)); //$NON-NLS-1$

        String report = render(collector, emptyScope());
        String row = rowStartingWith(report, "| 81 |"); //$NON-NLS-1$

        assertContains(row, "Report.NewOne"); //$NON-NLS-1$
        assertContains(row, ABSENT_CELL);
    }

    @Test
    public void testNothingToShowSaysSoOnlyWhenNothingIsStillRunning()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(identical(91L, "Catalog.Same")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        // With nothing unfinished, "no differences" IS the honest answer.
        assertContains(report, "found no differences"); //$NON-NLS-1$
        assertEquals(0, collector.getMatching());
    }

    @Test
    public void testAnEmptyTreeIsNeverReportedAsAnAbsenceOfDifferences()
    {
        // Nothing accepted at all: the tree was not built, or the session ended between the
        // poll and the read. Both counters are zero, exactly as they are when every top object
        // compared equal - so a report that only asks "is anything unfinished?" answers this
        // absence of data with a claim of equality.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, emptyScope());

        assertEquals(0, collector.getTotal());
        assertFalse("a tree that produced no node must never be described as equal: " + report, //$NON-NLS-1$
            report.toLowerCase(Locale.ROOT).contains("no differences")); //$NON-NLS-1$
        assertContains(report, "nothing was compared"); //$NON-NLS-1$
    }

    @Test
    public void testAScopeThatMatchedNothingIsNamedRatherThanReportedAsAgreement()
    {
        // ComparisonScopeBuilder validates only the LEADING type token, so this is a legal
        // scope that selects nothing. Telling the caller there are no differences in the
        // objects he named would be an answer about objects that were never compared.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, requestedScope("Catalog.NoSuchThing")); //$NON-NLS-1$

        assertFalse("a scope that matched nothing must never read as equality: " + report, //$NON-NLS-1$
            report.toLowerCase(Locale.ROOT).contains("no differences")); //$NON-NLS-1$
        assertContains(report, "The requested scope matched no object"); //$NON-NLS-1$
        assertContains(report, "Catalog.NoSuchThing"); //$NON-NLS-1$
    }

    @Test
    public void testAWholeConfigurationRunThatComparedNothingCarriesNoScopeAdvice()
    {
        // The same absence, but with no scope to blame: the advice about a mistyped name would
        // send the caller after a scope he never supplied.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, emptyScope());

        assertContains(report, "nothing was compared"); //$NON-NLS-1$
        assertFalse("nothing was requested, so there is no requested name to check: " + report, //$NON-NLS-1$
            report.contains("The requested scope matched no object")); //$NON-NLS-1$
    }

    // === fixtures ===

    private static ComparisonScope requestedScope(String symlink)
    {
        return new ComparisonScope(Collections.singletonList(symlink),
            Collections.singletonList(symlink), Collections.singletonList(symlink));
    }

    private static ComparisonScope emptyScope()
    {
        // Not ComparisonScope.EMPTY_SCOPE: that is a shared MUTABLE singleton, and one test
        // extending it would change what every other test reads.
        return new ComparisonScope(Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());
    }

    private static String render(ComparisonTreeReport.Collector collector, ComparisonScope scope)
    {
        return ComparisonTreeReport.render(
            new ComparisonTreeReport.Header("cmp-1", "TestConfiguration", "origin/main", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "v1.0", "finished"), //$NON-NLS-1$ //$NON-NLS-2$
            scope, collector);
    }

    private static TopComparisonNode conflicting(long id, String symlink)
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasDoubleChanges();
        flags.setHasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER);
        return node(id, symlink, symlink, symlink, flags, ComparisonNodeStatus.FINISHED);
    }

    private static TopComparisonNode changed(long id, String symlink, boolean onMain,
        boolean onOther)
    {
        ComparisonFlags flags = new ComparisonFlags();
        if (onMain)
        {
            flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        }
        if (onOther)
        {
            flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);
        }
        return node(id, symlink, symlink, symlink, flags, ComparisonNodeStatus.FINISHED);
    }

    private static TopComparisonNode identical(long id, String symlink)
    {
        return node(id, symlink, symlink, symlink, new ComparisonFlags(),
            ComparisonNodeStatus.FINISHED);
    }

    private static TopComparisonNode oneSided(long id, String symlink, ComparisonSide side,
        boolean ancestorExists)
    {
        ComparisonSide absent =
            side == ComparisonSide.MAIN ? ComparisonSide.OTHER : ComparisonSide.MAIN;
        ComparisonFlags flags = new ComparisonFlags();
        flags.setOnOneSide(side, absent);
        TopComparisonNode node = node(id, side == ComparisonSide.MAIN ? symlink : null,
            side == ComparisonSide.OTHER ? symlink : null, ancestorExists ? symlink : null, flags,
            ComparisonNodeStatus.FINISHED);
        when(node.isOneSideNode()).thenReturn(true);
        when(node.getNodeSide()).thenReturn(side);
        when(node.isAncestorObjectExists()).thenReturn(ancestorExists);
        return node;
    }

    private static TopComparisonNode unfinished(long id, String symlink)
    {
        // Deliberately given flags that say "equal": the STATUS has to win, or a lazy subtree
        // reads as a compared and identical one.
        return node(id, symlink, symlink, symlink, new ComparisonFlags(),
            ComparisonNodeStatus.HAS_UNFINISHED_CHILDREN);
    }

    private static TopComparisonNode node(long id, String main, String other, String ancestor,
        ComparisonFlags flags, ComparisonNodeStatus status)
    {
        TopComparisonNode node = mock(TopComparisonNode.class);
        when(node.bmGetId()).thenReturn(id);
        when(node.getMainSymlink()).thenReturn(main);
        when(node.getOtherSymlink()).thenReturn(other);
        when(node.getCommonAncestorSymlink()).thenReturn(ancestor);
        when(node.getComparisonFlags()).thenReturn(flags);
        when(node.getComparisonStatus()).thenReturn(status);
        return node;
    }

    // === assertions ===

    private static void assertContains(String haystack, String needle)
    {
        assertTrue("expected to find '" + needle + "' in:\n" + haystack, //$NON-NLS-1$ //$NON-NLS-2$
            haystack.contains(needle));
    }

    /**
     * @param report the rendered report
     * @param from the heading the section starts at
     * @param to the heading the next section starts at
     * @return the text between the two headings
     */
    private static String section(String report, String from, String to)
    {
        int start = report.indexOf(from);
        assertTrue("no section '" + from + "' in:\n" + report, start >= 0); //$NON-NLS-1$ //$NON-NLS-2$
        int end = report.indexOf(to, start);
        return end < 0 ? report.substring(start) : report.substring(start, end);
    }

    private static String rowStartingWith(String report, String prefix)
    {
        for (String line : report.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith(prefix))
            {
                return line;
            }
        }
        throw new AssertionError("no row starting with '" + prefix + "' in:\n" + report); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param row a rendered Markdown table row
     * @param index the 1-based cell index (a leading pipe makes cell 0 empty)
     * @return that cell's text
     */
    private static String cell(String row, int index)
    {
        String[] cells = row.split("\\|"); //$NON-NLS-1$
        assertTrue("row has no cell " + index + ": " + row, cells.length > index); //$NON-NLS-1$ //$NON-NLS-2$
        return cells[index];
    }
}
