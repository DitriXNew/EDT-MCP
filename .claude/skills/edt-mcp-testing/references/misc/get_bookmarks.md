# get_bookmarks — how to test

**Purpose.** List Eclipse editor **bookmarks** across the workspace as a markdown table (Project / Message / Path / Line). Bookmarks are the standard Eclipse marker of type `org.eclipse.core.resources.bookmark` (set by hand in an editor's left ruler via "Add Bookmark…"), not 1C metadata and not breakpoints. Read-only — it only reads markers, never mutates the model or any resource.

**Preconditions.** Live EDT + MCP server on :8765. Require `State=ready` for the target project in `list_projects` before trusting the read. The tool walks `IProject.findMarkers(..., DEPTH_INFINITE)` over open projects, so a project must be **open** to contribute (closed projects are silently skipped). No open editor, no cursor position, and no derived-data/index dependency — bookmarks live in the workspace `.metadata`, so even a freshly redeployed (`-clean`) workspace returns immediately. **A fresh workspace has no bookmarks**, so an empty result is the normal, expected case — it is informational, not an error.

**Call (real, 2026-06-02):**
```
get_bookmarks(projectName="TestConfiguration")
```
All three params are optional (the input schema has no `required`):
- `projectName` — filter to one project. If given and the project does not exist, the tool returns the structured error `Project not found: <name>`. Omit it to scan **every** open project in the workspace.
- `filePath` — case-insensitive substring filter against the resource's full workspace path (e.g. `filePath="CommonModule"`).
- `limit` — max results, default 100 (overridable via `ToolParameterSettings`), hard-clamped to `[1, 1000]`.

**Result.** Returned as an embedded Markdown resource (`embedded://get_bookmarks.md`), not a JSON envelope. Real output from the live workspace (no bookmarks set):
```markdown
## Bookmarks

**Found:** 0 bookmarks

*No bookmarks found.*
```
When bookmarks exist, the body is a 4-column table instead of the `*No bookmarks found.*` line. Representative shape (constructed from source — `GetBookmarksTool.getBookmarks` + `MarkdownUtils.tableHeader/tableRow`; to reproduce live, add a bookmark on a line in the EDT editor ruler first):
```markdown
## Bookmarks

**Found:** 1 bookmarks

| Project | Message | Path | Line |
|---------|---------|------|------|
| TestConfiguration | Check this validation | /TestConfiguration/src/CommonModules/Error/Module.bsl | 12 |
```
Field meaning (from the `BookmarkInfo` struct):
- **Project** — `project.getName()` (the contributing workspace project).
- **Message** — the `IMarker.MESSAGE` attribute (the bookmark's free-text label; empty string if none was typed).
- **Path** — the resource's full workspace path (`IResource.getFullPath()`, e.g. `/TestConfiguration/src/...`), not an OS path.
- **Line** — `IMarker.LINE_NUMBER`, or `-1` when the marker has no line (e.g. a bookmark on a whole file/folder).

If the result is truncated at the cap, the `**Found:**` line appends ` (limit: N, more available)`.

**Gotchas.**
- **Empty ≠ broken.** `**Found:** 0 bookmarks` / `*No bookmarks found.*` is the normal state on any workspace where nobody added a bookmark. It stays plain markdown (informational), so do NOT treat it as a failure. To get a non-empty table for testing you must first add a bookmark interactively in the EDT editor (right-click the ruler → Add Bookmark) — there is no MCP tool to create one.
- **Editor bookmarks, not breakpoints or tasks.** This reads only `org.eclipse.core.resources.bookmark` markers. Breakpoints are a different family (`list_breakpoints`), and `// TODO`-style task markers are a different tool (`get_tasks`). A bookmark you cannot see in EDT's Bookmarks view will not appear here.
- **Open projects only.** Closed projects (`Open=No` in `list_projects`) are skipped without error. If a bookmark you expect is missing, confirm its project is open and `ready`.
- **Path filter is a substring, case-insensitive.** `filePath` matches anywhere in the workspace path and lowercases both sides; it is not a glob and not anchored. There is no synonym/dialect handling here — paths are programmatic, so this is unrelated to 1C bilingual concerns (no metadata TYPE token, no synonyms).
- **Error contract.** A bad `projectName` yields the structured `{success:false, error:"Project not found: <name>"}` envelope (`isError:true`), and an unexpected internal exception returns `ToolResult.error(e.getMessage())` likewise — both regardless of the normal markdown shape. Everything else (including zero results) stays markdown.
- **Flaky output channel.** If the response comes back garbled/empty (a bare `Error`/`Done` instead of the `## Bookmarks` header), do not retry-spam. Re-verify independently via the EDT log at `D:\WS\EDT\.metadata\.log`, which records the full request/response.
