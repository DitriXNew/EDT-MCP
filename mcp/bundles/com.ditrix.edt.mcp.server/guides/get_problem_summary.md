Validation problem COUNTS grouped by severity and by project - the at-a-glance health check. Returns a Markdown summary; for the actual per-problem messages, files and line numbers, use `get_project_errors` instead.

## When to use
- A fast "is this project clean?" check, or to see where errors are concentrated before drilling in.
- After a clean / build / edit, to confirm the problem count went down.

## Parameter details
- `projectName` - which project. **Omit to summarize every project.**

## What you get
Markdown with an **Overall Totals** table (one row per severity - ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL - plus a TOTAL) and, when more than one project is included, a **By Project** table with the same severity columns. When there are no problems it reports "No problems found."

## Notes & gotchas
- Counts only - it never lists the individual problems. Follow up with `get_project_errors` for the detailed markers (file, line, message), or `get_markers` for bookmarks/tasks.
- The severities are EDT's own marker severities, not a simple error/warning split.
