# standalone_server

Report, stop or start the EDT standalone server (ibsrv) of a wst-server application. DESTRUCTIVE for stop/restart: it ends every session and client of that server - call once WITHOUT confirm to preview them, then with confirm=true. A stopped server stays held until action=start. Full parameters and examples: call get_tool_guide('standalone_server').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project that owns the standalone-server application (required). |
| applicationId | — | string | Application ID from get_applications; defaults to the project's default application. Must be a wst-server application. |
| action | — | string (one of: status, stop, start, restart) | status (default) reads the server; stop and restart end its sessions; start releases the hold a stop leaves. |
| confirm | — | boolean | For stop/restart: omit to preview what ends, true to do it. Refused on status/start. |
| standaloneServerPortConflict | — | string | For start/restart only. Answer to EDT's standalone-server port-conflict prompt: cancel (default) = fail and name the busy ports; reassign = let EDT move the server to free ports (rewrites its configuration). |

## Guide
Reports, stops, starts or restarts the EDT standalone server (`ibsrv`) of a `wst-server` application - for example to release a file infobase so Designer or `ibcmd` can open it directly, and to bring the server back afterwards.

## Scope

Only applications whose EDT type is `wst-server` are served; a file or client/server infobase application is refused with its type named (`get_applications` lists the types). `applicationId` defaults to the project's default application; pass it explicitly when the default lookup is slow or the default is not the standalone server.

## Actions

- `status` (default) - reads the server: `state` (`STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `UNKNOWN`), `liveLaunch`, the `ibsrv` `pid` and `processAlive` (only when EDT still holds a live launch whose process id could be read; a stale `STARTED` server reports neither), `heldStopped`, `databaseDirectory` (file-backed only) and `infobaseUrl` (while `STARTED`). Takes no infobase lock and changes nothing, except that it releases a hold when it finds the server running under a live launch (somebody started it from EDT) - never while a stop of it is still in flight or a start still claims it.
- `stop` - **destructive**: every session of the infobase, every EDT client launch and the server's debug session end. Call it first WITHOUT `confirm`: the answer is `action: "preview"` with `confirmationRequired: true`, the `sessions` block (`reachable`, `count`, `designerCount`, non-personal rows), `clientLaunches` and `debugTargets`, and nothing is stopped. Repeat with `confirm=true` to stop. Active non-Designer sessions are a warning, not a refusal.
- `start` - starts a stopped server and releases the hold once the server is observed `STARTED`. If EDT reports the start done but the server does not read `STARTED` within 5 seconds, the answer is an error (with `mutationOutcomeUnknown: true` while it still reads `STARTING`) and the hold stays. A server already running under a live launch is left alone (`alreadyRunning: true`).
- `restart` - stop (preview first, `confirm=true` required) followed by a start in the same call.

## How the stop is verified

The stop is EDT's own application cleanup, a forced stop of the WST server, run under the same per-application guard as the stale-server recovery and under the per-infobase lock that `update_database`, `run_yaxunit_tests`, `infobase_sessions`, `infobase_extensions`, `export_configuration_to_file` and `build_external_objects` (for the default application it prepares) also take (`launch` does not take it; it is refused by the hold instead). EDT reports `STOPPED` even when its own 6-second wait for the process gave up, so the tool waits up to 20 seconds for the `ibsrv` process it read under the stop's guard, right before dispatching the stop, to exit (that process, not whatever later process reuses its id):

- process gone - `success: true`, `processAlive: false`, `mutationCommitted: true`;
- process still alive - `success: false`, `processAlive: true` and the `pid`. Do NOT kill it with OS tools: a forced kill can damage a file infobase. Wait and call `status` again; if it never exits, stop it in EDT's Servers view or restart EDT.

When EDT's process is gone, EDT's hold on the file infobase is released. Other processes that opened the same files are not inspected, so "unlocked" is never claimed.

A stop that does not finish within 60 seconds answers `mutationOutcomeUnknown: true`; the server is held and further starts are refused until EDT's stop Job actually ends - there is no time limit, because a stop finishing late would take down a server started in the meantime. `status` then names how long ago that stop started; if it never ends, end it in EDT's Servers view or restart EDT.

## The hold

A confirmed `stop` (also on an already stopped server - that one asks no consent, and is refused if another start already claims the server or somebody started it meanwhile) holds the server stopped. Until `action=start`, every plugin-driven start of it is refused with a message naming this tool: `launch`, `run_yaxunit_tests`, `update_database`, `build_external_objects`, `export_configuration_to_file`, and the restoration of a server an operation stopped. The hold lives in memory only (an EDT restart clears it) and EDT's own UI does not consult it: starting the server from EDT's Servers view releases the hold the next time a tool sees the server running under a live launch, unless a stop of it is still in flight or a start still claims it.

A failed or unfinished start keeps the hold. When EDT's stop ran and failed, the answer carries `mutationOutcomeUnknown: true`; the hold stands if the server reads STOPPED afterwards and is otherwise put back as it was. A stop refused before anything was dispatched says so and leaves the hold as it was.

## Parameter details

- `projectName` (required) - the EDT project.
- `applicationId` - an id from `get_applications`; omitted means the project's default application.
- `action` - `status` (default), `stop`, `start` or `restart`.
- `confirm` - for `stop`/`restart` only: omit for the preview, `true` to act. Refused on `status` and `start`.
- `standaloneServerPortConflict` - for `start`/`restart` only: `cancel` (default) fails and names the busy ports; `reassign` lets EDT move the server to free ports and rewrites its configuration (`portsReassigned: true`).

## Examples

Release a file infobase for Designer:

```text
standalone_server(action='stop', projectName='MyProject', applicationId='ServerApplication.MyServer')
standalone_server(action='stop', projectName='MyProject', applicationId='ServerApplication.MyServer', confirm=true)
```

Bring it back:

```text
standalone_server(action='start', projectName='MyProject', applicationId='ServerApplication.MyServer')
```

Alternatives that keep the server running: connect Designer through the server (`1cv8 DESIGNER /S <server address>\<infobase>`), or run `ibcmd` against the running process with `--pid`.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
