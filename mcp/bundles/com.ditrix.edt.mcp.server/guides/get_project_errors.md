Lists EDT configuration problems (validation markers: the same set EDT shows in its *Configuration Problems* view) as a Markdown table, with optional filters. All parameters are optional; with none, every problem across every project is returned (up to `limit`).

## When to use
- Triage validation errors/warnings after editing code or metadata.
- Get a structural locator (Module path + Line) for a BSL problem to feed straight into `read_module_source` or `set_breakpoint`.
- Narrow to one object (`objects`), one check (`checkId`) or one severity band (`severity`) while iterating on a fix.
- For just the totals (counts per severity, no detail) prefer `get_problem_summary`.

## Parameter details
- `projectName` - EDT project name. Omit to scan all projects. An unknown project returns an error; a project still indexing returns a not-ready error.
- `severity` - one of `ERRORS`, `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `TRIVIAL`, `NONE` (case-insensitive). An out-of-set value is rejected (the filter is never silently widened to "all"). Matches that exact severity only (it is not a >= threshold).
- `checkId` - case-insensitive substring matched against EITHER the symbolic check id (e.g. `ql-temp-table-index`) OR the short UID (e.g. `SU23`). The short UID alone is rarely what you want, so the symbolic id is matched too.
- `objects` - array of object FQNs; returns problems only from these objects. Matching is a case-insensitive substring test against the resolved object presentation.
- `limit` - max rows; default 100, max 1000. When reached, the output appends a limit-reached notice; narrow the filters to see the rest.
- `responseFormat` - `concise` (default) or `detailed`. `concise` trims tokens by dropping the secondary `Has docs` column; every actionable column (`Description`, `Location`, `Module path`, `Line`, `Check code`) and the unresolved-marker warnings are kept. `detailed` adds the `Has docs` column back (true when `get_check_description` has detail for that check). An absent/unrecognized value defaults to `concise`.

## Output columns
`Description` | `Location` | `Module path` | `Line` | `Check code` | `Has docs`. `Module path` + `Line` are populated only for problems that resolve to a `.bsl` module under `src/` (empty for metadata-only problems). `Check code` shows the symbolic id when known, else the short UID. `Has docs=true` means `get_check_description` has detail for that check (the `Has docs` column appears only with `responseFormat: detailed`).

## Bilingual (ru/en) note
The `objects` filter accepts the TYPE token in English or Russian; each FQN is expanded to all language variants before matching, so `Document.SalesOrder` and the Russian `Документ.ПродажаТоваров` both resolve. The object NAME after the dot must still be the real programmatic name, not a synonym.

## Examples
- All problems in one project: `{projectName: "MyConfig"}`.
- Errors only: `{projectName: "MyConfig", severity: "ERRORS"}`.
- One check across all projects: `{checkId: "ql-temp-table-index"}`.
- Scoped to objects: `{objects: ["Catalog.Products", "Document.SalesOrder"]}`.
- Russian type name: `{objects: ["Справочник.Номенклатура"]}`.

## Gotchas
- Markers whose location cannot be resolved are NOT dropped: without an `objects` filter they appear with a `<unresolved: project>` placeholder (a trailing warning counts them); with an `objects` filter they are excluded (membership cannot be tested) and a separate warning counts them. Run `clean_project` / `revalidate_objects` to refresh stale markers.
- `severity` matches exactly; to see everything at or above a level, omit it and read the `Check code` / severity yourself, or call once per band.
- The `objects` match is a substring of the presentation, so an overly short FQN fragment can over-match; prefer the full `Type.Name`.
