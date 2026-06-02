# get_tags — how to test

**Purpose.** List every custom **tag** defined in an EDT project as a markdown table (`#` / Name / Color / Description / Objects), plus a `**Total tags:**` line. Tags are an **EDT-MCP-specific** feature — user-defined colored labels for organizing/filtering metadata objects — not a native 1C metadata concept. Read-only; the only argument is `projectName`.

**Preconditions.** A running MCP server (:8765) and the project open and `State=ready` (check `list_projects` first; right after a `-clean` redeploy the index rebuilds and reads can be empty/partial until `ready`). No open editor, cursor, or built BSL index is required — tags do **not** come from the BM/Configuration model. They are persisted per project in `.settings/metadata-tags.yaml` (a SnakeYAML file: `TagConstants.SETTINGS_FOLDER` + `TAGS_FILE`); the tool reads them through `TagService.getInstance().getTagStorage(project)` (an in-memory cache backed by that file). It does **not** mutate anything, so no revert is needed. On `TestConfiguration` no tags are defined, so the result is the empty-state message (this is expected, not a failure).

**Call (real, 2026-06-02).**
```
get_tags(projectName="TestConfiguration")
```
`projectName` is the only parameter and is **required** (it is the sole property in `getInputSchema()`). There is no filter/pagination — the tool always returns the full tag list for the project.

**Result.** Markdown (`ResponseType.MARKDOWN`, resource `embedded://get_tags.md`), NOT JSON. Real output from the live workspace — `TestConfiguration` has no tags, so the `tags.isEmpty()` branch fires:
```
No tags defined in project: TestConfiguration
```

**Representative populated shape** (clearly labelled — NOT observed on `TestConfiguration`, which is tag-free; this is the non-empty branch built by `GetTagsTool.getTags` + `MarkdownUtils.tableHeader/tableRow`). When the project's `.settings/metadata-tags.yaml` defines tags, the body is:
```
# Tags in project: SomeProject

| # | Name | Color | Description | Objects |
| --- | --- | --- | --- | --- |
| 1 | Critical | #FF0000 | Needs review before release | 3 |
| 2 | Legacy | #808080 | - | 0 |

**Total tags:** 2
```
Column meaning (from `Tag` + `TagStorage`):
- **#** — 1-based position in the stored list (this order also drives the `Ctrl+Alt+1-0` hotkeys in the EDT UI).
- **Name** — the tag's programmatic name (the key used by `assign`/`get_objects_by_tags`).
- **Color** — hex string, default `#808080` (gray) when none was set (`TagConstants.DEFAULT_TAG_COLOR`).
- **Description** — free text, or `-` when empty/null (the tool substitutes `-`).
- **Objects** — count of metadata FQNs assigned this tag (`storage.getObjectsByTag(name).size()`), so `0` means the tag exists but is unassigned.

**Gotchas.**
- **Empty is success, not error.** A project with no tags returns the plain string `No tags defined in project: <name>` (Markdown, no header/table, no `**Total**`). This is the normal state on `TestConfiguration`. Do NOT treat the absence of a table as a failure — assert on the literal `No tags defined in project:` substring or, for a populated project, on the `# Tags in project:` heading.
- **Tags are an EDT-MCP feature, project-local, not in the 1C model.** They live only in `.settings/metadata-tags.yaml`, are independent of the Configuration/BM model, and are NOT exported to XML. A freshly imported config (or `IRP`) will typically have none unless tags were created via the EDT-MCP UI / assign tools. So an empty result on any project is plausible — confirm by checking whether that file exists rather than assuming a bug.
- **Structured error contract (distinct from the Markdown success/empty paths).** Genuine failures come back as `ToolResult.error(...)` JSON — `{"success":false,"error":"…"}` with `isError:true` — even though both the success and the empty-state paths are plain Markdown. Triggers, in order: missing/empty `projectName` → `"Project name is required"`; project not `ready` → the `ProjectStateChecker.checkReadyOrError` message (state still `building`/unavailable); project absent from the workspace → `"Project not found: <name>"`; any unexpected exception → `"Error getting tags: <message>"` (also logged via `Activator.logError`). Note the success/empty Markdown has **no** `success` field — never key off a JSON flag for the normal output.
- **`Objects` is a count, not a list.** To see *which* objects carry a tag, use `get_objects_by_tags` — `get_tags` only reports the per-tag total.
- **No bilingual concern.** Tag names, colors, descriptions, and the fixed English table headers are all literal user/EDT-MCP strings — there is no 1C metadata TYPE token (Catalog/Справочник), no synonym, and no `getLanguageCode()` lookup involved. Nothing here is translated.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the message or table), do NOT retry-spam — the empty-state line and a dropped echo can look similar. Re-verify independently: inspect `.settings/metadata-tags.yaml` under the project, and the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response). Trust those over a dropped echo.
