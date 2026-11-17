Starts a 1C client in debug (default) or run mode, or a named standalone server through EDT.

## Quick path

1. `list_configurations`, then `launch({"launchConfigurationName":"<name>"})`. A busy infobase refuses launch within one second, before termination/restart; an active update refusal names its jobId. Poll `get_job_status` and launch after completion. Otherwise the call returns `status: "launching"`.
2. Poll `debug_status`: `starting` while EDT updates the infobase (minutes for large configurations) and starts the client, then `running`.
3. Do not `terminate_launch` or relaunch while it is starting: that interrupts the load and leaves an EDT error dialog. `restartIfRunning=true` refuses a start in progress; `terminate_launch` aborts one only with `force=true`.

## When to use

Use `mode="run"` for EDT's green Run button, or `debug` for breakpoints and stepping. For server-side code (HTTP services, background/scheduled jobs), name an Attach configuration. Inspect with `debug_status`, await a breakpoint with `wait_for_break`, and stop with `terminate_launch`.

## Target selection (choose ONE)

1. **launchConfigurationName**: exact existing configuration name. Runtime-client configurations spawn 1cv8c; Attach configurations connect to a debug server; standalone-server configurations start their server. No applicationId is required. This is the only selection form for Attach or direct standalone-server starts.
2. **projectName + applicationId**: find that application's runtime-client configuration (`get_applications` lists IDs). An unbound configuration matches the project's default application and reports the synthetic `applicationId` used by debug tools: `launch:<name>`.

## Client windows

`windowMode` defaults to `normal`. Runtime-client configurations also accept:

- `minimized`: current desktop, minimize without activation after EDT starts the client.
- `otherScreen`: maximize on another monitor. `screen` is a 1-based OS enumeration index; omission selects the first non-primary monitor. A single monitor or invalid index is refused.
- `isolated`: MCP starts and owns the thin client after the shared EDT pre-launch preparation. Windows uses a hidden desktop and kill-on-close Job Object; Linux uses a private Xvfb display and process group. macOS uses Launch Services to hide without activation: its window exists on the current desktop, because public APIs provide no private desktop.

Linux adjustment needs `xdotool`, `wmctrl`, `xprop` and (for monitors) `xrandr`; isolation needs `xvfb` and `setsid`. macOS window adjustment needs EDT's Accessibility/Automation permission in System Settings > Privacy & Security.

Adjustment polls up to 20 seconds, catches a replacement main window, and reads back the state. Check `windowModeApplied` and `windowModeReason`: an adjustment failure leaves EDT's successful launch running. Isolation failures are errors. `debug_status` reports mode, PID and isolated windows (title/class), or an explicit unavailable reason. `terminate_launch` and plugin shutdown clean up owned processes and desktop/display resources. Debug isolation uses EDT's attach session and its returned debug-server URL; testing and `/Execute` arguments are preserved. Isolation waits for preparation; normal launches return immediately.

## Parameter details

- **mode** (`debug` or `run`, default `debug`) — chooses the EDT launch mode. `debug` starts or attaches a debugger; `run` performs a regular non-debug runtime-client launch. Attach configurations support `debug` only. EDT's standalone-server service always starts a server in DEBUG mode even when `run` was requested, so a standalone-server result reports the effective `mode: "debug"` instead of echoing the request.
- **launchConfigurationName** — exact config name; overrides projectName/applicationId. Find names with `list_configurations`.
- **projectName** (string) — EDT project name; required when launchConfigurationName is absent.
- **applicationId** (string) — from `get_applications`; required in the projectName+applicationId mode.
- **updateBeforeLaunch** (boolean, default true) — bring the infobase up to date with the configuration before the client starts, inside the launch itself: the call returns `launching` at once, `debug_status` shows `state: starting` while EDT updates the infobase (minutes on a large configuration) and `running` once the client is up; an update failure surfaces in `debug_status` / the EDT log. EDT's 'Application update' / 'Обновление приложения' modal is auto-confirmed with 'Update then run', so no dialog blocks. Refused synchronously, with nothing launched: an application EDT does not know or cannot READ inside its bound, and an infobase that `update_database` or another launch is updating right now. A config without a persisted application binding (tracked under a synthetic `launch:<configName>` id) is updated the same way, against the project's default application. Ignored for Attach configs (nothing to update). For a named standalone-server launch configuration, no database update is performed on this route, including when this parameter is omitted. An explicit `updateBeforeLaunch=true` is refused; run `update_database` separately, then call `launch` with `updateBeforeLaunch=false`. With `updateBeforeLaunch=false` the platform may then show that modal: an out-of-date launch watches 750 ms after dispatch and returns `edtDialogs` when it appears. Call `edt_dialog` to list and explicitly press it; UPDATED launches skip this wait. On a **standalone-server** application (`applicationId` starting with `ServerApplication.`) reached through a runtime-client launch, EDT's coordinated launch flow prepares the server in debug mode first and then updates — this plugin does NOT pre-update such applications out-of-band: doing so started the standalone server in RUN mode and held a designer-agent connection that wedged the subsequent debug restart.
- `externalInfobaseChanges` — how to answer EDT's blocking "Infobase configuration changes" modal when the infobase was changed OUTSIDE EDT (Designer, `ibcmd`, a CLI pipeline) since the last EDT interaction: `override` (default) keeps the project configuration and overwrites the infobase, `import` pulls the external changes into the PROJECT sources, `cancel` aborts the update with an error. See ## Infobase changed outside EDT.
- **startupOption** (string) — the 1C `/C` startup option for THIS launch only, e.g. `xddRun DirectoryLoader C:/Tests/bin; xddReport C:/out/result.xml; xddShutdown;`. Applied to a working copy of the configuration; the saved EDT configuration is never modified and no `doSave()` happens. Runtime-client configurations only.
- **externalObjectProjectName** + **externalObjectName** (strings, always together) — run an external data processor / report on startup (the `/Execute` option). See ## Debugging an external data processor.
- **restartIfRunning** (boolean, default false) — what to do when a matching CLIENT session is already running. `false`: return `alreadyRunning: true` and do not relaunch. `true`: non-interactively terminate the existing CLIENT session, wait for it to end, then relaunch — so EDT never raises its blocking 'Debug session already exists' modal; use it for unattended re-launches after a code change. It terminates a client, NEVER a debug server (see Already-running guard). A launch EDT is still starting (no client yet) is NOT restarted: the call is refused, because cancelling the start interrupts EDT's infobase load; wait for `debug_status` state `running`, or abort it with `terminate_launch` and `force=true`.

## Automated testing

Set `automatedTestingMode="TESTCLIENT"` to start a runtime client with the platform's
`/TESTCLIENT` option. `automatedTestingPort=1540` selects its `-TPort` connection port
(integer 1..65535). If omitted, the working copy inherits the saved port; when no port
is saved, EDT uses **1538**. The response reports the effective mode and TESTCLIENT port
from the dispatched configuration, including stored settings when no override is passed.
These are launch settings; they do not confirm that a testing socket is listening or ready.
Unreadable settings are omitted without failing an already-dispatched launch. With no
stored or requested testing mode, both fields are absent; TESTMANAGER has no port field.
Mode values are accepted case-insensitively and stored as `TESTCLIENT` / `TESTMANAGER`.

`automatedTestingMode="TESTMANAGER"` starts the platform's test-manager mode. It takes
no port: `automatedTestingPort` is refused without `TESTCLIENT`. Attach and direct
standalone-server configurations refuse both parameters because they start no client.
These overrides affect this launch only; `create_launch_config` can save them instead.
A launch can switch a saved mode or override its port, but cannot clear the saved mode.
To clear it, delete and recreate the configuration without automated-testing parameters.

Pair testing mode with `startupOption` when the test harness also needs a `/C` command:

```json
{"launchConfigurationName":"MyProject Thin Client","automatedTestingMode":"TESTCLIENT","automatedTestingPort":1540,"startupOption":"RunTests"}
```

Web-client test mode is unverified because
no automated-testing web-client ID is stamped. Starting or driving tests requires an actual infobase.

## Debugging an external data processor

`externalObjectProjectName` + `externalObjectName` name an external data processor or report to run
when the client starts. EDT resolves the object inside that project, BUILDS its `.epf` / `.erf`, and
passes the built file as `/Execute` — so breakpoints inside the processor are hit, because the
debugger maps frames back through the object's project.

**The object is named, never pathed**: no prebuilt disk `.epf`. EDT needs workspace sources to map breakpoints. Import third-party processors (including xUnitForEDT runners) into an external-objects project first. `projectName` selects the configuration being debugged; `externalObjectProjectName` selects the project holding the processor. Both must be open.

Typical xUnitForEDT-style call:

```json
{
  "projectName": "MyConfiguration",
  "applicationId": "<from get_applications>",
  "externalObjectProjectName": "TestRunners",
  "externalObjectName": "xddTestRunner",
  "startupOption": "xddRun DirectoryLoader C:/Tests/bin; xddReport C:/out/result.xml; xddShutdown;"
}
```

The response echoes `startupOption`, `externalObjectProjectName` and `externalObjectName`.

The tool refuses missing objects, the wrong project nature, or disabled external-object dump generation: EDT otherwise logs these failures and starts with no `/Execute` at all. `build_external_objects` dumps directly and does not prove this launch preference is enabled. Both arguments are rejected on an **Attach** configuration, which starts no client.

## Already-running guard

If a live CLIENT session of the same configuration/application exists — including a RUN-mode launch with no debug target, and a debug client EDT tracks only through its debug target manager (started from EDT's UI, or from a configuration without an application id) — the tool returns `alreadyRunning: true` with a `mode` and starts nothing, instead of letting EDT raise its blocking 'Debug session already exists' (code 1003) modal. For a fresh session pass `restartIfRunning=true`, or call `terminate_launch` first.

Only CLIENT-typed sessions count. A standalone-server debug session shares its clients' application id, so it is told apart by the type of its live threads (`IRuntimeDebugTargetThread.getType()`, classified by EDT's `DebugTargetTypeUtil`): a thread typed SERVER (shown as «Сервер») is not a client; unknown types count as client; a target with no live thread is not a client. So launching a client WHILE a debug-server of the same application runs is allowed: it attaches to that server, which is never restarted or terminated.

If the 1003 modal still appears in a debug launch (a race with a second client), an armed auto-confirmer presses **"Keep existing and start new" / "Сохранить старую и запустить новую"**, matched on the message body ('Debug session for project' / 'Сессия отладки для проекта'), never the generic 'Question' title. If that label is absent the dialog is cancelled: the new launch aborts and the existing session survives — the destructive default 'Stop existing and start new' is never pressed. It is armed only for `mode="debug"`, regardless of `updateBeforeLaunch`, and performs no DB update, so `updateBeforeLaunch=false` still holds.

## Examples

- Regular run by name: `launchConfigurationName="MyApp / ThinClient"`, `mode="run"`.
- Debug runtime client by name (default mode): `launchConfigurationName="MyApp / ThinClient"`.
- Attach to debug server-side code: `launchConfigurationName="Attach to 1C:Enterprise Debug Server"`, `mode="debug"`.
- Start a standalone server directly: `launchConfigurationName="Standalone server for MyApp"` (the completed result reports `mode: "debug"`).
- Regular run by project + app: `projectName="MyProject"`, `applicationId="<id from get_applications>"`, `mode="run"`.
- Skip the DB update: add `updateBeforeLaunch=false`.

## Notes

- Returns JSON. On a fresh runtime-client or Attach launch: `launchConfiguration`, `configurationType`, `attach`, `mode`, `status: "launching"`, `project`/`applicationId` (when known), and a `message`. A completed direct standalone-server start reports `status: "running"` and effective `mode: "debug"`. The `alreadyRunning: true` client short-circuit returns the same identity fields but no `status` (nothing was launched).
- Runtime-client and Attach launches are ASYNCHRONOUS and non-blocking: the tool schedules `config.launch(DEBUG_MODE, ...)` for `mode="debug"` or `config.launch(RUN_MODE, ...)` for `mode="run"` as a **background EDT Job** (the same shape EDT's own launch UI uses) and returns `status: "launching"` immediately, WITHOUT waiting for the 1C client to finish starting. The workbench stays responsive and the job remains visible in the **Progress view**. Poll `debug_status` until the launch's `state` is `running`; for debug mode, then use `wait_for_break`. A direct standalone-server start instead uses EDT's self-contained start operation and waits for its real status under a 60-second bound.
- Because an asynchronous launch runs after the call returns, a later launch failure is recorded and surfaced by the NEXT `debug_status` call as `recentLaunchFailures` (last hour), besides going to the EDT error log and the job's result status. This includes a delegate that returns normally after cancelling its progress monitor: the reported failure says EDT abandoned the launch and includes the last relevant EDT error logged during it, when one was available.
- On a not-found config the error payload includes `availableConfigurations` (every debug-capable config: runtime client + attach), so you can pick a valid name.
- The launch goes through a direct `config.launch(...)` with the selected mode to avoid modal EDT dialogs that would block the MCP worker thread. While the background job runs, an auto-confirmer (`LaunchUpdateDialogAutoConfirmer`) is armed to dismiss the launch delegate's 'Application update' modal non-interactively; the delegate's dialogs marshal themselves to the EDT UI thread, where the confirmer's filter fires (the modal's own nested event loop dispatches the button press), and because the MCP worker has already returned `status: "launching"`, the server is never blocked on the dialog.

## Gotchas

- Attach is reachable ONLY via launchConfigurationName; projectName+applicationId never starts an Attach session.
- Attach configurations support only `mode="debug"`; `mode="run"` is rejected before launch.
- `alreadyRunning: true` is a success, not an error — don't retry it; terminate first if you truly need a fresh session.
- `updateBeforeLaunch` has no effect on Attach configs.

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

Launching an application served by a 1C STANDALONE SERVER starts that server first. If one of its
ports (HTTP gate / debug server / SSH gate) is already bound — most often by an `ibsrv` left over
from an earlier EDT session — EDT raises the modal **"Standalone server port conflict"** /
**"Конфликт портов автономного сервера"** and waits for a human.

`standaloneServerPortConflict` answers it: `cancel` (default) refuses, so the launch fails and the
reason — with the busy ports named — appears in `debug_status` under `recentLaunchFailures`;
`reassign` lets EDT move the server to free ports, which **rewrites the server configuration** and
changes the address its clients connect to. A successful direct start reports
`standaloneServerPortsReassigned: true` and a message telling clients to use the new address. See
the `update_database` guide for the full table. If the bounded start times out or is interrupted
while `reassign` remains armed, the error instead carries `mutationOutcomeUnknown: true`: the
background start may still answer a later conflict and rewrite the ports, so inspect the
standalone-server configuration before connecting or retrying.

## Standalone server: "can only start server that is stopped" (handled for you)

EDT starts a standalone server only from the STOPPED state, and treats it as "already running,
nothing to do" only when it is STARTED **and** still holds a live launch. So both a server left
STARTED with a dead launch (EDT waits only a few seconds to confirm the `ibsrv` process died) and a
server that is still STARTING because another operation just launched it end in the platform's
*"Can only start server that is stopped but current server state is 2"* — a failure that would
reach you through `debug_status` (`recentLaunchFailures`).

The state is therefore settled BEFORE the launch: a STARTED server with a live launch is left
alone, one whose launch is gone is stopped through EDT's own application lifecycle, and a
STARTING/STOPPING one is waited for (bounded, 30s) rather than stopped underneath the operation
holding it. A refusal that still arrives is repaired the same way and the launch retried ONCE. See
the `update_database` guide for the full description. If this recovery stopped a server and the
following operation fails conclusively, it makes one attempt to start that same server again and
appends the restore outcome to the failure. A timed-out or interrupted start may still be running,
so no second start is scheduled; if the server remains stopped, the failure names the explicit
`launch(launchConfigurationName='...')` call to use. A server this operation did not stop is never
restored or disturbed.
