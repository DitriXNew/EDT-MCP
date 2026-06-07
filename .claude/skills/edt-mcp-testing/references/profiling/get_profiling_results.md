# get_profiling_results — how to test

**Purpose.** Retrieve the accumulated 1C performance-measurement (замер производительности) data from the last/active profiling session: a per-module, per-line execution report (call count, timing, percentage) — effectively a coverage report. Reads `IProfilingService.getResults()` via reflection (`com._1c.g5.wiring.ServiceAccess.get(IProfilingService.class)` from bundle `com._1c.g5.wiring`; result interfaces from `com._1c.g5.v8.dt.profiling.core`). **Read-only** — it does not toggle profiling, mutate the model, or touch any infobase/configuration. With no active session it returns a benign informational empty result, so a bare live call is always safe.

**Preconditions.**
- TestConfiguration open in the workspace, EDT up, MCP on `:8765` (verify with `get_edt_version` — confirmed `2026.1.1.1`).
- No project/module needs to be open or selected — the tool reads a global service, not a project. There is no `projectName` parameter.
- For a NON-empty result you need a prior live profiling session: an active debug session (`debug_launch` or `debug_yaxunit_tests`) → `start_profiling(applicationId=...)` → run code/tests → THEN `get_profiling_results`. Without that whole chain it returns `count: 0` (which is itself a valid, documented response and the normal state on a fresh workbench).
- Does NOT mutate. No revert needed. Safe to call repeatedly.

**Call (real).** Bare call (no active profiling session — the safe default case):
```
get_profiling_results()
```
Optional parameters (both optional, no required fields per the schema):
```
get_profiling_results(
  moduleFilter="CommonModule",   # substring, case-insensitive, matched against module name
  minFrequency=2)                # only lines called >= N times; default 1
```

**Result.** `ResponseType.JSON`. Real output from the bare call above (no profiling session was active):
```
{"success":true,"count":0,"message":"No profiling results available. Make sure you called start_profiling before running the test."}
```
- This empty shape is emitted whenever `getResults()` is `null` or empty: `success:true`, `count:0`, plus the `message`. It is NOT an error — `isError` is absent/false. This is the expected response on a clean workbench.

Representative NON-empty shape (labelled — derived from the source `execute()` in `GetProfilingResultsTool.java`; not observed live in this run because no session was active). Top level: `count` = number of `IProfilingResult` objects; `results` = array, one entry per result:
```
{
  "success": true,
  "count": 1,
  "results": [
    {
      "name": "<profiling result name>",
      "totalDurability": 12.345,
      "moduleCount": 2,
      "modules": {
        "CommonModule.MyModule.Module": [
          {
            "line": 42,
            "calls": 5,
            "pct": 73.10,
            "dur": 8.200,
            "pureDur": 1.050,
            "code": "Result = MyFunction(Param);",
            "method": "MyFunction"
          }
        ],
        "CommonModule.Other.Module": [ /* ... */ ]
      }
    }
  ]
}
```
Field provenance (each maps to a verified reflective getter on the platform interfaces):
- Per result (`IProfilingResult`): `name` ← `getName()`; `totalDurability` ← `getTotalDurability()` (rounded to 3 dp); `moduleCount` = number of distinct module keys after filtering; `modules` = a map keyed by module name → array of line entries. If `getProfilingResults()` returns `null` the entry instead carries `"lines": 0` (no `modules` map).
- Per line (`ILineProfilingResult` + parent `IProfilingTimeHolder`): `line` ← `getLineNo()`; `calls` ← `getFrequency()`; `pct` ← `getPercentage()` (2 dp); `dur` ← `getDurability()` (3 dp); `pureDur` ← `getPureDurability()` (3 dp); `code` ← `getLine()` (truncated to 120 chars + `"..."`); `method` ← `getMethodSignature()`. Module name ← `getModuleName()` (falls back to `"?"` when null).

**Gotchas.**
- **`count: 0` is success, not failure.** The "No profiling results available…" message is the normal empty case (no session ran). Don't treat it as an error and don't retry-spam — to get data you must first run the `start_profiling` chain (see Preconditions). On a fresh harness this is the only output you'll see.
- **Two distinct empty conditions.** (1) Whole `getResults()` empty → top-level `count:0` + message. (2) An individual result with a `null` line list → that result carries `"lines": 0`. Both are `success:true`.
- **Output caps — silent truncation.** `MAX_LINES_PER_MODULE = 200`: lines beyond 200 per module are dropped with no marker, so a hot module's report can be incomplete. `code` snippets longer than 120 chars are clipped to `...`. Don't infer "only N lines executed" from a capped module.
- **Filters are applied before grouping.** `minFrequency` (default 1) drops lines below the threshold; `moduleFilter` is a case-insensitive substring on the module name (`modName.toLowerCase().contains(filter.toLowerCase())`). An over-tight `minFrequency` or a mistyped `moduleFilter` yields an empty/under-populated `modules` map even when raw data exists — that still returns `success:true`, just with fewer rows. There is no "not found" for a filter that matches nothing.
- **Every param read in `execute()` is in the schema** — only `moduleFilter` (string) and `minFrequency` (integer), both optional. There is no `projectName`, no `applicationId`, no pagination (`limit`/`offset`); module count is bounded only by the per-module cap, not a top-level limit.
- **Genuine-failure error contract.** Real failures go through `ToolResult.error(...)` → `{"success":false,"error":"<msg>"}` with `isError:true`. Sources of these: wiring bundle / profiling-core bundle not found, `IProfilingService not available — profiling bundle may not be active`, or any reflective exception (the message is `e.getMessage()`, logged via `Activator.logError`). So distinguish the benign `{success:true,count:0}` empty case from a true `{success:false,...}` failure — they are different shapes.
- **Reflection/version coupling.** All platform classes/methods are resolved by reflection against `com._1c.g5.v8.dt.profiling.core` (`IProfilingService`, `IProfilingResult`, `ILineProfilingResult`, `IProfilingTimeHolder`) and `com._1c.g5.wiring.ServiceAccess`. A platform-version change that renames any of these getters surfaces as a `{success:false}` reflective error from this tool — not a logic bug here. Verified against the live workbench at EDT `2026.1.1.1`.
- **Pairs with `start_profiling`.** That tool TOGGLES profiling on the active debug target (calling it twice turns profiling back OFF). This tool only reads. To validate a real coverage report end-to-end: `debug_yaxunit_tests` (or `debug_launch`) → `start_profiling(applicationId=...)` → run/finish the test → `get_profiling_results`. None of that is required just to exercise this tool's read path.
- **Bilingual.** Module-name keys and `method` signatures come straight from the platform's profiling model (programmatic identifiers), not from synonyms — there is no language-code synonym resolution and nothing here is translated. `moduleFilter` is a literal, case-insensitive substring match, not dialect-aware (it won't map a Russian module display name to an English one). The `getDescription()` mentions "замер производительности" only as the Russian term for the feature; it does not affect inputs/outputs.
- **Flaky output channel.** If the echoed payload looks dropped/garbled, re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (full request/response) rather than hammering the tool; avoid firing concurrent tool calls. After a `-clean` redeploy (`pwsh D:\Soft\edt-redeploy.ps1`, prints "MCP server UP on 8765" = success even if it exits 1) any prior in-memory profiling session is gone, so expect `count:0` until you re-run the profiling chain.
