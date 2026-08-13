/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.Test;

import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;

/**
 * Tests the {@code disableIndices} half of the {@code rename_metadata_object} contract: WHICH change
 * points a requested index may switch off (#393), WHAT the executed report then claims about it (#394 -
 * the change points the request really left switched off, not the size of the request), and how the
 * entries that produced no skip at all are accounted for: an index that matched nothing, an index
 * naming a required point, or a token that never parsed as a number (#401).
 * <p>
 * Everything here is driven through {@code applyDisableIndices} - the method {@code performRename}
 * actually calls - rather than through the leaf walker underneath it. That is deliberate: the decision
 * under test lives in the branch that chooses whether to disable a leaf, so a test that poked the
 * walker directly would survive removing the guard entirely, and a guard whose removal no test notices
 * is not a guard. The reporting assertions likewise go through the real
 * {@code renderExecutedReport}, on the outcome the real walk produced.
 * <p>
 * Reached by REFLECTION: {@code applyDisableIndices} and {@code renderExecutedReport} are private, and
 * their only public entry ({@code rename}) needs a live EDT project, an {@code IMdRefactoringService}
 * and a BM transaction, none of which exist headlessly. The cost is stated rather than hidden -
 * RENAMING either method breaks these tests with a {@code NoSuchMethodException} instead of a compile
 * error.
 * <p>
 * The refactoring items are Mockito doubles because {@code isOptional()} is exactly the input under
 * test and no fixture reaches every combination of it: on {@code TestConfiguration} every NATIVE item
 * arrives optional, so the required-native case - the one #393 is about - has no live reproduction at
 * all and can only be exercised here. The change trees are bare LTK ({@link NullChange} /
 * {@link CompositeChange}), the same headless technique the numbering tests use.
 */
public class MetadataRenameDisableIndicesTest
{
    @Test
    public void testRequiredNativeItemKeepsItsLeavesEnabled() throws Exception
    {
        Change leaf = new NullChange("required-edit"); //$NON-NLS-1$
        IRefactoringItem item = nativeItem(leaf, false);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertTrue("a REQUIRED change point must stay enabled even when its index is requested", //$NON-NLS-1$
            leaf.isEnabled());
        assertEquals(0, disabledCount(outcome));
        assertEquals("[0]", notSkippableIndices(outcome).toString()); //$NON-NLS-1$
    }

    /**
     * The positive control for the test above: with the SAME shape and only {@code isOptional()}
     * flipped, the leaf must go off. Without this, "never disable anything" would pass #393.
     */
    @Test
    public void testOptionalNativeItemDisablesTheRequestedLeaf() throws Exception
    {
        Change leaf = new NullChange("optional-edit"); //$NON-NLS-1$
        IRefactoringItem item = nativeItem(leaf, true);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertFalse("an OPTIONAL change point must still be skippable", leaf.isEnabled()); //$NON-NLS-1$
        assertEquals(1, disabledCount(outcome));
        assertTrue(notSkippableIndices(outcome).isEmpty());
    }

    /**
     * The #388 trap, re-armed for the new branch: a required item must consume its indices exactly as
     * it did before, or every index after it addresses the wrong leaf. Here the required item owns
     * {@code #0} and {@code #1}, so the optional leaf that follows is {@code #2} - and asking for
     * {@code #2} must switch off THAT leaf, not the one an under-counting walk would land on.
     */
    @Test
    public void testRequiredItemStillConsumesItsIndicesSoLaterOnesStayAligned() throws Exception
    {
        CompositeChange required = new CompositeChange("required"); //$NON-NLS-1$
        Change requiredA = new NullChange("required-a"); //$NON-NLS-1$
        Change requiredB = new NullChange("required-b"); //$NON-NLS-1$
        required.add(requiredA);
        required.add(requiredB);
        Change optional = new NullChange("optional"); //$NON-NLS-1$

        Object outcome = applyDisableIndices(
            refactoring(nativeItem(required, false), nativeItem(optional, true)), request("2"));

        assertFalse("index #2 must reach the leaf AFTER the required item's two leaves", //$NON-NLS-1$
            optional.isEnabled());
        assertTrue(requiredA.isEnabled());
        assertTrue(requiredB.isEnabled());
        assertEquals(1, disabledCount(outcome));
    }

    /**
     * {@code applyDisableIndices} walks EVERY item, and an optional item whose leaves are already all
     * disabled looks "completely disabled" to the checkbox rule - so before this fix any request at all
     * unchecked it, including one that never named a single one of its indices.
     */
    @Test
    public void testUnrelatedIndexDoesNotUncheckAnAlreadyDisabledOptionalItem() throws Exception
    {
        Change leaf = new NullChange("already-off"); //$NON-NLS-1$
        leaf.setEnabled(false);
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), request("99"));

        verify(item, never()).setChecked(false);
    }

    /** The positive control: when the request DID empty the item, unchecking it is still right. */
    @Test
    public void testRequestThatDisablesEveryLeafStillUnchecksTheItem() throws Exception
    {
        Change leaf = new NullChange("last-one"); //$NON-NLS-1$
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), request("0"));

        verify(item).setChecked(false);
    }

    /**
     * The boundary between the two tests above: the leaf is already off AND the request names it. The
     * criterion is that the request NAMED the leaf, not that the flag changed value, so this counts as
     * a skip and the emptied item is unchecked - the caller who names every leaf of an item means the
     * item, whatever those leaves happened to be set to beforehand. Pinned because the opposite reading
     * ("only count a state transition") is the natural one to drift into, and it is silently different.
     */
    @Test
    public void testNamingAnAlreadyDisabledLeafStillCountsAsASkip() throws Exception
    {
        Change leaf = new NullChange("already-off"); //$NON-NLS-1$
        leaf.setEnabled(false);
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        Object outcome = applyDisableIndices(refactoring(item), request("0"));

        assertEquals(1, disabledCount(outcome));
        assertTrue(notSkippableIndices(outcome).isEmpty());
        verify(item).setChecked(false);
    }

    // ==================== #394: the report states the REAL number ====================

    /**
     * The live reproduction from #394, headless: two change points exist, {@code #99} is requested, and
     * the old report announced "disabledCount: 1" plus "1 change point(s) were skipped as requested"
     * while nothing whatsoever had been skipped.
     */
    @Test
    public void testUnknownIndexIsNotCountedAsASkip() throws Exception
    {
        Change leaf = new NullChange("edit"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, true)), request("99"));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(leaf.isEnabled());
        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertFalse("nothing was skipped, so the report must not claim a skip", //$NON-NLS-1$
            report.contains("were skipped as requested")); //$NON-NLS-1$
        assertTrue(report.contains("unknownIndices: [99]")); //$NON-NLS-1$
    }

    /**
     * The other half of #394, and the case measured live on the stand: the index names a REAL change
     * point that cannot be skipped. The caller has to be able to tell "I protected it" from "it was
     * applied anyway", which is precisely what a bare count of the request cannot express.
     */
    @Test
    public void testRequiredIndexIsReportedAsAppliedNotSkipped() throws Exception
    {
        Change leaf = new NullChange("mandatory"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), request("0"));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertTrue(report.contains("notSkippableIndices: [0]")); //$NON-NLS-1$
        assertTrue(report.contains("could NOT be skipped and were left in the rename")); //$NON-NLS-1$
        assertFalse(report.contains("were skipped as requested")); //$NON-NLS-1$
    }

    /**
     * The report is written from what the DISABLE pass decided, and that runs before {@code perform()};
     * so the mandatory-index note must not assert that the change point succeeded. A refactoring that
     * fails is reported under {@code errors}, and the two must not contradict each other in one report.
     */
    @Test
    public void testMandatoryNoteDoesNotClaimSuccessWhenTheRefactoringFailed() throws Exception
    {
        Change leaf = new NullChange("mandatory"); //$NON-NLS-1$
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), request("0"));

        String report = renderExecutedReport(outcome, List.of(), List.of("Rename: boom")); //$NON-NLS-1$

        assertTrue(report.contains("errors: 1")); //$NON-NLS-1$
        assertTrue(report.contains("notSkippableIndices: [0]")); //$NON-NLS-1$
        // Case-insensitive on purpose: the claim is about the WORD, and pinning one casing would let
        // the same overclaim back in as "applied" the moment someone rewrote the sentence.
        assertFalse("the report must not claim the change point was applied - perform() had not run " //$NON-NLS-1$
            + "when this was decided, and here it went on to fail", //$NON-NLS-1$
            report.toLowerCase(java.util.Locale.ROOT).contains("applied")); //$NON-NLS-1$
    }

    /** A real skip still reports as one, and reports the COUNT rather than the request's size. */
    @Test
    public void testActualSkipsAreCountedAndIneffectiveOnesListedSeparately() throws Exception
    {
        CompositeChange root = new CompositeChange("root"); //$NON-NLS-1$
        Change first = new NullChange("first"); //$NON-NLS-1$
        Change second = new NullChange("second"); //$NON-NLS-1$
        root.add(first);
        root.add(second);

        // Three requested: #0 lands, #1 lands, #99 does not exist.
        Object outcome = applyDisableIndices(refactoring(nativeItem(root, true)), request("0,1,99"));
        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertFalse(first.isEnabled());
        assertFalse(second.isEnabled());
        assertTrue(report.contains("disabledCount: 2")); //$NON-NLS-1$
        assertTrue(report.contains("_2 change point(s) were skipped as requested._")); //$NON-NLS-1$
        assertTrue(report.contains("unknownIndices: [99]")); //$NON-NLS-1$
        assertFalse("nothing here is required, so that bucket must stay off the report", //$NON-NLS-1$
            report.contains("notSkippableIndices")); //$NON-NLS-1$
    }

    /** With no request at all the report keeps its old shape - no counts, no extra keys, no prose. */
    @Test
    public void testEmptyRequestRendersNoSkipSectionsAtAll() throws Exception
    {
        Object outcome = newOutcome(request(null));

        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("disabledCount: 0")); //$NON-NLS-1$
        assertFalse(report.contains("notSkippableIndices")); //$NON-NLS-1$
        assertFalse(report.contains("unknownIndices")); //$NON-NLS-1$
        assertFalse(report.contains("skipped")); //$NON-NLS-1$
    }

    /** Indices are rendered as a sorted YAML flow sequence, so one value parses like several do. */
    @Test
    public void testIneffectiveIndicesRenderSortedAsAYamlSequence() throws Exception
    {
        Object outcome = applyDisableIndices(refactoring(nativeItem(new NullChange("x"), true)), //$NON-NLS-1$
            request("99,7,42"));

        String report = renderExecutedReport(outcome, List.of(), List.of());

        assertTrue(report.contains("unknownIndices: [7, 42, 99]")); //$NON-NLS-1$
    }

    // ============ #401: a token that never became an index is still accounted for ============


    /** Separator noise is punctuation, not an entry the caller meant - it must not inflate the count. */
    @Test
    public void testEmptyEntriesAreNotCountedAsUnparsed()
    {
        DisableRequest parsed = request("1,,2,"); //$NON-NLS-1$

        assertEquals(Set.of(Integer.valueOf(1), Integer.valueOf(2)), parsed.indices());
        assertEquals("a stray comma is formatting, not an entry the caller meant", //$NON-NLS-1$
            0, parsed.unparsedCount());
    }

    /**
     * An entry that is empty or only whitespace stays formatting, not an entry - tab included, which
     * Java counts as whitespace. Pinned so the boundary is a decision rather than an accident, and
     * because the counterpart matters just as much: a control character is NOT whitespace, so an entry
     * made only of one is COUNTED rather than silently dropped. That asymmetry is the whole reason
     * strip() replaced trim() here.
     */
    @Test
    public void testWhitespaceOnlyEntriesStayFormattingNotEntries()
    {
        assertEquals(0, request("1,\t,2").unparsedCount()); //$NON-NLS-1$
        assertEquals(2, request("1,\t,2").indices().size()); //$NON-NLS-1$
        assertEquals(1, request("\u0007").unparsedCount()); //$NON-NLS-1$
    }

    // ======== A change point THIS TOOL cannot skip is not the same as a mandatory one ========

    /**
     * The preview prints a plain item's own {@code isOptional()}, so such a row can read
     * {@code Skippable: yes} - and this branch still cannot switch it off, because a plain item owns no
     * leaf {@code Change}. Reporting that as "the refactoring deems it mandatory" is the report
     * asserting something untrue ABOUT THE REFACTORING, which is the rule #394 exists for: say what
     * actually happened, not the nearest convenient thing.
     */
    @Test
    public void testAnOptionalPlainItemIsReportedAsUnsupportedNotMandatory() throws Exception
    {
        IRefactoringItem optionalPlain = mock(IRefactoringItem.class);
        when(optionalPlain.isOptional()).thenReturn(Boolean.TRUE);

        Object outcome = applyDisableIndices(refactoring(optionalPlain), request("0")); //$NON-NLS-1$
        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("unsupportedIndices: [0]")); //$NON-NLS-1$
        assertFalse("the refactoring never called this point mandatory - we simply cannot skip it", //$NON-NLS-1$
            report.contains("notSkippableIndices")); //$NON-NLS-1$
        assertTrue(report.contains("THIS TOOL cannot switch them off")); //$NON-NLS-1$
    }

    /** The other side: a plain item the refactoring really does require is still called mandatory. */
    @Test
    public void testANonOptionalPlainItemIsStillReportedAsMandatory() throws Exception
    {
        Object outcome = applyDisableIndices(refactoring(mock(IRefactoringItem.class)), request("0")); //$NON-NLS-1$
        String report = renderExecutedReport(outcome, List.of("TestConfiguration"), List.of()); //$NON-NLS-1$

        assertTrue(report.contains("notSkippableIndices: [0]")); //$NON-NLS-1$
        assertFalse(report.contains("unsupportedIndices")); //$NON-NLS-1$
    }

    /**
     * The all-unparsable request, end to end and headless: nothing to walk, and the report must still
     * say so. This is the shape the old code answered exactly as if nothing had been asked, and the
     * one the outcome-before-the-guard construction exists for - so it is asserted here as well as
     * over the wire, because a headless test keeps working when the stand is unavailable.
     */
    @Test
    public void testARequestOfOnlyUnparsableEntriesIsStillReported() throws Exception
    {
        String askedForNothing = renderExecutedReport(newOutcome(request(null)), List.of(), List.of());
        String askedWithGarbage =
            renderExecutedReport(newOutcome(request("abc,1.5")), List.of(), List.of()); //$NON-NLS-1$

        assertTrue(askedWithGarbage.contains("unparsedCount: 2")); //$NON-NLS-1$
        assertTrue(askedWithGarbage.contains(
            "2 entr(ies) in disableIndices could not be read as change-point indices")); //$NON-NLS-1$
        assertFalse("a request that asked for something must not answer like one that asked nothing", //$NON-NLS-1$
            askedForNothing.equals(askedWithGarbage));
        assertFalse("the caller's own text must never come back", askedWithGarbage.contains("abc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== helpers ====================

    private static INativeChangeRefactoringItem nativeItem(Change change, boolean optional)
    {
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(change);
        when(item.isOptional()).thenReturn(optional);
        return item;
    }

    private static Collection<IRefactoring> refactoring(IRefactoringItem... items)
    {
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getItems()).thenReturn(Arrays.asList(items));
        when(refactoring.getTitle()).thenReturn("Rename"); //$NON-NLS-1$
        return List.of(refactoring);
    }

    /**
     * Builds the request through the REAL parser rather than handing the service a set of integers:
     * what a token DOES is half of this contract (#401), and a test that skipped the parse could not
     * see the half where a token never becomes an index at all.
     */
    private static DisableRequest request(String raw)
    {
        return DisableRequest.parse(raw);
    }

    private static Object applyDisableIndices(Collection<IRefactoring> refactorings,
        DisableRequest disableRequest) throws Exception
    {
        Object outcome = newOutcome(disableRequest);
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "applyDisableIndices", Collection.class, Set.class, outcomeClass()); //$NON-NLS-1$
        method.setAccessible(true);
        method.invoke(new MetadataRenameService(), refactorings, disableRequest.indices(), outcome);
        return outcome;
    }

    private static Object newOutcome(DisableRequest requested) throws Exception
    {
        java.lang.reflect.Constructor<?> constructor =
            outcomeClass().getDeclaredConstructor(DisableRequest.class);
        constructor.setAccessible(true);
        return constructor.newInstance(requested);
    }

    private static String renderExecutedReport(Object outcome, List<String> performed,
        List<String> errors) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod("renderExecutedReport", //$NON-NLS-1$
            String.class, String.class, outcomeClass(), List.class, List.class);
        method.setAccessible(true);
        return (String)method.invoke(null, "CommonModule.Old", "New", outcome, performed, errors); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Class<?> outcomeClass()
    {
        for (Class<?> candidate : MetadataRenameService.class.getDeclaredClasses())
        {
            if ("DisableOutcome".equals(candidate.getSimpleName())) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new AssertionError("MetadataRenameService.DisableOutcome not found"); //$NON-NLS-1$
    }

    private static int disabledCount(Object outcome) throws Exception
    {
        Method method = outcomeClass().getDeclaredMethod("disabledCount"); //$NON-NLS-1$
        method.setAccessible(true);
        return (Integer)method.invoke(outcome);
    }

    private static Set<?> notSkippableIndices(Object outcome) throws Exception
    {
        Method method = outcomeClass().getDeclaredMethod("notSkippableIndices"); //$NON-NLS-1$
        method.setAccessible(true);
        return (Set<?>)method.invoke(outcome);
    }
}
