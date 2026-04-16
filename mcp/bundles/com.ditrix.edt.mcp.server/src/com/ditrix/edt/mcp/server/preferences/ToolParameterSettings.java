/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Manages per-tool configurable parameter defaults.
 * Parameters are stored in the preference store with key format: tool.{toolName}.{paramName}
 * These defaults are used when the AI client does not specify the parameter explicitly.
 */
public final class ToolParameterSettings
{
    /** Preference key prefix for tool parameters */
    private static final String KEY_PREFIX = "tool."; //$NON-NLS-1$

    private static final ToolParameterSettings INSTANCE = new ToolParameterSettings();

    /**
     * Describes a single configurable parameter.
     */
    public static final class ParameterDef
    {
        private final String name;
        private final String displayName;
        private final String description;
        private final int defaultValue;
        private final int minValue;
        private final int maxValue;

        ParameterDef(String name, String displayName, String description,
                     int defaultValue, int minValue, int maxValue)
        {
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public String getName()
        {
            return name;
        }

        public String getDisplayName()
        {
            return displayName;
        }

        public String getDescription()
        {
            return description;
        }

        public int getDefaultValue()
        {
            return defaultValue;
        }

        public int getMinValue()
        {
            return minValue;
        }

        public int getMaxValue()
        {
            return maxValue;
        }
    }

    /** All configurable tool parameters, keyed by tool name */
    private static final Map<String, List<ParameterDef>> TOOL_PARAMETERS;

    static
    {
        Map<String, List<ParameterDef>> map = new LinkedHashMap<>();

        map.put("get_project_errors", Collections.singletonList( //$NON-NLS-1$
            new ParameterDef("limit", "Result limit", //$NON-NLS-1$ //$NON-NLS-2$
                "Default number of errors to return", 100, 1, 10000))); //$NON-NLS-1$

        map.put("get_bookmarks", Collections.singletonList( //$NON-NLS-1$
            new ParameterDef("limit", "Result limit", //$NON-NLS-1$ //$NON-NLS-2$
                "Default number of bookmarks to return", 100, 1, 10000))); //$NON-NLS-1$

        map.put("get_tasks", Collections.singletonList( //$NON-NLS-1$
            new ParameterDef("limit", "Result limit", //$NON-NLS-1$ //$NON-NLS-2$
                "Default number of tasks to return", 100, 1, 10000))); //$NON-NLS-1$

        map.put("get_metadata_objects", Collections.singletonList( //$NON-NLS-1$
            new ParameterDef("limit", "Result limit", //$NON-NLS-1$ //$NON-NLS-2$
                "Default number of metadata objects to return", 100, 1, 1000))); //$NON-NLS-1$

        map.put("search_in_code", Arrays.asList( //$NON-NLS-1$
            new ParameterDef("maxResults", "Max results", //$NON-NLS-1$ //$NON-NLS-2$
                "Maximum number of search matches to return", 100, 1, 500), //$NON-NLS-1$
            new ParameterDef("contextLines", "Context lines", //$NON-NLS-1$ //$NON-NLS-2$
                "Lines of context around each match", 2, 0, 5))); //$NON-NLS-1$

        TOOL_PARAMETERS = Collections.unmodifiableMap(map);
    }

    private ToolParameterSettings()
    {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     */
    public static ToolParameterSettings getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the list of configurable parameters for a tool, or empty list if none.
     */
    public List<ParameterDef> getParametersForTool(String toolName)
    {
        return TOOL_PARAMETERS.getOrDefault(toolName, Collections.emptyList());
    }

    /**
     * Returns all tool names that have configurable parameters.
     */
    public List<String> getConfigurableToolNames()
    {
        return new ArrayList<>(TOOL_PARAMETERS.keySet());
    }

    /**
     * Returns all configurable parameters keyed by tool name.
     */
    public Map<String, List<ParameterDef>> getAllParameters()
    {
        return TOOL_PARAMETERS;
    }

    /**
     * Gets the current value for a tool parameter from preferences.
     * Returns the parameter's default if not set.
     *
     * @param toolName  the tool name
     * @param paramName the parameter name
     * @param fallback  fallback value if parameter definition not found
     * @return the parameter value
     */
    public int getParameterValue(String toolName, String paramName, int fallback)
    {
        List<ParameterDef> params = TOOL_PARAMETERS.get(toolName);
        if (params == null)
        {
            return fallback;
        }
        ParameterDef def = null;
        for (ParameterDef p : params)
        {
            if (p.getName().equals(paramName))
            {
                def = p;
                break;
            }
        }
        if (def == null)
        {
            return fallback;
        }

        IPreferenceStore store = getStore();
        if (store == null)
        {
            return def.getDefaultValue();
        }

        String key = buildKey(toolName, paramName);
        // If the preference has never been set, return the parameter default
        if (store.isDefault(key))
        {
            return def.getDefaultValue();
        }
        int value = store.getInt(key);
        return Math.max(def.getMinValue(), Math.min(def.getMaxValue(), value));
    }

    /**
     * Sets a tool parameter value in preferences.
     */
    public void setParameterValue(String toolName, String paramName, int value)
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return;
        }
        store.setValue(buildKey(toolName, paramName), value);
    }

    /**
     * Resets a tool parameter to its default value.
     */
    public void resetParameter(String toolName, String paramName)
    {
        IPreferenceStore store = getStore();
        if (store == null)
        {
            return;
        }
        store.setToDefault(buildKey(toolName, paramName));
    }

    /**
     * Initializes default values for all tool parameters in the preference store.
     */
    public void initializeDefaults(IPreferenceStore store)
    {
        for (Map.Entry<String, List<ParameterDef>> entry : TOOL_PARAMETERS.entrySet())
        {
            for (ParameterDef param : entry.getValue())
            {
                store.setDefault(buildKey(entry.getKey(), param.getName()), param.getDefaultValue());
            }
        }
    }

    /**
     * Builds the preference key for a tool parameter.
     */
    static String buildKey(String toolName, String paramName)
    {
        return KEY_PREFIX + toolName + "." + paramName; //$NON-NLS-1$
    }

    private IPreferenceStore getStore()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }
}
