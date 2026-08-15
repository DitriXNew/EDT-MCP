# run_yaxunit_tests — how to test

**Purpose.** Run YAXUnit tests inside a launched 1C:Enterprise thin client and return the result as a Markdown JUnit report. The tool starts a named `BackgroundJobs` job that resolves a runtime-client launch configuration (by `launchConfigurationName`, or by `projectName` + `applicationId`), writes a YAXUnit `xUnitParams.json` (report path + optional `filter`), launches the client via the EDT debug platform with the `RunUnitTests=<paramsFile>` startup option, waits up to the start-call window (`timeout`, default and maximum 45s), then parses the produced `junit.xml` and renders it as Markdown. A short run returns the report directly; otherwise the start call returns **Pending** with a `jobId`, while the job continues. Source: `RunYaxunitTestsTool` → `BackgroundJobs` + `LaunchConfigUtils.resolveLaunchConfig` + `ILaunchConfigurationWorkingCopy.launch(RUN_MODE, …)` + `YaxunitReportUtils`. The full Markdown is also written to `report.md` next to `junit.xml` on disk.

> **HEAVY / mutating launch — explicit-request-only.** This actually **spawns a 1C client process**, runs code in the infobase, and (by default `updateBeforeLaunch=true`) first **terminates any live client on this configuration and silently updates the database**. It needs **exclusive** infobase access and produces real side effects (DB update, a running 1cv8c process). Do **not** run it live as part of routine reference drafting — document the procedure and only execute on an explicit request, against `TestConfiguration` only.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project **`State=ready`** — the tool calls `ProjectStateChecker.checkReadyOrError(projectName)`, so a still-indexing project returns the not-ready error rather than launching. Poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext index rebuilds for a while).
- **An existing runtime-client launch configuration** for the project. Only `com._1c.g5.v8.dt.launching.core.RuntimeClient` configs are accepted — Attach configs are rejected. Discover the exact name with `list_configurations(projectName="TestConfiguration")` (e.g. `TestConfiguration Thin Client`); discover the application `id` with `get_applications(projectName="TestConfiguration")`.
- **YAXUnit extension installed in the infobase.** YAXUnit is what actually interprets the `RunUnitTests` startup parameter, runs the tests, and writes `junit.xml`. Without it the client launches, terminates, and the tool reports `No JUnit XML report found …` (see error contract). The `TestConfiguration` repo project must therefore have a YAXUnit extension applied to its infobase for a real run.
- **Exclusive infobase access** (or rely on the auto-chain). With `updateBeforeLaunch=true` (default) the tool politely terminates any *EDT-started* live launch on this config and runs a silent DB update before spawning, so EDT's launch delegate does not pop a modal "Update database?" dialog. An **elevated/external** `1cv8`/`1cv8c` holding the IB cannot be terminated from a non-elevated shell and will block/stall the pre-launch DB update.

**Call (DOCUMENTED — not executed here).** Target the config by name (preferred) and run every test in the infobase:
```
run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client")
```

Or target by project + application id (use the `id` from `get_applications`):
```
run_yaxunit_tests(
  projectName="TestConfiguration",
  applicationId="82e532bc-b103-401d-9ce2-6f0785aad340"
)
```

Filtered run (any combination; all comma-separated). `tests` use `Module.Method` form; `modules`/`extensions` are name lists; `tags` are YAXUnit tags (`ЮТТесты.Тег(...)`, case-insensitive, matched at module/suite/test level, no exclusion syntax):
```
run_yaxunit_tests(
  launchConfigurationName="TestConfiguration Thin Client",
  modules="ПервыйТестовыйМодуль,ВторойТестовыйМодуль",
  tests="ПервыйТестовыйМодуль.ТестДолженПроверитьСложение",
  extensions="ТестовоеРасширение",
  timeout=120
)
```

**Full test procedure (explicit-request-only; documented, do NOT run live during reference drafting).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`.
2. **Confirm the config + app exist.** `list_configurations(projectName="TestConfiguration")` → note the runtime-client `name` and its `running` state; `get_applications(projectName="TestConfiguration")` → note the application `id`. Confirm YAXUnit is installed in the infobase.
3. **Free the infobase (or let auto-chain do it).** If any config shows `running:true`, either leave `updateBeforeLaunch=true` (the tool terminates EDT-started launches itself) or `terminate_launch` it first. Verify externally with `Get-Process 1cv8,1cv8c` — an *elevated/external* holder must be resolved by the user.
4. **Call** `run_yaxunit_tests(...)` as above (start with a small `tests=` filter to keep the run fast).
5. **Handle Pending.** If the start-call window expires before the job finishes, the tool returns **Pending** with a `jobId` (it does **not** terminate the launch). Poll `get_job_status(jobId=<returned-id>, waitSeconds=45)`. Do not reconstruct the start arguments to address the run. The internal run-key remains only a duplicate guard: an equivalent new call attaches while the job is live instead of launching beside it.
6. **Verify the report.** On completion the tool returns the Markdown summary; the summary table's `Result: PASSED`/`FAILED` and per-failure sections are the verdict. The same content is on disk at the `report.md` path printed in the footer (next to `junit.xml` under `%TEMP%\edt-mcp-yaxunit\<runKey>\`). Cross-check the EDT log `D:\WS\EDT\.metadata\.log` (it logs `Launching YAXUnit tests: config=…, startup=RunUnitTests=…` and `YAXUnit tests completed for …`).
7. **REVERT any source changes you made to author the tests.** If you added/edited BSL test modules in `TestConfiguration` through MCP to exercise this tool, undo them:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   **Note on the infobase:** the auto-chain DB update already restructured the *infobase database*; `git checkout`/`git clean` only restores `TestConfiguration/src`. To realign the DB with the reverted source, run `update_database` (or another `run_yaxunit_tests` with `updateBeforeLaunch=true`) afterwards. The temp report directory is left on disk on purpose; the completed result also remains fetchable through its `jobId` until the bounded registry evicts it. A new start after completion is always a fresh run and cleans the stable report directory before launching.

**Result.** `ResponseType.MARKDOWN` — the tool returns **plain Markdown text for both success and errors** (it does NOT use the `{success:false,error}` JSON envelope; see Gotchas). **Representative success shape from source** (`JUnitMarkdownFormatter.format` + the `readResults` footer) — *not a live capture*:
````
# YAXUnit Test Results

## Summary

| Metric | Count |
|--------|-------|
| Total  | 5 |
| Passed | 4 |
| Failed | 1 |
| Errors | 0 |
| Skipped | 0 |

**Result: FAILED**

## Failures

### ПервыйТестовыйМодуль.ТестДолженПроверитьСложение
**Message:** Ожидалось 4, получено 5
```
{ОбщийМодуль.ПервыйТестовыйМодуль.Модуль(12)}:ЮТест.Проверка(...)
{… 3 internal YAXUnit frames hidden …}
```
---
*Full report saved to:* `C:\Users\…\AppData\Local\Temp\edt-mcp-yaxunit\TestConfiguration_Thin_Client_<hash>\report.md`
````

A clean pass omits the `## Failures`/`## Errors`/`## Skipped` sections and shows `**Result: PASSED**`. When the auto-chain actually terminated a live launch, a one-line pre-launch note is prepended (only when it did real work — a no-op chain is silent):
```
> **Pre-launch:** terminated 1 live launch; DB ready
```

**Pending shape** (representative; the named job keeps running):
```
**Pending:** YAXUnit work continues in background job `<jobId>`. Nothing was cancelled.

Poll it with `get_job_status` using `jobId="<jobId>"`; do not repeat the original arguments to address this run.

# Background job: running

| Field | Value |
|---|---|
| jobId | <jobId> |
| owningTool | run_yaxunit_tests |
| status | running |
```

Report fields (from `JUnitTestResults` / `JUnitMarkdownFormatter`):
- **Summary table** — `Total`, `Passed` (`total − failures − errors − skipped`, floored at 0), `Failed`, `Errors`, `Skipped`.
- **`Result: PASSED`** iff `failures == 0 && errors == 0` (skipped does **not** fail the run); else `FAILED`.
- **`## Failures` / `## Errors`** — one `### <Module.Method>` per case with a `**Message:**` line and a compacted stack trace in a fenced block. **`## Skipped`** — a bullet list with optional `— <message>`.
- **Footer** — `*Full report saved to:* <path>` only when `report.md` was actually written; the raw, un-compacted trace stays in the on-disk `junit.xml`.

**Gotchas.**
- **Error contract deviates from the rest of the server.** Because the whole tool is `ResponseType.MARKDOWN`, its failures are emitted as **bare `**Error:** …` Markdown strings**, *not* the `ToolResult.error(...)` → `{success:false,error:…}` JSON envelope with `isError:true` that most tools use. So a YAXUnit "failure" arrives as readable text, and you cannot key off `success:false`/`isError` here — parse the `**Error:**` / `**Pending:**` prefix instead. Representative messages from source: `**Error:** projectName is required (or pass launchConfigurationName)`; `**Error:** applicationId is required (or pass launchConfigurationName). Use get_applications or list_configurations.`; `**Error:** Launch configuration not found: '<name>'. Use list_configurations to see what's available.`; `**Error:** Launch configuration '<name>' is not a runtime-client config — YAXUnit tests require one.`; the `ProjectStateChecker` not-ready message; `**Error:** Project not found/closed: <name>`; `**Error:** Application not found: <id>. Use get_applications to get valid application IDs.`; `**Error:** No JUnit XML report found in <dir>. Make sure YAXUnit extension is installed in the infobase and test configuration is correct.`; `**Error:** Pre-launch preparation failed: <reason>` (with hint to call `terminate_launch force=true` / pass `updateBeforeLaunch=false`); `**Error:** Launch failed: <msg>`; `**Error:** Test execution was interrupted`.
- **It launches a process and updates the DB.** This is genuinely heavy and mutating: a 1cv8c client is spawned and (default) a silent DB update runs first. Run only on `TestConfiguration`, only on explicit request. The launch is **not** terminated when the polling window expires — it keeps running in the background until it self-terminates (YAXUnit params set `closeAfterTests:true`, so the client closes once tests finish).
- **Pending is normal, not an error — poll the id.** `timeout` is a start-call wait window, not a kill timer and not the job lifetime. On expiry, poll the returned `jobId` with `get_job_status`. Do not bump `timeout` above 45 (it is silently clamped), and do not retry-spam the starting tool.
- **Terminal delivery is durable by id.** A completed report remains fetchable through `get_job_status` until the bounded job registry evicts it; fetching does not consume it. A later `run_yaxunit_tests` start is always a fresh execution, never a cached report.
- **Run-key / temp dir.** The run-key is derived from the resolved target and every execution-affecting filter, update flag, update scope, and conflict policy (built by package-private `buildRunKey`); the report dir is `%TEMP%\edt-mcp-yaxunit\<sanitized runKey>_<sha1>`. It prevents an equivalent live request from launching beside the existing job, but it is not a caller-facing address. Different execution parameters produce different dirs. The `findJunitXml` fallback accepts `junit.xml`, `report.xml`, `test-report.xml`, or the first `*.xml` in the dir.
- **Cancellation is consent-gated and destructive.** Preview `cancel_job(jobId=...)` without `confirm`: it changes nothing and states that terminating a live run kills the client, leaves all test effects in the infobase, and can leave a partial/absent report. With `confirm=true`, uncommitted work is cancelled normally. A committed job with a tracked live launch invokes the cancellation handler declared by `run_yaxunit_tests`, terminates the client, reports `terminated`, says the infobase was **NOT** rolled back, and renders usable partial JUnit XML. A committed preparation with no live launch still reports `alreadyCommitted`. Tools that declare no handler, including `ask_workmate`, also keep `alreadyCommitted`.
- **`updateBeforeLaunch` (default true).** It terminates only launches **started from this EDT instance** and runs a silent DB update so the EDT delegate's modal dialog doesn't block the MCP call. Set `false` for legacy behaviour — but then a modal "Update database?" dialog may appear and **block** the call in a headless run. If the pre-launch step fails because a launch is stuck, `terminate_launch` with `force=true` and retry.
- **Infobase exclusivity / elevation.** A running client holds the IB exclusively. The auto-chain frees *EDT-started* launches; an elevated/external `1cv8`/`1cv8c` cannot be killed from a non-elevated shell and will **stall the pre-launch update at "Connecting to designer agent"**. Resolve the external holder first.
- **Resolution precedence.** If `launchConfigurationName` is given it wins and *derives* `projectName`/`applicationId` from the config's `ATTR_PROJECT_NAME`/`ATTR_APPLICATION_ID` (any passed values are filled only if empty). Only when the name is omitted are `projectName` **and** `applicationId` both required.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the Markdown), do **not** retry-spam — a retry may spawn another client / re-run the DB update. Re-verify independently: read the on-disk `report.md`/`junit.xml` at the path in the footer, check `list_configurations`/`Get-Process 1cv8c` for the live launch, and read the EDT log `D:\WS\EDT\.metadata\.log` (it logs the params file, the launch line, and `YAXUnit tests completed`).
- **Bilingual.** Test/module names in `tests`/`modules`/`extensions` and in the report are **programmatic 1C identifiers**, not synonyms — pass and match the literal `Name` (typically Cyrillic for a Russian configuration), never a translated label. The report's trace compaction (`JUnitMarkdownFormatter`) deliberately keys on language-independent signals: it collapses YAXUnit-internal frames by Cyrillic module-name tokens (`ЮТУтверждения`, `ЮТМетодыСлужебный`, …) — the surrounding `ОбщийМодуль`/`CommonModule` kind word is localized and is ignored on purpose, so the compaction works on any platform UI language. For the in-IDE/debug variant of running these tests see `debug_yaxunit_tests`.
