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
and tags. Pass arrays (a comma-separated string is also accepted). A test path
is the programmatic `Module.Method`; a module filter is the programmatic module
name. Values inside one selector family are alternatives; different families
combine. Confirm exact forms with `get_tool_guide('run_yaxunit_tests')` when the
installed version differs.

Use the smallest selection that proves the requested behavior. Pin a single
test when debugging.

## Update and launch policy

1. Decide `updateBeforeLaunch` deliberately. Its current default may
   recompute projects, terminate a stale client, and update the application.
   Before permitting an update, require explicit update/restructure authority
   or a disposable test infobase where those effects are acceptable; update
   dialogs may be accepted automatically.
2. When `updateBeforeLaunch=true`, inspect the configuration's dependent
   extension projects and the project-policy Git/dirty state before the call.
   Pass an explicit `updateScope`: `configuration` for only the launch project;
   `extension:<ProjectName>` for the configuration plus that case-sensitive
   dependent extension name (comma-separate several); or `all` only when the
   configuration and every dependent extension are intentionally included.
   Within the selected scope the tool recomputes only projects whose sources
   changed, but the broad default is `all`, so never omit this parameter. Do not
   include an unrelated dirty/dependent extension without explicit authority.
   Unknown extension names are a hard error. If the required dependency graph
   cannot be expressed by these supported values, stop and explain the
   limitation instead of silently broadening the scope.
3. Before a normal `debug=false` run with `updateBeforeLaunch=true`, inspect
   application-wide live client launches using
   `list_configurations(type='client', projectName=<exact project>)` and match
   the selected real `applicationId`. Explain that the documented preparation
   chain may terminate an existing client before recompute/update, and require
   explicit authority to terminate or restart any user-owned matching client.
   Update/restructure authority does not grant client-termination authority.
   Without it, use `updateBeforeLaunch=false` only when the application is
   already prepared and that documented no-sweep route is proven safe for the
   requested run; otherwise stop and report the limitation. Do not claim that
   `run_yaxunit_tests` preserves a matching client.
4. When `updateBeforeLaunch=true`, pass `externalInfobaseChanges` explicitly.
   `override` writes the infobase and discards its external configuration
   changes; `import` rewrites project sources; `cancel` refuses the divergence.
   Require authority for the chosen effect rather than inheriting `override`.
5. On standalone-server applications, let the coordinated test launch perform
   the update; do not pre-run a bare `update_database`, which can start the
   server in run mode.
6. Pass `standaloneServerPortConflict='cancel'` unless explicit authority allows
   `reassign`, which rewrites the standalone-server configuration.
7. Use `updateBeforeLaunch=false` only when the application is already prepared
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

Before setting the breakpoint, inspect application-wide live client launches
for the exact selected application. Proceed only after proving that no matching
client exists, or after explicit authority to terminate the matching
user-owned client before this workflow starts and a fresh discovery proves it
is gone. Do not start the debug path alongside a pre-existing matching client:
configuration name, project/application identity, and the returned launch
handle do not make either supported `terminate_launch` selector unique when
duplicate launches coexist. With `updateBeforeLaunch=true`, the tool may also
terminate an existing client during its fresh-run sweep, so that side effect
still requires explicit authority.

Set the exact source breakpoint before starting the selected test and retain
the `breakpointId` returned by `set_breakpoint`. Then use the current debug mode
of `run_yaxunit_tests` with `debug=true`; the separate
`debug_yaxunit_tests` name is a compatibility alias.
Before
`wait_for_break`, `get_variables`, or `evaluate_expression`, require a
sanitized/non-production target, adequate enabled server redaction for the
requested values, or explicit authorization for the specific disclosure.
Redaction is optional and incomplete; post-filtering cannot undo disclosure.
The call returns a launch handle after spawning the run, not a completed test
result with totals or failures. If preparation is still pending, retain its
`jobId` and poll it with `get_job_status` until that handle is returned. Retain
the handle's `projectName` and `applicationId` (plus the exact configuration
name when that target form was used): the background job may already be
terminal while the spawned 1C client is still alive.

Use this complete order: `set_breakpoint` ->
`run_yaxunit_tests(debug=true)` -> poll only its `jobId` when pending ->
`wait_for_break` -> bounded inspect/step -> cleanup. Cleanup after success,
failure, timeout, or interruption is ordered: first resume the exact thread or
application if it is suspended; second remove the temporary breakpoint by its
retained `breakpointId`; third, if the client did not exit naturally, use
`terminate_launch` only when both launch ownership and selector uniqueness are
proven. The retained `launchConfigurationName` or returned `projectName` plus
`applicationId` is usable only while fresh discovery proves that it selects no
other live launch; set `includeAttach=false`. If uniqueness cannot be proven,
do not risk termination: leave the task-owned breakpoint removed, report the
remaining launch state, and request manual or newly authorized cleanup. A step
can suspend execution again, so check the final state before cleanup. If an
authorized unique termination times out, report it; use `force=true` only with
separate authority because it can lose client state. Never terminate a
pre-existing user/shared launch without explicit authorization.

`cancel_job` addresses a still-running background job and its destructive
cancellation contract; it is not launch cleanup after the debug job has already
returned its terminal launch handle. Do not confuse a suspended debugger with
a hung test job, or a terminal job with a terminated client.

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
breakpoint or frame evidence, job state, natural-exit/`terminate_launch`
outcome, and ordered debugger cleanup; do not claim completed-run totals or
failures.

## Stop conditions

Stop when the application or test extension is ambiguous, the engine is absent,
update policy lacks authority, a known job is still running, or the selected
infobase is not a designated test target.
