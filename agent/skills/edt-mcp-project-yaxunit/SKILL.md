---
name: edt-mcp-project-yaxunit
description: Discover, run, debug, poll, and cancel YAXUnit tests through current EDT-MCP launch and background-job contracts. Not for generic UI tests.
---

# EDT-MCP YAXUnit

## Purpose and trigger

Use this skill to discover, run, debug, poll, or cancel the narrowest useful
YAXUnit selection on an exact authorized test application.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Resolve one exact launch configuration or project/application, YAXUnit engine,
test extension, and intended selector. This skill is not for generic UI/E2E
tests or an unidentified/shared production infobase.

## Primary workflow

1. Resolve the target with `list_configurations` or `get_applications`, consult
   current `run_yaxunit_tests` help, and configure credentials with
   `set_infobase_credentials` only when authorized and required.
2. Select at least one intended test, module, extension, or tag; settle update,
   external-change, launch, disclosure, and dependency scope before execution.
   Before any `updateBeforeLaunch=true` route, discover and obtain authority
   for every affected project/application launch and project Attach launch;
   otherwise use a proven no-sweep route or stop.
3. Call `run_yaxunit_tests`. If pending, retain its job ID and poll only that
   job with `get_job_status`; never rerun merely to check status.
4. For a completed normal run, require at least one intended test to have
   executed. Treat zero executed tests as inconclusive unless an empty
   selection was explicitly requested.
5. For debugging, first prove the intended debug target can remain unambiguous
   under current help. Then use `set_breakpoint` -> debug-mode
   `run_yaxunit_tests` -> `wait_for_break` -> bounded inspection -> `resume` and
   `remove_breakpoint`. Use `terminate_launch` only for a uniquely identified,
   task-owned launch whose termination is authorized.
6. Use `cancel_job` only for the retained running job and follow its current
   preview, confirmation, and final-state contract.

## Authority rule

Infobase update/restructure, dependency scope, credentials, existing-launch
termination, sensitive-data disclosure, cancellation, and cleanup termination
require authority for the exact target and effect.

## Stop rule

Stop on ambiguous application/debug target or selector, absent engine/test
extension, missing update/launch authority, unsafe shared target, or a known job
whose final state remains unresolved.

## Completion signal

For a normal run, report exact target/selector, executed totals, failures,
report/job evidence, and update effects. For debug-only work, report bounded
frame evidence and cleanup without claiming pass/fail totals; include zero-test
or other inconclusive states explicitly.
