/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.bm.core.IBmObject;

/** Ordering of named metadata lists. Call model-facing methods inside a BM transaction. */
public final class MetadataOrdering
{
    private MetadataOrdering()
    {
    }

    /** One explicitly addressed collection, or the direct collections of an addressed object. */
    public record Level(MdObject owner, List<EReference> collections, boolean singleCollection)
    {
    }

    public static Level resolve(Configuration config, String fqn)
    {
        String[] parts = fqn.split("\\.", -1);
        for (String part : parts)
        {
            if (part.isBlank() || !part.equals(part.trim()))
            {
                throw new IllegalArgumentException("Invalid fqn '" + fqn + "'; use dot-separated metadata names.");
            }
        }
        if (ConfigurationRoot.isToken(parts[0]))
        {
            if (parts.length == 1)
            {
                return new Level(config, collections(config), false);
            }
            if (parts.length == 2)
            {
                return collectionLevel(config, parts[1], fqn);
            }
        }
        String normalized = MetadataTypeUtils.normalizeFqn(fqn);
        if (parts.length % 2 == 1 && parts.length > 2)
        {
            String ownerFqn = normalized.substring(0, normalized.lastIndexOf('.'));
            MdObject owner = MetadataNodeResolver.resolveAddress(MetadataScope.ofConfiguration(config), ownerFqn, true);
            if (owner != null)
            {
                return collectionLevel(owner, parts[parts.length - 1], fqn);
            }
        }
        MdObject owner = MetadataNodeResolver.resolveAddress(MetadataScope.ofConfiguration(config), normalized, true);
        if (owner == null)
        {
            throw new IllegalArgumentException("Metadata level '" + fqn
                + "' not found. Use get_metadata_objects/get_metadata_details for programmatic names.");
        }
        return new Level(owner, collections(owner), false);
    }

    private static Level collectionLevel(MdObject owner, String token, String fqn)
    {
        String featureName = owner instanceof Configuration ? MetadataTypeUtils.getConfigReferenceName(token)
            : MetadataNodeResolver.featureNameForKind(token);
        MetadataTypeUtils.NestedKindInfo nestedKind = MetadataTypeUtils.resolveNestedKind(token);
        if (featureName == null)
        {
            featureName = MetadataTypeUtils.getConfigReferenceName(token);
        }
        for (EReference ref : collections(owner))
        {
            if (ref.getName().equalsIgnoreCase(token) || ref.getName().equals(featureName)
                || (nestedKind != null && nestedKind.getEnglish().equalsIgnoreCase(
                    MetadataNodeResolver.kindTokenForFeature(ref.getName()))))
            {
                return new Level(owner, List.of(ref), true);
            }
        }
        throw new IllegalArgumentException("Ordered metadata collection '" + fqn
            + "' not found. Use an existing collection name from get_metadata_details.");
    }

    public static List<EReference> collections(MdObject owner)
    {
        List<EReference> result = new ArrayList<>();
        for (EReference ref : owner.eClass().getEAllReferences())
        {
            if (ref.isMany() && ref.isOrdered() && ref.isChangeable() && !ref.isDerived()
                && !ref.isTransient() && MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(ref.getEReferenceType()))
            {
                result.add(ref);
            }
        }
        return result;
    }

    /** Follow ownership only; content/default/reference targets must never widen a recursive sort. */
    public static boolean ownsChildren(MdObject owner, EReference ref)
    {
        if (ref.isContainment() || ReferencedTopObjects.isReferencedTopCollection(ref))
        {
            return true;
        }
        if (owner instanceof Configuration)
        {
            return MetadataTypeUtils.getAllEnglishSingularNames().stream()
                .anyMatch(type -> ref.getName().equals(MetadataTypeUtils.getConfigReferenceName(type)));
        }
        return "Subsystem".equals(owner.eClass().getName()) && "subsystems".equals(ref.getName());
    }

    @SuppressWarnings("unchecked")
    public static EList<MdObject> members(EObject owner, EReference ref)
    {
        return (EList<MdObject>)owner.eGet(ref);
    }

    public static String collectionAddress(MdObject owner, EReference ref)
    {
        String address = owner instanceof Configuration ? "Configuration"
            : owner instanceof IBmObject bm && !bm.bmIsTransient() && bm.bmIsTop() ? bm.bmGetFqn()
            : MetadataNodeResolver.addressOf(owner);
        return address + "." + ref.getName();
    }

    /** Pure permutation; stable for equal normalized names and for unlisted explicit members. */
    public static List<String> order(List<String> names, String mode, List<String> items,
        String objectName, String direction, int positions, String before, String after)
    {
        List<String> result = new ArrayList<>(names);
        switch (mode)
        {
        case "alphabetical":
            result.sort(Comparator.comparing(MdNameNormalizer::normalizeYo, String.CASE_INSENSITIVE_ORDER));
            break;
        case "explicit":
            result.clear();
            Set<String> seen = new HashSet<>();
            for (String item : items)
            {
                String actual = requireName(names, item);
                if (!seen.add(actual.toLowerCase(Locale.ROOT)))
                {
                    throw new IllegalArgumentException("Duplicate items entry '" + item + "'; list each member once.");
                }
                result.add(actual);
            }
            for (String name : names)
            {
                if (!seen.contains(name.toLowerCase(Locale.ROOT)))
                {
                    result.add(name);
                }
            }
            break;
        case "move":
            String actual = requireName(names, objectName);
            int oldIndex = names.indexOf(actual);
            result.remove(actual);
            int index;
            if (direction != null)
            {
                long requested = (long)oldIndex + ("up".equals(direction) ? -(long)positions : positions);
                index = (int)Math.max(0, Math.min(result.size(), requested));
            }
            else
            {
                String sibling = requireName(names, before != null ? before : after);
                if (sibling.equals(actual))
                {
                    throw new IllegalArgumentException("Cannot move '" + objectName + "' relative to itself; choose another sibling.");
                }
                index = result.indexOf(sibling) + (after != null ? 1 : 0);
            }
            result.add(index, actual);
            break;
        default:
            throw new IllegalArgumentException("Unknown mode '" + mode + "'; use alphabetical, explicit or move.");
        }
        return result;
    }

    private static String requireName(List<String> names, String name)
    {
        return names.stream().filter(n -> n.equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Member '" + name
                + "' not found; use a programmatic sibling Name from get_metadata_details."));
    }

    public static List<Map<String, Object>> moved(List<String> original, List<String> ordered)
    {
        return moved(original, ordered, Function.identity());
    }

    public static <T> List<Map<String, Object>> moved(List<T> original, List<T> ordered, Function<T, String> nameOf)
    {
        Map<T, Integer> from = new LinkedHashMap<>();
        for (int i = 0; i < original.size(); i++)
        {
            from.put(original.get(i), i + 1);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++)
        {
            T object = ordered.get(i);
            if (from.get(object) != i + 1)
            {
                result.add(Map.of("name", nameOf.apply(object), "from", from.get(object), "to", i + 1));
            }
        }
        return result;
    }

    public static String warning(MdObject owner, EReference ref)
    {
        String feature = ref.getName();
        if ("dimensions".equals(feature) && owner.eClass().getName().contains("Register"))
        {
            return "Register DIMENSIONS order defines indexes and affects query speed.";
        }
        if ("enumValues".equals(feature))
        {
            return "Enum value order affects ORDER BY and comparisons.";
        }
        if ("Subsystem".equals(owner.eClass().getName()) && "content".equals(feature))
        {
            return "Subsystem content order affects the command interface order.";
        }
        if ("parameters".equals(feature) && "Operation".equals(owner.eClass().getName()))
        {
            return "Web-service parameter order defines handler signatures and positional argument types.";
        }
        // Other ordered lists can be consumed positionally even when EDT gives no stronger guarantee.
        return "Metadata order can affect presentation and positional access; review dependent code and interfaces.";
    }

    /** Validate before creating anything; out-of-range positions are refused, never clamped. */
    public static void validateInsertPosition(Integer position, int size)
    {
        if (position != null && (position < 1 || position > (long)size + 1))
        {
            throw new IllegalArgumentException("insertPosition " + position + " is outside 1.." + ((long)size + 1)
                + "; choose a 1-based index in the destination collection or omit insertPosition to append.");
        }
    }

    public static void positionCreated(EObject owner, EReference ref, MdObject child, Integer position)
    {
        if (position != null)
        {
            members(owner, ref).move(position - 1, child);
        }
    }

    /** The metadata collection a create FQN will add to; external content models have no such level. */
    public static Level destination(Configuration config, String createFqn)
    {
        int last = createFqn.lastIndexOf('.');
        if (last < 0)
        {
            throw new IllegalArgumentException("insertPosition requires a metadata object/member FQN; omit it for external content.");
        }
        String collectionFqn = createFqn.substring(0, last);
        if (collectionFqn.indexOf('.') < 0)
        {
            collectionFqn = "Configuration." + collectionFqn;
        }
        Level level = resolve(config, collectionFqn);
        if (!level.singleCollection())
        {
            throw new IllegalArgumentException("insertPosition requires an ordered metadata collection; omit it for external content.");
        }
        return level;
    }
}
