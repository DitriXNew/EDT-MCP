# get_applications — how to test

**Purpose.** List the 1C applications (infobases / launch targets) defined for an EDT project, with each application's `id`, `name`, `type`, and update state. Read-only. The returned application `id` is the value you pass as the application identifier to `update_database` and `debug_launch`, so this tool is the discovery step before those operations. Source: `GetApplicationsTool` → `IApplicationManager.getApplications(project)`.

> Note: this returns the applications *configured for the project* (their `updateState` reflects whether the infobase schema is up to date vs the model), **not** a live list of OS processes currently running. An app appears here whether or not it is started.

**Preconditions.**
- Running MCP server (:8765), live EDT workbench, workspace `D:\WS\EDT`.
- `projectName` is **required**. The tool first runs `ProjectStateChecker.checkReadyOrError(projectName)`, so the project must be **`State=ready`** — confirm via `list_projects` before calling (right after a `-clean` redeploy the index rebuilds and the project shows `building`; wait for `ready`).
- The `IApplicationManager` OSGi service must be available (it is, in the normal EDT runtime).
- Read-only: no mutation, nothing to revert.

**Call (real, against TestConfiguration):**
```
get_applications(projectName="TestConfiguration")
```

**Result.** JSON envelope (`ResponseType.JSON`). Real live output from TestConfiguration:
```json
{
  "success": true,
  "project": "TestConfiguration",
  "count": 1,
  "defaultApplicationId": "82e532bc-b103-401d-9ce2-6f0785aad340",
  "applications": [
    {
      "id": "82e532bc-b103-401d-9ce2-6f0785aad340",
      "name": "TestConfiguration",
      "type": "com.e1c.g5.dt.applications.type.infobase",
      "updateState": "UPDATED",
      "updateStateDescription": "Up to date"
    }
  ]
}
```

Field meaning (from `GetApplicationsTool`):
- **`project`** — echo of the requested `projectName`.
- **`count`** — number of applications.
- **`defaultApplicationId`** — id of the project's default application (`IApplicationManager.getDefaultApplication`); omitted if none / on error.
- **`applications[]`** — one object per application:
  - **`id`** — application id (a GUID for an infobase app). Pass this to `update_database` / `debug_launch`.
  - **`name`** — application name.
  - **`type`** — application type id, e.g. `com.e1c.g5.dt.applications.type.infobase`. Omitted only if the type is null.
  - **`updateState`** — `ApplicationUpdateState` enum name: one of `UNKNOWN`, `INCREMENTAL_UPDATE_REQUIRED`, `FULL_UPDATE_REQUIRED`, `UPDATED`, `BEING_UPDATED`. If reading the state throws, the value is `ERROR` plus an `updateStateError` field with the message.
  - **`updateStateDescription`** — human-readable form of the above (`"Up to date"`, `"Incremental update required"`, `"Full update required"`, `"Currently being updated"`, `"Unknown state"`).
  - **`requiredVersion`** — present only if `app.getRequiredVersion()` is set.

**Empty case.** A project with no applications returns a clean **success** (not an error), with an empty array and a message:
```json
{
  "success": true,
  "project": "TestConfiguration",
  "count": 0,
  "applications": [],
  "message": "No applications found for project"
}
```

**Gotchas.**
- **JSON tool, with HTML/char escaping.** Output is the `ToolResult` JSON envelope. `ToolResult.toJson()` HTML-escapes characters like `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`; if you ever assert on text, match a delimiter-free substring, not raw `'…'` or `>=`.
- **"Empty" is success, not error.** No applications → `success:true` with `count:0`, `applications:[]`, and the `"No applications found for project"` message. This is informational, **not** the error contract.
- **Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false,error:"…"}` with `isError:true`. These are returned for: missing/empty `projectName` (`"projectName is required"`), project not `ready` (the `ProjectStateChecker` message), `"Project not found: <name>"`, `"Project is closed: <name>"`, `"IApplicationManager service is not available"`, and `"Error getting applications: <msg>"` (a caught `ApplicationException`). A per-application `getUpdateState` failure is *not* a tool-level error — it is captured inline as `updateState:"ERROR"` + `updateStateError` on that one app, and the call still succeeds.
- **State gate.** Because of `checkReadyOrError`, calling against a project that is still indexing returns the not-ready error, not partial data. Re-poll `list_projects` until `State=ready`.
- **ids are for the write/debug tools.** Treat `id` (and `defaultApplicationId`) as opaque GUIDs to feed into `update_database` / `debug_launch`. Do not synthesize or guess them.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the JSON), do **not** retry-spam. Re-verify independently via the EDT log at `D:\WS\EDT\.metadata\.log`, which records the full request/response.
- **No bilingual concern.** Field keys, type ids, and state enum names are fixed English/programmatic identifiers; the application `name` is the configured app name, not a translatable 1C synonym. Nothing here goes through the synonym / TYPE-token bilingual path.
