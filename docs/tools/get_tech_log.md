# get_tech_log

Analyze 1C JSON technological logs: problems first, then details and correlated chains; describe explains events and capture profiles. Absent events do not prove absence of a problem. Full parameters and examples: call get_tool_guide('get_tech_log').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| mode | — | string (one of: describe, summary, details, chain, events) | Default summary; describe needs no path. |
| path | — | string | Absolute JSON .log/.log.zip file or directory; required except describe. |
| event | — | string | Event reference in describe, exact event filter in events. |
| property | — | string | Property reference in describe; property name for contains in events. |
| profile | — | string | Capture profile reference in describe. |
| problem | — | string | Problem reference in describe; ids come from summary. |
| groupId | — | string | Group id from summary; required in details. |
| occurrenceId | — | string | Occurrence id from details/events; required in chain. |
| minDurationMs | — | integer | Minimum event duration in milliseconds; default 0. |
| contains | — | string | Case-insensitive substring in property; events only. |
| session | — | string | Exact SessionID filter; events only. |
| from | — | string | ISO completion/interval lower bound; no timezone means platform local time. |
| to | — | string | ISO interval upper bound; same clock as log timestamps. |
| limit | — | integer | Page size, default 100, maximum 1000. |
| offset | — | integer | Zero-based page offset, default 0. |
| readBudgetMb | — | integer | Expanded-byte budget, default 64 MB, range 1..512. |
| readBudgetSeconds | — | integer | Read time budget, default 10 seconds, range 1..30. |

## Guide
## Guide

Read a 1C **technological log**, distinct from the infobase event log (`get_event_log`). No running 1C session or database is needed. JSON-lines logs require platform 8.3.25+; a legacy text log is refused with a JSON configuration example.

### Investigation workflow

1. Use profiling to identify an expensive reproducible operation.
2. Call `get_tech_log` with `mode="describe"`; choose a profile with `profile="db"`, `"locks"`, `"slow-calls"`, `"errors"`, `"memory"` or `"scenario"`.
3. Use `manage_tech_log` preview, then consent-gated start; reproduce once briefly and stop. It writes capture configuration only to the CURRENT Windows user's `%LOCALAPPDATA%\1C\1cv8\conf\logcfg.xml`, capturing THIS user's processes on THIS machine. For server clusters or service accounts, use `mode=preview`, place the XML in that server's conf directory by hand and remove it afterwards; collect all relevant nodes.
4. Call summary with the capture directory. Problems precede coverage; each row has a `groupId` and `problemId`.
5. Explain a finding using `mode="describe", problem="slow-db"`. Request details with the same path/filters and `groupId`; use an `occurrenceId` in chain.
6. Inspect the evidence and missing data, fix the demonstrated cause, then re-measure the same scenario and data volume.

### Examples

```json
{"mode":"describe","event":"TLOCK"}
{"mode":"describe","property":"duration"}
{"path":"D:/TJ/capture","limit":20}
{"mode":"details","path":"D:/TJ/capture","groupId":"<from-summary>","limit":10,"offset":0}
{"mode":"chain","path":"D:/TJ/capture","occurrenceId":"<from-details>"}
{"mode":"events","path":"D:/TJ/capture","event":"CALL","minDurationMs":1000,"property":"Context","contains":"Report","session":"7","from":"2026-11-15T23:40:00","to":"2026-11-15T23:45:00"}
```

### Reading results

Event times are local to the machine that wrote the log. Capture start/stop times are displayed in the current machine's local zone with its UTC offset; the manifest keeps UTC instants. Chain offsets are measured from the selected anchor's start, including across pages.

JSON values are strings, names are compared case-insensitively, UTF-8 BOM and escaped multiline values are supported. Unknown event kinds remain available through events. Incomplete final lines and corrupted lines are counted separately, with corrupt samples. Zip is read in place without extracting files. Discovery includes `.log`, `.log.zip` and platform size rotations such as `.log.1`/`.log.1.zip` recursively; the same filter applies to zip members, so capture manifests and other non-log files are skipped. A single file may be supplied explicitly.

Durations in records are microseconds, displayed as **ms**. XML `duration` is in hundreds of microseconds; capture generation uses `durationus`. Missing properties are unknown, not zero. SQL grouping replaces literals/parameters and renumbers temporary tables by first appearance, preserving different table roles; a shared fingerprint does not prove an identical execution plan. Full original SQL, properties, context and plan remain available in details/events. SDBL without SQL remains raw evidence; transaction boundaries are not counted as statements.

When PII redaction is enabled, tool output is masked by the configured value-content rules (`VALUE` and the value side of `BOTH`), including emails in Markdown details. Rules scoped only to JSON keys or field names (`NAME`) cannot classify plain text and are skipped. A pass with no matches preserves the original output exactly.

Groups rank incident severity first, then cumulative duration (memory by peak bytes). Problems omits slow groups whose maximum duration is under 1 ms and reports their count; all groups remain available through group CSV export and details. Error groups remain regardless of duration. Slow groups are candidates, not universal slow thresholds. Nested CALL, SDBL and DB durations overlap and must not be added as independent time. PROC requires explicit abnormal-termination evidence to enter a crash group; normal starts/stops are retained in events. EXCP normalization is a diagnostic grouping, not incident deduplication across components.

Coverage lists present/absent event kinds. A manifest distinguishes disabled events from enabled-but-unobserved events. If a scan stops early, has an unfinished tail or rejects records, enabled-but-absent events are reported as unobserved in incomplete input, with those reasons, rather than as not having occurred. Filters, missing generators, unfinished operations, platform version and uncollected nodes can still explain absence. **Absent does not mean no problem.** Describe entries keep source links, author tags, disagreements and unverified observations.

Chain starts with the enclosing CALL when available and gathers interval/identity candidates within the same node/process/infobase, using thread, session and connection without equating different identifier types. Producer PID is read from platform filenames/folders or PID when available; otherwise correlation stays file-scoped. Node boundaries are inferred from source directories: keep copied nodes separate. Compare timestamps in the same recorded wall clock; align clocks/time zones before combining nodes. DBMS holders require poll-id and connection linkage. Managed holders are candidates until resource values, modes and transaction lifetime are checked. Missing parents, identities, other nodes and possible duration filtering remain explicit. This is not a proven distributed trace.

### Budgets and navigation

Defaults: expanded-byte budget 64 MB, read time 10 seconds, at most 200,000 indexed events and a memory budget. File discovery shares the time budget and refuses inventories above 50,000 files/members; if total bytes are not yet known, the error says so and asks for a smaller directory. Byte/time caps are per scan; time-window filters reduce indexed results but still scan input bytes. Narrow to a file/subdirectory for a smaller read. Increase `readBudgetMb` up to 512 or `readBudgetSeconds` up to 30 when appropriate. Headers report read MB of total expanded MB and every incomplete scan. Lists use shared limit/offset pagination; check next-page and showing counts.

The in-memory LRU holds at most four indices within a shared memory bound; no database or on-disk index is created. Occurrences use source file/member/byte-offset pointers; changed size/mtime invalidates the index, and stale detail pointers refuse rereading. Repeat summary when a capture grows or rotates. IDs must be used with the same path and filters. A manifest beside the input is named `edt-mcp-capture.json`.

Details and raw property filters have a separate read budget using the same parameters. Plain files seek directly to pointed lines; a zip member must decompress its prefix to seek. Sequential pointers share one open member so the prefix is not repeatedly decompressed. Prefix bytes count against the budget. A budget hit refuses a complete-result claim and gives narrowing/paging advice; it never silently drops remaining occurrences.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
