# run_yaxunit_tests

Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. The whole call is bounded by `timeout` (default and maximum 45s, larger values are clamped) so it always answers before an MCP transport cuts it: it returns the report, an error, or **Pending** naming the current phase (resolve / prep:terminate / prep:recompute / prep:db-update / spawn / run) — call again with identical arguments to keep waiting; nothing is terminated. A phase that stops changing is the server's only signal — it means either a legitimately long stage or one blocked on a modal dialog in EDT, and the tool cannot tell them apart: look at EDT before waiting indefinitely. Pass `debug=true` to instead launch in DEBUG mode (breakpoints fire) and return at once so you can call wait_for_break. The pre-launch auto-chain (updateBeforeLaunch=true, default) recomputes only projects whose sources changed since their last prepared run; that mark survives an EDT restart, so an unchanged project is not recomputed at all. Requires an existing runtime-client launch configuration and the YAXUnit extension installed in the infobase. Full parameters and examples: call get_tool_guide('run_yaxunit_tests').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client launch config name (preferred; from list_configurations). |
| projectName | — | string | EDT project name (required if launchConfigurationName is omitted). |
| applicationId | — | string | Application ID from get_applications (required if launchConfigurationName is omitted). |
| extensions | — | array | Extension names to filter tests (array; a comma-separated string is also accepted). |
| modules | — | array | Module names to filter tests (array; a comma-separated string is also accepted). |
| tests | — | array | Test names in Module.Method format (array; a comma-separated string is also accepted). |
| timeout | — | integer | Wall-clock window in seconds for the WHOLE call, not just the polling step (default and maximum 45; a larger value is clamped to it, because an MCP transport cuts the call at around 60s and a longer window would return a bare transport error instead of an answer). On expiry the tool returns Pending with the current phase; call again with the same arguments to keep waiting. |
| updateBeforeLaunch | — | boolean | Auto-chain (default: true): force-recompute the project + its extensions, terminate a live client and run a silent DB update first, so a freshly edited extension runs fresh (not stale), auto-answering the platform's update dialogs. This makes a blocking dialog unlikely, NOT impossible: a dialog EDT raises outside the tool's own windows still waits for a human, and the tool reports it as a Pending whose phase stops changing. false: legacy delegate behaviour — no client sweep, no auto-confirmed update dialog; platform dialogs may appear and block. Results are never served from a cache — a completed run is re-executed on the next identical call regardless of this flag. |
| updateScope | — | string | Which projects to rebuild+update before the run: 'all' (configuration + dependent extensions, default), 'configuration', or 'extension:<ProjectName>' (comma-separate several). Forces a derived-data recompute so a freshly edited extension's .cfe is regenerated and loaded into the infobase before the run. Unknown extension names fail the call (the error lists the available names). Only applies when updateBeforeLaunch=true. |
| externalInfobaseChanges | — | string | How to answer EDT's blocking 'Infobase configuration changes' modal when the infobase was changed outside EDT (Designer, ibcmd, a CLI pipeline) since the last EDT interaction: 'override' (default) keeps the project configuration and overwrites the infobase, 'import' pulls the external changes into the PROJECT sources, 'cancel' aborts the update with an error. Omitted, the modal is still answered (with 'override'), so an unattended call never blocks on it. |
| debug | — | boolean | Default false: poll and return the report. true: launch in DEBUG mode so breakpoints fire, return a launch handle as soon as it is spawned and call wait_for_break next. It does not POLL, so `timeout` is not a waiting window here — but the call is still bounded by it: a pre-launch preparation longer than the window returns Pending instead of the handle, and the next identical call picks up where it left off. |

## Guide
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

- `timeout` — wall-clock window in seconds for the WHOLE call (default and maximum 45; a larger value is clamped). See ## Polling and Pending.
- `updateBeforeLaunch` — auto-chain, default `true`. See ## Auto-chain.
- `updateScope` — which projects to force-recompute + update before the run when `updateBeforeLaunch=true`: `all` (configuration + dependent extensions, default), `configuration`, or `extension:<ProjectName>` (comma-separate several). See ## Auto-chain.
- `externalInfobaseChanges` — how to answer EDT's blocking "Infobase configuration changes" modal when the infobase was changed OUTSIDE EDT (Designer, `ibcmd`, a CLI pipeline) since the last EDT interaction: `override` (default) keeps the project configuration and overwrites the infobase, `import` pulls the external changes into the PROJECT sources, `cancel` aborts the update with an error. See ## Infobase changed outside EDT.

## Required order before the first run

Do this once before the first run against an infobase, and again after anything changed the infobase outside EDT:

```
get_applications                      # read updateState of the target application
  -> update_database(projectName, applicationId, confirm: true)   # ONLY if an update is required
  -> run_yaxunit_tests
```

Why it is worth the extra call: applying the infobase update through `update_database` is a call you watch, with its own error if it cannot proceed. Letting the auto-chain do it inside a launch is convenient but harder to observe — if the platform decides it needs a human there, all you see is a `Pending` whose phase stops changing (see ## Polling and Pending).

Note `update_database` identifies the application by `projectName` + `applicationId` from `get_applications` (for example `ServerApplication.MyApp`), NOT by the `applicationId` that `list_configurations` prints for a launch configuration.

## Polling and Pending

**Every call is bounded.** `timeout` is the window for the WHOLE call — resolution, pre-launch preparation, spawn and polling together, not the polling step alone — and it is clamped to **45 seconds**. That ceiling is deliberate: an MCP client cuts a call at roughly 60 seconds, so a longer window does not buy a longer wait, it replaces the tool's answer with a bare transport error carrying no phase and no reason. Ask for less if you want a quick probe; asking for more is silently clamped.

**A call that has not finished the work returns `Pending` naming the phase**, never a transport error:

| phase | what the server is doing |
|---|---|
| `resolve` | resolving the launch configuration and its application |
| `prep:terminate` | sweeping live / stale launches of this application |
| `prep:recompute` | force-recomputing the scoped projects |
| `prep:db-update` | updating the infobase |
| `spawn` | starting the 1C client |
| `run` | the client is running the tests |

Call again with the SAME arguments to keep waiting; nothing is cancelled and the work continues server-side.

**What the phase can and cannot tell you.** A phase that ADVANCES between calls proves the server is making progress — keep waiting. A phase that stops changing is ambiguous, and honestly so: a `prep:recompute` that sits still for forty minutes is normal on a large configuration, and one blocked on a modal dialog looks exactly the same from here (the elapsed counter grows either way — it is wall-clock, not a heartbeat). There is no signal that separates them, so **when a phase stops advancing, look at EDT** for a dialog waiting for a click instead of waiting indefinitely. Running the pre-flight above is what keeps that case rare.

The whole-call window is honoured as an aim, not to the millisecond: when a platform call has to be abandoned rather than returning on its own, the backstop adds a few seconds of grace before answering. The bound that matters holds — the answer arrives well inside the ~60s transport limit either way.

The tool polls for up to the remaining window. If the launch finishes in that window it returns the parsed JUnit report. If the window expires while the launch is still running it returns **Pending** and does NOT terminate the launch. Call the tool again with the SAME arguments to keep waiting and fetch the result once the launch completes. A run key is derived from the config name plus the filter, so identical arguments reattach to the in-flight launch instead of starting a new one. There is NO time-based result cache. A completed result is delivered to the matching identical call exactly once (to satisfy a re-call fetching a previously reported **Pending** run); every later identical call re-runs the tests. Caveat: if you were told **Pending** and never fetched the result, the next identical call returns that old report once (not a fresh run) before subsequent calls re-execute. To force a fresh run after an abandoned Pending, either change the filter (a new run-key carries no pending result) or make one identical call to drain that result, then call again to re-execute. (`terminate_launch` does NOT help here — it stops the Eclipse launch but leaves the once-only pending result to be served by the next identical call.)

## Auto-chain (updateBeforeLaunch)

Default `true`: before spawning a new test launch, the tool runs the **pre-launch preparation chain** (selectively force-recompute changed projects, wait for the workspace build to settle, politely terminate any live 1C client running this configuration, then run a silent database update) in a background job with a **25-second budget**:

- **If the chain completes within 25s** the tool proceeds to spawn and poll the test launch as normal.
- **If the chain is still running after 25s** the tool returns **Pending** with the chain's live phase (`prep:terminate` / `prep:recompute` / `prep:db-update`) — call again with the same arguments; the background preparation continues and the follow-up call waits again (or proceeds to launch if it finds the prep done). This prevents MCP client timeouts on large configurations where a recompute can take 2–8 minutes.
- The 25s budget is a maximum, not an allowance: the wait takes whatever is smaller, the budget or what is left of the call's own window. Waiting a full 25s *after* spending time resolving would push the call past the transport limit, which is how a call that respected every individual limit still died on the wire.

**Dialogs are not impossible with `updateBeforeLaunch=true`.** The auto-chain answers the platform's update dialogs automatically (`Application update`, `Restructure data`, `Infobase configuration changes`), including any that are already on screen when it starts, so the common cases do not block. What it cannot promise is that EDT never raises a dialog outside those windows. If one does appear, the run stops making progress and shows up as a **Pending whose phase stops changing** — check EDT for a dialog waiting for a click, answer it, and the next call continues. Running the pre-flight in ## Required order before the first run is what keeps the infobase update out of the launch and makes this case rare.

The recompute step is **selective**: a project is force-recomputed (`recomputeAll`) only when its sources differ from the content state of its last successful preparation, or when a non-derived file change was observed since then; projects with no change get only a cheap derived-data drain that returns immediately when nothing is pending. The "prepared at" mark is a fingerprint of the project content (paths plus workspace modification stamps of all non-derived files) recorded on the project itself, so it **survives an EDT restart** — restarting EDT no longer forces a full recompute by itself, only a real source change does. A project with no recorded mark yet (a fresh workspace, or a preparation that did not complete) still recomputes fully. Whether the infobase itself is then updated stays EDT's own decision: the application update state (`UPDATED` — the value `get_applications` reports) means nothing to update. This eliminates the per-call 2–8 minute delay on large configurations while keeping the stale-`.cfe` safety guarantee: a test extension edited just before the run is still force-rebuilt and its regenerated `.cfe` is loaded into the infobase before the run, and a change that lands *during* the recompute keeps the project dirty for the next run instead of being recorded as prepared.

Set `false` to keep legacy delegate behaviour: NO client sweep (including the debug fresh-run sweep, see ## Debug mode), NO auto-confirmed 'Update database?' dialog (auto-pressing it would perform the very update you opted out of), and the platform's own dialogs may appear and block; no extension-rebuild either, so a freshly edited extension may run stale. If pre-launch preparation fails because a previous launch is stuck, call `terminate_launch` with `force=true` and retry.

On a **standalone-server** application (`applicationId` starting with `ServerApplication.`) the silent-database-update step of the auto-chain is skipped and the DB update is performed by EDT's coordinated launch flow instead (its 'Application update' dialog is auto-confirmed around the launch; no dialog at all when the IB is already in sync). This plugin does NOT pre-update such applications out-of-band: doing so started the standalone server in RUN mode and held a designer-agent connection that wedged the subsequent debug restart. The recompute and terminate-stale steps still run. Consequence: for server apps there is no synchronous 'stale IB' refusal — an update failure surfaces in the run / the EDT log instead.

`updateScope` narrows the outer scope of the recompute+update: `all` (default) covers the configuration plus its dependent extensions; `configuration` covers just the launch project; `extension:<ProjectName>` (comma-separate several) covers the configuration plus only the named extension project(s) — the fast path when only one extension changed. Within the resolved scope the dirty-tracking filter is then applied: a project not in the scope is never recomputed; a project in the scope but not dirty (no file changes since last prepare) gets only the cheap derived-data drain. The configuration project is always included, since an extension cannot reach the infobase without its parent configuration. An unknown extension project name is a HARD ERROR: the call fails fast (before terminating any live client) with a message listing the requested-but-unknown names and the available extension projects — a typo'd name silently skipping the recompute would produce exactly the stale run this parameter prevents. Names are case-sensitive.

## Debug mode (debug=true)

Pass `debug=true` to launch in DEBUG mode so breakpoints set with `set_breakpoint` trip. Then the tool does NOT poll: it returns a Markdown launch handle as soon as the launch is spawned and you call `wait_for_break` next. `timeout` is therefore not a waiting window here — but the call is still bounded by it, exactly like the polling path: a pre-launch preparation that outlasts the window returns **Pending** with its phase instead of the handle, and the next identical call carries on from there. The full cycle:

```
set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```
Pin to ONE test (`tests`) so exactly one breakpoint trips. The deprecated `debug_yaxunit_tests` tool is a thin alias for this.

With `updateBeforeLaunch=true` (the default) a debug run is always a FRESH run: before launching, the tool detects and non-interactively terminates an existing client session of the application — a debug session or a RUN-mode client (including one started from the EDT UI via 'Debug As', which only EDT's debug target manager tracks) — so the launch delegate's blocking 'Debug session already exists' modal is never raised and the call does not hang unattended. Launches owned by other MCP tools (e.g. a concurrent `run_yaxunit_tests` launch of the same application) are exempt from this sweep — each is managed by the tool that spawned it; wait for it or stop it via `terminate_launch` explicitly. The detection is thread-TYPE-aware: it terminates only a live CLIENT session, never the standalone server — a debug-mode standalone server's live thread is typed SERVER and is left running untouched. With `updateBeforeLaunch=false` the sweep is skipped along with the rest of the auto-chain (legacy delegate behaviour): an existing session is left alone and the platform decides. As a race net, the same 'Keep existing and start new' auto-confirmer that guards `debug_launch` stays armed around the launch regardless of `updateBeforeLaunch` (it performs no DB update, so it does not undo the opt-out): a 1003 modal that appears — slipping through the sweep or raised because the sweep was opted out — is pressed automatically with the non-destructive choice.

## Examples

Run all tests via a named config:

```json
{ "launchConfigurationName": "TestClient" }
```

Run by project + application, filtered to two modules:

```json
{ "projectName": "MyProject", "applicationId": "<id-from-get_applications>", "modules": ["Tests_Catalog", "Tests_Document"] }
```

Run a single test method, waiting the full window:

```json
{ "launchConfigurationName": "TestClient", "tests": "Tests_Catalog.CreateAndPost", "timeout": 45 }
```

A longer run is waited for by CALLING AGAIN, not by asking for a longer window — `"timeout": 180` is clamped to 45 and behaves exactly like the call above.

## Notes

- Response type is Markdown; the report is also saved to `report.md` next to `junit.xml`.
- The temp/report directory is not deleted on completion so a later call can re-fetch it.
- Module and test names are 1C identifiers (programmatic `Name`), not synonyms.

## Gotchas

- A timeout returns **Pending**, not a failure — do not retry with different arguments; reuse the same ones so the run key matches.
- `timeout` above 45 is clamped, silently and on purpose. If a call ever comes back as a bare transport error rather than **Pending**, that is a bug worth reporting: the whole point of the ceiling is that it cannot happen.
- A **Pending whose phase never changes** means waiting will not help — look for a modal dialog in EDT. A phase that advances, or an elapsed counter that grows, means the server is working; keep calling.
- If no JUnit XML appears after the launch finishes, the YAXUnit extension is likely not installed in the infobase, or the filter matched no tests.
- The config must be a runtime-client launch configuration; other types are rejected.

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

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
