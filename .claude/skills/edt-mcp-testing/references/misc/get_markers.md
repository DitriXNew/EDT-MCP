# get_markers — how to test

**Purpose.** List Eclipse workspace markers — **bookmarks** (manual navigation aids) and/or **task markers** (TODO / FIXME / XXX / HACK style, indexed by the Xtext/BSL build) — in one report, optionally scoped to a project. Returns each marker's kind, type, priority, message, resource path and line number as a Markdown table. Read-only — never mutates the model; reads only Eclipse marker state, no BM transaction. **Consolidation of the former `get_bookmarks` + `get_tasks`** (those two were mirror scans of the same workspace markers; `markerKind` selects the family, `priority` sub-filters tasks only).

**Preconditions.** Live EDT + workspace `D:\WS\EDT`, MCP on `:8765`. Confirm the target project is `State=ready` via `list_projects` before trusting the task side — task markers are produced by the Xtext/BSL build, so after a `-clean` redeploy you must wait for the index/build to finish or the task list will be empty (or stale). Bookmarks are set by a human in the IDE (no committed fixture has any), so the bookmark side is normally an empty report. No editor / cursor state required. Does not mutate; no revert needed.

**Call (real):**
```
get_markers(projectName="TestConfiguration")
```
All parameters are optional (`required: []`). Filters:
- `markerKind` — enum `bookmark` | `task`; **omit to list both**. An out-of-set value is rejected up front with `Invalid markerKind: '<v>'. Must be one of: bookmark, task`.
- `priority` — enum `high` / `normal` / `low` (case-insensitive); maps to `IMarker.PRIORITY_*`. **Sub-filters task markers only** — bookmarks have no priority and are unaffected. Combining `priority` with `markerKind=bookmark` is a **rejected contradiction** (`priority filter applies to task markers only ...`). An out-of-set value is rejected (`Invalid priority: '<v>'. Must be one of: high, normal, low`).
- `filePath` — case-insensitive **substring** match against the full resource path (e.g. `"ManagedApplicationModule"` or `"CommonModule"`). Literal `contains`, no glob/FQN.
- `limit` — integer, default 100, hard-clamped to `[1, 1000]`. When the count reaches the limit the header appends the limit-reached notice.
- Omitting `projectName` scans **every open project** in the workspace (via `ProjectContext.allProjects()`) — on this setup that includes the large `IRP` config, so prefer scoping to `TestConfiguration` for a fast, predictable result.

**Result.** Returned as an embedded Markdown resource, not JSON. Header `## Markers`, a `**Found:** N markers` line, then a table with columns `Kind | Type | Priority | Message | Path | Line`. Representative task row:
```markdown
## Markers

**Found:** 1 markers

| Kind | Type | Priority | Message | Path | Line |
| --- | --- | --- | --- | --- | --- |
| task | TODO | normal | TODO: Insert the handler content  | /TestConfiguration/src/Configuration/ManagedApplicationModule.bsl | 3 |
```
Key checks:
- `Kind` is `bookmark` or `task`. For a bookmark row, `Type=BOOKMARK` and `Priority=-` (a bookmark has no priority).
- For a task row, `Type` is derived from the message text (`TODO` / `FIXME` / `XXX` / `HACK`, else `TASK`) by uppercase substring, NOT from the marker type.
- `Path` is the workspace-relative resource path (`/Project/src/...`) — it already carries the project name as its first segment, so there is no separate Project column. `Line` is 1-based (`-1` if the marker has no line).
- A single BSL TODO surfaces under both the base task marker (`org.eclipse.core.resources.taskmarker`) and the Xtext subtype (`org.eclipse.xtext.ui.task`) — the tool dedups by `(path, line, message, priority)`, so expect it reported **once**, not twice.

**Empty (representative) shape.** When no markers match (a fresh `-clean` before the build finishes, `markerKind=bookmark` with no human bookmarks, or an unmatchable `filePath`), the table is omitted and the body is the italic sentinel — a normal informational result (Markdown), NOT an error:
```markdown
## Markers

**Found:** 0 markers

*No markers found.*
```

**Gotchas.**
- **Empty is informational, not an error.** `*No markers found.*` + `**Found:** 0 markers` stay Markdown with no `isError`. Don't treat zero as a failure — for tasks, verify the project is `ready` and contains TODO/FIXME comments; for bookmarks, the committed fixture has none, so empty is the correct happy state.
- **Validation runs up front (headless-reachable).** `markerKind`/`priority` enum checks and the `priority + markerKind=bookmark` contradiction are validated before any workspace access, so they are deterministic negative cases. Genuine errors arrive via `ToolResult.error(...)` → `{success:false, error:"..."}` with `isError:true`, not Markdown. An unknown `projectName` gives the actionable `Project not found: <name>. Use list_projects ...` (via `ProjectContext.of`).
- **`priority` is task-only.** With `markerKind` omitted, `priority` sub-filters tasks while bookmarks pass through; with `markerKind=task` it filters tasks; with `markerKind=bookmark` it is rejected. Most BSL TODOs are `normal`.
- **`Type` is heuristic from the message.** A comment like `// HACK: ...` shows `Type=HACK`; anything unrecognized falls back to `TASK`. The leading `TODO:`/`FIXME:` prefix is part of the marker `Message` itself (the platform leaves the trailing space — `TODO: Insert the handler content `).
- **`filePath` is a literal substring, not a glob.** `contains` (lower-cased) over the full path; no `*`/`?` wildcards, no FQN resolution. Match on the path only.
- **Markdown cells are escaped.** Messages/paths go through `MarkdownUtils.tableRow`, so an embedded `|` or newline won't break the table. Don't assert on raw unescaped pipes.
- **Not bilingual / not code-aware.** This reads Eclipse markers — language-agnostic at the API surface (no ru/en TYPE token, no synonym, no object-name resolution). It only finds comments the BSL/Xtext task tagger emitted; it does not parse code itself.
- **Flaky output channel.** If the text arrives garbled or as a bare `Error`/`Done` instead of the table, do NOT retry-spam. Re-verify via the EDT log `D:\WS\EDT\.metadata\.log` and confirm the server is up (`:8765` / `get_edt_version`); trust the log over the echoed text.
