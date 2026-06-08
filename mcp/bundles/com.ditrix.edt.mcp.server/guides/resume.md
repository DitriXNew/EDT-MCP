Resumes (un-pauses) a suspended debug session so the 1C application keeps running until the next breakpoint - the counterpart of `wait_for_break`. Resume a single thread, or all threads of a debug target.

## When to use
- You are done inspecting at a breakpoint and want execution to continue.
- To let the app run on to the next breakpoint hit (then `wait_for_break` again).

## Parameter details
Provide one (or none):
- `threadId` - resume just that thread (from `wait_for_break`).
- `applicationId` - resume all threads of that debug target (real id or `attach:<configName>`).
- **No arguments** - if exactly one debug launch is active it is auto-resolved and resumed.

## What you get
JSON: `resumed: true`, `scope` (`thread` or `target`), the `applicationId`, and `autoResolved: true` when the lone active launch was picked automatically.

## Notes & gotchas
- 1C debug targets resume at thread granularity, so a target-level resume falls back to resuming each suspended thread - you don't need to resume threads one by one.
- A `stale threadId` error means re-acquire it via `wait_for_break`.
- After resuming, call `wait_for_break` to catch the next suspend; to advance one line instead of running free, use `step`.
