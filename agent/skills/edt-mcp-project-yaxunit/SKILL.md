---
name: edt-mcp-project-yaxunit
description: Discover, run, debug, poll, and cancel YAXUnit tests through current EDT-MCP launch and background-job contracts. Not for generic UI tests.
---

# EDT-MCP YAXUnit

## Purpose and trigger

Use this skill to discover, run, debug, poll, or cancel the narrowest useful
YAXUnit selection on an exact authorized test application.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Resolve one exact launch configuration or project/application, YAXUnit engine,
test extension, and intended selector. This skill is not for generic UI/E2E
tests or an unidentified/shared production infobase.

## Selector contract

Resolve the exact filter before starting a run; do not guess test method names.

- To run a whole test module, use `modules=ModuleName` and omit `tests`.
- To run an individual test, use `tests=ModuleName.MethodName` exactly as
  required by the current `run_yaxunit_tests` contract.
- Never pass only a module name or only a method name in `tests`.
- Use `extensions` and `tags` only for their documented filter families; do
  not invent module wildcards or encode one filter family inside another.
- After an invalid selector, zero-test result, or missing-report outcome, do
  not blindly repeat the same invocation. Re-check current help and the actual
  test registration/source, correct the selector, then start one clean run.
- Completion evidence must record the exact filters that were actually sent.

## Primary workflow

1. Resolve the target with `list_configurations` or `get_applications`, consult
   current `run_yaxunit_tests` help, and configure credentials with
   `set_infobase_credentials` only when authorized and required.
2. Select at least one intended test, module, extension, or tag; settle update,
   external-change, launch, disclosure, and dependency scope before execution.
   With `updateBeforeLaunch=true`, pass an explicit `updateScope` of
   `configuration` or exact `extension:<ProjectName>` targets; use `all` only
   when every dependent extension is intentionally included and authorized.
   Obtain authority for every affected project/application launch and project
   Attach launch; otherwise use a proven no-sweep route or stop.
3. Call `run_yaxunit_tests` with the resolved update values. If pending, retain
   its job ID and poll only that job with `get_job_status`; never rerun merely
   to check status. If a caller-approved deadline expires while that job
   remains live or unresolved, do not abandon ownership: continue
   reconciliation, cancel only with authorization and confirm a terminal
   state, or obtain an explicit accepted handoff of the retained `jobId` and
   all task-owned breakpoint/launch cleanup obligations.
4. For a completed normal run, require at least one intended test to have
   executed. Treat zero executed tests as inconclusive unless an empty
   selection was explicitly requested.
5. For debugging, first prove the intended debug target can remain unambiguous
   under current help. Then use `set_breakpoint` -> debug-mode
   `run_yaxunit_tests` -> `wait_for_break` -> bounded inspection -> `resume` and
   `remove_breakpoint`. After the run or any target race, call `debug_status`
   and resume every uniquely attributable task-caused suspension before
   removing the task breakpoint; never resume an unrelated or ambiguously
   owned target.
   Use `terminate_launch` only for a uniquely identified, task-owned launch
   whose termination is authorized.
6. Use `cancel_job` only for the retained running job and follow its current
   preview, confirmation, and final-state contract.

## Authority rule

Infobase update/restructure, dependency scope, credentials, existing-launch
termination, sensitive-data disclosure, cancellation, and cleanup termination
require authority for the exact target and effect.

## Stop rule

Stop on ambiguous application/debug target or selector, absent engine/test
extension, missing update/launch authority, or unsafe shared target only after
any retained job is terminal or its ownership and task-owned cleanup
obligations have been explicitly handed off. A known unresolved job is not, by
itself, a safe stop condition.

## Completion signal

For a normal run, report exact target/selector, executed totals, failures,
report/job evidence, and update effects. For debug-only work, report bounded
frame evidence and cleanup without claiming pass/fail totals; include zero-test
or other inconclusive states explicitly.
