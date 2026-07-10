# get_event_log

Read a 1C infobase event log WITHOUT a running 1C session by parsing the raw log files (legacy text ver 2.0: a 1Cv8.lgf dictionary + dated *.lgp partitions). High diagnostic value: errors, user actions, posting, authentication. Locate the log by projectName (+ optional applicationId from get_applications for a FILE infobase), or pass logDir directly for an offline copy or a SERVER-mode infobase. Filters: from/to period, user, event, eventContains, severity, commentContains, metadataContains, session; paginated (limit/offset/order). Returns infobase PII (user names, data presentations). Full parameters and examples: call get_tool_guide('get_event_log').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | EDT configuration project whose infobase event log to read. Provide this (optionally with applicationId) OR logDir. |
| applicationId | — | string | Application id from get_applications, to pick ONE infobase when the project has several. Optional; ignored when logDir is given. |
| logDir | — | string | Absolute path to a 1Cv8Log directory to read directly (an offline copy, or a SERVER-mode infobase whose cluster path is not model-derivable). Overrides projectName/applicationId resolution. |
| from | — | string | Period lower bound, ISO-8601 (e.g. 2026-04-01 or 2026-04-01T09:00:00). Omit for no lower bound. |
| to | — | string | Period upper bound, ISO-8601. Omit for no upper bound. |
| user | — | array | Filter by user name(s) (exact). JSON array ["Ivanov"] or comma-separated. |
| event | — | string | Filter by exact event id/name (e.g. _$Session$_.Authentication). Use eventContains for a substring match instead. |
| eventContains | — | string | Case-insensitive substring match on the event name. |
| severity | — | array | Filter by severity. Accepts I/W/E/N, the English words Information/Warning/Error/Note, or their Russian equivalents. JSON array or comma-separated. |
| commentContains | — | string | Case-insensitive substring match on the event comment. |
| metadataContains | — | string | Case-insensitive substring match on the metadata full name as stored in the log (e.g. Document.SalesOrder), not a localized synonym. |
| session | — | string | Filter by session number. |
| limit | — | integer | Max events to return (default 100, max 1000). |
| offset | — | integer | Number of matching events to skip before this page (pagination; default 0). |
| order | — | string (one of: date_asc, date_desc) | Result order by date: date_asc (oldest first, default) or date_desc (newest first). |

## Guide
Read a 1C infobase **event log** (журнал регистрации) WITHOUT a running 1C session, by parsing the raw log files directly with Java. The single tool for "what happened in this infobase" — errors, user actions, document posting, authentication — for diagnostics.

## When to use
- Investigate an error or an unexpected change: filter by `severity=Error` over a period.
- Audit a user's actions (`user`) or a specific event kind (`event` / `eventContains`).
- Trace what a session did (`session`), or find every mention of a metadata object (`metadataContains`).

## How the log is located
Provide **one** of:
- `projectName` (+ optional `applicationId`) — resolves a **FILE** infobase's `<db>/1Cv8Log` directory from the EDT project model. If the project has several infobases, pass `applicationId` (from `get_applications`) to disambiguate; the error lists the candidate ids when it is ambiguous.
- `logDir` — an absolute path to a `1Cv8Log` directory, read directly. Use it for an **offline / off-host copy** of a log, or for a **SERVER-mode** infobase (the cluster log path is not derivable from the EDT model, so `logDir` is required there). `logDir` overrides `projectName`/`applicationId`.

The resolved directory is echoed back as `resolvedLogDir`, and `infobaseType` reports how it was found (`FILE` / `SERVER` / `OVERRIDE`).

## Supported format (v1)
- **Legacy text "ver 2.0"** only: a `1Cv8.lgf` references dictionary plus dated `*.lgp` record partitions (UTF-8). This is the format a FILE infobase writes by default when the log mode is "text".
- A modern **SQLite `.lgd`** log (a directory with a `.lgd` file and no `1Cv8.lgf`) is **not supported yet** — the tool returns an actionable error rather than an empty result (the SQLite driver is not bundled in EDT; deferred to a later version). Switch the infobase's log to text mode, or read the `.lgd` with another tool.

## Filters
- `from` / `to` — ISO-8601 period bounds (`2026-04-01` or `2026-04-01T09:00:00`). Omit either for an open bound. Partitions fully outside the window are skipped without reading.
- `user` — array of user names (exact). JSON array `["Иванов"]` or comma-separated.
- `event` — exact event id/name (e.g. `_$Session$_.Authentication`, `_$Data$_.Post`).
- `eventContains` — case-insensitive substring of the event name (use instead of `event` for a fuzzy match).
- `severity` — array; accepts one-letter `I`/`W`/`E`/`N`, the English words `Information`/`Warning`/`Error`/`Note`, or the Russian `Информация`/`Предупреждение`/`Ошибка`/`Примечание`. Normalised to the one-letter code internally.
- `commentContains` — case-insensitive substring of the event comment.
- `metadataContains` — case-insensitive substring of the metadata **full name** as stored in the log (e.g. `Document.SalesOrder`, or the localized `Документ.ЗаказПокупателя` token on a Russian configuration) — **not** a localized synonym like "Заказ покупателя".
- `session` — session number.

## Pagination
- `limit` — max events returned (default 100, max 1000; clamped, never rejected).
- `offset` — number of matching events to skip.
- `order` — `date_asc` (oldest first, default) or `date_desc` (newest first).

`matched` is the total number of records that passed the filters; `returned` is the size of this page; `scanned` is how many raw records were read. If the scan hits its internal cap, `truncated` is `true` and `matched`/`scanned` are lower bounds — narrow the `from`/`to` window and page through.

## What you get
A JSON result (`structuredContent`):
```
{ "success": true, "resolvedLogDir": "...", "infobaseType": "FILE", "format": "text-2.0",
  "matched": 1234, "scanned": 5000, "returned": 100, "limit": 100, "offset": 0,
  "truncated": false,
  "events": [ { "date": "2026-04-06T12:34:56", "severity": "E",
                "severityPresentation": "Error", "user": "Иванов", "computer": "PC-01",
                "application": "1CV8C", "event": "_$Data$_.Post", "comment": "...",
                "metadata": "Document.SalesOrder", "metadataUuid": "a1b2c3d4-....",
                "session": 42, "data": "..." } ] }
```

Per-event fields: `severity` is the one-letter code (`I`/`W`/`E`/`N`) and `severityPresentation` its English word (`Information`/`Warning`/`Error`/`Note`). `metadata` is the metadata **full name** as stored in the log (e.g. `Document.SalesOrder`) with its `metadataUuid` — the log does **not** store a localized metadata synonym, so no `metadataPresentation` is emitted. `data` is the event's data value or presentation (a scalar such as an error message surfaces its value; a reference surfaces its caption) and may carry PII; it is omitted when the record has no data.

## PII note
The event log **contains personal data**: user names, computer names, and value presentations in `data`. Treat this output as sensitive. This tool is a `returnsInfobaseData` source for the planned PII-redactor (#242); v1 does **not** redact.

## Errors (actionable)
- Neither `projectName` nor `logDir` given → tells you to provide one (points at `list_projects`).
- Invalid `order` → echoes the bad value and lists `date_asc`, `date_desc`.
- Project not found / closed, or infobase ambiguous → names the value and steers to `list_projects` / `get_applications`.
- Resolved directory has no `1Cv8Log` / no `1Cv8.lgf` → names the path that was checked.
- SERVER-mode infobase without `logDir` → asks for an explicit `logDir`.
- SQLite `.lgd`-only log → "not supported" with the reason.

## Notes & gotchas
- Read-only: never opens a session, never touches the model, never mutates the project — pure file read.
- For **validation** problems (errors/warnings on the configuration source) use `get_project_errors` / `get_problem_summary` instead — those are EDT markers, not the infobase runtime log.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
