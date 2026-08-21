---
name: edt-mcp-project-runtime-debug
description: Collect bounded runtime and debugger evidence for a 1C project through EDT-MCP, including launch, breakpoints, variables, event log, and cleanup.
---

# EDT-MCP runtime debugging

## Purpose and trigger

Use this skill when one runtime question needs authorized launch, Attach,
debugger, variable, expression, or event-log evidence.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Resolve one exact project, application, launch/Attach configuration, and runtime
question. Skip launch and debugger work entirely when bounded `get_event_log`
evidence alone answers the question.

## Primary workflow

1. For event-log-only work, resolve the authorized log source, apply current
   server-side bounds from help, call `get_event_log`, and report any paging,
   format, location, disclosure, or completeness gap.
2. Otherwise resolve the target with `get_applications`,
   `list_configurations`, and `debug_status`; settle update, external-change,
   restart, credential, and data-disclosure authority before launching.
3. Use `set_infobase_credentials` only for the confirmed target and only when
   authorized. Set the smallest `set_breakpoint` before `debug_launch` or the
   Attach start, retaining task-owned identifiers.
4. If `debug_launch` reports `alreadyRunning=true`, do not claim that launch,
   update, restart, or startup options ran. Continue only when the existing
   session is the authorized target and no fresh-start effect is required;
   otherwise remove task-owned state and stop. Terminate and relaunch only
   with explicit user authority.
5. Refresh `debug_status`. Before `wait_for_break`, `get_variables`,
   `evaluate_expression`, `set_variable`, `step`, or `resume`, require the
   current help and status to identify one unambiguous intended debug target.
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
