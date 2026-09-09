Creates or updates a line breakpoint on a BSL module so the 1C application suspends there when it runs. Add a BSL condition or a hit-count rule when a plain line breakpoint is too broad.

## When to use
- You want execution to pause at a specific line so you can inspect variables and step through code.
- Setting up a debug session before `launch` (or before attaching to a running infobase).

## Parameter details
- `modulePath` (required) - the module identifier: either an EDT module path like `CommonModules/Foo/Module.bsl` or an absolute filesystem path to a `.bsl` file. (`module` is a deprecated alias.)
- `projectName` - required **when `modulePath` is an EDT module path** (to resolve it); not needed for an absolute path.
- `lineNumber` (required) - 1-based line to break on.
- `condition` - BSL boolean expression evaluated at the line; omit or pass an empty string to clear it.
- `hitCount` - Positive hit count; omit or pass 0 to clear it. EDT transmits hit counts only to 1C:Enterprise 8.3.24 or newer.
- `hitCondition` - Hit-count comparison; requires a positive hitCount and defaults to EQUALS. Exact values: `EQUALS`, `EQUAL_OR_LESS`, `EQUAL_OR_HIGHER`, `MULTIPLIER`.

## What you get
JSON: `action` (`created` or `updated`), `breakpointId` (the Eclipse marker id - keep it to remove the breakpoint later), the echoed `modulePath` / `resolvedFile`, and `lineNumber`. Configured condition/hit-count fields are included when set. If native setter methods are absent, `configurationFallback` says which verified EDT marker attributes were written - including when the set as a whole reports NOT applied, because one member may have written attributes while another took nothing at all.

## Notes & gotchas
- **`degraded: true` means the breakpoint may NOT actually suspend execution** (the EDT BSL breakpoint class wasn't available, so it fell back to a plain marker). Verify it appears in EDT's Breakpoints view.
- A degraded marker-only breakpoint cannot honor a condition or hit count. The result reports `conditionApplied: false` / `hitCountApplied: false` and says plainly that the requested setting was not applied.
- Conditions are sent on every EDT debug model. Hit count and hit condition are sent only when the connected 1C:Enterprise runtime is 8.3.24 or newer. EDT chooses the runtime debug model when the session attaches, so `set_breakpoint` cannot know at creation time whether an older runtime will ignore the hit-count rule.
- Calling the tool again for the same file and line updates the existing breakpoint instead of creating a duplicate; the result returns `action: "updated"`. Omitting `condition` and `hitCount` clears both settings.
- Requires a debug session to be useful: pair with `launch` (or an Attach config), then `wait_for_break`. Inspect with `get_variables` / `evaluate_expression`, move with `step`, continue with `resume`.
- Setting on an EDT module path while the project is still building returns a clear "still building" error - wait for it to settle.
- Remove it with `remove_breakpoint` (by `breakpointId`, or by the same coordinates); list active ones with `list_breakpoints`.

When an upgraded workspace holds legacy DUPLICATE breakpoints at the requested line, the same settings are REQUESTED for all of them and `reconciledBreakpoints` says how many. They do not always end up identical: a marker-only member takes no condition and no hit count, so a line holding one beside a native breakpoint answers `degraded: true` with `conditionApplied`/`hitCountApplied` false for the SET, and the warning says how many of them are native. `breakpointIds` then lists every reconciled marker: removing the singular `breakpointId` takes only ONE of them and leaves the others live, so clean up by passing each id in `breakpointIds` to `remove_breakpoint`. Do not clean up by COORDINATE: that removal takes the first line breakpoint it finds at the position and does not check the debug model, so where another Eclipse model has one on the same file and line it can take that one instead.
