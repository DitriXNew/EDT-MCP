# Map of EDT-MCP MCP-server tools

> The source of truth is the "Available Tools" section in the EDT-MCP repository `README.md`. If it differs from this file — trust the README.
>
> Total tools: **57**, organised into 9 groups.

## Tool name prefixes across clients

MCP clients name tools differently. Determine the actual name in your client from the `tools/list` response — do not guess:

- Claude Code: `mcp__EDT-MCP-Server__list_projects`
- Cursor: `mcp_EDT-MCP-Server_list_projects`
- Others: see client documentation

This document uses **short names**: `list_projects`.

## Tool-selection algorithm (read first as an AI)

This section matters more than any table below. Use it to pick the right tool, then go to the group for parameter details.

| User's request | Starting tool | Why |
|---|---|---|
| "What is this project?" | `list_projects` -> `get_configuration_properties` | Basic onboarding |
| "What errors are in the project?" | `get_problem_summary` **first**, then `get_project_errors` with filters | Summary is cheap, details are expensive |
| "Where is X implemented / used?" | metadata object -> `find_references`; method -> `get_method_call_hierarchy`; arbitrary text -> `search_in_code` with `outputMode: count` first | Semantic search is more precise than grep |
| "Read method X of module Y" | `read_method_source` | **Never** load the whole module for a single method |
| "Show me the whole module" | first `get_module_structure` (the map), then `read_module_source` only if you really need the whole file | Token economy |
| "Modify code in a method" | `read_method_source` -> `write_module_source` with `mode: searchReplace` | See `edt-mcp-write-safety.md` |
| "Rename a metadata object" | `rename_metadata_object` **without** `confirm` (preview) -> the same call with `confirm: true` | Manual XML edits break references cascadingly |
| "Delete an object / member" | `delete_metadata` (by FQN) **without** `confirm` (preview) -> the same call with `confirm: true` | Manual XML edits break references cascadingly |
| "Create a new object" | `create_metadata` with a top-level FQN (`Catalog.Products`) | EDT default content + correct UUID; do not hand-build the `.mdo` |
| "Add an attribute / tabular section / dimension / resource" | `create_metadata` with a member FQN (`Catalog.Products.Attribute.Weight`) | Do not edit `.mdo` by hand |
| "Set a property / type of an object or member" | `modify_metadata` (discover what is settable via `get_metadata_details(assignable: true)`) | Structured `type` spec; do not edit `.mdo` by hand |
| "Validate a 1C query" | `validate_query` (DCS query -> `dcsMode: true`) | Before pasting a query text into code |
| "What's in this form?" | `get_form_layout_snapshot` with `mode: compact` (YAML); `get_form_screenshot` for visuals | YAML is cheaper than PNG |
| "What does the platform expose for type X?" | `get_platform_documentation` | Do not guess signatures |
| "Run / debug / update an infobase" | `list_configurations` -> `debug_launch` / `update_database` | First learn the launch configuration name |
| "Run tests" | `run_yaxunit_tests`; to debug failing ones — `debug_yaxunit_tests` + `set_breakpoint` | |
| "What does check Z mean?" | `get_check_description` | |

If a tool returns `tool is disabled` — the current preset (see below) hides it. **Do not try to bypass**; tell the user and suggest switching the preset.

## Access profiles (presets)

In plugin settings (`Window -> Preferences -> MCP Server -> Tools`) the user selects a preset. Exact sets are defined in `ToolPreset.java`:

| Preset | What is disabled |
|---|---|
| **All Tools** | Nothing (all 57 tools) |
| **Analysis Only** | Groups Applications & Testing, Debugging, BSL Code, Refactoring, Translation + `export_configuration_to_xml` + `import_configuration_from_xml`. Available: Core/Project (except export/import), Errors & Problems, Code Intelligence, Tags |
| **Code Review** | Same as Analysis Only, **plus** all BSL Code tools become available **except** `write_module_source`. So available: `read_method_source`, `read_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `get_method_call_hierarchy`, `go_to_definition`, `get_symbol_info`, `get_form_layout_snapshot`, `get_form_screenshot`, `validate_query` |
| **Development** | Only the Debugging group (including `debug_yaxunit_tests`, `start_profiling`, `get_profiling_results`). Refactoring, Translation, BSL Code, Applications are all available |

## Configurable parameter defaults

In the settings UI some tools have configurable limit defaults (applied when the client does not pass the parameter):

| Tool | Parameter | Default | Range |
|---|---|---|---|
| `get_project_errors` | `limit` | 100 | 1-1000 |
| `get_bookmarks` | `limit` | 100 | 1-1000 |
| `get_tasks` | `limit` | 100 | 1-1000 |
| `get_metadata_objects` | `limit` | 100 | 1-1000 |
| `get_content_assist` | (limit) | 100 | 1-1000 |
| `search_in_code` | `maxResults` | 100 | 1-500 |
| `search_in_code` | `contextLines` | 2 | 0-5 |

## The 9 tool groups

### 1. Core / Project (8)

| Tool | Purpose | When to use |
|---|---|---|
| `get_edt_version` | EDT version | Once at session start |
| `list_projects` | Workspace projects | At first contact with the workspace |
| `get_configuration_properties` | 1C configuration properties (name, version, subsystems) | During project onboarding |
| `clean_project` | Clear markers and full revalidation | When `get_project_errors` hangs or shows stale data |
| `revalidate_objects` | Revalidate by FQN array (`objects: ["Document.Foo"]`); empty array = full project | Targeted use after edits |
| `get_check_description` | Description of an EDT check by `checkId` | When you need to understand what a specific error means |
| `export_configuration_to_xml` | Export configuration to XML files (EDT menu: Export -> Configuration to XML Files) | On user request |
| `import_configuration_from_xml` | Import configuration from XML files (reverse of export) | On user request |

### 2. Errors & Problems (4)

| Tool | Purpose | When to use |
|---|---|---|
| `get_problem_summary` | Problem counts by project and severity | **First** — gives the full picture in one call |
| `get_project_errors` | Detailed errors. Filters: `projectName`, `severity` (ERRORS/BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL), `checkId` (substring), `objects` (FQN array), `limit` (default 100, max 1000) | After the summary — for targeted investigation |
| `get_bookmarks` | Workspace bookmarks | On request |
| `get_tasks` | TODO/FIXME markers | On request or during a technical-debt audit |

### 3. Code Intelligence (7)

| Tool | Purpose | When to use |
|---|---|---|
| `get_content_assist` | Completions at a code position (types, methods) | When working at a specific BSL position |
| `get_platform_documentation` | Platform documentation (types, methods, properties, constructors) | When in doubt about a 1C platform signature |
| `get_metadata_objects` | List of configuration objects with filters `metadataType`, `nameFilter`, `limit` (default 100, max 1000), `language` | Object overview; **always** use a filter |
| `get_metadata_details` | Detailed object properties by FQN array (`objectFqns: [...]`); FQNs may address members (e.g. `Catalog.Products.Attribute.Weight`). With `assignable: true` returns the assignable-property schema (per property: value kind, current value, allowed enum literals) | After finding the objects you need; use `assignable: true` to discover what `modify_metadata` can set and to what values |
| `list_subsystems` | Subsystem tree (flat table, recursive by default) | Getting familiar with the configuration structure |
| `get_subsystem_content` | Subsystem contents by FQN: properties, objects, nested subsystems | Drilling into a specific subsystem |
| `find_references` | All references to a metadata object in code, forms, roles, metadata. **Top-level objects only** (`Catalog.X`, `Document.Y`, `CommonModule.Z`); for nested ones (attributes, tabular sections, forms) it returns an error — use `rename_metadata_object`/`delete_metadata` directly | Before rename/delete of a top-level object |

### 4. Tags (2)

| Tool | Purpose |
|---|---|
| `get_tags` | All project tags with descriptions and object counts |
| `get_objects_by_tags` | Objects filtered by tags, with FQNs and tag descriptions |

### 5. Applications & Testing (5)

| Tool | Purpose | When to use |
|---|---|---|
| `get_applications` | Project infobases with update state | For debugging/updating |
| `list_configurations` | Launch configurations (runtime-client + Attach) with current running/suspended state | Before `debug_launch` |
| `update_database` | Update an infobase. Two ways to identify: `launchConfigurationName` **or** `projectName + applicationId`. Mode full/incremental | On request |
| `debug_launch` | Launch in debug mode. Same identification: `launchConfigurationName` (incl. Attach to 1C:Enterprise Debug Server) **or** `projectName + applicationId` | On request |
| `run_yaxunit_tests` | Run YAxUnit tests, parse JUnit XML, Markdown report | After edits, if the project has tests |

### 6. Debugging (12)

| Tool | Purpose |
|---|---|
| `set_breakpoint` | Set a BSL line breakpoint (EDT module path or absolute path + line) |
| `remove_breakpoint` | Remove by id or by project+module+line |
| `list_breakpoints` | Active breakpoints, optionally filtered by project |
| `wait_for_break` | Blocking wait for suspend (e.g. breakpoint hit) |
| `get_variables` | Read variables of a stack frame (lazy expand for nested) |
| `step` | Step over/into/out, returns a new snapshot |
| `resume` | Resume a thread or all threads of a target |
| `evaluate_expression` | Execute a BSL expression in frame context |
| `debug_yaxunit_tests` | Run YAxUnit tests in DEBUG mode so breakpoints fire |
| `debug_status` | Status of active debug launches: mode, suspend, threads, top frame |
| `start_profiling` | Toggle performance profiling on the active debug target |
| `get_profiling_results` | Profiling results: per-module / per-line, call counts, timing, coverage |

Typical cycle — see `edt-mcp-workflows.md`, section "Debugging".

### 7. BSL Code (12)

| Tool | Purpose | Notes |
|---|---|---|
| `list_modules` | BSL modules with type and parent object | Use filters; do not request everything |
| `get_module_structure` | Structure: procedures/functions with signatures, regions, parameters | **Before** reading code — check the map |
| `read_module_source` | Read the whole module or a line range (with YAML frontmatter) | Only when method context depends on regions/global variables |
| `write_module_source` | Write to module. Modes: `searchReplace` (default), `replace`, `append`. Identification via `modulePath` **or** `objectName + moduleType` (`ObjectModule`/`ManagerModule`/`FormModule`/`CommandModule`/`RecordSetModule`; FormModule needs `formName`, CommandModule needs `commandName`). Block-keyword balance is checked before write; `skipSyntaxCheck=true` forces the write. **See `edt-mcp-write-safety.md` — the most dangerous area** | |
| `read_method_source` | Read a **specific** method by name (case-insensitive) | **Preferred** way to read; saves tokens |
| `search_in_code` | Full-text/regex search over BSL. Parameters: `query`, `isRegex`, `caseSensitive`, `fileMask`, `metadataType`, `outputMode: full/count/files`, `maxResults` (default 100, max 500), `contextLines` (default 2, max 5) | Use instead of `Grep` on `.bsl` files. Run `count` or `files` first to gauge scope |
| `get_method_call_hierarchy` | Who calls a method (callers) / whom it calls (callees) | Before modifying a method — assess the impact |
| `go_to_definition` | Jump to a definition (method by name, metadata object by FQN) | Like F12 in an IDE |
| `get_symbol_info` | Type/hover info at a BSL position: inferred types, signatures, docs | To check inferred types |
| `get_form_layout_snapshot` | YAML snapshot of the WYSIWYG form: bounds, types, display props. `mode: compact` (default) — only visible elements; `full` — everything | Before editing a form — snapshot the structure |
| `get_form_screenshot` | PNG form from WYSIWYG (embedded image resource) | When visual context is needed |
| `validate_query` | Validate a 1C query (syntax + semantics). `dcsMode: true` — for DCS queries | **Before** inserting a new query into code |

### 8. Refactoring (4)

| Tool | Purpose | When to use |
|---|---|---|
| `rename_metadata_object` | Rename with cascading update (BSL code, forms, metadata). Workflow: 1) call without `confirm` — preview all change points with indices; 2) (optional) `disableIndices: "2,3,5"` to skip specific changes; 3) `confirm: true`. `maxResults` (default 20, 0 = no limit) caps the preview. Russian FQNs are supported | **Only** this way to rename; manual XML editing is dangerous. `modify_metadata` does **not** rename |
| `delete_metadata` | Delete an object **or** member by FQN with cascading reference cleanup (BSL, forms, other metadata). Two-phase: call without `confirm` to preview affected references, then `confirm: true` to apply. Russian FQNs are supported | Instead of manual `.mdo` editing |
| `create_metadata` | Create a metadata node addressed by a 1C full-name FQN: a top-level object (`Catalog.Products`) **or** a subordinate member (`Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Dimension.Product`, `Document.Order.TabularSection.Goods`, `Enum.Colors.EnumValue.Red`). Kind is inferred from the FQN; type/kind tokens may be English or Russian. Supported top-level types: `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor`. Params: `projectName`, `fqn`, optional `properties` (`[{name, value, language?}]`, only `synonym`/`comment` at creation), `expectedNotExists`. UUID is generated automatically | Instead of hand-building a new `.mdo`; set other properties with `modify_metadata`; run `get_project_errors` afterwards |
| `modify_metadata` | Set properties of an object **or** member by FQN. Params: `projectName`, `fqn`, `properties` (`[{name, value, language?}]`). Each property is validated against the assignable schema and allowed enum literals. Can set `synonym` (language-keyed), `comment`, and the data `type` (structured spec). Discover what is settable via `get_metadata_details(assignable: true)` | Set type/synonym/other assignable properties without editing `.mdo`. Does **not** rename — use `rename_metadata_object` |

### 9. Translation (LanguageTool) (3)

| Tool | Purpose |
|---|---|
| `generate_translation_strings` | Generate translation strings (`.lstr`/`.trans`/`.dict`) for a configuration, with translation storage and collection options. EDT menu: Translation -> Generate translation strings |
| `translate_configuration` | Propagate dictionary changes from storage projects (or in-configuration storages) into translated artifacts. EDT menu: Translation -> Translate configuration |
| `get_translation_project_info` | Diagnostics: project translation storages and available translation provider IDs |
