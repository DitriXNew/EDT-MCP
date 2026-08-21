---
name: edt-mcp-project-runtime-debug
description: Collect bounded runtime and debugger evidence for a 1C project through EDT-MCP, including launch, breakpoints, variables, event log, and cleanup.
---

# EDT-MCP runtime debugging

## Goal

Answer one runtime question with the smallest authorized launch and debugger
experiment, then clean up temporary state.

## Use when

- branch choice, values, rights, transactions, or runtime environment matter;
- a static conclusion is insufficient;
- a server-side path requires Attach debugging;
- event-log evidence is necessary.

## Do not use when

- static inspection already proves the requested fact;
- no authorized test application or infobase is identified;
- the task only asks for code review or model validation.

## Preflight

1. Resolve the exact project, application, and launch configuration with
   `get_applications` and `list_configurations`.
2. Inspect `debug_status` before launching.
3. Use `get_tool_guide('debug_launch')` for current launch/update semantics,
   especially standalone servers or external objects.
4. Decide whether database update, external-infobase changes, client restart,
   or standalone-server port reassignment is authorized.
5. Resolve the exact source location and place the smallest required
   `set_breakpoint` calls before `debug_launch` or an Attach launch.

## Launch and probe

1. After placing breakpoints, use `debug_launch` in one supported targeting
   mode.
2. For server-side code, use an Attach launch configuration; a runtime client
   cannot hit server breakpoints by itself.
3. Poll `debug_status` because launch is asynchronous and later failures are
   reported there.
4. Wait with `wait_for_break`.
5. Read variables with `get_variables`.
6. Use `evaluate_expression` only when necessary; it executes BSL and may have
   side effects.
7. Use `set_variable` only for an explicitly authorized experiment.
8. Step or resume minimally.

## Standalone-server lifecycle

Prefer coordinated launch with `updateBeforeLaunch` for standalone-server
applications. A bare `update_database` can start the server in run mode and
complicate a subsequent debug restart. Do not request automatic port
reassignment unless changing the server configuration is authorized.

## Event log

Use `get_event_log` with narrow time, severity, event, and application filters.
For a FILE infobase, the tool can resolve its event-log location from the
selected application. For a SERVER infobase, require a caller-confirmed
absolute `logDir` that points to an accessible copy of `1Cv8Log` in supported
`text-2.0` format. Neither `projectName` nor `applicationId` automatically
resolves the server log directory; stop explicitly when a suitable `logDir`
is unavailable. Logs can contain user, business, or connection data; return
only evidence needed for the question.

## Cleanup

Remove temporary breakpoints. Terminate only sessions owned by or explicitly
authorized for the task using `terminate_launch`. Do not stop a shared debug
server merely because a client probe is complete.

## Evidence report

Record target application, launch mode, exact breakpoint and frame, decisive
values, expressions or state mutations, event-log filters, cleanup, and
remaining uncertainty.

## Stop conditions

Stop when target identity is ambiguous, launch/update policy lacks authority,
the required Attach configuration is absent, the application is shared and
cannot be safely restarted, or the probe would execute uncontrolled BSL.
