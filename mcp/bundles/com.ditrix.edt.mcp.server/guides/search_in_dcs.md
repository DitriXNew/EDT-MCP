Literal or regex search across every Data Composition Schema file (`*.dcs`) under the project's `src/` folder - the serialized report / data-processor composition schemas. Returns matches with surrounding context, or a lightweight count / file list.

## When to use
- Find where a field, dataset, query fragment, calculated/total expression, parameter, or a setting (selection / order / filter / variant) is used across report schemas.
- Audit reports: e.g. every schema that filters by a given field, or whose query hits a given table.
- Use `outputMode='count'` or `'files'` first for a cheap overview before pulling full context.

## What it searches
The `.dcs` is serialized XML, so this is a textual scan of that XML. It finds anything the schema stores: `<query>` text, `<dataPath>` / `<field>` field paths, `<expression>` of calculated / total fields, `<parameter>` names, and the settings (`<dcsset:selection>` / `<dcsset:order>` / `<dcsset:filter>` items, `<comparisonType>`, `<orderType>`, right-hand values, `<settingsVariant>` names). Matching is **purely textual and NOT ru/en dialect-aware**; each match is a single line (a pattern spanning lines will not match).

To EDIT a schema (add datasets / fields / parameters / calculated fields) use `modify_metadata`'s `dcs` payload on the owning Report FQN (currently the only supported DCS authoring surface) - not this tool.

## Parameter details
- `projectName` (required) - EDT project name.
- `query` (required) - search string or regex; matched literally unless `isRegex=true`.
- `caseSensitive` - default `false`.
- `isRegex` - treat `query` as a Java regular expression; default `false`. An invalid pattern returns an error.
- `limit` - max matches returned with context; default 100, max 500. Counts in `count`/`files` mode are always exact regardless of `limit`.
- `contextLines` - lines before/after each match; default 1, max 5 (`full` mode only).
- `fileMask` - case-insensitive substring of the `.dcs` path (e.g. `Reports/Sales`, or a report name).

## Output modes (`outputMode`)
- `full` (default) - matches grouped by file with `contextLines` of context (XML), capped at `limit`.
- `count` - only the total match and file counts; fastest.
- `files` - one row per `.dcs` file with its per-file match count; no context.

## Examples
- Find a field across all schemas: `{projectName, query: "Amount"}`.
- Every schema filtering on a field (regex): `{projectName, query: "<dcsset:left[^>]*>Contractor<", isRegex: true}`.
- Reports whose query hits a table: `{projectName, query: "FROM Document.SalesOrder", outputMode: "files"}`.
- Scoped count: `{projectName, query: "orderType", fileMask: "Reports/Sales", outputMode: "count"}`.

## Notes & gotchas
- Searches `src/` only; a project without a `src/` folder returns an error.
- Only `.dcs` files are scanned. A DCS template with no content has no `.dcs` on disk, so it is not searchable until it has a schema.
- Unreadable files are skipped and reported as a warning, not an error.
