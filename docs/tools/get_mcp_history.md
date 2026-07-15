# get_mcp_history

Return the recorded MCP call history (this server's in-memory ring of request/response exchanges) so you can introspect your OWN traffic: which tools you called, how long they took, which failed, and what has been filling your context. Read-only (a snapshot; never mutates anything). Filters (AND): tool (substring over the tool name / method), status (all|error|ok), minDurationMs, sinceMs/untilMs (half-open epoch-ms window); newest first, capped by limit. Records are metadata only by default; set includeBodies for the raw payloads (may carry infobase data), or includeStats for the aggregated per-tool context-usage totals. Full parameters and examples: call get_tool_guide('get_mcp_history').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| tool | — | string | Case-insensitive substring match on the call key: the tool name for a tools/call exchange, otherwise the JSON-RPC method (e.g. tools/list, initialize). Omit to match every call. |
| status | — | string (one of: all, error, ok) | Outcome filter: all (default), error (only failed calls), or ok (only successful calls). Classified the same way the statistics are (a JSON-RPC error, or a tool result with isError:true / success:false). |
| minDurationMs | — | integer | Keep only calls whose duration is at least this many milliseconds (find the slow ones). Default 0 (no lower bound). |
| sinceMs | — | integer | Time-window lower bound, INCLUSIVE, as an epoch-millisecond timestamp (matches the timestampMs of a returned record). Omit for no lower bound. |
| untilMs | — | integer | Time-window upper bound, EXCLUSIVE, as an epoch-millisecond timestamp. Omit for no upper bound. The window is half-open [sinceMs, untilMs). |
| limit | — | integer | Max records to return, newest first (default 100, max 1000; clamped, never rejected). |
| includeBodies | — | boolean | Include the raw request/response JSON bodies for each record (default false = metadata only). The bodies can carry infobase / personal data; keep them off unless you need the payloads. |
| includeStats | — | boolean | Append the aggregated per-tool context-usage statistics (rows + summary + top3) for the FILTERED set of records (default false). |

## Guide
Return this server's recorded **MCP call history** — the in-memory ring of request/response exchanges captured at the transport choke point — so an AI client can introspect its OWN traffic: which tools it has called, how long they took, which failed, and (above all) **what has been filling its context window**. Read-only: it takes a snapshot of the ring and reads it; it never mutates the recorder, the history view, or anything else.

## When to use
- "What have I been calling, and what is eating my context?" — call with `includeStats: true` for the per-tool context-usage totals and the top-3 context-eaters.
- Find your slow calls: `minDurationMs: 2000`.
- Review only what failed: `status: "error"`.
- Zoom into one tool: `tool: "get_metadata_details"` (substring, case-insensitive).
- Inspect a specific exchange's raw payloads: `includeBodies: true` (see the PII note).

## How recording works
History is an in-memory **ring buffer** (bounded; default 500 exchanges), configured in *Preferences → MCP Server → History*. When the ring is full the oldest exchange is evicted. If `recording` comes back `false` in the result, recording is switched off and the ring is not being filled — nothing to introspect until you enable it. The result always echoes `recording`, `bufferSize`, and `totalRecorded` (the live ring size) so an empty page is never ambiguous.

The row **key** for a record is the tool name for a `tools/call` exchange, otherwise the JSON-RPC method itself (`tools/list`, `initialize`, `notifications/initialized`, `ping`, …) — so a non-tool method such as `tools/list` (a real context-eater) gets its own key rather than being lumped together. This is the same keying the statistics use.

## Filters (all optional, combined with AND)
- `tool` — case-insensitive **substring** of the call key (the tool name / method). Omit to match every call.
- `status` — `all` (default), `error` (only failed calls), or `ok` (only successful calls). The error/OK classification is structural (a JSON-RPC top-level `error`, or a tool result with `isError:true` / `success:false`) — the same classifier the statistics use, never a substring heuristic. An out-of-set value is rejected with an actionable error.
- `minDurationMs` — keep only calls whose duration is at least this many milliseconds. Default 0.
- `sinceMs` / `untilMs` — a **half-open** epoch-millisecond window `[sinceMs, untilMs)`: `sinceMs` is inclusive, `untilMs` is exclusive. They compare against each record's `timestampMs` (read one back from a first call to pick exact bounds). Omit either for an open bound.

## Pagination & order
- `limit` — max records returned, **newest first** (default 100, max 1000; clamped, never rejected).
- `matched` is the total number of records that passed the filters; `returned` is the size of this page (`min(matched, limit)`). To see older records, narrow the window with `untilMs` and page back.

## Bodies vs metadata
By default each record is **metadata only**: `timestampMs`, `method`, `tool` (the resolved key), `durationMs`, `status` (`ok`/`error`), and the payload sizes `requestChars` / `responseChars`. Set `includeBodies: true` to also attach the raw `requestJson` / `responseJson` (each already capped by the recorder, with a truncation marker when it was longer). Keep bodies **off** unless you need the payloads — they are large and may carry sensitive data (see below).

## Statistics (`includeStats: true`)
Appends a `stats` block aggregated over the **filtered** set (not just the returned page):
- `summary` — `totalCalls`, `totalOutputChars`, `approxTotalTokens` (+ its tilde-prefixed display), `totalErrors`.
- `rows` — one per tool/method, ordered heaviest **context weight** first: `calls`, `sharePercent`, `totalDurationMs`, `avgDurationMs`, `requestChars`/`requestWords`, `responseChars`/`responseWords`, `approxTokens` (+ display), `contextWeight`, `errorCount`.
- `top3` — the up-to-three heaviest context-eaters (a subset of `rows`).

Token figures are **approximate** (`responseChars / 4`) and are always rendered with a leading `~`; never treat them as exact.

## What you get
A JSON result (`structuredContent`):
```
{ "success": true, "matched": 42, "returned": 42, "limit": 100,
  "totalRecorded": 137, "recording": true, "bufferSize": 500,
  "records": [ { "timestampMs": 1720000000000, "method": "tools/call",
                 "tool": "get_metadata_details", "durationMs": 1234,
                 "status": "ok", "requestChars": 120, "responseChars": 8400 } ],
  "stats": { "summary": { ... }, "rows": [ ... ], "top3": [ ... ] } }
```
(`stats` is present only with `includeStats: true`; `requestJson`/`responseJson` are added to each record only with `includeBodies: true`.)

## PII note
With `includeBodies: true` the returned bodies contain **whatever the recorded tools returned**, which can include infobase data / personal data (e.g. a recorded `get_variables`, `evaluate_expression`, or `wait_for_break` response). Treat that output as sensitive. The default (metadata only) carries none of it.

## Errors (actionable)
- Invalid `status` → echoes the bad value and lists `all`, `error`, `ok`.

## Notes & gotchas
- Read-only: a snapshot only — never opens a session, never touches the model, never mutates the ring or the history view.
- An empty `records` with `recording: false` means recording is off, not that nothing happened — enable it in Preferences.
- This tool records itself: a later call will show the earlier `get_mcp_history` exchange in the history.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
