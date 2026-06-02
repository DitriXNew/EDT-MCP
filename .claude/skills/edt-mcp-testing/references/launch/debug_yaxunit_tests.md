# debug_yaxunit_tests — how to test

**Purpose.** Launches YAXUnit tests in **DEBUG mode** so breakpoints set via `set_breakpoint` actually trip when the test runs the code under inspection. Unlike `run_yaxunit_tests`, this tool does **not** poll for `junit.xml` — after the launch is queued it returns immediately and the LLM is expected to call `wait_for_break` next. The full debug cycle (from the class Javadoc) is:

```
set_breakpoint → debug_yaxunit_tests → wait_for_break
  → get_variables / evaluate_expression / step → resume
```

The `junit.xml` report is still written to the returned `reportDir`, so a follow-up file read (or `run_yaxunit_tests`) can pick it up after the test finishes. This is a **heavy, mutating, infobase-exclusive** tool: it spawns a real 1С client, terminates any live launch on the same IB, and (by default) runs a silent DB update. **Do not run it live unattended** — this reference documents the procedure from source; it was not live-called.

Source: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/DebugYaxunitTestsTool.java` (verified). Auto-chain helper: `utils/LaunchLifecycleUtils.java` (`prepareForFreshLaunch`, `PreLaunchResult.summary()`).

**Preconditions.**
- Live (non-elevated) EDT copy, workspace `D:\WS\EDT`, MCP on `:8765`; confirm with `get_edt_version`, not the redeploy exit code (`pwsh D:\Soft\edt-redeploy.ps1` may exit 1 yet print `MCP server UP on 8765` = success).
- Target project (`TestConfiguration`) is open and `State=ready` (check `list_projects`). After a `-clean` relaunch the index rebuilds for a while.
- **An existing runtime-client launch configuration** (`list_configurations` → e.g. `TestConfiguration Thin Client`). The tool rejects any config whose type is not `LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID` (the EDT RuntimeClient type) with `"… is not a runtime-client config — YAXUnit tests require one."`.
- **YAXUnit extension installed in the infobase** (the tool feeds 1С a `RunUnitTests=<xUnitParams.json>` startup option; without YAXUnit the client ignores it and no breakpoint fires).
- A breakpoint set on a line that the chosen test method actually executes — otherwise the cycle never suspends. See the shared [debug SETUP](../debug/SETUP.md) for how to plant executable code + a breakpoint in `TestConfiguration`.
- **Infobase exclusivity for the default DB update**: with `updateBeforeLaunch=true` (default), the auto-chain needs exclusive access to the file infobase. If another 1С client holds it, the update hangs on `Connecting to designer agent for infobase <name>` and times out.

**Call (documented — DO NOT run live unattended).** Pin to a single test method so the cycle is predictable (the description explicitly recommends "a tight tests filter").

```
# 1. Set the breakpoint inside the code the test exercises (see debug/SETUP.md).
set_breakpoint(projectName="TestConfiguration",
               modulePath="CommonModules/<ModuleUnderTest>/Module.bsl",
               lineNumber=<exec line>)

# 2. Launch the test under the debugger (preferred: by exact config name).
debug_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client",
                    tests="<TestModule.TestMethod>",
                    updateBeforeLaunch=true)

# Legacy addressing (when launchConfigurationName is omitted) — BOTH required:
# debug_yaxunit_tests(projectName="TestConfiguration",
#                     applicationId="<id from get_applications>",
#                     tests="<TestModule.TestMethod>")

# 3. Block until the breakpoint trips (returns immediately on its own; you wait here).
wait_for_break(applicationId="<from the response>", timeout=30)

# 4. Inspect at the suspended frame.
get_variables(frameRef=<frameRef>)
evaluate_expression(frameRef=<frameRef>, expression="<expr>")
step(threadId=<threadId>, mode="over")

# 5. Continue / finish.
resume(threadId=<threadId>)
```

Filter params are all comma-separated and optional: `extensions`, `modules`, `tests` (Module.Method format, recommended). They map to the YAXUnit `filter.{extensions,modules,tests}` arrays in the generated `xUnitParams.json` (`buildParamsJson`); the JSON also pins `reportFormat="jUnit"` and `closeAfterTests=true`. Omitting all three runs the whole suite (heavier, not recommended under the debugger).

**Verify / observe.** This tool does not report test results itself. Confirm progress and outcome out-of-band:
- Suspension is confirmed by `wait_for_break` returning `{"hit":true, ...}` — see [debug/wait_for_break.md](../debug/wait_for_break.md).
- Live session state: `debug_status` (`count>=1`) and/or `Get-Process 1cv8c`.
- After the run finishes, the JUnit report lives at the returned `junitXml` path; read it directly, or hand the same `reportDir` parent to a file read. (`run_yaxunit_tests` produces the Markdown summary; this tool deliberately does not.)
- Full request/response is in the EDT log `D:\WS\EDT\.metadata\.log` — the source of truth when the tool-output channel is flaky.

**Revert (MUTATION SAFETY).** This tool mutates the live infobase (DB update) and may terminate other launches, but it does **not** edit repo source. If you planted code/breakpoints in `TestConfiguration` for the setup, undo them:

```
# Stop the spawned client / debug session:
terminate_launch(launchConfigurationName="TestConfiguration Thin Client")   # or Stop-Process -Name 1cv8c -Force
remove_breakpoint(...)            # see debug/remove_breakpoint.md; check actual ids with list_breakpoints
# Revert any TestConfiguration source you edited through MCP:
git checkout HEAD -- TestConfiguration
git clean -fd -- TestConfiguration
```

Always mutate **through MCP** so the EDT model and disk stay in sync — a `-clean` redeploy discards unsaved in-memory edits.

**Result.** No live call was made. Representative success shape, built from source (`execute`, lines ~282-293):

```json
{
  "success": true,
  "launched": true,
  "projectName": "TestConfiguration",
  "applicationId": "<resolved application id>",
  "reportDir": "<java.io.tmpdir>/edt-mcp-yaxunit-debug/TestConfiguration-<millis>-<counter>",
  "junitXml": "<reportDir>/junit.xml",
  "nextStep": "call wait_for_break with the same applicationId",
  "preLaunch": "terminated 1 live launch; DB ready"
}
```

Notes on the shape (all field names verified against the source):
- `preLaunch` is **only present** when the auto-chain actually terminated something (`preLaunch.getTerminatedCount() > 0`). Its string comes from `PreLaunchResult.summary()`: `"terminated N live launch(es); DB ready"`, or `"no-op (no live launch held the lock; DB ready)"` when nothing was running.
- `applicationId` echoes the *effective* id — when you call by `launchConfigurationName`, it is derived from the config's `ATTR_APPLICATION_ID`, not from your input.
- There is **no** test pass/fail data here; that is in `junitXml` once the test completes.

Representative error shape (genuine failure → `ToolResult.error(...).toJson()`, `{"success":false,"error":...}` with `isError:true`):

```json
{"success":false,"error":"Launch configuration not found: 'TestConfiguration Thin Client'"}
```

Other error strings emitted by the source (same JSON shape): `"projectName is required (or pass launchConfigurationName)"`, `"applicationId is required (or pass launchConfigurationName)"`, `"Launch configuration '<name>' is not a runtime-client config — YAXUnit tests require one."`, `"Application not found: <id>"`, `"Project is closed: <name>"`, `"Pre-launch preparation failed: <reason>. If the previous launch is stuck, call terminate_launch with force=true and retry…"`, and `"Launch failed: <msg>"`.

**Gotchas.**
- **Heavy + infobase-exclusive + mutating — not for unattended runs.** It spawns a real 1С client and, by default, runs a silent DB update that needs exclusive access to the file IB. If another client holds the IB, the update hangs (`Connecting to designer agent…`) and the MCP call times out. Close other clients first (`Stop-Process -Name 1cv8,1cv8c`); **an elevated 1С can't be killed from a non-elevated shell** (`Access denied`) — the user must close it.
- **`updateBeforeLaunch` default is `true`.** The auto-chain (`LaunchLifecycleUtils.prepareForFreshLaunch`) politely terminates every live launch on the same `(project, applicationId)`, sweeps stale Attach launches, then brings the IB to `UPDATED` so EDT's launch delegate skips its modal "Update database?" dialog (which would otherwise block the MCP call). Setting `updateBeforeLaunch=false` keeps legacy behaviour — the delegate decides, and **a modal dialog may appear and block the call**.
- **Without an update, the breakpoint may not fire.** Source you wrote via `write_module_source` lands in the workspace/on disk, not in the running IB's configuration. If you skip the update, the client runs the *old* config and the new code (and its breakpoint) is never executed. Keep `updateBeforeLaunch=true`.
- **Returns immediately — it does NOT wait for a break.** The success response only means the launch was *queued*. You must call `wait_for_break` next (the `nextStep` field reminds you). Pass the same `applicationId`; if exactly one debug session is active it auto-resolves (A13). A `wait_for_break` timeout is `{"hit":false}` and does NOT kill the launch — call it again to keep waiting.
- **Owned-launch protection.** A concurrent `run_yaxunit_tests`/`debug_yaxunit_tests` against the same IB will get `"Another test run is already in progress for this IB …"` (the registry refuses to terminate an owned launch). Wait, or `terminate_launch` first. Different `(project, applicationId)` pairs run in parallel.
- **Use a tight `tests` filter.** A single `Module.Method` makes the suspend deterministic; running the whole suite under the debugger is slow and may suspend at an unexpected first breakpoint.
- **`reportDir` is a fresh unique temp dir per call** (`java.io.tmpdir/edt-mcp-yaxunit-debug/<project>-<millis>-<counter>`). Use the path the call returned; don't assume a stable location. `xUnitParams.json` is written with native separators because YAXUnit builds `file://` URIs that break on forward slashes on Windows.
- **Error contract.** Genuine failures are `{"success":false,"error":...}` with `isError:true`. A stuck pre-launch points you at `terminate_launch force=true`; as a last resort `updateBeforeLaunch=false` (with the modal-dialog caveat above).
- **Flaky output channel.** If the tool-output text is dropped/garbled (e.g. a bare `Error`/`Done`), do **not** retry-spam — re-verify via the EDT log `D:\WS\EDT\.metadata\.log` and `debug_status` / `Get-Process 1cv8c`.
- **Bilingual.** The `tests`/`modules` filter uses programmatic object **names** (not synonyms). The type token (Справочник/Catalog) can be bilingual; object names are literal; synonyms are keyed by language **code**. The breakpoint handler name in your setup module depends on `scriptVariant` (English `OnStart`/`Message` vs Russian `ПриНачалеРаботыСистемы`/`Сообщить`) — check `get_configuration_properties`.

**Debug family cross-links:** [SETUP](../debug/SETUP.md) · [set_breakpoint](../debug/set_breakpoint.md) · [list_breakpoints](../debug/list_breakpoints.md) · [remove_breakpoint](../debug/remove_breakpoint.md) · [debug_launch](../debug/debug_launch.md) · [wait_for_break](../debug/wait_for_break.md) · [get_variables](../debug/get_variables.md) · [evaluate_expression](../debug/evaluate_expression.md) · [step](../debug/step.md) · [resume](../debug/resume.md) · [debug_status](../debug/debug_status.md) · [terminate_launch](../debug/terminate_launch.md)
