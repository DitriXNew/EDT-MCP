Turns on 1C line-level performance measurement for a running debug session: once active, every executed BSL line is tracked with its call count and timing. Run your scenario, then `stop_profiling` and `get_profiling_results` to see what code ran and where the time went (also doubles as code-coverage).

## When to use
- You want to know which lines actually execute during a scenario, or where the time goes.
- Measuring coverage of a YAXUnit run or a manual test (start it, exercise the code, stop, read results).

## Recipe: measure a slow action
1. `list_configurations`, then `launch({"launchConfigurationName":"<name>"})` (debug mode is the default).
2. Poll `debug_status` until the launch's `state` is `running`. `starting`/`synchronizing` can last minutes while EDT loads the configuration into the infobase; do not terminate or relaunch meanwhile.
3. Right before the action (so client startup is not measured): `start_profiling` with the `applicationId` that `launch` and `debug_status` report for the launch (`launch:<configuration name>` for a configuration not bound to an application).
4. Reproduce the action in the client (TestClient tools, or ask the user), then `stop_profiling`.
5. `get_profiling_results` for the hot lines and methods.

## Parameter details
- `applicationId` (required) - the running debug session to profile. Get it from `get_applications` or `debug_status`; a `debug_yaxunit_tests` / `launch` run provides one.

## What you get
JSON: `active`, `started` (true if this call switched it on, false if it was already on), `applicationId`, and a `message` with the next step.

## Notes & gotchas
- **Requires an active DEBUG session** - start one with `launch` or `debug_yaxunit_tests` first. A plain run is not enough.
- Start-only and idempotent: calling it again while already profiling does NOT toggle it off - it reports "already active". (The on/off state is shared with `stop_profiling`.)
- The data is collected on the debug server while the session runs; you only see it after `stop_profiling` + `get_profiling_results`.
