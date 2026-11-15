---
name: edt-mcp-project-profiling
description: Run a bounded 1C performance profiling experiment through EDT-MCP and collect attributable evidence without leaving profiling active.
---

# EDT-MCP profiling

## Purpose and trigger

Use this skill when one reproducible performance question requires runtime
measurement against an exact 1C application.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Measure one authorized scenario and data volume. Do not mix the measurement
with refactoring, broad load generation, or unrelated runtime activity.

## Primary workflow

1. Define the question and resolve an active, unambiguous debug target with
   `debug_status`; route launch or Attach preparation to
   `edt-mcp-project-runtime-debug` when needed.
2. Record existing measurements with `get_profiling_results` in `mode=list`.
   Resolve measurement UUIDs independently of `applicationId`, which selects only
   the active-profiling flag. Keep the profiling window quiescent for attribution.
3. Call `start_profiling` and claim ownership only when its result confirms
   this task started the profiling window. If profiling was already active, do
   not stop or replace it; stop and report that an attributable window could
   not be established without disrupting another owner. Otherwise execute only
   the bounded scenario and call `stop_profiling` for that task-owned window on
   success, failure, timeout, or interruption. Optionally supply a unique `name`.
   Retain the returned `measurementId`; an asynchronous timeout or ambiguous
   arrival means no measurement was named. Check `mode=list` before proceeding.
4. Read the exact `measurementId` with the default Markdown summary. Times are
   in ms; own (pure) time is additive, while inclusive line sums may double-count
   recursion. Follow `showing N of M` notices: narrow module/method filters or use
   `mode=lines` with `limit`/`offset` for more rows. Correlate with exact source.
5. Compare only controlled runs using `mode=compare`, `baseId` and `comparedId`.
   Rank absolute own-time changes, account for unmatched elements, and avoid
   completeness claims from bounded tops. Methods match by name; lines by number
   and trimmed text, so edits can break line matching.
6. Use the separate write tool `manage_profiling_results` to export full CSV or
   native EDT results only to authorized absolute host paths;
   export/import require their own tool permission, the shared consent gate and a
   writable Tools preset. `get_profiling_results` only reads measurements.
   CSV files must be new with existing parents; native measurement subfolders
   must be new. Import skips loaded UUIDs. Do not overwrite existing evidence.

## Authority rule

The target application, scenario, data effects, launch/Attach work, and any
repeat measurement must be authorized. Profiling does not authorize a code fix.

## Stop rule

Stop and clean up when the target changes, another session prevents reliable
attribution, the scenario would mutate prohibited data, or results cannot be
linked to the intended run.

## Completion signal

Report the exact target and scenario, conditions, observed duration and
candidate hotspots, partial-result and attribution limits, cleanup status, and
the next validation experiment for any proposed optimization.

## Technological-log investigation

After profiling identifies a candidate, use `get_tech_log` in describe mode to
choose errors, slow-calls, db, locks, memory or scenario. Read the chosen profile,
event and problem entries with their sources and cost/coverage caveats. Use
`manage_tech_log` preview before start; preserve the previewed dedicated location,
obtain the shared write consent, reproduce once briefly, then stop even on a
failed reproduction. Config written and observed log-file pickup are separate
facts; service-account clusters need their own configuration and all-node logs.

Run `get_tech_log` summary, follow a groupId into details and an occurrenceId into
chain. Use events for raw filters and describe(problem=...) for evidence and
remediation references. Missing events, filtered parents, unknown identities and
uncollected nodes remain explicit; candidates do not establish causality. Never
sum nested CALL/SDBL/DB durations as independent time. A code fix still needs
separate authority; after the fix, repeat the same profiling/scenario and data
volume to demonstrate the effect. Report stop/byte-exact config restoration and
remaining evidence gaps with the measurement.
