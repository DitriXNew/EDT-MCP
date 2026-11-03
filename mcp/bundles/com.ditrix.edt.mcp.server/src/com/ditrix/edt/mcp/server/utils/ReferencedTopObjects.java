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
        "tables", "parentDataSource", "cubes", "parentDataSource", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "dimensionTables", "parentCube"); //$NON-NLS-1$ //$NON-NLS-2$

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

    /**
     * File owner of a resolved metadata address, preserving its spelling. The shared child grammar
     * and referenced-top collection rule identify each separate root; contained members keep the
     * last such root. Other addresses retain their leading Type.Name owner.
     */
    public static String fileOwnerFqn(String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length <= 2)
        {
            return fqn;
        }
        String fileOwner = parts[0] + "." + parts[1]; //$NON-NLS-1$
        String typeName = MetadataTypeUtils.toEnglishSingular(parts[0]);
        Object type = typeName == null ? null : MdClassPackage.eINSTANCE.getEClassifier(typeName);
        EClass ownerType = type instanceof EClass ? (EClass)type : null;
        String address = fileOwner;
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            EReference child = MetadataNodeResolver.childFeature(ownerType, parts[i]);
            if (child == null)
            {
                break;
            }
            address += '.' + parts[i] + '.' + parts[i + 1];
            if (isReferencedTopCollection(child))
            {
                fileOwner = address;
            }
            ownerType = child.getEReferenceType();
        }
        return fileOwner;
    }

    /** The folder of a supported referenced top class. */
    public static String folderFor(EClass type)
    {
        if ("Table".equals(type.getName())) //$NON-NLS-1$
        {
            return "Tables"; //$NON-NLS-1$
        }
        if ("Cube".equals(type.getName())) //$NON-NLS-1$
        {
            return "Cubes"; //$NON-NLS-1$
        }
        return "DimensionTable".equals(type.getName()) ? "DimensionTables" : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Project-relative file of an EDS referenced top object, or null for any other address. */
    public static String mdoPath(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String[] p = MetadataTypeUtils.normalizeFqn(fqn).split("\\.", -1); //$NON-NLS-1$
        if (p.length < 4 || !MetadataNodeResolver.isValidArity(p.length) || !"ExternalDataSource".equals(p[0])) //$NON-NLS-1$
        {
            return null;
        }
        EClass ownerType = MdClassPackage.Literals.EXTERNAL_DATA_SOURCE;
        StringBuilder path = new StringBuilder("src/ExternalDataSources/").append(p[1]); //$NON-NLS-1$
        for (int i = 2; i < p.length; i += 2)
        {
            EReference feature = MetadataNodeResolver.childFeature(ownerType, p[i]);
            if (!isReferencedTopCollection(feature))
            {
                return null;
            }
            ownerType = feature.getEReferenceType();
            String folder = folderFor(ownerType);
            if (folder == null)
            {
                return null;
            }
            path.append('/').append(folder).append('/').append(p[i + 1]);
        }
        return path.append('/').append(p[p.length - 1]).append(".mdo").toString(); //$NON-NLS-1$
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
