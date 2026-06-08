Literal or regex full-text search across every BSL module (`*.bsl`) under the project's `src/` folder. Returns matches with surrounding context, or a lightweight count / file list.

## When to use
- Find a literal string, comment, message, or regex pattern in BSL source.
- Scope a query to one metadata family (`metadataType`) or a path (`fileMask`).
- Use `outputMode='count'` or `'files'` first for a cheap overview before pulling full context.

## When NOT to use (ru/en dialect trap)
Matching is **purely textual and NOT dialect-aware**. A query in one BSL language will not match its other-language spelling: searching the English `Procedure` will NOT find a module written with the Russian keyword `Процедура`, and vice versa. To locate an identifier (method, variable, object) regardless of ru/en spelling, use `get_symbol_info`, `find_references` or `get_method_call_hierarchy` instead.

## Parameter details
- `projectName` (required) - EDT project name.
- `query` (required) - search string or regex; matched literally unless `isRegex=true`.
- `caseSensitive` - default `false`.
- `isRegex` - treat `query` as a Java regular expression; default `false`. An invalid pattern returns an error.
- `limit` - max matches returned with context; default 100, max 500. Counts in `count`/`files` mode are always exact regardless of `limit`.
- `maxResults` - deprecated alias for `limit` (used only when `limit` is absent).
- `contextLines` - lines before/after each match; default 2, max 5 (`full` mode only).
- `fileMask` - case-insensitive substring of the module path (e.g. `CommonModules`, `Documents/SalesOrder`).
- `metadataType` - restrict to one family by folder prefix; more precise than `fileMask`. Allowed: documents, catalogs, commonModules, informationRegisters, accumulationRegisters, reports, dataProcessors, exchangePlans, businessProcesses, tasks, constants, commonCommands, commonForms, webServices, httpServices, enums, chartsOfCharacteristicTypes, chartsOfAccounts, chartsOfCalculationTypes. An unknown value returns an error.

## Output modes (`outputMode`)
- `full` (default) - matches grouped by file with `contextLines` of context, capped at `limit`.
- `count` - only the total match and file counts; fastest.
- `files` - one row per file with its per-file match count; no context.

## Examples
- Literal search: `{projectName, query: "FixedDate"}`.
- Regex, case-sensitive: `{projectName, query: "Sum\\d+", isRegex: true, caseSensitive: true}`.
- Scoped count: `{projectName, query: "Export", metadataType: "commonModules", outputMode: "count"}`.
- File overview: `{projectName, query: "TODO", outputMode: "files"}`.

## Notes & gotchas
- Searches `src/` only; a project without a `src/` folder returns an error.
- Only `.bsl` files are scanned (no form/query/XML files).
- Each match is a single line; a pattern spanning multiple lines won't match.
- Unreadable files are skipped and reported as a warning, not an error.
- `fileMask` and `metadataType` combine (both must match).
