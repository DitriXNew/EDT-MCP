# debug_launch

Start an EDT debug session: either an existing config by launchConfigurationName (runtime client OR Attach, the latter needed to debug server-side code), or a runtime-client config matched by projectName + applicationId. If that config is already running it short-circuits with alreadyRunning:true (terminate_launch first to force a restart). Full parameters and examples: call get_tool_guide('debug_launch').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | EDT project name; required unless launchConfigurationName is given. |
| applicationId | — | string | Application ID from get_applications; required in the projectName+applicationId mode. |
| launchConfigurationName | — | string | Exact name of an EDT debug launch config (runtime client or Attach); skips projectName/applicationId. |
| updateBeforeLaunch | — | boolean | Default true: silently apply the configuration->DB update before launching so no 'Update database?' modal blocks the call (even on a Russian-locale EDT the dialog is auto-confirmed); false skips the update and the platform may then show that modal. Ignored for Attach. |
| restartIfRunning | — | boolean | Default false: if a matching session is already running, short-circuit with alreadyRunning:true and do NOT relaunch (call terminate_launch to restart). true: non-interactively terminate the existing session, then relaunch — no 'Debug session already exists' modal blocks the call. |

## Guide
Starts an EDT debug session for a 1C application. There are two ways to pick what to launch, plus an idempotency guard that prevents a second client over a session that is already alive.

## When to use

Use this to bring up a debuggable 1C session before setting breakpoints and stepping. For client-side code, launch a runtime-client config (spawns 1cv8c). For SERVER-side code (HTTP services, background jobs, scheduled jobs) you must use an 'Attach to 1C:Enterprise Debug Server' config — a runtime-client launch cannot hit those breakpoints. After it returns, use `debug_status` to inspect, `wait_for_break` to block until a breakpoint is hit, and `terminate_launch` to stop.

## Modes (choose ONE)

1. **launchConfigurationName** — start an existing EDT launch configuration by its EXACT name. Works for both runtime-client configs (spawns 1cv8c) AND Attach configs (attaches to ragent/rphost for server-side code). Does NOT require applicationId. This is the only mode that can start an Attach session.
2. **projectName + applicationId** — searches the runtime-client configs of that project for a match and launches it. Runtime-client only; cannot reach an Attach config. Get the applicationId from `get_applications`.

## Parameter details

- **launchConfigurationName** (string) — exact config name; if set, projectName and applicationId are ignored. Use `list_configurations` to find the name.
- **projectName** (string) — EDT project name; required when launchConfigurationName is absent.
- **applicationId** (string) — from `get_applications`; required in the projectName+applicationId mode.
- **updateBeforeLaunch** (boolean, default true) — silently apply the configuration->DB update before launching so the EDT launch delegate finds the infobase already UPDATED and shows no 'Update database?' modal. Ignored for Attach configs (nothing to update). The update analysis is shared with the YAXUnit tools: skip when already UPDATED, wait when BEING_UPDATED, otherwise incremental-update. A config without a persisted application binding (tracked under a synthetic `launch:<configName>` id) skips this programmatic update — there is no resolvable application to update — and relies on the auto-confirmer safeguard alone. As a belt-and-suspenders safeguard, the actual `config.launch(...)` is wrapped in an auto-confirmer that programmatically presses 'Update then run' if the delegate's modal still appears — in either EDT locale (English 'Application update' / Russian 'Обновление приложения'), so an unattended Russian-locale EDT never hangs on it. With `updateBeforeLaunch=false` the update is skipped and the platform may then show that modal. On a **standalone-server** application (`applicationId` starting with `ServerApplication.`) the DB update is performed by EDT's coordinated launch flow instead (auto-confirmed by the same armed confirmer; no dialog at all when the IB is already in sync) — this plugin does NOT pre-update such applications out-of-band: doing so started the standalone server in RUN mode and held a designer-agent connection that wedged the subsequent debug restart. Consequence: for server apps there is no synchronous 'stale IB' refusal — the update happens asynchronously inside the launch, and a failure surfaces via `debug_status` / the EDT log, matching EDT-native behaviour.
- **restartIfRunning** (boolean, default false) — controls what happens when a matching CLIENT session is already running. Default (`false`): short-circuit with `alreadyRunning: true` and do NOT relaunch — call `terminate_launch` first if you truly need a fresh session. `true`: non-interactively terminate the existing CLIENT session, wait for it to die (clearing its registry entry), then relaunch — so the EDT launch delegate never raises its blocking 'Debug session already exists' modal. It only ever terminates a live client session, NEVER a debug server: a standalone-server debug session's live threads are typed SERVER (a debug-mode standalone server keeps a live «Сервер» thread), so it is never treated as the duplicate and is left running. Use `restartIfRunning=true` for unattended re-launches after a code change instead of a separate `terminate_launch` + `debug_launch` round-trip.

## Already-running guard

If a CLIENT launch of the same configuration/application is still alive, the tool short-circuits with `alreadyRunning: true` and a `mode` field, and does NOT spawn a fresh client. This also covers a launch started in RUN mode (no debug target): the tool still detects it and refuses to start a second client over it. It ALSO now catches a DEBUG **client** session that EDT tracks only through its debug **target manager** — e.g. one started from the EDT UI ('Debug As'), or a config without a persisted application id — using the SAME (project + application) key EDT's launch delegate uses to raise its blocking 'Debug session already exists' (code 1003) modal. So an unattended `debug_launch` returns `alreadyRunning: true` cleanly instead of hanging on that modal. To force a clean restart (e.g. after code changes that require a new session), call `terminate_launch` first then `debug_launch` again, or pass `restartIfRunning=true` (non-interactively stops the old client session and relaunches in one call).

The already-running detect is scoped to a live CLIENT session specifically: `alreadyRunning`/`restartIfRunning` consider only CLIENT-typed sessions. A 1C **standalone-server** debug session ('Автономный сервер …') and a thin client of the same project share the SAME application id, so the app id alone cannot tell them apart — and mere thread liveness cannot either: a standalone server launched in DEBUG mode carries a LIVE thread typed SERVER (shown as «Сервер» — just the localized presentation of that type). The discriminator is therefore the live thread's **type** (`IRuntimeDebugTargetThread.getType()`, classified by EDT's own `DebugTargetTypeUtil`): a client session has a live CLIENT-typed thread (thin/thick/web/mobile client); a session whose live threads are all server-typed — or that has no live thread at all (profiling/idle server target) — is never a client. Unknown or non-1C thread types conservatively count as client. So **launching a client WHILE a debug-server for the same application is already running is allowed** — even when that debug-mode standalone server has a live server thread: the new client comes up and attaches to the already-debugging server; it does NOT return `alreadyRunning`, it does NOT block the launch, and the server is never restarted or terminated.

If the unlikely preflight→launch race still lets the 1003 modal appear (e.g. a transient where a second client is genuinely racing), an armed auto-confirmer presses its **"Keep existing and start new" / "Сохранить старую и запустить новую"** button (LAUNCH_ANYWAY) on the EDT UI thread so the launch completes without a human — keeping the already-running session (server or other client) alive and starting the new client alongside it, never the default 'Stop existing and start new' button that would terminate it. The 1003 modal is matched on its message body ('Debug session for project' / 'Сессия отладки для проекта'), never the generic 'Question' title; the keep-button is located by its label (falling back to the default button only if that label is absent). This 1003 safety net is armed on every debug launch **regardless of `updateBeforeLaunch`**: pressing it performs no DB update, so it does not undo the `updateBeforeLaunch=false` opt-out (only the separate 'Application update' modal stays gated on `updateBeforeLaunch`, and that one keeps pressing its default 'Update then run' button).

## Examples

- Runtime client by name: `launchConfigurationName="MyApp / ThinClient"`.
- Attach to debug server-side code: `launchConfigurationName="Attach to 1C:Enterprise Debug Server"`.
- Runtime client by project + app: `projectName="MyProject"`, `applicationId="<id from get_applications>"`.
- Skip the DB update: add `updateBeforeLaunch=false`.

## Notes

- Returns JSON. On a fresh launch: `launchConfiguration`, `configurationType`, `attach`, `mode`, `status: "launching"`, `project`/`applicationId` (when known), and a `message`. The `alreadyRunning: true` short-circuit returns the same identity fields but no `status` (nothing was launched).
- The launch is ASYNCHRONOUS and non-blocking: the tool schedules `config.launch(DEBUG_MODE, ...)` as a **background EDT Job** (the same shape EDT's own launch UI uses) and returns `status: "launching"` immediately, WITHOUT waiting for the 1C client to finish starting (it may show login / database-update dialogs). Because the launch runs OFF the UI thread, the EDT workbench stays responsive for its whole duration — including a standalone-server mode-switch restart (non-debug→debug), which can take minutes; the job's progress is visible in the EDT **Progress view**. Poll `debug_status` until the session appears running, then use `wait_for_break`. Because the launch runs after the call returns, a launch failure is NOT reported in this response — it is written to the EDT error log (and the job's result status) instead.
- On a not-found config the error payload includes `availableConfigurations` (every debug-capable config: runtime client + attach), so you can pick a valid name.
- The launch goes through a direct `config.launch(DEBUG_MODE, ...)` to avoid modal EDT dialogs that would block the MCP worker thread. While the background job runs, an auto-confirmer (`LaunchUpdateDialogAutoConfirmer`) is armed to dismiss the launch delegate's 'Application update' modal non-interactively; the delegate's dialogs marshal themselves to the EDT UI thread, where the confirmer's filter fires (the modal's own nested event loop dispatches the button press), and because the MCP worker has already returned `status: "launching"`, the server is never blocked on the dialog.

## Gotchas

- Attach is reachable ONLY via launchConfigurationName; projectName+applicationId never starts an Attach session.
- `alreadyRunning: true` is a success, not an error — don't retry it; terminate first if you truly need a fresh session.
- `updateBeforeLaunch` has no effect on Attach configs.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
