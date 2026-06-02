# get_problem_summary — how to test

**Purpose.** Aggregate the configuration's validation problems (EDT markers) into counts, grouped by **severity** and by **project**. Read-only — it reads `IMarkerManager.markers()` and never mutates the model. Output is two markdown tables: an `### Overall Totals` table (one row per `MarkerSeverity`) and a `### By Project` table (one row per project, with a per-severity column breakdown and a per-project Total). It is the "how healthy is this config?" bird's-eye view; for the individual problems with file/line/message use `get_project_errors`.

**Preconditions.**
- Running MCP server (`:8765`), workspace `D:\WS\EDT`. Test project: `TestConfiguration`.
- The project must be **fully indexed** before the counts are trustworthy — call `list_projects` first and require `State=ready`. Markers are produced by EDT validation; right after a `-clean` redeploy the index/validation is still rebuilding, so counts can read low or zero until validation settles.
- `projectName` is **optional**. Omit it to summarize every project in the workspace; pass it to filter to one project (validated to exist before counting).
- No open editor, no cursor, no `modulePath`. Does NOT mutate — no revert needed.

**Call (real, 2026-06-02).** Verified live against `TestConfiguration` (EDT 2026.1.1):
```
get_problem_summary(
  projectName="TestConfiguration"
)
```
To summarize the whole workspace instead, call with no arguments:
```
get_problem_summary()
```
(`projectName` is the only parameter; the input schema is `{ projectName?: string }`.)

**Result.** Markdown resource (`embedded://get_problem_summary.md`), not a JSON envelope. Real output captured from the live workspace:
```markdown
## Problem Summary

### Overall Totals

| Severity | Count |
|----------|-------|
| ERRORS | 1 |
| BLOCKER | 2 |
| CRITICAL | 0 |
| MAJOR | 7 |
| MINOR | 4 |
| TRIVIAL | 0 |
| **TOTAL** | **14** |

### By Project

| Project | Errors | Blocker | Critical | Major | Minor | Trivial | Total |
|---------|--------|---------|----------|-------|-------|---------|-------|
| TestConfiguration | 1 | 2 | 0 | 7 | 4 | 0 | 14 |
```
Structure (from `GetProblemSummaryTool`):
- **`### Overall Totals`** — one row per `MarkerSeverity` value in enum order (`ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL`), then a bold `**TOTAL**` row. `NONE` is shown only if its count is non-zero (skipped when zero — almost always skipped). The `TOTAL` here is the grand sum over **all** severities present (so if `NONE` markers existed they would be included in this grand total even though the column is hidden).
- **`### By Project`** — fixed columns `Project | Errors | Blocker | Critical | Major | Minor | Trivial | Total`. The per-project `Total` is `sumDisplayedSeverities(...)` = the sum of exactly those six displayed columns (it deliberately **excludes** `NONE`, so the per-project Total always equals the visible columns).
- When no markers match (clean project, or filtered to a project with zero problems) the `### By Project` section is replaced by `*No problems found.*` — the `### Overall Totals` table still renders with all-zero counts and `**TOTAL** | **0**`. `TestConfiguration` ships a `CommonModules/Error` module with a deliberate syntax error, so a clean `ready` index should report at least one `ERRORS` there — a `0`/empty result usually means validation has not finished yet.

**Gotchas.**
- **Success is a plain Markdown resource, NOT a `{success:true,…}` JSON envelope.** Assert on Markdown substrings (`## Problem Summary`, `### Overall Totals`, `### By Project`, a severity name, the project name). Do not look for a JSON `success` field on the happy path.
- **Genuine-failure error contract is the structured JSON envelope.** Unlike the happy path, real failures return `ToolResult.error(...).toJson()` → `{"success":false,"error":"…"}` with `isError:true`. Triggers: `IMarkerManager` service unavailable → `"IMarkerManager service is not available"`; a `projectName` that does not exist → `"Project not found: <name>"`; any unexpected exception → the exception message (also logged via `Activator.logError`). A non-existent project is a genuine error here (structured envelope), not an informational markdown listing.
- **`State=ready` is load-bearing.** Counts are only meaningful after validation/indexing completes. Polling `get_problem_summary` while a project shows `State=building` (e.g. right after `-clean` redeploy or opening `IRP`) can return artificially low or zero counts. Re-check `list_projects` for `ready`, then re-call once.
- **Filter is an exact project-name match.** The `projectName` filter compares `marker.getProject().getName().equals(projectName)` — exact, case-sensitive, on the programmatic workspace project name (the `Name` column from `list_projects`). It is NOT bilingual and NOT a synonym; there is no metadata TYPE token here, so the ru/en resolution concerns do not apply to this tool.
- **Markers with no project are skipped.** Any marker whose `getProject()` is null is silently ignored (not counted). Severity `null` is bucketed as `NONE` (which is then hidden from totals unless non-zero).
- **Per-project Total vs grand TOTAL can differ if NONE exists.** The grand `**TOTAL**` in Overall Totals sums every severity (including `NONE`); each per-project `Total` sums only the six displayed columns. In practice `NONE` is 0, so they match — but do not assert that the grand total equals the sum of per-project Totals as an invariant.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the tables), do not retry-spam. Re-verify independently: the EDT log `D:\WS\EDT\.metadata\.log` records the full request/response, and `get_project_errors` on the same project lists the underlying markers. The call is read-only, so `git status` on `TestConfiguration` should stay clean regardless.
- **Big config (`IRP`) is slow/large.** Summarizing the whole workspace (no `projectName`) or filtering to `IRP` walks every marker in a real config and can return large counts — fine, but prefer filtering to `TestConfiguration` for a fast, deterministic representative result.
