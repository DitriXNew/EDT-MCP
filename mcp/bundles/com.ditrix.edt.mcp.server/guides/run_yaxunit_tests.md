Starts a named background job that launches the 1C:Enterprise application with the `RunUnitTests` startup parameter, waits for the launch to terminate, parses the JUnit XML report, and retains that report by `jobId`. A short run still returns its Markdown report directly from the start call. The full report is also written to `report.md` next to `junit.xml`.

## When to use

Use after writing or changing test code to verify it. Prerequisites: an existing runtime-client launch configuration for the project/application, and the YAXUnit extension installed in the target infobase. Without YAXUnit no JUnit XML is produced and the tool returns an error.

## Parameter details

Two ways to identify the launch:

- `launchConfigurationName` (preferred) — the exact runtime-client config name from `list_configurations`. When set, `projectName` and `applicationId` are derived from it.
- `projectName` + `applicationId` — required together when `launchConfigurationName` is omitted. Get the application id from `get_applications`.

Optional test filters (each an array of names; the families are AND-combined with one another, while the values WITHIN one family are OR-ed. A comma-separated string is also accepted):

- `extensions` — restrict to tests in these extensions.
- `modules` — restrict to these test modules.
- `tests` — individual tests in `Module.Method` format.
- `tags` — restrict to tests carrying one of these YAXUnit tags.

### Filtering by tag

Tags are declared next to the test, so they stay in step with it — which is what a hand-maintained
`modules` list cannot do. `ЮТТесты.Тег("юнит")` before the first suite tags the whole MODULE;
after `ДобавитьТестовыйНабор(...)` it tags that SUITE; on a test it tags that test.

The tool only puts the list into `filter.tags`; YAXUnit itself does the selecting. What that means
in practice (verified against YAXUnit v25.12):

- A test is selected when its MODULE, its SUITE, **or** the test itself carries a listed tag — a
  module-level tag covers everything inside it.
- Matching is **case-insensitive** (`Smoke` and `smoke` are the same tag).
- Exclusion is **not supported** by the framework. There is no "everything except" syntax; a
  leading `-` is matched literally, as part of the tag name. Select the layer you want by tagging it.
- An **empty** list is not a filter — the run is unfiltered, exactly as if `tags` had been omitted.
- A tag no test carries selects **nothing**, and neither the tool nor the framework treats that as
  a bad request — there is no "unknown tag" error to expect. What you get back is the report of a
  run in which nothing matched. (If no report is produced at all, the call fails with the
  missing-report error; see the gotcha below, which cannot distinguish that from a
  YAXUnit-not-installed infobase.)
- A value may not contain a comma: the families are comma-separated on the way in, so `a,b` is
  read as two tags, never as one tag named `a,b`. This applies to `extensions`/`modules`/`tests` too.

Control:

- `timeout` — how many seconds the start call waits for the background job (default and maximum 45; a larger value is clamped). It does not limit the job's server-side lifetime. See ## Polling and Pending.
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

**The start call is bounded.** `timeout` is how long `run_yaxunit_tests` waits for its named job, and it is clamped to **45 seconds**. That ceiling is deliberate: an MCP client cuts a call at roughly 60 seconds. The job itself continues server-side through resolution, pre-launch preparation, spawn, test execution, and report collection.

If the job finishes in this window, the tool returns the parsed JUnit report in the same shape as before. If it does not, the reply is **Pending**, carries a `jobId`, and includes the job's progress journal. Poll that identity with:

```json
{ "jobId": "<id-from-run_yaxunit_tests>", "waitSeconds": 45 }
```

using `get_job_status`. Do not repeat the original arguments to address a run you already know: changing one argument would describe another request, while the `jobId` is the run's actual identity.

The progress journal names these phases:

| phase | what the server is doing |
|---|---|
| `resolve` | resolving the launch configuration and its application |
| `prep:terminate` | sweeping live / stale launches of this application |
| `prep:recompute` | force-recomputing the scoped projects |
| `prep:db-update` | updating the infobase |
| `spawn` | starting the 1C client |
| `run` | the client is running the tests |

**What the phase can and cannot tell you.** A phase that ADVANCES between polls proves the server is making progress — keep waiting. A phase that stops changing is ambiguous, and honestly so: a `prep:recompute` that sits still for forty minutes is normal on a large configuration, and one blocked on a modal dialog looks exactly the same from here. There is no signal that separates them, so **when a phase stops advancing, look at EDT** for a dialog waiting for a click instead of waiting indefinitely. Running the pre-flight above is what keeps that case rare.

The run key still matters, but only as an in-flight duplicate guard. It includes the resolved target, every filter family (including `tags`), the update flag/scope, and the external-change policy. A new call describing the same execution while its job is live attaches to that job instead of launching a second 7-minute run. It is not how a caller addresses a known run; use `jobId` for that.

Completed reports stay fetchable with `get_job_status(jobId=...)` until the bounded registry evicts them. At the same time, a new `run_yaxunit_tests` call after terminal completion starts a **fresh run**; completed jobs are never selected by argument identity. This preserves real reruns without sacrificing a report when an agent loses its earlier context.

### Cancellation boundary

Call `cancel_job` without `confirm` first. The preview changes nothing and names the owning tool, state, progress, and the destructive effect of stopping a YAXUnit run: the client process is killed, the infobase keeps whatever the tests already did, no rollback is performed, and the JUnit report may be partial or absent.

Before the commit handshake, `confirm=true` cancels the job normally. With `updateBeforeLaunch=true`, commit happens immediately before the auto-chain is handed to an Eclipse background job, because that chain may terminate a client and update the infobase independently. With it disabled, commit happens immediately before `workingCopy.launch()`.

After the client launch exists, this owning tool's declared cancellation capability can terminate it even though the job is committed. A confirmed stop reports `terminated`, never a clean pass/fail outcome. It says explicitly that the infobase was **NOT** rolled back and includes a rendered partial JUnit report only when the XML is usable; otherwise it says the report is absent or incomplete.

`terminated` describes the client launch. The background job remains `running` until its worker callable actually exits, so an identical request keeps attaching to that live job and cannot start a second run against the same stable report directory. Once the worker exits, the job becomes `cancelled` and retains the same partial-report/no-rollback result for every attached caller.

If `ILaunch.terminate()` accepted the request but the client did not confirm termination within the verification wait, the outcome is `terminationRequested`. The same outcome is used when verification is interrupted after `terminate()` returned successfully. It says plainly that termination was requested and cannot be taken back but is not yet confirmed, that the infobase was **NOT** rolled back, and that the job's status will settle when the run actually ends. The job is cancellation-pending immediately: a later normal worker return cannot publish `done`, and an identical run remains attached to this non-terminal job until it resolves to `cancelled`. The stored result remains the honest partial-or-absent report explanation, never a clean pass/fail report.

A failed `terminate()` call, a launch whose `canTerminate()` is false, or the absence of a live client means no termination was initiated. Those cases retain `alreadyCommitted` and leave the existing job running.

The registry's 30-second outer handler guard bounds the `cancel_job` call, not the handler thread's lifetime. If a platform call ignores interruption beyond that guard, polling is released to publish any deferred worker outcome, which is marked as provisional while the job remains claimed. A second cancellation cannot launch another handler, and the run key cannot admit equivalent destructive work while that stale handler is alive. If the handler later reports that nothing was stopped, the worker outcome stands. A verified late `stopped` interrupts a worker that is still running; a late `stopInitiated` does not, because verification is incomplete, so the worker remains `running` until the run ends on its own. If a `done` or `failed` outcome was already published, either destructive result supersedes it and corrects the job to `cancelled` with the honest partial-or-absent report. The correction in the progress journal names the replaced outcome and retains the original failure message when applicable. A poller may therefore see `done` or `failed` corrected to `cancelled` after the handler exits.

A committed job with no live YAXUnit client yet cannot undo already-dispatched preparation and still reports `alreadyCommitted`. Jobs owned by tools without this explicit capability also keep `alreadyCommitted`; `cancel_job` does not infer cancellability from a tool name.

Cancelling an ATTACHMENT is a different thing from cancelling the run. When an equivalent request attaches to a live job rather than launching beside it, cancelling that attachment stops only the waiting: the mirrored run is a separate job that keeps going, and the reply names its `jobId` so it can still be polled with `get_job_status`. Nothing about the tests, the client or the infobase is affected — to stop the run itself, cancel the job the attachment names.

## Auto-chain (updateBeforeLaunch)

Default `true`: before spawning a new test launch, the tool runs the **pre-launch preparation chain** (selectively force-recompute changed projects, wait for the workspace build to settle, politely terminate any live 1C client running this configuration, then run a silent database update) in an Eclipse background job. The owning registry job observes it in **25-second wait slices**:

- **If the chain completes within 25s** the tool proceeds to spawn and poll the test launch as normal.
- **If the chain is still running after 25s** the owning registry job keeps waiting; when the start call's `timeout` expires it returns **Pending** with the same job's `jobId`. `get_job_status` shows the live phase (`prep:terminate` / `prep:recompute` / `prep:db-update`). This prevents MCP client timeouts on large configurations where a recompute can take 2–8 minutes.
- The 25s slice is internal, not a caller-visible lifetime or a reason to start again. The named job owns every slice through the eventual launch and report.

**Dialogs are not impossible with `updateBeforeLaunch=true`.** The auto-chain answers the platform's update dialogs automatically (`Application update`, `Restructure data`, `Infobase configuration changes`), including any that are already on screen when it starts, so the common cases do not block. What it cannot promise is that EDT never raises a dialog outside those windows. If one does appear, the run stops making progress and shows up as a **Pending whose phase stops changing** — check EDT for a dialog waiting for a click, answer it, and the next `get_job_status` poll continues to observe the same job. Running the pre-flight in ## Required order before the first run is what keeps the infobase update out of the launch and makes this case rare.

The recompute step is **selective**: a project is force-recomputed (`recomputeAll`) only when its sources differ from the content state of its last successful preparation, or when a non-derived file change was observed since then; projects with no change get only a cheap derived-data drain that returns immediately when nothing is pending. The "prepared at" mark is a fingerprint of the project content (paths plus workspace modification stamps of all non-derived files) recorded on the project itself, so it **survives an EDT restart** — restarting EDT no longer forces a full recompute by itself, only a real source change does. A project with no recorded mark yet (a fresh workspace, or a preparation that did not complete) still recomputes fully. That fingerprint is read from the workspace's own resource tree, so the first preparation of a project in each EDT session refreshes the project from disk first (bounded — a refresh that cannot finish in time makes the project count as changed): the operating system reports no events for changes made while EDT was **not running**, so without that refresh a `git checkout` performed on a closed workspace would still look unchanged. Whether the infobase itself is then updated stays EDT's own decision: the application update state (`UPDATED` — the value `get_applications` reports) means nothing to update. This eliminates the per-call 2–8 minute delay on large configurations while keeping the stale-`.cfe` safety guarantee: a test extension edited just before the run is still force-rebuilt and its regenerated `.cfe` is loaded into the infobase before the run, and a change that lands *during* the recompute keeps the project dirty for the next run instead of being recorded as prepared.

Set `false` to keep legacy delegate behaviour: NO client sweep (including the debug fresh-run sweep, see ## Debug mode), NO auto-confirmed 'Update database?' dialog (auto-pressing it would perform the very update you opted out of), and the platform's own dialogs may appear and block; no extension-rebuild either, so a freshly edited extension may run stale. If pre-launch preparation fails because a previous launch is stuck, call `terminate_launch` with `force=true` and retry.

On a **standalone-server** application (`applicationId` starting with `ServerApplication.`) the silent-database-update step of the auto-chain is skipped and the DB update is performed by EDT's coordinated launch flow instead (its 'Application update' dialog is auto-confirmed around the launch; no dialog at all when the IB is already in sync). This plugin does NOT pre-update such applications out-of-band: doing so started the standalone server in RUN mode and held a designer-agent connection that wedged the subsequent debug restart. The recompute and terminate-stale steps still run. Consequence: for server apps there is no synchronous 'stale IB' refusal — an update failure surfaces in the run / the EDT log instead.

`updateScope` narrows the outer scope of the recompute+update: `all` (default) covers the configuration plus its dependent extensions; `configuration` covers just the launch project; `extension:<ProjectName>` (comma-separate several) covers the configuration plus only the named extension project(s) — the fast path when only one extension changed. Within the resolved scope the dirty-tracking filter is then applied: a project not in the scope is never recomputed; a project in the scope but not dirty (no file changes since last prepare) gets only the cheap derived-data drain. The configuration project is always included, since an extension cannot reach the infobase without its parent configuration. An unknown extension project name is a HARD ERROR: the call fails fast (before terminating any live client) with a message listing the requested-but-unknown names and the available extension projects — a typo'd name silently skipping the recompute would produce exactly the stale run this parameter prevents. Names are case-sensitive.

## Debug mode (debug=true)

Pass `debug=true` to launch in DEBUG mode so breakpoints set with `set_breakpoint` trip. The background job returns a Markdown launch handle as soon as the launch is spawned, and the start call returns it directly when that happens within `timeout`; then call `wait_for_break`. If pre-launch preparation outlasts the window, **Pending** carries the jobId and `get_job_status` eventually returns the launch handle. The full cycle:

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

A longer run is followed by polling the returned `jobId` with `get_job_status`; `"timeout": 180` is clamped to 45 and never extends the start call.

## Notes

- Response type is Markdown; the report is also saved to `report.md` next to `junit.xml`.
- The temp/report directory is not deleted on completion, and the registry retains the parsed result by `jobId` until eviction.
- Module and test names are 1C identifiers (programmatic `Name`), not synonyms.

## Gotchas

- A start-call timeout returns **Pending**, not a failure. Keep its `jobId` and poll `get_job_status`; do not reconstruct the original arguments to address the run.
- `timeout` above 45 is clamped, silently and on purpose. If a call ever comes back as a bare transport error rather than **Pending**, that is a bug worth reporting: the whole point of the ceiling is that it cannot happen.
- A **Pending whose phase never changes** means waiting alone may not help — look for a modal dialog in EDT. A phase that advances means the server is working; keep polling the same jobId.
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

## Pre-launch recomputation

The pre-launch auto-chain (`updateBeforeLaunch=true`, the default) recomputes only projects whose sources changed since their last prepared run; that mark survives an EDT restart, so an unchanged project is not recomputed at all.

## Standalone server: busy ports

Launching an application served by a 1C STANDALONE SERVER starts that server first. If one of its
ports (HTTP gate / debug server / SSH gate) is already bound — most often by an `ibsrv` left over
from an earlier EDT session — EDT raises the modal **"Standalone server port conflict"** /
**"Конфликт портов автономного сервера"** and waits for a human.

`standaloneServerPortConflict` answers it: `cancel` (default) refuses, so the run fails and the
reason - with the busy ports named - comes back through THIS tool's own result: either in the
initial response, or, when the run was accepted as a background job, from `get_job_status(jobId)`.
It does NOT appear in `debug_status.recentLaunchFailures` - that channel belongs to `debug_launch`.
`reassign` lets EDT move the server to free ports, which **rewrites the server configuration** and
changes the address its clients connect to. See the `update_database` guide for the full table.
