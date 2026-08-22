---
name: edt-mcp-project-runtime-debug
description: Collect bounded runtime and debugger evidence for a 1C project through EDT-MCP, including launch, breakpoints, variables, event log, and cleanup.
---

# EDT-MCP runtime debugging

## Purpose and trigger

Use this skill when one runtime question needs authorized launch, Attach,
debugger, variable, expression, or event-log evidence.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Resolve one exact project, application, launch/Attach configuration, and runtime
question. Skip launch and debugger work entirely when bounded `get_event_log`
evidence alone answers the question.

## Primary workflow

1. For event-log-only work, resolve the authorized log source, apply current
   server-side bounds from help, call `get_event_log`, and report any paging,
   format, location, disclosure, or completeness gap.
2. Otherwise resolve the target with `get_applications`,
   `list_configurations`, `debug_status`, and `list_breakpoints`; distinguish
   pre-existing breakpoints from task-owned ones, and settle update,
   external-change, restart, credential, and data-disclosure authority before
   launching.
3. Use `set_infobase_credentials` only for the confirmed target and only when
   authorized. Decide from `debug_status` whether to use an existing session or
   start a fresh one before installing the smallest `set_breakpoint`. Use an
   existing session directly only when it is the exact authorized target and no
   fresh-start effect is required; install the breakpoint and continue without
   `debug_launch`, or stop. For a fresh launch or Attach, set the breakpoint
   before starting it and retain task-owned identifiers.
4. If a preflight race makes `debug_launch` report `alreadyRunning=true`, do not
   claim that launch, update, restart, or startup options ran. Refresh the
   uniquely identified target, resume only a suspension caused by the task's
   breakpoint, remove task-owned temporary state, and stop unless a fresh
   relaunch was explicitly authorized. Never resume an ambiguous or unrelated
   suspension.
5. Settle any in-progress or unknown launch outcome under current help and a
   bounded caller-approved deadline before removing a task-owned breakpoint,
   treating the launch as absent, or completing. Before `wait_for_break`,
   `get_variables`, `evaluate_expression`, `set_variable`, `step`, or
   `resume`, require current help and status to identify one unambiguous
   intended debug target.
   Otherwise remove only task-owned temporary state and stop.
6. Collect only the bounded evidence needed. Treat expression evaluation and
   variable mutation as potentially state-changing.
7. Resume execution if this task suspended it, call `remove_breakpoint`, and
   use `terminate_launch` only for a uniquely identified, task-owned launch
   whose termination is authorized.

## Authority rule

Infobase update/restructure, external-change handling, credentials, restart,
Attach, sensitive-data disclosure, expression/state mutation, and launch
termination each require authority for the exact target and effect.

## Stop rule

Stop on ambiguous debugger target, missing launch/Attach route, unapproved
side effects or disclosure, shared-session risk, or uncontrolled BSL execution.

## Completion signal

Report the exact runtime target and mode, decisive frame/value/log evidence,
server-side bounds and partial-result caveats, any state mutation, and cleanup
of task-owned suspensions, breakpoints, and launches.
