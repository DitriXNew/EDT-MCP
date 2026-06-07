# list_modules — how to test

**Purpose.** List the BSL modules in an EDT project, with each module's path, module type (Module / ManagerModule / ObjectModule / FormModule / CommandModule / ManagedApplicationModule / …), parent metadata type, and parent object name. Read-only; can be scoped by `metadataType`, `objectName`, and `nameFilter`.

**Preconditions.** The project must be open in the workspace (use `TestConfiguration`). No open editor, cursor position, or built index is required — `metadataType="all"` (the default) is a pure filesystem scan of `src/`, and the specific-type filters read the in-memory `Configuration` model via `IConfigurationProvider`. The tool runs on the SWT UI thread (`display.syncExec`). It does NOT mutate anything, so no revert is needed.

**Call (real).**
```
list_modules(projectName="TestConfiguration")
```
Filtered variant (single metadata type):
```
list_modules(projectName="TestConfiguration", metadataType="commonModules")
```
Other args (all optional): `objectName` (exact, case-insensitive match on the parent object name, e.g. `"OK"`), `nameFilter` (case-insensitive substring on the module path), `limit` (default 200, clamped to 1..1000).

**Result.** Markdown resource (`ResponseType.MARKDOWN`, filename `modules-<projectname>.md`), NOT JSON. A `## BSL Modules: <project>` heading, an optional `**Filter:**` line when `metadataType != "all"`, a `**Total:** N modules` line (with `(showing N)` appended when truncated by `limit`), then a 4-column table: Module Path | Module Type | Parent Type | Parent Name.

Default (`all`) — real output:
```
## BSL Modules: TestConfiguration

**Total:** 3 modules

| Module Path | Module Type | Parent Type | Parent Name |
| --- | --- | --- | --- |
| CommonModules/Error/Module.bsl | Module | CommonModule | Error |
| CommonModules/OK/Module.bsl | Module | CommonModule | OK |
| Configuration/ManagedApplicationModule.bsl | ManagedApplicationModule | Configuration | Configuration |
```

Filtered (`metadataType="commonModules"`) — real output:
```
## BSL Modules: TestConfiguration

**Filter:** commonModules
**Total:** 2 modules

| Module Path | Module Type | Parent Type | Parent Name |
| --- | --- | --- | --- |
| CommonModules/Error/Module.bsl | Module | CommonModule | Error |
| CommonModules/OK/Module.bsl | Module | CommonModule | OK |
```

**Gotchas.**
- **`all` ≠ a type filter (different code paths, different coverage).** `metadataType="all"` recursively scans `src/` for every `.bsl`, so it includes things the type filters never emit — e.g. `Configuration/ManagedApplicationModule.bsl` above. The specific filters (`commonModules`, `documents`, `catalogs`, …) walk the `Configuration` model instead, so the same project reports 3 modules for `all` but only 2 for `commonModules`. If counts differ between runs, this is why — not a bug.
- **Module path is src-relative, forward slashes, no `src/` prefix** (e.g. `CommonModules/Error/Module.bsl`). This is exactly the canonical `modulePath` form other tools expect (`read_module_source`, `write_module_source`, `get_module_structure`), so the output is meant to be copy-pasted into those calls.
- **Bilingual type token.** The `metadataType` parameter is the English programmatic token (`catalogs`, `commonModules`, …) — it is NOT bilingual; passing `справочники` falls through to the "Unknown metadata type" error. The Parent Type column shows the English EDT type label. Object names (`Parent Name`) are programmatic and never translated. `objectName`/`nameFilter` match by the programmatic name/path, not by synonym.
- **Supported `metadataType` values:** `all` (default), `documents`, `catalogs`, `commonModules`, `informationRegisters`, `accumulationRegisters`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `constants`, `commonCommands`, `commonForms`, `webServices`, `httpServices`. Anything else returns a structured error listing the supported set.
- **Structured error contract.** Failures come back as `ToolResult.error(...)` JSON — `{"success":false,"error":"…"}` with `isError:true` — even though the success path is Markdown. Triggers: missing/empty `projectName` (`"projectName is required"`), project not in workspace (`"Project not found: <name>"`), an unknown `metadataType`, or no configuration model. Note success-path Markdown has no `success` field — assert on the `## BSL Modules:` heading / table, not on a JSON flag.
- **`limit` is clamped to 1..1000** (default 200). When the result is truncated, the header shows `**Total:** N modules (showing M)` while the table holds only the first `M` rows — there is no offset/pagination, so narrow with `nameFilter`/`objectName`/`metadataType` instead of paging.
- **Empty result is success, not error:** an in-range project with no matching modules returns the heading plus `No modules found.` (still Markdown, not a JSON error).
- **UI-thread tool — don't hammer it concurrently** with other `display.syncExec` tools. If the output channel garbles (you see a bare `Error`/`Done` instead of the table), don't retry-spam: re-verify against `git status` on `TestConfiguration` and the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response), then trust that over the dropped echo.
