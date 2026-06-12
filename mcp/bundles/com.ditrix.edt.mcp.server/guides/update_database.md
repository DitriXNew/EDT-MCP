Applies the EDT configuration to an application's database (infobase) — the equivalent of "Update database configuration" in Designer. Supports a full reload or an incremental (changes-only) update.

## Think twice — destructive (confirm-preview)

This tool mutates the infobase and is **irreversible**. Run it ONLY on an explicit user request. A full update can drop/recreate database structures; back up or be sure the infobase is disposable.

It is guarded by a two-phase workflow (mirroring delete_metadata):
1. **Preview** (`confirm` omitted / false, the default): resolves the target and returns `action='preview'`, `confirmationRequired=true`, the resolved project/applicationId/applicationName, the `updateType` (FULL/INCREMENTAL) and `stateBefore` - WITHOUT touching the infobase.
2. **Apply** (`confirm=true`): performs the update; the result reports `action='updated'`.

## When to use

After changing metadata/configuration, to push those changes into the running infobase so a launched client sees them. Typically: edit metadata -> `update_database` -> launch/restart the client.

## Targeting (choose ONE)

1. **`launchConfigurationName`** (preferred) — exact runtime-client config name from `list_configurations`. It fixes the project + applicationId pair for you, so you cannot mismatch them. Must be a runtime-client config (not an Attach config).
2. **`projectName` + `applicationId`** — used only when `launchConfigurationName` is omitted. Get `applicationId` from `get_applications`. Both are required in this mode.

## Parameter details

- **launchConfigurationName** (string) — preferred target; see above.
- **projectName** (string) — required if launchConfigurationName is omitted.
- **applicationId** (string) — from `get_applications`; required if launchConfigurationName is omitted.
- **fullUpdate** (boolean, default false) — true performs a FULL reload (complete rebuild), false performs an INCREMENTAL update (changed objects only). Incremental is faster; use full when the structure changed substantially or an incremental update fails.
- **autoRestructure** (boolean, default true) — automatically apply database restructurization (table/index changes) when the update requires it, instead of prompting. Leave true for unattended use.
- **confirm** (boolean, default false) — false previews the resolved update without touching the infobase; true applies it.
- **terminateRunningClients** (boolean, default true) — before applying, terminate any 1C client THIS EDT launched on the target infobase to free the exclusive lock and stop it running stale modules. Set false to leave a running client in place (the update then fails if that client holds the infobase exclusively). Only affects the apply phase (confirm=true); the preview reports `willTerminateRunningClients` but terminates nothing.

## Exclusive-lock handling (automatic)

A 1C client launched from this EDT that is running against the target infobase holds it in **exclusive** use (so the update fails) and **caches the old module version** (it keeps running stale code even after a successful publish). With the default `terminateRunningClients=true` the tool frees the infobase itself before applying: it terminates that EDT-launched client using the same client-typed sweep the launch tools use — it never touches a debug-server session or a launch owned by another MCP tool — and reports `terminatedClient`.

Pass `terminateRunningClients=false` to keep the client running; then the old manual flow applies — check `list_configurations` for `running: true` and call `terminate_launch` yourself before retrying. Externally launched clients (Designer, ad-hoc 1cv8c.exe) are invisible to both this sweep and `terminate_launch`, and must be closed by hand.

## Examples

- Preferred, incremental: `launchConfigurationName="MyApp / ThinClient"`.
- Full reload via project + appId: `projectName="MyProject"`, `applicationId="<id from get_applications>"`, `fullUpdate=true`.

## Result

JSON with `project`, `applicationId`, `applicationName`, `updateType` (FULL/INCREMENTAL), `stateBefore`, `stateAfter` and a `message`. `terminatedClient: true` is present ONLY when a running client was actually terminated to free the infobase (absent on a preview, on opt-out, or when no client was running). A successful run reports `stateAfter = UPDATED`. If the application is already BEING_UPDATED the tool returns an error and you should wait.

## Gotchas

- With `terminateRunningClients=false`, most failures are the exclusive lock above — terminate the running launch first (the default frees it automatically).
- `launchConfigurationName` must reference a runtime-client config; an Attach config is rejected.
- The project must exist and be open; a closed project returns an error.
- Running this on a **standalone-server** application (`applicationId` starting with `ServerApplication.`) STARTS the standalone server in RUN mode as a side effect — that is EDT-native behaviour of the server-application update (the configurator agent publishes the modules into the running server). A subsequent `debug_launch` will then have to restart that server in DEBUG mode. Prefer letting the launch do the update: `debug_launch` / `run_yaxunit_tests` with `updateBeforeLaunch=true` defer the server-app update to EDT's coordinated launch flow.
