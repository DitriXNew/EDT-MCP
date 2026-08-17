Applies the EDT configuration to an application's database (infobase) — the equivalent of "Update database configuration" in Designer. Supports a full reload or an incremental (changes-only) update.

## Think twice — destructive (confirm-preview)

This tool mutates the infobase and is **irreversible**. Run it ONLY on an explicit user request. A full update can drop/recreate database structures; back up or be sure the infobase is disposable.

It is guarded by a two-phase workflow (mirroring delete_metadata):
1. **Preview** (`confirm` omitted / false, the default): resolves the target and returns `action='preview'`, `confirmationRequired=true`, the resolved project/applicationId/applicationName, the `updateType` (FULL/INCREMENTAL) and `stateBefore` - WITHOUT touching the infobase.
2. **Apply** (`confirm=true`): performs the update; the result reports `action='updated'`.

## When to use

After changing metadata/configuration, to push those changes into the running infobase so a launched client sees them. Typically: edit metadata -> `update_database` -> launch/restart the client.

## Targeting

1. **`launchConfigurationName`** (preferred) — exact runtime-client config name from `list_configurations`. The project and the applicationId are read off the configuration, so a configuration that is bound to an application cannot be mismatched. Must be a runtime-client config (not an Attach config).
2. **`projectName` + `applicationId`** — used when `launchConfigurationName` is omitted. Get `applicationId` from `get_applications`. Both are required in this mode.

`projectName` is always taken from the configuration when one is named. `applicationId` is too — unless the configuration has no application binding, and then the section below applies.

### A launch configuration with no application binding

A runtime-client configuration can be created without being bound to an application (its `applicationId` attribute is empty) — the same case the launch tools cover by falling back to the project's default application. Here the fallback is deliberately narrower, because this call WRITES to a database and cannot be undone:

- the project has **exactly one** application → that application is the target, and it is the same one `run_yaxunit_tests` / `debug_launch` would have used for this configuration;
- the project has **several** → the call is REFUSED and lists the candidates. Pick one and re-call with `projectName` + `applicationId`; updating the wrong database is not something this tool will guess at;
- the project has **none of its own** → refused. For an extension project the applications belong to its base configuration project, and `update_database` must target THAT project (an application id is only resolvable through the project that owns it).

If you pass `launchConfigurationName` **and** an explicit `applicationId`, the configuration's own binding still wins when it has one; your value is used only when the configuration has none (instead of falling back to the project).

### The `applicationId` from `list_configurations` is not always an application id

`list_configurations` reports `applicationId: "launch:<name>"` (or `"attach:<name>"`) for a configuration whose application binding is absent or unreadable — a launch identifier for debug tracking, minted from the configuration name. It is **not** an application id and `update_database` cannot resolve it ("Application not found"). Use the entry's `name` as `launchConfigurationName` (runtime-client configs only — an Attach config is rejected by type), or take a real id from `get_applications`. If targeting by name then reports that the configuration *could not be read*, one of its attributes is unreadable — do NOT conclude the binding is absent: repair or recreate the configuration in EDT, or target the update with `projectName` + a real `applicationId`.

## Parameter details

- **launchConfigurationName** (string) — preferred target; see above.
- **projectName** (string) — required if launchConfigurationName is omitted.
- **applicationId** (string) — from `get_applications`; required if launchConfigurationName is omitted.
- **fullUpdate** (boolean, default false) — true performs a FULL reload (complete rebuild), false performs an INCREMENTAL update (changed objects only). Incremental is faster; use full when the structure changed substantially or an incremental update fails.
- **confirm** (boolean, default false) — false previews the resolved update without touching the infobase; true applies it.
- `externalInfobaseChanges` — how to answer EDT's blocking "Infobase configuration changes" modal when the infobase was changed OUTSIDE EDT (Designer, `ibcmd`, a CLI pipeline) since the last EDT interaction: `override` (default) keeps the project configuration and overwrites the infobase, `import` pulls the external changes into the PROJECT sources, `cancel` aborts the update with an error. See ## Infobase changed outside EDT.
- **terminateRunningClients** (boolean, default true) — before applying, terminate any 1C client THIS EDT launched on the target infobase to free the exclusive lock and stop it running stale modules. Set false to leave a running client in place (the update then fails if that client holds the infobase exclusively). Only affects the apply phase (confirm=true); the preview reports `willTerminateRunningClients` but terminates nothing.

## Exclusive-lock handling (automatic)

A 1C client launched from this EDT that is running against the target infobase holds it in **exclusive** use (so the update fails) and **caches the old module version** (it keeps running stale code even after a successful publish). With the default `terminateRunningClients=true` the tool frees the infobase itself before applying: it terminates that EDT-launched client using the same client-typed sweep the launch tools use — it never touches a debug-server session or a launch owned by another MCP tool — and reports `terminatedClient`.

Pass `terminateRunningClients=false` to keep the client running; then the old manual flow applies — check `list_configurations` for `running: true` and call `terminate_launch` yourself before retrying. Externally launched clients (Designer, ad-hoc 1cv8c.exe) are invisible to both this sweep and `terminate_launch`, and must be closed by hand.

## Database restructure (auto-confirmed)

When the update changes the DB structure (new/changed objects), EDT pops a blocking **"Restructure data" / «Реорганизация информации»** confirmation dialog listing the structural changes. Because `confirm=true` has already approved this irreversible update, the tool **auto-presses that dialog's default "Accept" button** so the unattended call completes without a human click — otherwise the MCP call would hang on the modal. The EDT update API offers no per-call switch for this, so it is handled by intercepting the dialog only for the duration of this update; the auto-press is written to the EDT log. A structural restructure can include data-deleting changes (dropped attributes/objects) — that is part of applying the configuration you confirmed. Applies to both file infobases and standalone servers.

## Examples

- Preferred, incremental: `launchConfigurationName="MyApp / ThinClient"`.
- Full reload via project + appId: `projectName="MyProject"`, `applicationId="<id from get_applications>"`, `fullUpdate=true`.

## Result

JSON with `project`, `applicationId`, `applicationName`, `updateType` (FULL/INCREMENTAL), `stateBefore`, `stateAfter` and a `message`. `terminatedClient: true` is present ONLY when a running client was actually terminated to free the infobase (absent on a preview, on opt-out, or when no client was running). A successful run reports `stateAfter = UPDATED`. If the application is already BEING_UPDATED the tool returns an error and you should wait.

## Long-running updates and client timeouts

On a large configuration (thousands of objects) `update_database` can run **5–25 minutes**. Many MCP clients apply their own call timeout (e.g. 120 s) well short of that — the client gives up waiting, but that is purely a client-side timeout: it does **not** cancel the underlying EDT update job, which keeps running in EDT to completion (success or failure) regardless of whether anyone is still listening for the response.

If your client times out before the response arrives, do not assume the update failed or retry blindly (a retry while the first update is still running fails with "Application is currently being updated" or races the exclusive lock). Instead, retrieve the real outcome afterwards with `get_mcp_history`:

```
get_mcp_history(tool="update_database", limit=1)
```

This returns the recorded call, including its final `status` and `durationMs`, once the update has actually finished — even though the original call's own response was lost to the client-side timeout. Prefer raising the client's call timeout for this tool (well above the 5–25 minute range) over polling `get_mcp_history` in a loop.

## Known EDT limitation: missing InternalInfo node

On some projects the platform's load pipeline rejects the configuration XML that EDT itself generated for the update, with an error mentioning a missing `InternalInfo` node (Russian EDT message: "Отсутствует внутренняя информация (узел InternalInfo) для объекта Configuration"). This is an **EDT-platform pipeline limitation**, not a bug in this tool or in the MCP call — the EDT GUI's "Update database configuration" fails identically on the same project.

Workaround: update via the platform CLI instead — `export_configuration_to_xml` to export the configuration to files, then run `1cv8 DESIGNER /LoadConfigFromFiles <dir> /UpdateDBCfg` — or try a newer EDT release, which may not have the limitation.

## Gotchas

- With `terminateRunningClients=false`, most failures are the exclusive lock above — terminate the running launch first (the default frees it automatically).
- `launchConfigurationName` must reference a runtime-client config; an Attach config is rejected.
- The project must exist and be open; a closed project returns an error.
- Running this on a **standalone-server** application (`applicationId` starting with `ServerApplication.`) STARTS the standalone server in RUN mode as a side effect — that is EDT-native behaviour of the server-application update (the configurator agent publishes the modules into the running server). A subsequent `debug_launch` will then have to restart that server in DEBUG mode. Prefer letting the launch do the update: `debug_launch` / `run_yaxunit_tests` with `updateBeforeLaunch=true` defer the server-app update to EDT's coordinated launch flow.

## Infobase changed outside EDT

When something other than EDT wrote the infobase configuration since the last EDT interaction —
a `1cv8 DESIGNER /LoadConfigFromFiles`, an `ibcmd infobase config load`, a colleague in the
Configurator — the configuration-to-infobase update stops and asks what to do with those
changes in a modal titled **"Infobase configuration changes"** / **"Изменения конфигурации информационной базы"**
(buttons Import / Override / Cancel). Nobody presses it in an unattended run, so the call would
block on the UI thread until the tool times out.

`externalInfobaseChanges` answers it for you:

| value | what it writes | when to use |
|---|---|---|
| `override` (default) | the INFOBASE — the project configuration wins, the external changes are discarded | the literal meaning of "update the infobase from the project"; the right choice for a CI/agent pipeline that owns the infobase |
| `import` | the PROJECT sources — the infobase changes are pulled in and merged | you want to keep what was loaded into the infobase; note this rewrites your working tree |
| `cancel` | nothing | you want the call to fail loudly and resolve the divergence yourself |

The modal's own default button is **Import**, which would rewrite the project sources — so this
plugin never presses it blind: if the labelled button for the selected policy cannot be found (an
unshipped locale, a reworded button) the dialog is cancelled and the update reports the failure
instead of writing anything.

## Standalone server: busy ports

Updating a standalone-server application (`applicationId` starting with `ServerApplication.`)
STARTS the server first. If one of its configured ports (HTTP gate / debug server / SSH gate) is
already bound, EDT raises a modal titled **"Standalone server port conflict"** /
**"Конфликт портов автономного сервера"** offering *Find free port* or *Cancel*. Nobody presses it
in an unattended run, so the call would block until the client times out.

This plugin answers it with **Cancel** and fails the call with the busy ports named. *Find free
port* is deliberately not pressed: it makes EDT rewrite the server's `config.yaml`, which moves the
address every client of that server connects to — not something a database update may do behind
your back. The usual holder is an `ibsrv` process left over from an earlier EDT session; stop it
(or stop the server in EDT's *Servers* view) and retry. To move the server instead, start it once
from the EDT UI and answer *Find free port* there.

The same applies to `debug_launch` / `run_yaxunit_tests` against a standalone server: the launch is
fire-and-forget, so the reason is reported by `debug_status` under `recentLaunchFailures`.
