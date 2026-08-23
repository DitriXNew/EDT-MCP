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
import java.util.List;
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

    // ============ the reasons the engine gives are bounded by the report's own limit ============
    //
    // The table CELL beside them was truncated at the limit from the start; the bullet list under
    // it was not, and it prints one line per addition PER SIDE. A comparison of an object with
    // plentiful dependencies extends the scope by hundreds of names on each of three sides, so a
    // report asked for one row answered with thousands of lines - the report's own limit undone
    // by the section that explains it.

    @Test
    public void testTheReasonsTheEngineGivesAreCutAtTheLimitLikeTheCellTheyExplain()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        for (int i = 0; i < 5; i++)
        {
            scope.extendScope("Catalog.Pulled" + i, "referenced by " + CATALOG_GOODS, //$NON-NLS-1$ //$NON-NLS-2$
                ComparisonSide.MAIN);
        }

        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(2, true);
        collector.accept(conflicting(91L, CATALOG_GOODS));
        String report = render(collector, scope);

        assertEquals("the limit bounds the bullets, so five additions may print two lines", 2, //$NON-NLS-1$
            countLinesStartingWith(report, "- `main` /")); //$NON-NLS-1$
    }

    /**
     * A list that simply stops reads as the whole of what the engine did, which is the same class
     * of untruth the truncated cell beside it avoids by naming its own count.
     */
    @Test
    public void testACutReasonListSaysThatItWasCut()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        for (int i = 0; i < 5; i++)
        {
            scope.extendScope("Catalog.Pulled" + i, "referenced by " + CATALOG_GOODS, //$NON-NLS-1$ //$NON-NLS-2$
                ComparisonSide.MAIN);
        }

        String report = render(new ComparisonTreeReport.Collector(2, true), scope);

        assertContains(report, "Why the engine added a qualified name of its own (showing 2 of 5)"); //$NON-NLS-1$
    }

    /**
     * The count is over every side, because the bullets are: a per-side count would say "showing
     * 2 of 2" of a list that left four names out.
     */
    @Test
    public void testTheReasonCountCoversEverySideTheEngineExtended()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        scope.extendScope("Catalog.PulledA", "referenced by " + CATALOG_GOODS, //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.MAIN);
        scope.extendScope("Catalog.PulledB", "referenced by " + CATALOG_GOODS, //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.MAIN);
        scope.extendScope("Catalog.PulledC", "referenced by " + CATALOG_GOODS, //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.OTHER);

        String report = render(new ComparisonTreeReport.Collector(1, true), scope);

        assertContains(report, "(showing 2 of 3)"); //$NON-NLS-1$
    }

    /**
     * A list that fits is not a truncated one, and saying "showing 2 of 2" of a whole list teaches
     * the caller to raise a limit that is not binding.
     */
    @Test
    public void testAReasonListThatFitsCarriesNoTruncationNotice()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        scope.extendScope(CATALOG_WAREHOUSES, "referenced by " + CATALOG_GOODS, //$NON-NLS-1$
            ComparisonSide.MAIN);

        String report = render(new ComparisonTreeReport.Collector(50, true), scope);

        assertContains(report, "Why the engine added a qualified name of its own:"); //$NON-NLS-1$
    }

    // The outer limit bounds how many ADDED NAMES are explained; it says nothing about how many
    // reasons ONE of them carries. The engine records an addition once with a reason per requested
    // object that pulled it in, so a common dependency of a large request - one module referenced
    // by a thousand requested objects - is a single bullet a thousand reasons long, and the
    // report's own limit is undone one level further in than the loop that was fixed above.

    @Test
    public void testTheReasonsForOneAddedNameAreCutAtTheLimitToo()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        for (int i = 0; i < 5; i++)
        {
            scope.extendScope(CATALOG_WAREHOUSES, "referenced by Catalog.Asking" + i, //$NON-NLS-1$
                ComparisonSide.MAIN);
        }

        String report = render(new ComparisonTreeReport.Collector(2, true), scope);

        assertContains(report, "referenced by Catalog.Asking0; referenced by Catalog.Asking1"); //$NON-NLS-1$
        assertFalse("the third reason is past the limit and may not be printed: " + report, //$NON-NLS-1$
            report.contains("Catalog.Asking2")); //$NON-NLS-1$
    }

    /**
     * And the cut is NAMED, for the same reason the list around it names its own: a line that
     * simply stops reads as the whole of why the engine pulled the name in.
     */
    @Test
    public void testACutListOfReasonsSaysThatItWasCut()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        for (int i = 0; i < 5; i++)
        {
            scope.extendScope(CATALOG_WAREHOUSES, "referenced by Catalog.Asking" + i, //$NON-NLS-1$
                ComparisonSide.MAIN);
        }

        String report = render(new ComparisonTreeReport.Collector(2, true), scope);

        // One added name, so the notice on the heading above would read "showing 1 of 1" and is
        // therefore absent: this count can only be the reasons'.
        assertContains(report, "(showing 2 of 5)"); //$NON-NLS-1$
    }

    /**
     * A list of reasons that fits is not a truncated one. Without this, "cut it" could be
     * satisfied by a notice printed unconditionally, which teaches the caller to raise a limit
     * that is not binding.
     */
    @Test
    public void testAListOfReasonsThatFitsCarriesNoTruncationNotice()
    {
        ComparisonScope scope = new ComparisonScope(Collections.singletonList(CATALOG_GOODS),
            Collections.singletonList(CATALOG_GOODS), Collections.singletonList(CATALOG_GOODS));
        scope.extendScope(CATALOG_WAREHOUSES, "referenced by Catalog.AskingA", //$NON-NLS-1$
            ComparisonSide.MAIN);
        scope.extendScope(CATALOG_WAREHOUSES, "referenced by Catalog.AskingB", //$NON-NLS-1$
            ComparisonSide.MAIN);

        String report = render(new ComparisonTreeReport.Collector(50, true), scope);

        assertContains(report, "referenced by Catalog.AskingA; referenced by Catalog.AskingB"); //$NON-NLS-1$
        assertFalse("nothing was left out, so nothing may claim it was: " + report, //$NON-NLS-1$
            report.contains("(showing")); //$NON-NLS-1$
    }

    /**
     * @param report the rendered report
     * @param prefix the line prefix to count
     * @return how many lines start with it
     */
    private static int countLinesStartingWith(String report, String prefix)
    {
        int found = 0;
        for (String line : report.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith(prefix))
            {
                found++;
            }
        }
        return found;
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

    // === a scoped run compared content only inside the scope, and the report says so ===
    //
    // compare_configurations turns the platform's mergeObjectsContent setting on for a scoped
    // run, and MdCompareUtils.isExcludeObjectsContentFeature then excludes an object's own
    // features whenever that object is not under a scope entry. Such a node is still matched -
    // added and deleted are still reported - but it lands in the table as 'identical', which
    // means "compared, and equal" in every other row. The report cannot leave that unsaid.

    @Test
    public void testAScopedReportSaysContentWasComparedInsideTheScopeOnly()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(101L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, requestedScope("Catalog.Products")); //$NON-NLS-1$

        assertContains(report, "Content was compared INSIDE THE SCOPE ONLY"); //$NON-NLS-1$
        assertContains(report, "identical"); //$NON-NLS-1$
    }

    @Test
    public void testTheScopedCaveatDoesNotSayANodeWasNeverComparedFeatureByFeature()
    {
        // The exclusion is per FEATURE and spares a containment-many collection of MdObjects, so
        // a node outside the scope WAS compared - on everything the predicate left in. What the
        // caveat withdraws from `identical` is narrower than the row.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(104L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, requestedScope("Catalog.Products")); //$NON-NLS-1$

        assertFalse("the caveat may not deny a comparison that did take place: " + report, //$NON-NLS-1$
            report.contains("never compared feature by feature"));  //$NON-NLS-1$
    }

    @Test
    public void testTheScopedCaveatNamesTheCarveOutItIsBoundedBy()
    {
        // The positive half of the pin above: the caveat has to say WHICH features can be
        // excluded, or the narrower claim is indistinguishable from having simply dropped a
        // sentence.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(105L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, requestedScope("Catalog.Products")); //$NON-NLS-1$

        assertContains(report, "sparing an object's containment-many collections of metadata objects"); //$NON-NLS-1$
    }

    @Test
    public void testAWholeConfigurationReportCarriesNoContentCaveat()
    {
        // The setting is OFF for a whole-configuration run, so content WAS compared everywhere;
        // printing the caveat here would describe a limit that was not applied.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(102L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertFalse("nothing was excluded from a global comparison: " + report, //$NON-NLS-1$
            report.contains("Content was compared")); //$NON-NLS-1$
    }

    @Test
    public void testAnEngineExtendedWholeConfigurationRunStillCarriesNoContentCaveat()
    {
        // The mutation this exists for: asking the FULL scope instead of the REQUESTED one. The
        // platform settles "is this global?" in the session constructor, before anything can be
        // extended, so a run launched with no scope stays a global run for its whole life - but
        // getScope() grows the moment the engine pulls a dependency in, and a report that read
        // that would start describing a limit the launch never applied.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(103L, "Catalog.Products")); //$NON-NLS-1$
        ComparisonScope scope = emptyScope();
        scope.extendScope("Catalog.PulledIn", "referenced by a compared object", //$NON-NLS-1$ //$NON-NLS-2$
            ComparisonSide.MAIN);

        String report = render(collector, scope);

        assertFalse("an extension by the engine does not turn a global run into a scoped one: " //$NON-NLS-1$
            + report, report.contains("Content was compared")); //$NON-NLS-1$
    }

    @Test
    public void testTheContentCaveatFollowsTheSessionsAnswerNotTheScopeObject()
    {
        // The two are made to DISAGREE on purpose: a scope object that looks scoped, over a run
        // the session recorded as global. The report has to follow the session - it is describing
        // the setting the launch chose - and re-deriving from the object here would print a limit
        // that was never applied.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(104L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, requestedScope("Catalog.Products"), true); //$NON-NLS-1$

        assertFalse("the session called this run global, so nothing was excluded: " + report, //$NON-NLS-1$
            report.contains("Content was compared")); //$NON-NLS-1$
    }

    @Test
    public void testTheContentCaveatIsPrintedWhenTheSessionCalledTheRunScoped()
    {
        // The mirror, and the half that a report reading the scope object would lose entirely: the
        // object can end up empty - the platform's own ComparisonScope(String) form builds one -
        // while the session settled on a scoped run. The caveat belongs to the run.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, false);
        collector.accept(identical(105L, "Catalog.Products")); //$NON-NLS-1$

        String report = render(collector, emptyScope(), false);

        assertContains(report, "Content was compared INSIDE THE SCOPE ONLY"); //$NON-NLS-1$
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
        // The launch settles "is this global?" from the scope as it stood BEFORE the run, which is
        // the input scope: that is what the production path hands the session and what the session
        // then remembers. Passed explicitly here for the same reason the header carries it - the
        // report must not re-derive it.
        return render(collector, scope, isGlobalInput(scope));
    }

    private static String render(ComparisonTreeReport.Collector collector, ComparisonScope scope,
        boolean globalScope)
    {
        return ComparisonTreeReport.render(
            new ComparisonTreeReport.Header("cmp-1", "TestConfiguration", "origin/main", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "v1.0", "finished", globalScope), //$NON-NLS-1$ //$NON-NLS-2$
            scope, collector);
    }

    /** @return what the session would have computed at launch, from the scope it was handed */
    private static boolean isGlobalInput(ComparisonScope scope)
    {
        for (ComparisonSide side : ComparisonSide.values())
        {
            List<String> requested = scope.getInputScope(side);
            if (requested != null && !requested.isEmpty())
            {
                return false;
            }
        }
        return true;
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
