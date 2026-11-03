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
            IBmObject candidate = tx.getTopObjectByFqn(MetadataNodeResolver.addressOf((MdObject)source));
            return candidate instanceof MdObject && usable(tx, source, (MdObject)candidate)
                && matches(source, (MdObject)candidate) ? (MdObject)candidate : null;
        }
        EObject baseOwner = source.eContainer();
        EStructuralFeature feature = source.eContainingFeature();
        if (!(baseOwner instanceof MdObject) || feature == null || !feature.isMany())
        {
            return null;
        }
        String ownerFqn = MetadataNodeResolver.resolvableAddressOf((MdObject)baseOwner);
        IBmObject storedOwner = ownerFqn == null ? null : tx.getTopObjectByFqn(ownerFqn);
        if (!(storedOwner instanceof MdObject) || storedOwner.eClass() != baseOwner.eClass()
            || !ExtensionServices.isAdopted((EObject)storedOwner) || !primary(tx, storedOwner))
        {
            return null;
        }
        // ModelObjectAdopter.addChild writes this exact containment on its transaction-local owner.
        Object contents = ((EObject)storedOwner).eGet(feature);
        if (!(contents instanceof Iterable<?>))
        {
            return null;
        }
        MdObject byId = null;
        MdObject byName = null;
        boolean ambiguousName = false;
        UUID uuid = ((MdObject)source).getUuid();
        for (Object item : (Iterable<?>)contents)
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
            else if (link == null && sameName((MdObject)source, candidate))
            {
                ambiguousName |= byName != null;
                byName = candidate;
            }
        }
        return byId != null ? byId : ambiguousName ? null : byName;
    }

    private static boolean usable(IBmTransaction tx, BasicForm source, MdObject candidate)
    {
        return !candidate.eIsProxy() && candidate.eClass() == source.eClass()
            && ExtensionServices.isAdopted(candidate) && primary(tx, (IBmObject)candidate);
    }

    private static boolean primary(IBmTransaction tx, IBmObject object)
    {
        return object.bmGetId() != IBmObject.BM_NULL_ID
            && tx.getPlatformTransaction().getContainingDataStore(object) == tx.getNamespace().getPrimaryStore();
    }

    private static boolean matches(BasicForm source, MdObject candidate)
    {
        UUID link = candidate.getExtendedConfigurationObject();
        return link != null ? link.equals(((MdObject)source).getUuid()) : sameName((MdObject)source, candidate);
    }

    private static boolean sameName(MdObject source, MdObject candidate)
    {
        return source.getName() != null && source.getName().equalsIgnoreCase(candidate.getName());
    }
}
