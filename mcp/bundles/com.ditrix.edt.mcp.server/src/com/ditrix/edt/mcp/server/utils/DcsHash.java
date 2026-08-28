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
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.form.model.FormPackage;

/**
 * Computes a compact structural fingerprint for a resolved DCS or dynamic-list root.
 *
 * <p>The fingerprint is deliberately a within-session stale-address guard, not a serialized format
 * or a promise of stability across EDT/model versions. It records containment order, each node's
 * EClass and every explicitly set attribute. A non-containment reference whose target the DCS tool
 * authors is fingerprinted by content; pointers to objects outside the tool's ownership boundary
 * are fingerprinted by identity. A caller must compute it while the root is still inside the same
 * BM transaction boundary used to read it.</p>
 */
public final class DcsHash
{
    private static final int SHORT_HEX_LENGTH = 20;

    /**
     * The explicit authoring-ownership boundary for non-containment references. Entries here are
     * owned subtrees that the tool mutates/replaces, so their complete content participates in the
     * hash. Every other non-containment target is an external pointer and contributes identity only.
     */
    private static final Set<EReference> CONTENT_HASHED_REFERENCES = Set.of(
        FormPackage.Literals.DYNAMIC_LIST_EXT_INFO__LIST_SETTINGS);

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

        // Non-containment references follow the authoring boundary declared above. Owned targets
        // contribute their complete raw model content; external targets contribute identity only.
        // Neither path resolves a localized presentation, so the hash never depends on language.
        for (EReference reference : object.eClass().getEAllReferences())
        {
            // The transient skip stays for every OTHER reference: a transient feature is not
            // persisted and may be derived or session-scoped, so folding those into the
            // fingerprint would make it drift on an unchanged model. listSettings is transient
            // AND authored, and it is admitted by the ownership set above - not by relaxing the
            // rule for everything.
            boolean authored = CONTENT_HASHED_REFERENCES.contains(reference);
            if (reference.isContainment() || reference.isDerived() || !object.eIsSet(reference)
                || reference.isTransient() && !authored)
            {
                continue;
            }
            token(digest, "reference"); //$NON-NLS-1$
            token(digest, reference.getName());
            Object value = object.eGet(reference);
            if (authored)
            {
                appendReferenceContent(digest, reference, value);
                continue;
            }
            if (value instanceof List<?>)
            {
                List<?> targets = (List<?>)value;
                token(digest, "list-size"); //$NON-NLS-1$
                token(digest, Integer.toString(targets.size()));
                for (int i = 0; i < targets.size(); i++)
                {
                    token(digest, Integer.toString(i));
                    token(digest, referenceIdentity(targets.get(i)));
                }
            }
            else
            {
                token(digest, referenceIdentity(value));
            }
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

    private static void appendReferenceContent(MessageDigest digest, EReference reference,
        Object value)
    {
        token(digest, "content"); //$NON-NLS-1$
        if (value instanceof List<?>)
        {
            List<?> targets = (List<?>)value;
            token(digest, "list-size"); //$NON-NLS-1$
            token(digest, Integer.toString(targets.size()));
            for (int i = 0; i < targets.size(); i++)
            {
                Object target = targets.get(i);
                if (target instanceof EObject)
                {
                    visit(digest, (EObject)target, reference.getName(), i);
                }
                else
                {
                    token(digest, Integer.toString(i));
                    token(digest, canonicalValue(target));
                }
            }
        }
        else if (value instanceof EObject)
        {
            visit(digest, (EObject)value, reference.getName(), 0);
        }
        else
        {
            token(digest, canonicalValue(value));
        }
    }

    /**
     * A stable, language-independent identity for a referenced object: its programmatic
     * {@code name} when it has one - which is what the reader displays for these features - and
     * its EClass otherwise. Deliberately not a synonym: synonyms are localized, and a fingerprint
     * that moved with the caller's language would fail the very comparison it exists for.
     */
    private static String referenceIdentity(Object value)
    {
        if (!(value instanceof EObject))
        {
            return canonicalValue(value);
        }
        EObject target = (EObject)value;
        // An unresolved proxy already carries the URI the model persists for this reference. That
        // is the most precise identity available, and the only one that does not require loading
        // the target just to fingerprint a pointer to it.
        if (target.eIsProxy() && target instanceof InternalEObject)
        {
            URI proxy = ((InternalEObject)target).eProxyURI();
            if (proxy != null)
            {
                return "proxy:" + proxy; //$NON-NLS-1$
            }
        }
        EStructuralFeature name = target.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (name instanceof EAttribute && target.eIsSet(name))
        {
            Object raw = target.eGet(name);
            if (raw != null)
            {
                return target.eClass().getName() + ':' + raw;
            }
        }
        // Nothing named to go on. Fall back to the object's identity within its resource so that
        // repointing between two unnamed targets of the same class still moves the hash. Only when
        // the target IS in a resource: outside one the URI degrades to a bare class fragment that
        // is identical for every instance, and a fabricated distinction would be worse than an
        // admitted one.
        if (target.eResource() != null)
        {
            return target.eClass().getName() + '@' + EcoreUtil.getURI(target);
        }
        return target.eClass().getName();
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
