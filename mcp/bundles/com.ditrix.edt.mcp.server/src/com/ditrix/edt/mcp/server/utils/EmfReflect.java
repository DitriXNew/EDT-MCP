/**
 * Copyright (C) 2024, DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

/**
 * Reflective EMF primitives addressed by feature / classifier NAME, with no compile-time dependency
 * on the target metamodel: a model object is reached through its own {@link EPackage}, or a fresh one
 * is created from the global EMF package registry by nsURI. Shared by the form and DCS model writers
 * so the "build a model object by name" logic lives in one place (no duplicated reflective helpers).
 * <p>
 * The setters are best-effort: a missing / wrong-kind feature is a no-op, so a writer can set the
 * superset of defaults a type might carry without guarding each one. {@link #addToList} is the
 * exception - adding to a non-list feature is a programming error and throws.
 */
public final class EmfReflect
{
    private EmfReflect()
    {
    }

    /**
     * Creates an instance of {@code classifierName} from the EPackage registered under {@code nsUri}
     * in the global EMF registry (the compile-time-free route into a model not on the classpath).
     *
     * @throws IllegalStateException when the package is not registered or has no such concrete EClass
     *     (the caller turns this into a tool error naming the absent model bundle)
     */
    public static EObject createByNsUri(String nsUri, String classifierName)
    {
        EPackage pkg = EPackage.Registry.INSTANCE.getEPackage(nsUri);
        EClassifier classifier = pkg != null ? pkg.getEClassifier(classifierName) : null;
        if (!(classifier instanceof EClass))
        {
            throw new IllegalStateException("EMF package '" + nsUri + "' has no class '" //$NON-NLS-1$ //$NON-NLS-2$
                + classifierName + "' (is the model bundle present in this platform?)"); //$NON-NLS-1$
        }
        return pkg.getEFactoryInstance().create((EClass)classifier);
    }

    /**
     * Creates an instance of {@code classifierName} resolved on the SAME EPackage as {@code sibling};
     * {@code null} when the classifier is absent on that package.
     */
    public static EObject createOnSamePackage(EObject sibling, String classifierName)
    {
        EClassifier classifier = sibling.eClass().getEPackage().getEClassifier(classifierName);
        if (!(classifier instanceof EClass) || classifier.getEPackage() == null)
        {
            return null;
        }
        return classifier.getEPackage().getEFactoryInstance().create((EClass)classifier);
    }

    /** Sets a feature by name to a String value; a no-op when the feature is absent. */
    public static void setString(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    /** The value of a String-valued feature by name, or {@code null} (absent / not a String). */
    public static String getString(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof String ? (String)value : null;
    }

    /**
     * The many-valued EReference {@code featureName} as a list of EObject, or an EMPTY list when the
     * feature is absent / not a many-valued reference (read-only convenience for iteration).
     */
    @SuppressWarnings("unchecked")
    public static EList<EObject> list(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            return ECollections.emptyEList();
        }
        return (EList<EObject>)owner.eGet(feature);
    }

    /** Sets a feature by name to a boolean value; a no-op when the feature is absent. */
    public static void setBoolean(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    /**
     * Sets an EEnum-typed feature by name to the literal named (or whose literal string is)
     * {@code literalName}, matched case-insensitively; a no-op when the feature is absent / not an
     * enum / has no such literal (best-effort, like the other setters).
     */
    public static void setEnum(EObject object, String featureName, String literalName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || !(feature.getEType() instanceof EEnum) || literalName == null)
        {
            return;
        }
        EEnum eenum = (EEnum)feature.getEType();
        EEnumLiteral literal = eenum.getEEnumLiteral(literalName);
        if (literal == null)
        {
            for (EEnumLiteral candidate : eenum.getELiterals())
            {
                if (candidate.getName().equalsIgnoreCase(literalName)
                    || candidate.getLiteral().equalsIgnoreCase(literalName))
                {
                    literal = candidate;
                    break;
                }
            }
        }
        if (literal != null)
        {
            object.eSet(feature, literal.getInstance());
        }
    }

    /** Sets a feature by name to an int value; a no-op when the feature is absent. */
    public static void setInt(EObject object, String featureName, int value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Integer.valueOf(value));
        }
    }

    /**
     * Sets a feature by name to an arbitrary already-typed value (e.g. a {@link java.math.BigDecimal}
     * or {@link Boolean} for a numeric / boolean {@code EAttribute}); a no-op when the feature is absent
     * or {@code value} is {@code null} (best-effort, like the other setters).
     */
    public static void setObject(EObject object, String featureName, Object value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null && value != null)
        {
            object.eSet(feature, value);
        }
    }

    /**
     * The value of a scalar (attribute) feature by name rendered as a String, or {@code null} when the
     * feature is absent or unset. An {@link Enumerator} (an EMF enum literal) renders as its
     * {@link Enumerator#getName() name}; any other value via {@link String#valueOf}. Read-only
     * convenience for enum/typed-literal features that {@link #getString} (String-only) does not cover
     * (e.g. {@code orderType}, {@code comparisonType}, an mcore typed value).
     */
    public static String scalarToString(EObject object, String featureName)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            return null;
        }
        Object value = object.eGet(feature);
        if (value == null)
        {
            return null;
        }
        if (value instanceof org.eclipse.emf.common.util.Enumerator)
        {
            return ((org.eclipse.emf.common.util.Enumerator)value).getName();
        }
        return String.valueOf(value);
    }

    /** The value of a single-valued EReference by name, or {@code null} (absent / many / not a ref). */
    public static EObject singleReference(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || feature.isMany())
        {
            return null;
        }
        Object value = owner.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    /**
     * Sets a single-valued EReference by name; a no-op when the feature is absent / not a
     * single-valued reference or {@code value} is {@code null} (best-effort, like the other setters).
     */
    public static void setSingleReference(EObject owner, String featureName, EObject value)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature instanceof EReference && !feature.isMany() && value != null)
        {
            owner.eSet(feature, value);
        }
    }

    /**
     * Adds {@code element} to the many-valued EReference {@code featureName}.
     *
     * @throws IllegalArgumentException when the feature is not a many-valued reference
     */
    @SuppressWarnings("unchecked")
    public static void addToList(EObject container, String featureName, EObject element)
    {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EReference) || !feature.isMany())
        {
            throw new IllegalArgumentException("feature '" + featureName + "' is not a list"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ((EList<EObject>)container.eGet(feature)).add(element);
    }
}
