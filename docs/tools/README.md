# EDT MCP Server — Tool Reference

One page per tool: what it does, every parameter, and how it works. Generated from the live server by `docs/generate_tool_docs.py` (re-run to refresh; the source of truth is each tool's Java).

**90 tools.**

## Core

> Always-on essentials: project/module navigation, source read, metadata discovery, and the toolset-management tools (list_toolsets / enable_toolset).

| Tool | Description |
|------|-------------|
| [`enable_toolset`](enable_toolset.md) | Make additional MCP tool groups visible or hide them. Parameters and examples: get_tool_guide('enable_toolset'). |
| [`get_edt_version`](get_edt_version.md) | Identify the installed 1C:EDT version. Parameters and examples: get_tool_guide('get_edt_version'). |
| [`get_metadata_details`](get_metadata_details.md) | Inspect the properties and structure of a metadata object or member. Parameters and examples: get_tool_guide('get_metadata_details'). |
| [`get_metadata_objects`](get_metadata_objects.md) | Discover metadata objects available in a 1C configuration. Parameters and examples: get_tool_guide('get_metadata_objects'). |
| [`get_module_structure`](get_module_structure.md) | Discover procedures, functions, regions, and execution contexts in a BSL module. Parameters and examples: get_tool_guide('get_module_structure'). |
| [`get_server_status`](get_server_status.md) | Diagnose the EDT MCP server and its feature configuration. Parameters and examples: get_tool_guide('get_server_status'). |
| [`get_tool_guide`](get_tool_guide.md) | Retrieve detailed instructions for an MCP tool. Parameters and examples: get_tool_guide('get_tool_guide'). |
| [`list_modules`](list_modules.md) | Discover BSL modules available in an EDT project. Parameters and examples: get_tool_guide('list_modules'). |
| [`list_projects`](list_projects.md) | Discover projects available in the EDT workspace. Parameters and examples: get_tool_guide('list_projects'). |
| [`list_toolsets`](list_toolsets.md) | Discover available groups of MCP tools and their visibility. Parameters and examples: get_tool_guide('list_toolsets'). |
| [`read_module_source`](read_module_source.md) | Inspect the source of a complete BSL module. Parameters and examples: get_tool_guide('read_module_source'). |
| [`search_in_code`](search_in_code.md) | Literal/regex full-text search across BSL modules. Matching is textual and NOT ru/en dialect-aware, so a query in one BSL language will not find the other sp… |

## Metadata

> Metadata objects: discovery, create/modify/delete/rename/adopt, subsystems, configuration.

| Tool | Description |
|------|-------------|
| [`adopt_metadata_object`](adopt_metadata_object.md) | Add a base-configuration object or member to an extension for customization. Parameters and examples: get_tool_guide('adopt_metadata_object'). |
| [`create_launch_config`](create_launch_config.md) | Configure an EDT runtime client for launching a 1C application. Parameters and examples: get_tool_guide('create_launch_config'). |
| [`create_metadata`](create_metadata.md) | Add a new metadata object or member to a configuration. Parameters and examples: get_tool_guide('create_metadata'). |
| [`delete_launch_config`](delete_launch_config.md) | Remove an unused EDT runtime or attach launch configuration. Two-phase: call once WITHOUT confirm to preview, then again with confirm=true to apply. Paramete… |
| [`delete_metadata`](delete_metadata.md) | Delete a metadata object or member (FQN-addressed). DESTRUCTIVE and CASCADING: on the md-refactoring path EDT cleans the REFERENCES to the deleted object acr… |
| [`export_common_picture`](export_common_picture.md) | Inspect or extract the image data of a 1C common picture. Parameters and examples: get_tool_guide('export_common_picture'). |
| [`get_configuration_properties`](get_configuration_properties.md) | Inspect the identity and compatibility settings of a 1C configuration. Parameters and examples: get_tool_guide('get_configuration_properties'). |
| [`get_subsystem_content`](get_subsystem_content.md) | Inspect which metadata objects and child subsystems belong to a 1C subsystem. Parameters and examples: get_tool_guide('get_subsystem_content'). |
| [`list_common_pictures`](list_common_pictures.md) | Inventory common pictures available in a 1C configuration. Parameters and examples: get_tool_guide('list_common_pictures'). |
| [`list_configurations`](list_configurations.md) | Discover EDT runtime and server-side launch configurations. Parameters and examples: get_tool_guide('list_configurations'). |
| [`list_subsystems`](list_subsystems.md) | Discover the subsystem hierarchy of a 1C configuration. Parameters and examples: get_tool_guide('list_subsystems'). |
| [`modify_metadata`](modify_metadata.md) | Set properties of any metadata node (object or member, including form items, attributes, commands, and handlers). Parameters and examples: get_tool_guide('mo… |
| [`rename_metadata_object`](rename_metadata_object.md) | Rename a metadata object or member and rewrite the references EDT RESOLVES for it. CASCADES ACROSS THE WHOLE CONFIGURATION - BSL, forms, roles, subsystems -… |

## Code

> BSL code: write/read methods, call hierarchy, go-to-definition, references, content assist, queries.

| Tool | Description |
|------|-------------|
| [`find_references`](find_references.md) | Discover where a metadata object is used throughout the configuration and BSL code. Parameters and examples: get_tool_guide('find_references'). |
| [`get_content_assist`](get_content_assist.md) | Find valid BSL completion suggestions at a source-code position. Parameters and examples: get_tool_guide('get_content_assist'). |
| [`get_method_call_hierarchy`](get_method_call_hierarchy.md) | Trace which BSL methods call a method or are called by it; optional depth walks the chain transitively for impact analysis (callers only, max 5). Finds STATI… |
| [`get_outgoing_structures`](get_outgoing_structures.md) | Discover the fields passed to outgoing or qualified BSL method calls. BEST-EFFORT and incomplete by design: only top-level LITERAL keys of the first argument… |
| [`get_symbol_info`](get_symbol_info.md) | Inspect the type and documentation of a BSL symbol at its source location. Parameters and examples: get_tool_guide('get_symbol_info'). |
| [`go_to_definition`](go_to_definition.md) | Locate the source definition of a BSL symbol or metadata object. Parameters and examples: get_tool_guide('go_to_definition'). |
| [`read_method_source`](read_method_source.md) | Inspect the source of one BSL procedure or function. Parameters and examples: get_tool_guide('read_method_source'). |
| [`validate_query`](validate_query.md) | Check a 1C query for syntax and metadata-reference errors. Parameters and examples: get_tool_guide('validate_query'). |
| [`write_module_source`](write_module_source.md) | Create or edit BSL source in a metadata module. Parameters and examples: get_tool_guide('write_module_source'). |

## Debug

> Runtime debugging: launch/attach, breakpoints, step/resume, variables, expression evaluation.

| Tool | Description |
|------|-------------|
| [`debug_launch`](debug_launch.md) | Run a 1C application under EDT debugging. An already-running session is NOT relaunched - the call short-circuits with alreadyRunning:true; restartIfRunning=t… |
| [`debug_status`](debug_status.md) | Check which EDT debug sessions are running or paused. Parameters and examples: get_tool_guide('debug_status'). |
| [`evaluate_expression`](evaluate_expression.md) | Evaluate a BSL expression in a paused debug frame and return the value. WARNING: this executes arbitrary code in the running application - it can change stat… |
| [`get_applications`](get_applications.md) | Discover infobases connected to an EDT project. Parameters and examples: get_tool_guide('get_applications'). |
| [`get_variables`](get_variables.md) | Inspect runtime variables in a paused debug frame. Parameters and examples: get_tool_guide('get_variables'). |
| [`list_breakpoints`](list_breakpoints.md) | Review breakpoints currently set in BSL source code. Parameters and examples: get_tool_guide('list_breakpoints'). |
| [`remove_breakpoint`](remove_breakpoint.md) | Stop pausing execution at a BSL source breakpoint. Address it EITHER by breakpointId OR by modulePath together with lineNumber - every field is optional on i… |
| [`resume`](resume.md) | Continue a paused 1C debug session. Parameters and examples: get_tool_guide('resume'). |
| [`set_breakpoint`](set_breakpoint.md) | Pause BSL execution at a selected source line during debugging. Parameters and examples: get_tool_guide('set_breakpoint'). |
| [`set_variable`](set_variable.md) | Change a variable while execution is paused in the debugger. WARNING: the value is EVALUATED as a BSL expression in the running application, so it can invoke… |
| [`step`](step.md) | Advance paused debug execution one step. Parameters and examples: get_tool_guide('step'). |
| [`terminate_launch`](terminate_launch.md) | Stop 1C sessions started by EDT. NOT two-phase for a single launch: selecting one (launchConfigurationName, or projectName + applicationId) stops it IMMEDIAT… |
| [`wait_for_break`](wait_for_break.md) | Wait until a running 1C debug session reaches a breakpoint or other suspend event. Parameters and examples: get_tool_guide('wait_for_break'). |

## Testing

> YAXUnit unit testing, 1C:Workmate assistance, and shared background-job polling.

| Tool | Description |
|------|-------------|
| [`ask_workmate`](ask_workmate.md) | Start a background question to the 1C:Workmate plugin and return its jobId. Hands the question to an EXTERNAL agent: by default (shareMcpTools) Workmate may… *(not enabled by default)* |
| [`cancel_job`](cancel_job.md) | Cancel a background job by jobId. DESTRUCTIVE. Two-phase: call once WITHOUT confirm to see the owning tool, state and progress, then again with confirm=true… |
| [`debug_yaxunit_tests`](debug_yaxunit_tests.md) | DEPRECATED alias of run_yaxunit_tests(debug=true) - prefer that instead; the implementation is shared. DEBUG mode, so breakpoints fire: a short start returns… |
| [`get_job_status`](get_job_status.md) | Poll any background job by the jobId its owning tool returned: state, progress journal and terminal result. Parameters and examples: get_tool_guide('get_job_… |
| [`run_yaxunit_tests`](run_yaxunit_tests.md) | Run YAXUnit tests as a named background job and return a JUnit Markdown report. The start call waits up to `timeout` (default and maximum 45s): a short run r… |

## Profiling

> Performance profiling: start/stop a measurement and read the results.

| Tool | Description |
|------|-------------|
| [`get_profiling_results`](get_profiling_results.md) | Identify performance hotspots in executed BSL code. Returns the MOST RECENT measurement session GLOBALLY - applicationId only changes the reported active-sta… |
| [`start_profiling`](start_profiling.md) | Measure execution time and coverage of BSL code in a debug session. Parameters and examples: get_tool_guide('start_profiling'). |
| [`stop_profiling`](stop_profiling.md) | Finish measuring BSL performance in a debug session. Parameters and examples: get_tool_guide('stop_profiling'). |

## Forms

> Form and template rendering: form layout snapshot, form screenshot, template screenshot.

| Tool | Description |
|------|-------------|
| [`get_form_layout_snapshot`](get_form_layout_snapshot.md) | Inspect the calculated visual layout of an EDT form. Requires EDT launched with -DnativeFormBufferedLayoutRender=true: without the flag the layout comes back… |
| [`get_form_screenshot`](get_form_screenshot.md) | Visually inspect an EDT form as rendered by the designer. Requires EDT launched with -DnativeFormBufferedLayoutRender=true: without the flag the image comes… |
| [`get_template_screenshot`](get_template_screenshot.md) | Visually inspect how a spreadsheet print template renders. Parameters and examples: get_tool_guide('get_template_screenshot'). |

## Tags

> Tag-based organization: list tags and find objects by tag.

| Tool | Description |
|------|-------------|
| [`get_objects_by_tags`](get_objects_by_tags.md) | Find metadata objects organized under selected tags. Parameters and examples: get_tool_guide('get_objects_by_tags'). |
| [`get_tags`](get_tags.md) | Discover user-defined tags used to organize project metadata. Parameters and examples: get_tool_guide('get_tags'). |

## Translation

> Configuration translation via LanguageTool: extract, translate, project info.

| Tool | Description |
|------|-------------|
| [`generate_translation_strings`](generate_translation_strings.md) | Collect translatable strings of a configuration and WRITE the generated keys into the project's translation storage (.lstr/.trans/.dict; storageId, default '… |
| [`get_translation_project_info`](get_translation_project_info.md) | Inspect the translation setup of an EDT project. Parameters and examples: get_tool_guide('get_translation_project_info'). |
| [`translate_configuration`](translate_configuration.md) | SYNCHRONIZE a 1C configuration's translated artifacts with the target languages. Does NOT translate anything itself: it regenerates the artifacts from transl… |

## Project

> Project operations: clean/revalidate, update DB, export/import XML, problems and markers, docs.

| Tool | Description |
|------|-------------|
| [`apply_quick_fix`](apply_quick_fix.md) | Apply an EDT quick fix to a validation problem. Parameters and examples: get_tool_guide('apply_quick_fix'). |
| [`build_external_objects`](build_external_objects.md) | Compile external 1C data processors and reports into deployable files. NOT self-contained: the project needs an associated infobase AND a resolvable 1C runti… |
| [`clean_project`](clean_project.md) | Rebuild an EDT project from the on-disk src/ files and revalidate everything. Direction DISK -> MODEL; slow, and it DISCARDS unsaved in-memory model edits -… |
| [`code_review`](code_review.md) | Review BSL code quality with the BSL Language Server engine: its FULL diagnostic catalog (magic number, cyclomatic/cognitive complexity, method/line length,… |
| [`create_git_branch`](create_git_branch.md) | Start isolated work on a new Git branch for an EDT project. Parameters and examples: get_tool_guide('create_git_branch'). |
| [`create_infobase`](create_infobase.md) | Prepare an infobase for an EDT project by creating a database or registering an existing one. Passing user/password/access STORES those credentials in EDT's… |
| [`create_project`](create_project.md) | Start a new EDT configuration, extension, or external-objects project. Parameters and examples: get_tool_guide('create_project'). |
| [`delete_infobase`](delete_infobase.md) | Remove a project's infobase or its standalone-server registration, optionally deleting the database files. DESTRUCTIVE and IRREVERSIBLE. Two-phase: call once… |
| [`delete_project`](delete_project.md) | Remove an EDT project from the workspace, optionally with its sources on disk. DESTRUCTIVE and IRREVERSIBLE. Two-phase: call once WITHOUT confirm to preview,… |
| [`export_configuration_to_xml`](export_configuration_to_xml.md) | Export an EDT configuration into 1C XML files. Parameters and examples: get_tool_guide('export_configuration_to_xml'). |
| [`get_check_description`](get_check_description.md) | Understand an EDT validation rule and how to fix its diagnostic. Parameters and examples: get_tool_guide('get_check_description'). |
| [`get_event_log`](get_event_log.md) | Investigate infobase activity and errors through its event log. Reads the LEGACY text format only (ver 2.0: 1Cv8.lgf + *.lgp); an infobase on the modern SQLi… |
| [`get_markers`](get_markers.md) | Find bookmarks and TODO-style task markers in the workspace. Parameters and examples: get_tool_guide('get_markers'). |
| [`get_mcp_history`](get_mcp_history.md) | Diagnose MCP tool calls by reviewing recent requests, failures, timings, and context usage. Parameters and examples: get_tool_guide('get_mcp_history'). |
| [`get_platform_documentation`](get_platform_documentation.md) | Look up a built-in 1C type or global function in the platform documentation. Returns headers and member names by default - pass responseFormat='detailed' for… |
| [`get_problem_summary`](get_problem_summary.md) | See validation problem counts grouped by project and severity. Parameters and examples: get_tool_guide('get_problem_summary'). |
| [`get_project_errors`](get_project_errors.md) | Find detailed validation errors and warnings in an EDT project. Parameters and examples: get_tool_guide('get_project_errors'). |
| [`import_configuration_from_xml`](import_configuration_from_xml.md) | Create an EDT project from exported 1C configuration XML files. Parameters and examples: get_tool_guide('import_configuration_from_xml'). |
| [`list_git_branches`](list_git_branches.md) | Inspect available Git branches and their EDT infobase bindings. Parameters and examples: get_tool_guide('list_git_branches'). |
| [`resync_to_disk`](resync_to_disk.md) | Write the in-memory model back out to the on-disk src/ .mdo files and report model-to-disk desync; fixes 'object file does not exist' failures and dangling C… |
| [`revalidate_objects`](revalidate_objects.md) | Revalidate a project or a named list of objects, picking up .mdo edits made outside EDT. Targeted, lightweight alternative to clean_project - no full rebuild… |
| [`set_branch_infobase`](set_branch_infobase.md) | Associate an existing infobase with a Git branch of an EDT project. Parameters and examples: get_tool_guide('set_branch_infobase'). |
| [`set_infobase_credentials`](set_infobase_credentials.md) | STORE infobase credentials (user/password) in EDT settings so update_database and debug_launch can authenticate. The secret PERSISTS beyond this call, and ad… |
| [`switch_git_branch`](switch_git_branch.md) | Change the active version of an EDT project through Git branch checkout. Parameters and examples: get_tool_guide('switch_git_branch'). |
| [`update_database`](update_database.md) | Apply the current EDT configuration to an infobase. DESTRUCTIVE - restructures data and can evict live sessions. Two-phase: call once WITHOUT confirm to prev… |
| [`validate_xdto_package`](validate_xdto_package.md) | Check an XDTO package for configuration validation problems. Reads the markers EDT computed EARLIER - it does not validate on demand, so a verdict right afte… |

## Git

> Run raw git commands (status/diff/commit/push/pull/...) in a project's repository via the 'git' tool. Powerful (it can push, checkout, stash); DISABLED by default - check it in the MCP Server Tools preference tab to enable.

| Tool | Description |
|------|-------------|
| [`git`](git.md) | Run a git command in a project's repository through the real git CLI, sent as a shell-style string. Only a whitelisted set of subcommands runs, and the write… |
