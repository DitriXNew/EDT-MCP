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
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;

/**
 * Introspects the ASSIGNABLE properties of a metadata {@link EObject}: which structural features a
 * client may set, what kind of value each takes, the allowed values (for an enum), and the current
 * value. This is the single source of truth shared by the {@code get_metadata_details} "assignable"
 * view (human-readable) and {@code modify_metadata}'s validation (availability + value-validity).
 *
 * <p>A feature is considered assignable when it is changeable and not derived / transient / volatile,
 * and is not a containment reference (those are child collections - attributes / tabular sections /
 * forms / commands - created via {@code create_metadata}, not set as a scalar value).</p>
 */
public final class MetadataPropertyIntrospector
{
    /** The kind of value an assignable property takes - drives validation and rendering. */
    public enum ValueKind
    {
        /** A free-text string (e.g. name, comment). */
        STRING,
        /** A boolean flag. */
        BOOLEAN,
        /** An integer (e.g. a length / precision). */
        INTEGER,
        /** An enum: only one of {@link PropertyInfo#allowedValues} is valid. */
        ENUM,
        /** The localized synonym map, keyed by language code. */
        LOCALIZED_STRING,
        /** A 1C data type (mcore {@code TypeDescription}); set via the structured type form. */
        TYPE_DESCRIPTION
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
        /** For {@link ValueKind#ENUM}: the allowed literal names; empty otherwise. */
        public final List<String> allowedValues;
        /** The owning {@link EStructuralFeature} (for the applier; not serialized). */
        public final EStructuralFeature feature;

        PropertyInfo(String name, ValueKind valueKind, String currentValue, List<String> allowedValues,
            EStructuralFeature feature)
        {
            this.name = name;
            this.valueKind = valueKind;
            this.currentValue = currentValue;
            this.allowedValues = allowedValues == null ? Collections.emptyList()
                : Collections.unmodifiableList(allowedValues);
            this.feature = feature;
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
        for (EStructuralFeature feature : obj.eClass().getEAllStructuralFeatures())
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
            result.add(new PropertyInfo(feature.getName(), kind, renderCurrent(obj, feature, kind),
                kind == ValueKind.ENUM ? enumLiterals(feature) : null, feature));
        }
        return result;
    }

    /**
     * Finds the assignable property named {@code name} (case-insensitive) on {@code obj}.
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
     * Returns the assignable property names of {@code obj}, for an actionable "available properties"
     * error hint.
     *
     * @param obj the object
     * @return the assignable feature names (never {@code null})
     */
    public static List<String> assignableNames(EObject obj)
    {
        List<String> names = new ArrayList<>();
        for (PropertyInfo info : introspect(obj))
        {
            names.add(info.name);
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

    private static boolean isAssignable(EStructuralFeature feature)
    {
        // Only the EMF mutability gates here; classify() returns null to exclude the rest
        // (child-collection containment refs and object references are not simple values).
        return feature != null && !feature.isDerived() && !feature.isTransient()
            && !feature.isVolatile() && feature.isChangeable();
    }

    private static ValueKind classify(EStructuralFeature feature)
    {
        if (feature instanceof EAttribute)
        {
            EClassifier type = ((EAttribute)feature).getEAttributeType();
            if (type instanceof EEnum)
            {
                return ValueKind.ENUM;
            }
            String typeName = type != null ? type.getInstanceClassName() : null;
            if ("boolean".equals(typeName) || "java.lang.Boolean".equals(typeName)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return ValueKind.BOOLEAN;
            }
            if ("int".equals(typeName) || "java.lang.Integer".equals(typeName) //$NON-NLS-1$ //$NON-NLS-2$
                || "long".equals(typeName) || "java.lang.Long".equals(typeName)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return ValueKind.INTEGER;
            }
            return ValueKind.STRING;
        }
        if (feature instanceof EReference)
        {
            EReference ref = (EReference)feature;
            // The synonym (and other localized strings) is a containment map-entry reference reached
            // via getSynonym(); the data type is a contained TypeDescription. Both ARE assignable
            // values. Every other reference - child collections (attributes/forms/...) and plain
            // object references - is NOT a simple assignable value, so it is excluded (null).
            if ("synonym".equals(ref.getName()) || isMapEntry(ref)) //$NON-NLS-1$
            {
                return ValueKind.LOCALIZED_STRING;
            }
            if (isTypeDescription(ref))
            {
                return ValueKind.TYPE_DESCRIPTION;
            }
            return null;
        }
        return null;
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

    private static String renderCurrent(EObject obj, EStructuralFeature feature, ValueKind kind)
    {
        // The whole render is guarded: reading or rendering one feature (e.g. a dangling type proxy
        // whose name resolver is unavailable) must NOT abort introspecting the rest of the object.
        try
        {
            Object value = obj.eGet(feature);
            if (value == null)
            {
                return null;
            }
            switch (kind)
            {
                case LOCALIZED_STRING:
                    return renderLocalizedString(value);
                case TYPE_DESCRIPTION:
                    return value instanceof TypeDescription ? renderType((TypeDescription)value) : null;
                case ENUM:
                    // Render via the literal NAME so "Current" shares the vocabulary of allowedValues.
                    return value instanceof org.eclipse.emf.common.util.Enumerator enumerator
                        ? enumerator.getName() : String.valueOf(value);
                default:
                    return String.valueOf(value);
            }
        }
        catch (Exception e)
        {
            return null;
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
