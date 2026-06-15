/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/**
 * Converts Eclipse {@link IVariable}/{@link IValue} into JSON-friendly DTOs
 * with truncation and a flat representation for nested structures.
 *
 * <p>Top-level variables are dumped as a flat list. Composite values
 * ({@code Структура}, {@code Соответствие}, {@code Массив}, references to
 * objects, etc.) get {@code hasChildren=true} and an opaque {@code ref} that
 * the caller can pass back to {@code get_variables} to expand.
 */
public final class VariableSerializer
{
    /** Hard cap for serialised string values to keep MCP responses sane. */
    public static final int MAX_VALUE_LENGTH = 500;

    /** DTO key: the variable's serialised value. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** DTO key: whether the value can be expanded into child variables. */
    private static final String KEY_HAS_CHILDREN = "hasChildren"; //$NON-NLS-1$

    private VariableSerializer()
    {
    }

    /**
     * Serialises all variables visible from the given stack frame.
     *
     * @param frame  the suspended stack frame
     * @param registry registry used to allocate {@code ref} ids for child variables
     * @return list of variable DTOs (each is a {@code LinkedHashMap} suitable for Gson)
     */
    public static List<Map<String, Object>> serializeFrame(IStackFrame frame,
            DebugSessionRegistry registry) throws Exception
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (frame == null)
        {
            return out;
        }
        // Do NOT gate on frame.hasVariables() here: some 1C BSL stack frames populate
        // their internal variable array lazily, and their hasVariables() reads that
        // array's length — throwing an NPE ("this.variables is null") when called
        // BEFORE getVariables() has realized it. getVariables() is the populating call,
        // so invoke it directly and guard for a null/empty result. (Observed on a live
        // YAXUnit debug frame; the EvaluateExpression path works on the same frame, so
        // the frame is valid — only the lazy hasVariables() probe was unsafe.)
        IVariable[] vars = frame.getVariables();
        if (vars == null)
        {
            return out;
        }
        for (IVariable var : vars)
        {
            out.add(serializeVariable(var, registry));
        }
        return out;
    }

    /**
     * Serialises children of a single {@link IVariable} (used for {@code expandPath}).
     */
    public static List<Map<String, Object>> serializeChildren(IVariable parent,
            DebugSessionRegistry registry) throws Exception
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parent == null)
        {
            return out;
        }
        IValue value = parent.getValue();
        if (value == null || !value.hasVariables())
        {
            return out;
        }
        for (IVariable child : value.getVariables())
        {
            out.add(serializeVariable(child, registry));
        }
        return out;
    }

    /**
     * Builds a flat DTO for a single variable (no recursion into children).
     */
    public static Map<String, Object> serializeVariable(IVariable var,
            DebugSessionRegistry registry) throws Exception
    {
        Map<String, Object> dto = new LinkedHashMap<>();
        String name = safe(var.getName());
        dto.put("name", name); //$NON-NLS-1$

        IValue value = null;
        try
        {
            value = var.getValue();
        }
        catch (Exception ex)
        {
            dto.put("type", "<unknown>"); //$NON-NLS-1$ //$NON-NLS-2$
            dto.put(KEY_VALUE, "<error: " + ex.getMessage() + ">"); //$NON-NLS-1$
            dto.put(KEY_HAS_CHILDREN, false);
            return dto;
        }

        if (value == null)
        {
            dto.put("type", "Undefined"); //$NON-NLS-1$ //$NON-NLS-2$
            dto.put(KEY_VALUE, null);
            dto.put(KEY_HAS_CHILDREN, false);
            return dto;
        }

        String type = safe(value.getReferenceTypeName());
        dto.put("type", type); //$NON-NLS-1$

        String stringValue;
        try
        {
            stringValue = value.getValueString();
        }
        catch (Exception ex)
        {
            stringValue = "<error: " + ex.getMessage() + ">"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (stringValue != null && stringValue.length() > MAX_VALUE_LENGTH)
        {
            dto.put(KEY_VALUE, stringValue.substring(0, MAX_VALUE_LENGTH));
            dto.put("truncated", true); //$NON-NLS-1$
            dto.put("fullLength", stringValue.length()); //$NON-NLS-1$
        }
        else
        {
            dto.put(KEY_VALUE, stringValue);
        }

        boolean hasChildren;
        try
        {
            hasChildren = value.hasVariables();
        }
        catch (Exception ex)
        {
            hasChildren = false;
        }
        dto.put(KEY_HAS_CHILDREN, hasChildren);
        if (hasChildren)
        {
            // Hint for the caller: pass this name as expandPath (or append it
            // to the current expandPath with a dot separator) to drill in.
            dto.put("expandHint", name); //$NON-NLS-1$
        }
        return dto;
    }

    /**
     * Walks {@code expandPath} (dot-separated names) starting from frame variables
     * and returns the resolved {@link IVariable}, or {@code null} if not found.
     */
    public static IVariable resolvePath(IStackFrame frame, String expandPath) throws Exception
    {
        if (frame == null || expandPath == null || expandPath.isEmpty())
        {
            return null;
        }
        String[] parts = expandPath.split("\\."); //$NON-NLS-1$
        IVariable[] current = frame.getVariables();
        IVariable found = null;
        for (String part : parts)
        {
            found = null;
            for (IVariable v : current)
            {
                if (part.equalsIgnoreCase(v.getName()))
                {
                    found = v;
                    break;
                }
            }
            if (found == null)
            {
                return null;
            }
            IValue val = found.getValue();
            if (val == null || !val.hasVariables())
            {
                // last segment is fine, otherwise nothing further to walk
                current = new IVariable[0];
            }
            else
            {
                current = val.getVariables();
            }
        }
        return found;
    }

    private static String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }
}
