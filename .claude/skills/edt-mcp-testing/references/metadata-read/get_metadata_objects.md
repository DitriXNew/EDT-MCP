# get_metadata_objects — how to test

**Purpose.** List metadata objects of a 1C configuration as a markdown table (Name / Synonym / Comment / Type / ObjectModule / ManagerModule). Read-only inventory of the configuration model: scope it by `metadataType` (one family or `all`), narrow with `nameFilter`, cap with `limit`, and choose the synonym `language`. Source: `GetMetadataObjectsTool` (`ResponseType.MARKDOWN`).

**Preconditions.** Project must be open in the workspace and `State=ready` (check with `list_projects`). Use `TestConfiguration`. The tool reads the in-memory `Configuration` model via `IConfigurationProvider` (no open editor, cursor, or filesystem scan), and runs on the SWT UI thread (`display.syncExec`). It does NOT mutate anything — no revert needed. After a `-clean` redeploy the index rebuilds and reads can be empty/partial until the project reports `ready`; poll `list_projects` first. Note: `IRP` is a large real config — prefer `TestConfiguration` for a small, deterministic result.

**Call (real).** Representative single-family call (matches the project hint):
```
get_metadata_objects(projectName="TestConfiguration", metadataType="catalogs")
```
Whole-configuration call:
```
get_metadata_objects(projectName="TestConfiguration")
```
Filtered call (family + substring):
```
get_metadata_objects(projectName="TestConfiguration", metadataType="commonModules", nameFilter="err")
```
Other args (all optional): `nameFilter` (case-insensitive substring on the programmatic Name), `limit` (default 100, clamped 1..1000; no offset), `language` (synonym language code `"en"`/`"ru"`; default = configuration default language). `metadataType` defaults to `all`.

**Result.** Markdown resource (`embedded://metadata-<projectname>.md`, e.g. `metadata-testconfiguration.md`), NOT JSON. A `## Configuration Metadata: <project>` heading, an optional `**Filter:** <metadataType>` line (omitted when `all`), a `**Total:** N objects` line (`(showing M)` appended only when truncated by `limit`), then a 6-column table: Name | Synonym | Comment | Type | ObjectModule | ManagerModule. ObjectModule/ManagerModule are `Yes` when the object owns that module, `-` otherwise.

`metadataType="catalogs"` — real output:
```
## Configuration Metadata: TestConfiguration

**Filter:** catalogs
**Total:** 1 objects

| Name | Synonym | Comment | Type | ObjectModule | ManagerModule |
| --- | --- | --- | --- | --- | --- |
| Catalog | Catalog |  | Catalog | Yes | Yes |
```

Default (`all`) — real output:
```
## Configuration Metadata: TestConfiguration

**Total:** 4 objects

| Name | Synonym | Comment | Type | ObjectModule | ManagerModule |
| --- | --- | --- | --- | --- | --- |
| Catalog | Catalog |  | Catalog | Yes | Yes |
| Error | Error |  | CommonModule | Yes | - |
| OK | OK |  | CommonModule | Yes | - |
| CommonAttribute | Common attribute |  | CommonAttribute | - | - |
```

Filtered (`commonModules` + `nameFilter="err"`) — real output:
```
## Configuration Metadata: TestConfiguration

**Filter:** commonModules
**Total:** 1 objects

| Name | Synonym | Comment | Type | ObjectModule | ManagerModule |
| --- | --- | --- | --- | --- | --- |
| Error | Error |  | CommonModule | Yes | - |
```

**Gotchas.**
- **`metadataType` is the English programmatic token and is NOT bilingual.** Supported (case-insensitive): `all` (default), `documents`, `catalogs`, `informationRegisters`, `accumulationRegisters`, `commonModules`, `enums`, `constants`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `commonAttributes`, `eventSubscriptions`, `scheduledJobs`. Anything else (including a Russian token like `справочники`) falls through to a structured error listing the supported set. The `Type` column shows the fixed English EDT type label (`Catalog`, `CommonModule`, `CommonAttribute`, …) — never translated.
- **Name vs Synonym (the bilingual trap).** The `Name` column is the programmatic identifier and is the value you pass to other tools; it is never translated. The `Synonym` column is the localized display text resolved for the chosen `language`. The synonym map is keyed by language CODE (`"ru"`/`"en"`), not by the language object's name — `language="en"` requests the `en` entry, with fallback when absent. In `TestConfiguration` Name and Synonym happen to coincide (e.g. `Catalog`/`Catalog`); in a real config they differ, and `CommonAttribute` shows synonym `Common attribute`. Do not match objects by synonym — match by Name.
- **`nameFilter` is a case-insensitive substring on the programmatic Name only** (not synonym, not comment) — `nameFilter="err"` matched `Error`. There is no exact-match mode here.
- **`metadataType` scopes which families are collected, not row-level filtering.** `all` walks every supported family; a single token walks only that family. `TestConfiguration` reports 4 objects for `all` (Catalog, Error, OK, CommonAttribute) but 1 for `catalogs`.
- **`limit` is clamped to 1..1000 (default 100), no offset/pagination.** When truncated, the header reads `**Total:** N objects (showing M)` and the table holds the first `M` rows. Narrow with `metadataType`/`nameFilter` rather than paging.
- **Module columns are per-family, not universal.** ObjectModule/ManagerModule reflect which modules a family actually owns: CommonModule has an object module (its `Module`) but `-` for manager; Enum has only a manager module; CommonAttribute / EventSubscription / ScheduledJob always show `-`/`-`. A `-` is "this family has no such module", not an error.
- **Empty result is success, not error.** A valid project/family with no matches returns the heading, `**Total:** 0 objects`, and `No metadata objects found.` as Markdown — not a JSON error.
- **Structured error contract.** Genuine failures come back as `ToolResult.error(...)` JSON — `{"success":false,"error":"…"}` with `isError:true` — even though the success path is Markdown. Triggers: missing/empty `projectName` (`"projectName is required"`), project not in workspace (`"Project not found: <name>"`), no configuration provider/model, or an unknown `metadataType`. The success Markdown has no `success` field — assert on the `## Configuration Metadata:` heading / table, not on a JSON flag.
- **UI-thread tool — don't hammer it concurrently** with other `display.syncExec` tools. **Flaky output channel:** if the text comes back garbled/empty (a bare `Error`/`Done` instead of the table), do NOT retry-spam. Re-verify independently via the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response) and the EDT Project Explorer, then trust that over the dropped echo.
- **Table cells are escaped** by `MarkdownUtils.tableHeader`/`tableRow`, so a synonym/comment containing `|` or a newline cannot break the table — don't "fix" odd escaping in the output.
