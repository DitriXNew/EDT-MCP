---
name: edt-mcp-project-profiling
description: Run a bounded 1C performance profiling experiment through EDT-MCP and collect attributable evidence without leaving profiling active.
---

# EDT-MCP profiling

## Goal

Measure one scenario and answer one performance question without mixing
measurement, refactoring, and unrelated runtime activity.

## Use when

- a performance claim needs runtime evidence;
- a method or line hotspot must be identified;
- static query/code observations need confirmation.

## Do not use when

- the issue is functional correctness only;
- the target application or scenario is not reproducible;
- a shared runtime cannot be isolated enough to attribute results.

## Preflight

1. Define one scenario, expected start/end, and performance question.
2. Resolve the exact debug target and application.
3. Check `debug_status` and require an active debug session/application before
   `start_profiling`. Otherwise launch or Attach through the runtime-debug
   workflow and wait for readiness, or stop and route there.
4. Call `get_profiling_results` without `applicationId` and check its global
   `profilingActive` before starting. Require an exclusive profiling window
   with no other session allowed to publish a result.
5. Read guides for `start_profiling`, `stop_profiling`, and
   `get_profiling_results` when target or result semantics are uncertain.
6. Decide how the scenario will be triggered and what data volume it uses.

## Workflow

1. Start with `start_profiling` for the exact target.
2. Execute only the bounded scenario.
3. Stop with `stop_profiling` even when the scenario fails.
4. Read `get_profiling_results` immediately after stopping.
5. Correlate hot methods and lines with exact project source using module/method
   readers.
6. Repeat only when a controlled comparison is necessary and the first run's
   target and conditions are known.

## Attribution

`get_profiling_results` exposes the latest global result without reliable
result identity or timestamps. It cannot prove which application produced a
result after another session publishes one. Use the preflight global
`profilingActive` check, an exclusive profiling window, and the immediate
stop-then-read sequence for attribution; do not require identity or timestamp
fields that the API does not return.

## Evidence boundary

- Static complexity or query shape is a hypothesis, not a measured bottleneck.
- One profiling run proves behavior only for its scenario and data volume.
- A hot line may be the caller of expensive platform work rather than the full
  root cause.

## Output

Report scenario, application/target, data conditions, duration, hottest
methods/lines, evidence linking them to source, confounders, optimization
hypothesis, and the validation experiment for any future change.

## Safety and stop conditions

Do not refactor during the measurement step. Stop and clean up when the target
changes, another session starts profiling, the scenario mutates prohibited
data, or results cannot be attributed. Always attempt to stop profiling before
handing control back.
