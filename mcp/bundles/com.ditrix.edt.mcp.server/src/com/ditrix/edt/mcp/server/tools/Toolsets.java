/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Static catalogue that groups every built-in tool into a named <em>toolset</em>,
 * the unit of progressive tool disclosure (dynamic toolsets).
 * <p>
 * When the {@link PreferenceConstants#PREF_PROGRESSIVE_DISCLOSURE} preference is on,
 * {@code tools/list} exposes only the {@link #CORE} toolset plus whatever toolsets
 * were enabled at runtime ({@link ToolsetState}); when off (the default), the full
 * tool list is exposed exactly as before. The {@code list_toolsets} and
 * {@code enable_toolset} meta-tools live in {@link #CORE}, so they are always
 * reachable to drive disclosure.
 * <p>
 * This is the single source of truth for tool grouping. {@code ToolsetsTest}
 * ratchets it: every registered tool must be EXPLICITLY assigned here (a new or
 * renamed tool fails the build until categorized), and every assigned name must be
 * a real registered tool.
 */
public final class Toolsets
{
    /** The always-on toolset id (navigation/read essentials + toolset management). */
    public static final String CORE = "core"; //$NON-NLS-1$
    /** Metadata objects: discovery, CRUD, subsystems, configuration. */
    public static final String METADATA = "metadata"; //$NON-NLS-1$
    /** BSL code: write/read methods, navigation, references, content assist, queries. */
    public static final String CODE = "code"; //$NON-NLS-1$
    /** Runtime debugging: launches, breakpoints, suspended-state inspection. */
    public static final String DEBUG = "debug"; //$NON-NLS-1$
    /** YAXUnit unit testing (run / debug). */
    public static final String TESTING = "testing"; //$NON-NLS-1$
    /** Performance profiling (start / stop / results). */
    public static final String PROFILING = "profiling"; //$NON-NLS-1$
    /** Form rendering (layout snapshot / screenshot). */
    public static final String FORMS = "forms"; //$NON-NLS-1$
    /** Tag-based organization and lookup. */
    public static final String TAGS = "tags"; //$NON-NLS-1$
    /** Configuration translation (LanguageTool). */
    public static final String TRANSLATION = "translation"; //$NON-NLS-1$
    /** Project operations: build/validate/update DB, export/import, problems. */
    public static final String PROJECT = "project"; //$NON-NLS-1$

    /** A toolset: a stable id plus a human-readable title and description. */
    public static final class Toolset
    {
        private final String id;
        private final String title;
        private final String description;

        Toolset(String id, String title, String description)
        {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        public String getId()
        {
            return id;
        }

        public String getTitle()
        {
            return title;
        }

        public String getDescription()
        {
            return description;
        }
    }

    /** Toolset id -> metadata, in display order (core first). */
    private static final Map<String, Toolset> TOOLSETS = new LinkedHashMap<>();

    /** Tool name -> toolset id (explicit; no implicit defaulting for the ratchet). */
    private static final Map<String, String> TOOL_TO_TOOLSET = new LinkedHashMap<>();

    static
    {
        define(CORE, "Core",
            "Always-on essentials: project/module navigation, source read, metadata discovery, " //$NON-NLS-1$
                + "and the toolset-management tools (list_toolsets / enable_toolset)."); //$NON-NLS-1$
        define(METADATA, "Metadata",
            "Metadata objects: discovery, create/modify/delete/rename/adopt, subsystems, configuration."); //$NON-NLS-1$
        define(CODE, "Code",
            "BSL code: write/read methods, call hierarchy, go-to-definition, references, content assist, queries."); //$NON-NLS-1$
        define(DEBUG, "Debug",
            "Runtime debugging: launch/attach, breakpoints, step/resume, variables, expression evaluation."); //$NON-NLS-1$
        define(TESTING, "Testing",
            "YAXUnit unit testing: run and debug test suites."); //$NON-NLS-1$
        define(PROFILING, "Profiling",
            "Performance profiling: start/stop a measurement and read the results."); //$NON-NLS-1$
        define(FORMS, "Forms",
            "Form and template rendering: form layout snapshot, form screenshot, template screenshot."); //$NON-NLS-1$
        define(TAGS, "Tags",
            "Tag-based organization: list tags and find objects by tag."); //$NON-NLS-1$
        define(TRANSLATION, "Translation",
            "Configuration translation via LanguageTool: extract, translate, project info."); //$NON-NLS-1$
        define(PROJECT, "Project",
            "Project operations: clean/revalidate, update DB, export/import XML, problems and markers, docs."); //$NON-NLS-1$

        assign(CORE,
            "list_toolsets", "enable_toolset", //$NON-NLS-1$ //$NON-NLS-2$
            "get_tool_guide", "get_server_status", "get_edt_version", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "list_projects", "list_modules", "get_module_structure", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "read_module_source", "search_in_code", //$NON-NLS-1$ //$NON-NLS-2$
            "get_metadata_objects", "get_metadata_details"); //$NON-NLS-1$ //$NON-NLS-2$

        assign(METADATA,
            "create_metadata", "modify_metadata", "delete_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "rename_metadata_object", "adopt_metadata_object", //$NON-NLS-1$ //$NON-NLS-2$
            "get_subsystem_content", "list_subsystems", //$NON-NLS-1$ //$NON-NLS-2$
            "get_configuration_properties", "list_configurations", //$NON-NLS-1$ //$NON-NLS-2$
            "create_launch_config", "delete_launch_config"); //$NON-NLS-1$ //$NON-NLS-2$

        assign(CODE,
            "write_module_source", "read_method_source", "get_method_call_hierarchy", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "go_to_definition", "get_symbol_info", "find_references", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "get_content_assist", "validate_query"); //$NON-NLS-1$ //$NON-NLS-2$

        assign(DEBUG,
            "debug_launch", "debug_status", "set_breakpoint", "remove_breakpoint", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "list_breakpoints", "wait_for_break", "get_variables", "step", "resume", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "evaluate_expression", "get_applications", "terminate_launch"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assign(TESTING,
            "run_yaxunit_tests", "debug_yaxunit_tests"); //$NON-NLS-1$ //$NON-NLS-2$

        assign(PROFILING,
            "start_profiling", "stop_profiling", "get_profiling_results"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assign(FORMS,
            "get_form_layout_snapshot", "get_form_screenshot", "get_template_screenshot"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assign(TAGS,
            "get_tags", "get_objects_by_tags"); //$NON-NLS-1$ //$NON-NLS-2$

        assign(TRANSLATION,
            "generate_translation_strings", "translate_configuration", "get_translation_project_info"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assign(PROJECT,
            "clean_project", "revalidate_objects", "resync_to_disk", "update_database", "delete_project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "export_configuration_to_xml", "import_configuration_from_xml", "build_external_objects", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "create_infobase", "delete_infobase", "set_infobase_credentials", "create_project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "get_problem_summary", "get_project_errors", "get_markers", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "get_check_description", "get_platform_documentation"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Toolsets()
    {
        // Utility class
    }

    private static void define(String id, String title, String description)
    {
        TOOLSETS.put(id, new Toolset(id, title, description));
    }

    private static void assign(String toolsetId, String... toolNames)
    {
        for (String name : toolNames)
        {
            TOOL_TO_TOOLSET.put(name, toolsetId);
        }
    }

    /**
     * The toolset id a tool belongs to, or {@link #CORE} when the tool is not
     * explicitly mapped (so an as-yet-uncategorized tool is never hidden at
     * runtime — the build-time ratchet is what forces explicit categorization).
     *
     * @param toolName the tool name
     * @return the toolset id (never {@code null})
     */
    public static String toolsetOf(String toolName)
    {
        return TOOL_TO_TOOLSET.getOrDefault(toolName, CORE);
    }

    /**
     * Whether the tool is EXPLICITLY assigned to a toolset (used by the ratchet to
     * forbid silent CORE-defaulting of a new/renamed tool).
     *
     * @param toolName the tool name
     * @return {@code true} if explicitly mapped
     */
    public static boolean isExplicitlyMapped(String toolName)
    {
        return TOOL_TO_TOOLSET.containsKey(toolName);
    }

    /**
     * All defined toolsets in display order (core first).
     *
     * @return the toolsets, unmodifiable
     */
    public static Collection<Toolset> all()
    {
        return Collections.unmodifiableCollection(TOOLSETS.values());
    }

    /**
     * Looks up a toolset by id.
     *
     * @param id the toolset id
     * @return the toolset, or {@code null} if no such id
     */
    public static Toolset get(String id)
    {
        return id == null ? null : TOOLSETS.get(id);
    }

    /**
     * Whether a toolset id is defined.
     *
     * @param id the toolset id
     * @return {@code true} if defined
     */
    public static boolean exists(String id)
    {
        return id != null && TOOLSETS.containsKey(id);
    }

    /**
     * The tool names assigned to a toolset, in catalogue order.
     *
     * @param toolsetId the toolset id
     * @return the tool names (possibly empty), unmodifiable
     */
    public static List<String> toolNamesOf(String toolsetId)
    {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : TOOL_TO_TOOLSET.entrySet())
        {
            if (entry.getValue().equals(toolsetId))
            {
                names.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Env override for {@link #isProgressiveDisclosureEnabled()}: when set (non-blank)
     * it wins over the preference, so a headless/CI run can turn disclosure on/off
     * without touching the workspace preferences (mirrors {@code EDT_MCP_AUTO_START}).
     */
    static final String ENV_PROGRESSIVE_DISCLOSURE = "EDT_MCP_PROGRESSIVE_DISCLOSURE"; //$NON-NLS-1$

    /**
     * Whether progressive tool disclosure is enabled. The {@link #ENV_PROGRESSIVE_DISCLOSURE}
     * environment variable wins when set; otherwise the
     * {@link PreferenceConstants#PREF_PROGRESSIVE_DISCLOSURE} preference is read.
     * Null-safe for a headless/test context with no Activator or preference store:
     * defaults to {@link PreferenceConstants#DEFAULT_PROGRESSIVE_DISCLOSURE} (off).
     *
     * @return {@code true} if progressive disclosure is on
     */
    public static boolean isProgressiveDisclosureEnabled()
    {
        String env = System.getenv(ENV_PROGRESSIVE_DISCLOSURE);
        if (env != null && !env.isBlank())
        {
            String v = env.trim();
            return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getPreferenceStore() == null)
        {
            return PreferenceConstants.DEFAULT_PROGRESSIVE_DISCLOSURE;
        }
        return activator.getPreferenceStore()
            .getBoolean(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE);
    }
}
