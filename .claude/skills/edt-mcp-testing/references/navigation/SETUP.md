# Navigation / read family — SETUP

Shared preconditions for e2e-testing the read & navigation tools
(`list_projects`, `list_modules`, `read_module_source`, `read_method_source`,
`get_module_structure`, `go_to_definition`, `get_method_call_hierarchy`,
`get_symbol_info`, `find_references`, `get_content_assist`).

These tools are **read-only** — they never mutate the model, so no revert is
needed. The work is making sure the project is *indexed and ready* before you
trust a result.

## 1. Workbench up, MCP reachable
- A live (non-elevated) EDT copy with workspace `D:\WS\EDT`; MCP on `:8765`.
- After a plugin change: `pwsh D:\Soft\edt-redeploy.ps1` (build → kill → swap →
  `-clean` relaunch → waits for `:8765`). The script may **exit 1** yet print
  `MCP server UP on 8765` — that *is* success. Confirm with `get_edt_version`,
  not the exit code.

## 2. Project ready (not "building")
- First call `list_projects` and require the target project's `State` = **ready**
  (not `building` / `not_available`). After a `-clean` relaunch the Xtext/BSL
  index rebuilds for a while; navigation results are **empty or partial until
  the build finishes**. If a navigation call comes back empty, re-check
  `list_projects` and retry once the project is `ready`.
- Test base: **`TestConfiguration`** (small: `Catalog.Catalog`, a `CommonModule`,
  a form). `IRP` is a large real config for heavier cases.

## 3. Module addressing
- `modulePath` is the canonical module parameter (src-relative, e.g.
  `CommonModules/Foo/Module.bsl`, or an absolute path). It is the same form the
  read/navigation tools already use; the debug tools also accept it now
  (`module` is a legacy alias).

## 4. UI-thread / editor-backed tools
- `get_symbol_info` and `get_content_assist` run on the SWT UI thread and resolve
  against an Xtext editor/document at a position. They are the hardest to drive
  headlessly: a cold or rapid call can return empty (readiness race). Give the
  index time, don't spam, and cross-check against `read_module_source` to confirm
  the position you are pointing at.

## 5. Error & flaky-output expectations
- **Error contract**: a genuine failure (project/object not found, bad args,
  service unavailable) comes back as a structured `{"success":false,"error":…}`
  with `isError:true`, regardless of the tool's normal markdown/JSON output.
  *Informational* not-found (e.g. "method not found" + a list of available
  methods, "No references found") stays in the natural markdown format.
- **Flaky channel**: the result text occasionally drops/garbles. If you see a
  bare `Error`/`Done`, re-verify via the EDT log `D:\WS\EDT\.metadata\.log`
  (full request/response is logged there) rather than trusting the echo.

## 6. Search vs. AST
- `search_in_code` (query family) is **literal**, not dialect-aware — searching an
  English keyword won't find its Russian equivalent. For identifiers/definitions
  use the AST-backed tools here (`go_to_definition`, `find_references`,
  `get_method_call_hierarchy`).
