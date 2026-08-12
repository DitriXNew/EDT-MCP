/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.junit.Test;

import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;

/**
 * Pins the change-point NUMBERING PARITY of {@link MetadataRenameService}: the preview walk and the
 * execute walk must hand the same index to the same leaf, because a preview {@code #index} is the
 * cross-call handle {@code disableIndices} is later resolved against.
 * <p>
 * The two walks are a MIRROR, not a delegation - {@code collectFlatChanges} (preview) and
 * {@link MetadataRenameService#walkLeafChanges} (execute) are separate traversals, so they can
 * drift silently. They did: the preview's fallback row took a SECOND index for a leaf that had
 * already taken one at the top of {@code collectLeafChange}, so the first leaf reaching that row
 * shifted every later index and {@code disableIndices=N} disabled a different change point than
 * the caller saw (issue #388). The pre-existing tests in {@code RenameMetadataObjectToolTest}
 * walked only the EXECUTE side, so the drift passed straight through them.
 * <p>
 * Three axes are covered:
 * <ul>
 * <li>tree parity - BOTH walks over one synthetic LTK tree, compared leaf for leaf AND on the final
 * counter (a trailing extra take moves no row but shifts the next refactoring's first index);</li>
 * <li>fan-out - a leaf that renders SEVERAL rows must still consume ONE index (the rows share the
 * leaf's index and the counter does not move), which a single-row tree cannot express. These two
 * drive the preview renderers directly - the invariant they pin is one-sided by nature;</li>
 * <li>item parity - a plain (non-native) refactoring item consumes one index on BOTH sides, proven
 * the way a caller experiences it: take the index the preview printed for a named leaf and check
 * WHICH leaf the execute-side disable walk switches off.</li>
 * </ul>
 * <p>
 * Reached by REFLECTION on private internals ({@code collectFlatChanges},
 * {@code collectRefactoringItems}, {@code applyDisableToItem}, {@code addLeafEditChangePoints},
 * {@code addExactMatchChangePoints} and the {@code ScanContext} / {@code LeafScan} /
 * {@code ChangePoint} / {@code ExactMatchInfo} / {@code CodeLocation} holders). Production shape is
 * not bent for testability, so the cost is stated rather than hidden: RENAMING one of those members
 * breaks these tests with a reflection failure instead of a compile error.
 * <p>
 * The synthetic leaves are {@link NullChange}s with an empty exact-match map, which is exactly the
 * leaf kind that reaches the defective fallback row: no exact match, not a BM text-content change,
 * and no {@code IFile} for the source scanner to extract edits from. That is asserted, not assumed
 * - see {@link #testSyntheticLeavesReallyTakeTheFallbackRow()} - so the ratchet cannot quietly stop
 * covering the branch it exists for.
 */
public class MetadataRenameNumberingParityTest
{
    /** The {@code type} the preview stamps on a BSL-reference row (including the fallback row). */
    private static final String BSL_REF = "bslRef"; //$NON-NLS-1$

    /** The {@code type} the preview stamps on a plain (non-native) refactoring item's row. */
    private static final String RENAME = "rename"; //$NON-NLS-1$

    // ==================== Axis 1: the two tree walks, leaf for leaf ====================

    /**
     * THE RATCHET. Both walks run over one tree; the leaf-to-index mapping and the final counter
     * must agree. Names are distinct so the comparison is sensitive to ORDER, not just to the set
     * of indices - a reordered traversal on either side changes the list, and the canonical order
     * is pinned as well so reversing BOTH sides cannot cancel out.
     */
    @Test
    public void testPreviewAndExecuteNumberTheSameLeavesIdentically() throws Exception
    {
        Change root = tree();

        int[] previewCounter = {0};
        List<Object> rows = new ArrayList<>();
        runPreviewWalk(root, rows, previewCounter);

        int[] executeCounter = {0};
        List<String> execute = new ArrayList<>();
        MetadataRenameService.walkLeafChanges(root, executeCounter,
            (leaf, idx) -> execute.add(leaf.getName() + "#" + idx)); //$NON-NLS-1$

        // Depth-first, composites not counted: this is the numbering both sides owe the caller.
        assertEquals("execute walk drifted from the canonical numbering", //$NON-NLS-1$
            List.of("a#0", "b#1", "c#2", "d#3"), execute); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("preview must number the same leaves the same way - a preview #index is the " //$NON-NLS-1$
            + "handle disableIndices is resolved against on execute", execute, describe(rows)); //$NON-NLS-1$
        // A counter that ends higher on one side shifts the FIRST index of the next refactoring,
        // which no per-row assertion above can see.
        assertEquals("both walks must consume the same number of indices", //$NON-NLS-1$
            executeCounter[0], previewCounter[0]);
    }

    /**
     * Positive control for the ratchet above: proves the synthetic leaves really reach the FALLBACK
     * row (the branch issue #388 lived in) rather than some other rendering. The fallback row is the
     * only one such a leaf can produce, and it is built with the no-location {@code CodeLocation},
     * so it is a {@code bslRef} row with line/column {@code -1}. The exact row count is asserted
     * first: without it, an empty result would satisfy the per-row checks vacuously.
     */
    @Test
    public void testSyntheticLeavesReallyTakeTheFallbackRow() throws Exception
    {
        List<Object> rows = new ArrayList<>();
        runPreviewWalk(tree(), rows, new int[] {0});

        assertEquals("one fallback row per leaf", 4, rows.size()); //$NON-NLS-1$
        for (Object row : rows)
        {
            assertEquals(BSL_REF, field(row, "type")); //$NON-NLS-1$
            assertEquals("the fallback row carries no location - if this is a real line number the " //$NON-NLS-1$
                + "leaf took a scanner branch and the ratchet stopped covering the fallback", //$NON-NLS-1$
                Integer.valueOf(-1), field(row, "lineNumber")); //$NON-NLS-1$
        }
    }

    /**
     * The tightest statement of the fix, on the smallest input: ONE leaf renders the fallback row
     * under index 0 and leaves the counter at 1. Before the fix the row carried index 1 and the
     * counter ended at 2.
     */
    @Test
    public void testFallbackRowReusesTheLeafIndexAndConsumesOnlyOne() throws Exception
    {
        int[] counter = {0};
        List<Object> rows = new ArrayList<>();
        runPreviewWalk(new NullChange("only"), rows, counter); //$NON-NLS-1$

        assertEquals(1, rows.size());
        assertEquals(Integer.valueOf(0), field(rows.get(0), "index")); //$NON-NLS-1$
        assertEquals("a leaf consumes exactly one index, whatever it renders", 1, counter[0]); //$NON-NLS-1$
    }

    // ==================== Axis 2: fan-out - many rows, still one index ====================

    /**
     * A leaf whose text edits expand into SEVERAL preview rows still consumes ONE index: every row
     * carries the leaf's index and the counter does not move. A single-row tree cannot express this,
     * so without this test the same defect could reappear in the multi-row renderer unnoticed.
     */
    @Test
    public void testLeafEditRowsAllShareTheLeafIndexAndDoNotAdvanceTheCounter() throws Exception
    {
        String content = "Procedure Run() Export\n\tX = Calc.Add(1, 2);\n\tY = Calc.Sub(3, 4);\nEndProcedure\n"; //$NON-NLS-1$
        List<TextEdit> edits = List.of(
            new ReplaceEdit(content.indexOf("Calc"), 4, "Compute"), //$NON-NLS-1$ //$NON-NLS-2$
            new ReplaceEdit(content.lastIndexOf("Calc"), 4, "Compute"), //$NON-NLS-1$ //$NON-NLS-2$
            new ReplaceEdit(content.indexOf("Run"), 3, "Start")); //$NON-NLS-1$ //$NON-NLS-2$

        int[] counter = {42};
        List<Object> rows = new ArrayList<>();
        Object ctx = newScanContext(rows, counter);
        method("addLeafEditChangePoints").invoke(new MetadataRenameService(), //$NON-NLS-1$
            new NullChange("leaf"), Integer.valueOf(7), newLeafScan(), edits, content, null, ctx); //$NON-NLS-1$

        assertEquals(3, rows.size());
        for (Object row : rows)
        {
            assertEquals("every row of one leaf shares that leaf's index", //$NON-NLS-1$
                Integer.valueOf(7), field(row, "index")); //$NON-NLS-1$
        }
        assertEquals("rendering extra rows must not consume extra indices", 42, counter[0]); //$NON-NLS-1$
    }

    /** The same one-index-per-leaf rule for the OTHER multi-row renderer: exact matches. */
    @Test
    public void testExactMatchRowsAllShareTheLeafIndexAndDoNotAdvanceTheCounter() throws Exception
    {
        List<Object> matches = List.of(
            newExactMatchInfo("/P/src/CommonModules/A/Module.bsl", 10, 3, 5), //$NON-NLS-1$
            newExactMatchInfo("/P/src/CommonModules/B/Module.bsl", 20, 9, 1)); //$NON-NLS-1$

        int[] counter = {42};
        List<Object> rows = new ArrayList<>();
        Object ctx = newScanContext(rows, counter);
        method("addExactMatchChangePoints").invoke(new MetadataRenameService(), //$NON-NLS-1$
            new NullChange("leaf"), Integer.valueOf(7), newLeafScan(), matches, ctx); //$NON-NLS-1$

        assertEquals(2, rows.size());
        for (Object row : rows)
        {
            assertEquals(Integer.valueOf(7), field(row, "index")); //$NON-NLS-1$
        }
        assertEquals("rendering extra rows must not consume extra indices", 42, counter[0]); //$NON-NLS-1$
    }

    // ==================== Axis 3: plain items, end to end ====================

    /**
     * The cross-call handle has to survive a PLAIN (non-native) refactoring item sitting between
     * the native ones - both sides advance the counter past it, or every later index is off by one.
     * Proven the way a caller experiences it: take the index the preview printed for a named leaf,
     * feed exactly that index to the execute-side disable walk, and assert THAT leaf went dark and
     * its neighbours did not. An off-by-one on either side darkens the wrong leaf.
     */
    @Test
    public void testPreviewedIndexDisablesThatSameLeafAcrossAPlainItem() throws Exception
    {
        Change a = new NullChange("a"); //$NON-NLS-1$
        Change b = new NullChange("b"); //$NON-NLS-1$
        Change c = new NullChange("c"); //$NON-NLS-1$
        CompositeChange firstNative = new CompositeChange("n1"); //$NON-NLS-1$
        firstNative.add(a);
        firstNative.add(b);

        List<IRefactoringItem> items = List.of(
            plainItem("p1"), nativeItem(firstNative), plainItem("p2"), nativeItem(c)); //$NON-NLS-1$ //$NON-NLS-2$
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getItems()).thenReturn(items);

        int[] previewCounter = {0};
        List<Object> rows = new ArrayList<>();
        method("collectRefactoringItems").invoke(new MetadataRenameService(), //$NON-NLS-1$
            refactoring, "title", Map.of(), "Calc", rows, previewCounter); //$NON-NLS-1$ //$NON-NLS-2$

        // The plain items are numbered too, interleaved with the native leaves.
        assertEquals(List.of("p1#0", "a#1", "b#2", "p2#3", "c#4"), describe(rows)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals(RENAME, field(rows.get(0), "type")); //$NON-NLS-1$
        assertEquals(BSL_REF, field(rows.get(1), "type")); //$NON-NLS-1$

        int previewedIndexOfC = indexOfRow(rows, "c"); //$NON-NLS-1$
        int[] executeCounter = {0};
        Set<Integer> requested = Set.of(Integer.valueOf(previewedIndexOfC));
        // The execute walk also classifies what each requested index did (#394), so it needs the
        // outcome accumulator. Built reflectively like every other seam here; what this test cares
        // about is still only the NUMBERING, which the accumulator does not participate in.
        Object outcome = onlyConstructor(nested("DisableOutcome")).newInstance(requested); //$NON-NLS-1$
        Method applyDisableToItem = method("applyDisableToItem"); //$NON-NLS-1$
        for (IRefactoringItem item : items)
        {
            applyDisableToItem.invoke(new MetadataRenameService(), item, requested, executeCounter,
                outcome);
        }

        assertFalse("the leaf the caller saw under that #index must be the one disabled", //$NON-NLS-1$
            c.isEnabled());
        assertTrue("a neighbouring leaf must not be disabled instead", a.isEnabled()); //$NON-NLS-1$
        assertTrue("a neighbouring leaf must not be disabled instead", b.isEnabled()); //$NON-NLS-1$
        assertEquals("both sides must consume the same number of indices", //$NON-NLS-1$
            previewCounter[0], executeCounter[0]);
    }

    // ==================== fixtures ====================

    /** {@code root[ a, mid[ b, c ], d ]} - nested composites so recursion order is observable. */
    private static Change tree()
    {
        CompositeChange root = new CompositeChange("root"); //$NON-NLS-1$
        CompositeChange mid = new CompositeChange("mid"); //$NON-NLS-1$
        mid.add(new NullChange("b")); //$NON-NLS-1$
        mid.add(new NullChange("c")); //$NON-NLS-1$
        root.add(new NullChange("a")); //$NON-NLS-1$
        root.add(mid);
        root.add(new NullChange("d")); //$NON-NLS-1$
        return root;
    }

    private static IRefactoringItem plainItem(String name)
    {
        IRefactoringItem item = mock(IRefactoringItem.class);
        when(item.getName()).thenReturn(name);
        return item;
    }

    /**
     * A native item carrying {@code change}, stubbed OPTIONAL - which is what the real ones are
     * (the live preview marks every {@code bslRef} row Skippable=yes) and what {@code disableIndices}
     * is documented to act on. Leaving Mockito's default {@code isOptional()==false} would make this
     * test assert that a change point the table marks NON-skippable can be switched off, pinning a
     * behaviour that is a separate question from numbering parity.
     */
    private static INativeChangeRefactoringItem nativeItem(Change change)
    {
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(change);
        when(item.isOptional()).thenReturn(true);
        return item;
    }

    // ==================== reflective seams ====================

    /** Runs the PREVIEW walk over {@code root}, appending its rows to {@code rows}. */
    private static void runPreviewWalk(Change root, List<Object> rows, int[] counter) throws Exception
    {
        method("collectFlatChanges").invoke(new MetadataRenameService(), //$NON-NLS-1$
            root, null, null, newScanContext(rows, counter));
    }

    /** Renders preview rows as {@code description#index} so comparisons see order AND numbering. */
    private static List<String> describe(List<Object> rows) throws Exception
    {
        List<String> described = new ArrayList<>();
        for (Object row : rows)
        {
            described.add(field(row, "description") + "#" + field(row, "index")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return described;
    }

    /** The {@code index} the preview stamped on the (single) row describing {@code description}. */
    private static int indexOfRow(List<Object> rows, String description) throws Exception
    {
        Integer found = null;
        for (Object row : rows)
        {
            if (description.equals(field(row, "description"))) //$NON-NLS-1$
            {
                assertNull("ambiguous row: " + description, found); //$NON-NLS-1$
                found = (Integer)field(row, "index"); //$NON-NLS-1$
            }
        }
        assertNotNull("no row describes " + description, found); //$NON-NLS-1$
        return found.intValue();
    }

    private static Object newScanContext(List<Object> rows, int[] counter) throws Exception
    {
        Constructor<?> ctor = onlyConstructor(nested("ScanContext")); //$NON-NLS-1$
        return ctor.newInstance(Map.of(), rows, counter, "title", Boolean.TRUE, "Calc"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Object newLeafScan() throws Exception
    {
        return onlyConstructor(nested("LeafScan")).newInstance("Fqn", "Project"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Object newExactMatchInfo(String filePath, int offset, int line, int column)
        throws Exception
    {
        Object location = onlyConstructor(nested("CodeLocation")) //$NON-NLS-1$
            .newInstance("Fqn", "Project", Integer.valueOf(line), Integer.valueOf(column), //$NON-NLS-1$ //$NON-NLS-2$
                "context", "Method"); //$NON-NLS-1$ //$NON-NLS-2$
        return onlyConstructor(nested("ExactMatchInfo")) //$NON-NLS-1$
            .newInstance(filePath, Integer.valueOf(offset), location);
    }

    /** The single declared method of that name (fails loudly if it stops being unambiguous). */
    private static Method method(String name)
    {
        Method found = null;
        for (Method candidate : MetadataRenameService.class.getDeclaredMethods())
        {
            if (candidate.getName().equals(name))
            {
                assertNull("MetadataRenameService." + name + " is overloaded - this seam assumed " //$NON-NLS-1$ //$NON-NLS-2$
                    + "one declaration", found); //$NON-NLS-1$
                found = candidate;
            }
        }
        assertNotNull("MetadataRenameService has no method " + name, found); //$NON-NLS-1$
        found.setAccessible(true); // NOSONAR the walk is private; production shape is not bent for tests
        return found;
    }

    private static Class<?> nested(String simpleName)
    {
        for (Class<?> candidate : MetadataRenameService.class.getDeclaredClasses())
        {
            if (candidate.getSimpleName().equals(simpleName))
            {
                return candidate;
            }
        }
        fail("MetadataRenameService has no nested class " + simpleName); //$NON-NLS-1$
        return null;
    }

    private static Constructor<?> onlyConstructor(Class<?> type)
    {
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        assertEquals(type.getSimpleName() + " must have exactly one constructor", 1, ctors.length); //$NON-NLS-1$
        ctors[0].setAccessible(true); // NOSONAR the holders are private; see the class javadoc
        return ctors[0];
    }

    private static Object field(Object owner, String name) throws Exception
    {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); // NOSONAR the holders are private; see the class javadoc
        return field.get(owner);
    }
}
