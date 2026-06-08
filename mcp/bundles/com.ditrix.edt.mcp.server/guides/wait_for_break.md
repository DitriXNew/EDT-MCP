Blocks until the running 1C application hits a breakpoint (or otherwise suspends), then returns a snapshot of the suspended thread and its call stack. This is the tool that catches a breakpoint hit so you can start inspecting - it hands you the `threadId` and `frameRef` values the other debug tools need.

## When to use
- After `set_breakpoint` + a debug launch, to wait for execution to reach the breakpoint.
- Any time you expect the app to suspend and want the resulting stack/frames.

## Parameter details
- `applicationId` - the debug session (real id, or `attach:<configName>`). **Optional** if exactly one EDT debug launch is active - it auto-resolves.
- `timeout` - how long to wait, in seconds (default 60, capped at 600).

## What you get
On a hit: JSON with `hit: true`, `threadId`, `threadName`, and `frames` - each frame carrying `frameIndex`, a stable `frameRef`, `name`, `line`, and (for BSL frames) `modulePath` + `project`. `topFrameRef` points at the top frame. On timeout: `hit: false`, `reason: "timeout"` - **the launch is NOT terminated**, so just call again to keep waiting.

## Notes & gotchas
- Keep the `frameRef` / `topFrameRef` values - feed them to `get_variables` and `evaluate_expression`; use `threadId` for `step` and `resume`.
- It catches a suspend that already happened before the call too (e.g. a manual breakpoint hit in EDT), so you won't miss an early break.
- The `modulePath` + `project` on a frame let you chain straight into `read_module_source` / `set_breakpoint` at the current location.
- A timeout is normal if the code path wasn't exercised - trigger the action in 1C, then wait again.
