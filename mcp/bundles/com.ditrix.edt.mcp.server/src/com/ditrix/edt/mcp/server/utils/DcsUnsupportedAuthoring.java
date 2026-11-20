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
        if (!NESTED_DATA_SET_CLASS.equals(className))
        {
            return null;
        }
        String location = address == null || address.isEmpty() ? "" : " at '" + address + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return NESTED_DATA_SET_CLASS + location + " is a nested data set" //$NON-NLS-1$
            + "; authoring it is not supported by this tool. Copy a schema that already contains " //$NON-NLS-1$
            + "it through the lossless XML channel: action='replace', type='schema', " //$NON-NLS-1$
            + "body={xml:...} on a bare schema root."; //$NON-NLS-1$
    }

    /**
     * Field templates and resource templates ({@code fieldTemplates}, {@code totalFieldsTemplates})
     * are not authored by the typed API (#662 covers area templates and grouping bindings only).
     */
    static String templateCollectionRefusal(String collection, String address)
    {
        return "'" + address + "' is in " + collection //$NON-NLS-1$ //$NON-NLS-2$
            + ": field and resource templates are not authorable through the typed API, so nothing was " //$NON-NLS-1$
            + "written. Change them through the lossless XML channel: read the schema with action='get', " //$NON-NLS-1$
            + "type='schema', format='xml' on the bare root, edit it, and send action='replace', " //$NON-NLS-1$
            + "type='schema', body={xml:...} with that read's expectedHash."; //$NON-NLS-1$
    }

    static boolean isNestedDataSetKind(String kind)
    {
        return kind != null && ("nestedDataSet".equalsIgnoreCase(kind) //$NON-NLS-1$
            || "nested-data-set".equalsIgnoreCase(kind) //$NON-NLS-1$
            || NESTED_DATA_SET_CLASS.equalsIgnoreCase(kind));
    }
}
