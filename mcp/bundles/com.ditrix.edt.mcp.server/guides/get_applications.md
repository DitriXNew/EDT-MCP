Lists the applications (infobases) configured for a project, with each one's id, name, type and update state. The application **id** is the key input for `update_database` and `debug_launch`, so this is usually the step right before running or debugging.

## When to use
- You need the `applicationId` to pass to `update_database`, `debug_launch`, `debug_yaxunit_tests`, profiling, etc.
- Checking whether the infobase is up to date or needs a database update before launching.

## Parameter details
- `projectName` (required) - the EDT project.

## What you get
JSON: `applications` - each with `id`, `name`, `type`, `updateState` (e.g. UPDATED, INCREMENTAL_UPDATE_REQUIRED, FULL_UPDATE_REQUIRED) plus a human-readable `updateStateDescription`, and `requiredVersion` when set. Also `count` and `defaultApplicationId`. When none are configured it returns `count: 0` with an informational message.

## Notes & gotchas
- Use `defaultApplicationId` when you just want "the" application; otherwise pick by `name`/`type`.
- An `updateState` other than `UPDATED` means the infobase schema is behind the model - run `update_database` (which itself takes the `applicationId`) before launching, or a launch may prompt for an update.
- No applications usually means the launch configurations aren't set up yet for the project.
- A project still building is refused with a clear message; a missing/closed project returns an error naming the value.
