Lists workspace markers - **bookmarks** (manual navigation marks) and/or **task markers** (TODO / FIXME / XXX / HACK comments) - in one report. The single tool for "where are the TODOs / my bookmarks" across a project.

## When to use
- Find all TODO/FIXME/XXX/HACK comments in a project (optionally only high-priority ones).
- List bookmarks you placed for navigation.
- Audit outstanding work markers before a release.

## Parameter details
- `markerKind` - `bookmark` or `task`. **Omit to list both.**
- `projectName` - limit to one project; omit to scan all open projects.
- `filePath` - case-insensitive path-substring filter.
- `priority` - `high` / `normal` / `low`, **task markers only**. Combining it with `markerKind=bookmark` is rejected with a clear message (bookmarks have no priority).
- `limit` - max rows (default 100, max 1000).

## What you get
A Markdown table: **Kind**, **Type** (BOOKMARK / TODO / FIXME / XXX / HACK), **Priority**, **Message**, **Path** (project-relative, project name is the first segment), and **Line**. A header shows the count and notes when the limit was reached.

## Notes & gotchas
- This is for bookmarks/tasks, not validation problems - for errors/warnings use `get_project_errors` (detail) or `get_problem_summary` (counts).
- Task markers come from code comments; a single BSL TODO is de-duplicated across Eclipse's two task-marker types so it's counted once.
- Hitting the limit appends a notice - narrow with `projectName`/`filePath` or raise `limit`.
