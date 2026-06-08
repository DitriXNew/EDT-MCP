Single-steps a suspended debug thread - over, into, or out of the current line - and waits for it to settle, returning the new stack snapshot. This is how you walk through code line by line once `wait_for_break` has you paused.

## When to use
- You are suspended at a breakpoint and want to advance execution one step.
- To trace into a called procedure (`into`), skip over a call (`over`), or run to the caller (`out`).

## Parameter details
- `threadId` (required) - the suspended thread, from `wait_for_break`.
- `kind` (required) - `over` (execute the current line, don't descend into calls), `into` (descend into the called method), or `out` (run until the current method returns).
- `timeout` - seconds to wait for the step to complete (default 30, capped at 600).

## What you get
The same snapshot shape as `wait_for_break`: `hit: true` with the new `threadId`, `frames` (each with `frameRef`, `name`, `line`, `modulePath`, `project`), and `topFrameRef`. On timeout: `hit: false`, `reason: "timeout"`.

## Notes & gotchas
- **`frameRef`s change after every step** - always use the fresh ones from the step response for the next `get_variables` / `evaluate_expression`; old refs go stale.
- A `stale threadId` error means the session moved on - call `wait_for_break` again to re-acquire it.
- If the requested step isn't possible (e.g. `out` at the top frame) you get a clear "cannot step ..." error.
- To continue running freely instead of stepping, use `resume`.
