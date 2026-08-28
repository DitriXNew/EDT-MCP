/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormPackage;

/**
 * Compares the effective model content authored by the DCS writer.
 *
 * <p>This deliberately does not compare {@link EObject#eIsSet(EStructuralFeature)}. BM records an
 * explicit assignment of a default differently from a detached EMF object, although both expose
 * the same value and serialize/read the same way. Persisted attributes and the tool's ownership
 * tree are instead compared field by field. References outside that explicit ownership boundary
 * are not content the writer can claim to have applied and do not participate.</p>
 */
public final class DcsModelComparison
{
    private static final int MAX_VALUE_CHARS = 160;

    private DcsModelComparison()
    {
        // Utility class
    }

    /**
     * Returns the first effective-content difference, including its model path, or {@code null}.
     */
    public static String firstDifference(EObject expected, EObject actual)
    {
        Map<EObject, Set<EObject>> visited = new IdentityHashMap<>();
        return firstDifference(expected, actual, "root", visited); //$NON-NLS-1$
    }

    /**
     * Takes a detached snapshot of the complete dynamic-list content owned by this tool.
     * {@code listSettings} is an external, non-containment property, so ordinary
     * {@link EcoreUtil#copy(EObject)} would retain a pointer to the transaction-owned object.
     */
    public static DynamicListExtInfo snapshot(DynamicListExtInfo source)
    {
        if (source == null)
        {
            return null;
        }
        DynamicListExtInfo result = EcoreUtil.copy(source);
        DataCompositionSettings settings = source.getListSettings();
        result.setListSettings(settings == null ? null : EcoreUtil.copy(settings));
        return result;
    }

    private static String firstDifference(EObject expected, EObject actual, String path,
        Map<EObject, Set<EObject>> visited)
    {
        if (expected == actual)
        {
            return null;
        }
        if (expected == null || actual == null)
        {
            return difference(path, expected, actual);
        }
        if (!Objects.equals(expected.eClass().getName(), actual.eClass().getName()))
        {
            return difference(path + "/@type", expected.eClass().getName(), //$NON-NLS-1$
                actual.eClass().getName());
        }
        if (!markVisited(expected, actual, visited))
        {
            return null;
        }

        for (EStructuralFeature expectedFeature : expected.eClass().getEAllStructuralFeatures())
        {
            boolean authoredExternal = expectedFeature
                == FormPackage.Literals.DYNAMIC_LIST_EXT_INFO__LIST_SETTINGS;
            if (expectedFeature.isDerived() || !expectedFeature.isChangeable()
                || expectedFeature.isTransient() && !authoredExternal
                || expectedFeature instanceof EReference
                    && !((EReference)expectedFeature).isContainment() && !authoredExternal)
            {
                continue;
            }
            EStructuralFeature actualFeature = actual.eClass()
                .getEStructuralFeature(expectedFeature.getName());
            String featurePath = path + '/' + expectedFeature.getName();
            if (actualFeature == null)
            {
                return difference(featurePath, expected.eGet(expectedFeature), "<missing feature>"); //$NON-NLS-1$
            }
            Object expectedValue = expected.eGet(expectedFeature);
            Object actualValue = actual.eGet(actualFeature);
            String difference;
            if (expectedFeature instanceof EAttribute)
            {
                difference = attributeDifference(expectedValue, actualValue, featurePath);
            }
            else if (expectedFeature instanceof EReference)
            {
                difference = referenceDifference((EReference)expectedFeature, expectedValue,
                    actualValue, featurePath, visited);
            }
            else
            {
                difference = Objects.deepEquals(expectedValue, actualValue) ? null
                    : difference(featurePath, expectedValue, actualValue);
            }
            if (difference != null)
            {
                return difference;
            }
        }
        return null;
    }

    private static String attributeDifference(Object expected, Object actual, String path)
    {
        if (expected instanceof List<?> || actual instanceof List<?>)
        {
            return listDifference(expected, actual, path, false, null, null);
        }
        return Objects.deepEquals(expected, actual) ? null : difference(path, expected, actual);
    }

    private static String referenceDifference(EReference reference, Object expected, Object actual,
        String path, Map<EObject, Set<EObject>> visited)
    {
        boolean owned = reference.isContainment()
            || reference == FormPackage.Literals.DYNAMIC_LIST_EXT_INFO__LIST_SETTINGS;
        if (reference.isMany())
        {
            return listDifference(expected, actual, path, owned, reference, visited);
        }
        if (owned)
        {
            if (expected instanceof EObject || actual instanceof EObject)
            {
                return firstDifference(asEObject(expected), asEObject(actual), path, visited);
            }
            return Objects.equals(expected, actual) ? null : difference(path, expected, actual);
        }
        return Objects.equals(expected, actual) ? null : difference(path, expected, actual);
    }

    private static String listDifference(Object expected, Object actual, String path, boolean owned,
        EReference reference, Map<EObject, Set<EObject>> visited)
    {
        if (!(expected instanceof List<?>) || !(actual instanceof List<?>))
        {
            return difference(path, expected, actual);
        }
        List<?> expectedValues = (List<?>)expected;
        List<?> actualValues = (List<?>)actual;
        if (expectedValues.size() != actualValues.size())
        {
            return difference(path + "/@size", Integer.valueOf(expectedValues.size()), //$NON-NLS-1$
                Integer.valueOf(actualValues.size()));
        }
        for (int i = 0; i < expectedValues.size(); i++)
        {
            Object expectedValue = expectedValues.get(i);
            Object actualValue = actualValues.get(i);
            String itemPath = path + '/' + i;
            String itemDifference;
            if (reference == null)
            {
                itemDifference = Objects.deepEquals(expectedValue, actualValue) ? null
                    : difference(itemPath, expectedValue, actualValue);
            }
            else if (owned)
            {
                itemDifference = firstDifference(asEObject(expectedValue), asEObject(actualValue),
                    itemPath, visited);
            }
            else
            {
                itemDifference = Objects.equals(expectedValue, actualValue) ? null
                    : difference(itemPath, expectedValue, actualValue);
            }
            if (itemDifference != null)
            {
                return itemDifference;
            }
        }
        return null;
    }

    private static boolean markVisited(EObject expected, EObject actual,
        Map<EObject, Set<EObject>> visited)
    {
        Set<EObject> actuals = visited.computeIfAbsent(expected,
            unused -> Collections.newSetFromMap(new IdentityHashMap<EObject, Boolean>()));
        return actuals.add(actual);
    }

    private static EObject asEObject(Object value)
    {
        return value instanceof EObject ? (EObject)value : null;
    }

    private static String difference(String path, Object expected, Object actual)
    {
        return path + ": expected " + canonicalValue(expected) //$NON-NLS-1$
            + ", actual " + canonicalValue(actual); //$NON-NLS-1$
    }

    private static String canonicalValue(Object value)
    {
        String result;
        if (value == null)
        {
            result = "<null>"; //$NON-NLS-1$
        }
        else if (value instanceof EObject)
        {
            result = ((EObject)value).eClass().getName();
        }
        else
        {
            result = value.toString();
        }
        if (result.length() <= MAX_VALUE_CHARS)
        {
            return result;
        }
        return result.substring(0, MAX_VALUE_CHARS - 1) + '\u2026';
    }
}
