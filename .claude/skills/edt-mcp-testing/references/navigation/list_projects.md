# list_projects — how to test

**Purpose.** List every project in the EDT workspace as a markdown table (Name / State / Path / Open / EDT Project / Natures / Reason). It is the safe, read-only "is the workbench ready?" first call — check the `State` column (`ready` vs `building`) before driving any project-scoped tool.

**Preconditions.** None beyond a running MCP server (:8765). No `projectName` is required. The optional `format` is `md` (default) or `json`. No open editor, no cursor position, no index dependency. It does **not** mutate anything, so no revert is needed. State is computed per project from the EDT context lifecycle and derived-data manager, so a project that is still indexing shows `State=building` (only `ready` projects are safe for resolution/AST tools).

**Call (real):**
```
list_projects()
```
(The default format is `md`; call `list_projects(format="json")` for machine-readable rows.)

**Result.** With `format="md"`, returns an MCP **resource** (`embedded://list_projects.md`) containing a markdown document. Representative output:
```
## Workspace Projects

**Total:** 3 projects

| Name | State | Path | Open | EDT Project | Natures | Reason |
|------|-------|------|------|-------------|--------|--------|
| IRP | ready | D:\CF\IRP\IRP | Yes | Yes | xtextNature, V8ConfigurationNature |  |
| TestConfiguration | ready | D:\GitHub\EDT-MCP\TestConfiguration | Yes | Yes | xtextNature, V8ConfigurationNature |  |
| Unit | not_available | D:\CF\IRP\Unit | No | - | - | Project is closed |
```
Column meaning (from `ListProjectsTool` + `ProjectStateChecker`):
- **Name** — the workspace project name (this is the value you pass as `projectName` to other tools).
- **State** — one of `ready`, `building`, `not_available`, `unknown` (the `ProjectState` enum). `ready` = context started, derived data idle and all computed; `building` = pipeline busy or derived data incomplete; `not_available` = missing / closed / not an EDT project / unreadable natures / no EDT context (not started, or a restart job pending); `unknown` = state services unavailable.
- **Path** — absolute OS path of the project location (empty string if `getLocation()` is null).
- **Open** — `Yes`/`No` (`project.isOpen()`).
- **EDT Project** — `Yes` if the project has `V8ConfigurationNature` or `V8ExtensionNature`; `No` for a non-EDT open project; `-` when the project is closed.
- **Natures** — short (last dotted segment) names of up to the first 3 natures; a 4th+ shows as `...+N`. `-` when closed or none.
- **Reason** — the state explanation, escaped for the table and always the last column; empty for `ready`. A context that stays not started needs close/reopen in EDT or `delete_project` and re-import. A registered context that EDT has not finished starting keeps its derived-data state (`ready`, `building` or `unknown`); only when that is not `ready` does the reason add the starting-context sentence. Starting contexts and pending workspace restart jobs call for polling `list_projects` until `ready`.

With `format="json"`, `structuredContent` contains `{"projects":[{"name", "state", "reason"?, "path", "open", "edtProject"?, "natures"}]}`. The optional string `reason` is present only for non-ready rows and contains the raw state explanation; ready rows omit it.

When the workspace has no projects the body is `*No projects found.*` under the same header.

**Gotchas.**
- **Output format.** The default returns a markdown resource (`embedded://list_projects.md`); parse its table. With `format="json"`, read the rows from `structuredContent.projects` and inspect the optional `reason` for non-ready projects.
- **Error contract is different too.** On an unexpected internal failure the tool returns a plain markdown string `**Error:** <message>` (it logs via `Activator.logError` and returns that string) — it does NOT use the structured `{success:false,error}` / `isError:true` envelope that argument-validating tools use. There are no arguments to validate here, so the structured-error path is effectively unreachable from the caller side.
- **State is the whole point.** Right after a `-clean` redeploy (or opening a large config like `IRP`) a project can briefly show `State=building`; wait for `ready` before calling resolution/AST/metadata tools, or they may fail or return partial data. Re-run `list_projects` to poll readiness.
- **Closed projects are thin.** A closed project (`Open=No`) reports `State=not_available` and `-` for EDT Project and Natures — that is expected, not a bug.
- **Flaky output channel.** If the result text comes back garbled/empty (a bare `Error`/`Done` instead of the table), do not retry-spam. Re-verify independently: the EDT log at `D:\WS\EDT\.metadata\.log` records the full request/response, and the same project list is observable in the EDT Project Explorer.
- **No bilingual concern.** Output is fixed English headers and programmatic project names; nothing here is translated (no 1C metadata TYPE token, no synonyms, no `modulePath`).
- **Multiple projects.** The live workspace also contains `IRP` (a large real config) and `Unit`; expect more than just `TestConfiguration`. Match on the `Name` column exactly.
