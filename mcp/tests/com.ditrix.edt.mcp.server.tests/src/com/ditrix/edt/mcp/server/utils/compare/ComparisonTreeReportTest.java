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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** The engine's reason for a name it pulls in AFTER a reading has been taken. */
    private static final String LATE_NAME_REASON = "pulled in after the reading"; //$NON-NLS-1$

    /** And for one more reason under a name that reading already carried. */
    private static final String LATE_EXTRA_REASON = "a second reason, after the reading"; //$NON-NLS-1$

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

    /**
     * A node the engine returned NO verdict for is listed - dropping it would present a subtree
     * nobody judged as an equal one - and it is not one of the differences the comparison found.
     * <p>
     * One predicate used to answer both questions, so the row and the counter beside it disagreed:
     * the Change cell said the engine never reported on this node while the headline counted it
     * among the objects that differ.
     */
    @Test
    public void testANodeWithNoVerdictIsListedUnderChangedOnly()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(notReported(41L, "Catalog.Unjudged")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertContains(report, "| 41 |"); //$NON-NLS-1$
        assertContains(report, "not reported by the engine"); //$NON-NLS-1$
    }

    /** The other half, in its own test: listed is not counted. */
    @Test
    public void testANodeWithNoVerdictIsNotCountedAsADifference()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(notReported(41L, "Catalog.Unjudged")); //$NON-NLS-1$

        assertEquals(1, collector.getTotal());
        assertEquals(0, collector.getDiffering());
    }

    /** And the headline says so in the row's own words, so the two documents cannot disagree. */
    @Test
    public void testTheHeadlineCountsTheUnjudgedNodeUnderItsOwnName()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(notReported(41L, "Catalog.Unjudged")); //$NON-NLS-1$

        String report = render(collector, emptyScope());

        assertContains(report, "0 with differences"); //$NON-NLS-1$
        assertContains(report, "1 not reported by the engine"); //$NON-NLS-1$
    }

    /**
     * The positive control for both: a node the engine DID judge is still counted, and the
     * unjudged counter stays at zero. Without it the two tests above are satisfied by a report
     * that counts nothing at all.
     */
    @Test
    public void testARealDifferenceIsStillCountedAsOne()
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(conflicting(42L, CATALOG_WAREHOUSES));

        String report = render(collector, emptyScope());

        assertEquals(1, collector.getDiffering());
        assertEquals(0, collector.getNotReported());
        assertContains(report, "1 with differences"); //$NON-NLS-1$
        assertContains(report, "0 not reported by the engine"); //$NON-NLS-1$
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
        // A name whose type token is real and whose object is not exists on none of the three
        // sides, so this is a legal scope that selects nothing. Telling the caller there are no
        // differences in the objects he named would be an answer about objects that were never
        // compared.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, requestedScope("Catalog.NoSuchThing")); //$NON-NLS-1$

        assertFalse("a scope that matched nothing must never read as equality: " + report, //$NON-NLS-1$
            report.toLowerCase(Locale.ROOT).contains("no differences")); //$NON-NLS-1$
        assertContains(report, "The requested scope matched no object"); //$NON-NLS-1$
        assertContains(report, "Catalog.NoSuchThing"); //$NON-NLS-1$
    }

    @Test
    public void testTheScopeAdviceSaysWhyTheNameIsLegalWithoutDescribingTheValidator()
    {
        // The reason the advice gives, pinned as the whole sentence. It is a statement about
        // NAMES - a qualified name can exist on none of the three sides - and it stays true
        // whatever the scope builder checks.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, requestedScope("Catalog.NoSuchThing")); //$NON-NLS-1$

        assertContains(report, "A qualified name that exists on none of the three sides is a " //$NON-NLS-1$
            + "legal scope that selects nothing"); //$NON-NLS-1$
    }

    @Test
    public void testTheScopeAdviceDoesNotClaimOnlyTheLeadingTypeTokenIsValidated()
    {
        // It used to, and the branch that added padding and empty-segment validation to
        // ComparisonScopeBuilder made it false without touching this file. Pinned as an absence,
        // in its own @Test: JUnit stops a method at its first failed assertion, so an absence
        // sharing a method with the positive pin above would only be reached while that held.
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);

        String report = render(collector, requestedScope("Catalog.NoSuchThing")); //$NON-NLS-1$

        assertFalse("more than the leading token is validated now: " + report, //$NON-NLS-1$
            report.contains("leading type token")); //$NON-NLS-1$
    }

    @Test
    public void testNothingInThisClassClaimsOnlyTheLeadingTypeTokenIsValidated()
    {
        // The twin. Correcting the sentence the reader SEES left the same sentence standing in
        // the class javadoc, where no rendered-text assertion can reach it - and that is how it
        // survived the round that removed it from the wire. So the claim is pinned absent from
        // the SOURCE of this one file, javadoc and comments included.
        //
        // The text is unwrapped before it is searched: the javadoc broke the phrase across two
        // lines as "LEADING\n * type token", which every contains() on the rendered report, and
        // any naive one on the file, walks straight past.
        String source =
            unwrapped(sourceOf("utils/compare/ComparisonTreeReport.java")).toLowerCase( //$NON-NLS-1$
                Locale.ROOT);

        assertFalse("this file may not say it, in prose any more than on the wire: " //$NON-NLS-1$
            + window(source, "leading type token"), source.contains("leading type token")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Shows WHERE a banned phrase was found, without emptying the file into the report.
     *
     * @param source the unwrapped source text
     * @param phrase the phrase looked for
     * @return the phrase with up to 120 characters of context on either side, or an empty string
     *         when it is absent - the message is built on every call, pass or fail, and the whole
     *         unwrapped {@code ComparisonTreeReport} is ~40 KB of it
     */
    private static String window(String source, String phrase)
    {
        int at = source.indexOf(phrase);
        if (at < 0)
        {
            return ""; //$NON-NLS-1$
        }
        int from = Math.max(0, at - 120);
        int to = Math.min(source.length(), at + phrase.length() + 120);
        return (from > 0 ? "..." : "") + source.substring(from, to) //$NON-NLS-1$ //$NON-NLS-2$
            + (to < source.length() ? "..." : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads one file of the bundle source tree, so a claim made in a comment can be pinned the
     * way a claim made on the wire is.
     *
     * @param relative the path below the bundle's package root
     * @return the file's text
     */
    private static String sourceOf(String relative)
    {
        String root = "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/"; //$NON-NLS-1$
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, root + relative);
            if (candidate.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(candidate.toPath()),
                        StandardCharsets.UTF_8);
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("could not locate '" + root + relative //$NON-NLS-1$
            + "' by walking up from user.dir=" + System.getProperty("user.dir")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param source Java source text
     * @return the same text with javadoc line prefixes dropped and every run of whitespace
     *         collapsed to one space, so a phrase broken across two lines is still one phrase
     */
    private static String unwrapped(String source)
    {
        return source.replace("\r", "") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("\\n\\s*\\*", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
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

    @Test
    public void testACopiedScopeDoesNotGrowWithTheEngine()
    {
        // The scope object the handle hands out is the one the engine keeps extending, and it
        // extends it IN PLACE: extendScope puts the name into the map getExtendedScope returns
        // and appends the reason to the list already under it. So the copy has to be deep enough
        // to cover BOTH, and this test makes the two fail separately - a new name kills a copy
        // that kept the platform's map, a second reason kills a copy that took the map but shared
        // its lists.
        ComparisonScope scope = requestedScope(CATALOG_GOODS);
        scope.extendScope(CATALOG_WAREHOUSES, "referenced by " + CATALOG_GOODS, //$NON-NLS-1$
            ComparisonSide.MAIN);

        ComparisonTreeReport.ScopeSnapshot snapshot =
            ComparisonTreeReport.ScopeSnapshot.copyOf(scope, 50);

        scope.extendScope(DOCUMENT_ORDER, LATE_NAME_REASON, ComparisonSide.MAIN);
        scope.extendScope(CATALOG_WAREHOUSES, LATE_EXTRA_REASON, ComparisonSide.MAIN);

        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(50, true);
        collector.accept(conflicting(61L, CATALOG_GOODS));
        String report = render(collector, snapshot, false);

        // What the reading DID carry is still there: without this the copy could be empty.
        assertContains(report, CATALOG_WAREHOUSES);
        assertContains(report, "referenced by " + CATALOG_GOODS); //$NON-NLS-1$
        assertFalse("a name added after the reading was taken is not part of it:\n" + report, //$NON-NLS-1$
            report.contains(DOCUMENT_ORDER));
        assertFalse("nor is a reason added after it:\n" + report, //$NON-NLS-1$
            report.contains(LATE_EXTRA_REASON));
    }

    // === fixtures ===

    private static ComparisonScope requestedScope(String symlink)
    {
        return new ComparisonScope(Collections.singletonList(symlink),
            Collections.singletonList(symlink), Collections.singletonList(symlink));
    }

    // ====== a bounded report may not do unbounded work to produce its bounded prefix ======
    //
    // The Scope section prints at most 'limit' added names per side, in a table cell and again as
    // bullets - and it used to obtain that prefix by copying the WHOLE map into a TreeMap, once
    // for each of the two, on each of the three sides. Six full orderings, O(n) live entries each,
    // with the VALUES carried along (an addition holds one reason per requested object that pulled
    // it in), all while the comparison read is still held. limit=1 paid every bit of it to print
    // three lines.
    //
    // The pins below COUNT, and never time anything: how many keys the reading takes out of the
    // map, how many values, and how many of each it KEEPS. All are exact integers, and the reads
    // go up by a whole traversal per restored TreeMap.
    //
    // The bound is applied by ScopeSnapshot, where the live collections are, and not by the
    // renderer afterwards - so these pins are taken on the SNAPSHOT and not only through the
    // text. The counting map is the reason ScopeSnapshot has a package-visible factory: copyOf
    // reads a live ComparisonScope, and a test cannot put a counting map inside one of those.

    @Test
    public void testTheAddedNamesAreReadOutOfTheMapExactlyOncePerSide()
    {
        CountingAdditions added = additions(200);

        renderBounded(1, added);

        // ONE pass. The cell and the bullets describe the same names under the same limit, so
        // they share one prefix; two TreeMap copies made it two passes, and a third caller would
        // have made it three.
        assertEquals("the keys of one side may be walked once, not once per rendered section", //$NON-NLS-1$
            200, added.keyReads);
    }

    @Test
    public void testOnlyTheReasonsOfThePrintedNamesAreEverRead()
    {
        // The expensive half. Ordering the map copied every entry, so every OTHER name's reason
        // list came along - and one such list can be thousands of strings long. With limit=1
        // exactly one reason list is needed, so exactly one may be read.
        CountingAdditions added = additions(200);

        renderBounded(1, added);

        assertEquals("a bounded report reads the reasons of the names it prints and no others", //$NON-NLS-1$
            1, added.valueReads);
    }

    @Test
    public void testRaisingTheLimitRaisesTheReasonsReadAndNothingElse()
    {
        // The positive control for the pin above: a report that read NO values at all - or a
        // count that happened to be one for some unrelated reason - would pass it just as well.
        CountingAdditions added = additions(200);

        renderBounded(5, added);

        assertEquals("five printed names need five reason lists", 5, added.valueReads); //$NON-NLS-1$
        assertEquals("and still one walk of the keys", 200, added.keyReads); //$NON-NLS-1$
    }

    @Test
    public void testTheBoundedPrefixIsTheSMALLESTNamesAndNotTheFirstOnesTheMapHappensToHold()
    {
        // Stopping the walk early would bound it further and answer the wrong question: the
        // platform hands these back unordered, so the first k of the iteration are not the k the
        // report is supposed to print, and two runs of the same comparison would disagree. The
        // fixture is inserted in DESCENDING order, so "the first one seen" and "the smallest one"
        // are different names.
        CountingAdditions added = new CountingAdditions();
        added.put("Catalog.Zulu", Collections.singletonList("pulled in")); //$NON-NLS-1$ //$NON-NLS-2$
        added.put("Catalog.Mike", Collections.singletonList("pulled in")); //$NON-NLS-1$ //$NON-NLS-2$
        added.put("Catalog.Alpha", Collections.singletonList("pulled in")); //$NON-NLS-1$ //$NON-NLS-2$

        String report = renderBounded(1, added);

        // The positive half on the CELL, so it pins that consumer rather than being satisfied by
        // the bullet below it; the negative half stays report-wide, because a name that is not
        // the smallest must appear nowhere at all.
        assertTrue("the cell must print the smallest name: " + mainScopeRow(report), //$NON-NLS-1$
            mainScopeRow(report).contains("Catalog.Alpha")); //$NON-NLS-1$
        assertFalse("the first name the map lists is not the name a sorted prefix prints:\n" //$NON-NLS-1$
            + report, report.contains("Catalog.Zulu")); //$NON-NLS-1$
    }

    @Test
    public void testTheWholeCountSurvivesTheBoundThatHidTheNames()
    {
        // The other half of the ask: bounding what is ordered may not cost the report its count
        // of what it left out. The total comes from the map's own size(), which is free, and not
        // from the prefix - deriving it from the prefix would answer "showing 1 of 1".
        //
        // Asserted on the TABLE ROW and not on the report. The bullet list below the table
        // carries a notice of its own, over its own count, and its text is identical here - so a
        // report-wide assertContains passed with the cell's notice gone. Measured: mutating
        // describeAdded's total to prefix.size() left every test in this class green until this
        // pin was narrowed to the row.
        CountingAdditions added = additions(200);

        String row = mainScopeRow(renderBounded(1, added));

        assertTrue("the cell that hid 199 names must still say how many there were: " + row, //$NON-NLS-1$
            row.contains("(showing 1 of 200)")); //$NON-NLS-1$
    }

    @Test
    public void testTheBulletListKeepsItsOwnCountToo()
    {
        // The other notice, pinned separately for the same reason: one assertion covering both
        // is an assertion that either of them can satisfy alone.
        CountingAdditions added = additions(200);

        String report = renderBounded(1, added);

        assertContains(report,
            "Why the engine added a qualified name of its own (showing 1 of 200)"); //$NON-NLS-1$
    }

    /**
     * The Scope table's {@code main} row, which is where the added-name cell lives.
     * <p>
     * Searched from the {@code ## Scope} heading onwards, and that is not fussiness: the summary
     * table at the top of the report ALSO has a row beginning {@code | main |} - the working tree
     * it names - and it comes first. Matching the first {@code | main |} in the report picked
     * that one, so this helper returned a row that can never carry a truncation notice and the
     * assertion using it failed whatever the code did. Caught by reading the failure text of a
     * mutation run rather than by the mutation's verdict, which was red for the wrong reason.
     *
     * @param report the rendered report
     * @return the row
     */
    private static String mainScopeRow(String report)
    {
        int scope = report.indexOf("## Scope"); //$NON-NLS-1$
        if (scope < 0)
        {
            throw new AssertionError("no Scope section in:\n" + report); //$NON-NLS-1$
        }
        for (String line : report.substring(scope).split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| main |")) //$NON-NLS-1$
            {
                return line;
            }
        }
        throw new AssertionError("no '| main |' row under ## Scope in:\n" + report); //$NON-NLS-1$
    }

    @Test
    public void testAskingTheMapForItsSizeIsNotAskingItForItsContents()
    {
        // size() must not be answered by counting a traversal: the count above is what makes the
        // bound affordable, and a total derived by walking would put the walk straight back.
        CountingAdditions added = additions(200);

        renderBounded(1, added);

        assertEquals("one walk, not two - the second would be the count", 200, added.keyReads); //$NON-NLS-1$
        assertTrue("and the fixture really is big enough for a second walk to show up", //$NON-NLS-1$
            added.size() > 1);
    }

    /**
     * The bound the renderer relies on, asserted where it is APPLIED: the reading keeps only the
     * names a report under that limit can print.
     *
     * <h2>Why this is not covered by the read counts above</h2>
     * How much is READ and how much is KEPT are different quantities, and only the first was
     * pinned. A reading that walked the keys once - satisfying every count above - and then
     * copied all two hundred entries with their reason lists into itself would render exactly the
     * same text, because the renderer prints a prefix of what it is given either way. What it
     * would also do is hold the whole thing live for the rest of the comparison read.
     *
     * @see #testTheWholeCountSurvivesTheBoundThatHidTheNames the notice that must survive it
     */
    @Test
    public void testTheReadingKeepsOnlyTheAddedNamesTheReportCanPrint()
    {
        CountingAdditions added = additions(200);

        ComparisonTreeReport.ScopeSnapshot snapshot = snapshotOf(added, 1);

        assertEquals("a reading taken for a report that prints one name may keep one", //$NON-NLS-1$
            1, snapshot.addedNames(ComparisonSide.MAIN).size());
        assertEquals("and it must still know how many there were, or the notice cannot", //$NON-NLS-1$
            200, snapshot.addedTotal(ComparisonSide.MAIN));
    }

    /**
     * The expensive half of the same bound: an addition's REASONS are one string per requested
     * object that pulled it in, so a common dependency of a large request carries a list that
     * dwarfs the map holding it. The report prints at most {@code limit} of them.
     */
    @Test
    public void testTheReadingKeepsOnlyTheReasonsTheReportCanPrint()
    {
        CountingAdditions added = new CountingAdditions();
        added.put(CATALOG_WAREHOUSES, manyReasons(500));

        ComparisonTreeReport.ScopeSnapshot snapshot = snapshotOf(added, 3);

        assertEquals("three reasons are printed, so three are kept", //$NON-NLS-1$
            3, snapshot.reasons(ComparisonSide.MAIN, CATALOG_WAREHOUSES).size());
        assertEquals("and the whole count survives the bound that hid the rest", //$NON-NLS-1$
            500, snapshot.reasonTotal(ComparisonSide.MAIN, CATALOG_WAREHOUSES));
    }

    /**
     * The other column of the scope table. The caller's own request is the smaller of the two in
     * the usual case, but "usually smaller" is not a bound - a caller may name thousands of
     * objects - and the cell printing it is cut by the same limit as the one beside it.
     */
    @Test
    public void testTheReadingKeepsOnlyTheRequestedNamesTheReportCanPrint()
    {
        List<String> request = new ArrayList<>();
        for (int i = 0; i < 200; i++)
        {
            request.add(String.format(Locale.ROOT, "Catalog.Asked%04d", Integer.valueOf(i))); //$NON-NLS-1$
        }
        Map<ComparisonSide, List<String>> requested = new EnumMap<>(ComparisonSide.class);
        Map<ComparisonSide, Map<String, List<String>>> additions =
            new EnumMap<>(ComparisonSide.class);
        for (ComparisonSide side : ComparisonSide.values())
        {
            requested.put(side, request);
            additions.put(side, Collections.emptyMap());
        }

        ComparisonTreeReport.ScopeSnapshot snapshot =
            ComparisonTreeReport.ScopeSnapshot.of(requested, additions, 2);

        assertEquals("two names are printed, so two are kept", //$NON-NLS-1$
            2, snapshot.requested(ComparisonSide.MAIN).size());
        assertEquals("and the cell's notice still has its whole count", //$NON-NLS-1$
            200, snapshot.requestedTotal(ComparisonSide.MAIN));
    }

    /**
     * A kept prefix is a COPY, so the platform extending the list it came from cannot reach it.
     * <p>
     * {@code subList} would have satisfied every count above while leaving the reading a view of
     * the platform's own list - which is the defect the whole class exists to prevent, reopened
     * one level further in than {@code testACopiedScopeDoesNotGrowWithTheEngine} looks.
     */
    @Test
    public void testAKeptReasonPrefixDoesNotSeeLaterAppends()
    {
        List<String> live = new ArrayList<>(manyReasons(5));
        CountingAdditions added = new CountingAdditions();
        added.put(CATALOG_WAREHOUSES, live);

        ComparisonTreeReport.ScopeSnapshot snapshot = snapshotOf(added, 2);
        live.add(LATE_EXTRA_REASON);

        assertEquals("the prefix was copied, not viewed", //$NON-NLS-1$
            2, snapshot.reasons(ComparisonSide.MAIN, CATALOG_WAREHOUSES).size());
        assertFalse("a reason appended after the reading is not part of it", //$NON-NLS-1$
            snapshot.reasons(ComparisonSide.MAIN, CATALOG_WAREHOUSES).contains(LATE_EXTRA_REASON));
    }

    /**
     * @param count how many reasons to build
     * @return that many distinct reasons
     */
    private static List<String> manyReasons(int count)
    {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            list.add(String.format(Locale.ROOT, "referenced by Catalog.Asked%04d", //$NON-NLS-1$
                Integer.valueOf(i)));
        }
        return list;
    }

    /**
     * @param count how many additions to build
     * @return a counting map of {@code count} names, inserted in DESCENDING order so that the
     *     first one iterated is never the smallest
     */
    private static CountingAdditions additions(int count)
    {
        CountingAdditions added = new CountingAdditions();
        for (int i = count - 1; i >= 0; i--)
        {
            added.put(String.format(Locale.ROOT, "Catalog.Pulled%04d", Integer.valueOf(i)), //$NON-NLS-1$
                Collections.singletonList("referenced by " + CATALOG_GOODS)); //$NON-NLS-1$
        }
        return added;
    }

    /**
     * Wraps one side's additions in a snapshot, leaving the other two sides empty.
     *
     * @param added the additions of the main side
     * @return the snapshot, holding the map ITSELF so its counters see what the report does
     */
    private static ComparisonTreeReport.ScopeSnapshot snapshotOf(
        Map<String, List<String>> added, int limit)
    {
        Map<ComparisonSide, List<String>> requested = new EnumMap<>(ComparisonSide.class);
        Map<ComparisonSide, Map<String, List<String>>> additions =
            new EnumMap<>(ComparisonSide.class);
        for (ComparisonSide side : ComparisonSide.values())
        {
            requested.put(side, Collections.singletonList(CATALOG_GOODS));
            additions.put(side, side == ComparisonSide.MAIN ? added : Collections.emptyMap());
        }
        return ComparisonTreeReport.ScopeSnapshot.of(requested, additions, limit);
    }

    /**
     * Renders one side's additions under {@code limit}, with the SNAPSHOT and the collector cut
     * by the same number.
     * <p>
     * The two are taken in different places in production - the snapshot inside the comparison
     * read, the collector where the text is assembled - so they can only agree by being given one
     * limit. A test that spelled the number twice could hold them apart and would then be pinning
     * a report no caller can produce; this helper cannot.
     *
     * @param limit the bound applied to both
     * @param added the additions of the main side
     * @return the rendered report
     */
    private static String renderBounded(int limit, Map<String, List<String>> added)
    {
        ComparisonTreeReport.Collector collector =
            new ComparisonTreeReport.Collector(limit, true);
        return render(collector, snapshotOf(added, collector.limit()), false);
    }

    /**
     * A map that records how much of itself the report took: one count for the KEYS it handed
     * out and one for the VALUES.
     * <p>
     * The two are separate on purpose. Ordering the whole map costs both - {@code TreeMap}'s
     * copy constructor walks {@code entrySet} and reads each key AND each value - while a bounded
     * prefix costs one walk of the keys and a value lookup per printed name. A single counter
     * could not tell the two apart.
     */
    private static final class CountingAdditions
        extends LinkedHashMap<String, List<String>>
    {
        private static final long serialVersionUID = 1L;

        transient int keyReads;

        transient int valueReads;

        @Override
        public Set<String> keySet()
        {
            Set<String> delegate = super.keySet();
            return new AbstractSet<String>()
            {
                @Override
                public Iterator<String> iterator()
                {
                    Iterator<String> keys = delegate.iterator();
                    return new Iterator<String>()
                    {
                        @Override
                        public boolean hasNext()
                        {
                            return keys.hasNext();
                        }

                        @Override
                        public String next()
                        {
                            keyReads++;
                            return keys.next();
                        }
                    };
                }

                @Override
                public int size()
                {
                    return delegate.size();
                }
            };
        }

        @Override
        public Set<Map.Entry<String, List<String>>> entrySet()
        {
            Set<Map.Entry<String, List<String>>> delegate = super.entrySet();
            return new AbstractSet<Map.Entry<String, List<String>>>()
            {
                @Override
                public Iterator<Map.Entry<String, List<String>>> iterator()
                {
                    Iterator<Map.Entry<String, List<String>>> entries = delegate.iterator();
                    return new Iterator<Map.Entry<String, List<String>>>()
                    {
                        @Override
                        public boolean hasNext()
                        {
                            return entries.hasNext();
                        }

                        @Override
                        public Map.Entry<String, List<String>> next()
                        {
                            return counting(entries.next());
                        }
                    };
                }

                @Override
                public int size()
                {
                    return delegate.size();
                }
            };
        }

        @Override
        public List<String> get(Object key)
        {
            valueReads++;
            return super.get(key);
        }

        /**
         * @param entry the real entry
         * @return a view that charges a key read and a value read as they are asked for
         */
        Map.Entry<String, List<String>> counting(Map.Entry<String, List<String>> entry)
        {
            return new Map.Entry<String, List<String>>()
            {
                @Override
                public String getKey()
                {
                    keyReads++;
                    return entry.getKey();
                }

                @Override
                public List<String> getValue()
                {
                    valueReads++;
                    return entry.getValue();
                }

                @Override
                public List<String> setValue(List<String> value)
                {
                    throw new UnsupportedOperationException();
                }
            };
        }
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
        // Copied HERE, which is where production copies it: inside the boundary that read the
        // tree, not at the moment the text is assembled.
        return render(collector,
            ComparisonTreeReport.ScopeSnapshot.copyOf(scope, collector.limit()), globalScope);
    }

    private static String render(ComparisonTreeReport.Collector collector,
        ComparisonTreeReport.ScopeSnapshot scope, boolean globalScope)
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

    /**
     * A node the engine FINISHED and attached no flags to: the absence of a verdict, which is not
     * a verdict of "equal" and not a difference either.
     *
     * @param id the node id
     * @param symlink the name on every side
     * @return the stubbed node
     */
    private static TopComparisonNode notReported(long id, String symlink)
    {
        return node(id, symlink, symlink, symlink, null, ComparisonNodeStatus.FINISHED);
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
