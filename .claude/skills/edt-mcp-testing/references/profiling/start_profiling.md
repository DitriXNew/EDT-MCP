# start_profiling — acceptance procedure

Start line-level measurement on a running debug session, using its `applicationId` from `debug_status`. A project or an unlaunched infobase alone is insufficient. The target must support EDT profiling; a missing service/target produces an actionable structured error.

```text
debug_status()
start_profiling(applicationId="<live debug-session id>")
start_profiling(applicationId="<same id>")
stop_profiling(applicationId="<same id>")
stop_profiling(applicationId="<same id>")
get_profiling_results(applicationId="<same id>")
```

- The first start returns JSON `success:true`, `active:true`, `started:true` when it starts measurement. An already active start returns `active:true`, `started:false` and leaves measurement enabled; it never toggles off.
- Between start and stop, exercise code while measurement is active. Profiling cannot measure code that ran before the start.
- Stop explicitly with `stop_profiling`: expect `active:false`, `stopped:true`; another stop is benign with `stopped:false`. Ending the debug session also clears its tracked active state.
- Read back the measured modules, lines and nonzero call counts. `get_profiling_results` reads the most recent measurement globally; `applicationId` scopes its active-state flag, not its result data. Empty results can mean no code ran while profiling was active.
- Profiling changes live runtime state, not project files. Verify fixture cleanliness and stop measurement after the procedure. Revert any separate source edits made to seed executable code, then synchronize the model through the orchestrator.
- Test missing `applicationId` and a nonexistent debug target; require `isError:true`, the offending value and launch/setup recovery. Inspect `structuredContent`, since a JSON tool's text placeholder is not its payload.

Use `StartProfilingToolTest`, `StopProfilingToolTest` and the profiling e2e files for executable assertions. This procedure describes the current source contract; execute it on the live stand before claiming runtime acceptance.
