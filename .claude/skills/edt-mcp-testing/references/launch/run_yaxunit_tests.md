# run_yaxunit_tests — how to test

**Purpose.** Run YAXUnit tests inside a launched 1C:Enterprise thin client and return the result as a Markdown JUnit report. The tool resolves a runtime-client launch configuration (by `launchConfigurationName`, or by `projectName` + `applicationId`), writes a YAXUnit `xUnitParams.json` (report path + optional `filter`), launches the client via the EDT debug platform with the `RunUnitTests=<paramsFile>` startup option, **polls** until the launch terminates or the polling window (`timeout`, default 60s) expires, then parses the produced `junit.xml` and renders it as Markdown. Source: `RunYaxunitTestsTool` → `LaunchConfigUtils.resolveLaunchConfig` + `ILaunchConfigurationWorkingCopy.launch(RUN_MODE, …)` + `JUnitXmlParser.parse` + `JUnitMarkdownFormatter.format`. The full Markdown is also written to `report.md` next to `junit.xml` on disk.

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

Filtered run (any combination; all comma-separated). `tests` use `Module.Method` form; `modules`/`extensions` are name lists:
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
5. **Handle Pending.** If the polling window expires before the client finishes, the tool returns **Pending** (it does **not** terminate the launch). **Call the tool again with the identical arguments** to keep waiting and fetch the result once the launch completes. The run-key is derived from the resolved config name + a hash of the filter, so the same args re-attach to the same in-flight launch and the same report directory.
6. **Verify the report.** On completion the tool returns the Markdown summary; the summary table's `Result: PASSED`/`FAILED` and per-failure sections are the verdict. The same content is on disk at the `report.md` path printed in the footer (next to `junit.xml` under `%TEMP%\edt-mcp-yaxunit\<runKey>\`). Cross-check the EDT log `D:\WS\EDT\.metadata\.log` (it logs `Launching YAXUnit tests: config=…, startup=RunUnitTests=…` and `YAXUnit tests completed for …`).
7. **REVERT any source changes you made to author the tests.** If you added/edited BSL test modules in `TestConfiguration` through MCP to exercise this tool, undo them:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   **Note on the infobase:** the auto-chain DB update already restructured the *infobase database*; `git checkout`/`git clean` only restores `TestConfiguration/src`. To realign the DB with the reverted source, run `update_database` (or another `run_yaxunit_tests` with `updateBeforeLaunch=true`) afterwards. The temp report directory is left on disk on purpose (so a re-call can fetch the cached result); it is cleaned automatically before the next fresh launch of the same run-key.

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

**Pending shape** (returned verbatim when the polling window expires; the launch keeps running):
```
**Pending:** YAXUnit tests are still running.

Report directory: `C:\Users\…\AppData\Local\Temp\edt-mcp-yaxunit\TestConfiguration_Thin_Client_<hash>`

Call `run_yaxunit_tests` again with the same arguments to wait further and fetch the JUnit XML once the launch completes.
```

Report fields (from `JUnitTestResults` / `JUnitMarkdownFormatter`):
- **Summary table** — `Total`, `Passed` (`total − failures − errors − skipped`, floored at 0), `Failed`, `Errors`, `Skipped`.
- **`Result: PASSED`** iff `failures == 0 && errors == 0` (skipped does **not** fail the run); else `FAILED`.
- **`## Failures` / `## Errors`** — one `### <Module.Method>` per case with a `**Message:**` line and a compacted stack trace in a fenced block. **`## Skipped`** — a bullet list with optional `— <message>`.
- **Footer** — `*Full report saved to:* <path>` only when `report.md` was actually written; the raw, un-compacted trace stays in the on-disk `junit.xml`.

**Gotchas.**
- **Error contract deviates from the rest of the server.** Because the whole tool is `ResponseType.MARKDOWN`, its failures are emitted as **bare `**Error:** …` Markdown strings**, *not* the `ToolResult.error(...)` → `{success:false,error:…}` JSON envelope with `isError:true` that most tools use. So a YAXUnit "failure" arrives as readable text, and you cannot key off `success:false`/`isError` here — parse the `**Error:**` / `**Pending:**` prefix instead. Representative messages from source: `**Error:** projectName is required (or pass launchConfigurationName)`; `**Error:** applicationId is required (or pass launchConfigurationName). Use get_applications or list_configurations.`; `**Error:** Launch configuration not found: '<name>'. Use list_configurations to see what's available.`; `**Error:** Launch configuration '<name>' is not a runtime-client config — YAXUnit tests require one.`; the `ProjectStateChecker` not-ready message; `**Error:** Project not found/closed: <name>`; `**Error:** Application not found: <id>. Use get_applications to get valid application IDs.`; `**Error:** No JUnit XML report found in <dir>. Make sure YAXUnit extension is installed in the infobase and test configuration is correct.`; `**Error:** Pre-launch preparation failed: <reason>` (with hint to call `terminate_launch force=true` / pass `updateBeforeLaunch=false`); `**Error:** Launch failed: <msg>`; `**Error:** Test execution was interrupted`.
- **It launches a process and updates the DB.** This is genuinely heavy and mutating: a 1cv8c client is spawned and (default) a silent DB update runs first. Run only on `TestConfiguration`, only on explicit request. The launch is **not** terminated when the polling window expires — it keeps running in the background until it self-terminates (YAXUnit params set `closeAfterTests:true`, so the client closes once tests finish).
- **Pending is normal, not an error — re-call to fetch.** `timeout` is a *polling window*, not a kill timer. On expiry you get the Pending text; re-invoke with **identical** arguments to keep waiting. Don't bump `timeout` huge to avoid Pending — a long synchronous poll can hit the MCP call timeout; small polls + re-calls is the intended pattern.
- **Result caching.** If no launch is active but a fresh `junit.xml` exists for the run-key (modified < 5 min ago, `CACHE_TTL_MS`), the tool returns the cached result without relaunching. To force a real re-run, change the filter (new run-key) or wait out the 5-minute TTL; a fresh launch also deletes the old temp dir for that run-key first.
- **Run-key / temp dir.** The run-key is `<resolvedConfigName>:<sha1(extensions|modules|tests)>`; the report dir is `%TEMP%\edt-mcp-yaxunit\<sanitized runKey>_<sha1>`. Different filters → different dirs (no collision). The `findJunitXml` fallback accepts `junit.xml`, `report.xml`, `test-report.xml`, or the first `*.xml` in the dir.
- **`updateBeforeLaunch` (default true).** It terminates only launches **started from this EDT instance** and runs a silent DB update so the EDT delegate's modal dialog doesn't block the MCP call. Set `false` for legacy behaviour — but then a modal "Update database?" dialog may appear and **block** the call in a headless run. If the pre-launch step fails because a launch is stuck, `terminate_launch` with `force=true` and retry.
- **Infobase exclusivity / elevation.** A running client holds the IB exclusively. The auto-chain frees *EDT-started* launches; an elevated/external `1cv8`/`1cv8c` cannot be killed from a non-elevated shell and will **stall the pre-launch update at "Connecting to designer agent"**. Resolve the external holder first.
- **Resolution precedence.** If `launchConfigurationName` is given it wins and *derives* `projectName`/`applicationId` from the config's `ATTR_PROJECT_NAME`/`ATTR_APPLICATION_ID` (any passed values are filled only if empty). Only when the name is omitted are `projectName` **and** `applicationId` both required.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the Markdown), do **not** retry-spam — a retry may spawn another client / re-run the DB update. Re-verify independently: read the on-disk `report.md`/`junit.xml` at the path in the footer, check `list_configurations`/`Get-Process 1cv8c` for the live launch, and read the EDT log `D:\WS\EDT\.metadata\.log` (it logs the params file, the launch line, and `YAXUnit tests completed`).
- **Bilingual.** Test/module names in `tests`/`modules`/`extensions` and in the report are **programmatic 1C identifiers**, not synonyms — pass and match the literal `Name` (typically Cyrillic for a Russian configuration), never a translated label. The report's trace compaction (`JUnitMarkdownFormatter`) deliberately keys on language-independent signals: it collapses YAXUnit-internal frames by Cyrillic module-name tokens (`ЮТУтверждения`, `ЮТМетодыСлужебный`, …) — the surrounding `ОбщийМодуль`/`CommonModule` kind word is localized and is ignored on purpose, so the compaction works on any platform UI language. For the in-IDE/debug variant of running these tests see `debug_yaxunit_tests`.
