/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

/**
 * The PERSISTED contained children of an EMF object - the containments the model would write to
 * disk - in metamodel order.
 *
 * <p>{@link EObject#eContents()} is deliberately NOT that list. EMF builds it over
 * <em>every</em> containment reference of the object's {@code EClass} and evaluates each one
 * ({@code eIsSet}, then {@code eGet}); its own inclusion filter accepts derived and transient
 * features unconditionally. In the EDT models such a containment is not an empty slot but a
 * computation. Measured on an EDT 2026.2 form, the form root alone answers three of them:
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
}
