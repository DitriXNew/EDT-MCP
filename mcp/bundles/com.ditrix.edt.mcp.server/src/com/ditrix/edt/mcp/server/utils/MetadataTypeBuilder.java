/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

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
import com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil;
import com._1c.g5.v8.dt.metadata.mdtype.BasicDbObjectTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdObjectType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypes;
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
 * Primitive kinds: String / Number / Boolean / Date (qualifiers given inline); ValueStorage / UUID
 * are platform simple types that carry no qualifiers. A reference is
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
        return build(spec, config, version, false);
    }

    /**
     * Builds the {@link TypeDescription} from a validated spec, like {@link #build(JsonElement,
     * Configuration, Version)}, additionally appending an extension-adopt hint to an unresolved-reference
     * error when {@code isExtensionProject} is {@code true} (issue #262): a reference target that lives
     * in the BASE configuration is invisible to an EXTENSION project until it is adopted
     * ({@code adopt_metadata_object}), and the plain "not found" wording gives no clue why. The hint is
     * APPENDED, so the sentinel "Cannot resolve the reference target" stays a continuous substring.
     *
     * @param spec the {@code type} value (object with a {@code types} array)
     * @param config the configuration (to resolve reference targets)
     * @param version the platform version (to create primitive type proxies)
     * @param isExtensionProject whether the project being modified is a configuration EXTENSION
     * @return the result (type or error)
     */
    public static Result build(JsonElement spec, Configuration config, Version version,
        boolean isExtensionProject)
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
            String err = addType(td, item, kind, provider, config, isExtensionProject);
            if (err != null)
            {
                return error(err);
            }
        }
        return new Result(td, null);
    }

    /** The platform pseudo-type a form list attribute carries as its value type. */
    private static final String DYNAMIC_LIST_TYPE = "DynamicList"; //$NON-NLS-1$

    /**
     * The English-singular TYPE tokens whose object form carries a main {@code Object} attribute of type
     * {@code <Type>Object.<Name>} - the reference / object metadata kinds with an object module (Catalog,
     * Document, the three ChartOf* plans, ExchangePlan, BusinessProcess, Task) plus Report / DataProcessor
     * (whose object form's main attribute is likewise {@code ReportObject} / {@code DataProcessorObject}).
     * Record-based owners (Information / Accumulation / Accounting / Calculation registers), Constant,
     * Enum, etc. are deliberately EXCLUDED: their object/record form's main data source is not a
     * {@code <Type>Object} value type, so seeding an {@code Object} attribute there would be semantically
     * wrong (issue #208 review).
     */
    private static final Set<String> OBJECT_FORM_TYPES = new HashSet<>(Arrays.asList(
        "Catalog", "Document", "ChartOfCharacteristicTypes", "ChartOfAccounts", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ChartOfCalculationTypes", "ExchangePlan", "BusinessProcess", "Task", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "Report", "DataProcessor")); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Whether a metadata owner addressed by {@code englishSingularType} (the canonical English-singular
     * TYPE token, e.g. {@code Catalog} / {@code Document}) has an object form whose main attribute is the
     * {@code Object} attribute of type {@code <Type>Object.<Name>}. Only for such owners may
     * {@code create_metadata}'s {@code generateContent} seed the main {@code Object} attribute; for any
     * other owner (registers, Constant, ...) the object-form {@code Object} attribute does not apply, so
     * the seed must be skipped. The check is on the static type KIND, not on a runtime proxy resolve (a
     * Catalog created in the same BM transaction may not yet resolve its {@code CatalogObject.X} proxy, but
     * it IS an object-form type and must still be seeded). See issue #208.
     *
     * @param englishSingularType the owner's English-singular TYPE token, or {@code null}
     * @return {@code true} when the owner carries a {@code <Type>Object} main form attribute
     */
    public static boolean hasObjectFormMainAttribute(String englishSingularType)
    {
        return englishSingularType != null && OBJECT_FORM_TYPES.contains(englishSingularType);
    }

    /**
     * Builds a {@link TypeDescription} carrying ONLY the {@code DynamicList} platform pseudo-type - the
     * value type a form list attribute uses ({@code <types>DynamicList</types>} on disk). Reuses the
     * same platform type provider as {@link #build}. Returns {@code null} when the platform version is
     * unknown, the provider is unavailable, or the platform does not expose a {@code DynamicList} type
     * proxy; the caller then relies on the {@code DynamicListExtInfo} alone (which EDT also accepts as a
     * dynamic list). The returned object is an mcore {@code TypeDescription} ready to {@code eSet} onto a
     * form attribute's {@code valueType} feature.
     *
     * @param version the platform version (to create the type proxy)
     * @return the dynamic-list type description, or {@code null} when it cannot be built
     */
    public static EObject dynamicListType(Version version)
    {
        if (version == null)
        {
            return null;
        }
        IEObjectProvider provider =
            IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        if (provider == null)
        {
            return null;
        }
        EObject proxy = provider.createProxy(DYNAMIC_LIST_TYPE);
        if (!(proxy instanceof TypeItem))
        {
            return null;
        }
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add((TypeItem)proxy);
        return td;
    }

    /**
     * Builds a {@link TypeDescription} carrying ONLY the OBJECT value-type of {@code owner} - the type the
     * main {@code Object} attribute of a managed object form carries
     * ({@code <types><Type>Object.<Name></types>} on disk, e.g. {@code DocumentObject.Invoice} /
     * {@code CatalogObject.Goods}). The object type is taken from the owner's OWN produced types (the same
     * path {@code MetadataReferenceService.collectProducedTypesReferences} uses), NOT synthesized from a
     * type token: {@link MdClassUtil#getProducedTypes} returns the owner's derived {@link MdTypes}, and for
     * a reference / object owner that is a {@link BasicDbObjectTypes} whose {@link BasicDbObjectTypes#getObjectType()
     * object type}'s {@link MdObjectType#getType() Type} is the {@code <Type>Object.<Name>} value the
     * attribute needs. The owner is a PRE-EXISTING object resolved inside the same BM transaction, so its
     * produced-types derived data is already computed and resolvable here.
     * <p>
     * Mirrors the working {@link #addType} Ref path: the model-owned {@code Type} is added straight into a
     * fresh {@code TypeDescription}'s {@code getTypes()} - a NON-containment reference list, so the type is
     * SHARED (not detached from the owner's produced types). Returns {@code null} when the owner is blank,
     * has no produced object type yet, or is not an object-form owner (the caller then seeds the attribute
     * without a value type rather than failing). The returned object is an mcore {@code TypeDescription}
     * ready to {@code eSet} onto a form attribute's {@code valueType} feature. Never throws
     * (unattended-safe). The wrong path would be {@code MdTypeUtil.getRefType}, which yields the REF type
     * ({@code CatalogRef.X}); the main {@code Object} attribute needs the OBJECT type
     * ({@code <Type>Object.<Name>}) from {@link BasicDbObjectTypes#getObjectType()}.
     *
     * @param owner the owner metadata object (re-fetched inside the active BM transaction), or {@code null}
     * @return the object value-type description, or {@code null} when it cannot be built
     */
    public static EObject objectType(EObject owner)
    {
        if (!(owner instanceof MdObject))
        {
            return null;
        }
        MdTypes producedTypes = MdClassUtil.getProducedTypes((MdObject)owner);
        if (producedTypes == null)
        {
            return null;
        }
        // The produced-types holder is a per-kind subtype (CatalogTypes, DocumentTypes,
        // DataProcessorTypes, ...). Only the DB-stored kinds extend BasicDbObjectTypes - on
        // EDT 2026.1 a DataProcessor's DataProcessorTypesImpl does NOT (issue #262), so an
        // instanceof gate silently dropped the object type for it. Every kind's holder carries
        // the same EMF feature "objectType", so read it generically through eGet instead.
        MdObjectType mdObjType = null;
        EStructuralFeature objectTypeFeature =
            producedTypes.eClass().getEStructuralFeature("objectType"); //$NON-NLS-1$
        if (objectTypeFeature != null)
        {
            Object value = producedTypes.eGet(objectTypeFeature);
            if (value instanceof MdObjectType)
            {
                mdObjType = (MdObjectType)value;
            }
        }
        if (mdObjType == null)
        {
            return null;
        }
        Type objType = mdObjType.getType();
        if (objType == null)
        {
            return null;
        }
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        // getTypes() is a NON-containment reference list, so the model-owned Type is SHARED, not detached
        // (the same way the Ref path adds MdTypeUtil.getRefType(target) - itself a model-owned Type).
        td.getTypes().add(objType);
        return td;
    }

    // Package-visible (not private) so MetadataTypeBuilderTest can exercise the Ref-not-found branch -
    // including the extension-adopt hint - directly, without a registered platform type `provider`
    // (which only the primitive branch needs; see MetadataTypeBuilderTest's class doc).
    static String addType(TypeDescription td, JsonObject item, String kind,
        IEObjectProvider provider, Configuration config, boolean isExtensionProject)
    {
        if (isRefKind(kind))
        {
            MdObject target = resolveRefTarget(config, kind, asString(item.get("ref"))); //$NON-NLS-1$
            if (target == null)
            {
                return "Cannot resolve the reference target for kind '" + kind + "' ref '" //$NON-NLS-1$ //$NON-NLS-2$
                    + asString(item.get("ref")) + "'. Use {kind:'Ref', ref:'Type.Name'} or " //$NON-NLS-1$ //$NON-NLS-2$
                    + "{kind:'CatalogRef', ref:'Name'} and check the object exists." //$NON-NLS-1$
                    + extensionAdoptHint(isExtensionProject);
            }
            Type refType;
            try
            {
                // The generic getRefType(MdObject) dispatcher does NOT route Enum (it has a separate
                // overload) and THROWS AssertionError for kinds with no ref type (registers, reports,
                // ...). AssertionError is an Error, so it would escape the tool's catch(Exception) - // NOSONAR explanatory comment, not commented-out code
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
        if (primitive != null)
        {
            EObject proxy = provider.createProxy(primitive);
            if (!(proxy instanceof TypeItem))
            {
                return "Could not create the platform type '" + primitive + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            td.getTypes().add((TypeItem)proxy);
            applyQualifiers(td, item, primitive);
            return null;
        }

        String[] simpleTypeCandidates = platformSimpleTypeCandidates(kind);
        if (simpleTypeCandidates != null)
        {
            return addSimplePlatformType(td, provider, simpleTypeCandidates);
        }

        return "Unknown type kind '" + kind + "'. Use String / Number / Boolean / Date / ValueStorage / " //$NON-NLS-1$ //$NON-NLS-2$
            + "UUID, or a reference ({kind:'Ref', ref:'Type.Name'})."; //$NON-NLS-1$
    }

    /**
     * Resolves a NO-QUALIFIER platform simple type (ValueStorage / UUID) by trying each candidate
     * proxy name in order and adding the first one that resolves to a real {@link TypeItem}.
     * {@code createProxy} THROWS for a name the provider does not know (verified in issue #262), so
     * each attempt is guarded; this tolerates a platform-version rename of the same type (the
     * candidate list is name-tolerance, never a retry of a DIFFERENT type). Mirrors the try/catch
     * idiom {@link FormElementWriter#resolveType} already uses for the same reason.
     *
     * @param td the type description to append the resolved type to
     * @param provider the platform type provider
     * @param candidates the proxy names to try, in resolution order
     * @return {@code null} on success, or an actionable error naming every tried candidate name
     */
    private static String addSimplePlatformType(TypeDescription td, IEObjectProvider provider, String[] candidates)
    {
        for (String candidate : candidates)
        {
            TypeItem resolved = tryCreateTypeItem(provider, candidate);
            if (resolved != null)
            {
                td.getTypes().add(resolved);
                return null;
            }
        }
        return "Could not create the platform type. Tried: " + String.join(", ", candidates) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Creates the proxy for {@code name} and returns it as a {@link TypeItem}, or {@code null} on any failure. */
    private static TypeItem tryCreateTypeItem(IEObjectProvider provider, String name)
    {
        try
        {
            EObject proxy = provider.createProxy(name);
            return (proxy instanceof TypeItem) ? (TypeItem)proxy : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
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

    /**
     * The extension-adopt hint appended to an unresolved-reference error (issue #262 "Мелочь (UX)"): a
     * reference target that exists in the BASE configuration is simply invisible to an EXTENSION
     * project's resolvers until it is adopted, and the plain "not found" wording gives no clue why. Empty
     * (never {@code null}) for a base-configuration project, so callers can append it unconditionally.
     *
     * @param isExtensionProject whether the project being modified is a configuration EXTENSION
     * @return the hint sentence (with a leading space), or an empty string
     */
    static String extensionAdoptHint(boolean isExtensionProject)
    {
        return isExtensionProject
            ? " If this is an extension project, adopt the target object from the base " //$NON-NLS-1$
                + "configuration first (adopt_metadata_object) and retry." //$NON-NLS-1$
            : ""; //$NON-NLS-1$
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

    /**
     * Maps a NO-QUALIFIER platform simple type kind (ValueStorage / UUID, bilingual, case-insensitive)
     * to its candidate platform proxy NAMES, in resolution order, or {@code null} when {@code kind} is
     * neither. Unlike {@link #normalizePrimitive}, a kind here may carry MORE THAN ONE candidate name:
     * some platform versions expose the type under a different proxy name (issue #279), and
     * {@code createProxy} throws for a name it does not know, so the caller tries each name in turn -
     * {@link #addSimplePlatformType} does the trying. These kinds take no inline qualifiers
     * ({@link #applyQualifiers} is never invoked for them).
     *
     * @param kind the raw {@code kind} token from the spec
     * @return the candidate proxy names, or {@code null} when {@code kind} is not ValueStorage/UUID
     */
    static String[] platformSimpleTypeCandidates(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        switch (kind.trim().toLowerCase())
        {
            case "valuestorage": //$NON-NLS-1$
            case "хранилищезначения": //$NON-NLS-1$
                return new String[] { "ValueStorage" }; //$NON-NLS-1$
            case "uuid": //$NON-NLS-1$
            case "uniqueidentifier": //$NON-NLS-1$
            case "уникальныйидентификатор": //$NON-NLS-1$
                return new String[] { "UUID", "UniqueIdentifier" }; //$NON-NLS-1$ //$NON-NLS-2$
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
