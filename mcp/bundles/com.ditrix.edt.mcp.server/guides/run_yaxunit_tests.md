Launches the 1C:Enterprise application with the `RunUnitTests` startup parameter, polls until the launch terminates or the polling window expires, then parses the JUnit XML report and returns a Markdown summary. The full Markdown report is also written to `report.md` next to `junit.xml` so you can read it directly from disk.

## When to use

Use after writing or changing test code to verify it. Prerequisites: an existing runtime-client launch configuration for the project/application, and the YAXUnit extension installed in the target infobase. Without YAXUnit no JUnit XML is produced and the tool returns an error.

## Parameter details

Two ways to identify the launch:

- `launchConfigurationName` (preferred) — the exact runtime-client config name from `list_configurations`. When set, `projectName` and `applicationId` are derived from it.
- `projectName` + `applicationId` — required together when `launchConfigurationName` is omitted. Get the application id from `get_applications`.

Optional test filters (each an array of names, AND-combined; a comma-separated string is also accepted):

- `extensions` — restrict to tests in these extensions.
- `modules` — restrict to these test modules.
- `tests` — individual tests in `Module.Method` format.

Control:

- `timeout` — polling window in seconds (default 60). See ## Polling and Pending.
- `updateBeforeLaunch` — auto-chain, default `true`. See ## Auto-chain.
- `updateScope` — which projects to force-recompute + update before the run when `updateBeforeLaunch=true`: `all` (configuration + dependent extensions, default), `configuration`, or `extension:<ProjectName>` (comma-separate several). See ## Auto-chain.

## Polling and Pending

The tool polls for up to `timeout` seconds. If the launch finishes in that window it returns the parsed JUnit report. If the window expires while the launch is still running it returns **Pending** and does NOT terminate the launch. Call the tool again with the SAME arguments to keep waiting and fetch the result once the launch completes. A run key is derived from the config name plus the filter, so identical arguments reattach to the in-flight launch instead of starting a new one. There is NO time-based result cache. A completed result is delivered to the matching identical call exactly once (to satisfy a re-call fetching a previously reported **Pending** run); every later identical call re-runs the tests. Caveat: if you were told **Pending** and never fetched the result, the next identical call returns that old report once (not a fresh run) before subsequent calls re-execute. To force a fresh run after an abandoned Pending, either change the filter (a new run-key carries no pending result) or make one identical call to drain that result, then call again to re-execute. (`terminate_launch` does NOT help here — it stops the Eclipse launch but leaves the once-only pending result to be served by the next identical call.)

## Auto-chain (updateBeforeLaunch)

Default `true`: before spawning a new test launch, the tool FORCES a derived-data recompute (`recomputeAll`) of the project AND its dependent EXTENSION (`.cfe`) projects, waits for the workspace build and derived data to settle, politely terminates any live 1C client running this configuration, then runs a silent database update — so a test you just edited inside an extension is force-rebuilt and its regenerated `.cfe` is loaded into the infobase before the run (not executed stale), and EDT's launch delegate does not pop its modal 'Update database?' dialog that would otherwise block the MCP call. It then waits until the infobase actually reports UPDATED (via EDT's own update-state event) before starting; if the IB cannot be brought in sync in time it REFUSES to run with a clear out-of-sync error rather than producing a stale-green result. Set `false` to keep legacy behaviour (the delegate decides; the dialog may appear and block; no extension-rebuild, so a freshly edited extension may run stale). If pre-launch preparation fails because a previous launch is stuck, call `terminate_launch` with `force=true` and retry.

On a **standalone-server** application (`applicationId` starting with `ServerApplication.`) the silent-database-update step of the auto-chain is skipped and the DB update is performed by EDT's coordinated launch flow instead (its 'Application update' dialog is auto-confirmed around the launch; no dialog at all when the IB is already in sync). This plugin does NOT pre-update such applications out-of-band: doing so started the standalone server in RUN mode and held a designer-agent connection that wedged the subsequent debug restart. The recompute and terminate-stale steps still run. Consequence: for server apps there is no synchronous 'stale IB' refusal — an update failure surfaces in the run / the EDT log instead.

`updateScope` narrows the recompute+update: `all` (default) covers the configuration plus its dependent extensions; `configuration` covers just the launch project; `extension:<ProjectName>` (comma-separate several) covers the configuration plus only the named extension project(s) — the fast path when only one extension changed. The configuration project is always included, since an extension cannot reach the infobase without its parent configuration. An unknown extension project name is a HARD ERROR: the call fails fast (before terminating any live client) with a message listing the requested-but-unknown names and the available extension projects — a typo'd name silently skipping the recompute would produce exactly the stale run this parameter prevents. Names are case-sensitive.

## Debug mode (debug=true)

Pass `debug=true` to launch in DEBUG mode so breakpoints set with `set_breakpoint` trip. Then the tool does NOT poll (it ignores `timeout`): it returns a Markdown launch handle immediately and you call `wait_for_break` next. The full cycle:

```
set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```
Pin to ONE test (`tests`) so exactly one breakpoint trips. The deprecated `debug_yaxunit_tests` tool is a thin alias for this.

A debug run is always a FRESH run: before launching, the tool detects and non-interactively terminates an existing client session of the application — a debug session or a RUN-mode client (including one started from the EDT UI via 'Debug As', which only EDT's debug target manager tracks) — so the launch delegate's blocking 'Debug session already exists' modal is never raised and the call does not hang unattended. Launches owned by other MCP tools (e.g. a concurrent `run_yaxunit_tests` launch of the same application) are exempt from this sweep — each is managed by the tool that spawned it; wait for it or stop it via `terminate_launch` explicitly. The detection is thread-TYPE-aware: it terminates only a live CLIENT session, never the standalone server — a debug-mode standalone server's live thread is typed SERVER and is left running untouched. As a race net, the same 'Keep existing and start new' auto-confirmer that guards `debug_launch` is armed around the launch, so even a 1003 modal that still slips through is pressed automatically.

## Examples

Run all tests via a named config:

```json
{ "launchConfigurationName": "TestClient" }
```

Run by project + application, filtered to two modules:

```json
{ "projectName": "MyProject", "applicationId": "<id-from-get_applications>", "modules": ["Tests_Catalog", "Tests_Document"] }
```

Run a single test method with a longer window:

```json
{ "launchConfigurationName": "TestClient", "tests": "Tests_Catalog.CreateAndPost", "timeout": 180 }
```

## Notes

- Response type is Markdown; the report is also saved to `report.md` next to `junit.xml`.
- The temp/report directory is not deleted on completion so a later call can re-fetch it.
- Module and test names are 1C identifiers (programmatic `Name`), not synonyms.

## Gotchas

- A timeout returns **Pending**, not a failure — do not retry with different arguments; reuse the same ones so the run key matches.
- If no JUnit XML appears after the launch finishes, the YAXUnit extension is likely not installed in the infobase, or the filter matched no tests.
- The config must be a runtime-client launch configuration; other types are rejected.
