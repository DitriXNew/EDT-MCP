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
        "Essential server, project, configuration, history, and XML export/import tools", //$NON-NLS-1$
        "get_edt_version", "get_server_status", "get_tool_guide", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "list_toolsets", "enable_toolset", "list_projects", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "get_configuration_properties", //$NON-NLS-1$
        "clean_project", "revalidate_objects", "resync_to_disk", "get_check_description", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "export_configuration_to_xml", "import_configuration_from_xml", //$NON-NLS-1$ //$NON-NLS-2$
        "delete_project", "create_project", "get_event_log", "get_mcp_history"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    PROBLEMS("problems", "Errors & Problems", //$NON-NLS-1$ //$NON-NLS-2$
        "Error reporting, validation, and workspace markers (bookmarks, tasks)", //$NON-NLS-1$
        "get_problem_summary", "get_project_errors", "get_markers", "apply_quick_fix", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "validate_xdto_package"), //$NON-NLS-1$

    CODE_INTELLIGENCE("codeIntelligence", "Code Intelligence", //$NON-NLS-1$ //$NON-NLS-2$
        "Content assist, documentation, metadata and common-picture browsing, and references", //$NON-NLS-1$
        "get_content_assist", "get_platform_documentation", "get_metadata_objects", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "get_metadata_details", "list_subsystems", "get_subsystem_content", "find_references", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "list_common_pictures", "export_common_picture"), //$NON-NLS-1$ //$NON-NLS-2$

    TAGS("tags", "Tags", //$NON-NLS-1$ //$NON-NLS-2$
        "Metadata tag management", //$NON-NLS-1$
        "get_tags", "get_objects_by_tags"), //$NON-NLS-1$ //$NON-NLS-2$

    APPLICATIONS("applications", "Applications & Testing", //$NON-NLS-1$ //$NON-NLS-2$
        "Application and infobase management, external-object builds, launch, testing, " //$NON-NLS-1$
            + "background jobs, and Workmate", //$NON-NLS-1$
        "get_applications", "list_configurations", "create_launch_config", "delete_launch_config", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "create_infobase", "delete_infobase", "update_database", "launch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "terminate_launch", "run_yaxunit_tests", "ask_workmate", "get_job_status", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "cancel_job", "build_external_objects", "set_infobase_credentials"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    DEBUG("debug", "Debugging", //$NON-NLS-1$ //$NON-NLS-2$
        "Breakpoints, stepping, variables, expression evaluation, and profiling", //$NON-NLS-1$
        "set_breakpoint", "remove_breakpoint", "list_breakpoints", "wait_for_break", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "get_variables", "set_variable", "step", "resume", "evaluate_expression", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "debug_yaxunit_tests", "debug_status", "start_profiling", "stop_profiling", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "get_profiling_results"), //$NON-NLS-1$

    BSL_CODE("bslCode", "BSL Code", //$NON-NLS-1$ //$NON-NLS-2$
        "Module source reading/writing, structure, search, call hierarchy, navigation, and forms", //$NON-NLS-1$
        "read_module_source", "write_module_source", "get_module_structure", "list_modules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "search_in_code", "read_method_source", "get_method_call_hierarchy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "get_outgoing_structures", "go_to_definition", "get_symbol_info", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "get_form_layout_snapshot", //$NON-NLS-1$
        "get_form_screenshot", "get_template_screenshot", "validate_query"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    REFACTORING("refactoring", "Refactoring", //$NON-NLS-1$ //$NON-NLS-2$
        "Metadata and DCS create, inspect, rename, adopt, delete, and property management", //$NON-NLS-1$
        "rename_metadata_object", "delete_metadata", "create_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "modify_metadata", "adopt_metadata_object", "dcs"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    TRANSLATION("translation", "Translation (LanguageTool)", //$NON-NLS-1$ //$NON-NLS-2$
        "LanguageTool: translation strings generation, configuration sync, project info", //$NON-NLS-1$
        "generate_translation_strings", "translate_configuration", //$NON-NLS-1$ //$NON-NLS-2$
        "get_translation_project_info"), //$NON-NLS-1$

    /**
     * Three-way configuration comparison. Its own group rather than a few names appended to an
     * existing one: the three tools are useless apart (a comparison is started, then expanded,
     * then its merge rules are read or authored), and EDT runs ONE comparison at a time, so an
     * operator who wants that slot left alone turns off exactly this group. Nothing here ships
     * disabled - the family never merges and never writes the project.
     */
    COMPARISON("comparison", "Comparison", //$NON-NLS-1$ //$NON-NLS-2$
        "Three-way configuration comparison: start one against two git revisions, expand a node," //$NON-NLS-1$
            + " and read or author the merge-rules file", //$NON-NLS-1$
        "compare_configurations", "get_comparison_node", "merge_rules"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Git tools. The {@code git} command tool ships DISABLED by default, and this tree is the UI its
     * description points at - so it MUST appear here, otherwise there is no way to enable it from the
     * Tools tab. The branch tools - including {@code set_branch_infobase}, which MUTATES the
     * branch-to-infobase binding - are listed alongside it so the whole Git surface is manageable in
     * one place: a group that omitted one would leave it enabled after the operator disabled the
     * group (and after a disable-all, which iterates the groups). Membership does not change what
     * ships enabled - only {@code git} is disabled by default - but it does put the branch tools
     * under this group's toggle, which is the point.
     */
    GIT("git", "Git", //$NON-NLS-1$ //$NON-NLS-2$
        "Git operations: the 'git' command tool (disabled by default), branch listing/switching" //$NON-NLS-1$
            + " and the branch-to-infobase binding", //$NON-NLS-1$
        "git", "list_git_branches", "switch_git_branch", "create_git_branch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "set_branch_infobase"); //$NON-NLS-1$

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
