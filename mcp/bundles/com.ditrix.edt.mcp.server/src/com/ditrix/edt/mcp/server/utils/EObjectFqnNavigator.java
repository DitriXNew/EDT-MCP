/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.Collection;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

/**
 * Walks a dotted 1C FQN down the EMF containment tree to the EObject it names,
 * shared by the Navigator UI features ({@code tags} and {@code groups}) that store
 * object references as FQN strings.
 *
 * <p>The top-level object ({@code Type.Name}) is resolved by the caller (via
 * {@code IBmTransaction.getTopObjectByFqn}); this navigator handles the NESTED
 * remainder — pairs of {@code (SubTypeName, SubName)} such as
 * {@code Catalog.Files.CatalogAttribute.Width} — by matching each segment against the
 * parent's containment references by EClass type token + reflective {@code getName()}.
 *
 * <p>NOTE: the nested match uses the EClass type name, not the bilingual metadata type
 * token; aligning it with {@code MetadataNodeResolver} is tracked separately (see the
 * {@code ui-adapters-reinvent-fqn-eobject-resolution} backlog card).
 */
public final class EObjectFqnNavigator
{
    private EObjectFqnNavigator()
    {
    }

    /**
     * Resolves a nested object from a parent by navigating the FQN parts in
     * {@code (subTypeName, subName)} pairs.
     *
     * @param parent the parent EObject (a resolved top-level object)
     * @param parts the full FQN split on {@code '.'}
     * @param startIndex the index to start from (skip the top-level type and name,
     *        typically {@code 2})
     * @return the resolved nested EObject, or {@code null} if any segment is unresolved
     */
    public static EObject resolveNestedObject(EObject parent, String[] parts, int startIndex)
    {
        EObject current = parent;

        for (int i = startIndex; i < parts.length; i += 2) // Skip by 2 (SubTypeName.SubName)
        {
            if (i + 1 >= parts.length)
            {
                break;
            }

            String subTypeName = parts[i];
            String subName = parts[i + 1];

            // Try to find the child by navigating containment references
            EObject child = findChildByTypeAndName(current, subTypeName, subName);
            if (child == null)
            {
                return null;
            }
            current = child;
        }

        return current;
    }

    /**
     * Finds a child EObject under any of the parent's containment references whose
     * EClass type token and name match.
     */
    private static EObject findChildByTypeAndName(EObject parent, String typeName, String name)
    {
        for (EReference ref : parent.eClass().getEAllContainments())
        {
            Object value = parent.eGet(ref);
            if (value instanceof Collection<?> collection)
            {
                for (Object item : collection)
                {
                    if (item instanceof EObject child && matchesTypeAndName(child, typeName, name))
                    {
                        return child;
                    }
                }
            }
            else if (value instanceof EObject child && matchesTypeAndName(child, typeName, name))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * Checks if an EObject matches the given EClass type token and name.
     */
    private static boolean matchesTypeAndName(EObject obj, String typeName, String name)
    {
        String objTypeName = obj.eClass().getName();
        if (!objTypeName.equals(typeName) && !objTypeName.endsWith(typeName))
        {
            return false;
        }

        // Try to get name
        try
        {
            for (Method m : obj.getClass().getMethods())
            {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) //$NON-NLS-1$
                {
                    Object objName = m.invoke(obj);
                    return name.equals(objName);
                }
            }
        }
        catch (Exception e)
        {
            // Ignore
        }

        return false;
    }
}
