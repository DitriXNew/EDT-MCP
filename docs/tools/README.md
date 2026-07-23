# EDT MCP Server — Tool Reference

One page per tool: what it does, every parameter, and how it works. Generated from the live server by `docs/generate_tool_docs.py` (re-run to refresh; the source of truth is each tool's Java).

**89 tools.**

## Core

> Always-on essentials: project/module navigation, source read, metadata discovery, and the toolset-management tools (list_toolsets / enable_toolset).

| Tool | Description |
|------|-------------|
| [`enable_toolset`](enable_toolset.md) | Reveal (or hide) tool groups for progressive disclosure. Pass toolsets=[ids] from list_toolsets to reveal them, then RE-REQUEST tools/list to see the newly r… |
| [`get_edt_version`](get_edt_version.md) | Returns the running 1C:EDT version as a plain version string. Returns "Unknown" when the version cannot be determined. |
| [`get_metadata_details`](get_metadata_details.md) | Get detailed properties of one or more 1C metadata objects (basic info by default, or every reflected section with 'full: true'). Use it after get_metadata_o… |
| [`get_metadata_objects`](get_metadata_objects.md) | Get a flat list of 1C configuration metadata objects (Name, Synonym, Comment, Type, ObjectModule, ManagerModule) as a Markdown table. Use it to discover what… |
| [`get_module_structure`](get_module_structure.md) | Get structure of a BSL module: all procedures/functions with signatures, line numbers, regions, execution context (&AtServer, &AtClient), export flag, and pa… |
| [`get_server_status`](get_server_status.md) | Self-diagnosis snapshot of the running MCP server: listening port, MCP protocol version, plugin version, EDT version, enabled/total tool counts, the plainTex… |
| [`get_tool_guide`](get_tool_guide.md) | Get the full on-demand how-to for a tool: its description, every parameter (type, required, allowed values) and extended examples/preconditions kept OUT of t… |
| [`list_modules`](list_modules.md) | List BSL modules in an EDT project as a table (module path, module type, parent type, parent name). Use it to discover module paths before reading or editing… |
| [`list_projects`](list_projects.md) | List all workspace projects with properties (name, path, type, natures) |
| [`list_toolsets`](list_toolsets.md) | List the tool groups (toolsets) used by progressive tool disclosure: each toolset's id, title, description, member tools, and whether it is currently visible… |
| [`read_module_source`](read_module_source.md) | Read BSL module source code from an EDT project, whole file or a line range. Returns YAML frontmatter (including a contentHash revision token to round-trip i… |
| [`search_in_code`](search_in_code.md) | Literal/regex full-text search across all BSL modules in a project. Matching is purely textual and NOT ru/en dialect-aware, so a query in one BSL language wo… |

## Metadata

> Metadata objects: discovery, create/modify/delete/rename/adopt, subsystems, configuration.

| Tool | Description |
|------|-------------|
| [`adopt_metadata_object`](adopt_metadata_object.md) | Adopt a base-configuration metadata object or member (object / form / attribute / tabular section / ...) into a configuration EXTENSION so the extension can… |
| [`create_launch_config`](create_launch_config.md) | Create a 1C:EDT runtime-client launch configuration (thin/thick/web). The SAME config works for both run and debug (mode is chosen at launch time by debug_la… |
| [`create_metadata`](create_metadata.md) | Create a metadata node addressed by a 1C full-name FQN: a top-level object (Catalog.Products) or a subordinate member (Catalog.Products.Attribute.Weight, Inf… |
| [`delete_launch_config`](delete_launch_config.md) | Delete a 1C:EDT launch configuration by name (runtime client or Attach). Destructive: guarded by a confirm-preview - call without confirm to preview (no chan… |
| [`delete_metadata`](delete_metadata.md) | Delete a metadata node (object or member, including a FORM object 'Type.Object.Form.Name', a FORM member - item / attribute / command / handler - or an XDTO… |
| [`export_common_picture`](export_common_picture.md) | Export a 1C CommonPicture (общая картинка) as PNG and list its picture variants (dpi, theme, interface variant, direction, template flag, glyph size). Resolv… |
| [`get_configuration_properties`](get_configuration_properties.md) | Get 1C:Enterprise configuration properties (name, synonym, comment, script variant, compatibility mode, etc.) |
| [`get_subsystem_content`](get_subsystem_content.md) | Get one 1C subsystem's content: properties, its metadata objects (Type/Name/Synonym/FQN) and child subsystems, identified by FQN (e.g. 'Subsystem.Sales.Subsy… |
| [`list_common_pictures`](list_common_pictures.md) | List a 1C configuration's CommonPicture objects and the variants each carries in its Picture.zip (DPI, theme, interface variant, template flag, glyph size, p… |
| [`list_configurations`](list_configurations.md) | List EDT launch configurations (runtime client + Attach + other 1C types) with their running state. This is the discovery step before debug_launch / run_yaxu… |
| [`list_subsystems`](list_subsystems.md) | List 1C subsystems of a configuration as a flat table (FQN, Synonym, Comment, InCommandInterface, content count, children count). Walks the whole tree by def… |
| [`modify_metadata`](modify_metadata.md) | Set properties of a metadata node (object or member, including a FORM member - item / attribute / command) addressed by a 1C full-name FQN, as properties=[{n… |
| [`rename_metadata_object`](rename_metadata_object.md) | Rename a metadata object or attribute, cascading the change across all references in BSL code, forms, and other metadata. Use the two-phase workflow: call wi… |

## Code

> BSL code: write/read methods, call hierarchy, go-to-definition, references, content assist, queries.

| Tool | Description |
|------|-------------|
| [`find_references`](find_references.md) | Find every place a metadata object is used: BSL code modules (with line numbers), other metadata, forms, roles, subsystems, etc. Pass the object FQN; the typ… |
| [`get_content_assist`](get_content_assist.md) | Get code-completion proposals at a 1-based line/column in a BSL module - the members, globals and variables valid at that caret (e.g. after a '.'). May retur… |
| [`get_method_call_hierarchy`](get_method_call_hierarchy.md) | Find a BSL method's call hierarchy: who calls it (callers, default) or what it calls (callees), via semantic AST analysis that resolves ru/en spellings (unli… |
| [`get_outgoing_structures`](get_outgoing_structures.md) | For each outgoing qualified call in a BSL module (or one method), report the top-level literal keys of the Structure passed as its first argument (local .Ins… |
| [`get_symbol_info`](get_symbol_info.md) | Get type/hover info about a symbol at a position in a BSL module. Returns inferred types, signatures, and documentation. |
| [`go_to_definition`](go_to_definition.md) | Go to the definition of a symbol (the inverse of find_references): a qualified method 'ModuleName.MethodName', a bare 'MethodName' (also pass modulePath), or… |
| [`read_method_source`](read_method_source.md) | Read a specific procedure/function from a BSL module by name. Returns source code with metadata. Lists available methods if not found. Use this for one metho… |
| [`validate_query`](validate_query.md) | Validate 1C:Enterprise query language (QL) text against a project, returning syntax and semantic errors with line numbers. Use to check a query before embedd… |
| [`write_module_source`](write_module_source.md) | Write BSL source code to a 1C metadata object module. Use to edit a module: searchReplace a fragment (default, needs oldSource), replace the whole file, or a… |

## Debug

> Runtime debugging: launch/attach, breakpoints, step/resume, variables, expression evaluation.

| Tool | Description |
|------|-------------|
| [`debug_launch`](debug_launch.md) | Start an EDT debug session: either an existing config by launchConfigurationName (runtime client OR Attach, the latter needed to debug server-side code), or… |
| [`debug_status`](debug_status.md) | Report active debug sessions: applicationId (real or synthetic 'attach:<name>' / 'launch:<name>'), launch configuration name/type, mode (debug/run), whether… |
| [`evaluate_expression`](evaluate_expression.md) | Evaluate a BSL expression in the context of a suspended stack frame. Pass frameRef from wait_for_break and the expression text. WARNING: this executes arbitr… |
| [`get_applications`](get_applications.md) | Get list of applications (infobases) for a project. Returns application ID, name, type, and update state. Application ID is required for update_database and… |
| [`get_variables`](get_variables.md) | Read variables from a stack frame of a suspended debug thread. Pass frameRef from wait_for_break (preferred) or threadId+frameIndex. Use expandPath to drill… |
| [`list_breakpoints`](list_breakpoints.md) | List active line breakpoints. Optionally filter by projectName. |
| [`remove_breakpoint`](remove_breakpoint.md) | Remove a 1C BSL line breakpoint. Either pass breakpointId (returned from set_breakpoint) or projectName+module+lineNumber to look it up by coordinates. |
| [`resume`](resume.md) | Resume a suspended debug thread or all threads of a debug target. Pass threadId (from wait_for_break) or applicationId. applicationId accepts ANY id form for… |
| [`set_breakpoint`](set_breakpoint.md) | Set a line breakpoint on a 1C BSL module. Accepts either an EDT module-relative path (e.g. 'CommonModules/Foo/Module.bsl') or an absolute filesystem path. Us… |
| [`set_variable`](set_variable.md) | Set a BSL variable's value in a suspended debug frame. WRITE/side-effect: EXECUTES the entered value as a BSL literal/expression live in the running 1C appli… |
| [`step`](step.md) | Step a suspended debug thread. kind ∈ {over, into, out}. Blocks until the next SUSPEND event (or timeout) and returns the new frame snapshot. |
| [`terminate_launch`](terminate_launch.md) | Terminate one or more 1C launches started from THIS EDT instance; externally launched 1C clients are never touched. Select ONE target mode: launchConfigurati… |
| [`wait_for_break`](wait_for_break.md) | Wait for a debug suspend event (e.g. breakpoint hit) on the given application. Returns the suspended thread/frame snapshot, or {hit:false} on timeout. applic… |

## Testing

> YAXUnit unit testing: run and debug test suites.

| Tool | Description |
|------|-------------|
| [`debug_yaxunit_tests`](debug_yaxunit_tests.md) | Deprecated alias for run_yaxunit_tests with debug=true. Launches YAXUnit tests in DEBUG mode so breakpoints fire, then call wait_for_break to inspect. Prefer… |
| [`run_yaxunit_tests`](run_yaxunit_tests.md) | Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. Polls for up to `timeout` seconds, then returns the report or **Pending** (… |

## Profiling

> Performance profiling: start/stop a measurement and read the results.

| Tool | Description |
|------|-------------|
| [`get_profiling_results`](get_profiling_results.md) | Get profiling (performance measurement) results after a debug session: per-module, per-line call count, timing and percentage. Returns only the MOST RECENT m… |
| [`start_profiling`](start_profiling.md) | Start performance measurement on the active debug target. Enables line-level profiling: call counts and timing for every executed BSL line. Start-only and id… |
| [`stop_profiling`](stop_profiling.md) | Stop performance measurement on the active debug target. Counterpart to start_profiling: deterministically switches profiling off. Idempotent: if profiling i… |

## Forms

> Form and template rendering: form layout snapshot, form screenshot, template screenshot.

| Tool | Description |
|------|-------------|
| [`get_form_layout_snapshot`](get_form_layout_snapshot.md) | Return a YAML snapshot of a form's calculated WYSIWYG layout (bounds, element types, display properties) as text; use it to inspect or compare what a form ac… |
| [`get_form_screenshot`](get_form_screenshot.md) | Capture a PNG screenshot of a form's WYSIWYG editor; pass formPath to open the form automatically or omit it to shoot the active editor. Requires EDT launche… |
| [`get_template_screenshot`](get_template_screenshot.md) | Capture a PNG screenshot of a 1C template (a SpreadsheetDocument print form) as EDT renders it, so its layout and text are visible to an AI. Works for a comm… |

## Tags

> Tag-based organization: list tags and find objects by tag.

| Tool | Description |
|------|-------------|
| [`get_objects_by_tags`](get_objects_by_tags.md) | Get metadata objects filtered by tags. Returns objects that have any of the specified tags, including tag descriptions and object FQNs. |
| [`get_tags`](get_tags.md) | Get list of all tags defined in the project. Tags are user-defined labels for organizing metadata objects. Returns tag name, color, description, and number o… |

## Translation

> Configuration translation via LanguageTool: extract, translate, project info.

| Tool | Description |
|------|-------------|
| [`generate_translation_strings`](generate_translation_strings.md) | Generate translation strings (.lstr/.trans/.dict) for a configuration project: scans translatable features and writes the resulting keys into the project's s… |
| [`get_translation_project_info`](get_translation_project_info.md) | Return LanguageTool metadata for a project: the translation storages declared on it and the available translation provider IDs. Use it to check whether a dic… |
| [`translate_configuration`](translate_configuration.md) | Run EDT 'Translate configuration' on a configuration project - reads the dictionaries from the storages bound to it (external dictionary storage projects wit… |

## Project

> Project operations: clean/revalidate, update DB, export/import XML, problems and markers, docs.

| Tool | Description |
|------|-------------|
| [`build_external_objects`](build_external_objects.md) | Build (compile to disk) the external data processors/reports of an EDT external-object project to .epf/.erf files. Build ONE object with objectName, or ALL o… |
| [`clean_project`](clean_project.md) | Clean EDT project and trigger full revalidation. Direction: DISK -> MODEL - re-imports the on-disk src/ .mdo files into the in-memory model. Refreshes files… |
| [`commit_git_changes`](commit_git_changes.md) | Commit a project's ON-DISK git changes: stage all tracked-modified files (all=true) and/or explicit paths[], then record a commit and return its SHA. Commits… |
| [`create_git_branch`](create_git_branch.md) | Create a new local git branch, optionally check it out, and optionally attach an EXISTING infobase (application, from get_applications) to the new branch's c… |
| [`create_git_repository`](create_git_repository.md) | Bootstrap a git repository for an EDT project. Without 'url': git init at an EXISTING open project's location and connect it to EGit (rejected if the project… |
| [`create_infobase`](create_infobase.md) | Create a new FILE infobase (1C database) OR register an existing one, and bind it to a configuration project so it appears in get_applications. mode='create'… |
| [`create_project`](create_project.md) | Create a NEW 1C project in the EDT workspace. projectKind selects the kind: 'configuration' (standalone), 'extension' (bound to a base configuration), or 'ex… |
| [`delete_infobase`](delete_infobase.md) | Remove a FILE infobase association from a configuration project OR delete a standalone (autonomous) server application. Destructive: guarded by a confirm-pre… |
| [`delete_project`](delete_project.md) | Remove an EDT project from the workspace, optionally deleting its files from disk (deleteContent). Destructive: guarded by a confirm-preview - call without c… |
| [`export_configuration_to_xml`](export_configuration_to_xml.md) | Export an EDT configuration project to XML files (EDT menu: Export -> Configuration to XML Files). Equivalent of 1C platform DumpConfigToFiles. |
| [`get_check_description`](get_check_description.md) | Get detailed description of an EDT check by its ID. Returns markdown content with check explanation, examples, and how to fix. Accepts the symbolic check id… |
| [`get_event_log`](get_event_log.md) | Read a 1C infobase event log WITHOUT a running 1C session by parsing the raw log files (legacy text ver 2.0: a 1Cv8.lgf dictionary + dated *.lgp partitions).… |
| [`get_git_status`](get_git_status.md) | Report a project's git working-tree status: the current branch (detached HEAD flagged), whether the tree is clean, and the porcelain change sets (added/chang… |
| [`get_markers`](get_markers.md) | List workspace markers: bookmarks and/or task markers (TODO, FIXME, XXX, HACK). Filter by markerKind (bookmark \| task; omit to list both), projectName, fileP… |
| [`get_mcp_history`](get_mcp_history.md) | Return the recorded MCP call history (this server's in-memory ring of request/response exchanges) so you can introspect your OWN traffic: which tools you cal… |
| [`get_platform_documentation`](get_platform_documentation.md) | Look up 1C:Enterprise platform documentation for built-in types (ValueTable, Array, Structure) and global built-in functions, including their methods, proper… |
| [`get_problem_summary`](get_problem_summary.md) | Get problem summary with counts grouped by project and EDT severity level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL). Use this for severity totals on… |
| [`get_project_errors`](get_project_errors.md) | List EDT configuration problems (validation markers) with optional project / severity / check-id / object filters. Each row carries the check code, message,… |
| [`import_configuration_from_xml`](import_configuration_from_xml.md) | Import a configuration from a directory of XML files into a NEW EDT project (EDT menu: Import); the reverse of export_configuration_to_xml. The projectName m… |
| [`list_git_branches`](list_git_branches.md) | List a project's git branches: local and remote-tracking, with the CURRENT branch marked (detached HEAD flagged), plus the 1C application/infobase each branc… |
| [`pull_git_branch`](pull_git_branch.md) | Fetch a branch from a remote and integrate it into the project's current branch (merge, or rebase when requested) - the non-UI 'Pull'. remote and branch are… |
| [`push_git_branch`](push_git_branch.md) | Push a project's git branch (or an explicit refspec) to a remote (headless JGit push). remote AND refspec are BOTH required with no defaulting (the no-autono… |
| [`resync_to_disk`](resync_to_disk.md) | Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files and report BM-to-disk desync. Direction: MODEL -> DISK (writes the model out to src… |
| [`revalidate_objects`](revalidate_objects.md) | Revalidate EDT project or specific objects. If objects array is empty or missing, revalidates entire project. FQN examples: 'Document.SalesOrder', 'Catalog.P… |
| [`set_branch_infobase`](set_branch_infobase.md) | Attach or detach an EXISTING infobase (application) to/from a specific git branch context, so switch_git_branch's automatic binding follows that branch. Targ… |
| [`set_infobase_credentials`](set_infobase_credentials.md) | Store infobase connection credentials (user/password) so update_database and debug_launch can authenticate the update agent on an infobase that has a user li… |
| [`switch_git_branch`](switch_git_branch.md) | Switch a project's git repository to another branch (headless EGit checkout). branch may be a short local name (e.g. 'feature/x') or a full ref ('refs/heads/… |
| [`update_database`](update_database.md) | Apply configuration changes to an application's database (infobase), full or incremental. Target by launchConfigurationName (preferred) or projectName + appl… |
| [`validate_xdto_package`](validate_xdto_package.md) | Validate a single XDTO package by running EDT's OWN configuration validation (the same check engine behind get_project_errors) scoped to that package, and re… |
