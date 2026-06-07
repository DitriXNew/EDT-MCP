---
name: edt-mcp-testing
description: How to manually e2e-test each EDT-MCP server tool against a live EDT workbench + TestConfiguration. One reference per tool under references/<family>/, each with the exact MCP call, the real expected result, and gotchas. Use when validating an MCP tool end-to-end by actually calling it (for build/unit tests see edt-mcp-build-test).
---

# EDT-MCP — manual e2e testing of tools

How to **run each MCP tool live against a working EDT** and confirm it actually works (not just compiles / passes a unit test). Build and unit tests are in `edt-mcp-build-test`; this is live e2e through real MCP calls. (The automated black-box suite is `edt-mcp-e2e-testing`.)

## Structure
- `references/<family>/<tool>.md` — **one reference per tool**: purpose, exact call, expected result, gotchas.
- `references/<family>/SETUP.md` — shared family preconditions (what the family's tools can't be tested without).

## Test bench (harness)
- **Non-elevated EDT copy** + workspace `D:\WS\EDT`; MCP listens on `:8765`. Deploy edits with `pwsh D:\Soft\edt-redeploy.ps1` (build → kill EDT → swap bundle → `-clean` relaunch → wait for `:8765`). The script sometimes returns **exit 1** yet prints `MCP server UP on 8765` — that is success; **check the port / `get_edt_version`, don't trust the exit code**.
- **Test base** — the `TestConfiguration` project (in repo `TestConfiguration/src`, open in the workspace). Run destructive/mutating cases only on it; revert after: `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
- **Workflow invariant**: edit → `bash source/compile.sh` → redeploy → live MCP call → `git diff` → revert. After a `-clean` redeploy EDT loses unsaved in-memory changes — mutate through MCP (`write_module_source` etc.) so model and disk stay in sync.
- **Flaky tool output**: the channel sometimes drops text (you see a bare `Error` / `Done` instead of JSON). **Re-verify state independently**: `debug_status`, `git status`, `Get-Process`, and especially the EDT log `D:\WS\EDT\.metadata\.log` (it records the full request/response). Don't trust the echo.

## How to add a tool reference
1. Run the tool **live** with real arguments on `TestConfiguration`.
2. Record the **exact call + actual output + gotchas** in `references/<family>/<tool>.md`.
3. Link the family `SETUP.md`.
4. If the run needs a base mutation — document it and the revert.

## Index — debug (`references/debug/`)
- [SETUP](references/debug/SETUP.md) — bring up a debuggable scenario (executable code + trigger + stop).
- Configs/launch: `list_configurations`, `debug_launch`, `debug_status`, `terminate_launch`.
- Breakpoints: `set_breakpoint`, `list_breakpoints`, `remove_breakpoint`.
- Break and inspect: `wait_for_break`, `get_variables`, `evaluate_expression`, `step`, `resume`.

> Full debug flow: set_breakpoint → debug_launch(update) → wait_for_break(hit) → get_variables → step over → get_variables → evaluate_expression → resume → remove_breakpoint. Details per tool in its reference.

## Index — navigation / read (`references/navigation/`)

Read-only navigation and read tools, each with a real live call on `TestConfiguration`.
- [SETUP](references/navigation/SETUP.md) — project ready (not `building`), BSL index built, modulePath addressing, UI-thread tools.
- Workspace/listing: [list_projects](references/navigation/list_projects.md), [list_modules](references/navigation/list_modules.md).
- Source: [read_module_source](references/navigation/read_module_source.md), [read_method_source](references/navigation/read_method_source.md), [get_module_structure](references/navigation/get_module_structure.md).
- AST/refs: [go_to_definition](references/navigation/go_to_definition.md), [find_references](references/navigation/find_references.md), [get_method_call_hierarchy](references/navigation/get_method_call_hierarchy.md), [get_symbol_info](references/navigation/get_symbol_info.md), [get_content_assist](references/navigation/get_content_assist.md).

## Index — metadata-read (`references/metadata-read/`)

Read-only reading of the configuration model (objects, details, properties, subsystems, tags). Each with a real live call on `TestConfiguration`.
- Objects: [get_metadata_objects](references/metadata-read/get_metadata_objects.md), [get_metadata_details](references/metadata-read/get_metadata_details.md).
- Configuration: [get_configuration_properties](references/metadata-read/get_configuration_properties.md) (YAML).
- Subsystems: [list_subsystems](references/metadata-read/list_subsystems.md), [get_subsystem_content](references/metadata-read/get_subsystem_content.md).
- Tags: [get_tags](references/metadata-read/get_tags.md), [get_objects_by_tags](references/metadata-read/get_objects_by_tags.md).

## Index — query / problems / misc (read-only)

- **query** (`references/query/`): [validate_query](references/query/validate_query.md) (dialect-aware, `valid:false` ≠ failure).
- **problems** (`references/problems/`): [get_project_errors](references/problems/get_project_errors.md), [get_problem_summary](references/problems/get_problem_summary.md), [get_check_description](references/problems/get_check_description.md) (reads `<checkId>.md` from a configurable folder; the feature is OFF by default; checkId is sanitized — dotted codes don't resolve).
- **misc** (`references/misc/`): [get_edt_version](references/misc/get_edt_version.md) (TEXT, liveness probe), [get_platform_documentation](references/misc/get_platform_documentation.md), [get_markers](references/misc/get_markers.md), [get_applications](references/misc/get_applications.md).

## Index — profiling / forms / xml / translation / launch / metadata-write

Families with write/destructive/special setup. For mutating/destructive tools the docs describe the **test procedure from source** (mutate-then-revert / preview-then-confirm), without live destruction.
- **profiling** (`references/profiling/`): [start_profiling](references/profiling/start_profiling.md) (a TOGGLE; applicationId = the debug-session id, not the infobase GUID), [get_profiling_results](references/profiling/get_profiling_results.md) (read-only; returns only the most recent measurement session).
- **forms** (`references/forms/`): [get_form_screenshot](references/forms/get_form_screenshot.md), [get_form_layout_snapshot](references/forms/get_form_layout_snapshot.md) — need the JVM flag `-DnativeFormBufferedLayoutRender=true`; blank ≠ a bug; native vs buffered render.
- **xml** (`references/xml/`): [export_configuration_to_xml](references/xml/export_configuration_to_xml.md), [import_configuration_from_xml](references/xml/import_configuration_from_xml.md) (MUTATES → revert).
- **translation** (`references/translation/`): [get_translation_project_info](references/translation/get_translation_project_info.md) (read-only), [generate_translation_strings](references/translation/generate_translation_strings.md), [translate_configuration](references/translation/translate_configuration.md) (MUTATES + external providers).
- **launch** (`references/launch/`): [update_database](references/launch/update_database.md) (DESTRUCTIVE, exclusive infobase access), [run_yaxunit_tests](references/launch/run_yaxunit_tests.md), [debug_yaxunit_tests](references/launch/debug_yaxunit_tests.md).
- **metadata-write** (`references/metadata-write/`): [create_metadata](references/metadata-write/create_metadata.md) (FQN-addressed), [modify_metadata](references/metadata-write/modify_metadata.md) (set properties=[{name,value,language?}] by FQN), [write_module_source](references/metadata-write/write_module_source.md) (mutate→verify→revert), [rename_metadata_object](references/metadata-write/rename_metadata_object.md), [delete_metadata](references/metadata-write/delete_metadata.md) (FQN-addressed; CASCADE; preview→confirm→revert; explicit-request-only), [adopt_metadata_object](references/metadata-write/adopt_metadata_object.md) (adopt a base object/member into an extension; MUTATES→revert).

> **Coverage:** debug + navigation, metadata-read, query, problems, misc, profiling, forms, xml, translation, launch, metadata-write = every MCP tool (62 today) has a per-tool e2e reference. Write/destructive tools document the procedure from source (mutate-then-revert / preview-then-confirm), not live destruction.
