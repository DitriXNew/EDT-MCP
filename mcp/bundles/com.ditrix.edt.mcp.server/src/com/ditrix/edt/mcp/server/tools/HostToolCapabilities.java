// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ditrix.edt.mcp.server.HostKind;

/** Code-audited admission inventory; runtime certification still requires live EDT acceptance. */
public final class HostToolCapabilities
{
    public enum Capability { HEADLESS, HEADLESS_DEGRADED, WINDOWED_ONLY }
    public record Entry(Capability capability, String reason) { }
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    static
    {
        define("get_edt_version", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_server_status", Capability.HEADLESS, "Core status; render modes remain unknown without a renderer");
        define("get_tool_guide", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_toolsets", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("enable_toolset", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_projects", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_configuration_properties", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("clean_project", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("revalidate_objects", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("resync_to_disk", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("export_configuration_to_xml", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("import_configuration_from_xml", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("import_project_from_file", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("build_external_objects", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("export_configuration_to_file", Capability.HEADLESS, "Core prepare/dump uses an empty ExecutionContext; no mandatory shell handoff");
        define("delete_project", Capability.WINDOWED_ONLY, "DestructiveConsentGate requires a live operator shell");
        define("create_project", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_problem_summary", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("apply_quick_fix", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_project_errors", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_markers", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_event_log", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_mcp_history", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_git_branches", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("switch_git_branch", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_branch_infobase", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_git_branch", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("git", Capability.HEADLESS_DEGRADED, "Read subcommands remain; gated write subcommands refuse without live UI");
        define("get_check_description", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("project_checks", Capability.HEADLESS_DEGRADED, "Read/list remain; gated settings/profile writes refuse");
        define("get_content_assist", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_platform_documentation", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_metadata_objects", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_metadata_details", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("dcs", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("business_process_route_map", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("list_common_pictures", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("export_common_picture", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_subsystems", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_subsystem_content", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("find_references", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_tags", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_objects_by_tags", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_applications", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_infobase", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_infobase_credentials", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("delete_infobase", Capability.WINDOWED_ONLY, "DestructiveConsentGate requires a live operator shell");
        define("infobase_sessions", Capability.HEADLESS_DEGRADED, "List remains; gated terminate refuses");
        define("infobase_extensions", Capability.HEADLESS_DEGRADED, "List remains; gated mutations refuse");
        define("update_database", Capability.WINDOWED_ONLY, "DestructiveConsentGate requires a live operator shell");
        define("launch", Capability.HEADLESS_DEGRADED, "Explicit synchronous headless path exists; platform launch delegates/dialogs need live validation");
        define("list_configurations", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_launch_config", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("delete_launch_config", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("run_yaxunit_tests", Capability.HEADLESS_DEGRADED, "Core/background path exists; update/launch delegates may require UI");
        define("ask_workmate", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_job_status", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("cancel_job", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("terminate_launch", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_breakpoint", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_error_breakpoint", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("remove_breakpoint", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_breakpoints", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("wait_for_break", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_variables", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_variable", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("step", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("resume", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("debug_pause", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("evaluate_expression", Capability.WINDOWED_ONLY, "DestructiveConsentGate requires a live operator shell");
        define("debug_yaxunit_tests", Capability.HEADLESS_DEGRADED, "Deprecated alias shares run_yaxunit_tests limitations");
        define("debug_status", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("start_profiling", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("stop_profiling", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_profiling_results", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("read_module_source", Capability.HEADLESS_DEGRADED, "File source remains; best-effort UI interception footer is omitted");
        define("open_editor", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("write_module_source", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_module_structure", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("list_modules", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("search_in_code", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("search_in_dcs", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("read_method_source", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_method_call_hierarchy", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_outgoing_structures", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("go_to_definition", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_symbol_info", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_form_layout_snapshot", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_form_screenshot", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("get_template_screenshot", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("validate_query", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("validate_form_model", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("rename_metadata_object", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("delete_metadata", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("create_metadata", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("modify_metadata", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("adopt_metadata_object", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("clone_metadata_object", Capability.WINDOWED_ONLY, "Mandatory workbench, editor or UI-thread path (including shared helpers)");
        define("validate_xdto_package", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("compare_configurations", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_comparison_node", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("merge_rules", Capability.HEADLESS_DEGRADED, "Read and fresh-path write remain; same-path rewrite requires consent");
        define("generate_translation_strings", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("translate_configuration", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_translation_project_info", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("sort_metadata", Capability.WINDOWED_ONLY, "AbstractMetadataWriteTool dispatches to the UI thread");
    }

    private HostToolCapabilities() { }
    private static void define(String name, Capability capability, String reason)
    {
        if (ENTRIES.put(name, new Entry(capability, reason)) != null) { throw new IllegalStateException("Duplicate host classification: " + name); }
    }
    public static Map<String, Entry> entries() { return Collections.unmodifiableMap(ENTRIES); }
    public static Entry requireEntry(String name)
    {
        Entry entry = ENTRIES.get(name);
        if (entry == null) { throw new IllegalStateException("Missing host classification: " + name); }
        return entry;
    }
    public static boolean available(String name)
    {
        Entry entry = ENTRIES.get(name);
        return entry != null && entry.capability() != Capability.WINDOWED_ONLY;
    }
    public static boolean availableOnCurrentHost(String name)
    { return HostKind.current() == HostKind.WINDOWED || available(name); }
    public static String refusal(String name)
    {
        return "Tool '" + name + "' needs a windowed EDT; this workspace is running in a headless host. "
            + "Remove -headless from .mcp.json and close the host, or open the workspace in EDT after closing the host. "
            + "No tool ran. The bootstrap never starts a second EDT or closes the existing host.";
    }
}
