# terminate_launch

Terminate one or more 1C launches started from THIS EDT instance; externally launched 1C clients are never touched. Select ONE target mode: launchConfigurationName, projectName+applicationId, or all=true (needs confirm=true). Full parameters and examples: call get_tool_guide('terminate_launch').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact launch configuration name from list_configurations (single-launch mode). |
| projectName | — | string | EDT project name; pair with applicationId for one launch, or with all=true. |
| applicationId | — | string | Application ID from get_applications; requires projectName. |
| all | — | boolean | Terminate every live EDT launch (optionally narrowed by projectName); requires confirm=true. Default false. |
| confirm | — | boolean | Required (true) when all=true; guards against accidental mass termination. |
| force | — | boolean | On polite-termination timeout, escalate to an OS-level process kill; may lose unsaved 1C state. Default false. Ignored for Attach. |
| timeout | — | integer | Polite-wait window per launch in seconds, clamped to [1, 120]. Default from EDT preferences (factory default 10). |
| timeoutSeconds | — | integer | Deprecated alias of 'timeout' (kept for backward compatibility). |
| includeAttach | — | boolean | Whether to act on Attach configs (disconnected, server keeps running). Default true; set false to skip them. |

## Guide
Terminates 1C launches started from THIS EDT instance. The affectable set is exactly `ILaunchManager.getLaunches()` — any 1C client started externally (Designer, ad-hoc 1cv8c.exe, another EDT instance) is invisible to this tool and therefore never touched.

## When to use

Run `list_configurations` first to see what is currently running (look for entries with `running: true`), then pick a selection mode below. Use this to stop a runtime-client launch, or to disconnect an Attach session.

## Selection modes (mutually exclusive — choose exactly ONE)

1. **`launchConfigurationName`** — one live launch by exact config name (from `list_configurations`).
2. **`projectName` + `applicationId`** — one live launch by project + appId (appId from `get_applications`). The search is scoped to the project first, then matches the applicationId, so a same-named appId in another project won't cause a false not_found.
3. **`all=true`** (requires **`confirm=true`**) — every live EDT launch, optionally narrowed by `projectName`.

Combination rules enforced at runtime: `applicationId` requires `projectName`; `applicationId` cannot be combined with `all=true`; `projectName` alone (no applicationId, no all) is ambiguous and rejected; `all=true` without `confirm=true` is rejected.

## Parameters

- **launchConfigurationName** (string) — exact config name; mode 1.
- **projectName** (string) — EDT project; pairs with applicationId (mode 2) or narrows all (mode 3).
- **applicationId** (string) — from `get_applications`; requires projectName.
- **all** (boolean, default false) — terminate every live EDT launch; needs confirm.
- **confirm** (boolean, default false) — must be true when all=true.
- **force** (boolean, default false) — see Force escalation below; ignored for Attach.
- **timeout** (integer, seconds; alias `timeoutSeconds`) — polite-wait window per launch, clamped to [1, 120]. Default comes from EDT preferences (MCP Server -> Tools -> terminate_launch), factory default 10.
- **includeAttach** (boolean, default true) — see Attach behaviour below.

## Attach behaviour

Attach launches (RemoteRuntime / LocalRuntime) are **disconnected**, not killed: the 1C server (ragent/rphost) keeps running, only the debugger detaches. Per the Eclipse Debug Platform contract, disconnect does not flip `ILaunch.isTerminated()`. A detach is reported as `detached`. Set `includeAttach=false` to skip Attach launches entirely.

## Force escalation

By default the tool waits up to `timeout` for a polite `ILaunch.terminate()`. With `force=true`, an unfinished termination escalates to an OS-level `IProcess.terminate()` on the launch's processes (plus a short grace window). This can lose unsaved 1C state. `force` is ignored for Attach launches.

## Result codes

`terminated`, `force_terminated`, `detached`, `timeout`, `already_terminated`, `error`. A `timeout` on a runtime launch suggests re-running with `force=true`.

## Examples

- Single by name: `launchConfigurationName="MyApp / ThinClient"`.
- Single by project + appId: `projectName="MyProject"`, `applicationId="<id from get_applications>"`.
- All for one project, runtime only: `all=true`, `confirm=true`, `projectName="MyProject"`, `includeAttach=false`.
- Force-kill a stuck launch: `launchConfigurationName="MyApp"`, `force=true`, `timeout=5`.

## Gotchas

- Nothing matched returns a `not_found` result, not an error — verify the name/appId against `list_configurations`/`get_applications`.
- `force` only affects runtime launches; it never kills the 1C server behind an Attach session.
- This tool can only ever affect launches owned by this EDT instance.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
