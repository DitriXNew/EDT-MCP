/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Field;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Table;

/**
 * The single-valued references that point at a Field CONTAINED in the object being modified
 * (issue #448): an external data source Table's {@code presentationField} and {@code parentField}
 * (and a cube DimensionTable's {@code presentationField}) - the only {@code refers Field} features in
 * {@code model/MdClass.xcore}. The Field lives inside its owner's BM top object, so its BM id
 * re-fetches inside the write transaction like a form's or a template's.
 *
 * <p>The rules are the EDT designer's own choice lists
 * ({@code FieldsSourceContentProvider$TablePresentationFieldsContentProvider} and
 * {@code $TableParentFieldsContentProvider} in {@code com._1c.g5.v8.dt.md.ui}): a presentation field is
 * any of the owner's own fields; a parent field is an own field whose type is exactly one type, and
 * that type belongs to the same Table (a reference to the Table itself). Anything else is refused,
 * so this does not open member references in general.</p>
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
     * Why {@code field} cannot be the value of {@code feature} on {@code owner}, or {@code null} when it can.
     *
     * @param prop the property name as supplied by the caller
     * @param feature the reference feature
     * @param owner the object being modified, may be {@code null}
     * @param field the resolved Field
     * @param fqn the reference value as supplied by the caller
     * @return the refusal text, or {@code null} when the field is usable
     */
    public static String refusal(String prop, EStructuralFeature feature, EObject owner, EObject field,
        String fqn)
    {
        if (owner == null || !sameObject(field.eContainer(), owner))
        {
            String ownerLabel = owner instanceof MdObject
                ? " '" + MetadataNodeResolver.addressOf((MdObject)owner) + "'" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            return "Reference target '" + fqn + "' for '" + prop + "' must be a Field of the object being " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "modified" + ownerLabel + "; EDT offers only that object's own fields here."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (feature == MdClassPackage.Literals.TABLE__PARENT_FIELD && !isSelfReference((Field)field, owner))
        {
            return "Reference target '" + fqn + "' for '" + prop + "' must be a Field whose type is exactly " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "one type, a reference to this same Table; EDT offers only such fields as the parent " //$NON-NLS-1$
                + "field. Give that field this Table's reference type in the EDT designer first."; //$NON-NLS-1$
        }
        return null;
    }

    /** The designer's parent-field filter: one type item whose nearest Table container is the owner. */
    private static boolean isSelfReference(Field field, EObject owner)
    {
        TypeDescription type = field.getType();
        List<TypeItem> items = type == null ? null : type.getTypes();
        if (items == null || items.size() != 1)
        {
            return false;
        }
        for (EObject at = items.get(0); at != null; at = at.eContainer())
        {
            if (at instanceof Table)
            {
                return sameObject(at, owner);
            }
        }
        return false;
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
