/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Read-only addresses and labels for top and contained BM objects. */
public final class BmObjectAddress
{
    private BmObjectAddress()
    {
        // utility class
    }

    /**
     * Returns the owning top object's FQN; bmGetFqn is legal only on attached top objects.
     *
     * @param object the top object or contained child, possibly null or transient
     * @return the owning top FQN, or null when EDT cannot resolve it
     */
    public static String topFqnOrNull(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        try
        {
            IBmObject top = object.bmIsTop() ? object : object.bmGetTopObject();
            return top == null ? null : top.bmGetFqn();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Returns the owning top FQN with recognized Form, Template or role Rights content suffixes
     * removed for get_metadata_details. Role type aliases are normalized to Role; other owner
     * tokens and names are preserved. The BM FQN and URI used for identity are not changed.
     *
     * @param object the top object or contained child, possibly null or transient
     * @return the owning metadata address, or null when EDT cannot resolve its top
     */
    public static String metadataAddressOrNull(IBmObject object)
    {
        String fqn = topFqnOrNull(object);
        if (fqn == null)
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 3 && parts.length != 5)
        {
            return fqn;
        }
        String content = parts[parts.length - 1];
        if (parts.length == 3 && "Role".equals(MetadataTypeUtils.toEnglishSingular(parts[0])) //$NON-NLS-1$
            && "Rights".equalsIgnoreCase(content)) //$NON-NLS-1$
        {
            return "Role." + parts[1]; //$NON-NLS-1$
        }
        String kind = null;
        if ("Form".equalsIgnoreCase(content) || "\u0424\u043e\u0440\u043c\u0430".equalsIgnoreCase(content)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            kind = "Form"; //$NON-NLS-1$
        }
        else if ("Template".equalsIgnoreCase(content) || "\u041c\u0430\u043a\u0435\u0442".equalsIgnoreCase(content)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            kind = "Template"; //$NON-NLS-1$
        }
        MetadataTypeUtils.NestedKindInfo ownerKind = parts.length == 5
            ? MetadataTypeUtils.resolveNestedKind(parts[2]) : null;
        if (kind != null && (parts.length == 3
            ? ("Common" + kind).equals(MetadataTypeUtils.toEnglishSingular(parts[0])) //$NON-NLS-1$
            : ownerKind != null && kind.equals(ownerKind.getEnglish())))
        {
            return fqn.substring(0, fqn.lastIndexOf('.'));
        }
        return fqn;
    }

    /**
     * Returns a referenceable BM URI, including the containment path of a child.
     *
     * @param object the top object or contained child, possibly null or transient
     * @return the BM URI, or null for an unavailable or unreferenceable address
     */
    public static String uriOrNull(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        try
        {
            URI uri = object.bmGetUri();
            return uri != null && BmUriUtil.isBmUri(uri)
                && !BmUriUtil.isUnreferenceableObjectFragment(uri.fragment()) ? uri.toString() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Returns the top FQN with a child's local metadata or Ecore name when available.
     * Preserves the labels used by delete_metadata.
     *
     * @param obj the top object or contained child, possibly null
     * @return the human-readable label, local name fallback, or null
     */
    public static String labelOrNull(IBmObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            if (obj.bmIsTop())
            {
                return obj.bmGetFqn();
            }
        }
        catch (Exception e)
        {
            // fall through to top-object resolution
        }

        String localName = null;
        if (obj instanceof MdObject mdo)
        {
            localName = mdo.getName();
        }
        else if (obj instanceof org.eclipse.emf.ecore.ENamedElement ene)
        {
            localName = ene.getName();
        }

        try
        {
            IBmObject top = obj.bmGetTopObject();
            if (top != null && top != obj)
            {
                String topFqn = top.bmGetFqn();
                if (topFqn != null)
                {
                    return (localName != null && !localName.isEmpty())
                        ? topFqn + " (" + localName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                        : topFqn;
                }
            }
        }
        catch (Exception e)
        {
            // ignore - fall back to the local name (or null)
        }
        return localName;
    }
}
