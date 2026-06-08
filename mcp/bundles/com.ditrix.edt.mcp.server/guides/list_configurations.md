Lists EDT launch configurations — runtime client, Attach (RemoteRuntime / LocalRuntime), and any other config in the 1C/EDT namespace — together with their current running state. Once the client knows the exact `name`, it can target that configuration by name without juggling applicationId/project pairs.

## When to use
- Discovery step before `debug_launch`, `run_yaxunit_tests`, `debug_yaxunit_tests` and `update_database` — copy the returned `name` into their `launchConfigurationName`.
- See whether a configuration is already running (and whether it is paused on a breakpoint) before launching a second client.

## Server-side debug workflow
1. `list_configurations({type: "attach"})` — see available Attach configs, their infobase aliases, and whether any is already running.
2. `debug_launch({launchConfigurationName: ...})` — attach to it.
3. `set_breakpoint` → `wait_for_break` → standard debug flow.

## Parameter details
- `type` — filter by config kind:
  - `attach`: RemoteRuntime + LocalRuntime (server-side debug: HTTP services, background jobs).
  - `client`: RuntimeClient (1C:Enterprise client configs). The aliases `runtime` and `runtimeClient` are also accepted.
  - `all` (default): any 1C/EDT launch config. An unknown value is treated permissively as `all`.
- `projectName` — optional; keeps only configs whose project attribute equals this value exactly. Omit to list across all projects.

## Result fields (per entry)
- `name` — the configuration name; this is the value to pass as `launchConfigurationName` downstream.
- `type` — the launch-config type id.
- `attach` — boolean, true for Attach configs.
- `applicationId` — real applicationId, or a synthetic `attach:<name>` for Attach configs (present only when known).
- `project`, `infobaseAlias`, `debugServerUrl` — present only when the config defines them.
- `running` — boolean; when true, `mode` (debug/run) is added, and `suspended` is true when a thread is paused on a breakpoint.

## Notes
- Returns JSON: a `configurations` array plus a `count`.
- Only the `launchConfigurationName` mode of `debug_launch` can start an Attach session — the `projectName + applicationId` mode reaches runtime-client configs only.
