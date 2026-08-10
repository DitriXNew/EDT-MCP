/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

/**
 * The PERSISTED contained children of an EMF object - the containments the model would write to
 * disk - in metamodel order.
 *
 * <p>{@link EObject#eContents()} is deliberately NOT that list. EMF builds it over every ordinary
 * containment reference of the object's {@code EClass} and evaluates each one ({@code eIsSet}, then
 * {@code eGet}); its own inclusion filter accepts derived and transient features without asking
 * about either flag. (The exception is a reference assigned to a {@code FeatureMap} group: EMF
 * yields those children through the map instead, and the grouped reference itself is not evaluated.
 * The models this serves use no such grouping - see {@link #of}.)</p>
 *
 * <p>In the EDT models such a containment is not an empty slot but a computation. Measured on an
 * EDT 2026.2 form, the form root alone answers three of them:
 * {@code Form.formContext} hands back the whole BSL {@code ContextDef} (its types, properties,
 * methods, parameters and events), {@code FormStandardCommandSource.commands} infers the 22
 * standard commands, and {@code commandPanelGlobalCommandSource} materializes its marker - none
 * of which is authored and none of which reaches {@code Form.form}.</p>
 *
 * <p>Hence the ordering rule this class exists to enforce: the feature is asked whether it is
 * derived or transient <b>before</b> its value is read. Asking afterwards is no protection at
 * all - the model has already been computed by the time the answer arrives.</p>
 */
public final class PersistedContents
{
    private PersistedContents()
    {
    }

    /**
     * The persisted contained children of {@code parent}, in metamodel order (declaration order of
     * the containment references, then list order within each).
     *
     * <p>Non-containment references are skipped, as are derived and transient containments - the
     * check runs BEFORE {@code eGet}, so a computed containment is never triggered. Only ordinary
     * containment {@code EReference}s are followed: a containment held through a {@code FeatureMap}
     * (which {@code eContents()} would also yield) is not, and no EDT model this serves uses one.</p>
     *
     * <p>Precision about the two flags, because they do different jobs and are NOT interchangeable.
     * What keeps a feature out of the saved file is {@code transient}; {@code derived} on its own
     * only says the value is computed, and a derived-but-not-transient feature would still be
     * serialized. Both are skipped here, for different reasons: {@code transient} because such a
     * child is not part of the persisted model, {@code derived} because reading it is the very cost
     * this class exists to avoid. They are also not redundant - dropping the {@code transient} test
     * would skip nothing at all in the form metamodel, where every computed containment is declared
     * {@code transient} with {@code isDerived() == false}. What the metamodel happens not to contain
     * is the third combination, a derived containment that IS serialized; one appearing would need
     * this rule revisited.</p>
     *
     * @param parent the object whose containments to follow; {@code null} yields an empty list
     * @return a fresh, caller-owned list of the persisted children (never {@code null})
     */
    public static List<EObject> of(EObject parent)
    {
        List<EObject> children = new ArrayList<>();
        if (parent == null)
        {
            return children;
        }
        for (EReference reference : parent.eClass().getEAllReferences())
        {
            // Derived / transient BEFORE eGet: a derived feature can compute a whole model on read.
            if (!reference.isContainment() || reference.isDerived() || reference.isTransient())
            {
                continue;
            }
            Object value = parent.eGet(reference);
            if (value instanceof List<?>)
            {
                for (Object child : (List<?>)value)
                {
                    if (child instanceof EObject)
                    {
                        children.add((EObject)child);
                    }
                }
            }
            else if (value instanceof EObject)
            {
                children.add((EObject)value);
            }
        }
        return children;
    }

    /**
     * Every PERSISTED descendant of {@code root}, depth-first in metamodel order - what
     * {@link EObject#eAllContents()} yields, minus the objects only a computed containment leads to
     * (and, as {@link #of} notes, minus anything held through a {@code FeatureMap}), and without
     * evaluating those containments at all.
     *
     * <p>Filters NOTHING by EClass, deliberately. A caller that scans for a feature rather than for
     * a kind - a bound {@code dataPath}, an allocated {@code id} - has to see the unnamed property
     * holders too, so narrowing this to any one type would lose legitimate matches.</p>
     *
     * <p>LAZY, like the tree iterator it stands in for: a node is expanded when the walk reaches it,
     * not up front. What is held at any moment is the child list of each node on the current path -
     * bounded by fan-out times depth - and not one entry per descendant, which a materialized result
     * would be. It is NOT free, though: each visited node's persisted children are copied into a
     * fresh list once, where {@code eAllContents()} iterates the live {@code EList} in place.</p>
     *
     * <p>An explicit iterator stack, not recursion - a {@code StackOverflowError} is an
     * {@link Error} that no {@code catch (Exception)} above would stop. No node budget either: the
     * callers use this to decide whether something blocks an edit, and a walk that stopped early
     * would answer "nothing blocks it" about a form it never finished reading.</p>
     *
     * @param root the object to descend from; it is NOT itself included, and {@code null} yields an
     *     empty sequence
     * @return the descendants, iterable once per call (never {@code null})
     */
    public static Iterable<EObject> descendants(EObject root)
    {
        return () -> new DescendantIterator(root);
    }

    /** Depth-first pre-order over the persisted containments, expanding a node only when reached. */
    private static final class DescendantIterator implements Iterator<EObject>
    {
        private final Deque<Iterator<EObject>> levels = new ArrayDeque<>();

        DescendantIterator(EObject root)
        {
            if (root != null)
            {
                levels.push(of(root).iterator());
            }
        }

        @Override
        public boolean hasNext()
        {
            while (!levels.isEmpty() && !levels.peek().hasNext())
            {
                levels.pop();
            }
            return !levels.isEmpty();
        }

        @Override
        public EObject next()
        {
            if (!hasNext())
            {
                throw new NoSuchElementException();
            }
            EObject node = levels.peek().next();
            // Descend BEFORE the siblings are resumed - that is what makes the order depth-first.
            levels.push(of(node).iterator());
            return node;
        }
    }
}
