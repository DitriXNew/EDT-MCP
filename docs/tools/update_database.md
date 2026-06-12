# update_database

Apply configuration changes to an application's database (infobase), full or incremental. Target by launchConfigurationName (preferred) or projectName + applicationId. Destructive/irreversible: guarded by a confirm-preview - call without confirm to preview the exact update (no infobase change), then confirm=true to apply. Terminate any running 1C client on the target infobase first (exclusive lock). Full parameters and examples: call get_tool_guide('update_database').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client config name from list_configurations (preferred target). |
| projectName | — | string | EDT project name; required if launchConfigurationName is omitted. |
| applicationId | — | string | Application ID from get_applications; required if launchConfigurationName is omitted. |
| fullUpdate | — | boolean | true = full reload, false = incremental (default false). |
| confirm | — | boolean | true = apply the update; default false = preview only (resolves the target and reports what would change WITHOUT mutating the infobase). |

## Guide
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
- **confirm** (boolean, default false) — false previews the resolved update without touching the infobase; true applies it.

## Exclusive-lock gotcha

If a 1C client launched from this EDT is currently running against the target infobase, the update typically FAILS because the infobase is held in exclusive use. Check `list_configurations` for `running: true`; if so, call `terminate_launch` first (it only affects launches started from this EDT instance), then retry. Externally launched clients (Designer, ad-hoc 1cv8c.exe) are invisible to `terminate_launch` and must be closed by hand.

## Examples

- Preferred, incremental: `launchConfigurationName="MyApp / ThinClient"`.
- Full reload via project + appId: `projectName="MyProject"`, `applicationId="<id from get_applications>"`, `fullUpdate=true`.

## Result

JSON with `project`, `applicationId`, `applicationName`, `updateType` (FULL/INCREMENTAL), `stateBefore`, `stateAfter` and a `message`. A successful run reports `stateAfter = UPDATED`. If the application is already BEING_UPDATED the tool returns an error and you should wait.

## Gotchas

- Most failures are the exclusive lock above — terminate the running launch first.
- `launchConfigurationName` must reference a runtime-client config; an Attach config is rejected.
- The project must exist and be open; a closed project returns an error.
- Running this on a **standalone-server** application (`applicationId` starting with `ServerApplication.`) STARTS the standalone server in RUN mode as a side effect — that is EDT-native behaviour of the server-application update (the configurator agent publishes the modules into the running server). A subsequent `debug_launch` will then have to restart that server in DEBUG mode. Prefer letting the launch do the update: `debug_launch` / `run_yaxunit_tests` with `updateBeforeLaunch=true` defer the server-app update to EDT's coordinated launch flow.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
