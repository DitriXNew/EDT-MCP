/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.text.edits.ReplaceEdit;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.lcore.refactoring.IFullTextSearchChange;
import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange;
import com.ditrix.edt.mcp.server.utils.ContentHash;

/** Pins the two real rendering/apply entry points, rather than a render-only text filter. */
public class MetadataRenameTextOccurrencesTest
{
    @Test
    public void testKindsCountersAndFooterAreClassifiedPerLeaf() throws Exception
    {
        String preview = preview(new MetadataRenameService(), tree(), 0);
        assertTrue(row(preview, 0).endsWith("| rename |"));
        assertTrue(row(preview, 1).endsWith("| text |"));
        assertTrue(row(preview, 2).endsWith("| reference |"));
        assertTrue(preview.contains("referenceChanges: 1\n"));
        assertTrue(preview.contains("textChanges: 1\n"));
        assertTrue(preview.contains("are change points #1; pass them in disableIndices"));
        assertTrue(row(preview, 1).contains("\u2705"));
        assertTrue(preview.contains("| FQN | Kind |\n"));
        assertEquals(10, row(preview, 1).split("\\|", -1).length - 2);
    }

    @Test
    public void testModelSideTextContentRemainsAReference() throws Exception
    {
        IBmObject model = mock(IBmObject.class);
        var feature = EcoreFactory.eINSTANCE.createEAttribute();
        feature.setName("queryText");
        when(model.eGet(feature)).thenReturn("SELECT Old");
        Change modelText = new BmObjectTextContentChange<>("query", model, feature,
            new ReplaceEdit(7, 3, "New"));
        boolean enabledBefore = modelText.isEnabled();
        List<IRefactoring> tree = refactorings(nativeItem(modelText));
        MetadataRenameService service = service(true);
        // Some supported EDT versions mark this model class too; it still stays a reference.
        method("applyTextOccurrencePolicy", Collection.class).invoke(service, tree);
        assertEquals("Exclusion must preserve the model leaf's original state", enabledBefore, modelText.isEnabled());
        String preview = preview(service, tree, 0);
        assertTrue(row(preview, 0).endsWith("| reference |"));
        assertTrue(preview.contains("textChanges: 0\n"));
        assertFalse(preview.contains("> Text occurrences"));
    }

    @Test
    public void testExcludeDisablesExactlyTextLeavesInPreviewAndConfirmBeforeHashing() throws Exception
    {
        List<IRefactoring> previewTree = tree();
        String preview = preview(service(true), previewTree, 0);
        assertTrue(row(preview, 1).contains("\u274c"));
        assertTrue(row(preview, 2).contains("\u2705"));
        assertTrue(row(preview, 0).contains("\u2705"));
        assertTrue(preview.contains("textChanges: 1\n"));

        List<IRefactoring> confirmTree = tree();
        MetadataRenameService confirmService = service(true);
        String error = performUntilHashGuard(confirmService, confirmTree, "stale");
        assertTrue(error, error.contains("preview is stale"));
        assertFalse(leaf(confirmTree, 0).isEnabled());
        assertTrue(leaf(confirmTree, 1).isEnabled());
        assertEquals(frontmatter(preview, "contentHash"), confirmService.changePointContentHash(confirmTree));
        assertNull(confirmService.expectedHashError(confirmTree, DisableRequest.parse("999"),
            frontmatter(preview, "contentHash")));
    }

    @Test
    public void testCrossFlagHashPairsAreRefusedInBothDirections() throws Exception
    {
        String includeHash = frontmatter(preview(new MetadataRenameService(), tree(), 0), "contentHash");
        String excludeHash = frontmatter(preview(service(true), tree(), 0), "contentHash");
        assertNotEquals(includeHash, excludeHash);
        assertTrue(performUntilHashGuard(service(true), tree(), includeHash).contains("preview is stale"));
        assertTrue(performUntilHashGuard(new MetadataRenameService(), tree(), excludeHash).contains("preview is stale"));
    }

    @Test
    public void testKindEntersSignatureEvenWhenAllOtherFieldsAndIdentityStayTheSame() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        List<?> points = points(service, tree());
        Method sign = method("changePointSignature", List.class);
        String before = ContentHash.of((String)sign.invoke(service, points));
        Field kind = field(points.get(1), "kind");
        kind.set(points.get(1), "reference");
        String after = ContentHash.of((String)sign.invoke(service, points));
        assertNotEquals("A kind change alone must invalidate the plan", before, after);
    }

    @Test
    public void testPolicyIsSignedEvenWithoutTextLeavesOrWhenTheyWereAlreadyDisabled() throws Exception
    {
        for (boolean hasText : new boolean[] {false, true})
        {
            Change includeLeaf = hasText ? new TextLeaf() : new NullChange("reference");
            Change excludeLeaf = hasText ? new TextLeaf() : new NullChange("reference");
            includeLeaf.setEnabled(false);
            excludeLeaf.setEnabled(false);
            String includeHash = frontmatter(preview(new MetadataRenameService(),
                refactorings(nativeItem(includeLeaf)), 0), "contentHash");
            String excludeHash = frontmatter(preview(service(true),
                refactorings(nativeItem(excludeLeaf)), 0), "contentHash");
            assertNotEquals("The policy itself must round-trip with the plan", includeHash, excludeHash);
        }
    }

    @Test
    public void testKindColumnIsLastAndEscapedAndEachContextBlockLabelsItsKind() throws Exception
    {
        Object point = pointWithContext("text|marker");
        StringBuilder row = new StringBuilder();
        method("appendChangePointRow", StringBuilder.class, point.getClass())
            .invoke(new MetadataRenameService(), row, point);
        assertTrue(row.toString(), row.toString().endsWith("| text\\|marker |\n"));
        StringBuilder context = new StringBuilder();
        method("appendCodeContext", StringBuilder.class, List.class, int.class)
            .invoke(new MetadataRenameService(), context, List.of(point), 1);
        assertTrue(context.toString(), context.toString().contains("Kind: text\\|marker\n\n```bsl"));
    }

    @Test
    public void testCountersAndTextIndicesCoverRowsHiddenByMaxResults() throws Exception
    {
        String preview = preview(new MetadataRenameService(), tree(), 1);
        assertFalse(preview.contains("| 1 | bslRef"));
        assertTrue(preview.contains("textChanges: 1\n"));
        assertTrue(preview.contains("referenceChanges: 1\n"));
        assertTrue(preview.contains("are change points #1;"));
        String overflow = preview.lines().filter(line -> line.startsWith("| ... |")).findFirst().orElseThrow();
        assertEquals(10, overflow.split("\\|", -1).length - 2);
    }

    @Test
    public void testSkippedTextCountDoesNotInflateDisableIndicesCount() throws Exception
    {
        MetadataRenameService service = service(true);
        List<IRefactoring> tree = tree();
        int skipped = (Integer)method("applyTextOccurrencePolicy", Collection.class).invoke(service, tree);
        assertEquals(1, skipped);
        Class<?> outcomeType = nested("DisableOutcome");
        Constructor<?> ctor = outcomeType.getDeclaredConstructor(DisableRequest.class);
        ctor.setAccessible(true);
        Object outcome = ctor.newInstance(DisableRequest.parse("999"));
        field(outcome, "textOccurrencesSkipped").setInt(outcome, skipped);
        method("applyDisableIndices", Collection.class, java.util.Set.class, outcomeType)
            .invoke(service, tree, java.util.Set.of(999), outcome);
        String report = (String)method("renderExecutedReport", String.class, String.class, outcomeType,
            List.class, List.class).invoke(null, "CommonModule.Old", "New", outcome, List.of("Rename"), List.of());
        assertTrue(report, report.contains("textOccurrencesSkipped: 1\n"));
        assertTrue(report.contains("disabledCount: 0\n"));
        assertTrue(report.contains("unknownIndices: [999]"));
        assertFalse(report.contains("were skipped as requested"));
    }

    @Test
    public void testFullTextCompositeDoesNotMakeItsReferenceChildText() throws Exception
    {
        CompositeChange composite = mock(CompositeChange.class,
            withSettings().extraInterfaces(IFullTextSearchChange.class));
        Change child = new NullChange("semantic");
        when(composite.getChildren()).thenReturn(new Change[] {child});
        String preview = preview(service(true), refactorings(nativeItem(composite)), 0);
        assertTrue(child.isEnabled());
        assertTrue(row(preview, 0).endsWith("| reference |"));
    }

    @Test
    public void testExcludeFooterSaysTheTextPointsAreAlreadySwitchedOff() throws Exception
    {
        String preview = preview(service(true), tree(), 0);
        assertTrue(preview, preview.contains("are change points #1; textOccurrences=exclude has already "
            + "switched them off, and a confirm with the same textOccurrences leaves them untouched."));
        assertFalse(preview.contains("pass them in disableIndices"));
    }

    @Test
    public void testEachMatchOfAFileIsItsOwnTextLeafAndIndex() throws Exception
    {
        // EDT's file-level FullTextSearchCustomCompositeChange carries no full-text marker; each of
        // its child edits is a separate marked leaf. Two matches in one file are two text points.
        Change first = new TextLeaf();
        Change second = new TextLeaf();
        Change reference = new NullChange("semantic reference");
        CompositeChange file = new CompositeChange("text.bsl", new Change[] {first, second});
        List<IRefactoring> tree = refactorings(nativeItem(new CompositeChange("native",
            new Change[] {file, reference})));
        String preview = preview(service(true), tree, 0);
        assertTrue(preview.contains("textChanges: 2\n"));
        assertTrue(preview.contains("referenceChanges: 1\n"));
        assertTrue(preview.contains("are change points #0, #1; textOccurrences=exclude"));
        assertFalse(first.isEnabled());
        assertFalse(second.isEnabled());
        assertTrue(reference.isEnabled());
    }

    private static MetadataRenameService service(boolean exclude) throws Exception
    {
        return MetadataRenameService.class.getConstructor(boolean.class).newInstance(exclude);
    }

    private static List<IRefactoring> tree()
    {
        IRefactoringItem rename = mock(IRefactoringItem.class);
        when(rename.getName()).thenReturn("Rename object");
        when(rename.isChecked()).thenReturn(true);
        Change text = new TextLeaf();
        Change reference = new NullChange("semantic reference");
        return refactorings(rename, nativeItem(new CompositeChange("native", new Change[] {text, reference})));
    }

    private static List<IRefactoring> refactorings(IRefactoringItem... items)
    {
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getTitle()).thenReturn("Rename");
        when(refactoring.getItems()).thenReturn(List.of(items));
        return List.of(refactoring);
    }

    private static INativeChangeRefactoringItem nativeItem(Change change)
    {
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(change);
        when(item.isChecked()).thenReturn(true);
        when(item.isOptional()).thenReturn(true);
        return item;
    }

    private static Change leaf(List<IRefactoring> tree, int nativeIndex)
    {
        INativeChangeRefactoringItem item = (INativeChangeRefactoringItem)tree.get(0).getItems().stream()
            .filter(value -> value instanceof INativeChangeRefactoringItem).findFirst().orElseThrow();
        return ((CompositeChange)item.getNativeChange()).getChildren()[nativeIndex];
    }

    /** The marker interface, rather than the concrete EDT source-file class, is the contract. */
    private static class TextLeaf extends NullChange implements IFullTextSearchChange
    {
        TextLeaf()
        {
            super("whole-word text");
        }

        @Override
        public Object getModifiedElement()
        {
            return Path.fromPortableString("/Demo/text.bsl");
        }
    }

    private static String preview(MetadataRenameService service, List<IRefactoring> tree, int max) throws Exception
    {
        return (String)method("renderPreview", String.class, String.class, String.class, Collection.class,
            int.class, Map.class, List.class).invoke(service, "CommonModule.Old", "New", "Old", tree,
                max, Map.of(), List.of());
    }

    private static String performUntilHashGuard(MetadataRenameService service, List<IRefactoring> tree,
        String expectedHash) throws Exception
    {
        return (String)method("performRename", String.class, String.class, Collection.class, DisableRequest.class,
            String.class, RenameProgress.class, String.class).invoke(service, "CommonModule.Old", "New", tree,
                DisableRequest.parse("999"), expectedHash, new RenameProgress(), "metadata object");
    }

    private static List<?> points(MetadataRenameService service, List<IRefactoring> tree) throws Exception
    {
        return (List<?>)method("changePointsForSignature", Collection.class).invoke(service, tree);
    }

    private static Object pointWithContext(String kind) throws Exception
    {
        Constructor<?> locationCtor = nested("CodeLocation").getDeclaredConstructors()[0];
        locationCtor.setAccessible(true);
        Object location = locationCtor.newInstance("CommonModule.Old", "Demo", 2, 1, "// Old\n", "Caller");
        Constructor<?> identityCtor = nested("ChangePointIdentity").getDeclaredConstructors()[0];
        identityCtor.setAccessible(true);
        Object identity = identityCtor.newInstance("stable");
        Constructor<?> pointCtor = nested("ChangePoint").getDeclaredConstructors()[0];
        pointCtor.setAccessible(true);
        return pointCtor.newInstance(1, "bslRef", kind, "description", true, true, true, location, identity);
    }

    private static String row(String preview, int index)
    {
        return preview.lines().filter(line -> line.startsWith("| " + index + " | ")).findFirst().orElseThrow();
    }

    private static String frontmatter(String preview, String key)
    {
        return preview.lines().filter(line -> line.startsWith(key + ": ")).findFirst().orElseThrow()
            .substring(key.length() + 2);
    }

    private static Class<?> nested(String name) throws Exception
    {
        return Class.forName(MetadataRenameService.class.getName() + "$" + name);
    }

    private static Field field(Object value, String name) throws Exception
    {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, Class<?>... parameters) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }
}
