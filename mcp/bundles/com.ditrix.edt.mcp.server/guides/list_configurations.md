Lists EDT launch configurations — runtime client, Attach (RemoteRuntime / LocalRuntime), and any other config in the 1C/EDT namespace — together with their current running state. Once the client knows the exact `name`, it can target that configuration by name without juggling applicationId/project pairs.

## When to use
- Discovery step before `launch`, `run_yaxunit_tests`, `debug_yaxunit_tests` and `update_database` — copy the returned `name` into their `launchConfigurationName`.
- See whether a configuration is already running (and whether it is paused on a breakpoint) before launching a second client.

## Server-side debug workflow
1. `list_configurations({type: "attach"})` — see available Attach configs, their infobase aliases, and whether any is already running.
2. `launch({launchConfigurationName: ...})` — attach to it.
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
- `applicationId` — the configuration's real applicationId when it has one; otherwise a synthetic launch identifier minted from the configuration name (`attach:<name>` for Attach configs, `launch:<name>` for any other 1C/EDT config). The synthetic form appears when the application binding is absent — or could not be read — and exists so a session stays addressable for debug tracking; it is **not** an application id and `update_database` / `get_applications` cannot resolve it. To act on such a configuration, pass its `name` as `launchConfigurationName` to a tool that accepts that configuration type (`update_database` takes runtime-client configs only), or take a real id from `get_applications`. If that tool then reports the configuration *could not be read*, one of its attributes is unreadable rather than absent — repair or recreate the configuration in EDT.
- `project`, `infobaseAlias`, `debugServerUrl` — present only when the config defines them.
- `running` — boolean, true only once a 1C client process or debug target exists.
- `state` — present while a launch of the configuration is live: `starting` / `synchronizing` (EDT has not started the client yet; do not terminate it), `running`, or `abandoned` (the start ended without a client), with `stateNote` when not running. `mode` (debug/run) and `suspended` (a thread paused on a breakpoint) come with it.

## Notes
- Returns JSON: a `configurations` array plus a `count`.
- Only the `launchConfigurationName` mode of `launch` can start an Attach session — the `projectName + applicationId` mode reaches runtime-client configs only.
- A standalone-server row can also be passed to `launch` by name. That path starts the server directly and reports effective DEBUG mode after the bounded start completes.
