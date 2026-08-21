# Business-project skill capability matrix

This matrix records only tools named by the shipped skills. The registered
implementation, current MCP help/schema, and [`docs/tools/`](../docs/tools/)
remain authoritative. The validator enforces agreement among them.

| Skill | Named tools |
|---|---|
| `edt-mcp-project-session` | `get_edt_version`, `list_projects`, `get_configuration_properties`, `get_problem_summary`, `list_subsystems`, `router_status`, `get_server_status`, `list_toolsets`, `enable_toolset`, `get_tool_guide` |
| `edt-mcp-project-code-research` | `get_metadata_objects`, `list_modules`, `search_in_code`, `get_module_structure`, `read_method_source`, `read_module_source`, `go_to_definition`, `find_references`, `get_method_call_hierarchy`, `get_outgoing_structures`, `get_symbol_info`, `get_platform_documentation` |
| `edt-mcp-project-local-fix` | `read_method_source`, `validate_query`, `get_tool_guide`, `write_module_source`, `revalidate_objects`, `get_project_errors` |
| `edt-mcp-project-metadata` | `get_metadata_details`, `find_references`, `create_metadata`, `modify_metadata`, `rename_metadata_object`, `delete_metadata`, `adopt_metadata_object` |
| `edt-mcp-project-forms` | `get_metadata_details`, `get_module_structure`, `read_method_source`, `create_metadata`, `modify_metadata`, `delete_metadata`, `validate_query`, `get_form_layout_snapshot`, `get_form_screenshot` |
| `edt-mcp-project-query-dcs` | `search_in_code`, `get_module_structure`, `read_method_source`, `get_metadata_details`, `validate_query`, `modify_metadata`, `get_project_errors` |
| `edt-mcp-project-external-objects` | `list_projects`, `get_applications`, `list_configurations`, `build_external_objects`, `set_infobase_credentials`, `set_breakpoint`, `debug_launch`, `debug_status`, `wait_for_break`, `get_variables`, `resume`, `remove_breakpoint`, `terminate_launch` |
| `edt-mcp-project-runtime-debug` | `get_event_log`, `get_applications`, `list_configurations`, `debug_status`, `list_breakpoints`, `set_infobase_credentials`, `set_breakpoint`, `debug_launch`, `wait_for_break`, `get_variables`, `evaluate_expression`, `set_variable`, `step`, `resume`, `remove_breakpoint`, `terminate_launch` |
| `edt-mcp-project-yaxunit` | `list_configurations`, `get_applications`, `run_yaxunit_tests`, `set_infobase_credentials`, `get_job_status`, `set_breakpoint`, `wait_for_break`, `debug_status`, `resume`, `remove_breakpoint`, `terminate_launch`, `cancel_job` |
| `edt-mcp-project-maintenance` | `get_project_errors`, `get_problem_summary`, `revalidate_objects`, `apply_quick_fix`, `clean_project`, `list_projects`, `resync_to_disk`, `get_applications`, `list_configurations`, `update_database`, `get_job_status`, `cancel_job`, `import_configuration_from_xml`, `export_configuration_to_xml`, `list_git_branches`, `create_git_branch`, `switch_git_branch` |
| `edt-mcp-project-profiling` | `debug_status`, `get_profiling_results`, `start_profiling`, `stop_profiling` |
| `edt-mcp-project-workmate` | `get_tool_guide`, `ask_workmate`, `get_job_status` |
| `edt-mcp-project-translation` | `list_projects`, `get_configuration_properties`, `get_translation_project_info`, `generate_translation_strings`, `translate_configuration`, `get_metadata_details` |
