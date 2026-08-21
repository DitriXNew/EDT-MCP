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
