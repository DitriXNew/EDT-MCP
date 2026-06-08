Reads the variables visible in a stack frame of a suspended debug thread - the "inspect state at the breakpoint" tool. Drill into nested structures (Структура, Соответствие, Массив, object properties) on demand instead of dumping everything at once.

## When to use
- You are suspended at a breakpoint (via `wait_for_break`) and want to see local/parameter values.
- Expanding a complex value to inspect its children.

## Parameter details
Identify the frame one of these ways:
- `frameRef` (preferred) - the stable frame reference from `wait_for_break` / `step`.
- `threadId` + `frameIndex` - a thread id plus a 0-based frame index (re-resolved against the live thread).
- Pass neither and, if exactly one debug launch is suspended, its top frame is used.

Then optionally:
- `expandPath` - a dot-separated path to expand a nested variable and return its children instead of the frame's top-level variables.

## What you get
JSON: `variables` (each with `name`, `value`, `type`) and a `count`. Long values are truncated; container values report having children you can reach via `expandPath`.

## Notes & gotchas
- **`frameRef`s go stale after every `step` or `resume`** - always use the freshest one; a stale ref returns "call wait_for_break again".
- Start from the frames in the `wait_for_break` / `step` response, then use `expandPath` to dig into a specific structure rather than expanding everything.
- To evaluate an arbitrary BSL expression (not just read a variable) in the same frame, use `evaluate_expression`.
