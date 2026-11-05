// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ditrix.edt.mcp.server.HostKind;

/** Explicit admission inventory; moved entries name real host evidence and remaining limitations. */
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
        define("get_configuration_properties", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("clean_project", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("revalidate_objects", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("resync_to_disk", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("export_configuration_to_xml", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("import_configuration_from_xml", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("import_project_from_file", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("build_external_objects", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("export_configuration_to_file", Capability.HEADLESS, "Core prepare/dump uses an empty ExecutionContext; no mandatory shell handoff");
        define("delete_project", Capability.HEADLESS, "EDT 2026.2.2 real headless host previewed and confirmed deletion of a disposable project; its directory was removed");
        define("create_project", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_problem_summary", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("apply_quick_fix", Capability.HEADLESS_DEGRADED, "EDT 2026.2.2 real headless host applied the native md-object-duplicate-uuid fix and rewrote .mdo bytes; interactive BSL fixes require their native UI context");
        define("get_project_errors", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_markers", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_event_log", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_mcp_history", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_git_branches", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("switch_git_branch", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_branch_infobase", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_git_branch", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("git", Capability.HEADLESS, "EDT 2026.2.2 real headless host executed a gated branch write under the analysis-only preset; refs verified on disk");
        define("get_check_description", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("project_checks", Capability.HEADLESS, "EDT 2026.2.2 real headless host created and switched profiles under the analysis-only preset; .cset and preference files verified on disk");
        define("get_content_assist", Capability.WINDOWED_ONLY, "Real viewer-free probe fails at BslProposalProvider.createProposals:623 -> getDocEvent:7056 -> ITextViewer.getTextWidget; native proposals require an SWT viewer");
        define("get_platform_documentation", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_metadata_objects", Capability.HEADLESS, "GetMetadataObjectsToolTest verifies bilingual Names/TYPE tokens and language-code synonyms inside a BM read without a workbench; stdio contracts pin windowed output");
        define("get_metadata_details", Capability.HEADLESS, "GetMetadataDetailsToolTest verifies bilingual Names/TYPE tokens and synonym refusal inside BM reads without a workbench; lazy content retains rollback boundaries");
        define("dcs", Capability.HEADLESS, "EDT 2026.2.2 real headless host read and wrote English/Cyrillic targets; native designer exports verified on disk before another call");
        define("business_process_route_map", Capability.HEADLESS, "EDT 2026.2.2 real headless host read and wrote English/Cyrillic targets; native designer exports verified on disk before another call");
        define("list_common_pictures", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("export_common_picture", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("list_subsystems", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_subsystem_content", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("find_references", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_tags", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_objects_by_tags", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_applications", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_infobase", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("set_infobase_credentials", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("delete_infobase", Capability.HEADLESS, "EDT 2026.2.2 real headless host previewed and confirmed unregistering a private file infobase; registration removal and preserved database file verified on disk");
        define("infobase_sessions", Capability.HEADLESS_DEGRADED, "Real headless admission reaches native preconditions without consent; terminating a live server session still needs disposable runtime acceptance");
        define("infobase_extensions", Capability.HEADLESS_DEGRADED, "Real headless list reaches Designer runtime and fails on denied profile access in the sandbox; extension writes remain unmeasured");
        define("update_database", Capability.HEADLESS_DEGRADED, "Real headless file-infobase update reaches the core synchronization API and returns a bounded Designer connection error; successful apply and active-session acceptance remain unmeasured");
        define("launch", Capability.HEADLESS_DEGRADED, "Explicit synchronous headless path exists; platform launch delegates/dialogs need live validation");
        define("list_configurations", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("create_launch_config", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("delete_launch_config", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("run_yaxunit_tests", Capability.HEADLESS_DEGRADED, "Core/background path exists; update/launch delegates may require UI");
        define("ask_workmate", Capability.HEADLESS_DEGRADED, "Real headless token-preflight probe reaches ConversationFacade and network without a workbench; sandbox networking prevents a completed reply");
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
        define("evaluate_expression", Capability.HEADLESS_DEGRADED, "Real headless call reaches frame lookup and returns stale frameRef without consent refusal; evaluation in a paused runtime remains unmeasured");
        define("debug_yaxunit_tests", Capability.HEADLESS_DEGRADED, "Deprecated alias shares run_yaxunit_tests limitations");
        define("debug_status", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("start_profiling", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("stop_profiling", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_profiling_results", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("read_module_source", Capability.HEADLESS_DEGRADED, "ReadModuleSourceToolTest verifies whole/range reads of English/Cyrillic paths without entering the UI footer; optional interception enrichment remains windowed");
        define("open_editor", Capability.WINDOWED_ONLY, "Real headless call with navigation enabled fails in EditorNavigation.open -> BoundedUiCall.workbenchExecutorOrNull: EDT UI is unavailable");
        define("write_module_source", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_module_structure", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("list_modules", Capability.HEADLESS, "ListModulesToolTest verifies filtered BM reads and filesystem enumeration for English/Cyrillic Names without a workbench; stdio contracts pin windowed output");
        define("search_in_code", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("search_in_dcs", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("read_method_source", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_method_call_hierarchy", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_outgoing_structures", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("go_to_definition", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("get_symbol_info", Capability.HEADLESS_DEGRADED, "EDT 2026.2.2 real headless probe returns EMF symbol/signature/parameters for English and Cyrillic Names; editor hover type documentation requires a workbench");
        define("get_form_layout_snapshot", Capability.WINDOWED_ONLY, "Real headless form snapshot fails at EditorScreenshotHelper.getWorkbenchPage:313 -> PlatformUI.getWorkbench:119: Workbench has not been created yet");
        define("get_form_screenshot", Capability.WINDOWED_ONLY, "Real headless form capture fails at EditorScreenshotHelper.resolveFormEditor:767 -> openForm:215 -> getWorkbenchPage:313 -> PlatformUI.getWorkbench:119");
        define("get_template_screenshot", Capability.WINDOWED_ONLY, "Real headless CommonTemplate.PrintForm capture reports moxel presentation service unavailable; TemplateScreenshotHelper requires Display.getCurrent and an SWT Shell");
        define("validate_query", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("validate_form_model", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("rename_metadata_object", Capability.HEADLESS, "EDT 2026.2.2 real headless probe confirmed mutation and object-file changes; root export is checked separately by stdio_headless");
        define("delete_metadata", Capability.HEADLESS, "EDT 2026.2.2 real headless probe confirmed mutation and object-file changes; root export is checked separately by stdio_headless");
        define("create_metadata", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("modify_metadata", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("adopt_metadata_object", Capability.HEADLESS, "EDT 2026.2.2 real headless host adopted English/Cyrillic catalogs into an extension; exported object files read before another call");
        define("clone_metadata_object", Capability.HEADLESS, "EDT 2026.2.2 real headless probe confirmed mutation and object-file changes; root export is checked separately by stdio_headless");
        define("validate_xdto_package", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("compare_configurations", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_comparison_node", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("merge_rules", Capability.HEADLESS, "EDT 2026.2.2 real headless host wrote and rewrote the same rules path with basedOn; XML verified on disk");
        define("generate_translation_strings", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("translate_configuration", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("get_translation_project_info", Capability.HEADLESS, "Core/file service path; platform readiness and ordinary tool preconditions still apply");
        define("sort_metadata", Capability.HEADLESS, "EDT 2026.2.2 real headless probe returned content on the caller thread; stdio_headless exercises this path");
        define("edt_dialog", Capability.WINDOWED_ONLY, "Reads and presses SWT shells of the workbench display");
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
