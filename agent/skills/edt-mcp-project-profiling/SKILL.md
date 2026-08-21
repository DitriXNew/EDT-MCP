---
name: edt-mcp-project-profiling
description: Run a bounded 1C performance profiling experiment through EDT-MCP and collect attributable evidence without leaving profiling active.
---

# EDT-MCP profiling

## Purpose and trigger

Use this skill when one reproducible performance question requires runtime
measurement against an exact 1C application.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Measure one authorized scenario and data volume. Do not mix the measurement
with refactoring, broad load generation, or unrelated runtime activity.

## Primary workflow

1. Define the question and resolve an active, unambiguous debug target with
   `debug_status`; route launch or Attach preparation to
   `edt-mcp-project-runtime-debug` when needed.
2. Establish an attributable profiling window using current help and existing
   `get_profiling_results` state.
3. Call `start_profiling`, execute only the bounded scenario, and call
   `stop_profiling` on success, failure, timeout, or interruption.
4. Read `get_profiling_results` immediately, correlate candidate methods/lines
   with exact source, and repeat only for a controlled comparison.
5. Treat returned profiling rows as potentially partial; do not make absolute
   hotspot or completeness claims unless the measurement proves them.

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
