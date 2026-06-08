Retrieve the accumulated 1C performance-measurement (profiling) readout: which BSL lines ran, how often, and how long. Effectively a line-level coverage + timing report, grouped per module. Returns JSON.

## When to use
- After a profiled debug run, to see which code was exercised and where time went.
- Typical sequence: `debug_launch` (or `debug_yaxunit_tests`) -> `start_profiling` -> run the scenario/test -> `stop_profiling` (finalizes collection) -> `get_profiling_results`.
- You can also call it just to check whether profiling is currently active (see `applicationId` below) without having results yet.

## Parameter details
- `moduleFilter` - optional case-insensitive substring matched against the module name; only matching modules appear. Omit to include every module.
- `minFrequency` - optional integer (default `1`); drops lines whose call count is below this threshold. Raise it to hide rarely-hit lines and focus on hot paths.
- `applicationId` - optional debug session id. When supplied, the `profilingActive` field reflects that specific session's on/off state. When omitted, `profilingActive` reflects whether ANY session is currently profiling, so a client can still tell a stop is pending.
- `responseFormat` - optional, `concise` (default) or `detailed`. `concise` returns lean per-line rows (`line`, `calls`, `pct` only) to save tokens; `detailed` adds the verbose extras `code` (source text), `method` (signature) and the `dur`/`pureDur` timing columns. An unrecognized value falls back to `concise`. The top-level `count` / `profilingActive` / `message` and the per-result `name` / `totalDurability` / `moduleCount` are identical in both formats — only the per-line detail differs.

## Output
JSON with `count` (number of profiling result sets), `profilingActive` (boolean) and `results`. Each result has `name`, `totalDurability` and a `modules` map (module name -> list of lines). In `detailed`, each line carries `line`, `calls` (frequency), `pct`, `dur`, `pureDur`, `code` (source text, truncated to 120 chars) and `method` (the method signature); `concise` (the default) keeps only `line`, `calls` and `pct`.
Output is capped at 200 lines per module to keep the response bounded; narrow with `moduleFilter` / `minFrequency` if you hit the cap.
Only the MOST RECENT measurement session is returned (`count` is 0 or 1); earlier sessions are intentionally not included.

## Examples
- Everything collected: `{}`.
- Hot lines in one module: `{moduleFilter: "CommonModule", minFrequency: 10}`.
- Check a specific session's state: `{applicationId: "<id>"}`.

## Notes & gotchas
- An empty/zero result usually means `start_profiling` was not called before the run, or the data has not been finalized yet - call `stop_profiling` first.
- `profilingActive: true` with `count: 0` means a session is profiling but no results have been flushed; finish the scenario and stop profiling.
- Results are read-only; this tool never toggles profiling on or off.
