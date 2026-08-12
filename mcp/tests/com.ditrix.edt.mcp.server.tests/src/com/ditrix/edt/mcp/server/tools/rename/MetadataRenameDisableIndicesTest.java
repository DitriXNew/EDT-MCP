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
import java.util.LinkedHashSet;
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
 * points a requested index may switch off (#393) and WHAT the executed report then claims about it
 * (#394).
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
    // ==================== #393: a required change point is never switched off ====================

    /**
     * The promise the preview footer and the guide both make - "required ones are always applied" -
     * enforced for a NATIVE item. Before #393 {@code applyDisableToChange} was reached unconditionally
     * and this leaf came back disabled; {@code isOptional()} guarded only the item's own checkbox.
     */
    @Test
    public void testRequiredNativeItemKeepsItsLeavesEnabled() throws Exception
    {
        Change leaf = new NullChange("required-edit"); //$NON-NLS-1$
        IRefactoringItem item = nativeItem(leaf, false);

        Object outcome = applyDisableIndices(refactoring(item), indices(0));

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

        Object outcome = applyDisableIndices(refactoring(item), indices(0));

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
            refactoring(nativeItem(required, false), nativeItem(optional, true)), indices(2));

        assertFalse("index #2 must reach the leaf AFTER the required item's two leaves", //$NON-NLS-1$
            optional.isEnabled());
        assertTrue(requiredA.isEnabled());
        assertTrue(requiredB.isEnabled());
        assertEquals(1, disabledCount(outcome));
    }

    /**
     * A plain (non-native) rename item owns no leaf {@link Change} to switch off, so it is not
     * skippable whatever it reports for {@code isOptional()}. It already behaved that way; what is new
     * is that asking for its index is now ACCOUNTED for instead of vanishing.
     */
    @Test
    public void testPlainItemIsReportedAsNotSkippable() throws Exception
    {
        Object outcome = applyDisableIndices(refactoring(mock(IRefactoringItem.class)), indices(0));

        assertEquals(0, disabledCount(outcome));
        assertEquals("[0]", notSkippableIndices(outcome).toString()); //$NON-NLS-1$
    }

    // ============ #393: an unrelated index must not silently drop a whole item ============

    /**
     * {@code applyDisableIndices} walks EVERY item, and an optional item whose leaves are already all
     * disabled looks "completely disabled" to the checkbox rule - so before this fix any request at all
     * unchecked it, including one that never named a single one of its indices. An empty change tree
     * counts as completely disabled too, which makes the same request drop an item that had nothing
     * disabled about it.
     */
    @Test
    public void testUnrelatedIndexDoesNotUncheckAnAlreadyDisabledOptionalItem() throws Exception
    {
        Change leaf = new NullChange("already-off"); //$NON-NLS-1$
        leaf.setEnabled(false);
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), indices(99));

        verify(item, never()).setChecked(false);
    }

    /** The positive control: when the request DID empty the item, unchecking it is still right. */
    @Test
    public void testRequestThatDisablesEveryLeafStillUnchecksTheItem() throws Exception
    {
        Change leaf = new NullChange("last-one"); //$NON-NLS-1$
        INativeChangeRefactoringItem item = nativeItem(leaf, true);

        applyDisableIndices(refactoring(item), indices(0));

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

        Object outcome = applyDisableIndices(refactoring(item), indices(0));

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
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, true)), indices(99));

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
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), indices(0));

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
        Object outcome = applyDisableIndices(refactoring(nativeItem(leaf, false)), indices(0));

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
        Object outcome = applyDisableIndices(refactoring(nativeItem(root, true)), indices(0, 1, 99));
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
        Object outcome = newOutcome(Set.of());

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
            indices(99, 7, 42));

        String report = renderExecutedReport(outcome, List.of(), List.of());

        assertTrue(report.contains("unknownIndices: [7, 42, 99]")); //$NON-NLS-1$
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

    private static Set<Integer> indices(Integer... values)
    {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Object applyDisableIndices(Collection<IRefactoring> refactorings,
        Set<Integer> disableIndices) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "applyDisableIndices", Collection.class, Set.class); //$NON-NLS-1$
        method.setAccessible(true);
        return method.invoke(new MetadataRenameService(), refactorings, disableIndices);
    }

    private static Object newOutcome(Set<Integer> requested) throws Exception
    {
        Class<?> outcomeClass = outcomeClass();
        java.lang.reflect.Constructor<?> constructor = outcomeClass.getDeclaredConstructor(Set.class);
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
