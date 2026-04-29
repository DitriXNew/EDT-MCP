/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines logical groups for MCP tools.
 * Each group contains a set of related tools that can be enabled/disabled together.
 */
public enum ToolGroup
{
    CORE("core", "Core / Project", //$NON-NLS-1$ //$NON-NLS-2$
        "Essential project, configuration, and XML export/import tools", //$NON-NLS-1$
        "get_edt_version", "list_projects", "get_configuration_properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "clean_project", "revalidate_objects", "get_check_description", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "export_configuration_to_xml", "import_configuration_from_xml"), //$NON-NLS-1$ //$NON-NLS-2$

    PROBLEMS("problems", "Errors & Problems", //$NON-NLS-1$ //$NON-NLS-2$
        "Error reporting, bookmarks, and tasks", //$NON-NLS-1$
        "get_problem_summary", "get_project_errors", "get_bookmarks", "get_tasks"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    CODE_INTELLIGENCE("codeIntelligence", "Code Intelligence", //$NON-NLS-1$ //$NON-NLS-2$
        "Content assist, documentation, metadata browsing, and references", //$NON-NLS-1$
        "get_content_assist", "get_platform_documentation", "get_metadata_objects", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "get_metadata_details", "list_subsystems", "get_subsystem_content", "find_references"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    TAGS("tags", "Tags", //$NON-NLS-1$ //$NON-NLS-2$
        "Metadata tag management", //$NON-NLS-1$
        "get_tags", "get_objects_by_tags"), //$NON-NLS-1$ //$NON-NLS-2$

    APPLICATIONS("applications", "Applications & Testing", //$NON-NLS-1$ //$NON-NLS-2$
        "Application management, database update, launch, and testing", //$NON-NLS-1$
        "get_applications", "list_configurations", "update_database", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "debug_launch", "run_yaxunit_tests"), //$NON-NLS-1$ //$NON-NLS-2$

    DEBUG("debug", "Debugging", //$NON-NLS-1$ //$NON-NLS-2$
        "Breakpoints, stepping, variables, expression evaluation, and profiling", //$NON-NLS-1$
        "set_breakpoint", "remove_breakpoint", "list_breakpoints", "wait_for_break", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "get_variables", "step", "resume", "evaluate_expression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "debug_yaxunit_tests", "debug_status", "start_profiling", "get_profiling_results"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    BSL_CODE("bslCode", "BSL Code", //$NON-NLS-1$ //$NON-NLS-2$
        "Module source reading/writing, structure, search, call hierarchy, and navigation", //$NON-NLS-1$
        "read_module_source", "write_module_source", "get_module_structure", "list_modules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "search_in_code", "read_method_source", "get_method_call_hierarchy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "go_to_definition", "get_symbol_info", "get_form_screenshot", "validate_query"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    REFACTORING("refactoring", "Refactoring", //$NON-NLS-1$ //$NON-NLS-2$
        "Metadata rename, delete, and attribute management", //$NON-NLS-1$
        "rename_metadata_object", "delete_metadata_object", "add_metadata_attribute"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    TRANSLATION("translation", "Translation (LanguageTool)", //$NON-NLS-1$ //$NON-NLS-2$
        "LanguageTool: translation strings generation, configuration sync, project info", //$NON-NLS-1$
        "generate_translation_strings", "translate_configuration", //$NON-NLS-1$ //$NON-NLS-2$
        "get_translation_project_info"); //$NON-NLS-1$

    private final String id;
    private final String displayName;
    private final String description;
    private final List<String> toolNames;

    /** Reverse lookup: tool name -> group */
    private static final Map<String, ToolGroup> TOOL_TO_GROUP;

    static
    {
        Map<String, ToolGroup> map = new HashMap<>();
        for (ToolGroup group : values())
        {
            for (String toolName : group.toolNames)
            {
                map.put(toolName, group);
            }
        }
        TOOL_TO_GROUP = Collections.unmodifiableMap(map);
    }

    ToolGroup(String id, String displayName, String description, String... toolNames)
    {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.toolNames = Collections.unmodifiableList(Arrays.asList(toolNames));
    }

    /**
     * Returns the group identifier used in preference keys.
     */
    public String getId()
    {
        return id;
    }

    /**
     * Returns the human-readable group name for UI display.
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the group description for tooltips.
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the ordered list of tool names in this group.
     */
    public List<String> getToolNames()
    {
        return toolNames;
    }

    /**
     * Returns the group that contains the given tool, or null if not found.
     */
    public static ToolGroup getGroupForTool(String toolName)
    {
        return TOOL_TO_GROUP.get(toolName);
    }

    /**
     * Returns the total number of tools across all groups.
     */
    public static int getTotalToolCount()
    {
        return TOOL_TO_GROUP.size();
    }
}
