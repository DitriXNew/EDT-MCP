# Tool Management

Control which MCP tools are exposed to AI assistants. This lets you reduce context window usage and restrict AI to read-only operations when needed.

## Tool Groups

Tools are organized into preference groups that control enablement; progressive-disclosure toolsets in the
[tool index](tool-overviews.md#available-tools) control visibility and have separate membership.
`git`, `ask_workmate`, and `get_form_screenshot` are **disabled by default**. Enable
them in **Window → Preferences → MCP Server → Tools**; `enable_toolset` only changes
progressive-disclosure visibility and does not enable disabled tools:

| Group | Description | Tools |
|-------|-------------|-------|
| **Core / Project** | Essential server, project, configuration, history, and XML export/import tools | `get_edt_version`, `get_server_status`, `get_tool_guide`, `list_toolsets`, `enable_toolset`, `list_projects`, `get_configuration_properties`, `clean_project`, `revalidate_objects`, `resync_to_disk`, `get_check_description`, `export_configuration_to_xml`, `import_configuration_from_xml`, `import_project_from_file`, `delete_project`, `create_project`, `get_event_log`, `get_mcp_history` |
| **Errors & Problems** | Error reporting, validation, check-settings profiles, and workspace markers (bookmarks, tasks) | `get_problem_summary`, `get_project_errors`, `get_markers`, `apply_quick_fix`, `validate_xdto_package`, `project_checks` |
| **Code Intelligence** | Content assist, documentation, metadata and common-picture browsing, and references | `get_content_assist`, `get_platform_documentation`, `get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `get_subsystem_content`, `find_references`, `list_common_pictures`, `export_common_picture`, `search_in_dcs` |
| **Tags** | Metadata tag management | `get_tags`, `get_objects_by_tags` |
| **Applications & Testing** | Application and infobase management, external-object builds, launch, testing, background jobs, and Workmate | `edt_dialog`, `get_applications`, `infobase_sessions`, `infobase_extensions`, `standalone_server`, `list_configurations`, `create_launch_config`, `delete_launch_config`, `create_infobase`, `delete_infobase`, `update_database`, `launch`, `terminate_launch`, `run_yaxunit_tests`, `ask_workmate`, `get_job_status`, `cancel_job`, `build_external_objects`, `set_infobase_credentials`, `export_configuration_to_file` |
| **Debugging** | Breakpoints, stepping, variables, expression evaluation, and profiling | `set_breakpoint`, `set_error_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `wait_for_break`, `get_variables`, `set_variable`, `step`, `resume`, `debug_pause`, `evaluate_expression`, `debug_yaxunit_tests`, `debug_status`, `start_profiling`, `stop_profiling`, `get_profiling_results` |
| **BSL Code** | Module source reading/writing, structure, search, call hierarchy, navigation, and forms (`get_form_screenshot` is disabled by default) | `open_editor`, `read_module_source`, `write_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `read_method_source`, `get_method_call_hierarchy`, `get_outgoing_structures`, `go_to_definition`, `get_symbol_info`, `get_form_layout_snapshot`, `get_form_screenshot`, `get_template_screenshot`, `validate_query`, `validate_form_model` |
| **Refactoring** | Metadata and DCS create, inspect, rename, adopt, delete, and property management | `rename_metadata_object`, `delete_metadata`, `create_metadata`, `sort_metadata`, `modify_metadata`, `adopt_metadata_object`, `clone_metadata_object`, `dcs`, `business_process_route_map` |
| **Translation (LanguageTool)** | Translation strings generation, configuration synchronization, project info | `generate_translation_strings`, `translate_configuration`, `get_translation_project_info` |
| **Comparison** | Three-way configuration comparison: start one against two git revisions, expand a node, and read or author the merge-rules file | `compare_configurations`, `get_comparison_node`, `merge_rules` |
| **Git** | Git operations: the `git` command tool (disabled by default), branch listing/switching, and the branch-to-infobase binding | `git`, `list_git_branches`, `switch_git_branch`, `create_git_branch`, `set_branch_infobase` |

Enable or disable entire groups or individual tools from the **Tools** tab in **Window → Preferences → MCP Server**. Disabled tools are filtered out of `tools/list` responses. If a client calls a disabled tool directly through `tools/call`, the server returns a message explaining that the tool is disabled.

`open_editor` additionally requires **Allow UI navigation** in **MCP Server > General**
(off by default). It opens metadata objects, members and managed forms by `fqn`, or BSL modules
by `modulePath` with an optional `line` or `methodName`. Pass `projectName` and exactly one target.
Calls require an EDT workbench and wait at most 5 seconds for its UI thread; timed-out queued
requests are cancelled. See the [open_editor guide](../mcp/bundles/com.ditrix.edt.mcp.server/guides/open_editor.md).

## Presets

Quickly switch between common tool configurations using presets:

| Preset | Description |
|--------|-------------|
| **All Tools** | Explicitly enable every tool, including those disabled by default |
| **Analysis Only** | Read-only analysis — Core reads, comparison and git branch listing, Errors, Code Intelligence, Tags; `clean_project`, `resync_to_disk`, `create_project`, `delete_project`, git branch changes, infobase binding and `project_checks` are off; `revalidate_objects` and `enable_toolset` stay enabled |
| **Code Review** | Analysis + BSL code reading (excludes `write_module_source`) |
| **Development** | Full development without debugging tools |

Select a preset from the dropdown in the Tools tab. The dropdown shows the preset you last chose and keeps it when you tick or untick individual tools, so later preset-owed updates (for example a new mutating tool disabled in the read-only presets) still reach your edited profile. Choose "Custom", "Disable all" or "Restore Defaults" to opt out. Profiles saved by older versions are recognised once, on upgrade.

For scripted launches, set **`EDT_MCP_TOOL_PRESET=<preset id>`** on the EDT process before
startup: `all-tools`, `analysis-only`, `code-review`, or `development`. These predefined IDs are
case-insensitive; surrounding whitespace is ignored. The value is resolved once, on first use,
including in-process bridge calls and manual server starts when early
startup is disabled. A predefined preset replaces the effective tool selection **for this
session only**; stored Tools preferences are untouched by launch-preset resolution and registry
reads, including their migration state. An unset variable, a blank value or `custom` uses the
stored selection; explicit `custom` logs one INFO line explaining that there is no launch override.
An unknown ID is ignored and logged once at WARNING, listing only the predefined override IDs
and explaining that unset or `custom` uses the stored selection.

The Tools tab always displays and edits the saved selection, and shows a read-only note when
a launch preset is active. Opening the tab still runs normal stored-preference migrations;
unchanged OK/Apply preserves the saved selection and preset ID. Deliberate edits are saved for
a launch without the override, while the active session keeps its launch preset. Progressive
disclosure still narrows the enabled preset's tools; set `EDT_MCP_PROGRESSIVE_DISCLOSURE=false`
when checking its full `tools/list` catalogue. See
[the preset wire checks](../tests/TESTING.md#launch-preset-wire-checks) for the e2e launch recipe.

## Per-Tool Parameter Defaults

Some tools have configurable default values for parameters like result limits. These defaults are used when the AI client doesn't specify the parameter explicitly:

| Tool | Parameter | Default | Range |
|------|-----------|---------|-------|
| `get_project_errors` | `limit` | 100 | 1–1000 |
| `get_markers` | `limit` | 100 | 1–1000 |
| `get_metadata_objects` | `limit` | 100 | 1–1000 |
| `get_content_assist` | `limit` | 100 | 1–1000 |
| `search_in_code` | `limit` (`maxResults` deprecated) | 100 | 1–500 |
| `search_in_code` | `contextLines` | 2 | 0–5 |
| `read_module_source` | `maxLines` (preferences only) | 500 | 100–50000 |
| `terminate_launch` | `timeout` (seconds) | 10 | 1–120 |

Configure these in the Tools tab by selecting a tool that has configurable parameters — the parameter editors appear in the details panel below the tool tree.

## Progressive Tool Disclosure (dynamic toolsets)

For clients that work better with a small initial tool surface, the server can expose
only a **core** toolset up front and reveal the rest on demand — shrinking the
always-loaded `tools/list`. This is **off by default** (the full list is exposed,
unchanged); turn it on with the **Progressive disclosure** preference (or the
`EDT_MCP_PROGRESSIVE_DISCLOSURE=true` environment variable for headless/CI runs).

When on, only the `core` toolset (navigation, source read, metadata discovery, and the
two management tools) appears in `tools/list`. To use more tools:

1. Call **`list_toolsets`** to see the groups (`core`, `metadata`, `code`, `debug`,
   `testing`, `profiling`, `forms`, `tags`, `translation`, `project`, `comparison`, `git`) and their tools.
2. Call **`enable_toolset`** with `toolsets=[ids]` (e.g. `["code","debug"]`).
3. **Re-request `tools/list`** — the revealed tools now appear.

The server pushes `notifications/tools/list_changed` whenever that surface changes —
after `enable_toolset` reveals a toolset, and after the **Tools** tab enables or
disables tools — through an open SSE stream or an attached stdio connection. A client without one
must re-list after enabling (the `enable_toolset` response says so). A hidden tool is
only hidden from `tools/list`; it remains callable by name. These progressive-disclosure
toolsets are distinct from the **Tool Groups** above (which the Tools tab uses to
enable/disable tools persistently).

