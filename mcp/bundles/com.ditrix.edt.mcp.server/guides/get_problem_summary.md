Validation problem COUNTS grouped by severity and by project - the at-a-glance health check - or, with `groupBy`, by check, module or object. Returns a Markdown summary; for the actual per-problem messages, files and line numbers, use `get_project_errors` instead.

## When to use
- A fast "is this project clean?" check, or to see where errors are concentrated before drilling in.
- After a clean / build / edit, to confirm the problem count went down.
- "Which checks fire most / which modules are worst" on a large configuration: `groupBy` answers in one short table instead of paging through thousands of `get_project_errors` rows.

## Parameter details
- `projectName` - which project. **Omit to summarize every project.**
- `groupBy` - `check`, `module` or `object`: one table of groups, sorted by count (descending), then by key. `check` rows carry the check's most severe severity, `Fix registered` and how many modules it hits; `module` and `object` rows name their top 3 checks. `module` puts every problem without a BSL module into one `-` row; `object` keys by the top object in canonical English (`Справочник.X.Форма.Y.Форма` and `Catalog.X.Form.Y.Form` both count under `Catalog.X`, so the key can be passed to `get_project_errors` `objects`), and markers with an unresolvable location form one `<unresolved>` row.
- `top` - how many groups to show (default 25, max 1000). The rest is folded into ONE `*(N other groups)*` row, so the Count column always adds up to the Total line. Only valid with `groupBy`.
- `severity`, `checkId`, `modulePaths` - the same filters as `get_project_errors` (`severity` exact, `checkId` substring of the symbolic id or short UID, `modulePaths` exact paths as in its Module path column). They narrow either report; without `groupBy` they narrow the totals tables, and the active filters are echoed on one line under the title. With `projectName`, a `modulePaths` entry that names no module is reported as not found. `severity` `NONE` is the level of a marker EDT stores without a severity, not "all levels"; when a severity leaves nothing while the other filters match problems at other levels, the report names those levels with their counts.

## What you get
Without any of the parameters above except `projectName`: Markdown with an **Overall Totals** table (one row per severity - ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL - plus a TOTAL) and, when more than one project is included, a **By Project** table with the same severity columns. When there are no problems it reports "No problems found." With `groupBy`: `## Problem Summary (by check|module|object)`, a `**Total:** N problems in G groups` line and the groups table.

## Examples
- Drill down in two calls: `{projectName: "MyConfig", groupBy: "module", top: 10}`, then `{projectName: "MyConfig", groupBy: "check", modulePaths: ["CommonModules/Sales/Module.bsl"]}`.
- Every check code with its count, then the modules of one check: `{projectName: "MyConfig", groupBy: "check", top: 1000}`, then `{projectName: "MyConfig", groupBy: "module", checkId: "ql-temp-table-index"}`; then list those problems with `get_project_errors` and the same `checkId` + `modulePaths`.

## Notes & gotchas
- Counts only - it never lists the individual problems. Follow up with `get_project_errors` for the detailed markers (file, line, message), or `get_markers` for bookmarks/tasks.
- The severities are EDT's own marker severities, not a simple error/warning split.
- There is no "new since" filter: EDT re-creates every marker of a module each time it re-validates it, so a marker's time says when it was last checked, not when the problem appeared. To see what an edit changed, compare a `groupBy` summary taken before and after it.
