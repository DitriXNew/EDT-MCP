/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;

/**
 * The DCS model stores field folders and their children in one flat {@link DataSet#getFields()}
 * list. The platform hierarchy is encoded by dotted {@code dataPath} prefixes. This class is the
 * single projection of that flat storage into the public nested {@code /fields/.../fields/...}
 * address tree.
 */
final class DcsFieldFolders
{
    private static final char PATH_SEPARATOR = '.';

    private DcsFieldFolders()
    {
        // Utility class
    }

    static String key(DataSetField field)
    {
        if (field == null) return null;
        EStructuralFeature feature = field.eClass().getEStructuralFeature("dataPath"); //$NON-NLS-1$
        Object value = feature == null ? null : field.eGet(feature);
        return value instanceof String ? (String)value : null;
    }

    static DataCompositionSchemaDataSetFieldFolder parent(DataSet dataSet, DataSetField field)
    {
        if (dataSet == null || field == null) return null;
        String path = key(field);
        if (path == null || path.isEmpty()) return null;
        DataCompositionSchemaDataSetFieldFolder best = null;
        int bestLength = -1;
        for (DataSetField candidate : dataSet.getFields())
        {
            if (candidate == field || !(candidate instanceof DataCompositionSchemaDataSetFieldFolder))
            {
                continue;
            }
            String folderPath = key(candidate);
            if (isDescendantPath(folderPath, path) && folderPath.length() > bestLength)
            {
                best = (DataCompositionSchemaDataSetFieldFolder)candidate;
                bestLength = folderPath.length();
            }
        }
        return best;
    }

    static List<DataSetField> children(DataSet dataSet,
        DataCompositionSchemaDataSetFieldFolder folder)
    {
        if (dataSet == null) return Collections.emptyList();
        List<DataSetField> result = new ArrayList<>();
        for (DataSetField field : dataSet.getFields())
        {
            if (field != folder && parent(dataSet, field) == folder)
            {
                result.add(field);
            }
        }
        return result;
    }

    static List<DataSetField> descendants(DataSet dataSet,
        DataCompositionSchemaDataSetFieldFolder folder)
    {
        String folderPath = key(folder);
        if (dataSet == null || folderPath == null || folderPath.isEmpty())
        {
            return Collections.emptyList();
        }
        List<DataSetField> result = new ArrayList<>();
        for (DataSetField field : dataSet.getFields())
        {
            if (field != folder && isDescendantPath(folderPath, key(field))) result.add(field);
        }
        return result;
    }

    static List<DataCompositionSchemaDataSetFieldFolder> ancestors(DataSet dataSet,
        DataSetField field)
    {
        List<DataCompositionSchemaDataSetFieldFolder> result = new ArrayList<>();
        DataCompositionSchemaDataSetFieldFolder current = parent(dataSet, field);
        while (current != null)
        {
            result.add(current);
            current = parent(dataSet, current);
        }
        Collections.reverse(result);
        return result;
    }

    static void renameSubtree(DataSet dataSet,
        DataCompositionSchemaDataSetFieldFolder folder, String newPath)
    {
        String oldPath = key(folder);
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;
        List<DataSetField> descendants = descendants(dataSet, folder);
        folder.setDataPath(newPath);
        for (DataSetField child : descendants)
        {
            String childPath = key(child);
            EStructuralFeature feature = child.eClass().getEStructuralFeature("dataPath"); //$NON-NLS-1$
            if (feature != null)
            {
                child.eSet(feature, newPath + childPath.substring(oldPath.length()));
            }
        }
    }

    private static boolean isDescendantPath(String folderPath, String candidatePath)
    {
        return folderPath != null && candidatePath != null && !folderPath.isEmpty()
            && candidatePath.length() > folderPath.length()
            && candidatePath.startsWith(folderPath)
            && candidatePath.charAt(folderPath.length()) == PATH_SEPARATOR;
    }
}
