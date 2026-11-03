/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.eclipse.text.edits.ReplaceEdit;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.EditingForbiddenProblem;
import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange;
import com.ditrix.edt.mcp.server.utils.BmObjectAddress;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentCompositeChange;

/** Regression for contained query targets in preview, exact matches and confirm's hash walk (#790). */
public class MetadataRenameContainedTargetTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String OWNER = "Catalog.Goods.Form.ListForm.Form"; //$NON-NLS-1$
    private static final String ADDRESS = "Catalog.Goods.Form.ListForm"; //$NON-NLS-1$
    private static final String CONTENT = "SELECT Ref FROM Catalog.Goods"; //$NON-NLS-1$
    private static final String DESCRIPTION = "Dynamic list query"; //$NON-NLS-1$
    private static final String LIST_URI = "bm://TestConfiguration/" + OWNER + "#/attributes:List/extInfo"; //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testAPreviewRowForATextChangeInsideAContainedObjectNamesItsOwner() throws Exception
    {
        IBmObject target = contained(LIST_URI);
        String preview = preview(refactorings(change(target)), Map.of());
        String[] cells = row(preview, 0);

        assertEquals(DESCRIPTION, cells[2]);
        assertEquals("1", cells[3]); //$NON-NLS-1$
        assertEquals("17", cells[4]); //$NON-NLS-1$
        assertEquals("yes", cells[6]); //$NON-NLS-1$
        assertEquals(PROJECT, cells[7]);
        assertEquals(ADDRESS, cells[8]);
        assertFalse(preview, preview.contains(OWNER));
        assertTrue(preview, preview.contains(CONTENT));
        assertFalse(preview, preview.contains("cannot be proven")); //$NON-NLS-1$
        verify(target, never()).bmGetFqn();
    }

    @Test
    public void testTwoContainedTargetsUnderOneOwnerGetDistinctStableIdentities() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        Change a = change(contained(LIST_URI));
        Change b = change(contained("bm://TestConfiguration/" + OWNER + "#/attributes:Other/extInfo")); //$NON-NLS-1$ //$NON-NLS-2$
        List<IRefactoring> distinct = refactorings(a, b);

        assertNotEquals(service.changePointContentHash(refactorings(
            change(contained(LIST_URI)), change(contained(LIST_URI)))), service.changePointContentHash(distinct));
        String preview = preview(distinct, Map.of());
        assertEquals("yes", row(preview, 0)[6]); //$NON-NLS-1$
        assertEquals("yes", row(preview, 1)[6]); //$NON-NLS-1$
        assertFalse(preview, preview.contains("cannot be proven")); //$NON-NLS-1$
    }

    @Test
    public void testAContainedTargetWithoutABmUriIsSignedWithoutIdentity() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        List<IRefactoring> tree = refactorings(change(contained("file:/detached.model#child"))); //$NON-NLS-1$
        String preview = preview(tree, Map.of());

        assertEquals(ADDRESS, row(preview, 0)[8]);
        assertFalse(preview, preview.contains(OWNER));
        assertEquals("1", row(preview, 0)[3]); //$NON-NLS-1$
        assertEquals("no", row(preview, 0)[6]); //$NON-NLS-1$
        assertTrue(preview, preview.contains("cannot be proven")); //$NON-NLS-1$
        String hash = service.changePointContentHash(tree);
        assertNotNull(hash);
        assertTrue(preview, preview.contains("contentHash: " + hash)); //$NON-NLS-1$
        String refusal = service.expectedHashError(tree, DisableRequest.parse("0"), hash); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("indices [0]")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnreferenceableContainedTargetCannotBecomeSkippable() throws Exception
    {
        String preview = preview(refactorings(change(contained(
            "bm://TestConfiguration/" + OWNER + "#/-1"))), Map.of()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("no", row(preview, 0)[6]); //$NON-NLS-1$
        assertTrue(preview, preview.contains("cannot be proven")); //$NON-NLS-1$
    }

    @Test
    public void testTheConfirmSideSignatureSurvivesAContainedTarget() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        String hash = service.changePointContentHash(refactorings(change(contained(LIST_URI))));
        // Confirm rebuilds fresh BM wrappers and a fresh change tree for the same attached child.
        List<IRefactoring> rebuilt = refactorings(change(contained(LIST_URI)));
        assertNotNull(hash);
        assertEquals(hash, service.changePointContentHash(rebuilt));
        assertNull(service.expectedHashError(rebuilt, DisableRequest.parse("0"), hash)); //$NON-NLS-1$
        String stale = service.expectedHashError(rebuilt, DisableRequest.parse("0"), "stale-" + hash); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(stale);
        assertTrue(stale, stale.contains("preview is stale")); //$NON-NLS-1$
    }

    @Test
    public void testTopTargetIdentityPartsRemainByteIdentical() throws Exception
    {
        IBmObject top = top(OWNER);
        assertEquals("8:bmEngine17:TestConfiguration5:bmFqn32:Catalog.Goods.Form.ListForm.Form", //$NON-NLS-1$
            method("stableTargetIdentity", Object.class).invoke(null, top)); //$NON-NLS-1$
        assertEquals("8:bmEngine17:TestConfiguration5:bmFqn27:Catalog.Goods.Form.ListForm", //$NON-NLS-1$
            method("stableTargetIdentity", Object.class).invoke(null, top(ADDRESS))); //$NON-NLS-1$
        verify(top, never()).bmGetUri();
        verify(top, never()).bmGetTopObject();
    }

    @Test
    public void testATopContentTargetIsRenderedByAddressButSignedByItsOwnFqn() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService();
        IBmObject target = top(OWNER);
        String preview = preview(refactorings(change(target)), Map.of());

        assertEquals(ADDRESS, row(preview, 0)[8]);
        // Same rendered row, different BM FQN: only a signature that keeps the BM FQN tells them apart.
        assertNotEquals(service.changePointContentHash(refactorings(change(top(OWNER)))),
            service.changePointContentHash(refactorings(change(top(ADDRESS)))));
        verify(target, never()).bmGetTopObject();
    }

    @Test
    public void testAContainedTargetWithExactMatchesNamesItsOwner() throws Exception
    {
        Object location = constructor("CodeLocation").newInstance(OWNER, PROJECT, 41, 9, "BSL module context", null); //$NON-NLS-1$
        Object exact = constructor("ExactMatchInfo").newInstance("/TestConfiguration/list.form", 16, location); //$NON-NLS-1$ //$NON-NLS-2$
        String preview = preview(refactorings(change(contained(LIST_URI))), Map.of("match", exact)); //$NON-NLS-1$
        assertEquals(ADDRESS, row(preview, 0)[8]);
        assertFalse(preview, preview.contains(OWNER));
        assertEquals("1", row(preview, 0)[3]); //$NON-NLS-1$
        assertEquals("17", row(preview, 0)[4]); //$NON-NLS-1$
        assertEquals("yes", row(preview, 0)[6]); //$NON-NLS-1$
    }

    @Test
    public void testACompositeContainedContextNamesItsOwner() throws Exception
    {
        BmObjectTextContentCompositeChange<?> composite = mock(BmObjectTextContentCompositeChange.class);
        IBmObject target = contained(LIST_URI);
        when(composite.getModifiedElement()).thenReturn(target);
        when(composite.getProjectName()).thenReturn(PROJECT);
        when(composite.getChildren()).thenReturn(new Change[] {new NullChange("child")}); //$NON-NLS-1$
        String preview = preview(refactorings(composite), Map.of());
        assertEquals(ADDRESS, row(preview, 0)[8]);
        assertFalse(preview, preview.contains(OWNER));
    }

    @Test
    public void testModelExactMatchResolvesATopWithoutLookingForItsOwner() throws Exception
    {
        IBmObject top = top(OWNER);
        when(top.bmGetTopObject()).thenThrow(new RuntimeException("no owner lookup on a top")); //$NON-NLS-1$
        EAttribute feature = queryFeature();
        when(top.eGet(feature)).thenReturn(CONTENT);
        Object exact = method("createModelExactMatchInfo", Object.class).invoke(new MetadataRenameService(), //$NON-NLS-1$
            new ModelMatch(top, feature));
        assertNotNull(exact);
        // The match keeps the BM FQN; only the rendered row shows the metadata address.
        assertEquals(OWNER, field(exact, "fqn")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(1), field(exact, "lineNumber")); //$NON-NLS-1$
        verify(top, never()).bmGetTopObject();
    }

    @Test
    public void testAProblemOnAContainedObjectNamesItsOwnerInTheProblemsSection() throws Exception
    {
        IBmObject target = namedChild();
        String preview = preview(List.of(problemRefactoring(new EditingForbiddenProblem(target))), Map.of());
        assertTrue(preview, preview.contains("## Problems")); //$NON-NLS-1$
        assertTrue(preview, preview.contains("- Catalog.Goods (Weight)")); //$NON-NLS-1$
        verify(target, never()).bmGetFqn();
    }

    @Test
    public void testCleanReferenceProblemNamesBothContainedOwners() throws Exception
    {
        CleanReferenceProblem problem = mock(CleanReferenceProblem.class);
        IBmObject referencing = namedChild();
        IBmObject target = contained(LIST_URI);
        when(problem.getReferencingObject()).thenReturn(referencing);
        when(problem.getObject()).thenReturn(target);
        EAttribute feature = queryFeature();
        when(problem.getReference()).thenReturn(feature);
        String preview = preview(List.of(problemRefactoring(problem)), Map.of());
        assertTrue(preview, preview.contains("Catalog.Goods (Weight)")); //$NON-NLS-1$
        assertTrue(preview, preview.contains("queryText | " + OWNER)); //$NON-NLS-1$
    }

    @Test
    public void testAnUnnamedProblemUsesTheExistingFallbackLabel() throws Exception
    {
        IBmObject target = mock(IBmObject.class);
        when(target.bmGetFqn()).thenThrow(new RuntimeException("top objects only")); //$NON-NLS-1$
        IRefactoringProblem problem = mock(IRefactoringProblem.class);
        when(problem.getObject()).thenReturn(target);
        String preview = preview(List.of(problemRefactoring(problem)), Map.of());
        assertTrue(preview, preview.contains("- an object EDT could not name")); //$NON-NLS-1$
    }

    @Test
    public void testLockedContainedObjectUsesTheSharedLocalLabel() throws Exception
    {
        String label = (String)method("lockedObjectLabel", IRefactoringProblem.class).invoke(null, //$NON-NLS-1$
            new EditingForbiddenProblem(namedChild()));
        assertEquals("Catalog.Goods (Weight)", label); //$NON-NLS-1$
    }

    @Test
    public void testContainedTextUnderAMetadataTopIgnoresBslFileOffsets() throws Exception
    {
        IBmObject target = contained("bm://TestConfiguration/Catalog.Goods#/characteristics:0"); //$NON-NLS-1$
        IBmObject owner = top("Catalog.Goods"); //$NON-NLS-1$
        when(target.bmGetTopObject()).thenReturn(owner);
        Object location = constructor("CodeLocation").newInstance("Catalog.Goods", PROJECT, 41, 9, //$NON-NLS-1$ //$NON-NLS-2$
            "BSL module context", null); //$NON-NLS-1$
        Object exact = constructor("ExactMatchInfo").newInstance( //$NON-NLS-1$
            "/TestConfiguration/src/Catalogs/Goods/ObjectModule.bsl", 16, location); //$NON-NLS-1$
        String preview = preview(refactorings(change(target)), Map.of("bsl", exact)); //$NON-NLS-1$
        assertEquals("Catalog.Goods", row(preview, 0)[8]); //$NON-NLS-1$
        assertEquals("1", row(preview, 0)[3]); //$NON-NLS-1$
        assertEquals("17", row(preview, 0)[4]); //$NON-NLS-1$
        assertTrue(preview, preview.contains(CONTENT));
        assertFalse(preview, preview.contains("BSL module context")); //$NON-NLS-1$
    }

    @Test
    public void testAContainedDcsQueryNamesItsMetadataTemplateAddress() throws Exception
    {
        String owner = "Report.R.Template.T.Template"; //$NON-NLS-1$
        IBmObject target = contained("bm://TestConfiguration/" + owner + "#/dataSets:0"); //$NON-NLS-1$ //$NON-NLS-2$
        IBmObject top = top(owner);
        when(target.bmGetTopObject()).thenReturn(top);
        String preview = preview(refactorings(change(target)), Map.of());
        assertEquals("Report.R.Template.T", row(preview, 0)[8]); //$NON-NLS-1$
        assertEquals("bslRef", row(preview, 0)[1]); //$NON-NLS-1$
        assertEquals(DESCRIPTION, row(preview, 0)[2]);
        assertFalse(preview, preview.contains(owner));
    }

    @Test
    public void testAContainedRlsTextNamesItsRoleAddress() throws Exception
    {
        String owner = "Role.R.Rights"; //$NON-NLS-1$
        IBmObject target = contained("bm://TestConfiguration/" + owner + "#/rights:0/rights:0/rls:0"); //$NON-NLS-1$ //$NON-NLS-2$
        IBmObject top = top(owner);
        when(target.bmGetTopObject()).thenReturn(top);
        String preview = preview(refactorings(change(target)), Map.of());
        assertEquals("Role.R", row(preview, 0)[8]); //$NON-NLS-1$
        assertEquals("1", row(preview, 0)[3]); //$NON-NLS-1$
        assertEquals("17", row(preview, 0)[4]); //$NON-NLS-1$
        assertEquals("yes", row(preview, 0)[6]); //$NON-NLS-1$
        assertFalse(preview, preview.contains(owner));
        verify(target, never()).bmGetFqn();
    }

    @Test
    public void testACommonFormContentTopIgnoresModuleFileOffsets() throws Exception
    {
        IBmObject target = top("CommonForm.F.Form"); //$NON-NLS-1$
        Object location = constructor("CodeLocation").newInstance("CommonForm.F", PROJECT, 41, 9, //$NON-NLS-1$ //$NON-NLS-2$
            "BSL module context", null); //$NON-NLS-1$
        Object exact = constructor("ExactMatchInfo").newInstance( //$NON-NLS-1$
            "/TestConfiguration/src/CommonForms/F/Module.bsl", 16, location); //$NON-NLS-1$
        String preview = preview(refactorings(change(target)), Map.of("bsl", exact)); //$NON-NLS-1$
        String[] cells = row(preview, 0);
        assertEquals("CommonForm.F", cells[8]); //$NON-NLS-1$
        assertEquals("1", cells[3]); //$NON-NLS-1$
        assertEquals("17", cells[4]); //$NON-NLS-1$
        assertTrue(preview, preview.contains(CONTENT));
        assertFalse(preview, preview.contains("BSL module context")); //$NON-NLS-1$
    }

    @Test
    public void testModelExactMatchNamesTheMetadataAddressOfAContainedQuery() throws Exception
    {
        String owner = "Report.R.Template.T.Template"; //$NON-NLS-1$
        IBmObject target = contained("bm://TestConfiguration/" + owner + "#/dataSets:0"); //$NON-NLS-1$ //$NON-NLS-2$
        IBmObject top = top(owner);
        when(target.bmGetTopObject()).thenReturn(top);
        EAttribute feature = queryFeature();
        when(target.eGet(feature)).thenReturn(CONTENT);
        Object exact = method("createModelExactMatchInfo", Object.class).invoke(new MetadataRenameService(), //$NON-NLS-1$
            new ModelMatch(target, feature));
        assertNotNull(exact);
        // The match keeps the owning top's BM FQN (as before #790); the row renders its address.
        assertEquals(owner, field(exact, "fqn")); //$NON-NLS-1$
        assertEquals("Report.R.Template.T", BmObjectAddress.metadataAddressOf(owner)); //$NON-NLS-1$
    }

    @Test
    public void testLockedNamedChildWithoutAnOwnerUsesTheGenericLabel() throws Exception
    {
        IBmObject child = namedChild();
        when(child.bmGetTopObject()).thenReturn(null);
        assertEquals("an object EDT could not name", lockedLabel(new EditingForbiddenProblem(child))); //$NON-NLS-1$
        when(child.bmGetTopObject()).thenThrow(new RuntimeException("transient")); //$NON-NLS-1$
        assertEquals("an object EDT could not name", lockedLabel(new EditingForbiddenProblem(child))); //$NON-NLS-1$
    }

    @Test
    public void testAnUnavailableLockedObjectUsesTheGenericLabel() throws Exception
    {
        IRefactoringProblem problem = mock(IRefactoringProblem.class);
        when(problem.getObject()).thenThrow(new RuntimeException("object unavailable")); //$NON-NLS-1$
        assertEquals("an object EDT could not name", lockedLabel(problem)); //$NON-NLS-1$
    }

    private static String lockedLabel(IRefactoringProblem problem) throws Exception
    {
        return (String)method("lockedObjectLabel", IRefactoringProblem.class).invoke(null, problem); //$NON-NLS-1$
    }

    private static IBmObject top(String fqn)
    {
        IBmObject object = mock(IBmObject.class);
        IBmEngine engine = mock(IBmEngine.class);
        when(engine.getId()).thenReturn(PROJECT);
        when(object.bmGetEngine()).thenReturn(engine);
        when(object.bmIsTop()).thenReturn(true);
        when(object.bmGetFqn()).thenReturn(fqn);
        return object;
    }

    private static IBmObject contained(String uri)
    {
        IBmObject object = mock(IBmObject.class, withSettings().extraInterfaces(InternalEObject.class));
        when(((InternalEObject)object).eProxyURI()).thenReturn(URI.createURI("#//")); //$NON-NLS-1$
        when(object.bmGetFqn()).thenThrow(new RuntimeException("Method bmGetFqn may be called on top objects only")); //$NON-NLS-1$
        IBmObject owner = top(OWNER);
        when(object.bmGetTopObject()).thenReturn(owner);
        IBmEngine engine = owner.bmGetEngine();
        when(object.bmGetEngine()).thenReturn(engine);
        when(object.bmGetUri()).thenReturn(URI.createURI(uri));
        return object;
    }

    private static IBmObject namedChild()
    {
        IBmObject object = mock(IBmObject.class, withSettings().extraInterfaces(MdObject.class));
        IBmEngine engine = mock(IBmEngine.class);
        when(object.bmGetEngine()).thenReturn(engine);
        when(object.bmGetId()).thenReturn(7L);
        when(engine.getObjectById(7L)).thenReturn(object);
        when(object.bmGetFqn()).thenThrow(new RuntimeException("top objects only")); //$NON-NLS-1$
        IBmObject owner = top("Catalog.Goods");
        when(object.bmGetTopObject()).thenReturn(owner); //$NON-NLS-1$
        when(((MdObject)object).getName()).thenReturn("Weight"); //$NON-NLS-1$
        return object;
    }

    private static EAttribute queryFeature()
    {
        EAttribute feature = EcoreFactory.eINSTANCE.createEAttribute();
        feature.setName("queryText"); //$NON-NLS-1$
        EClass owner = EcoreFactory.eINSTANCE.createEClass();
        owner.setName("DynamicListExtInfo"); //$NON-NLS-1$
        owner.getEStructuralFeatures().add(feature);
        return feature;
    }

    private static Change change(IBmObject target)
    {
        EAttribute feature = queryFeature();
        when(target.eGet(feature)).thenReturn(CONTENT);
        return new BmObjectTextContentChange<>(DESCRIPTION, target, feature,
            new ReplaceEdit(16, 13, "Catalog.Items")); //$NON-NLS-1$
    }

    private static List<IRefactoring> refactorings(Change... changes)
    {
        // EDT returns two nested composites for dynamic-list queries.
        CompositeChange root = new CompositeChange("Changes for list form", //$NON-NLS-1$
            new Change[] {new CompositeChange("Query text", changes)}); //$NON-NLS-1$
        INativeChangeRefactoringItem item = mock(INativeChangeRefactoringItem.class);
        when(item.getNativeChange()).thenReturn(root);
        when(item.isOptional()).thenReturn(true);
        when(item.isChecked()).thenReturn(true);
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getTitle()).thenReturn("Rename query references"); //$NON-NLS-1$
        when(refactoring.getItems()).thenReturn(List.of(item));
        return List.of(refactoring);
    }

    private static IRefactoring problemRefactoring(IRefactoringProblem problem)
    {
        RefactoringStatus status = new RefactoringStatus();
        status.addProblem(problem);
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getStatus()).thenReturn(status);
        return refactoring;
    }

    private static String preview(List<IRefactoring> refactorings, Map<String, ?> exactMatches) throws Exception
    {
        return (String)method("renderPreview", String.class, String.class, String.class, //$NON-NLS-1$
            java.util.Collection.class, int.class, Map.class, List.class).invoke(new MetadataRenameService(),
                "Catalog.Goods", "Items", "Goods", refactorings, 0, exactMatches, List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String[] row(String preview, int index)
    {
        String prefix = "| " + index + " | "; //$NON-NLS-1$ //$NON-NLS-2$
        String line = Arrays.stream(preview.split("\\R")).filter(value -> value.startsWith(prefix)) //$NON-NLS-1$
            .findFirst().orElseThrow(() -> new AssertionError(preview));
        return Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1)).map(String::trim) //$NON-NLS-1$
            .toArray(String[]::new);
    }

    private static Method method(String name, Class<?>... parameters) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Constructor<?> constructor(String name) throws Exception
    {
        Class<?> type = Class.forName(MetadataRenameService.class.getName() + "$" + name); //$NON-NLS-1$
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor;
    }

    private static Object field(Object object, String name) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    /** The public accessors reflect EDT's text-search match wrapper. */
    private static final class ModelMatch
    {
        private final EObject object;
        private final EStructuralFeature feature;

        ModelMatch(EObject object, EStructuralFeature feature)
        {
            this.object = object;
            this.feature = feature;
        }

        public Optional<EObject> resolveMatchObject()
        {
            return Optional.of(object);
        }

        public EStructuralFeature getFeature()
        {
            return feature;
        }

        public int getTextOffset()
        {
            return 16;
        }
    }
}
