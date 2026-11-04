# validate_query

Check a 1C query or stored BSL query literals for syntax and metadata-reference errors. Parameters and examples: get_tool_guide('validate_query').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| queryText | — | string | Full 1C query text, e.g. 'SELECT Ref FROM Catalog.Products'. Pass exactly one of queryText or modulePath. |
| modulePath | — | string | Stored BSL path from src/, e.g. 'CommonModules/MyModule/Module.bsl'. Exclusive with queryText; omit method to scan the whole module. |
| method | — | string | Procedure/function name (case-insensitive); only with modulePath. |
| dcsMode | — | boolean | true for Data Composition System queries (allows DCS-specific syntax). Default: false. |

## Guide
Validates 1C queries with EDT's native Xtext parser and semantic checks, reporting grammar errors and invalid table/field references. Configuration extensions use the union of their own metadata and the base configuration. Nothing is written or executed.

## When to use
- Before pasting a query string into a BSL module, to confirm it parses and all referenced metadata resolves.
- To diagnose a query that fails at runtime: the returned line/column points at the offending token.
- For queries that drive a Data Composition Schema (set `dcsMode=true`).

## Parameter details
- `projectName` (required) - EDT project name. A configuration project validates in its own model. A configuration extension validates in a union scope containing its own objects and the base configuration's objects, including inherited fields of borrowed objects.
- Pass exactly one of `queryText` or `modulePath`. `queryText` is complete query text, passed unchanged to the shared validator. Parameters (`&SearchString`) need not be bound. Example: `SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString`.
- `modulePath` - stored BSL path from `src/`, e.g. `CommonModules/MyModule/Module.bsl` (also accepts workspace absolute paths, like other module tools). Uses saved file text, never an unsaved editor buffer. Use `list_modules` to discover paths.
- `method` - optional procedure/function name, case-insensitive in either language; only with `modulePath`. Omit it to scan the whole module, including module-level literals. Use `get_module_structure` for names.
- `dcsMode` - default `false`. Set `true` only for queries used inside a Data Composition Schema; this enables DCS-specific syntax (e.g. `{...}` braces, dataset fields) that a plain query would reject, and is echoed back as `dcsMode` in the result.

## Bilingual (ru/en) notes
The QL parser is dialect-aware: both the English and Russian forms of keywords and table-type tokens are accepted (e.g. `SELECT ... FROM Catalog.Products` and `ВЫБРАТЬ ... ИЗ Справочник.Продукты`). Do not assume a single dialect. Metadata objects are referenced by their programmatic `Name` (not by synonym); only the type token (Catalog / Справочник) may be in either language.

## Result shape
Text mode returns JSON with `validationScope` (the project and, for a linked extension, its base),
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
- Stored method: `{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl", "method": "BuildQuery" }`.

## Stored-module extraction
BSL AST string-literal nodes supply candidates; the platform literal-text helper removes quotes and `|` continuation framing and unescapes `""`. Each candidate's first token after leading whitespace must be `SELECT` or `ВЫБРАТЬ` (case-insensitive). After that token it must have a nonempty projection before a standalone `FROM`/`ИЗ`, `INTO`/`ПОМЕСТИТЬ`, or `AS`/`КАК`, or its projection must start with a digit, `*`, or `&` followed by an identifier. Structural words inside QL strings, comments, or qualified identifiers do not count. This is deliberately narrow: `"Выбрать файл"` and `"SELECT file"` are skipped for `no query structure`; some legal queries without these signals require `queryText` mode. Syntax validity is decided only by `QueryValidationService`, including malformed queries that pass this structural filter.

Literals directly concatenated with `+` on either side (including parenthesized operands), and adjacent BSL string parts, are skipped as `dynamic`. No variable substitution, concatenation folding, or runtime text reconstruction is attempted. Broken stored BSL is refused with a line and a `queryText` fallback rather than silently scanning recovered fragments.

Module mode returns `queries`, `skipped`, `foundCount`, and `skippedCount`. Each entry has `method` (omitted outside methods), and 1-based module `line`/`column` at the opening quote. Validated entries carry the same shared verdict, DCS mode, source census and issues as text mode. Issue `line`/`column`/`offset` refer to the stored module; `queryLine`/`queryColumn`/`queryOffset` retain native query coordinates when supplied. Mapping accounts for continuation framing, escaped quotes and intervening physical lines. Source-census offsets remain relative to decoded QL. Skipped entries carry `reason`; unrelated strings are ignored. Counts describe extraction, never a completeness claim about runtime queries. When no candidates exist the response says `no query literal found` and points to `queryText`.

## Gotchas
- `success:true` means validation ran, not that queries are valid. Check the top-level verdict in text mode, or each `queries[]` verdict in module mode; module mode has no aggregate `valid`. Skips/counts do not prove coverage of runtime-generated queries.
- For an extension project, the union scope resolves both extension-owned objects and base-configuration objects, including inherited fields of borrowed objects.
- If QL language support is unavailable the tool returns an error rather than a validation result.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
