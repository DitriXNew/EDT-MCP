Places a line breakpoint on a BSL module so the 1C application suspends there when it runs. The first step of an interactive debug session: set the breakpoint, launch/attach in debug mode, then `wait_for_break` to catch the hit.

## When to use
- You want execution to pause at a specific line so you can inspect variables and step through code.
- Setting up a debug session before `debug_launch` (or before attaching to a running infobase).

## Parameter details
- `modulePath` (required) - the module identifier: either an EDT module path like `CommonModules/Foo/Module.bsl` or an absolute filesystem path to a `.bsl` file. (`module` is a deprecated alias.)
- `projectName` - required **when `modulePath` is an EDT module path** (to resolve it); not needed for an absolute path.
- `lineNumber` (required) - 1-based line to break on.

## What you get
JSON: `breakpointId` (the Eclipse marker id - keep it to remove the breakpoint later), the echoed `modulePath` / `resolvedFile`, and `lineNumber`. If only a marker-only breakpoint could be created you also get `degraded: true` and a `warning`.

## Notes & gotchas
- **`degraded: true` means the breakpoint may NOT actually suspend execution** (the EDT BSL breakpoint class wasn't available, so it fell back to a plain marker). Verify it appears in EDT's Breakpoints view.
- Requires a debug session to be useful: pair with `debug_launch` (or an Attach config), then `wait_for_break`. Inspect with `get_variables` / `evaluate_expression`, move with `step`, continue with `resume`.
- Setting on an EDT module path while the project is still building returns a clear "still building" error - wait for it to settle.
- Remove it with `remove_breakpoint` (by `breakpointId`, or by the same coordinates); list active ones with `list_breakpoints`.
