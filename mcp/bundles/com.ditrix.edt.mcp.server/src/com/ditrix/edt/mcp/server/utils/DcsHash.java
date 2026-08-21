/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

/**
 * Computes a compact structural fingerprint for a resolved DCS or dynamic-list root.
 *
 * <p>The fingerprint is deliberately a within-session stale-address guard, not a serialized format
 * or a promise of stability across EDT/model versions. It records containment order, each node's
 * EClass, and every explicitly set attribute. A caller must compute it while the root is still inside
 * the same BM transaction boundary used to read it.</p>
 */
public final class DcsHash
{
    private static final int SHORT_HEX_LENGTH = 20;

    private DcsHash()
    {
        // utility class
    }

    /**
     * Fingerprints {@code root}. A missing external resource has its own stable value, distinct from
     * every materialized EObject tree.
     *
     * @param root resolved schema or dynamic-list ext-info, possibly {@code null}
     * @return a short lowercase SHA-256 prefix suitable for {@code expectedHash}
     */
    public static String compute(EObject root)
    {
        MessageDigest digest = sha256();
        if (root == null)
        {
            token(digest, "missing-root"); //$NON-NLS-1$
        }
        else
        {
            visit(digest, root, "root", 0); //$NON-NLS-1$
        }
        String hex = toHex(digest.digest());
        return hex.substring(0, SHORT_HEX_LENGTH);
    }

    private static void visit(MessageDigest digest, EObject object, String containmentFeature,
        int containmentIndex)
    {
        token(digest, "node"); //$NON-NLS-1$
        token(digest, containmentFeature);
        token(digest, Integer.toString(containmentIndex));
        token(digest, object.eClass().getName());

        for (EAttribute attribute : object.eClass().getEAllAttributes())
        {
            if (!object.eIsSet(attribute))
            {
                continue;
            }
            token(digest, "attribute"); //$NON-NLS-1$
            token(digest, attribute.getName());
            appendAttributeValue(digest, object.eGet(attribute));
        }

        for (EReference reference : object.eClass().getEAllContainments())
        {
            if (!object.eIsSet(reference))
            {
                continue;
            }
            Object value = object.eGet(reference);
            if (reference.isMany())
            {
                if (value instanceof List<?>)
                {
                    List<?> children = (List<?>)value;
                    for (int i = 0; i < children.size(); i++)
                    {
                        Object child = children.get(i);
                        if (child instanceof EObject)
                        {
                            visit(digest, (EObject)child, reference.getName(), i);
                        }
                    }
                }
            }
            else if (value instanceof EObject)
            {
                visit(digest, (EObject)value, reference.getName(), 0);
            }
        }
    }

    private static void appendAttributeValue(MessageDigest digest, Object value)
    {
        if (value instanceof EList<?> || value instanceof List<?>)
        {
            List<?> values = (List<?>)value;
            token(digest, "list-size"); //$NON-NLS-1$
            token(digest, Integer.toString(values.size()));
            for (int i = 0; i < values.size(); i++)
            {
                token(digest, Integer.toString(i));
                token(digest, canonicalValue(values.get(i)));
            }
            return;
        }
        token(digest, canonicalValue(value));
    }

    private static String canonicalValue(Object value)
    {
        if (value == null)
        {
            return "<null>"; //$NON-NLS-1$
        }
        if (value instanceof byte[])
        {
            return toHex((byte[])value);
        }
        return value.getClass().getName() + ':' + value.toString();
    }

    private static void token(MessageDigest digest, String value)
    {
        byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        digest.update((byte)(bytes.length >>> 24));
        digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8));
        digest.update((byte)bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e); //$NON-NLS-1$
        }
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
        {
            result.append(Character.forDigit(value >>> 4 & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
