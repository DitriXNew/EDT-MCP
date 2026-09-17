Lists the applications (infobases) configured for a project, with each one's id, name, type and update state. The application **id** is the key input for `update_database` and `launch`, so this is usually the step right before running or debugging.

## When to use
- You need the `applicationId` to pass to `update_database`, `launch`, `debug_yaxunit_tests`, profiling, etc.
- Checking whether the infobase is up to date or needs a database update before launching.

## Parameter details
- `projectName` (required) - the EDT project.

## What you get
JSON: `applications` - each with `id`, `name`, `type`, `updateState` (e.g. UPDATED, INCREMENTAL_UPDATE_REQUIRED, FULL_UPDATE_REQUIRED, or UNKNOWN when that read did not conclude) plus a human-readable `updateStateDescription`, and `requiredVersion` when set. Also `count`, `defaultApplication` and `defaultApplicationId`. `defaultApplication` is present on every success and is the field to read: `resolved` (`defaultApplicationId` is present), `none` (the lookup concluded and this project records no default), `unknown` (the lookup did not conclude, so nothing was established - `message` then starts with "The default application is UNKNOWN: "). `defaultApplicationId` is ABSENT in the last two cases alike, so its absence alone cannot tell them apart. For external-objects and extension projects, which do not own their own applications, the list is inherited from the base (parent) project and an extra `inheritedFromProject` field names that base project. When none are configured it returns `count: 0`, `defaultApplication: "none"` and an informational message.

`updateState` is a cached infobase-equality comparison that EDT refreshes asynchronously. Immediately after `update_database` it can still show the pre-update value; use that call's `stateAfter` as the authoritative post-update answer.

## Notes & gotchas
- Use `defaultApplicationId` when you just want "the" application; otherwise pick by `name`/`type`. Branch on `defaultApplication`, not on whether `defaultApplicationId` is there: `unknown` means the lookup never concluded, so pick from `applications` by `name`/`type` (or retry) instead of reading it as "this project has none".
- `updateState: UNKNOWN` (with `updateStateError`) means the state could NOT be read - it is not a claim either way; re-run once EDT is responsive rather than treating it as up to date.
- An `updateState` other than `UPDATED` normally means the infobase schema is behind the model - run `update_database` (which itself takes the `applicationId`) before launching, or a launch may prompt for an update. If an update just completed, prefer that call's `stateAfter` because this cached value may not have refreshed yet.
- No applications usually means the launch configurations aren't set up yet for the project.
- A project still building is refused with a clear message; a missing/closed project returns an error naming the value.
- Inheritance for dependent projects: external-objects and extension projects do not own applications, so when the named project has none they are resolved from its base (parent) project. In that case `project` still echoes the project you asked about and `inheritedFromProject` names the base project the applications came from. For configuration projects this never happens and `inheritedFromProject` is absent.
