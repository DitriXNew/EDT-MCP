# Typical EDT-MCP workflows

This is a cookbook: short recipes for common tasks. For tool parameters see `edt-mcp-tools.md` or the tool descriptions themselves.

## 1. Onboarding — first contact with a project

Goal: understand the project, the configuration, the subsystems, and where to look for code.

1. `get_edt_version` — record the EDT version (useful for subsequent compatibility questions).
2. `list_projects` — list of workspace projects; pick the one you need.
3. `get_configuration_properties` with `projectName` — name, version, key configuration properties.
4. `get_problem_summary` (optionally `projectName`) — overall picture: how many problems and where. Immediately shows the state of the configuration.
5. `list_subsystems` — overview of the subsystem tree.
6. `get_metadata_objects` with `metadataType: commonModules`, `limit: 100` — review key common modules.
7. Note for yourself the patterns you find (configuration type, SSL version, key common modules, extensions) so you do not have to re-query the same things later in the session.

## 2. Reading object code

Before editing/analysing a specific object (document, catalog, common module):

1. `get_metadata_details` with an array of FQNs — properties, attributes, tabular sections.
2. `list_modules` with `projectName` (filter yourself) — modules of the project/object.
3. `get_module_structure` with `projectName` + `modulePath` — map of methods and regions of the module.
4. `read_method_source` with `projectName` + `modulePath` + `methodName` — a specific method. **Do not** call `read_module_source` for the whole module if a single method or a line range (`startLine` / `endLine`) is enough.

## 3. Finding usages and impact

Before changing a method or object:

- **Metadata object (top-level)**: `find_references` with `objectFqn` — all references in code, forms, roles, metadata. **Does not** work for nested objects (attributes, tabular sections).
- **BSL method**: `get_method_call_hierarchy` with `projectName` + `modulePath` + `methodName` + `direction` (`callers` | `callees`).
- **Jump to definition**: `go_to_definition` with `projectName` + `symbol` (method name or object FQN).
- **Text search in code**: `search_in_code` with `outputMode: count` first (assess scope), then `full` for details.
- **Type / signature at a position**: `get_symbol_info` with `projectName` + `modulePath` + `line` + `column`.

## 4. Editing BSL code

See `edt-mcp-write-safety.md` for details.

Short form:
1. `read_method_source` -> remember the body.
2. Prepare the full new version of the method (or fragment for `searchReplace`).
3. `write_module_source` with `mode: "searchReplace"`, `oldSource` (what to replace) and `source` (replacement). Do not disable the syntax check (`skipSyntaxCheck`) without a reason.
4. Inspect the response for syntax / semantic errors. If any — fix them.
5. `get_project_errors` with `projectName` and `objects: ["<FQN>"]` — final check on the changed object. For larger edits — `get_problem_summary` on the project for an overall picture.

## 5. A new 1C query

1. Prepare the query text.
2. `validate_query` with `projectName` and `queryText`. If the query is for DCS — `dcsMode: true`.
3. If there are no errors — insert into code via `write_module_source`.

## 6. Metadata refactoring

### Rename

1. If you are renaming a **top-level object** (`Catalog.X`, `Document.Y`, `CommonModule.Z`) — first run `find_references` to estimate scope. For nested objects (attributes, tabular sections) skip this step: `find_references` is not available for them.
2. `rename_metadata_object` with `objectFqn` (current FQN) and `newName` (**short** new name, not an FQN), **without** `confirm` — preview all change points.
3. If needed — disable specific changes via `disableIndices` (list of indices from the preview).
4. Same call with `confirm: true` — apply.

### Delete

1. For top-level — `find_references` to estimate scope. For nested — skip.
2. `delete_metadata_object` with `projectName` + `objectFqn`, without `confirm` — preview affected references.
3. With `confirm: true` — apply.

### Add an attribute

`add_metadata_attribute` with `projectName` + `parentFqn` + `attributeName` — cascadingly creates the attribute without manual `.mdo` editing.

## 7. Debugging

`debug_launch` accepts either **`launchConfigurationName`** (an existing EDT launch configuration, including Attach) or a pair **`projectName` + `applicationId`** (when no configuration exists). Remember the `applicationId` from the response — you will need it for `wait_for_break`, `start_profiling`, `get_applications`.

1. `list_configurations` or `get_applications` — figure out what to launch.
2. `set_breakpoint` with `projectName` + `module` + `lineNumber` — set breakpoints.
3. `debug_launch` — launch. Save the `applicationId` from the response.
4. The user performs an action in 1C:Enterprise.
5. `wait_for_break` with `applicationId` — wait for suspend. The response contains `threadId` and `frameRef`.
6. `debug_status` -> `get_variables` (`frameRef` or `threadId` + `frameIndex`) -> `evaluate_expression` -> `step` (`threadId` + `kind`: `over` | `into` | `out`) -> `resume`.
7. When done: `remove_breakpoint` for all temporary breakpoints.

## 8. Running the application and updating the infobase

- Run in enterprise mode (with debugger attached or just to test): `debug_launch` by `launchConfigurationName` or `projectName` + `applicationId`.
- Update the infobase: `update_database` with `launchConfigurationName` or `projectName` + `applicationId`. Use after metadata changes.

## 9. Running YAxUnit tests

- Normal run: `run_yaxunit_tests` with `projectName` — JUnit XML is parsed; the response is in Markdown.
- When a test fails and the reason is unclear: `debug_yaxunit_tests` + `set_breakpoint` in the relevant test, then proceed as in section 7.

## 10. Profiling

1. An active debug session is required (see section 7); you have the `applicationId`.
2. `start_profiling` with `applicationId` — toggle ON.
3. Execute the scenario in 1C:Enterprise.
4. `start_profiling` with the same `applicationId` again — toggle OFF.
5. `get_profiling_results` (optional `moduleFilter`, `minFrequency`) — per-module / per-line, call counts, timing.

## 11. Working with forms

1. `get_form_layout_snapshot` with `projectName` + `formPath`, `mode: compact` — form structure as YAML.
2. If a visual view is needed — `get_form_screenshot` with `projectName` + `formPath`.
3. Edits to `.form` — via `Read` + `Edit` (following the rules in `edt-metadata.md`).

## 12. Problem and check analysis

1. `get_problem_summary` (optionally `projectName`) — overview by project / severity.
2. `get_project_errors` with `severity`, `objects`, `checkId`, `limit` filters — details on a subset.
3. `get_check_description` with `checkId` — what the rule actually checks and how to fix it.
4. After mass edits: `revalidate_objects` for specific FQNs or `clean_project` for a full revalidation.

## 13. Platform reference and code hints

- Platform type documentation (methods, properties, constructors): `get_platform_documentation`.
- Hints at a concrete BSL position (after a dot, after a variable name): `get_content_assist` with `projectName` + `filePath` + `line` + `column`. The file must be saved on disk.
- Type / hover info for a symbol: `get_symbol_info`.

## 14. XML export / import

- `export_configuration_to_xml` — export the project to a directory of XML files (equivalent to the EDT menu item). Useful for shipping a configuration in XML format or to Designer.
- `import_configuration_from_xml` — reverse operation, creates a new EDT project.

## 15. Translation (LanguageTool)

1. `get_translation_project_info` — configured storages and providers.
2. `generate_translation_strings` — generate strings.
3. `translate_configuration` — propagate dictionary changes into artifacts.

## 16. Finishing the task — what to tell the user

After a series of edits, your report to the user **must** include not only "what I did" but also **what they need to verify by hand** — things you cannot confirm through MCP. At minimum:

- **List of changed objects** (FQNs of modules, metadata, forms) — so the user knows where to look.
- **What is already verified through MCP** — e.g. `get_project_errors` on the changed object returned 0 errors, `validate_query` passed, `run_yaxunit_tests` is green. If something is not verified — say why explicitly.
- **What the user needs to verify by hand:**
  - visual form rendering, if you edited `.form` or code that affects a form;
  - behaviour in 1C:Enterprise mode — especially for logic not covered by tests;
  - performance under load, if the edits touched queries in hot paths;
  - infobase update, if metadata changed;
  - open EDT editors on the same module (may show the old version — recommend reloading the file from disk).
- **What is left undone** — if the task is broader than what you completed in this session, list it explicitly.

Do not write "all done, everything works" if part of the verification requires UI you do not have access to. It is more honest to say "MCP-side checks passed, final feature confirmation is up to you".
