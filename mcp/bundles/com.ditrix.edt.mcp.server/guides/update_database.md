Applies the EDT configuration to an application's database (infobase) — the equivalent of "Update database configuration" in Designer. Supports a full reload or an incremental (changes-only) update.

## Think twice — destructive (confirm-preview)

This tool mutates the infobase and is **irreversible**. Run it ONLY on an explicit user request. A full update can drop/recreate database structures; back up or be sure the infobase is disposable.

It is guarded by a two-phase workflow (mirroring delete_metadata):
1. **Preview** (`confirm` omitted / false, the default): resolves the target and returns `action='preview'`, `confirmationRequired=true`, the resolved project/applicationId/applicationName, the `updateType` (FULL/INCREMENTAL) and `stateBefore` - WITHOUT touching the infobase and WITHOUT terminating any client.
2. **Apply** (`confirm=true`): terminates running clients (see below), then performs the update; the result reports `action='updated'`.

## When to use

After changing metadata/configuration (or an extension's BSL/metadata), to push those changes into the running infobase so a launched client sees them. Typically: edit metadata -> `update_database` -> launch/restart the client. Extensions are handled exactly like the main configuration — the same incremental update publishes them.

## Targeting (choose ONE)

1. **`launchConfigurationName`** (preferred) — exact runtime-client config name from `list_configurations`. It fixes the project + applicationId pair for you, so you cannot mismatch them. Must be a runtime-client config (not an Attach config).
2. **`projectName` + `applicationId`** — used only when `launchConfigurationName` is omitted. Get `applicationId` from `get_applications`. Both are required in this mode.

## Transparent session termination (default ON)

On `confirm=true` the tool first terminates any 1C client **this EDT instance launched** against the target infobase (controlled by `terminateRunningClients`, default `true`). This removes the two reasons an update misbehaves with a live session:
- the infobase is held in **exclusive** use, so the update would block/fail; and
- a connected client caches the old module version, so even after a successful publish it would keep running stale code until it restarts.

Termination is **polite** (no force-kill): if a client will not stop within the timeout the tool returns an actionable error suggesting `terminate_launch` with `force=true`. The response includes `terminatedClients` (how many were stopped).

The tool also **auto-confirms** EDT's blocking "update the database?" modal that can pop during the update when a session is active, so the call does not hang waiting for a human click. As a backstop, a watchdog (`updateTimeoutSeconds`, default 120) bounds the wait: if the update does not finish in time it keeps running in the background and the tool returns `stateAfter=BEING_UPDATED` so you can poll `get_applications` (a full reload can take several minutes). The auto-confirm stays active for the **whole** life of the update, so even a long full reload that pops a restructurization dialog after the watchdog window is still confirmed in the background and does not hang.

## Incremental vs full

Incremental is the default and is almost always what you want — including for extensions. After an incremental update an extension-bearing configuration may still report `stateAfter=INCREMENTAL_UPDATE_REQUIRED`: this is a **cosmetic** EDT state (the changes ARE published) and is reported as **success**, so do NOT retry with a full update. Only a genuine `FULL_UPDATE_REQUIRED` (surfaced with `fullUpdateRequired=true`) means incremental is insufficient and you should re-call with `fullUpdate=true`. Full updates are rare, slow, and can drop/recreate structures — reach for one only when explicitly required.

## Parameter details

- **launchConfigurationName** (string) — preferred target; see above.
- **projectName** (string) — required if launchConfigurationName is omitted.
- **applicationId** (string) — from `get_applications`; required if launchConfigurationName is omitted.
- **fullUpdate** (boolean, default false) — true performs a FULL reload (complete rebuild), false performs an INCREMENTAL update (changed objects only). Incremental is faster; use full only when `fullUpdateRequired=true` is returned or the structure changed substantially.
- **autoRestructure** (boolean, default true) — automatically apply database restructurization (table/index changes) when the update requires it, instead of prompting. Leave true for unattended use.
- **confirm** (boolean, default false) — false previews the resolved update without touching the infobase; true applies it.
- **terminateRunningClients** (boolean, default true) — on `confirm=true`, terminate EDT-launched 1C clients on the target infobase before updating. Set false only if you have already stopped them yourself.
- **updateTimeoutSeconds** (integer, default 120, clamped 5..600) — watchdog window for the update call; on timeout the update continues in the background and `stateAfter=BEING_UPDATED` is returned.

## Exclusive-lock gotcha (external clients)

The tool can only terminate clients **this EDT instance launched**. A 1C client started **externally** — Designer, an ad-hoc `1cv8c.exe`, another EDT instance, or a server session — is invisible to `terminate_launch` and still holds the infobase in exclusive use. If an update stalls or fails on a lock and `terminatedClients` is 0, close that external client by hand and retry.

## Examples

- Preferred, incremental, transparent termination: `launchConfigurationName="MyApp / ThinClient"`, `confirm=true`.
- Full reload via project + appId: `projectName="MyProject"`, `applicationId="<id from get_applications>"`, `fullUpdate=true`, `confirm=true`.
- Skip termination (you stopped clients yourself): add `terminateRunningClients=false`.

## Result

JSON with `project`, `applicationId`, `applicationName`, `updateType` (FULL/INCREMENTAL), `stateBefore`, `stateAfter`, `terminatedClients` and a `message`. A successful run reports `stateAfter = UPDATED` (or `INCREMENTAL_UPDATE_REQUIRED` for an extension-bearing config — also a success). `fullUpdateRequired=true` with `success=false` means re-call with `fullUpdate=true`.

## Gotchas

- Most remaining failures are an **external** client holding the lock — close it by hand (see above).
- `launchConfigurationName` must reference a runtime-client config; an Attach config is rejected.
- The project must exist and be open; a closed project returns an error.
