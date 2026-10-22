/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The single-valued references that point at a Field CONTAINED in the object being modified
 * (issue #448): an external data source Table's {@code presentationField} and {@code parentField}
 * (and a cube DimensionTable's {@code presentationField}) - the only {@code refers Field} features in
 * {@code model/MdClass.xcore}. The Field lives inside its owner's BM top object, so its BM id
 * re-fetches inside the write transaction like a form's or a template's.
 *
 * <p>Both properties require one of the owner's own fields. The field's type is not checked,
 * so a parent field can be assigned before its type is set. This does not open member references
 * in general.</p>
 */
public final class TableFieldReferences
{
    private TableFieldReferences()
    {
    }

    /**
     * Whether {@code feature} is a single {@code refers Field} reference and {@code target} is a Field,
     * the case {@link #refusal} judges.
     *
     * @param feature the reference feature
     * @param target the resolved reference target
     * @return {@code true} for a Field target of a Field-typed single reference
     */
    public static boolean isFieldReference(EStructuralFeature feature, EObject target)
    {
        return feature instanceof EReference && !feature.isMany()
            && ((EReference)feature).getEReferenceType() == MdClassPackage.Literals.FIELD
            && target != null && MdClassPackage.Literals.FIELD.isSuperTypeOf(target.eClass());
    }

    /**
     * Why {@code field} cannot be used on {@code owner}, or {@code null} when it can.
     *
     * @param prop the property name as supplied by the caller
     * @param owner the object being modified, may be {@code null}
     * @param field the resolved Field
     * @param fqn the reference value as supplied by the caller
     * @return the refusal text, or {@code null} when the field is usable
     */
    public static String refusal(String prop, EObject owner, EObject field, String fqn)
    {
        if (owner == null || !sameObject(field.eContainer(), owner))
        {
            String ownerLabel = owner instanceof MdObject
                ? " '" + MetadataNodeResolver.addressOf((MdObject)owner) + "'" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            return "Reference target '" + fqn + "' for '" + prop + "' must be a Field of the object being " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "modified" + ownerLabel + "; EDT offers only that object's own fields here."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /** Identity, or the same BM object read through another view of the model. */
    private static boolean sameObject(EObject a, EObject b)
    {
        if (a == b)
        {
            return true;
        }
        return a instanceof IBmObject && b instanceof IBmObject
            && ((IBmObject)a).bmGetId() > 0 && ((IBmObject)a).bmGetId() == ((IBmObject)b).bmGetId();
    }
}
