Export complete BSL measurements or import persisted native EDT results. `mode` defaults to `export`; `exportFormat` defaults to `csv`. Use `get_profiling_results` with `mode=list` to choose a UUID; export defaults to the latest session date. Times are in **ms** with three decimals; percentages use two decimals.

## Export and import

Both modes use the shared EDT consent gate, honor its existing preferences/headless policy, and refuse read-only Tools presets. Tool annotations describe a non-destructive, non-idempotent writer. Reading is a separate tool permission: `get_profiling_results` cannot export or import. Use paths on the **EDT host**, not the MCP client's machine.

- CSV: `{"mode":"export","measurementId":"<uuid>","exportPath":"C:/temp/before.csv"}`. The absolute file must not exist and its parent must exist. UTF-8 with BOM, RFC 4180 quotes and CRLF records; one row per line record, including UUID, module, method, line, calls, inclusive/pure ms, percentages, server-call signal, per-target inclusive/pure ms and full source. Exports always contain every line record; this tool has no display filters.
- Native EDT: add `"exportFormat":"edt"`; `exportPath` is a directory, created if necessary. EDT writes `<exportPath>/<measurementName>/` with XML and properties. An existing measurement subdirectory is refused; nothing is overwritten. Measurement names must be safe directory names. Incomplete files are reported as failure; inspect the EDT log and retry in a new directory.
- Import: `{"mode":"import","importPath":"C:/temp/profiling"}`. The absolute directory holds native EDT measurement folders, not a single measurement XML. Already-loaded UUIDs are skipped and reported. Different UUIDs with conflicting names are refused to protect EDT's name-based store. Legacy caches must first be exported using EDT. Import runs on the workbench UI thread when a display exists, otherwise on the worker. EDT saves before notifying listeners; even if a listener throws, success depends on observing every added UUID (with a view-refresh note on a listener failure). A partial or unobserved import is an error. Added measurements are read back by UUID; imported fixtures intentionally remain available across runs. A bounded import receipt points to paged `get_profiling_results` with `mode=list` for additional IDs.

Invalid enums, relative paths and unsafe names produce actionable errors; values are never silently coerced.

## Permissions and recovery

Allowing `get_profiling_results` grants no write permission. Enable and allow `manage_profiling_results` separately. Imported measurements persist across EDT restarts; UUID duplicates are skipped on later imports. Files use the requested host paths and are never overwritten. To remove exported evidence, delete only the export you created; manage loaded measurements through EDT.
