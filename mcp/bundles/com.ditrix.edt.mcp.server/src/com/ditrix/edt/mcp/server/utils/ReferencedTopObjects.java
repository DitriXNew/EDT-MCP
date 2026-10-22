/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/** Separate BM top objects registered by a metadata owner's reference collection. */
public final class ReferencedTopObjects
{
    /** Both directions are serialized; these references are not EMF opposites. */
    public static final Map<String, String> BACK_REFERENCE_BY_COLLECTION = Map.of(
        "tables", "parentDataSource", "cubes", "parentDataSource"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private ReferencedTopObjects()
    {
    }

    /** Whether this collection registers a separate top object, rather than containing a member. */
    public static boolean isReferencedTopCollection(EReference feature)
    {
        return feature != null && !feature.isContainment() && feature.isMany()
            && BACK_REFERENCE_BY_COLLECTION.containsKey(feature.getName());
    }

    /** The canonical attach address uses the owner's stored spelling and the metamodel class. */
    public static String attachFqn(String ownerFqn, EClass type, String name)
    {
        return ownerFqn + '.' + type.getName() + '.' + name;
    }

    /** Register an already attached top object and set its independent parent back-reference. */
    @SuppressWarnings("unchecked")
    public static void link(EObject owner, EReference collection, EObject child)
    {
        if (!isReferencedTopCollection(collection))
        {
            throw new IllegalArgumentException("Not a referenced top-object collection"); //$NON-NLS-1$
        }
        EStructuralFeature parent = child.eClass().getEStructuralFeature(
            BACK_REFERENCE_BY_COLLECTION.get(collection.getName()));
        if (parent == null)
        {
            throw new IllegalArgumentException("Referenced top object has no parent back-reference"); //$NON-NLS-1$
        }
        ((EList<EObject>)owner.eGet(collection)).add(child);
        child.eSet(parent, owner);
    }

    /** Attach before writing references; finish with the same factory defaults as a normal top. */
    public static void attachAndLink(IBmTransaction tx, EObject owner, EReference collection,
        MdObject child, String fqn, IModelObjectFactory factory)
    {
        tx.attachTopObject((IBmObject)child, fqn);
        link(owner, collection, child);
        factory.fillDefaultReferences(child);
    }

    /** File owner read inside the transaction, including members of a referenced top object. */
    public static String fileOwnerFqn(EObject object)
    {
        IBmObject bm = (IBmObject)object;
        return (bm.bmIsTop() ? bm : bm.bmGetTopObject()).bmGetFqn();
    }

    /** The folder of each supported referenced top class: {@code Tables/<T>/} and {@code Cubes/<C>/}. */
    public static String folderFor(EClass type)
    {
        if ("Table".equals(type.getName())) //$NON-NLS-1$
        {
            return "Tables"; //$NON-NLS-1$
        }
        return "Cube".equals(type.getName()) ? "Cubes" : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Project-relative file of an EDS Table/Cube, or null for any other address. */
    public static String mdoPath(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String[] p = MetadataTypeUtils.normalizeFqn(fqn).split("\\.", -1); //$NON-NLS-1$
        if (p.length != 4 || !"ExternalDataSource".equals(p[0])) //$NON-NLS-1$
        {
            return null;
        }
        String feature = MetadataNodeResolver.featureNameForKind(p[2]);
        String kind = feature == null ? null : MetadataNodeResolver.kindTokenForFeature(feature);
        Object type = kind == null ? null : MdClassPackage.eINSTANCE.getEClassifier(kind);
        String folder = type instanceof EClass ? folderFor((EClass)type) : null;
        return folder == null ? null : "src/ExternalDataSources/" + p[1] + '/' + folder //$NON-NLS-1$
            + '/' + p[3] + '/' + p[3] + ".mdo"; //$NON-NLS-1$
    }

    /** Explicit boundary for operations whose member refactoring has not been verified for EDS. */
    public static String memberOperationRefusal(String operation, String fqn)
    {
        if (fqn == null || !MetadataTypeUtils.normalizeFqn(fqn).startsWith("ExternalDataSource.")) //$NON-NLS-1$
        {
            return null;
        }
        return fqn.split("\\.", -1).length > 2
            ? operation + " does not support ExternalDataSource members. Use the EDT designer " //$NON-NLS-1$
                + "for this operation; use create_metadata, modify_metadata, delete_metadata and " //$NON-NLS-1$
                + "get_metadata_details to describe their structure." : null; //$NON-NLS-1$
    }
}
