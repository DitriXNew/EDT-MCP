/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Finds an adopted form in the transaction's stored owner, without the global metadata scope. */
public final class AdoptedFormLookup
{
    private AdoptedFormLookup()
    {
    }

    /** The caller must hold the extension's BM transaction; no model object escapes that boundary. */
    public static MdObject find(IBmTransaction tx, BasicForm source)
    {
        if (source instanceof CommonForm)
        {
            return findTop(tx, (MdObject)source);
        }
        MdObject storedOwner = findOwner(tx, source);
        EStructuralFeature feature = source.eContainingFeature();
        if (storedOwner == null)
        {
            return null;
        }
        // ModelObjectAdopter.addChild writes this exact containment on its transaction-local owner.
        Object contents = storedOwner.eGet(feature);
        return contents instanceof Iterable<?> ? select(tx, (MdObject)source, (Iterable<?>)contents) : null;
    }

    static MdObject findOwner(IBmTransaction tx, BasicForm source)
    {
        EObject baseOwner = source.eContainer();
        EStructuralFeature feature = source.eContainingFeature();
        if (!(baseOwner instanceof MdObject) || feature == null || !feature.isMany())
        {
            return null;
        }
        return findTop(tx, (MdObject)baseOwner);
    }

    private static MdObject findTop(IBmTransaction tx, MdObject source)
    {
        String fqn = source instanceof CommonForm ? MetadataNodeResolver.addressOf(source)
            : MetadataNodeResolver.resolvableAddressOf(source);
        IBmObject named = fqn == null ? null : tx.getTopObjectByFqn(fqn);
        MdObject match = named instanceof MdObject && usable(tx, source, (MdObject)named)
            && (((MdObject)named).getExtendedConfigurationObject() == null || matches(source, (MdObject)named))
                ? (MdObject)named : null;
        if (match != null && (source.getUuid() == null || match.getExtendedConfigurationObject() != null))
        {
            return match;
        }
        // A renamed ID-mapped owner/common form keeps its origin link, not its base address.
        IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
        if (root instanceof Configuration && source.getUuid() != null)
        {
            Iterable<? extends MdObject> objects = MetadataTypeUtils.getObjects((Configuration)root, source.eClass().getName());
            if (objects != null)
            {
                return select(tx, source, objects);
            }
        }
        return match;
    }

    private static MdObject select(IBmTransaction tx, MdObject source, Iterable<?> contents)
    {
        MdObject byId = null;
        MdObject byName = null;
        boolean ambiguousName = false;
        UUID uuid = source.getUuid();
        for (Object item : contents)
        {
            if (!(item instanceof MdObject) || !usable(tx, source, (MdObject)item))
            {
                continue;
            }
            MdObject candidate = (MdObject)item;
            UUID link = candidate.getExtendedConfigurationObject();
            if (uuid != null && uuid.equals(link))
            {
                if (byId != null)
                {
                    return null;
                }
                byId = candidate;
            }
            else if (link == null && sameName(source, candidate))
            {
                ambiguousName |= byName != null;
                byName = candidate;
            }
        }
        return byId != null ? byId : ambiguousName ? null : byName;
    }

    private static boolean usable(IBmTransaction tx, MdObject source, MdObject candidate)
    {
        return !candidate.eIsProxy() && candidate.eClass() == source.eClass()
            && ExtensionServices.isAdopted(candidate) && candidate instanceof IBmObject && BmObjectOwnership.isPrimary(tx, (IBmObject)candidate);
    }

    private static boolean matches(MdObject source, MdObject candidate)
    {
        UUID link = candidate.getExtendedConfigurationObject();
        return link != null ? link.equals(source.getUuid()) : sameName(source, candidate);
    }

    private static boolean sameName(MdObject source, MdObject candidate)
    {
        return source.getName() != null && source.getName().equalsIgnoreCase(candidate.getName());
    }
}
