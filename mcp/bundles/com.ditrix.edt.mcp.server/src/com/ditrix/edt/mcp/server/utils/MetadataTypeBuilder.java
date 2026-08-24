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
 * Primitive kinds: String / Number / Boolean / Date (qualifiers given inline); ValueStorage / UUID /
 * ValueTable / ValueTree are platform types that carry no qualifiers - the last two are in-memory
 * collections the platform accepts on a FORM attribute only (issue #295). A reference is
 * {@code {"kind":"Ref", "ref":"Type.Name"}} (the ref FQN is resolved bilingually) or
 * {@code {"kind":"CatalogRef", "ref":"Name"}}. The {@code types} list may mix several (a composite
 * type). The shape is validated before any platform call, so a malformed spec fails fast.
 * <p>
 * On a {@link TypeTarget#FORM_ATTRIBUTE} the vocabulary is not a fixed list: ANY type name the
 * platform version knows is accepted (ValueList, SpreadsheetDocument, Chart, GanttChart, Dendrogram,
 * Planner, GeographicalSchema, GraphicalSchema, StandardPeriod, TypeDescription, Picture, Color,
 * FormattedString, DataCompositionSettingsComposer, ...), in EITHER language - the platform type
 * provider indexes each type under both its English and its Russian name, so no alias table is kept
 * here (issue #369). Those types live only in a form's data, so every other target refuses them with
 * {@code formOnlyTypeRefusal}. {@code DynamicList} is refused even on a form attribute: it needs its
 * query too, which {@code modify_metadata}'s {@code queryText} path owns.
 */
public final class MetadataTypeBuilder
{
    /**
     * The RUSSIAN platform names of the primitives, parallel to {@link #EN_PRIMITIVE_NAMES}. Read-side
     * only: a resolved type answers its name in the configuration's language, while a spec always
     * spells its {@code kind} the way {@link #normalizePrimitive} maps it.
     */
    private static final String[] RU_PRIMITIVE_NAMES = {
        MetadataLanguageUtils.cp(0x0421, 0x0442, 0x0440, 0x043e, 0x043a, 0x0430), // Stroka
        MetadataLanguageUtils.cp(0x0427, 0x0438, 0x0441, 0x043b, 0x043e), // Chislo
        MetadataLanguageUtils.cp(0x0411, 0x0443, 0x043b, 0x0435, 0x0432, 0x043e), // Bulevo
        MetadataLanguageUtils.cp(0x0414, 0x0430, 0x0442, 0x0430) }; // Data

    /** The canonical ENGLISH platform names of the primitives, parallel to {@link #RU_PRIMITIVE_NAMES}. */
    private static final String[] EN_PRIMITIVE_NAMES = {"String", "Number", "Boolean", "Date"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Accepted JSON members for each kind-specific type-item grammar. */
    private static final String[] STRING_ITEM_MEMBERS = {"kind", "length", "fixed"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final String[] NUMBER_ITEM_MEMBERS = {"kind", "precision", "scale", "nonNegative"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final String[] DATE_ITEM_MEMBERS = {"kind", "fractions"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String[] REF_ITEM_MEMBERS = {"kind", "ref"}; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String[] MEMBERLESS_ITEM_MEMBERS = {"kind"}; //$NON-NLS-1$

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
        JsonArray types = typesEl.getAsJsonArray();
        for (int i = 0; i < types.size(); i++)
        {
            JsonElement itemEl = types.get(i);
            if (!itemEl.isJsonObject())
            {
                return "each entry of type.types must be an object like {kind:'String'}."; //$NON-NLS-1$
            }
            JsonObject item = itemEl.getAsJsonObject();
            String kind = asString(item.get("kind")); //$NON-NLS-1$
            if (kind == null || kind.isEmpty())
            {
                return "each type item needs a non-empty 'kind' (String/Number/Boolean/Date or a Ref)."; //$NON-NLS-1$
            }
            String memberError = validateItemMembers(item, kind, i);
            if (memberError != null)
            {
                return memberError;
            }
        }
        return null;
    }

    /**
     * Refuses a member that the item's OWN kind cannot consume. Keeping the sets kind-specific is
     * important: advertising the union would make a misplaced {@code length} on Number look valid and
     * preserve the same silent-drop ambiguity this validation removes. Non-primitive platform kinds
     * (ValueStorage / UUID / form-only values / an as-yet unknown kind) carry no inline qualifiers, so
     * their only accepted member is {@code kind}; unknown-kind resolution still reports the kind itself
     * later when the item has no extra member.
     */
    private static String validateItemMembers(JsonObject item, String kind, int index)
    {
        String primitive = normalizePrimitive(kind);
        String[] accepted;
        if ("String".equals(primitive)) //$NON-NLS-1$
        {
            accepted = STRING_ITEM_MEMBERS;
        }
        else if ("Number".equals(primitive)) //$NON-NLS-1$
        {
            accepted = NUMBER_ITEM_MEMBERS;
        }
        else if ("Date".equals(primitive)) //$NON-NLS-1$
        {
            accepted = DATE_ITEM_MEMBERS;
        }
        else if (isRefKind(kind))
        {
            accepted = REF_ITEM_MEMBERS;
        }
        else
        {
            // Boolean and every qualifier-free platform kind accept only `kind`.
            accepted = MEMBERLESS_ITEM_MEMBERS;
        }

        Set<String> allowed = new HashSet<>(Arrays.asList(accepted));
        for (String member : item.keySet())
        {
            if (!allowed.contains(member))
            {
                return "Unknown member '" + member + "' in type.types[" + index //$NON-NLS-1$ //$NON-NLS-2$
                    + "]. Accepted members: " + String.join(", ", accepted) + ". Remove '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + member + "' or use one of them."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * What the built {@link TypeDescription} will be attached to. This decides whether the IN-MEMORY
     * collection kinds (ValueTable / ValueTree) are acceptable: the platform materializes them only in
     * a form's data, never in the database, so a stored metadata attribute must reject them (issue
     * #295). EDT itself does NOT validate this - a ValueTable written into a {@code .mdo} attribute
     * passes a full revalidation silently and only breaks later, in the platform - which is exactly why
     * the refusal has to happen here.
     */
    public enum TypeTarget
    {
        /** A persisted metadata feature (an attribute, a resource, a predefined item's value, ...). */
        METADATA,
        /** A form attribute (or one of its columns) - the only place an in-memory collection lives. */
        FORM_ATTRIBUTE,
        /**
         * A data-composition (DCS) parameter's {@code valueType}. Not a stored metadata feature: the
         * refusal the METADATA target gives ("never in a stored metadata feature", "use ValueStorage")
         * would be untrue here, so this target owns its own wording, which claims only what this tool
         * actually does - it does not build the collection kinds for a DCS parameter (issue #295
         * review).
         */
        DCS_PARAMETER
    }

    /**
     * Builds the {@link TypeDescription} from a validated spec for a persisted METADATA feature.
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
        return build(spec, config, version, isExtensionProject, TypeTarget.METADATA);
    }

    /**
     * Builds the {@link TypeDescription} from a validated spec, like {@link #build(JsonElement,
     * Configuration, Version, boolean)}, for an explicit {@code typeTarget}. Only
     * {@link TypeTarget#FORM_ATTRIBUTE} accepts the in-memory collection kinds (issue #295); every
     * other target refuses them with an actionable error naming where they ARE allowed.
     *
     * @param spec the {@code type} value (object with a {@code types} array)
     * @param config the configuration (to resolve reference targets)
     * @param version the platform version (to create primitive type proxies)
     * @param isExtensionProject whether the project being modified is a configuration EXTENSION
     * @param typeTarget what the built type description will be attached to
     * @return the result (type or error)
     */
    public static Result build(JsonElement spec, Configuration config, Version version,
        boolean isExtensionProject, TypeTarget typeTarget)
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
            String err = addType(td, item, kind, provider, config, isExtensionProject, typeTarget);
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
        "Report", "DataProcessor", //$NON-NLS-1$ //$NON-NLS-2$
        // The standalone twins: an external data processor / report has exactly the same object
        // form with a main Object attribute (the committed ExternalObjects fixture is that shape),
        // and its produced types carry the objectType the seed reads. Leaving them out made
        // generateContent=true silently seed nothing on an external owner (issue #309 review).
        "ExternalDataProcessor", "ExternalReport")); //$NON-NLS-1$ //$NON-NLS-2$

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
        IEObjectProvider provider, Configuration config, boolean isExtensionProject, TypeTarget typeTarget)
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
        if (simpleTypeCandidates.length > 0)
        {
            if (isCollectionKind(kind) && typeTarget != TypeTarget.FORM_ATTRIBUTE)
            {
                return collectionKindRefusal(kind, typeTarget);
            }
            return addSimplePlatformType(td, provider, simpleTypeCandidates);
        }

        // Any OTHER type the platform knows for this version - ValueList, SpreadsheetDocument, Chart,
        // StandardPeriod, TypeDescription, ... (issue #369). The provider indexes every type under BOTH
        // its English and its Russian name, so the bilingual spelling resolves with no alias table here.
        TypeItem platformType = tryCreateTypeItem(provider, kind);
        if (platformType != null)
        {
            if (isDynamicListKind(kind))
            {
                return dynamicListKindRefusal(kind);
            }
            if (typeTarget != TypeTarget.FORM_ATTRIBUTE)
            {
                return formOnlyTypeRefusal(kind, typeTarget);
            }
            td.getTypes().add(platformType);
            return null;
        }

        return "Unknown type kind '" + kind + "'. Use String / Number / Boolean / Date / ValueStorage / " //$NON-NLS-1$ //$NON-NLS-2$
            + "UUID, ValueTable / ValueTree (in-memory collections - a FORM attribute only), or a " //$NON-NLS-1$
            + "reference ({kind:'Ref', ref:'Type.Name'}). On a FORM attribute any platform type name " //$NON-NLS-1$
            + "also works (ValueList / SpreadsheetDocument / Chart / StandardPeriod / ..., English or " //$NON-NLS-1$
            + "Russian) - this one names no type this platform version knows."; //$NON-NLS-1$
    }

    /** The platform pseudo-type name a form list attribute carries, in both languages. */
    private static final String RU_DYNAMIC_LIST = MetadataLanguageUtils.cp(0x0414, 0x0438, 0x043d, 0x0430,
        0x043c, 0x0438, 0x0447, 0x0435, 0x0441, 0x043a, 0x0438, 0x0439, 0x0421, 0x043f, 0x0438, 0x0441,
        0x043e, 0x043a); // DinamicheskijSpisok

    /**
     * Whether {@code kind} names the {@code DynamicList} pseudo-type. It resolves like any other platform
     * type, but a dynamic list is NOT just a value type: it also needs its {@code DynamicListExtInfo}
     * query settings, which {@code modify_metadata}'s {@code queryText} / {@code mainTable} path owns
     * (and which prompts its own conversion consent). Building it from a bare {@code type} spec would
     * produce a list with no query, so the spec refuses it and names the property that does the job.
     *
     * @param kind the raw {@code kind} token from the spec
     * @return {@code true} for the dynamic-list pseudo-type in either language
     */
    static boolean isDynamicListKind(String kind)
    {
        if (kind == null)
        {
            return false;
        }
        String k = kind.trim();
        return DYNAMIC_LIST_TYPE.equalsIgnoreCase(k) || RU_DYNAMIC_LIST.equalsIgnoreCase(k);
    }

    /** The refusal of a bare {@code DynamicList} type spec, naming the property that builds one. */
    private static String dynamicListKindRefusal(String kind)
    {
        return "Type kind '" + kind + "' is the dynamic-list pseudo-type, which a bare type spec " //$NON-NLS-1$ //$NON-NLS-2$
            + "cannot build: a dynamic list also needs its query. Convert the form attribute with the " //$NON-NLS-1$
            + "'queryText' property instead (optionally with 'customQuery' / 'mainTable'), e.g. " //$NON-NLS-1$
            + "{name:'queryText', value:'SELECT Ref FROM Catalog.Products'}."; //$NON-NLS-1$
    }

    /**
     * The refusal of a platform type this version DOES know but which this tool builds only for a FORM
     * attribute (SpreadsheetDocument, Chart, ValueList, StandardPeriod, ...). Worded per TARGET like
     * {@link #collectionKindRefusal}, and worded as a statement about what this TOOL builds rather than
     * about what the platform can store: the reachable set here is the whole platform type system, so a
     * blanket "the database cannot hold this" would over-claim for its edges. What every target can say
     * truthfully is where the type IS accepted.
     * <p>
     * The type is named as RECOGNIZED (it is) rather than as unknown - the "Unknown type kind" wording
     * sent callers hunting for a spelling mistake that was not there (issue #369).
     *
     * @param kind the requested kind, as the caller spelled it
     * @param typeTarget what the type description was being built for
     * @return the actionable refusal
     */
    private static String formOnlyTypeRefusal(String kind, TypeTarget typeTarget)
    {
        if (typeTarget == TypeTarget.DCS_PARAMETER)
        {
            return "Type kind '" + kind + "' is a platform value this tool does not build for a " //$NON-NLS-1$ //$NON-NLS-2$
                + "data-composition parameter: a FORM attribute (fqn " //$NON-NLS-1$
                + "'Type.Object.Form.FormName.Attribute.Name') is the only target that accepts it. Give " //$NON-NLS-1$
                + "the parameter a primitive or a reference type instead."; //$NON-NLS-1$
        }
        return "Type kind '" + kind + "' is a platform value this tool builds only for a FORM attribute " //$NON-NLS-1$ //$NON-NLS-2$
            + "(fqn 'Type.Object.Form.FormName.Attribute.Name'), not for a stored metadata feature. Set " //$NON-NLS-1$
            + "it on a form attribute; a stored feature takes String / Number / Boolean / Date / " //$NON-NLS-1$
            + "ValueStorage / UUID or a reference ({kind:'Ref', ref:'Type.Name'})."; //$NON-NLS-1$
    }

    /**
     * The refusal of an in-memory collection kind, worded for the TARGET that refused it. The stored
     * metadata wording ("never in a stored metadata feature", "use {@code ValueStorage}") is TRUE only
     * for a persisted feature; a DCS parameter is neither stored nor served by {@code ValueStorage}, so
     * repeating it there would state a platform fact that does not hold and give advice that does not
     * apply (issue #295 review). Each target claims only what is true of it.
     *
     * @param kind the requested collection kind
     * @param typeTarget what the type description was being built for
     * @return the actionable refusal
     */
    private static String collectionKindRefusal(String kind, TypeTarget typeTarget)
    {
        if (typeTarget == TypeTarget.DCS_PARAMETER)
        {
            return "Type kind '" + kind + "' is an IN-MEMORY collection, and this tool does not build " //$NON-NLS-1$ //$NON-NLS-2$
                + "one for a data-composition parameter: a FORM attribute (fqn " //$NON-NLS-1$
                + "'Type.Object.Form.FormName.Attribute.Name') is the only target that accepts the " //$NON-NLS-1$
                + "collection kinds. Give the parameter a primitive or a reference type instead."; //$NON-NLS-1$
        }
        return "Type kind '" + kind + "' is an IN-MEMORY collection: the platform holds it " //$NON-NLS-1$ //$NON-NLS-2$
            + "only in a FORM attribute (fqn 'Type.Object.Form.FormName.Attribute.Name'), " //$NON-NLS-1$
            + "never in a stored metadata feature. Set it on a form attribute, or use " //$NON-NLS-1$
            + "{kind:'ValueStorage'} to persist arbitrary data here."; //$NON-NLS-1$
    }

    /**
     * Resolves a NO-QUALIFIER platform type (ValueStorage / UUID / ValueTable / ValueTree) by trying
     * each candidate proxy name in order and adding the first one that resolves to a real {@link TypeItem}.
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

    /**
     * Creates the proxy for {@code name} and returns it as a {@link TypeItem}, or {@code null} on any
     * failure - including a {@code null} provider, which the unknown-kind probe reaches when a caller
     * has none (the tests exercise the refusal branches without one).
     */
    private static TypeItem tryCreateTypeItem(IEObjectProvider provider, String name)
    {
        if (provider == null)
        {
            return null;
        }
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
     * Maps a NO-QUALIFIER platform type kind (ValueStorage / UUID / ValueTable / ValueTree, bilingual,
     * case-insensitive) to its candidate platform proxy NAMES, in resolution order, or an empty array
     * when {@code kind} is none of them. The collection kinds (ValueTable / ValueTree) are in-memory
     * types: the platform stores them only in a FORM attribute, never in a database attribute (issue
     * #295). Unlike {@link #normalizePrimitive}, a kind here may carry MORE THAN ONE candidate name:
     * some platform versions expose the type under a different proxy name (issue #279), and
     * {@code createProxy} throws for a name it does not know, so the caller tries each name in turn -
     * {@link #addSimplePlatformType} does the trying. These kinds take no inline qualifiers
     * ({@link #applyQualifiers} is never invoked for them).
     *
     * @param kind the raw {@code kind} token from the spec
     * @return the candidate proxy names, or an empty array when {@code kind} is not one of the
     *             no-qualifier platform types (ValueStorage / UUID / ValueTable / ValueTree)
     */
    static String[] platformSimpleTypeCandidates(String kind)
    {
        if (kind == null)
        {
            return new String[0];
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
            case "valuetable": //$NON-NLS-1$
            case "таблицазначений": //$NON-NLS-1$
                return new String[] { "ValueTable" }; //$NON-NLS-1$
            case "valuetree": //$NON-NLS-1$
            case "деревозначений": //$NON-NLS-1$
                return new String[] { "ValueTree" }; //$NON-NLS-1$
            default:
                return new String[0];
        }
    }

    /**
     * Whether {@code kind} names an IN-MEMORY collection (ValueTable / ValueTree). Keyed off the
     * CANONICAL name {@link #platformSimpleTypeCandidates} resolves to, so the bilingual alias list
     * stays in exactly one place.
     *
     * @param kind the raw {@code kind} token from the spec
     * @return {@code true} for a collection kind, {@code false} for anything else
     */
    public static boolean isCollectionKind(String kind)
    {
        String[] candidates = platformSimpleTypeCandidates(kind);
        return candidates.length > 0
            && ("ValueTable".equals(candidates[0]) || "ValueTree".equals(candidates[0])); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether {@code kindOrTypeName} names a platform type that owns NO addressable member - nothing a
     * dotted path can continue into. Answered for the spec vocabulary (a {@code kind} token) and for a
     * RESOLVED platform type name alike: the two coincide for every type this builder produces without
     * a reference, and the Russian names ARE the Russian kind aliases.
     *
     * <p>The classification is DERIVED from the maps a spec is resolved with -
     * {@link #platformSimpleTypeCandidates} for the no-qualifier platform types and
     * {@link #normalizePrimitive} for the qualified primitives - minus the collections
     * ({@link #isCollectionKind}), which own their {@code columns}. A kind added to the builder is
     * therefore classified here by construction, instead of being re-listed by every caller that needs
     * to know whether a path may continue: the caller-side list knew String / Number / Boolean / Date
     * and had silently fallen behind ValueStorage and UUID (issue #295 review).</p>
     *
     * <p>Not asked of the platform type system, on purpose. The query does exist
     * ({@code Type.getContextDef().allProperties()}), but the types on a form attribute are
     * {@link IEObjectProvider} PROXIES - which is why the readers go through {@code McoreUtil}, whose
     * accessors are proxy-aware - and a raw feature read of an unresolved proxy answers "no
     * properties", which is exactly the answer that REFUSES a path. A predicate whose failure mode is
     * indistinguishable from its refusal verdict cannot be used to refuse. Reference kinds answer
     * {@code false}: their members live in the metadata, which this classification deliberately does
     * not read.</p>
     *
     * @param kindOrTypeName a spec {@code kind} token or a resolved platform type name, either language
     * @return {@code true} only for a type this builder knows to own no addressable member
     */
    public static boolean isMemberlessType(String kindOrTypeName)
    {
        if (kindOrTypeName == null || kindOrTypeName.isEmpty())
        {
            return false;
        }
        if (platformSimpleTypeCandidates(kindOrTypeName).length > 0)
        {
            // ValueStorage / UUID hold one opaque value; ValueTable / ValueTree own their columns.
            return !isCollectionKind(kindOrTypeName);
        }
        return normalizePrimitiveTypeName(kindOrTypeName) != null;
    }

    /**
     * The canonical platform name of a PRIMITIVE, accepting a resolved Russian type name as well as
     * every EN {@code kind} token {@link #normalizePrimitive} maps. Kept beside it so the primitive
     * vocabulary lives in one place; the Russian names are read-side only (a spec still spells its
     * kinds the way the tool documents them).
     *
     * @param typeName a {@code kind} token or a resolved platform type name
     * @return the canonical EN name, or {@code null} when it names no primitive
     */
    private static String normalizePrimitiveTypeName(String typeName)
    {
        String canonical = normalizePrimitive(typeName);
        if (canonical != null)
        {
            return canonical;
        }
        String trimmed = typeName.trim();
        for (int i = 0; i < RU_PRIMITIVE_NAMES.length; i++)
        {
            if (RU_PRIMITIVE_NAMES[i].equalsIgnoreCase(trimmed))
            {
                return EN_PRIMITIVE_NAMES[i];
            }
        }
        return null;
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
