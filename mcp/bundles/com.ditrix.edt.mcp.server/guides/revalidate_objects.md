Re-runs EDT validation on a project (or a chosen set of objects) and waits for it to finish, so the markers reflect the current code. It first refreshes the project from disk, so it also picks up edits made outside EDT. A lighter, more targeted alternative to `clean_project` when you just want fresh validation results.

## When to use
- After editing code/metadata, to get up-to-date errors/warnings without a full rebuild.
- You changed files on disk and want EDT to re-check just the affected objects.
- Confirming a fix actually cleared its markers.

## Parameter details
- `projectName` (required) - the EDT project.
- `objects` - array of FQNs to revalidate, e.g. `["Document.SalesOrder", "Catalog.Products", "CommonModule.Common"]`. **Omit or pass an empty array to revalidate the WHOLE project.** Russian type tokens are accepted (e.g. `Документ.ПриходнаяНакладная`, `Справочник.Номенклатура`); only the leading type token is bilingual, the object name is its programmatic `Name`.

## What you get
Markdown with a YAML front-matter summary (tool, status, project, mode `full`/`objects`, counts) and, for object mode, sections listing which FQNs were **Validated**, **Not found**, and **Skipped (no persistent id)**.

## Notes & gotchas
- Full-project mode runs an incremental build and can be heavy on a large configuration; object mode is targeted and faster.
- A project that is still building is refused with a clear message - wait for it to settle.
- After it returns, read the results with `get_problem_summary` (counts) or `get_project_errors` (per-marker detail). To recover from a genuinely stuck/stale state, use `clean_project` instead (it discards unsaved in-memory edits).
