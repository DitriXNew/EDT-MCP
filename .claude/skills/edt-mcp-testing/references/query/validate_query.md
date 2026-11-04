# validate_query — acceptance procedure

Use a ready, indexed project with EDT BSL/QL support. This is read-only; inspect `structuredContent` and leave the fixture unchanged.

## Text mode

```text
validate_query(projectName="TestConfiguration", queryText="SELECT Ref FROM Catalog.Catalog")
validate_query(projectName="TestConfiguration", queryText="SELECT FORM Catalog.Catalog WHEER")
```

Require a complete `VALID` verdict for the first query and proven `INVALID` issues for the second. `success:true` means validation ran; `valid` is true only for complete validation without errors. Warnings alone do not invalidate it. Unresolved sources can yield `PARTIAL`; inspect `validationComplete`, `unresolvedSources`, `sources` and per-issue severity. A linked extension uses its own metadata plus the base configuration.

## Saved-module mode

Create a dedicated temporary CommonModule with `create_metadata` and save its source using `write_module_source`. Include a `BuildQuery` method containing a valid SELECT, a malformed static query, a SELECT-leading concatenation and a string such as `"SELECT file"`. Include a second method without queries. Use continuation lines and escaped quotes in the static literals. Do not change shared fixture modules or their search counts.

```text
validate_query(projectName="TestConfiguration", modulePath="CommonModules/QueryProbe/Module.bsl")
validate_query(projectName="TestConfiguration", modulePath="CommonModules/QueryProbe/Module.bsl", method="buildquery")
```

- Read saved BSL, never an unsaved editor buffer. Omit `method` to include module-level literals; method lookup is case-insensitive.
- Require `queries`, `skipped`, `foundCount`, `skippedCount`; there is no aggregate `valid`. Inspect each query's shared validation verdict and issues.
- Require 1-based module `line`/`column` and module `offset` on mapped issues; `queryLine`/`queryColumn`/`queryOffset` retain native coordinates when supplied. Source-census offsets stay relative to decoded QL.
- Require `dynamic` for concatenations/adjacent string parts and `no query structure` for keyword-leading nonqueries. Extraction accepts only standalone SELECT/ВЫБРАТЬ literals with the documented structural signals; it does not evaluate variables or runtime substitutions.
- The empty method returns zero counts and a `no query literal found` message pointing to `queryText`. Counts describe extraction, not runtime-query coverage.
- Repeat with Russian BSL/query keywords and programmatic method/object Names. Synonyms are not identifiers. `dcsMode:true` uses the same DCS validator in both input modes.

## Refusals and cleanup

Pass both/neither of `queryText` and `modulePath`, `method` without `modulePath`, blank/missing methods, nonexistent paths, and malformed stored BSL. Require actionable structured errors naming the input and a discovery tool or `queryText` fallback. A proven invalid query is an informational verdict, not a tool error; unavailable native validation is an error.

For executable assertions use `tests/e2e/tools/test_validate_query.py`. Revert the temporary module through the orchestrator's fixture/model reset; confirm no fixture changes remain. For resource/schema details use `get_tool_guide('validate_query')`.
