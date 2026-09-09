Controls EDT's workspace-wide BSL break-on-error breakpoint. It is deliberately scriptable so an unattended regression run can switch break-on-error off before the run, avoid stopping on every exception, and switch it back on afterward.

## When to use
- Disable a Break on Error setting that would otherwise suspend an unattended test or regression run on every exception.
- Restore break-on-error after the unattended run completes.
- Limit suspension to exceptions whose message matches a known filter.

## Parameter details
- `enabled` (required) - True creates/enables the workspace-wide breakpoint; false disables it while preserving its filter. To delete it, call `remove_breakpoint` with its `breakpointId`.
- `exceptionMessage` - Omit to keep an existing filter (or create catch-all when none exists); pass an empty string to catch all exceptions; non-empty text is forwarded verbatim to the platform's break-on-error filter as a message template, so pass a distinctive message fragment.

`breakpointIds` lists the marker id of EVERY exception breakpoint this call configured - normally one. A workspace can already hold several (legacy state); all of them are configured together, and `breakpointId` names only the first, so deleting the whole setting means removing every id in `breakpointIds`. The list is returned by this tool itself, so the cleanup does not depend on `list_breakpoints` being enabled in your toolset. The list is reported on the DISABLE path too, and `configuredCount` says how many breakpoints were touched - larger than the list only when one of them has no marker to name. The filter fields (`catchAllExceptions` / `exceptionMessage`) are reported only when every touched breakpoint carries the SAME filter; when legacy duplicates disagree they are omitted rather than described by one member.

## What you get
JSON with `action` (`created`, `updated`, `disabled`, or `notFound`), `enabled`, and `workspaceWide: true`. An enabled result also includes `breakpointId`, `catchAllExceptions`, and `exceptionMessage` when configured. A disabled result includes `disabledCount`.

## Notes & gotchas
- This setting is **workspace-wide**, not per project. EDT attaches the exception breakpoint to the workspace root and exposes no project scope, so this tool intentionally has no `projectName` parameter.
- `enabled=false` disables every registered BSL exception breakpoint without deleting its marker or message filter, and succeeds with `action: "notFound"` when none exists.
- Omitting `exceptionMessage` always keeps an existing filter, regardless of whether the breakpoint is currently enabled or disabled. Pass `exceptionMessage: ""` to clear that filter and catch all exceptions.
- To delete the saved configuration outright, pass EVERY id in `breakpointIds` to `remove_breakpoint` - normally one, but where a workspace holds legacy duplicates `breakpointId` alone leaves the others configured and breaking on error.
- Creating a breakpoint requires EDT's OSGi `IBslBreakpointFactory` service. If that service is unavailable, the tool reports the exact service name and does not fake success.
- A safe unattended-run pattern is: `set_error_breakpoint(enabled=false)` -> run the regression -> `set_error_breakpoint(enabled=true)`. The final call keeps the breakpoint's previous catch-all or message-filter configuration without requiring it again.
