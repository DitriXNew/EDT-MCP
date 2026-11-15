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
