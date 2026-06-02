# validate_query — how to test

**Purpose.** Validate a 1C:Enterprise query-language (QL) string against the platform's Xtext query parser **in the context of a project**, returning a `valid` flag plus per-issue diagnostics (severity, message, line/column/offset). It loads the text into a throwaway `.qldcs` Xtext resource, collects syntax errors from the resource diagnostics, then runs full semantic validation (`CheckMode.ALL`). The platform query parser is **dialect-aware** (Russian/English keywords). Read-only — it creates a temporary in-memory resource and unloads it; it never mutates the model.

**Preconditions.** Live EDT + the target project open and indexed in the workspace (Xtext/QL infrastructure built; right after a `-clean` redeploy give the index a moment). Confirm `State=ready` via `list_projects` before trusting results. The tool runs on the **UI thread** (`Display.syncExec`) — avoid hammering it concurrently with other UI-thread tools. Requires the QL plugin (`com._1c.g5.v8.dt.ql.dcs`) present in the workbench; if absent the tool returns a structured error. No editor / cursor needed. Does not mutate; no revert required. `TestConfiguration` is sufficient (it has `Catalog.Catalog`, so a SELECT against it resolves fields semantically).

**Call (real, 2026-06-02).** Two calls — a small valid SELECT, then an intentionally broken query to exercise the error path. Params: `projectName` (required), `queryText` (required — the full 1C query text), `dcsMode` (optional boolean, default `false`; set true for Data Composition System queries to allow DCS-specific syntax).

Valid query:
```
validate_query(projectName="TestConfiguration",
               queryText="SELECT Ref, Description, Code FROM Catalog.Catalog WHERE Description LIKE &SearchString")
```

Intentionally broken query (`FORM` instead of `FROM`, garbage tail):
```
validate_query(projectName="TestConfiguration",
               queryText="SELECT FORM Catalog.Catalog WHEER")
```

**Result.** JSON (`ResponseType.JSON`). Top-level: `valid` (true iff zero issues), `success`, `dcsMode` (echoes the mode used), `errorCount`/`warningCount`/`infoCount`, and `issues` (array). Each issue: `severity` (`ERROR`/`WARNING`/`INFO`), `message`, and — only when meaningful — `line` (>0), `column` (>0), `offset` (>=0). Issues come from two sources merged into one list: resource syntax diagnostics (errors/warnings, offset always omitted i.e. `-1`) and semantic validator issues (carry `offset`).

Valid query — real output:
```json
{"valid":true,"success":true,"warningCount":0,"infoCount":0,"issues":[],"errorCount":0,"dcsMode":false}
```

Broken query — real output:
```json
{"valid":false,"success":true,"warningCount":0,"infoCount":0,"issues":[
  {"severity":"ERROR","message":"Not allowed use '.' and whitespaces in alias","line":1,"column":13,"offset":12},
  {"severity":"ERROR","message":"Field 'FORM' not found","line":1,"column":8,"offset":7}],
 "errorCount":2,"dcsMode":false}
```
Key checks: `valid` mirrors `issues == []`; `errorCount`/`warningCount`/`infoCount` are recomputed by counting `issues` severities (they always agree with the array); `1`-based `line`/`column`; the parser surfaces both a syntax-level and a semantic ("Field 'FORM' not found") diagnostic for the malformed text. `success:true` even when `valid:false` — a query with errors is a *normal, informational* result, not a tool failure (see error contract below).

**Gotchas.**
- **`valid:false` is NOT a failure.** A query with diagnostics still returns `{"success":true, ...}` with no `isError`. The structured error contract (`{success:false, error:"..."}`, `isError:true`) is reserved for genuine tool failures only: empty `projectName` ("projectName is required"), empty `queryText` ("queryText is required"), unknown project ("Project not found: <name>"), a closed project ("Project is closed: <name>"), QL plugin missing ("QlDcs language support not available…"), or an internal load/validation exception ("Failed to load query text: …" / "Validation error: …"). Do not assert a JSON error for an invalid query — assert `valid:false` + the `issues` array.
- **Param name is `queryText`, not `query`.** The schema and `execute()` both read `queryText`; passing `query` is silently ignored and you'll hit "queryText is required".
- **`dcsMode` changes the grammar.** Default `false` validates a plain query. Set `true` only for queries embedded in a Data Composition Schema — it enables DCS-specific syntax (`{...}` field/parameter braces, `ВЫБОР`/expression extensions) via `DcsValidationModeOption` + alias pre-computation. A plain query is fine in either mode; a DCS query under `dcsMode:false` will report spurious errors. The mode used is echoed back in the result.
- **Dialect-aware (bilingual).** The query parser accepts both Russian and English QL keywords (`ВЫБРАТЬ`/`SELECT`, `ИЗ`/`FROM`, `ГДЕ`/`WHERE`) and bilingual metadata type tokens (`Справочник.Catalog` / `Catalog.Catalog`); the object **NAME** segment is programmatic (use the exact `Name`). This is unlike `search_in_code` (a different family) which is literal/non-dialect-aware. Diagnostic **messages** come from the platform and may surface in the workbench's UI language.
- **Project context matters for semantics.** Field/table resolution ("Field 'X' not found", unknown `Catalog.Foo`) depends on the project's metadata being indexed — run against a `ready` project. Pure syntax errors are reported regardless, but semantic checks need the built model.
- **UI-thread + temp resource.** Each call builds a unique `mcp_validate_query_<timestamp>.qldcs` resource bound to the project and unloads it in `finally` — no file is left behind, nothing is committed. Because it's `syncExec` on the SWT display, don't fire many concurrent calls.
- **Flaky output channel.** If the JSON arrives garbled, truncated, or as a bare `Error`/`Done` instead of the payload, do NOT retry-spam. Re-verify independently via the EDT log `D:\WS\EDT\.metadata\.log` (it records the full request/response) and confirm liveness (`:8765` / `get_edt_version`), then make ONE clean call. Note Gson HTML-escapes characters like `'` and `>=` in the JSON text channel, so apostrophes in messages (e.g. `Field 'FORM' not found`) may appear as `\uXXXX` in the raw stream — match on a delimiter-free substring (`not found`), not the raw quoted form.
