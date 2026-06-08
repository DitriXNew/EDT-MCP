Validates 1C:Enterprise query language (QL) text in the context of a project, using EDT's Xtext QL infrastructure. It parses the query and runs full semantic validation, so it catches both grammar errors and references to tables/fields that do not exist in the project's metadata. The query is validated only; nothing is written to the model or executed.

## When to use
- Before pasting a query string into a BSL module, to confirm it parses and all referenced metadata resolves.
- To diagnose a query that fails at runtime: the returned line/column points at the offending token.
- For queries that drive a Data Composition Schema (set `dcsMode=true`).

## Parameter details
- `projectName` (required) - EDT project name; the query is resolved against this project's metadata, so table and field names must exist there.
- `queryText` (required) - the complete query text. Query parameters (`&SearchString`) are allowed and need not be bound. Example: `SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString`.
- `dcsMode` - default `false`. Set `true` only for queries used inside a Data Composition Schema; this enables DCS-specific syntax (e.g. `{...}` braces, dataset fields) that a plain query would reject, and is echoed back as `dcsMode` in the result.

## Bilingual (ru/en) notes
The QL parser is dialect-aware: both the English and Russian forms of keywords and table-type tokens are accepted (e.g. `SELECT ... FROM Catalog.Products` and `ВЫБРАТЬ ... ИЗ Справочник.Продукты`). Do not assume a single dialect. Metadata objects are referenced by their programmatic `Name` (not by synonym); only the type token (Catalog / Справочник) may be in either language.

## Result shape
JSON with `valid` (true when there are zero issues), `dcsMode`, `errorCount`, `warningCount`, `infoCount`, and an `issues` array. Each issue has `severity` (ERROR / WARNING / INFO), `message`, and, when located, `line`, `column` and `offset` (1-based line/column; non-positive locations are omitted).

## Examples
- Plain query: `{ "projectName": "MyProj", "queryText": "SELECT Ref FROM Catalog.Products" }`.
- DCS query: add `"dcsMode": true` for a query backing a data composition schema.

## Gotchas
- `success:true` means the tool ran; check `valid` for whether the query itself is error-free. A successful run can still report `valid:false` with issues.
- A field/table that exists in the platform but not in THIS project resolves as a semantic error - pass the project that actually owns the metadata.
- If QL language support is unavailable the tool returns an error rather than a validation result.
