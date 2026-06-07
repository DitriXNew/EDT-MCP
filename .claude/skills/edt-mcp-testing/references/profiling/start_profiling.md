# start_profiling — how to test

**Purpose.** Toggle 1C performance measurement (замер производительности) on an **already running** debug target. Once enabled, EDT tracks every executed BSL line with call count and timing; you then exercise the app and call `get_profiling_results` to read back the per-module, per-line coverage/timing report. Source: `StartProfilingTool` → `DebugSessionRegistry.findActiveTarget(applicationId)` → adapt the `IDebugTarget` to `com._1c.g5.v8.dt.profiling.core.IProfileTarget`, then `IProfilingService.toggleProfiling(profileTarget)` resolved by reflection via `ServiceAccess.get(IProfilingService.class)`.

This is **quasi-mutating**: it does not change the model or any file on disk, but it flips live debug/runtime state (profiling on↔off) on a running 1C application. There is **nothing in the repo to `git checkout`/`git clean`** — you "revert" by toggling profiling back off (call the tool again) and/or terminating the debug session. Treat it like a debug-session operation, not a metadata-write operation.

**Preconditions.**
- Running MCP server (:8765), live EDT workbench, workspace `D:\WS\EDT`. Do **not** run this live unattended — it requires a running app and a deliberate "exercise then read results" sequence.
- **An active debug session must already exist** for the `applicationId` you pass. Start one first with `debug_launch` (or `debug_yaxunit_tests`). The tool calls `DebugSessionRegistry.findActiveTarget(applicationId)`; if there is no non-terminated `IDebugTarget` for that id, it errors. A plain RUN-mode launch will not work — `findActiveTarget` only resolves debug-mode launches that carry a debug target.
- **`applicationId` is required** and must match the debug session's id exactly. This is **not** the `get_applications` GUID; it is the id surfaced by `debug_status` / the `applicationId` echoed by `debug_launch` / `debug_yaxunit_tests` — the real `ATTR_APPLICATION_ID` for a runtime-client launch, or the synthetic `attach:<configName>` id for an attach launch (e.g. `attach:1C Enterprise debug process`). Discover it with `debug_status` first.
- The debug target must support profiling, i.e. it must be (or adapt to) `IProfileTarget`. A standard 1C `LocalRuntime` debug target does; if it cannot adapt you get a "does not support profiling" error.
- The profiling and wiring OSGi bundles must be present in the runtime: `com._1c.g5.v8.dt.profiling.core`, `com._1c.g5.v8.dt.debug.core`, and `com._1c.g5.wiring` (they are, in a normal EDT runtime).

**Call (documented — do NOT run live unattended).** Full test procedure against `TestConfiguration`:

1. **Set up executable code + a debug session** following the debug SETUP: put real code in an auto-run handler so something actually executes. For `TestConfiguration` (`scriptVariant=English`) use `ManagedApplicationModule.OnStart()` — write it **through MCP** so model and disk stay in sync across a `-clean` redeploy:
   ```
   write_module_source(projectName="TestConfiguration",
     modulePath="Configuration/ManagedApplicationModule.bsl", mode="replace",
     source="Procedure OnStart()\n    For I = 1 To 1000 Do\n        X = I * 2;\n    EndDo;\n    Message(\"profiled \" + X);\nEndProcedure")
   ```
2. **Start a debug session** so a debug target exists:
   ```
   debug_launch(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=true)
   ```
   (Needs exclusive infobase access for the update — see Gotchas.)
3. **Discover the applicationId** of the live session:
   ```
   debug_status()
   ```
   Note the `applicationId` of the active launch (e.g. `attach:1C Enterprise debug process` for the attach/LocalRuntime launch).
4. **Toggle profiling ON** (the call under test):
   ```
   start_profiling(applicationId="attach:1C Enterprise debug process")
   ```
5. **Exercise the application** so BSL lines actually execute — let `OnStart` run, drive the client, or run the YAXUnit suite if you used `debug_yaxunit_tests`. Profiling only accumulates data for code that runs while it is ON.
6. **Read the results:**
   ```
   get_profiling_results(moduleFilter="ManagedApplicationModule", minFrequency=1)
   ```
   (Verify here — `get_profiling_results` is the read tool that confirms the toggle actually took effect; a non-empty `results` array means profiling was active.)
7. **Revert / clean up the live state** (no repo revert needed):
   - Toggle profiling **OFF** by calling the tool again (it is a pure toggle): `start_profiling(applicationId="attach:1C Enterprise debug process")`.
   - End the session: `terminate_launch` (or `Stop-Process -Name 1cv8,1cv8c` if `terminate_launch` is not in the host's tool snapshot).
   - Restore the test module to its committed state: `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` (this only undoes the `write_module_source` from step 1; it does not touch live debug state).

**Result.** `ResponseType.JSON`. Representative shape **from source** (`StartProfilingTool.execute`, success path), not a captured live run:
```json
{
  "success": true,
  "toggled": true,
  "applicationId": "attach:1C Enterprise debug process",
  "message": "Profiling toggled (on↔off). This is a toggle — calling again will switch profiling off. Run your test, then call get_profiling_results."
}
```
Field meaning (exact keys from source):
- **`success`** — `true` on the happy path (the `ToolResult.success()` envelope).
- **`toggled`** — always `true` when the `toggleProfiling` reflection call returned without throwing. Note this is a literal flag, **not** a report of the new on/off state — the tool cannot tell whether it just turned profiling on or off, because the underlying call is a toggle.
- **`applicationId`** — echo of the requested id.
- **`message`** — the fixed reminder string (note: profiling is a toggle; calling again switches it off; then call `get_profiling_results`).

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false,error:"…"}` with `isError:true`. From source, the distinct error messages are:
- `"applicationId is required"` — missing/empty `applicationId`.
- `"No active debug target for applicationId: <id>. Start a debug session first (debug_launch or debug_yaxunit_tests)."` — `findActiveTarget` returned null (no live debug session for that id, or you passed the wrong id, e.g. the `get_applications` GUID instead of the `debug_status` id).
- `"Debug core bundle not found"` / `"Profiling core bundle not found"` / `"Wiring bundle not found"` — the respective OSGi bundle is absent.
- `"Debug target does not support profiling. Target class: <className>"` — the target is not and cannot adapt to `IProfileTarget`.
- `"IProfilingService not available"` — `ServiceAccess.get(IProfilingService.class)` returned null.
- A bare `e.getMessage()` — any other reflection/invocation exception (logged via `Activator.logError("Error in start_profiling", e)`).

**Gotchas.**
- **It is a toggle, not "start".** Despite the name, the underlying `IProfilingService.toggleProfiling(...)` flips on↔off. `toggled:true` does **not** mean "now ON". If you call it twice you turn profiling **off**. To be sure of the state: toggle once, run a test, then `get_profiling_results` — a non-empty result confirms it was ON; an empty result (`count:0`, "No profiling results available") means either it was toggled off, nothing executed, or you forgot to start profiling before the run.
- **Needs a running debug session, not just a project.** An empty/idle `TestConfiguration` executes nothing. You must have a live debug target (via `debug_launch`/`debug_yaxunit_tests`) AND code that actually runs while profiling is ON, or `get_profiling_results` comes back empty — that is not a code bug.
- **`applicationId` source matters.** Use the id from `debug_status` (or echoed by `debug_launch`/`debug_yaxunit_tests`), e.g. `attach:1C Enterprise debug process`. Do **not** pass the `get_applications` infobase GUID — that will not match a debug target and yields the "No active debug target" error.
- **Quasi-mutating, no repo revert.** This tool changes live debug/runtime state, not files. The only `git`-level cleanup is undoing any `write_module_source` you did to set up the scenario. Always toggle profiling back off and/or terminate the session when done so a later test does not inherit a profiling-ON target. After a `-clean` redeploy EDT loses unsaved in-memory edits, so do scenario edits THROUGH MCP (model + disk stay in sync) — and a `-clean` redeploy also drops the running debug session, so you must re-launch before profiling again.
- **Infobase exclusivity (setup step).** Step 2's `debug_launch(updateBeforeLaunch=true)` needs **exclusive** access to the file infobase to update it. If another 1C client holds the base, the update hangs on `Connecting to designer agent for infobase <name>` and times out (visible in the EDT log). Close clients (`Stop-Process -Name 1cv8,1cv8c`); an elevated 1C process cannot be killed from a non-elevated shell (Access denied) — only the user can close it.
- **No JVM flag here.** This tool does **not** depend on the `nativeFormBufferedLayoutRender` form-rendering flag (that is only for the form-screenshot tools); a blank/empty profiling result is about timing/scope (profiling not on, or no code ran), not a render-mode flag.
- **JSON output is not HTML-escaped.** `GsonProvider` uses `disableHtmlEscaping()`, so `ToolResult.toJson()` keeps `>`, `<`, `&`, `=`, the apostrophe `'`, and non-ASCII like the `↔` arrow and the `—` dash RAW UTF-8 (not `\uXXXX`). If you assert on the `message`, match a delimiter-free substring (e.g. `"toggled"`) for robustness, never the raw `↔`/`—` text.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the JSON envelope), do **not** retry-spam (re-toggling would flip profiling off). Re-verify independently via the EDT log at `D:\WS\EDT\.metadata\.log` — `StartProfilingTool` logs `Profiling toggled via IProfilingService for applicationId=<id>` on success, and the full request/response is recorded there.
- **No bilingual concern in this call.** The input is a single programmatic `applicationId` and the output keys are fixed English; nothing here goes through the synonym / TYPE-token path. (Module/line **names** show up only later in `get_profiling_results`, and those are programmatic module names, not translated synonyms.)
