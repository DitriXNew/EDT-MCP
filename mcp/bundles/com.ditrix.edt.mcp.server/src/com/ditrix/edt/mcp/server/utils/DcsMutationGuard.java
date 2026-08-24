/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;

/** Safety checks shared by destructive DCS mutations. */
public final class DcsMutationGuard
{
    private DcsMutationGuard()
    {
        // Utility class
    }

    /** Refuses authoritative replacement when it would discard an unmodellable descendant. */
    public static String replaceError(EObject root, DcsAddress target)
    {
        if (root == null || target == null) return null;
        String targetAddress = target.toString();
        List<String> blocked = new ArrayList<>();
        for (String node : DcsReadProjection.unmodellableNodes(root, target.rootFqn()))
        {
            int marker = node.indexOf(" at "); //$NON-NLS-1$
            String address = marker < 0 ? "" : node.substring(marker + 4); //$NON-NLS-1$
            if (!target.hasPointer() || address.equals(targetAddress)
                || address.startsWith(targetAddress + "/")) //$NON-NLS-1$
            {
                blocked.add(node);
            }
        }
        if (blocked.isEmpty()) return null;
        return "action='replace' refuses target '" + target //$NON-NLS-1$
            + "' because it contains content this writer cannot model: " //$NON-NLS-1$
            + String.join(", ", blocked) //$NON-NLS-1$
            + ". Remove or relocate those nodes in the DCS designer, or replace a narrower " //$NON-NLS-1$
            + "fully modelled node."; //$NON-NLS-1$
    }

    /** Refuses identity removal/rename while canonical referring nodes still exist. */
    public static String referenceError(EObject root, DcsAddress target, String kind,
        String identity)
    {
        String rootAddress = target == null ? null : target.rootFqn();
        return referenceError(root, rootAddress, target, kind, identity);
    }

    /** Refuses identity removal/rename within a subtree rooted at a canonical DCS address. */
    public static String referenceError(EObject root, String rootAddress, DcsAddress target,
        String kind, String identity)
    {
        if (root == null || rootAddress == null || target == null) return null;
        List<String> references = DcsReadProjection.referenceAddressesAt(root, rootAddress, kind,
            identity);
        String targetAddress = target.toString();
        references.removeIf(address -> address.equals(targetAddress)
            || address.startsWith(targetAddress + "/")); //$NON-NLS-1$
        if (references.isEmpty()) return null;
        return "Cannot remove or rename " + kind + " '" + identity + "' at '" + target //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "' because these nodes still refer to it: " + String.join(", ", references) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Update or remove those referring nodes first, re-run get, then retry with its new hash."; //$NON-NLS-1$
    }
}
