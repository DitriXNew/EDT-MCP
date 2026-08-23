/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;


import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.metadata.dbview.DbViewTableDef;

/** Determinism and structural-sensitivity tests for {@link DcsHash}. */
public class DcsHashTest
{
    @Test
    public void testSameModelProducesSameShortHash()
    {
        Model model = model("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        String first = DcsHash.compute(model.root);
        String second = DcsHash.compute(model.root);
        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{20}")); //$NON-NLS-1$
    }

    @Test
    public void testChangedAttributeChangesHash()
    {
        Model model = model("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = DcsHash.compute(model.root);
        model.children.get(0).eSet(model.name, "Changed"); //$NON-NLS-1$
        assertNotEquals(before, DcsHash.compute(model.root));
    }

    @Test
    public void testReorderedContainmentChangesHash()
    {
        Model model = model("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = DcsHash.compute(model.root);
        EObject first = model.children.remove(0);
        model.children.add(first);
        assertNotEquals(before, DcsHash.compute(model.root));
    }

    @Test
    public void testAddedNodeChangesHash()
    {
        Model model = model("A", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        String before = DcsHash.compute(model.root);
        EObject added = EcoreUtil.create(model.childClass);
        added.eSet(model.name, "C"); //$NON-NLS-1$
        model.children.add(added);
        assertNotEquals(before, DcsHash.compute(model.root));
    }

    @Test
    public void testDynamicListSettingsContentMovesEveryToolStageHash()
    {
        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionSelectedFields selection = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedFields();
        selection.getItems().add(selectedField("First")); //$NON-NLS-1$
        selection.getItems().add(selectedField("Second")); //$NON-NLS-1$
        settings.setSelection(selection);
        dynamic.setListSettings(settings);

        String getHash = DcsHash.compute(dynamic);
        assertEquals("an unchanged dynamic list must hash stably", getHash, //$NON-NLS-1$
            DcsHash.compute(dynamic));

        // EMF's EList enforces 'no duplicates', and Collections.swap sets one slot before the
        // other - which transiently duplicates an element and throws. move() is EMF's own
        // reorder, and it is what a designer reorder does to the model.
        selection.getItems().move(0, 1);
        String expectedHashCheck = DcsHash.compute(dynamic);
        assertNotEquals("reordering authored listSettings must stale the hash returned by get", //$NON-NLS-1$
            getHash, expectedHashCheck);

        String returnedWriteHash = DcsHash.compute(dynamic);
        assertEquals("the post-write hash must describe the same reordered settings content", //$NON-NLS-1$
            expectedHashCheck, returnedWriteHash);
    }

    @Test
    public void testDynamicListMainTableRemainsIdentityHashed()
    {
        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DbViewTableDef products = DbViewFactory.eINSTANCE.createDbViewTableDef();
        products.setName("Catalog.Products"); //$NON-NLS-1$
        DbViewTableDef partners = DbViewFactory.eINSTANCE.createDbViewTableDef();
        partners.setName("Catalog.Partners"); //$NON-NLS-1$

        dynamic.setMainTable(products);
        String productsHash = DcsHash.compute(dynamic);
        assertEquals(productsHash, DcsHash.compute(dynamic));

        dynamic.setMainTable(partners);
        assertNotEquals("repointing external mainTable identity must move the hash", //$NON-NLS-1$
            productsHash, DcsHash.compute(dynamic));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRepointedNonContainmentReferenceChangesHash()
    {
        // The fingerprint walked attributes and CONTAINMENT only. A non-containment reference is
        // still modelled, still rendered by the reader and still writable - DynamicListExtInfo's
        // mainTable is the live example - so a concurrent writer could repoint one and the hash
        // would not move. The get-edit-verify loop would then certify, with a matching
        // expectedHash, a node that had changed underneath it.
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("refTest"); //$NON-NLS-1$
        pkg.setNsPrefix("refTest"); //$NON-NLS-1$
        pkg.setNsURI("urn:edt-mcp:dcs-hash-ref-test"); //$NON-NLS-1$

        EClass targetClass = EcoreFactory.eINSTANCE.createEClass();
        targetClass.setName("Target"); //$NON-NLS-1$
        EAttribute targetName = EcoreFactory.eINSTANCE.createEAttribute();
        targetName.setName("name"); //$NON-NLS-1$
        targetName.setEType(EcorePackage.Literals.ESTRING);
        targetClass.getEStructuralFeatures().add(targetName);

        EClass rootClass = EcoreFactory.eINSTANCE.createEClass();
        rootClass.setName("Root"); //$NON-NLS-1$
        EReference owned = EcoreFactory.eINSTANCE.createEReference();
        owned.setName("owned"); //$NON-NLS-1$
        owned.setContainment(true);
        owned.setUpperBound(EStructuralFeatureUnlimited.UNBOUNDED);
        owned.setEType(targetClass);
        rootClass.getEStructuralFeatures().add(owned);
        EReference mainTarget = EcoreFactory.eINSTANCE.createEReference();
        mainTarget.setName("mainTarget"); //$NON-NLS-1$
        mainTarget.setContainment(false);
        mainTarget.setEType(targetClass);
        rootClass.getEStructuralFeatures().add(mainTarget);
        pkg.getEClassifiers().add(rootClass);
        pkg.getEClassifiers().add(targetClass);

        EObject root = EcoreUtil.create(rootClass);
        EList<EObject> targets = (EList<EObject>)root.eGet(owned);
        EObject first = EcoreUtil.create(targetClass);
        first.eSet(targetName, "Catalog.Products"); //$NON-NLS-1$
        EObject second = EcoreUtil.create(targetClass);
        second.eSet(targetName, "Catalog.Partners"); //$NON-NLS-1$
        targets.add(first);
        targets.add(second);

        root.eSet(mainTarget, first);
        String pointingAtFirst = DcsHash.compute(root);
        assertEquals("the same reference must hash the same", //$NON-NLS-1$
            pointingAtFirst, DcsHash.compute(root));

        root.eSet(mainTarget, second);
        assertNotEquals("repointing a non-containment reference must move the hash", //$NON-NLS-1$
            pointingAtFirst, DcsHash.compute(root));
    }

    @SuppressWarnings("unchecked")
    private static Model model(String... names)
    {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("hashTest"); //$NON-NLS-1$
        pkg.setNsPrefix("hashTest"); //$NON-NLS-1$
        pkg.setNsURI("urn:edt-mcp:dcs-hash-test"); //$NON-NLS-1$

        EClass rootClass = EcoreFactory.eINSTANCE.createEClass();
        rootClass.setName("Root"); //$NON-NLS-1$
        EClass childClass = EcoreFactory.eINSTANCE.createEClass();
        childClass.setName("Child"); //$NON-NLS-1$
        EAttribute name = EcoreFactory.eINSTANCE.createEAttribute();
        name.setName("name"); //$NON-NLS-1$
        name.setEType(EcorePackage.Literals.ESTRING);
        childClass.getEStructuralFeatures().add(name);
        EReference children = EcoreFactory.eINSTANCE.createEReference();
        children.setName("children"); //$NON-NLS-1$
        children.setContainment(true);
        children.setUpperBound(EStructuralFeatureUnlimited.UNBOUNDED);
        children.setEType(childClass);
        rootClass.getEStructuralFeatures().add(children);
        pkg.getEClassifiers().add(rootClass);
        pkg.getEClassifiers().add(childClass);

        EObject root = EcoreUtil.create(rootClass);
        EList<EObject> values = (EList<EObject>)root.eGet(children);
        for (String value : names)
        {
            EObject child = EcoreUtil.create(childClass);
            child.eSet(name, value);
            values.add(child);
        }
        return new Model(root, childClass, name, values);
    }

    private static DataCompositionSelectedField selectedField(String value)
    {
        DataCompositionSelectedField selected = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedField();
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionField();
        field.setValue(value);
        selected.setField(field);
        return selected;
    }

    /** Keeps the EMF constant out of the test's model-building flow. */
    private static final class EStructuralFeatureUnlimited
    {
        static final int UNBOUNDED = -1;

        private EStructuralFeatureUnlimited()
        {
        }
    }

    private static final class Model
    {
        final EObject root;
        final EClass childClass;
        final EAttribute name;
        final EList<EObject> children;

        Model(EObject root, EClass childClass, EAttribute name, EList<EObject> children)
        {
            this.root = root;
            this.childClass = childClass;
            this.name = name;
            this.children = children;
        }
    }
}
