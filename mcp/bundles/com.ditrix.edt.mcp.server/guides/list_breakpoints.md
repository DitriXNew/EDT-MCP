Lists the line breakpoints and workspace-wide BSL exception breakpoints currently registered in EDT. Use it to verify conditions and hit-count rules, inspect break-on-error state, and recover breakpoint ids.

## When to use
- To check which breakpoints are active before a debug run.
- To find the `breakpointId` of a breakpoint you want to remove.
- To confirm a `set_breakpoint` actually registered.

## Parameter details
- `projectName` - optional filter for line breakpoints; omit to list them across all projects. Exception breakpoints are workspace-wide and are always returned, even when this filter names a project.

## What you get
JSON: `breakpoints` and a `count`. A line entry has `kind: "line"`, coordinates, enabled state, and its debug model; non-empty `condition` and positive `hitCount` / `hitCondition` fields appear only when configured. An exception entry has `kind: "exception"`, `workspaceWide: true`, `enabled`, `catchAllExceptions`, and `exceptionMessage` (empty for catch-all).

## Notes & gotchas
- The `modelId` tells you the breakpoint's debug model - a 1C BSL model id indicates a real, suspend-capable breakpoint (as opposed to a degraded marker-only one from `set_breakpoint`).
- Remove entries with `remove_breakpoint` (pass the `breakpointId`).
- Use `set_error_breakpoint(enabled=false)` to disable the workspace-wide exception breakpoint while preserving its filter. To delete any breakpoint permanently, including a workspace-wide exception breakpoint, pass its `breakpointId` to `remove_breakpoint`.
