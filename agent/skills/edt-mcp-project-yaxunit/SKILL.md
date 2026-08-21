---
name: edt-mcp-project-yaxunit
description: Discover, run, debug, poll, and cancel YAXUnit tests through current EDT-MCP launch and background-job contracts. Not for generic UI tests.
---

# EDT-MCP YAXUnit

## Goal

Run the narrowest useful YAXUnit selection against the exact application while
making update, launch, background-job, and cancellation effects explicit.

## Use when

- running YAXUnit by extension, module, test, or tag;
- debugging a focused YAXUnit test;
- polling or cancelling a known YAXUnit job;
- diagnosing test launch/update lifecycle failures.

## Do not use when

- the project does not have a configured YAXUnit runtime and test extension;
- the request is a UI/E2E scenario rather than a unit test;
- no authorized test infobase is identified.

## Resolve the target

Prefer an exact launch configuration from `list_configurations`. Otherwise use
the exact project and application from `get_applications`. Confirm the test
extension and YAXUnit engine are available in the selected infobase.

If the infobase requires authentication, confirm an existing test user (for
example `Администратор`) and its intended password before running. Target
`set_infobase_credentials` by the exact `launchConfigurationName` so both the
update agent and launched client are configured, and require
`clientConfigured=true`. A project/application target configures only the
agent. A local launch stores a non-empty password in clear-text workspace
metadata, while a shared launch refuses it; use such storage only with explicit
authority or choose OS authentication, an empty password, or secure manual
client configuration.

## Selectors

Current `run_yaxunit_tests` supports selectors for extensions, modules, tests,
and tags. Values inside one selector family are alternatives; different
families combine. Confirm exact array/string forms with
`get_tool_guide('run_yaxunit_tests')` when the installed version differs.

Use the smallest selection that proves the requested behavior. Pin a single
test when debugging.

## Update and launch policy

1. Decide `updateBeforeLaunch` deliberately. Its current default may
   recompute projects, terminate a stale client, and update the application.
   Before permitting an update, require explicit update/restructure authority
   or a disposable test infobase where those effects are acceptable; update
   dialogs may be accepted automatically.
2. Choose `externalInfobaseChanges` explicitly when external configuration
   changes must be preserved or refused.
3. On standalone-server applications, let the coordinated test launch perform
   the update; do not pre-run a bare `update_database`, which can start the
   server in run mode.
4. Do not authorize standalone-server port reassignment implicitly.
5. Use `updateBeforeLaunch=false` only when the application is already prepared
   or the task intentionally delegates freshness elsewhere.

## Run and poll

1. Call `run_yaxunit_tests` with the exact target, selection, and update policy.
2. If a completed report is returned, inspect totals and failures.
3. If the result is pending, save the returned `jobId` and poll only that job
   with `get_job_status`.
4. Do not restart the same tests merely to obtain the status of a known job.
5. Treat a non-changing progress phase as ambiguous; recompute, launch dialogs,
   and long tests can look alike.

## Debug

Set the exact source breakpoint before starting the selected test and retain
the `breakpointId` returned by `set_breakpoint`. Then use the current debug
mode of `run_yaxunit_tests` with `debug=true`; the separate
`debug_yaxunit_tests` name is a compatibility alias. Before
`wait_for_break`, `get_variables`, or `evaluate_expression`, require a
sanitized/non-production target, adequate enabled server redaction for the
requested values, or explicit authorization for the specific disclosure.
Redaction is optional and incomplete; post-filtering cannot undo disclosure.
This call returns a
launch handle after spawning the run, not a completed test result with totals
or failures. Follow the safe order `set_breakpoint` ->
`run_yaxunit_tests(debug=true)` -> `wait_for_break` -> inspect, step, or
resume with `get_variables`, `evaluate_expression`, `step`, and `resume` ->
if execution is suspended, resume it -> remove the temporary breakpoint by
its retained `breakpointId`. Perform this cleanup after success, failure,
timeout, or interruption. A step can suspend execution again, so check the
final state before cleanup. Do not confuse a suspended debugger with a hung
test job.

If the user task explicitly requires pass/fail verification after debugging,
run one separate focused normal execution with `debug=false` after debugger
cleanup and report totals, failures, and the report path from that completed
run. Do not start this additional run otherwise.

## Cancellation

Use `cancel_job` according to its current preview/confirmation contract. A
cancelled run can leave runtime data changed and a partial or missing report;
no rollback is implied. A termination request is not a terminal job result;
poll the same `jobId` until it reaches a terminal state before reporting
cancellation complete.

## Verification

For a completed normal run, record application/infobase, selectors, update
policy, job ID when one was used, pass/fail/skip totals, failing tests, report
path when provided, cleanup, and whether test or cancellation behavior may
have changed runtime data. For a debug-only run, record the launch handle,
breakpoint or frame evidence, and debugger cleanup; do not claim completed-run
totals or failures.

## Stop conditions

Stop when the application or test extension is ambiguous, the engine is absent,
update policy lacks authority, a known job is still running, or the selected
infobase is not a designated test target.
