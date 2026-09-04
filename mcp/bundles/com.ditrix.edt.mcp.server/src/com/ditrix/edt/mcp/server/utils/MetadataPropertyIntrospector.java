/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StyleItem;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com.google.gson.JsonArray;

/**
 * Introspects the ASSIGNABLE properties of a metadata {@link EObject}: which structural features a
 * client may set, what kind of value each takes, the allowed values (for an enum), and the current
 * value. This is the single source of truth shared by the {@code get_metadata_details} "assignable"
 * view (human-readable) and {@code modify_metadata}'s validation (availability + value-validity).
 *
 * <p>A feature is considered assignable when it is changeable and not derived / transient / volatile,
 * and is not a containment reference (those are normally child collections - attributes / tabular
 * sections / forms / commands - created via {@code create_metadata}, not set as a scalar value).
 * Small containment value shapes with an explicit wire grammar are admitted separately.</p>
 */
public final class MetadataPropertyIntrospector
{
    /**
     * The {@code AdjustableBoolean} flag the wire boolean of an {@link ValueKind#ADJUSTABLE_BOOLEAN}
     * property addresses. Its sibling {@code for} list (per-role overrides) is left untouched.
     */
    public static final String COMMON_FEATURE = "common"; //$NON-NLS-1$

    /** The kind of value an assignable property takes - drives validation and rendering. */
    public enum ValueKind
    {
        /** A free-text string (e.g. name, comment). */
        STRING,
        /** A boolean flag. */
        BOOLEAN,
        /** An integer (e.g. a length / precision). */
        INTEGER,
        /** A 64-bit integer. */
        LONG,
        /** An enum: only one of {@link PropertyInfo#allowedValues} is valid. */
        ENUM,
        /**
         * A many-valued enum attribute, set by replacing the whole list with enum literals. The EDT
         * metamodel census found three such features in {@code MdClass.xcore}:
         * {@code BasicForm.usePurposes}, {@code Configuration.usePurposes}, and
         * {@code Configuration.requiredMobileApplicationPermissions}; {@code Form.xcore} has none.
         * The measured rule is therefore generic across the shape rather than tied to one property.
         */
        MANY_ENUM,
        /** The localized synonym map, keyed by language code. */
        LOCALIZED_STRING,
        /** A 1C data type (mcore {@code TypeDescription}); set via the structured type form. */
        TYPE_DESCRIPTION,
        /** A single reference to another metadata object, set by its FQN. */
        REFERENCE,
        /** A list of references to other metadata objects, set (replaced) by an array of FQNs. */
        MANY_REFERENCE,
        /**
         * A many-valued containment of mcore {@code Value} objects, set by replacing the whole list
         * from a JSON array of strings. The EDT metamodel census found this shape exactly once in
         * {@code MdClass.xcore} ({@code WebService.xdtoPackages}) and zero times in
         * {@code Form.xcore}; the measured generic rule is therefore narrower than a feature-name
         * special case while still leaving every other many containment excluded as a child list.
         */
        MCORE_VALUE_LIST,
        /**
         * A contained mcore {@code Picture}: set by {@code StdPicture.<Name>} or
         * {@code StdExtPicture.<Name>} for a platform picture, or {@code CommonPicture.<Name>} for a
         * configuration picture. Like
         * {@link #STYLE_VALUE}, this is a single-valued containment reference classified explicitly.
         */
        PICTURE,
        /**
         * A contained mcore {@link QName}: set by {@code {name, nsUri}} or the compact
         * {@code {nsUri}name} spelling. Like {@link #STYLE_VALUE}, this is a single-valued containment
         * reference classified explicitly.
         */
        QNAME,
        /**
         * A {@link com._1c.g5.v8.dt.metadata.mdclass.StyleItem StyleItem}'s {@code value}: an mcore
         * {@code Value} (a Color or a Font) set via the structured {@code {color:...}} / {@code {font:...}}
         * form (see {@link StyleValueBuilder}). It is a single-valued containment reference - excluded
         * from the generic containment-ref filter, so it is classified explicitly.
         */
        STYLE_VALUE,
        /**
         * A contained {@link com._1c.g5.v8.dt.metadata.mdclass.AdjustableBoolean AdjustableBoolean}:
         * a flag the designer stores as a nested object ({@code common}) that MAY additionally carry
         * per-role / per-functional-option overrides ({@code for}). On the wire it is a plain boolean
         * addressing {@code common}; the {@code for} overrides are preserved untouched.
         *
         * <p>Like {@link #STYLE_VALUE} this is a single-valued containment reference, so the generic
         * containment filter would drop it - it is classified explicitly. Classifying it by its TARGET
         * TYPE rather than by feature name covers every such flag with one rule: a form attribute's
         * {@code view} / {@code edit} (issue #382), a form item's {@code userVisible}, a form command's
         * {@code use}.</p>
         */
        ADJUSTABLE_BOOLEAN
    }

    /** The introspected schema of one assignable property. */
    public static final class PropertyInfo
    {
        /** The programmatic feature name (e.g. {@code "comment"}, {@code "indexing"}). */
        public final String name;
        /** The value kind. */
        public final ValueKind valueKind;
        /** The current value rendered as text (may be {@code null} / empty). */
        public final String currentValue;
        /**
         * The same value in the form that answers "is this the SAME value?", which for some kinds
         * is not the form that answers "what should a reader see?".
         * <p>
         * {@link #currentValue} is display text, and display text is allowed to drop what a reader
         * does not need. A metadata reference renders as the target's bare {@code Name} - the
         * shortest thing that reads well beside the property - so a broad reference holding
         * {@code Catalog.Foo} on one side and {@code Document.Foo} on the other renders the same
         * word {@code Foo} for both. A consumer that COMPARES two sides by their rendered text
         * therefore called two different targets equal. This field carries the target qualified by
         * its metadata type, so a comparison sees the difference the display legitimately hides.
         * <p>
         * For every other kind it is the same string as {@link #currentValue}: those renderings
         * already distinguish the values they show. It is never {@code null} while
         * {@code currentValue} is not, so a caller needing an identity never needs a second branch.
         * <p>
         * <b>The reverse DOES happen, and it is the point of the field:</b> a reference target that
         * is there but carries no printable name renders an EMPTY cell and still has an identity -
         * its type. A blank cell is the right thing to print for a nameless target (a name column
         * must not invent one), and it is the WRONG thing to compare, because an unset reference
         * prints the same blank. Nothing but this field separates "points at an unnamed object" from
         * "points at nothing". See {@link #referenceTarget(Object)}.
         * <p>
         * <b>What it does NOT establish:</b> two targets of the same metadata type carrying the
         * same {@code Name} under different owners are still one string here. It is a SEMANTIC
         * identity, and it has to be - the two sides of a comparison are different models, so an
         * object-level identity (a resource URI, a BM id) would make every logically identical
         * target look different, which is the opposite error and the worse one.
         */
        public final String valueIdentity;
        /**
         * Whether reading or rendering the value FAILED, as opposed to the property being empty.
         * <p>
         * Both outcomes leave {@link #currentValue} {@code null}, and until this flag existed they
         * were the same answer to a reader: a dangling proxy whose {@code eGet} threw rendered as
         * the same blank as a property nobody had set. A consumer that shows values side by side
         * then presents a failure as a fact about the model - and one that COMPARES them calls two
         * sides equal because neither could be read.
         */
        public final boolean readFailed;
        /** For {@link ValueKind#ENUM} / {@link ValueKind#MANY_ENUM}: allowed literal names. */
        public final List<String> allowedValues;
        /** The owning {@link EStructuralFeature} (for the applier; not serialized). */
        public final EStructuralFeature feature;
        /**
         * Whether this property lives on the element's nested {@code <extInfo>} EObject (a layout /
         * kind-specific sub-object) rather than on the element itself. A caller that WRITES the value
         * must therefore route the {@code eSet} to the extInfo holder (creating it when absent), not to
         * the element. Always {@code false} for a direct feature - the only case on the mdclass path.
         */
        public final boolean onExtInfo;

        PropertyInfo(String name, ValueKind valueKind, String currentValue, List<String> allowedValues,
            EStructuralFeature feature)
        {
            this(name, valueKind, currentValue, allowedValues, feature, false);
        }

        PropertyInfo(String name, ValueKind valueKind, String currentValue, List<String> allowedValues,
            EStructuralFeature feature, boolean onExtInfo)
        {
            this(name, valueKind, currentValue, allowedValues, feature, onExtInfo, false);
        }

        PropertyInfo(String name, ValueKind valueKind, String currentValue, List<String> allowedValues,
            EStructuralFeature feature, boolean onExtInfo, boolean readFailed)
        {
            this(name, valueKind, currentValue, null, allowedValues, feature, onExtInfo, readFailed);
        }

        PropertyInfo(String name, ValueKind valueKind, String currentValue, String valueIdentity,
            List<String> allowedValues, EStructuralFeature feature, boolean onExtInfo,
            boolean readFailed)
        {
            this.name = name;
            this.valueKind = valueKind;
            this.currentValue = currentValue;
            // A kind with nothing to qualify identifies itself by what it renders. The fallback
            // lives here rather than at each call site so the two fields cannot drift apart.
            this.valueIdentity = valueIdentity == null ? currentValue : valueIdentity;
            this.allowedValues = allowedValues == null ? Collections.emptyList()
                : Collections.unmodifiableList(allowedValues);
            this.feature = feature;
            this.onExtInfo = onExtInfo;
            this.readFailed = readFailed;
        }
    }

    private MetadataPropertyIntrospector()
    {
        // utility class
    }

    /**
     * Returns the assignable properties of {@code obj}, in the model's feature order.
     *
     * @param obj the object to introspect (e.g. a Catalog, a CatalogAttribute, a Dimension)
     * @return the assignable property schemas (never {@code null}; empty if {@code obj} is null)
     */
    public static List<PropertyInfo> introspect(EObject obj)
    {
        List<PropertyInfo> result = new ArrayList<>();
        if (obj == null)
        {
            return result;
        }
        for (EStructuralFeature feature : obj.eClass().getEAllStructuralFeatures()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            if (!isAssignable(feature))
            {
                continue;
            }
            ValueKind kind = classify(feature);
            if (kind == null)
            {
                continue;
            }
            Rendered current = renderCurrent(obj, feature, kind);
            result.add(new PropertyInfo(feature.getName(), kind, current.text, current.identity,
                allowedValuesFor(feature, kind), feature, false, current.failed));
        }
        return result;
    }

    /**
     * Extends {@link #introspect(EObject)} with the assignable properties that live on {@code element}'s
     * nested {@code <extInfo>} EObject - a general, model-agnostic path for ANY element that carries an
     * extInfo (e.g. a form group's {@code UsualGroupExtInfo} layout props). The direct-element
     * properties come first (each with {@code onExtInfo == false}); the {@code extInfoEClass}'s
     * assignable features are then appended (each with {@code onExtInfo == true}), skipping any whose
     * name collides with a direct feature (DIRECT-precedence). The extInfo properties are listed from
     * their EClass only (no instance), so their {@code currentValue} is {@code null}; use
     * {@link #introspect(EObject, EObject)} to also render the current values from a live extInfo
     * instance.
     *
     * <p>{@code extInfoEClass == null} - the mdclass path, where an object has no extInfo - makes this
     * exactly {@link #introspect(EObject)}.</p>
     *
     * @param element the element to introspect (e.g. a form group)
     * @param extInfoEClass the element's concrete extInfo EClass, or {@code null} when it has none
     * @return the direct-then-extInfo assignable property schemas (never {@code null})
     */
    public static List<PropertyInfo> introspect(EObject element, EClass extInfoEClass)
    {
        return introspectWithExtInfo(element, extInfoEClass, null);
    }

    /**
     * Like {@link #introspect(EObject, EClass)} but reads the extInfo properties' CURRENT values from
     * the live {@code extInfo} instance (whose EClass supplies the feature list). {@code extInfo == null}
     * - the element has no extInfo yet - makes this exactly {@link #introspect(EObject)}.
     *
     * @param element the element to introspect
     * @param extInfo the element's live extInfo instance, or {@code null} when it has none
     * @return the direct-then-extInfo assignable property schemas, extInfo currents rendered
     */
    public static List<PropertyInfo> introspect(EObject element, EObject extInfo)
    {
        return introspectWithExtInfo(element, extInfo == null ? null : extInfo.eClass(), extInfo);
    }

    /**
     * Finds the assignable property named {@code name} (case-insensitive) on {@code obj}, with the
     * current value rendered.
     *
     * @param obj the object
     * @param name the feature name
     * @return the property info, or {@code null} if no such assignable property exists
     */
    public static PropertyInfo find(EObject obj, String name)
    {
        if (obj == null || name == null)
        {
            return null;
        }
        for (PropertyInfo info : introspect(obj))
        {
            if (info.name.equalsIgnoreCase(name))
            {
                return info;
            }
        }
        return null;
    }

    /**
     * Lightweight variant of {@link #find}: locates and classifies ONLY the matched feature and
     * skips the current-value rendering ({@code currentValue} stays {@code null}). {@link #find}
     * runs the full {@link #introspect}, which renders the current value (an {@code eGet} + proxy +
     * type rendering) for EVERY assignable feature of the object - per-property validation (e.g.
     * {@code modify_metadata}'s prepare step, on the UI thread) never reads {@code currentValue},
     * so it must not pay that cost N times.
     *
     * @param obj the object
     * @param name the feature name (case-insensitive)
     * @return the property info WITHOUT a rendered current value, or {@code null} if no such
     *         assignable property exists
     */
    public static PropertyInfo findFeature(EObject obj, String name)
    {
        if (obj == null || name == null)
        {
            return null;
        }
        for (EStructuralFeature feature : obj.eClass().getEAllStructuralFeatures()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            if (!feature.getName().equalsIgnoreCase(name) || !isAssignable(feature))
            {
                continue;
            }
            ValueKind kind = classify(feature);
            if (kind == null)
            {
                continue;
            }
            return new PropertyInfo(feature.getName(), kind, null, allowedValuesFor(feature, kind),
                feature);
        }
        return null;
    }

    /**
     * Extends {@link #findFeature(EObject, String)} to also resolve a property that lives on
     * {@code element}'s nested extInfo instance. A DIRECT feature of {@code element} wins on a name
     * collision (returned with {@code onExtInfo == false}); only when the element has no such direct
     * assignable feature is {@code extInfo}'s feature returned (with {@code onExtInfo == true}), so the
     * caller can route the write to the extInfo holder. Like {@link #findFeature(EObject, String)} the
     * current value is NOT rendered.
     *
     * @param element the element (e.g. a form group)
     * @param extInfo the element's live extInfo instance, or {@code null}
     * @param name the feature name (case-insensitive)
     * @return the property info - its {@code onExtInfo} telling the caller which receiver to write - or
     *         {@code null} if neither the element nor its extInfo has such an assignable property
     */
    public static PropertyInfo findFeature(EObject element, EObject extInfo, String name)
    {
        PropertyInfo direct = findFeature(element, name);
        if (direct != null)
        {
            return direct;
        }
        PropertyInfo onExt = findFeature(extInfo, name);
        if (onExt == null)
        {
            return null;
        }
        return new PropertyInfo(onExt.name, onExt.valueKind, onExt.currentValue, onExt.valueIdentity,
            onExt.allowedValues, onExt.feature, true, onExt.readFailed);
    }

    /**
     * Returns the assignable property names of {@code obj}, for an actionable "available properties"
     * error hint. Names-only iteration: no current value is rendered.
     *
     * @param obj the object
     * @return the assignable feature names (never {@code null})
     */
    public static List<String> assignableNames(EObject obj)
    {
        List<String> names = new ArrayList<>();
        if (obj == null)
        {
            return names;
        }
        for (EStructuralFeature feature : obj.eClass().getEAllStructuralFeatures())
        {
            if (isAssignable(feature) && classify(feature) != null)
            {
                names.add(feature.getName());
            }
        }
        return names;
    }

    /**
     * Extends {@link #assignableNames(EObject)} with the assignable feature names on {@code element}'s
     * nested extInfo (its {@code extInfoEClass}), so an actionable "available properties" hint covers
     * the extInfo layout props too. Direct names come first; an extInfo name that collides with a
     * direct one is dropped (DIRECT-precedence). {@code extInfoEClass == null} makes this exactly
     * {@link #assignableNames(EObject)}.
     *
     * @param element the element
     * @param extInfoEClass the element's concrete extInfo EClass, or {@code null}
     * @return the direct-then-extInfo assignable feature names (never {@code null})
     */
    public static List<String> assignableNames(EObject element, EClass extInfoEClass)
    {
        List<String> names = assignableNames(element);
        if (extInfoEClass == null)
        {
            return names;
        }
        for (EStructuralFeature feature : extInfoEClass.getEAllStructuralFeatures())
        {
            if (isAssignable(feature) && classify(feature) != null
                && !containsIgnoreCase(names, feature.getName()))
            {
                names.add(feature.getName());
            }
        }
        return names;
    }

    /**
     * Resolves an enum input string to its {@link EEnumLiteral} on an enum feature, case-insensitively
     * by literal or by name.
     *
     * @param feature the enum feature
     * @param value the input value
     * @return the matching literal, or {@code null} if the value is not a valid literal
     */
    public static EEnumLiteral resolveEnumLiteral(EStructuralFeature feature, String value)
    {
        EEnum eEnum = enumTypeOf(feature);
        if (eEnum == null || value == null)
        {
            return null;
        }
        for (EEnumLiteral literal : eEnum.getELiterals())
        {
            if (value.equalsIgnoreCase(literal.getLiteral()) || value.equalsIgnoreCase(literal.getName()))
            {
                return literal;
            }
        }
        return null;
    }

    // ---- internals ------------------------------------------------------------------------------

    /**
     * Shared body of the extInfo-aware {@link #introspect(EObject, EClass)} /
     * {@link #introspect(EObject, EObject)}: the direct properties of {@code element} followed by the
     * assignable features of {@code extInfoEClass} (DIRECT-precedence on a name collision), the latter
     * marked {@code onExtInfo}, their current value rendered from {@code extInfo} when it is non-null.
     */
    private static List<PropertyInfo> introspectWithExtInfo(EObject element, EClass extInfoEClass,
        EObject extInfo)
    {
        List<PropertyInfo> result = introspect(element);
        if (extInfoEClass == null)
        {
            return result;
        }
        List<String> directNames = new ArrayList<>();
        for (PropertyInfo p : result)
        {
            directNames.add(p.name);
        }
        for (EStructuralFeature feature : extInfoEClass.getEAllStructuralFeatures())
        {
            if (!isAssignable(feature) || containsIgnoreCase(directNames, feature.getName()))
            {
                continue;
            }
            ValueKind kind = classify(feature);
            if (kind != null)
            {
                Rendered current = extInfo != null ? renderCurrent(extInfo, feature, kind) : Rendered.ABSENT;
                result.add(new PropertyInfo(feature.getName(), kind, current.text, current.identity,
                    allowedValuesFor(feature, kind), feature, true, current.failed));
            }
        }
        return result;
    }

    /** Whether {@code names} already contains {@code name}, case-insensitively (DIRECT-precedence check). */
    private static boolean containsIgnoreCase(List<String> names, String name)
    {
        for (String existing : names)
        {
            if (existing.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssignable(EStructuralFeature feature)
    {
        // Only the EMF mutability gates here; classify() returns null to exclude the rest
        // (child-collection containment refs and object references are not simple values).
        return feature != null && !feature.isDerived() && !feature.isTransient()
            && !feature.isVolatile() && feature.isChangeable();
    }

    private static ValueKind classify(EStructuralFeature feature)
    {
        // A StyleItem's `value` is a single-valued containment ref to an mcore Value (Color / Font).
        // It is assignable (the style item's whole point), but it is a containment ref so the generic
        // filter below would drop it - classify it explicitly as STYLE_VALUE.
        if (isStyleItemValue(feature))
        {
            return ValueKind.STYLE_VALUE;
        }
        if (isAdjustableBoolean(feature))
        {
            return ValueKind.ADJUSTABLE_BOOLEAN;
        }
        if (feature instanceof EAttribute)
        {
            return classifyAttribute((EAttribute)feature);
        }
        if (feature instanceof EReference)
        {
            return classifyReference((EReference)feature);
        }
        return null;
    }

    /** Classifies an attribute feature by its data type and, for enum attributes, multiplicity. */
    private static ValueKind classifyAttribute(EAttribute feature)
    {
        EClassifier type = feature.getEAttributeType();
        if (type instanceof EEnum)
        {
            return feature.isMany() ? ValueKind.MANY_ENUM : ValueKind.ENUM;
        }
        String typeName = type != null ? type.getInstanceClassName() : null;
        if ("boolean".equals(typeName) || "java.lang.Boolean".equals(typeName)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ValueKind.BOOLEAN;
        }
        if ("int".equals(typeName) || "java.lang.Integer".equals(typeName)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ValueKind.INTEGER;
        }
        if ("long".equals(typeName) || "java.lang.Long".equals(typeName)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ValueKind.LONG;
        }
        return ValueKind.STRING;
    }

    /**
     * Classifies a reference feature: the localized synonym map and the explicitly-supported contained
     * values (TypeDescription, Picture and QName), plus the measured many-containment mcore Value-list
     * shape, are assignable; a non-containment reference to an {@code MdObject} is a plain object
     * reference (single or many); every other reference is excluded ({@code null}).
     *
     * <p>A {@link com._1c.g5.v8.dt.metadata.mdclass.BasicCommand#getGroup() BasicCommand.group} feature
     * is declared against {@code com._1c.g5.v8.dt.mcore.CommandGroup} - the base interface both the
     * platform's {@code StandardCommandGroup} (a built-in group, addressed by an enum category, not an
     * FQN) and the metadata {@code com._1c.g5.v8.dt.metadata.mdclass.CommandGroup} (a real top
     * {@code MdObject}, e.g. {@code CommandGroup.Sales}) implement. The generic MdObject check above
     * would miss it (the DECLARED target type is the mcore interface, not the mdclass one), so a
     * reference declared against that mcore interface is admitted too - issue #262. Only the metadata
     * {@code CommandGroup} resolves by FQN, so WRITING a {@code StandardCommandGroup} stays out of
     * scope (see {@code ModifyMetadataTool}'s reference-not-found handling) - but what a feature
     * admits, it must also READ: an admitted target that is present is rendered and identified by
     * {@link #referenceTarget}, never reported as an empty property.</p>
     */
    private static ValueKind classifyReference(EReference ref)
    {
        // The synonym (and other localized strings) is a containment map-entry reference reached
        // via getSynonym(); the remaining containment exceptions are explicit small value types.
        // Every other containment reference is a child/owned-object relation, not an assignable value.
        if ("synonym".equals(ref.getName()) || isMapEntry(ref)) //$NON-NLS-1$
        {
            return ValueKind.LOCALIZED_STRING;
        }
        if (isTypeDescription(ref))
        {
            return ValueKind.TYPE_DESCRIPTION;
        }
        if (isContainedValue(ref, McorePackage.Literals.PICTURE, true))
        {
            return ValueKind.PICTURE;
        }
        if (isContainedValue(ref, McorePackage.Literals.QNAME, false))
        {
            return ValueKind.QNAME;
        }
        // Census of the EDT sources: `contains Value[]` occurs exactly once in MdClass.xcore
        // (WebService.xdtoPackages) and zero times in Form.xcore. Admit that measured shape while
        // keeping every other many containment classified as a child collection and excluded.
        if (isContainedValueList(ref))
        {
            return ValueKind.MCORE_VALUE_LIST;
        }
        // A non-containment reference whose target is a metadata object (MdObject subtype), OR the
        // mcore CommandGroup interface (BasicCommand.group - see the class doc above), is a plain
        // object reference, settable by FQN. Containment refs (child collections) and other non-MdObject
        // refs (e.g. the EObject-typed suppressObject) are excluded. Derived / transient /
        // non-changeable refs are already filtered upstream by isAssignable.
        EClass targetType = ref.getEReferenceType();
        if (!ref.isContainment() && targetType != null
            && (MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(targetType)
                || McorePackage.Literals.COMMAND_GROUP.isSuperTypeOf(targetType)))
        {
            return ref.isMany() ? ValueKind.MANY_REFERENCE : ValueKind.REFERENCE;
        }
        return null;
    }

    /**
     * The target metadata-type name of a reference feature (e.g. {@code "Subsystem"}), or
     * {@code "metadata object"} when the target is the abstract base {@code MdObject} (e.g. a
     * subsystem's content may hold any object). Used to report the allowed target in the schema.
     */
    static String referenceTargetTypeName(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference))
        {
            return null;
        }
        EClass target = ((EReference)feature).getEReferenceType();
        if (target == null)
        {
            return null;
        }
        return MdClassPackage.Literals.MD_OBJECT == target ? "metadata object" : target.getName(); //$NON-NLS-1$
    }

    /** A localized-string map feature (e.g. the synonym) is a containment ref to a *MapEntry EClass. */
    private static boolean isMapEntry(EReference reference)
    {
        EClassifier type = reference.getEType();
        return type != null && type.getName() != null && type.getName().contains("MapEntry"); //$NON-NLS-1$
    }

    /** The data-type feature is a containment ref whose element type is the mcore TypeDescription. */
    private static boolean isTypeDescription(EReference reference)
    {
        EClassifier type = reference.getEType();
        return type != null && "TypeDescription".equals(type.getName()); //$NON-NLS-1$
    }

    /**
     * Whether {@code reference} is a single-valued contained value of {@code expectedType}. Picture
     * subtypes are accepted; QName is intentionally exact because the model defines one trivial value
     * class and no polymorphic value family for it.
     */
    private static boolean isContainedValue(EReference reference, EClass expectedType,
        boolean acceptSubtypes)
    {
        EClass target = reference.getEReferenceType();
        return reference.isContainment() && !reference.isMany() && target != null
            && (target == expectedType || acceptSubtypes && expectedType.isSuperTypeOf(target));
    }

    /** A many-valued containment declared exactly against the abstract mcore Value base class. */
    private static boolean isContainedValueList(EReference reference)
    {
        return reference.isContainment() && reference.isMany()
            && reference.getEReferenceType() == McorePackage.Literals.VALUE;
    }

    /**
     * The {@code value} feature declared on (or inherited into) {@link StyleItem}: a single-valued
     * containment ref to an mcore {@code Value} (a Color or a Font). Matched by name + the declaring
     * class being {@code StyleItem}, so only the style item's value is treated as STYLE_VALUE.
     */
    private static boolean isStyleItemValue(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference) || !"value".equals(feature.getName())) //$NON-NLS-1$
        {
            return false;
        }
        EClass owner = feature.getEContainingClass();
        return owner != null && MdClassPackage.Literals.STYLE_ITEM.isSuperTypeOf(owner);
    }

    /**
     * Whether {@code feature} is a contained {@code AdjustableBoolean} flag - the designer's
     * "flag plus optional per-role overrides" shape (issue #382).
     *
     * <p>The test is on the reference's TARGET TYPE, not on its name: one rule then covers every such
     * flag wherever it is declared - a form attribute's {@code view} / {@code edit}, a form item's
     * {@code userVisible}, a form command's {@code use} - instead of a name list that silently misses
     * the next one. Subtypes are admitted too, so a specialization of the type stays recognized.</p>
     */
    private static boolean isAdjustableBoolean(EStructuralFeature feature)
    {
        if (!(feature instanceof EReference))
        {
            return false;
        }
        EReference ref = (EReference)feature;
        EClass target = ref.getEReferenceType();
        return ref.isContainment() && !ref.isMany() && target != null
            && MdClassPackage.Literals.ADJUSTABLE_BOOLEAN.isSuperTypeOf(target);
    }

    /**
     * Renders an {@code AdjustableBoolean}'s current value: the nested {@code common} flag, which is
     * what the wire boolean addresses. The sibling {@code for} overrides are NOT rendered - they are
     * neither readable nor writable through this property, and showing them would suggest otherwise.
     */
    private static String renderAdjustableBoolean(Object value)
    {
        if (!(value instanceof EObject))
        {
            return null;
        }
        EObject adjustable = (EObject)value;
        EStructuralFeature common = adjustable.eClass().getEStructuralFeature(COMMON_FEATURE);
        if (common == null)
        {
            return null;
        }
        Object flag = adjustable.eGet(common);
        return flag == null ? null : String.valueOf(flag);
    }

    private static EEnum enumTypeOf(EStructuralFeature feature)
    {
        if (feature instanceof EAttribute)
        {
            EClassifier type = ((EAttribute)feature).getEAttributeType();
            if (type instanceof EEnum)
            {
                return (EEnum)type;
            }
        }
        return null;
    }

    /** The schema "allowed values" column: enum literals for enum kinds, reference target otherwise. */
    private static List<String> allowedValuesFor(EStructuralFeature feature, ValueKind kind)
    {
        if (kind == ValueKind.ENUM || kind == ValueKind.MANY_ENUM)
        {
            return enumLiterals(feature);
        }
        if (kind == ValueKind.REFERENCE || kind == ValueKind.MANY_REFERENCE)
        {
            String target = referenceTargetTypeName(feature);
            return target != null ? Collections.singletonList(target) : null;
        }
        return Collections.emptyList();
    }

    private static List<String> enumLiterals(EStructuralFeature feature)
    {
        List<String> values = new ArrayList<>();
        EEnum eEnum = enumTypeOf(feature);
        if (eEnum != null)
        {
            for (EEnumLiteral literal : eEnum.getELiterals())
            {
                values.add(literal.getName());
            }
        }
        return values;
    }

    /**
     * One rendering attempt, kept apart from its result so that "nothing there" and "could not
     * look" do not arrive at the caller as the same {@code null}.
     */
    private static final class Rendered
    {
        /** No value, and nothing went wrong: the property is genuinely empty. */
        static final Rendered ABSENT = new Rendered(null, null, false);
        /** Reading or rendering threw, so nothing is known about the value. */
        static final Rendered FAILED = new Rendered(null, null, true);

        final String text;
        /** The same value as an identity; see {@link PropertyInfo#valueIdentity}. */
        final String identity;
        final boolean failed;

        private Rendered(String text, String identity, boolean failed)
        {
            this.text = text;
            this.identity = identity;
            this.failed = failed;
        }

        /** A rendering that identifies itself: what it shows is exactly what it is. */
        static Rendered of(String text)
        {
            return of(text, text);
        }

        /**
         * A rendering whose displayed text is DELIBERATELY shorter than the value it stands for.
         *
         * @param text what a reader sees
         * @param identity what the value is, for a caller that compares rather than prints
         * @return the rendering, or {@link #ABSENT} when there is no value to show
         */
        static Rendered of(String text, String identity)
        {
            return text == null ? ABSENT : new Rendered(text, identity, false);
        }

        /**
         * A value that IS there, whatever it renders as.
         *
         * <p>{@link #of(String, String)} folds a {@code null} text into {@link #ABSENT}, which is
         * right for the kinds whose renderer returns {@code null} to mean "there is nothing here",
         * and wrong for a reference: whether a reference is empty was already decided by
         * {@code eGet} returning {@code null}, before any rendering. A target that is present but
         * has no printable name is not an unset reference, and reporting it as one is the same
         * mistake this class documents for a failed read - two sides that both say "nothing"
         * compare equal.</p>
         *
         * @param text what a reader sees; {@code null} prints as an empty cell
         * @param identity what the value IS; a blank one cannot be told from an unset reference
         * @return the rendering, or {@link #FAILED} when nothing at all identifies the value
         */
        static Rendered present(String text, String identity)
        {
            // Not "absent": a value we cannot identify is a value we know nothing about, and the
            // empty identity an ABSENT would carry is exactly the one an unset reference carries.
            return identity == null || identity.isEmpty() ? FAILED
                : new Rendered(text, identity, false);
        }
    }

    private static Rendered renderCurrent(EObject obj, EStructuralFeature feature, ValueKind kind)
    {
        // The whole render is guarded: reading or rendering one feature (e.g. a dangling type proxy
        // whose name resolver is unavailable) must NOT abort introspecting the rest of the object.
        // The guard REPORTS, though - answering ABSENT here would make a failed read look like an
        // unset property, which is the one thing a side-by-side comparison must never do.
        try
        {
            Object value = obj.eGet(feature);
            if (value == null)
            {
                return Rendered.ABSENT;
            }
            switch (kind)
            {
                case LOCALIZED_STRING:
                    return Rendered.of(renderLocalizedString(value));
                case TYPE_DESCRIPTION:
                    return Rendered.of(value instanceof TypeDescription ? renderType((TypeDescription)value) : null);
                case ENUM:
                    // Render via the literal NAME so "Current" shares the vocabulary of allowedValues.
                    return Rendered.of(value instanceof org.eclipse.emf.common.util.Enumerator enumerator
                        ? enumerator.getName() : String.valueOf(value));
                case MANY_ENUM:
                    return Rendered.of(renderEnumList(value));
                case REFERENCE:
                    return referenceTarget(value);
                case MANY_REFERENCE:
                    return renderReferenceList(value);
                case MCORE_VALUE_LIST:
                    return Rendered.of(renderMcoreValueList(value));
                case STYLE_VALUE:
                    return Rendered.of(renderStyleValue(value));
                case PICTURE:
                    return Rendered.of(renderPicture(value));
                case QNAME:
                    return Rendered.of(value instanceof QName ? renderQName((QName)value) : null);
                case ADJUSTABLE_BOOLEAN:
                    return Rendered.of(renderAdjustableBoolean(value));
                default:
                    return Rendered.of(String.valueOf(value));
            }
        }
        catch (Exception e)
        {
            return Rendered.FAILED;
        }
    }

    @SuppressWarnings("unchecked")
    private static String renderLocalizedString(Object value)
    {
        if (!(value instanceof EMap<?, ?>))
        {
            return null;
        }
        EMap<String, String> map = (EMap<String, String>)value;
        if (map.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : map.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * The many-valued sibling of {@link #referenceTarget}, and deliberately narrower: it names
     * {@link MdObject} elements only.
     *
     * <p>That is not the omission it looks like next to {@link #referenceTarget}. The MANY kind is
     * reached only for a many-valued reference that {@link #classifyReference} admitted, and a
     * census of the platform's own models - {@code model/MdClass.xcore} and {@code model/Form.xcore}
     * inside the EDT bundles - finds every one of those declared against an {@code MdObject}
     * subtype. The one non-{@code MdObject} family the classifier admits, the mcore
     * {@code CommandGroup}, is referenced exactly three times and all three are SINGLE-valued
     * ({@code StandardCommand.group}, {@code BasicCommand.group},
     * {@code FormCommandInterfaceItem.group}). There is no many-valued shape to render, so widening
     * this method would add a branch no model can enter and no test can redden.</p>
     *
     * @param value the feature value, expected to be an {@code EList}
     * @return the rendering, or {@link Rendered#ABSENT} when the list holds nothing nameable
     */
    private static Rendered renderReferenceList(Object value)
    {
        if (!(value instanceof EList<?>))
        {
            return Rendered.ABSENT;
        }
        EList<?> list = (EList<?>)value;
        if (list.isEmpty())
        {
            return Rendered.ABSENT;
        }
        // Two strings out of one walk: the reader gets the bare names, a comparison gets the same
        // targets qualified by type. Built together so they can never disagree about which
        // elements the property holds, or in which order.
        StringBuilder shown = new StringBuilder();
        StringBuilder identity = new StringBuilder();
        for (Object element : list)
        {
            if (element instanceof MdObject)
            {
                if (shown.length() > 0)
                {
                    shown.append(", "); //$NON-NLS-1$
                    identity.append(", "); //$NON-NLS-1$
                }
                shown.append(((MdObject)element).getName());
                identity.append(qualifiedName((EObject)element, ((MdObject)element).getName()));
            }
        }
        return shown.length() > 0 ? Rendered.of(shown.toString(), identity.toString())
            : Rendered.ABSENT;
    }

    /**
     * One PRESENT reference target, as the pair this class deals in: the cell a reader sees and the
     * identity a comparison uses.
     *
     * <p>Everything that reaches here is present - {@link #renderCurrent} answers
     * {@link Rendered#ABSENT} for a feature whose {@code eGet} is {@code null}, before the switch -
     * so this method never answers absent. It used to, for every target that is not an
     * {@link MdObject}, and {@link #classifyReference} deliberately admits a family that is not:
     * a reference declared against the mcore {@code CommandGroup} interface (issue #262). Read from
     * the platform's own model, that interface has three concrete types - the metadata
     * {@code CommandGroup} (an {@code MdObject}), the platform's {@code StandardCommandGroup}, and
     * the command-interface derived-data {@code UnresolvedGroup} - and only the first was rendered.
     * The other two came back as {@code (null, null, not-failed)}, which is the SAME answer as
     * "this command has no group", so a command sitting in one standard group on one side and
     * another on the other side compared EQUAL and vanished from the differing properties.</p>
     *
     * <p>What a target is CALLED is a separate question with more than one answer -
     * {@link #referenceTargetName}. What a target IS always has one: its type, which is why the
     * identity is built from the type even when there is no name to hang on it.</p>
     *
     * @param value the reference value, never {@code null}
     * @return the rendering; {@link Rendered#FAILED} only for a value with neither a name nor an
     *     EClass name, which no object of a generated EMF model is
     */
    private static Rendered referenceTarget(Object value)
    {
        String name = referenceTargetName(value);
        return value instanceof EObject
            ? Rendered.present(name, qualifiedName((EObject)value, name))
            // Unreachable through an EReference, whose values are EObjects by definition. Kept so
            // that a value this method cannot type is still not published as an absent one.
            : Rendered.present(name, name);
    }

    /**
     * What the PLATFORM calls a reference target, asked of the interface that actually declares the
     * name rather than of one accessor assumed to be universal.
     *
     * <p>{@code MdObject.getName()} is NOT universal. {@link MdObject} declares its own
     * {@code String[1] name} and does not extend mcore's {@link NamedElement}: the two naming
     * contracts are unrelated types that spell the accessor alike. (Read from {@code model/
     * MdClass.xcore} and {@code model/Mcore.xcore} inside the EDT bundles - the Javadoc shows the
     * accessors but not which interface introduces them.) The admitted targets, and their answer:</p>
     * <ul>
     * <li>an {@link MdObject} - {@code MdObject.getName()}, e.g. {@code Sales};</li>
     * <li>an mcore {@link NamedElement} - its own {@code getName()}. The
     * {@code StandardCommandGroup} the {@code CommandGroup} interface admits is a
     * {@code DuallyNamedElement}, which extends {@code NamedElement}, so the general interface
     * answers it; the platform's catalogue of those names is
     * {@code IEObjectStandardCommandGroupNames} ({@code FormCommandBarImportant},
     * {@code NavigationPanelSeeAlso}, ...), and that is the token a {@code .mdo} stores. A group
     * that carries no name still carries the {@code category} its own class declares, and the
     * category is what the rest of this server already prints for such a group (the formatter layer
     * elects the enum as a wrapper's primary value), so it stands in - a half-built group is still
     * not the same value as a differently-categorised one;</li>
     * <li>anything else, including the derived-data {@code UnresolvedGroup} that lives in another
     * bundle - no name. It is not thereby absent: {@link #referenceTarget} still identifies it by
     * its type.</li>
     * </ul>
     *
     * <p>This says nothing about WRITING such a value: a standard group is not addressable by FQN
     * and {@code modify_metadata} still refuses it. Reading and reporting one is what was missing.</p>
     *
     * @param value the reference target, never {@code null}
     * @return the target's name, or {@code null} when the platform gives it none
     */
    private static String referenceTargetName(Object value)
    {
        if (value instanceof MdObject)
        {
            return ((MdObject)value).getName();
        }
        if (value instanceof NamedElement)
        {
            String name = ((NamedElement)value).getName();
            if (name != null && !name.isEmpty())
            {
                return name;
            }
            if (value instanceof StandardCommandGroup)
            {
                // Rendered through the literal NAME, the same vocabulary the ENUM kind publishes.
                CommandGroupCategory category = ((StandardCommandGroup)value).getCategory();
                return category == null ? null : category.getName();
            }
        }
        return null;
    }

    /**
     * A reference target as {@code Type.Name} - the form that still says WHICH object it is once
     * the bare {@code Name} no longer does.
     *
     * <p>A broad reference (a subsystem's {@code content} is declared against the abstract
     * {@code MdObject}, so it holds objects of any type) can name {@code Catalog.Foo} on one side
     * and {@code Document.Foo} on the other. Both render {@code Foo}, and a consumer comparing the
     * rendered text called them equal - see {@link PropertyInfo#valueIdentity}.</p>
     *
     * <p>The type is the object's CONCRETE EClass, not the reference's declared target type: it is
     * the declared type being broad that creates the ambiguity in the first place, so only the
     * actual object can resolve it.</p>
     *
     * <p>A target with NO name is identified by that type alone. The alternative - no identity -
     * is the empty string an unset reference carries, and "points at an unnamed object" and "points
     * at nothing" are not the same fact. Two unnamed targets of the same type do collapse into one
     * identity; that is the same semantic-identity limit {@link PropertyInfo#valueIdentity} states
     * for two same-named targets under different owners, and it is the safe direction: a comparison
     * of two different models has no object identity it could use instead.</p>
     *
     * @param object the reference target, never {@code null}
     * @param name the target's name, or {@code null} / empty when it has none
     * @return {@code Type.Name}; the bare type when there is no name; the bare name when the object
     *     has no EClass name to qualify by; {@code null} when it has neither
     */
    private static String qualifiedName(EObject object, String name)
    {
        EClass type = object.eClass();
        String typeName = type == null ? null : type.getName();
        if (name == null || name.isEmpty())
        {
            return typeName;
        }
        return typeName == null ? name : typeName + '.' + name;
    }

    /** Renders a many-valued enum to the same JSON array of literal names accepted on the wire. */
    private static String renderEnumList(Object value)
    {
        if (!(value instanceof EList<?>))
        {
            return null;
        }
        JsonArray rendered = new JsonArray();
        for (Object element : (EList<?>)value)
        {
            if (!(element instanceof org.eclipse.emf.common.util.Enumerator))
            {
                return null;
            }
            rendered.add(((org.eclipse.emf.common.util.Enumerator)element).getName());
        }
        return rendered.toString();
    }

    /** Renders a contained mcore Value list to the same JSON array of strings accepted on the wire. */
    private static String renderMcoreValueList(Object value)
    {
        if (!(value instanceof EList<?>))
        {
            return null;
        }
        JsonArray rendered = new JsonArray();
        for (Object element : (EList<?>)value)
        {
            if (element instanceof ReferenceValue)
            {
                EObject referenceValue = (EObject)element;
                EStructuralFeature valueFeature =
                    referenceValue.eClass().getEStructuralFeature("value"); //$NON-NLS-1$
                Object target = valueFeature == null ? null
                    : resolvingGet(referenceValue, valueFeature);
                if (!(target instanceof XDTOPackage))
                {
                    return null;
                }
                EObject targetObject = (EObject)target;
                EStructuralFeature nameFeature =
                    targetObject.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
                Object name = nameFeature == null ? null : resolvingGet(targetObject, nameFeature);
                if (name == null || name.toString().isEmpty())
                {
                    return null;
                }
                rendered.add(McoreValueListBuilder.XDTO_PREFIX + name);
            }
            else if (element instanceof StringValue)
            {
                EObject stringValue = (EObject)element;
                EStructuralFeature valueFeature =
                    stringValue.eClass().getEStructuralFeature("value"); //$NON-NLS-1$
                Object namespace = valueFeature == null ? null
                    : resolvingGet(stringValue, valueFeature);
                if (namespace == null || namespace.toString().isEmpty())
                {
                    return null;
                }
                rendered.add(namespace.toString());
            }
            else
            {
                return null;
            }
        }
        return rendered.toString();
    }

    /** Reads a feature normally while keeping a failed proxy resolution local to its rendered value. */
    private static Object resolvingGet(EObject object, EStructuralFeature feature)
    {
        try
        {
            return object.eGet(feature, true);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Renders a StyleItem {@code value} (an mcore {@code Value}): a Color as {@code Color: RGB(r, g, b)} /
     * {@code Color: Auto}, a Font as {@code Font: ...}. Delegates to {@link StyleValueBuilder} so the
     * AutoColor-first ordering is shared with the get_metadata_details formatter.
     */
    private static String renderStyleValue(Object value)
    {
        if (value instanceof ColorValue)
        {
            String color = StyleValueBuilder.renderColor(((ColorValue)value).getValue());
            return color != null ? "Color: " + color : null; //$NON-NLS-1$
        }
        if (value instanceof FontValue)
        {
            String font = StyleValueBuilder.renderFont(((FontValue)value).getValue());
            return font != null ? "Font: " + font : null; //$NON-NLS-1$
        }
        return null;
    }

    /** Renders a stored PictureRef back to the same symbolic form accepted on the wire. */
    private static String renderPicture(Object value)
    {
        if (!(value instanceof EObject))
        {
            return null;
        }
        EObject pictureRef = (EObject)value;
        EStructuralFeature pictureFeature = pictureRef.eClass().getEStructuralFeature("picture"); //$NON-NLS-1$
        if (pictureFeature == null)
        {
            return null;
        }
        // Prefix and name require different views of the same reference. Preserve the raw proxy URI
        // before resolving it; only the resolved object may be asked for its actual name below.
        Object raw = pictureRef.eGet(pictureFeature, false);
        if (!(raw instanceof EObject))
        {
            return null;
        }
        EObject rawPicture = (EObject)raw;
        URI proxyUri = null;
        URI definingUri = null;
        if (rawPicture.eIsProxy() && rawPicture instanceof InternalEObject)
        {
            proxyUri = ((InternalEObject)rawPicture).eProxyURI();
            definingUri = proxyUri;
        }
        else if (rawPicture.eResource() != null)
        {
            definingUri = rawPicture.eResource().getURI();
        }

        // A reloaded form may expose an unresolved CommonPicture proxy whose attributes are unset,
        // so resolve before reading either picture name. The scoped reload e2e covers this path;
        // synthetic ResourceSet unit fixtures did not reproduce its proxy resolution faithfully.
        Object resolvedValue = resolvingGet(pictureRef, pictureFeature);
        EObject resolvedPicture = resolvedValue instanceof EObject
            ? (EObject)resolvedValue : rawPicture;
        if (resolvedPicture.eClass() == null
            || !McorePackage.Literals.PICTURE.isSuperTypeOf(resolvedPicture.eClass()))
        {
            return null;
        }
        if (resolvedPicture instanceof CommonPicture)
        {
            String name = resolvedPicture.eIsProxy() ? null : pictureName(resolvedPicture);
            if (name != null)
            {
                return PictureValueBuilder.COMMON_PREFIX + name;
            }
            return unresolvedCommonPictureValue(proxyUri);
        }

        // StdPicturesLoader registers extended pictures under StdExtPicture.*, while EDT's own
        // SymbolicNameService and FormQualifiedNameProvider currently return StdPicture.* for every
        // platform picture. Use the defining resource URI so the emitted value remains resolvable.
        // Do not use EcoreUtil.getURI here: it throws for a detached, non-proxy EObject and the
        // caller's broad render guard would silently turn that into an empty Current value.
        String prefix = definingUri != null
            && definingUri.toString().contains("/Pictures/StdExt/") //$NON-NLS-1$
            ? PictureValueBuilder.EXTENDED_PREFIX : PictureValueBuilder.STANDARD_PREFIX;

        String name = resolvedPicture.eIsProxy() ? null : pictureName(resolvedPicture);
        if (name == null)
        {
            // StdPicturesLoader creates proxy URIs with uri.appendFragment("/" + name), so this
            // fragment is the authoritative name when resolution is unavailable.
            name = platformPictureNameFromProxyUri(proxyUri);
        }
        if (name == null)
        {
            return null;
        }
        return prefix + name;
    }

    private static String pictureName(EObject picture)
    {
        EStructuralFeature nameFeature = picture.eClass() == null ? null
            : picture.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        Object name = nameFeature == null ? null : resolvingGet(picture, nameFeature);
        return name == null || name.toString().isEmpty() ? null : name.toString();
    }

    /**
     * Renders a CommonPicture proxy that could not be resolved without silently blanking Current.
     * A BM proxy carries the target top-object FQN, so prefer that feedable value; otherwise expose
     * the unresolved URI explicitly instead of pretending the property is unset.
     */
    private static String unresolvedCommonPictureValue(URI proxyUri)
    {
        if (proxyUri != null && BmUriUtil.isBmUri(proxyUri))
        {
            String fqn = BmUriUtil.extractTopObjectFqn(proxyUri);
            if (fqn != null && fqn.startsWith(PictureValueBuilder.COMMON_PREFIX)
                && fqn.length() > PictureValueBuilder.COMMON_PREFIX.length())
            {
                return fqn;
            }
        }
        return proxyUri == null ? "Unresolved CommonPicture reference" //$NON-NLS-1$
            : "Unresolved CommonPicture reference: " + proxyUri; //$NON-NLS-1$
    }

    private static String platformPictureNameFromProxyUri(URI proxyUri)
    {
        if (proxyUri == null)
        {
            return null;
        }
        String fragment = proxyUri.fragment();
        if (fragment == null)
        {
            return null;
        }
        String name = fragment.startsWith("/") ? fragment.substring(1) : fragment; //$NON-NLS-1$
        return name.isEmpty() ? null : name;
    }

    /** Renders a QName in the standard compact {@code {nsUri}name} form. */
    private static String renderQName(QName qname)
    {
        String name = qname.getName();
        String nsUri = qname.getNsUri();
        return name == null || name.isEmpty() || nsUri == null || nsUri.isEmpty() ? null
            : "{" + nsUri + "}" + name; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String renderType(TypeDescription typeDesc)
    {
        EList<TypeItem> types = typeDesc.getTypes();
        if (types == null || types.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TypeItem item : types)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            String name = McoreUtil.getTypeName(item);
            sb.append(name != null ? name : String.valueOf(item));
        }
        return sb.toString();
    }
}
