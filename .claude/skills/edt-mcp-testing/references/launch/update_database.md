# update_database — how to test

**Purpose.** Apply the EDT configuration to the application's infobase database (the model equivalent of `1cv8 DESIGNER /UpdateDBCfg`). Target the application either by `launchConfigurationName` (preferred) or by `projectName` + `applicationId`, choose a **full** reload or an **incremental** update, and optionally let EDT auto-apply restructurization. Source: `UpdateDatabaseTool` → `IApplicationManager.update(application, updateType, ExecutionContext, monitor)`.

> **DESTRUCTIVE / irreversible on the infobase.** This mutates the *infobase database*, not the source tree. `git checkout`/`git clean` on `TestConfiguration/src` does **not** undo it — the DB restructuring already happened. It also needs **exclusive** access to the infobase: if a 1C client launched from this EDT is holding the IB, the update typically fails or stalls. Treat this as **explicit-request-only**; do **not** run it as part of routine reference drafting.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project **`State=ready`** — `update_database` calls `ProjectStateChecker.checkReadyOrError(projectName)` first, so a still-indexing project returns the not-ready error, not partial work. Poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext index rebuilds for a while).
- The `IApplicationManager` OSGi service must be available (it is in the normal EDT runtime).
- **EXCLUSIVE infobase access — the critical precondition.** No other client may hold the target infobase:
  - Check `list_configurations(projectName="TestConfiguration")`; if any runtime-client config shows `running: true`, the IB is in exclusive use.
  - Call `terminate_launch` (it only affects launches started from *this* EDT instance), then re-check `running:false`, then retry the update.
  - An **elevated/locked** `1cv8`/`1cv8c` process started outside this EDT cannot be terminated from a non-elevated shell; with such a process holding the IB the update **stalls at "Connecting to designer agent"**. Resolve the holder before calling.
- Identify the target first: run `get_applications(projectName="TestConfiguration")` to get the application `id`, **or** `list_configurations` to get the launch-config `name`. Do not synthesize the GUID.

**Call (DOCUMENTED — not executed here).** Two equivalent ways to target the same application.

By launch configuration name (preferred — fixes the project + applicationId pair):
```
update_database(
  launchConfigurationName="TestConfiguration Thin Client",
  fullUpdate=false,
  autoRestructure=true
)
```

By project + application id (use the `id` from `get_applications`):
```
update_database(
  projectName="TestConfiguration",
  applicationId="82e532bc-b103-401d-9ce2-6f0785aad340",
  fullUpdate=false,
  autoRestructure=true
)
```

**Full test procedure (explicit-request-only; documented, do NOT run live during reference drafting).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`.
2. **Establish a pending change to apply.** A clean `UPDATED` infobase has nothing to update. Make a *schema* change through MCP (so model + disk stay in sync after a later `-clean` redeploy), e.g. `create_metadata` with `fqn="Catalog.Catalog.Attribute.TmpFlag"`. After it, `get_applications(projectName="TestConfiguration")` should report `updateState` = `INCREMENTAL_UPDATE_REQUIRED` (or `FULL_UPDATE_REQUIRED`).
3. **Ensure exclusivity.** `list_configurations(projectName="TestConfiguration")` → every config `running:false`. If one is `running:true`, `terminate_launch` it and re-confirm. Verify independently with `Get-Process 1cv8,1cv8c` (must be empty for this IB).
4. **Call.** `update_database(...)` as above (incremental first; only use `fullUpdate=true` when a full reload is actually required).
5. **Verify success.** Re-run `get_applications(projectName="TestConfiguration")` and confirm `updateState` is back to `UPDATED` ("Up to date"). The tool's own response also reports `stateBefore` → `stateAfter` (expect `…_REQUIRED` → `UPDATED`) and `message:"Database updated successfully"`.
6. **REVERT the *source* change (NOT the DB).** Undo the metadata edit so the repo is clean:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   **Note:** this reverts the source tree only. The infobase DB was already restructured; to bring the DB back in line with the reverted source you must run `update_database` again (now applying the reverse delta). This is why the tool is destructive/irreversible at the DB level and explicit-request-only.

**Result.** JSON envelope (`ResponseType.JSON`). **Representative success shape from source** (`UpdateDatabaseTool.updateDatabase`) — *not a live capture*:
```json
{
  "success": true,
  "project": "TestConfiguration",
  "applicationId": "82e532bc-b103-401d-9ce2-6f0785aad340",
  "applicationName": "TestConfiguration",
  "updateType": "INCREMENTAL",
  "stateBefore": "INCREMENTAL_UPDATE_REQUIRED",
  "stateAfter": "UPDATED",
  "message": "Database updated successfully"
}
```

Field meaning (from source):
- **`project`** — resolved project name (echoed; derived from the launch config when `launchConfigurationName` was used).
- **`applicationId`** — resolved application id.
- **`applicationName`** — `IApplication.getName()`.
- **`updateType`** — `ApplicationUpdateType` enum name: `INCREMENTAL` (default) or `FULL` (`fullUpdate=true`).
- **`stateBefore` / `stateAfter`** — `ApplicationUpdateState` enum names before/after the `update()` call. The enum values are `UNKNOWN`, `INCREMENTAL_UPDATE_REQUIRED`, `FULL_UPDATE_REQUIRED`, `UPDATED`, `BEING_UPDATED`.
- **`message`** — derived from `stateAfter`: `"Database updated successfully"` when `stateAfter == UPDATED`; `"Update in progress"` when `BEING_UPDATED`; otherwise `"Update completed with state: <name>"`.

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}` with `isError:true`. Cases from source:
- Missing args when no name: `"projectName is required (or pass launchConfigurationName)"`; `"applicationId is required (or pass launchConfigurationName). Use get_applications or list_configurations."`.
- Launch-config path: `"Launch manager is not available"`; `"Launch configuration not found: '<name>'. Use list_configurations to see what's available."`; `"Launch configuration '<name>' is not a runtime-client config — update_database requires one."` (only `com._1c.g5.v8.dt.launching.core.RuntimeClient` configs are accepted); `"Launch configuration '<name>' has no project or applicationId attribute — cannot derive update target."`.
- Project state: the `ProjectStateChecker.checkReadyOrError` message when not ready; `"Project not found: <name>"`; `"Project is closed: <name>"`.
- Service/app: `"IApplicationManager service is not available"`; `"Application not found: <id>. Use get_applications to get valid application IDs."`.
- In-flight: `"Application is currently being updated. Please wait."` (when `stateBefore == BEING_UPDATED`).
- Update failure (caught `ApplicationException`): `error:"Database update failed: <msg>"` **plus** extra fields `applicationId`, `projectName`, and — if the exception has a cause — `causeMessage` and `causeType`. Representative shape:
```json
{
  "success": false,
  "error": "Database update failed: <platform message>",
  "applicationId": "82e532bc-b103-401d-9ce2-6f0785aad340",
  "projectName": "TestConfiguration",
  "causeMessage": "<root cause>",
  "causeType": "<exception simple name>",
  "isError": true
}
```
- Any other unexpected exception: `"Unexpected error: <msg>"`.

**Gotchas.**
- **Destructive at the DB level; source-revert is not enough.** `git checkout`/`git clean` only restores `TestConfiguration/src`. The infobase restructuring is already done — re-running `update_database` is the only way to realign the DB with the reverted source. Run **only** on `TestConfiguration`, **only** on an explicit request.
- **Infobase exclusivity is the #1 failure.** A running client (`running:true` in `list_configurations`) holds the IB exclusively → update fails. An elevated/external `1cv8`/`1cv8c` holding the IB makes the call **stall at "Connecting to designer agent"** and it cannot be killed from a non-elevated shell. Always free the IB (via `terminate_launch` for EDT-started launches) before calling.
- **Heavy / blocking.** `update()` runs synchronously with a `NullProgressMonitor`; a real restructuring can take a while. It also grabs the active SWT shell (`LaunchLifecycleUtils.grabActiveShell`) so EDT can parent confirmation/restructuring dialogs — in a headless/automated run a dialog may block. This is why the procedure is documented, not auto-executed.
- **`fullUpdate` vs `autoRestructure`.** `fullUpdate=true` → `ApplicationUpdateType.FULL` (complete reload, heavier/more destructive); default `false` → `INCREMENTAL` (changes only). `autoRestructure` (default `true`) lets EDT apply restructurization without a manual prompt; both are declared in the schema and read in `execute()`.
- **Resolution precedence.** If `launchConfigurationName` is given it wins and *derives* `projectName`/`applicationId` from the config's `ATTR_PROJECT_NAME`/`ATTR_APPLICATION_ID` (any passed `projectName`/`applicationId` are overwritten). Only when the name is omitted are `projectName` **and** `applicationId` both required.
- **State gate.** Because of `checkReadyOrError`, calling against an indexing project returns the not-ready error, not a partial update. Re-poll `list_projects` until `ready`.
- **JSON tool, with HTML/char escaping.** Output is the `ToolResult` JSON envelope; `ToolResult.toJson()` HTML-escapes `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`. If you assert on text, match a delimiter-free substring (e.g. `not found`), never raw `'…'` or `>=`.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done`), do **not** retry-spam a *mutating* tool — a retry could apply the update twice. Re-verify independently: check `updateState` via `get_applications`, and read the EDT log at `D:\WS\EDT\.metadata\.log` (the tool logs `"Update database: project=…, application=…, type=…, autoRestructure=…"` and any error).
- **Bilingual.** Field keys and enum names (`updateType`, `stateBefore/After`) are fixed English/programmatic identifiers. `applicationName` is the configured application name, not a translatable 1C synonym — nothing here goes through the synonym / TYPE-token bilingual path. Target the application by its programmatic `applicationId` / launch-config `name`, not by a localized label.
