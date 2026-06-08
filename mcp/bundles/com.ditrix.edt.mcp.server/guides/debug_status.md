Shows the active debug launches and whether each is currently suspended - the "where am I?" tool for a debug session. Use it to find the `applicationId` to drive the other debug tools, and to check if execution is paused at a breakpoint.

## When to use
- You don't know the `applicationId` of a running debug session and need it for `wait_for_break` / `resume`.
- To check whether a launch is suspended (and at which line) before stepping or reading variables.
- To confirm a `debug_launch` (or an attach) is actually running.

## Parameter details
- `applicationId` - optional filter to a single launch; omit to list all active EDT debug launches.

## What you get
JSON: `launches` - each with `applicationId` (a real id, or a synthetic `attach:<configName>` for attach launches), `launchConfiguration` name and `configurationType`, `mode`/`debug`, `project`, whether it is `suspended`, the `threadCount`, and `suspendedAt` (the top frame name and line) when paused. Plus a `count` and a `registry` snapshot.

## Notes & gotchas
- Only EDT/1C debug launches are listed; unrelated Java/Ant launches are filtered out.
- For attach configurations that don't carry an application id, use the reported `attach:<configName>` value as the `applicationId` for other tools.
- This only reports state - it doesn't pause or resume anything. To block until a suspend happens use `wait_for_break`; to continue use `resume`.
