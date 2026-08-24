/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;

/** Caller-facing refusals for deliberately excluded DCS authoring features. */
final class DcsUnsupportedAuthoring
{
    static final String CHART_CLASS = "DataCompositionChart"; //$NON-NLS-1$
    static final String NESTED_DATA_SET_CLASS = "DataCompositionSchemaNestedDataSet"; //$NON-NLS-1$

    private DcsUnsupportedAuthoring()
    {
        // Utility class
    }

    static String refusal(EObject object, String address)
    {
        return object == null ? null : refusal(object.eClass().getName(), address);
    }

    static String refusal(String className, String address)
    {
        String node;
        String feature;
        if (CHART_CLASS.equals(className))
        {
            node = CHART_CLASS;
            feature = "chart"; //$NON-NLS-1$
        }
        else if (NESTED_DATA_SET_CLASS.equals(className))
        {
            node = NESTED_DATA_SET_CLASS;
            feature = "nested data set"; //$NON-NLS-1$
        }
        else
        {
            return null;
        }
        String location = address == null || address.isEmpty() ? "" : " at '" + address + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return node + location + " is a " + feature //$NON-NLS-1$
            + "; authoring it is not supported by this tool. Copy a schema that already contains " //$NON-NLS-1$
            + "it through the lossless XML channel: action='replace', type='schema', " //$NON-NLS-1$
            + "body={xml:...} on a bare schema root."; //$NON-NLS-1$
    }

    static boolean isChartKind(String kind)
    {
        return kind != null && ("chart".equalsIgnoreCase(kind) //$NON-NLS-1$
            || CHART_CLASS.equalsIgnoreCase(kind));
    }

    static boolean isNestedDataSetKind(String kind)
    {
        return kind != null && ("nestedDataSet".equalsIgnoreCase(kind) //$NON-NLS-1$
            || "nested-data-set".equalsIgnoreCase(kind) //$NON-NLS-1$
            || NESTED_DATA_SET_CLASS.equalsIgnoreCase(kind));
    }
}
