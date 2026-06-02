# get_tasks — how to test

**Purpose.** List task markers (TODO / FIXME / XXX / HACK style) that EDT has indexed across the workspace, optionally scoped to one project. Returns each task's type, priority, message, resource path and line number as a Markdown table. Read-only — never mutates the model; reads only Eclipse marker state, no BM transaction.

**Preconditions.** Live EDT + workspace `D:\WS\EDT`, MCP on `:8765`. Confirm the target project is `State=ready` via `list_projects` before trusting the result — task markers are produced by the Xtext/BSL build, so after a `-clean` redeploy you must wait for the index/build to finish or the list will be empty (or stale). Tasks come from comments the validator recognizes (e.g. a BSL `// TODO: ...`); a config with no such comments legitimately returns zero tasks. No editor / cursor state required. Does not mutate; no revert needed.

**Call (real, 2026-06-02):**
```
get_tasks(projectName="TestConfiguration")
```
All parameters are optional (`required: []`). Other filters:
- `filePath` — case-insensitive **substring** match against the full resource path (e.g. `"ManagedApplicationModule"` or `"CommonModule"`).
- `priority` — one of `high` / `normal` / `low` (case-insensitive); maps to `IMarker.PRIORITY_*`. Most BSL TODOs are `normal`. An unrecognized value silently disables the filter (no error).
- `limit` — integer, default 100, hard-clamped to `[1, 1000]`. When the count reaches the limit the header appends `(limit: N, more available)`.
- Omitting `projectName` scans **every open project** in the workspace — on this setup that includes the large `IRP` config, so prefer scoping to `TestConfiguration` for a fast, predictable result.

**Result.** Returned as an embedded Markdown resource (`embedded://get_tasks.md`), not JSON. Header `## Tasks`, a `**Found:** N tasks` line, then a table with columns `Type | Priority | Message | Path | Line`. Real output for `TestConfiguration`:
```markdown
## Tasks

**Found:** 1 tasks

| Type | Priority | Message | Path | Line |
| --- | --- | --- | --- | --- |
| TODO | normal | TODO: Insert the handler content  | /TestConfiguration/src/Configuration/ManagedApplicationModule.bsl | 3 |
```
Key checks: `Type` is derived from the message text (`TODO` / `FIXME` / `XXX` / `HACK`, else `TASK`) by uppercase substring, NOT from the marker type; `Path` is the workspace-relative resource path (`/Project/src/...`); `Line` is 1-based (`-1` if the marker has no line). A single BSL TODO surfaces under both the base task marker (`org.eclipse.core.resources.taskmarker`) and the Xtext subtype (`org.eclipse.xtext.ui.task`) — the tool dedups by `(path, line, message, priority)`, so expect it reported **once**, not twice.

**Empty (representative) shape.** When no task markers exist (e.g. a fresh `-clean` before the build finishes, or a project with no TODO/FIXME comments), the table is omitted and the body is the italic sentinel — this is a normal informational result (Markdown), NOT an error:
```markdown
## Tasks

**Found:** 0 tasks

*No tasks found.*
```

**Gotchas.**
- **Empty is informational, not an error.** `*No tasks found.*` and a `**Found:** 0 tasks` header stay Markdown with no `isError`. Do not treat zero tasks as a failure — verify the project is `ready` and actually contains TODO/FIXME comments first.
- **Structured error contract.** Genuine failures arrive via `ToolResult.error(...)` → `{success:false, error:"..."}` with `isError:true`, not Markdown. An unknown `projectName` gives `Project not found: <name>` (resolved through `ProjectContext.of`). Unexpected `CoreException`s surface as the exception message.
- **`Type` is heuristic from the message.** A comment like `// HACK: ...` shows `Type=HACK`; anything the regex-free substring scan doesn't recognize falls back to `TASK`. The leading `TODO:`/`FIXME:` prefix is part of the marker `Message` itself (note the platform leaves the trailing space — `TODO: Insert the handler content `).
- **`filePath` is a literal substring, not a glob.** It is `contains` (lower-cased) over the full path; no `*`/`?` wildcards, no FQN resolution. Searching for the BSL TODO text won't work here — match on the path only.
- **Markdown cells are escaped.** Messages/paths go through `MarkdownUtils.tableRow`, so an embedded `|` or newline in a comment won't break the table. Don't assert on raw unescaped pipes.
- **Not bilingual / not code-aware.** This reads Eclipse markers, so it is language-agnostic at the API surface (no ru/en TYPE token, no synonym, no object-name resolution). It only finds comments the BSL/Xtext task tagger emitted; it does not parse code itself. (Contrast `search_in_code`, which is a literal text search — a different family.)
- **Flaky output channel.** If the text arrives garbled or as a bare `Error`/`Done` instead of the table, do NOT retry-spam. Re-verify via the EDT log `D:\WS\EDT\.metadata\.log` and confirm the server is up (`:8765` / `get_edt_version`); trust the log over the echoed text.
```
