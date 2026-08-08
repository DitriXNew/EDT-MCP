/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.eclipse.emf.ecore.util.EcoreEList;
import org.junit.Test;

/**
 * Pins the contract of {@link PersistedContents}: which contained children it yields, in which
 * order, and - the point of the class - that a derived or transient containment is recognized as
 * such BEFORE its value is read.
 *
 * <p>The fixture's computed features are real computations, not empty slots: each materializes its
 * value on read and counts every read, exactly like the EDT models where such a containment hands
 * back the whole BSL context or infers a standard-command set. A check placed after {@code eGet}
 * would drop the same children from the result and still pay for them - so the read counter, not
 * the returned list, is what distinguishes the two.</p>
 *
 * <p>Derived and transient are carried by SEPARATE features here. They are not the same flag and
 * they do not travel together: every computed containment the live EDT form root answers with
 * ({@code formContext}, {@code commands}, {@code commandPanelGlobalCommandSource}) is
 * {@code transient} with {@code derived=false}, so a check that only asked about {@code derived}
 * would skip nothing at all.</p>
 */
public class PersistedContentsTest
{
    @Test
    public void testYieldsPersistedChildrenInMetamodelOrder()
    {
        Model model = new Model();
        List<EObject> children = PersistedContents.of(model.parent);

        // 'single' is declared before 'many', and the list keeps its own order inside it.
        assertEquals(3, children.size());
        assertSame(model.single, children.get(0));
        assertSame(model.first, children.get(1));
        assertSame(model.second, children.get(2));
    }

    @Test
    public void testSkipsATransientContainmentWithoutReadingIt()
    {
        // The shape the EDT form model actually uses: transient, derived=false.
        Model model = new Model();
        List<EObject> children = PersistedContents.of(model.parent);

        assertFalse("a transient containment's children must not be yielded", //$NON-NLS-1$
            children.contains(model.transientChild));
        assertEquals("nor may the feature be evaluated at all", //$NON-NLS-1$
            0, model.parent.readsOf(model.transientRef));
    }

    @Test
    public void testSkipsADerivedContainmentWithoutReadingIt()
    {
        // ...and the other flag on its own, so neither check can be dropped unnoticed.
        Model model = new Model();
        List<EObject> children = PersistedContents.of(model.parent);

        assertFalse("a derived containment's children must not be yielded", //$NON-NLS-1$
            children.contains(model.derivedChild));
        assertEquals("nor may the feature be evaluated at all", //$NON-NLS-1$
            0, model.parent.readsOf(model.derivedRef));
    }

    @Test
    public void testSkipsNonContainmentReferencesWithoutReadingThem()
    {
        // The cross-reference points at a real object, but a reference is not a child - and, like
        // the computed containments, it is refused before it is read rather than read and dropped.
        Model model = new Model();

        assertFalse(PersistedContents.of(model.parent).contains(model.referenced));
        assertEquals(0, model.parent.readsOf(model.crossReference));
    }

    @Test
    public void testNullParentYieldsAFreshEmptyList()
    {
        List<EObject> children = PersistedContents.of(null);
        assertTrue(children.isEmpty());
        // The documented contract is a caller-OWNED list, so the null branch may not hand back a
        // shared immutable one: a caller that collects into the result would fail only there.
        children.add(new DynamicEObjectImpl(EcorePackage.Literals.ECLASS));
        assertEquals(1, children.size());
    }

    @Test
    public void testDescendantsWalkDepthFirstInMetamodelOrder()
    {
        // The sequence the callers depend on: the same depth-first pre-order eAllContents() gives,
        // over the persisted containments only. 'grand' sits under 'first', so it must come between
        // 'first' and 'second' - a breadth-first or reversed walk would put it last.
        Model model = new Model();

        assertEquals(Arrays.asList(model.single, model.first, model.grand, model.second),
            collect(PersistedContents.descendants(model.parent)));
    }

    @Test
    public void testDescendantsSkipComputedBranchesWithoutReadingThem()
    {
        Model model = new Model();
        List<EObject> all = collect(PersistedContents.descendants(model.parent));

        assertFalse(all.contains(model.derivedChild));
        assertFalse(all.contains(model.transientChild));
        assertEquals(0, model.parent.readsOf(model.derivedRef));
        assertEquals(0, model.parent.readsOf(model.transientRef));
    }

    @Test
    public void testDescendantsExpandANodeOnlyWhenTheWalkReachesIt()
    {
        // Laziness has to be observed, not asserted: an eager implementation that collected all four
        // descendants into a list would satisfy every "is it in the result" check just as well. So
        // the probe is a READ counter on a node the walk has not got to yet - 'second' is the last
        // sibling, and its own children may not be looked up while the caller is still on the first.
        Model model = new Model();

        Iterator<EObject> iterator = PersistedContents.descendants(model.parent).iterator();
        assertTrue(iterator.hasNext());
        assertSame(model.single, iterator.next());
        assertEquals("a later sibling must not be expanded before the walk reaches it", //$NON-NLS-1$
            0, model.second.readsOf(model.subReference));

        // ...and once the walk does reach it, it is expanded - otherwise the counter above would
        // read 0 for a walk that never descends at all, and prove nothing.
        while (iterator.hasNext())
        {
            iterator.next();
        }
        assertTrue("the walk must expand the node once it reaches it", //$NON-NLS-1$
            model.second.readsOf(model.subReference) > 0);
    }

    @Test
    public void testDescendantsAreRestartable()
    {
        // The Iterable is asked for a FRESH iterator each time, so a second traversal is not empty:
        // the two retype guards walk the same form one after the other, and a one-shot Iterable
        // would make the second of them silently answer "nothing blocks it".
        Model model = new Model();
        Iterable<EObject> descendants = PersistedContents.descendants(model.parent);

        assertEquals(4, collect(descendants).size());
        assertEquals(4, collect(descendants).size());
    }

    @Test
    public void testDescendantsOfNullIsAnEmptySequence()
    {
        assertTrue(collect(PersistedContents.descendants(null)).isEmpty());
    }

    private static List<EObject> collect(Iterable<EObject> objects)
    {
        List<EObject> all = new ArrayList<>();
        for (EObject object : objects)
        {
            all.add(object);
        }
        return all;
    }

    /**
     * A dynamic object whose computed containments are materialized on read, counting every read
     * (through {@code eGet} and through {@code eIsSet} alike - a derived feature computes to answer
     * either one).
     */
    private static final class ComputingEObject extends DynamicEObjectImpl
    {
        private final Map<EStructuralFeature, List<EObject>> computed = new HashMap<>();
        private final Map<EStructuralFeature, Integer> reads = new HashMap<>();

        ComputingEObject(EClass eClass)
        {
            super(eClass);
        }

        void compute(EReference feature, EObject value)
        {
            computed.computeIfAbsent(feature, key -> new ArrayList<>()).add(value);
        }

        int readsOf(EStructuralFeature feature)
        {
            Integer count = reads.get(feature);
            return count == null ? 0 : count.intValue();
        }

        private void count(EStructuralFeature feature)
        {
            reads.merge(feature, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + 1));
        }

        @Override
        public Object eGet(EStructuralFeature feature, boolean resolve, boolean coreType)
        {
            count(feature);
            List<EObject> value = computed.get(feature);
            if (value != null)
            {
                return new EcoreEList.UnmodifiableEList<EObject>(this, feature, value.size(),
                    value.toArray());
            }
            return super.eGet(feature, resolve, coreType);
        }

        @Override
        public boolean eIsSet(EStructuralFeature feature)
        {
            List<EObject> value = computed.get(feature);
            if (value != null)
            {
                count(feature);
                return !value.isEmpty();
            }
            return super.eIsSet(feature);
        }
    }

    /**
     * A parent carrying one single-valued and one many-valued persisted containment, a
     * derived-ONLY and a transient-ONLY computed containment, and one plain (non-containment)
     * reference. {@code first} owns a child of its own, so the descendant walk has a depth to get
     * wrong.
     */
    private static final class Model
    {
        final ComputingEObject parent;
        final EObject single;
        final EObject first;
        final EObject grand;
        /** Counting, so a test can watch WHEN the walk expands it. */
        final ComputingEObject second;
        final EReference subReference;
        final EReference derivedRef;
        final EObject derivedChild;
        final EReference transientRef;
        final EObject transientChild;
        final EReference crossReference;
        final EObject referenced;

        @SuppressWarnings("unchecked")
        Model()
        {
            EcoreFactory f = EcoreFactory.eINSTANCE;
            EPackage pkg = f.createEPackage();
            pkg.setName("persisted"); //$NON-NLS-1$
            pkg.setNsPrefix("persisted"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/persisted-contents"); //$NON-NLS-1$

            EClass child = f.createEClass();
            child.setName("Child"); //$NON-NLS-1$
            EAttribute name = f.createEAttribute();
            name.setName("name"); //$NON-NLS-1$
            name.setEType(EcorePackage.Literals.ESTRING);
            child.getEStructuralFeatures().add(name);
            subReference = containment(f, "sub", child, true); //$NON-NLS-1$
            child.getEStructuralFeatures().add(subReference);

            EClass parentClass = f.createEClass();
            parentClass.setName("Parent"); //$NON-NLS-1$
            parentClass.getEStructuralFeatures().add(containment(f, "single", child, false)); //$NON-NLS-1$
            parentClass.getEStructuralFeatures().add(containment(f, "many", child, true)); //$NON-NLS-1$
            derivedRef = containment(f, "derivedChildren", child, true); //$NON-NLS-1$
            derivedRef.setDerived(true);
            derivedRef.setVolatile(true);
            parentClass.getEStructuralFeatures().add(derivedRef);
            transientRef = containment(f, "transientChildren", child, true); //$NON-NLS-1$
            transientRef.setTransient(true);
            parentClass.getEStructuralFeatures().add(transientRef);
            crossReference = f.createEReference();
            crossReference.setName("peer"); //$NON-NLS-1$
            crossReference.setEType(child);
            parentClass.getEStructuralFeatures().add(crossReference);

            pkg.getEClassifiers().add(child);
            pkg.getEClassifiers().add(parentClass);

            parent = new ComputingEObject(parentClass);
            single = new DynamicEObjectImpl(child);
            parent.eSet(parentClass.getEStructuralFeature("single"), single); //$NON-NLS-1$
            first = new DynamicEObjectImpl(child);
            second = new ComputingEObject(child);
            List<EObject> many =
                (List<EObject>)parent.eGet(parentClass.getEStructuralFeature("many")); //$NON-NLS-1$
            many.add(first);
            many.add(second);
            grand = new DynamicEObjectImpl(child);
            ((List<EObject>)first.eGet(subReference)).add(grand);
            // 'second' is left childless on purpose: the laziness probe watches whether its 'sub'
            // feature is READ, and an empty answer is still a read. The counter is deliberately NOT
            // reset here - a read during construction should fail the probe, not be hidden from it.
            derivedChild = new DynamicEObjectImpl(child);
            parent.compute(derivedRef, derivedChild);
            transientChild = new DynamicEObjectImpl(child);
            parent.compute(transientRef, transientChild);
            referenced = new DynamicEObjectImpl(child);
            parent.eSet(crossReference, referenced);
        }

        private static EReference containment(EcoreFactory f, String featureName, EClass type,
            boolean many)
        {
            EReference reference = f.createEReference();
            reference.setName(featureName);
            reference.setEType(type);
            reference.setContainment(true);
            reference.setUpperBound(many ? -1 : 1);
            return reference;
        }
    }
}
