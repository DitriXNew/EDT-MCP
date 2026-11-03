Validates 1C queries with EDT's native Xtext parser and semantic checks, reporting grammar errors and invalid table/field references. Configuration extensions use the union of their own metadata and the base configuration. Nothing is written or executed.

## When to use
- Before pasting a query string into a BSL module, to confirm it parses and all referenced metadata resolves.
- To diagnose a query that fails at runtime: the returned line/column points at the offending token.
- For queries that drive a Data Composition Schema (set `dcsMode=true`).

## Parameter details
- `projectName` (required) - EDT project name. A configuration project validates in its own model. A configuration extension validates in a union scope containing its own objects and the base configuration's objects, including inherited fields of borrowed objects.
- `queryText` (required) - the complete query text. Query parameters (`&SearchString`) are allowed and need not be bound. Example: `SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString`.
- `dcsMode` - default `false`. Set `true` only for queries used inside a Data Composition Schema; this enables DCS-specific syntax (e.g. `{...}` braces, dataset fields) that a plain query would reject, and is echoed back as `dcsMode` in the result.

## Bilingual (ru/en) notes
The QL parser is dialect-aware: both the English and Russian forms of keywords and table-type tokens are accepted (e.g. `SELECT ... FROM Catalog.Products` and `ВЫБРАТЬ ... ИЗ Справочник.Продукты`). Do not assume a single dialect. Metadata objects are referenced by their programmatic `Name` (not by synonym); only the type token (Catalog / Справочник) may be in either language.

## Result shape
JSON with `validationScope` (the project and, for a linked extension, its base),
`validationState` (`VALID`, `INVALID`, or `PARTIAL`), `validationComplete`,
`unresolvedSources`, `sources`, and `valid` (true only for `VALID`). `INVALID` means
EDT proved an error; `PARTIAL` means source/field resolution could not be completed.
A missing future source is `PARTIAL`; independent proven errors still make the result
`INVALID`. Native lookup diagnostics depending on that unresolved source are returned
as warnings with `validationIncomplete:true` and their original `nativeSeverity`.
Warnings and informational diagnostics alone do not invalidate a complete query.

The `sources` census records native DBView resolution and computed `availableFields`
with both `name` and `nameRu`. EDT supplies virtual-table fields, including fields
computed from table parameters. No query is executed and no field catalogue is
maintained by this tool. Issue `origin` distinguishes resource diagnostics from the
native validator. The result also includes `dcsMode`, `errorCount`, `warningCount`, `infoCount`, and an `issues` array. Each issue has `severity` (ERROR / WARNING / INFO), `message`, and, when located, `line`, `column` and `offset` (1-based line/column; non-positive locations are omitted).

## Examples
- Plain query: `{ "projectName": "MyProj", "queryText": "SELECT Ref FROM Catalog.Products" }`.
- DCS query: add `"dcsMode": true` for a query backing a data composition schema.

## Gotchas
- `success:true` means the tool ran; check `valid` for whether the query itself is error-free. A successful run can still report `valid:false` with issues.
- For an extension project, the union scope resolves both extension-owned objects and base-configuration objects, including inherited fields of borrowed objects.
- If QL language support is unavailable the tool returns an error rather than a validation result.
