/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Resolves a 1C "full name" FQN to a metadata node anywhere in the mdclass tree,
 * for the unified metadata-CRUD tools (create / modify / delete / get_properties).
 *
 * <p>A full name addresses a node by its containment path, where each level adds a
 * {@code .KindToken.Name} pair: {@code Catalog.Products} (a top object),
 * {@code Catalog.Products.Attribute.Weight} (an attribute),
 * {@code Catalog.Products.TabularSection.Goods.Attribute.Price} (a tabular-section
 * attribute), {@code InformationRegister.Prices.Dimension.Product} / {@code .Resource.Price},
 * {@code Enum.Colors.EnumValue.Red}. The leading TYPE token and every child KIND token may
 * be English or Russian (Russian type and kind tokens are accepted too).
 * The Name parts are the programmatic Names, never the synonym.</p>
 *
 * <p>This generalizes the per-tool FQN navigation that previously lived (duplicated) in the
 * delete / rename tools: instead of a reflective {@code getAttributes()} call it resolves the
 * EMF containment {@link EReference} by the kind token, so the same map serves both
 * navigation (find an existing child) and creation (instantiate the reference's element type).</p>
 *
 * <p><b>Bilingual:</b> kind tokens are matched case-insensitively in English and Russian,
 * singular and plural. The Russian tokens are built from Unicode code points (see cp),
 * not raw Cyrillic, to stay safe under a non-UTF-8 Tycho build.</p>
 */
public final class MetadataNodeResolver
{
    /**
     * Bilingual child KIND token (lower-cased) &rarr; EMF containment feature name on the owner.
     * The feature name is the standard EMF accessor name (e.g. {@code getAttributes()} &rarr;
     * {@code "attributes"}), resolved on the owner's {@link EClass} at run time so it works for
     * every owner type that declares the collection.
     */
    private static final Map<String, String> CHILD_FEATURE_BY_TOKEN;
    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        // Russian kind tokens are built from Unicode code points via cp(...) so this source stays
        // pure ASCII: no raw Cyrillic and no reliance on the compiler source encoding (the same
        // non-UTF-8 Tycho-build risk the project guards against by avoiding raw Cyrillic literals).
        // The token map keys must be lower case (featureNameForKind lower-cases its input).
        // Attribute (ru: rekvizit / rekvizity)
        putTokens(m, "attributes", "attribute", "attributes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442),
            cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442, 0x044b));
        // TabularSection (ru: tablichnaya chast / tablichnye chasti)
        putTokens(m, "tabularSections", "tabularsection", "tabularsections", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x0430, 0x044f, 0x0447, 0x0430, 0x0441, 0x0442, 0x044c),
            cp(0x0442, 0x0430, 0x0431, 0x043b, 0x0438, 0x0447, 0x043d, 0x044b, 0x0435, 0x0447, 0x0430, 0x0441, 0x0442, 0x0438));
        // Dimension (ru: izmerenie / izmereniya)
        putTokens(m, "dimensions", "dimension", "dimensions", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0438, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x0435),
            cp(0x0438, 0x0437, 0x043c, 0x0435, 0x0440, 0x0435, 0x043d, 0x0438, 0x044f));
        // Resource (ru: resurs / resursy)
        putTokens(m, "resources", "resource", "resources", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0440, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441),
            cp(0x0440, 0x0435, 0x0441, 0x0443, 0x0440, 0x0441, 0x044b));
        // EnumValue (ru: znachenie perechisleniya / znacheniya perechisleniya)
        putTokens(m, "enumValues", "enumvalue", "enumvalues", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0437, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0435, 0x043f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f),
            cp(0x0437, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x044f, 0x043f, 0x0435, 0x0440, 0x0435, 0x0447, 0x0438, 0x0441, 0x043b, 0x0435, 0x043d, 0x0438, 0x044f));
        // Command (ru: komanda / komandy)
        putTokens(m, "commands", "command", "commands", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x0430),
            cp(0x043a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434, 0x044b));
        CHILD_FEATURE_BY_TOKEN = Collections.unmodifiableMap(m);
    }

    private static void putTokens(Map<String, String> m, String featureName, String... tokens)
    {
        for (String t : tokens)
        {
            m.put(t, featureName);
        }
    }

    /**
     * Builds a string from Unicode code points. Used for the Russian kind tokens so the source
     * stays pure ASCII (encoding-independent) rather than carrying raw Cyrillic literals.
     *
     * @param codePoints the BMP code points of the token characters
     * @return the assembled token string
     */
    private static String cp(int... codePoints)
    {
        StringBuilder sb = new StringBuilder(codePoints.length);
        for (int c : codePoints)
        {
            sb.append((char)c);
        }
        return sb.toString();
    }

    private MetadataNodeResolver()
    {
        // utility class
    }

    /**
     * A resolved EXISTING metadata node (a top-level object or a subordinate member).
     */
    public static final class MetadataNode
    {
        /** The resolved node. */
        public final MdObject object;
        /** The container: the {@link Configuration} for a top object, the owner {@link MdObject} for a child. */
        public final EObject owner;
        /** The containment feature in {@link #owner} that holds {@link #object}. */
        public final EReference feature;
        /** {@code true} when the node is a top-level configuration object. */
        public final boolean topLevel;

        MetadataNode(MdObject object, EObject owner, EReference feature, boolean topLevel)
        {
            this.object = object;
            this.owner = owner;
            this.feature = feature;
            this.topLevel = topLevel;
        }
    }

    /**
     * A resolved target for CREATING a new node: where the new node goes and what to instantiate.
     * For a top-level object it carries the canonical type and the Configuration collection name
     * (the create path uses the EDT model-object factory); for a child it carries the resolved owner,
     * the containment feature and the concrete element {@link EClass} to instantiate.
     */
    public static final class CreateTarget
    {
        /** {@code true} when creating a top-level configuration object. */
        public final boolean topLevel;
        /** Programmatic name of the node to create. */
        public final String childName;
        /** Canonical English singular type (e.g. {@code "Catalog"}); {@code null} for a child. */
        public final String topLevelType;
        /** Configuration collection feature name (e.g. {@code "catalogs"}); {@code null} for a child. */
        public final String configFeatureName;
        /** Resolved direct parent of the leaf; {@code null} for a top-level create. */
        public final EObject owner;
        /** The TOP object that owns the leaf's {@code .mdo} file and is re-fetchable in a BM
         * transaction (only TOP objects are re-fetchable by bmId); {@code null} for a top-level create. */
        public final MdObject topObject;
        /** Containment feature on {@link #owner}; {@code null} for a top-level create. */
        public final EReference feature;
        /** Concrete element type to instantiate; {@code null} for a top-level create. */
        public final EClass elementType;

        private CreateTarget(boolean topLevel, String childName, String topLevelType,
            String configFeatureName, EObject owner, MdObject topObject, EReference feature,
            EClass elementType)
        {
            this.topLevel = topLevel;
            this.childName = childName;
            this.topLevelType = topLevelType;
            this.configFeatureName = configFeatureName;
            this.owner = owner;
            this.topObject = topObject;
            this.feature = feature;
            this.elementType = elementType;
        }

        static CreateTarget forTopLevel(String topLevelType, String configFeatureName, String childName)
        {
            return new CreateTarget(true, childName, topLevelType, configFeatureName, null, null, null, null);
        }

        static CreateTarget forChild(EObject owner, MdObject topObject, EReference feature,
            EClass elementType, String childName)
        {
            return new CreateTarget(false, childName, null, null, owner, topObject, feature, elementType);
        }
    }

    /**
     * Maps a child KIND token (English/Russian, singular/plural, any case) to the EMF containment
     * feature name on the owner.
     *
     * @param kindToken the kind token from a full-name FQN segment
     * @return the EMF feature name (e.g. {@code "attributes"}), or {@code null} if unrecognized
     */
    public static String featureNameForKind(String kindToken)
    {
        if (kindToken == null)
        {
            return null;
        }
        return CHILD_FEATURE_BY_TOKEN.get(kindToken.toLowerCase());
    }

    /**
     * Checks that an FQN's dot-separated part count is a valid arity: a leading {@code Type.Name}
     * pair (2 parts) plus zero or more {@code .ChildType.ChildName} pairs. An odd trailing token is
     * malformed and must be rejected so a nested address can never silently fall back to a parent.
     *
     * @param partCount number of dot-separated tokens
     * @return {@code true} for 2, 4, 6, ... parts; {@code false} otherwise
     */
    public static boolean isValidArity(int partCount)
    {
        return partCount >= 2 && (partCount - 2) % 2 == 0;
    }

    /**
     * Resolves an existing node from its full-name FQN.
     *
     * @param config the configuration to resolve in
     * @param fqn the full-name FQN (e.g. {@code "Catalog.Products.Attribute.Weight"})
     * @return the resolved node, or {@code null} if any segment does not resolve / the FQN is malformed
     */
    public static MetadataNode resolveExisting(Configuration config, String fqn)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = MetadataTypeUtils.normalizeFqn(fqn).split("\\."); //$NON-NLS-1$
        if (!isValidArity(parts.length))
        {
            return null;
        }

        MdObject top = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (top == null)
        {
            return null;
        }
        if (parts.length == 2)
        {
            return new MetadataNode(top, config, configFeature(config, parts[0]), true);
        }

        EObject owner = top;
        MdObject current = top;
        EReference lastFeature = null;
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            EReference ref = childFeature(current, parts[i]);
            if (ref == null)
            {
                return null;
            }
            MdObject child = findInList(current, ref, parts[i + 1]);
            if (child == null)
            {
                return null;
            }
            owner = current;
            lastFeature = ref;
            current = child;
        }
        return new MetadataNode(current, owner, lastFeature, false);
    }

    /**
     * Resolves the target for creating a new node addressed by {@code fqn}. The parent (everything
     * before the last {@code .Kind.Name} pair) must already exist; the leaf is the node to create.
     *
     * @param config the configuration to resolve in
     * @param fqn the full-name FQN of the node to create
     * @return the create target, or {@code null} if the FQN is malformed, the type/kind token is
     *     unrecognized, or the parent does not exist
     */
    public static CreateTarget resolveForCreate(Configuration config, String fqn)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = MetadataTypeUtils.normalizeFqn(fqn).split("\\."); //$NON-NLS-1$
        if (!isValidArity(parts.length))
        {
            return null;
        }

        if (parts.length == 2)
        {
            String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
            if (type == null)
            {
                return null;
            }
            String configFeatureName = MetadataTypeUtils.getConfigReferenceName(type);
            if (configFeatureName == null)
            {
                return null;
            }
            return CreateTarget.forTopLevel(type, configFeatureName, parts[1]);
        }

        // Navigate to the OWNER of the leaf (everything up to, but excluding, the last pair).
        MdObject top = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (top == null)
        {
            return null;
        }
        MdObject owner = top;
        for (int i = 2; i + 1 < parts.length - 2; i += 2)
        {
            EReference ref = childFeature(owner, parts[i]);
            if (ref == null)
            {
                return null;
            }
            MdObject child = findInList(owner, ref, parts[i + 1]);
            if (child == null)
            {
                return null;
            }
            owner = child;
        }

        EReference leafFeature = childFeature(owner, parts[parts.length - 2]);
        if (leafFeature == null)
        {
            return null;
        }
        return CreateTarget.forChild(owner, top, leafFeature, leafFeature.getEReferenceType(),
            parts[parts.length - 1]);
    }

    /**
     * Re-navigates from a TOP object (already re-fetched inside a BM transaction) to the direct
     * parent of the leaf, following the same kind/name pairs the FQN encodes. This is how a member
     * of a NESTED object (e.g. a tabular-section attribute) is created: only TOP objects are
     * re-fetchable by bmId, so the owner is re-resolved by name from the in-transaction top.
     *
     * @param top the top object, re-fetched in the transaction (must be non-{@code null})
     * @param parts the normalized FQN parts (the same array {@code Type.Name(.Kind.Name)+})
     * @return the leaf's direct parent within the transaction (the top itself for a direct member),
     *     or {@code null} if any intermediate segment does not resolve
     */
    public static EObject resolveOwnerInTx(EObject top, String[] parts)
    {
        if (top == null || parts == null || !isValidArity(parts.length))
        {
            return null;
        }
        EObject owner = top;
        for (int i = 2; i + 1 < parts.length - 2; i += 2)
        {
            if (!(owner instanceof MdObject))
            {
                return null;
            }
            EReference ref = childFeature((MdObject)owner, parts[i]);
            if (ref == null)
            {
                return null;
            }
            MdObject child = findInList(owner, ref, parts[i + 1]);
            if (child == null)
            {
                return null;
            }
            owner = child;
        }
        return owner;
    }

    /**
     * Resolves the containment {@link EReference} on {@code owner} for a child kind token.
     *
     * @param owner the owner object
     * @param kindToken the child kind token
     * @return the containment reference, or {@code null} if the token is unknown or the owner has no
     *     such feature
     */
    private static EReference childFeature(MdObject owner, String kindToken)
    {
        String featureName = featureNameForKind(kindToken);
        if (featureName == null)
        {
            return null;
        }
        EStructuralFeature f = owner.eClass().getEStructuralFeature(featureName);
        return (f instanceof EReference) ? (EReference)f : null;
    }

    /**
     * Resolves the Configuration collection {@link EReference} for a top-level type token.
     *
     * @param config the configuration
     * @param typeToken the leading type token (English/Russian)
     * @return the Configuration collection reference, or {@code null} if unresolved
     */
    private static EReference configFeature(Configuration config, String typeToken)
    {
        String type = MetadataTypeUtils.toEnglishSingular(typeToken);
        if (type == null)
        {
            return null;
        }
        String name = MetadataTypeUtils.getConfigReferenceName(type);
        if (name == null)
        {
            return null;
        }
        EStructuralFeature f = config.eClass().getEStructuralFeature(name);
        return (f instanceof EReference) ? (EReference)f : null;
    }

    /**
     * Finds a child by case-insensitive programmatic Name in the owner's containment list.
     *
     * @param owner the owner object
     * @param feature the containment feature
     * @param name the programmatic Name to match
     * @return the matching child, or {@code null}
     */
    private static MdObject findInList(EObject owner, EReference feature, String name)
    {
        Object value = owner.eGet(feature);
        if (value instanceof EList<?>)
        {
            for (Object element : (EList<?>)value)
            {
                if (element instanceof MdObject child && name.equalsIgnoreCase(child.getName()))
                {
                    return child;
                }
            }
        }
        return null;
    }
}
