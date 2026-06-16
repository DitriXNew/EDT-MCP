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
        // AccountingFlag on a ChartOfAccounts (ru: priznak ucheta / priznaki ucheta).
        // The EMF model carries no English code for this collection, so the English tokens are the
        // natural CamelCased element name (singular/plural).
        putTokens(m, "accountingFlags", "accountingflag", "accountingflags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430),
            cp(0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0438, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430));
        // ExtDimensionAccountingFlag on a ChartOfAccounts (ru: priznak ucheta subkonto / priznaki ...).
        putTokens(m, "extDimensionAccountingFlags", //$NON-NLS-1$
            "extdimensionaccountingflag", "extdimensionaccountingflags", //$NON-NLS-1$ //$NON-NLS-2$
            cp(0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430,
                0x0441, 0x0443, 0x0431, 0x043a, 0x043e, 0x043d, 0x0442, 0x043e),
            cp(0x043f, 0x0440, 0x0438, 0x0437, 0x043d, 0x0430, 0x043a, 0x0438, 0x0443, 0x0447, 0x0435, 0x0442, 0x0430,
                0x0441, 0x0443, 0x0431, 0x043a, 0x043e, 0x043d, 0x0442, 0x043e));
        // AddressingAttribute on a Task (ru: rekvizit adresacii / rekvizity adresacii)
        putTokens(m, "addressingAttributes", "addressingattribute", "addressingattributes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442,
                0x0430, 0x0434, 0x0440, 0x0435, 0x0441, 0x0430, 0x0446, 0x0438, 0x0438),
            cp(0x0440, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442, 0x044b,
                0x0430, 0x0434, 0x0440, 0x0435, 0x0441, 0x0430, 0x0446, 0x0438, 0x0438));
        // Column on a DocumentJournal (ru: kolonka / kolonki). The EMF model carries no English
        // code for this collection, so the English tokens are the natural CamelCased element name.
        putTokens(m, "columns", "column", "columns", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0430),
            cp(0x043a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0438));
        // Template - needs factory-wired default content; serialized inline in the owner's .mdo
        // (ru: maket / makety).
        putTokens(m, "templates", "template", "templates", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043c, 0x0430, 0x043a, 0x0435, 0x0442),
            cp(0x043c, 0x0430, 0x043a, 0x0435, 0x0442, 0x044b));
        // Recalculation on a CalculationRegister - needs the factory (produced types); inline in
        // the register .mdo (ru: pereraschet / pereraschety).
        putTokens(m, "recalculations", "recalculation", "recalculations", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043f, 0x0435, 0x0440, 0x0435, 0x0440, 0x0430, 0x0441, 0x0447, 0x0435, 0x0442),
            cp(0x043f, 0x0435, 0x0440, 0x0435, 0x0440, 0x0430, 0x0441, 0x0447, 0x0435, 0x0442, 0x044b));
        // URLTemplate on an HTTPService (ru token is Cyrillic "Shablon" + ASCII "URL": shablonurl).
        putTokens(m, "urlTemplates", "urltemplate", "urltemplates", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x0448, 0x0430, 0x0431, 0x043b, 0x043e, 0x043d) + "url", //$NON-NLS-1$
            cp(0x0448, 0x0430, 0x0431, 0x043b, 0x043e, 0x043d, 0x044b) + "url"); //$NON-NLS-1$
        // Method on an HTTPService URLTemplate (ru: metod / metody)
        putTokens(m, "methods", "method", "methods", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043c, 0x0435, 0x0442, 0x043e, 0x0434),
            cp(0x043c, 0x0435, 0x0442, 0x043e, 0x0434, 0x044b));
        // Operation on a WebService (ru: operaciya / operacii)
        putTokens(m, "operations", "operation", "operations", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043e, 0x043f, 0x0435, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f),
            cp(0x043e, 0x043f, 0x0435, 0x0440, 0x0430, 0x0446, 0x0438, 0x0438));
        // Parameter on a WebService Operation (ru: parametr / parametry)
        putTokens(m, "parameters", "parameter", "parameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            cp(0x043f, 0x0430, 0x0440, 0x0430, 0x043c, 0x0435, 0x0442, 0x0440),
            cp(0x043f, 0x0430, 0x0440, 0x0430, 0x043c, 0x0435, 0x0442, 0x0440, 0x044b));
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
     * Delegates to the shared {@link MetadataLanguageUtils#cp}.
     *
     * @param codePoints the BMP code points of the token characters
     * @return the assembled token string
     */
    private static String cp(int... codePoints)
    {
        return MetadataLanguageUtils.cp(codePoints);
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

        /**
         * Top-level create target: {@link #topLevel} is {@code true} and only the top-level
         * fields are set; the child-navigation fields stay {@code null}. Matches the field
         * values the former 8-arg constructor produced for {@link #forTopLevel}.
         */
        private CreateTarget(String topLevelType, String configFeatureName, String childName)
        {
            this.topLevel = true;
            this.childName = childName;
            this.topLevelType = topLevelType;
            this.configFeatureName = configFeatureName;
            this.owner = null;
            this.topObject = null;
            this.feature = null;
            this.elementType = null;
        }

        /**
         * Child create target: {@link #topLevel} is {@code false} and only the child-navigation
         * fields are set; the top-level fields stay {@code null}. Matches the field values the
         * former 8-arg constructor produced for {@link #forChild}.
         */
        private CreateTarget(EObject owner, MdObject topObject, EReference feature,
            EClass elementType, String childName)
        {
            this.topLevel = false;
            this.childName = childName;
            this.topLevelType = null;
            this.configFeatureName = null;
            this.owner = owner;
            this.topObject = topObject;
            this.feature = feature;
            this.elementType = elementType;
        }

        static CreateTarget forTopLevel(String topLevelType, String configFeatureName, String childName)
        {
            return new CreateTarget(topLevelType, configFeatureName, childName);
        }

        static CreateTarget forChild(EObject owner, MdObject topObject, EReference feature,
            EClass elementType, String childName)
        {
            return new CreateTarget(owner, topObject, feature, elementType, childName);
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
     * Result of {@link #resolveExistingWithYoFallback}: the resolved node (or {@code null}),
     * the FQN that actually resolved, and whether the yo-normalized retry was the form that hit.
     */
    public static final class ResolvedNode
    {
        /** The resolved node; {@code null} when neither the exact nor the normalized FQN resolves. */
        public final MetadataNode node;
        /** The FQN that resolved (the yo-normalized form when the fallback hit); the input otherwise. */
        public final String fqn;
        /** {@code true} when the exact FQN missed and the yo-to-ye-normalized FQN resolved instead. */
        public final boolean yoFallback;

        private ResolvedNode(MetadataNode node, String fqn, boolean yoFallback)
        {
            this.node = node;
            this.fqn = fqn;
            this.yoFallback = yoFallback;
        }
    }

    /**
     * Resolves an existing node like {@link #resolveExisting}, with a yo-addressing fallback:
     * addressing here is EXACT, but {@code create_metadata} normalizes the Russian letter yo
     * (U+0451/U+0401) to ye (U+0435/U+0415) in names by default — so a caller who re-types the
     * original yo spelling would miss the stored, normalized name. When the exact FQN does not
     * resolve and it differs from its yo-normalized form, the normalized FQN is retried; the
     * result says which form hit so the caller can log/report it.
     *
     * <p>Deliberately a SEPARATE entry point (used by the modify/delete "node not found" sites)
     * rather than a change to {@link #resolveExisting} itself: the exact resolver also backs
     * create_metadata's duplicate check (where a {@code normalizeYo=false} creation of a yo
     * name must NOT be misread as a duplicate of its ye twin) and reference-target resolution.
     *
     * @param config the configuration to resolve in
     * @param fqn the full-name FQN (already {@code normalizeFqn}-ed by callers; may be {@code null})
     * @return the resolution result, never {@code null}
     */
    public static ResolvedNode resolveExistingWithYoFallback(Configuration config, String fqn)
    {
        MetadataNode node = resolveExisting(config, fqn);
        if (node != null)
        {
            return new ResolvedNode(node, fqn, false);
        }
        String retry = yoRetryFqn(fqn);
        if (retry != null)
        {
            MetadataNode normalized = resolveExisting(config, retry);
            if (normalized != null)
            {
                return new ResolvedNode(normalized, retry, true);
            }
        }
        return new ResolvedNode(null, fqn, false);
    }

    /**
     * Returns the yo-to-ye-normalized retry FQN for a failed exact resolve, or {@code null}
     * when the input contains no yo (no distinct retry form exists).
     *
     * @param fqn the requested FQN (may be {@code null})
     * @return the normalized FQN when it differs from the input; {@code null} otherwise
     */
    public static String yoRetryFqn(String fqn)
    {
        String norm = MdNameNormalizer.normalizeYo(fqn);
        return norm != null && !norm.equals(fqn) ? norm : null;
    }

    /**
     * One-sentence hint to append to a "Node not found" error when the requested FQN contains
     * the Russian letter yo: names are stored normalized ({@code create_metadata} rewrites
     * yo to ye by default) and even the normalized form did not resolve. Empty (never
     * {@code null}) for a yo-less FQN, so callers can append it unconditionally.
     *
     * @param fqn the requested FQN (may be {@code null})
     * @return the hint sentence (with a leading space), or an empty string
     */
    public static String yoNotFoundHint(String fqn)
    {
        String retry = yoRetryFqn(fqn);
        if (retry == null)
        {
            return ""; //$NON-NLS-1$
        }
        // The Cyrillic letters are written as Unicode escapes - this source stays pure ASCII
        // (same non-UTF-8-build guard as the cp(...) token construction above).
        return " Note: names are stored with '\u0435' (create_metadata normalizes " //$NON-NLS-1$
            + "'\u0451'->'\u0435' by default), but the normalized form '" + retry //$NON-NLS-1$
            + "' was not found either."; //$NON-NLS-1$
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
