/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Builds a 1C data type ({@code TypeDescription}) from a structured spec, for the {@code type}
 * property of an attribute / dimension / resource. The spec is a JSON object:
 *
 * <pre>
 * { "types": [ {"kind":"String", "length":50},
 *              {"kind":"Number", "precision":10, "scale":2, "nonNegative":true},
 *              {"kind":"Date", "fractions":"DateTime"},
 *              {"kind":"Boolean"},
 *              {"kind":"Ref", "ref":"Catalog.Goods"} ] }
 * </pre>
 *
 * Primitive kinds: String / Number / Boolean / Date (qualifiers given inline). A reference is
 * {@code {"kind":"Ref", "ref":"Type.Name"}} (the ref FQN is resolved bilingually) or
 * {@code {"kind":"CatalogRef", "ref":"Name"}}. The {@code types} list may mix several (a composite
 * type). The shape is validated before any platform call, so a malformed spec fails fast.
 */
public final class MetadataTypeBuilder
{
    /** The build outcome: exactly one of {@link #typeDescription} / {@link #error} is non-null. */
    public static final class Result
    {
        /** The built type, or {@code null} on error. */
        public final TypeDescription typeDescription;
        /** The error message, or {@code null} on success. */
        public final String error;

        private Result(TypeDescription typeDescription, String error)
        {
            this.typeDescription = typeDescription;
            this.error = error;
        }
    }

    private MetadataTypeBuilder()
    {
        // utility class
    }

    private static Result error(String message)
    {
        return new Result(null, message);
    }

    /**
     * Validates the spec SHAPE without touching the platform (so a malformed spec is rejectable in a
     * unit test); returns an error message, or {@code null} when the shape is acceptable.
     *
     * @param spec the candidate {@code type} value
     * @return the shape error, or {@code null}
     */
    public static String validateShape(JsonElement spec)
    {
        if (spec == null || !spec.isJsonObject())
        {
            return "type value must be an object like {types:[{kind:'String', length:50}]}."; //$NON-NLS-1$
        }
        JsonElement typesEl = spec.getAsJsonObject().get("types"); //$NON-NLS-1$
        if (typesEl == null || !typesEl.isJsonArray() || typesEl.getAsJsonArray().isEmpty())
        {
            return "type.types must be a non-empty array of {kind, ...} items."; //$NON-NLS-1$
        }
        for (JsonElement itemEl : typesEl.getAsJsonArray())
        {
            if (!itemEl.isJsonObject())
            {
                return "each entry of type.types must be an object like {kind:'String'}."; //$NON-NLS-1$
            }
            String kind = asString(itemEl.getAsJsonObject().get("kind")); //$NON-NLS-1$
            if (kind == null || kind.isEmpty())
            {
                return "each type item needs a non-empty 'kind' (String/Number/Boolean/Date or a Ref)."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Builds the {@link TypeDescription} from a validated spec.
     *
     * @param spec the {@code type} value (object with a {@code types} array)
     * @param config the configuration (to resolve reference targets)
     * @param version the platform version (to create primitive type proxies)
     * @return the result (type or error)
     */
    public static Result build(JsonElement spec, Configuration config, Version version)
    {
        String shapeError = validateShape(spec);
        if (shapeError != null)
        {
            return error(shapeError);
        }

        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(
            McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            return error("Platform type provider is not available for this configuration version."); //$NON-NLS-1$
        }

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonArray types = spec.getAsJsonObject().getAsJsonArray("types"); //$NON-NLS-1$
        for (JsonElement itemEl : types)
        {
            JsonObject item = itemEl.getAsJsonObject();
            String kind = asString(item.get("kind")).trim(); //$NON-NLS-1$
            String err = addType(td, item, kind, provider, config);
            if (err != null)
            {
                return error(err);
            }
        }
        return new Result(td, null);
    }

    @SuppressWarnings("java:S125")
    private static String addType(TypeDescription td, JsonObject item, String kind,
        IEObjectProvider provider, Configuration config)
    {
        if (isRefKind(kind))
        {
            MdObject target = resolveRefTarget(config, kind, asString(item.get("ref"))); //$NON-NLS-1$
            if (target == null)
            {
                return "Cannot resolve the reference target for kind '" + kind + "' ref '" //$NON-NLS-1$ //$NON-NLS-2$
                    + asString(item.get("ref")) + "'. Use {kind:'Ref', ref:'Type.Name'} or " //$NON-NLS-1$ //$NON-NLS-2$
                    + "{kind:'CatalogRef', ref:'Name'} and check the object exists."; //$NON-NLS-1$
            }
            Type refType;
            try
            {
                // The generic getRefType(MdObject) dispatcher does NOT route Enum (it has a separate
                // overload) and THROWS AssertionError for kinds with no ref type (registers, reports,
                // ...). AssertionError is an Error, so it would escape the tool's catch(Exception) -
                // route Enum explicitly and convert the AssertionError into a clean error Result.
                refType = (target instanceof com._1c.g5.v8.dt.metadata.mdclass.Enum)
                    ? MdTypeUtil.getRefType((com._1c.g5.v8.dt.metadata.mdclass.Enum)target)
                    : MdTypeUtil.getRefType(target);
            }
            catch (AssertionError e)
            {
                refType = null;
            }
            if (refType == null)
            {
                return "Object '" + target.getName() + "' is not a reference type. Only objects with a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Ref type (Catalog / Document / Enum / ChartOf* / ExchangePlan / BusinessProcess / " //$NON-NLS-1$
                    + "Task) can be referenced."; //$NON-NLS-1$
            }
            td.getTypes().add(refType);
            return null;
        }

        String primitive = normalizePrimitive(kind);
        if (primitive == null)
        {
            return "Unknown type kind '" + kind + "'. Use String / Number / Boolean / Date, or a " //$NON-NLS-1$ //$NON-NLS-2$
                + "reference ({kind:'Ref', ref:'Type.Name'})."; //$NON-NLS-1$
        }
        EObject proxy = provider.createProxy(primitive);
        if (!(proxy instanceof TypeItem))
        {
            return "Could not create the platform type '" + primitive + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        td.getTypes().add((TypeItem)proxy);
        applyQualifiers(td, item, primitive);
        return null;
    }

    private static void applyQualifiers(TypeDescription td, JsonObject item, String primitive)
    {
        if ("String".equals(primitive)) //$NON-NLS-1$
        {
            Integer length = asInt(item.get("length")); //$NON-NLS-1$
            if (length != null)
            {
                StringQualifiers q = McoreFactory.eINSTANCE.createStringQualifiers();
                q.setLength(length.intValue());
                q.setFixed(asBool(item.get("fixed"), false)); //$NON-NLS-1$
                td.setStringQualifiers(q);
            }
        }
        else if ("Number".equals(primitive)) //$NON-NLS-1$
        {
            Integer precision = asInt(item.get("precision")); //$NON-NLS-1$
            if (precision != null)
            {
                Integer scale = asInt(item.get("scale")); //$NON-NLS-1$
                NumberQualifiers q = McoreFactory.eINSTANCE.createNumberQualifiers();
                q.setPrecision(precision.intValue());
                q.setScale(scale != null ? scale.intValue() : 0);
                q.setNonNegative(asBool(item.get("nonNegative"), false)); //$NON-NLS-1$
                td.setNumberQualifiers(q);
            }
        }
        else if ("Date".equals(primitive)) //$NON-NLS-1$
        {
            DateQualifiers q = McoreFactory.eINSTANCE.createDateQualifiers();
            q.setDateFractions(parseFractions(asString(item.get("fractions")))); //$NON-NLS-1$
            td.setDateQualifiers(q);
        }
    }

    /** A reference kind is the literal {@code "Ref"} or any {@code "...Ref"} token (CatalogRef, ...). */
    static boolean isRefKind(String kind)
    {
        if (kind == null)
        {
            return false;
        }
        String k = kind.trim();
        return k.equalsIgnoreCase("Ref") //$NON-NLS-1$
            || (k.length() > 3 && k.regionMatches(true, k.length() - 3, "Ref", 0, 3)); //$NON-NLS-1$
    }

    private static MdObject resolveRefTarget(Configuration config, String kind, String ref)
    {
        if (ref == null || ref.isEmpty())
        {
            return null;
        }
        if (kind.equalsIgnoreCase("Ref")) //$NON-NLS-1$
        {
            // ref is a full FQN ('Type.Name'); resolve it bilingually.
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, ref);
            return node != null ? node.object : null;
        }
        // kind is '<Type>Ref'; the leading token is the type, ref is the object Name.
        String type = kind.substring(0, kind.length() - 3);
        return MetadataTypeUtils.findObject(config, type, ref);
    }

    /** Maps a primitive kind to its canonical platform type name, or {@code null} if not a primitive. */
    static String normalizePrimitive(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        switch (kind.trim().toLowerCase())
        {
            case "string": //$NON-NLS-1$
                return "String"; //$NON-NLS-1$
            case "number": //$NON-NLS-1$
                return "Number"; //$NON-NLS-1$
            case "boolean": //$NON-NLS-1$
            case "bool": //$NON-NLS-1$
                return "Boolean"; //$NON-NLS-1$
            case "date": //$NON-NLS-1$
                return "Date"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /** Parses the date-fractions name; defaults to date+time. */
    static DateFractions parseFractions(String fractions)
    {
        if (fractions == null)
        {
            return DateFractions.DATE_TIME;
        }
        switch (fractions.trim().toLowerCase())
        {
            case "date": //$NON-NLS-1$
                return DateFractions.DATE;
            case "time": //$NON-NLS-1$
                return DateFractions.TIME;
            case "datetime": //$NON-NLS-1$
            default:
                return DateFractions.DATE_TIME;
        }
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static Integer asInt(JsonElement el)
    {
        try
        {
            return (el != null && el.isJsonPrimitive()) ? Integer.valueOf(el.getAsInt()) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static boolean asBool(JsonElement el, boolean dflt)
    {
        if (el == null || !el.isJsonPrimitive())
        {
            return dflt;
        }
        try
        {
            return el.getAsBoolean();
        }
        catch (Exception e)
        {
            return dflt;
        }
    }
}
