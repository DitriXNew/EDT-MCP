Removes a BSL line breakpoint you set earlier - the cleanup counterpart of `set_breakpoint`. Address it either by its id or by the same line coordinates.

## When to use
- You are done with a breakpoint and want execution to stop pausing there.
- Tidying up before a clean run, or clearing a breakpoint you set by mistake.

## Parameter details
Two ways to identify the breakpoint - provide ONE:
- `breakpointId` - the marker id returned by `set_breakpoint` (most reliable), OR
- `projectName` + `modulePath` + `lineNumber` - the same coordinates you used to set it (`module` is a deprecated alias for `modulePath`).

## What you get
JSON: `removed` - `true` if a matching breakpoint was found and deleted, `false` if there was nothing to remove.

## Notes & gotchas
- If you pass neither a valid `breakpointId` nor a full coordinate triple you get an actionable error telling you to provide one.
- `removed: false` is not an error - it just means no breakpoint matched (already gone, or wrong coordinates).
- Use `list_breakpoints` to see what's currently set (and to recover ids) before removing.
