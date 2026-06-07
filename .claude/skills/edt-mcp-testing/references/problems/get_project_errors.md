# get_project_errors — how to test

**Purpose.** Return the configuration problems EDT's validation engine has recorded for a project (the same set you see in the "Configuration Problems" view), read from `IMarkerManager`. Each row carries the problem message, the resolved object location, the check identifier, and whether built-in docs exist for that check. Optional filters by `severity`, `checkId`, and `objects` (FQN). Read-only — never mutates the model.

**Preconditions.** Live EDT + the project is open and **indexed** (BM model built). After a `-clean` redeploy the marker index is rebuilt asynchronously, so first confirm `list_projects` shows the project `State=ready` before trusting the count — a half-built index reports fewer problems. TestConfiguration is the canonical target: it contains a `CommonModule.Error` with a **deliberate syntax error** plus a few intentional metadata problems (a `Catalog.Catalog` attribute with no type, common-module settings issues), so it always yields a non-empty, stable-ish result. The tool resolves each marker's presentation lazily inside a **BM read transaction** (per project), so a freshly restarted EDT does not throw. Does not mutate; no revert needed.

**Call (real):**
```
get_project_errors(projectName="TestConfiguration")
```
Optional parameters (all filters are AND-combined):
- `severity` — one of `ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL` (case-insensitive; an unrecognized value is silently ignored and all severities are shown).
- `checkId` — substring match against **either** the symbolic check id (e.g. `common-module-type`, `bsl-legacy-check-library-module`) **or** the short UID (e.g. `SU23`). Case-insensitive.
- `objects` — array of FQNs, e.g. `["Catalog.Catalog"]`; the Russian type token works too (`["Справочник.Catalog"]`). Matches markers whose resolved location contains any of the FQN variants. Returns problems only from those objects.
- `limit` — integer, default 100 (configurable per-tool via `ToolParameterSettings`), clamped to `[1, 1000]`. Omitting `projectName` scans every workspace project.

**Result.** Returned as an embedded Markdown resource (`ResponseType.MARKDOWN`), **not** JSON. Header `# Configuration Problems`, a `**Found:** N` line (suffixed `+ (limited to N)` when the cap is hit), then a 4-column table: `Description | Location | Check code | Has docs`. Real output for TestConfiguration (14 problems):
```markdown
# Configuration Problems

**Found:** 14

| Description | Location | Check code | Has docs |
|-------------|----------|------------|----------|
| Common module for type "Server module" has incorrect settings: Client ordinary application, External connection | CommonModule.OK | `common-module-type` | false |
| Syntax error. Mismatched input ";" | CommonModule.Error.Module | `org.eclipse.xtext.diagnostics.Diagnostic.Syntax` | false |
| Unknown operator | CommonModule.Error.Module | `bsl-legacy-check-simple-statement` | false |
| Property "Error" is not writable for type "" [Server] | CommonModule.Error.Module | `bsl-legacy-check-static-feature-access` | false |
| This module can contain only procedures and functions | CommonModule.Error.Module | `bsl-legacy-check-library-module` | false |
| The required feature 'type' of '...CatalogAttributeImpl@...{bm://TestConfiguration/Catalog.Catalog#/attributes:TestSynAttr}' must be set | Catalog.Catalog | `md-legacy-emf-check` | false |
| Invalid property "type" of the MD object "TestSynAttr". Type is not specified | Catalog.Catalog | `md-legacy-emf-check` | false |
| Neither Object presentation nor List presentation is not filled | Catalog.Catalog | `md-list-object-presentation` | false |
| The method "OnStart" should be placed in one of the standard regions: Variables, EventHandlers, Private, Initialize | Configuration.ManagedApplicationModule | `module-structure-method-in-regions` | false |
| Using non-recommended method | Configuration.ManagedApplicationModule | `use-non-recommended-method` | false |
```
(The full live run returns 14 rows; trimmed above for brevity.) Key checks:
- The **deliberate syntax error** in `CommonModule.Error.Module` shows up as `Syntax error. Mismatched input ";"` with check code `org.eclipse.xtext.diagnostics.Diagnostic.Syntax` — its presence is the smoke test that validation actually ran on BSL.
- The **Check code** column shows the symbolic check id when resolvable, otherwise the short UID. Xtext/EMF diagnostics surface their raw diagnostic id, not a friendly check id.
- **Has docs** is `true` only for checks that have built-in documentation (resolved via `GetCheckDescriptionTool.hasCheckDocumentation`); most legacy/Xtext checks are `false`. When `true`, follow up with `get_check_description(checkId=...)`.
- Filtered call to verify narrowing: `get_project_errors(projectName="TestConfiguration", objects=["Catalog.Catalog"])` returns only the `Catalog.Catalog` rows; `get_project_errors(projectName="TestConfiguration", checkId="common-module-type")` returns only the `CommonModule.OK` settings problems.

**Empty result (informational, NOT an error).** When nothing matches, the tool returns Markdown `# No Errors Found` with the active filters echoed and the line `No configuration problems match the specified criteria.` This is a normal `MARKDOWN` response (`isError` absent), not the error contract.

**Gotchas.**
- **Project must be `ready`.** If `projectName` is given, the tool first runs `ProjectStateChecker.checkReadyOrError` — a not-yet-indexed project returns a structured error, not an empty table. Always gate on `list_projects` `State=ready`; after `-clean` redeploy the count climbs as the index rebuilds, so re-poll instead of trusting the first low number.
- **Structured error contract.** Genuine failures arrive via `ToolResult.error(...)` → `{success:false, error:"..."}` with `isError:true` (JSON), regardless of the normal Markdown output. Cases: project not `ready`, `Project not found: <name>` (unknown `projectName`), `IMarkerManager service is not available` (services not wired up — usually means EDT is still starting), and a wrapped `Failed to get project errors: <msg>` on an unexpected exception. The empty-result and unresolved-marker cases above are **informational Markdown**, not errors.
- **Unresolved-marker warnings.** Markers whose location cannot be resolved are **never silently dropped**. With no `objects` filter they appear in the table with a `<unresolved: <project>>` placeholder location and a trailing `> ⚠️ N marker(s) could not be resolved ...` note. With an `objects` filter active they are *excluded* (membership can't be tested) and a separate `> ⚠️ N marker(s) were excluded from the object filter ...` note is appended. Both warnings suggest `clean_project` / `revalidate_objects`. Seeing these usually means a stale index — refresh and re-run rather than treating it as a code bug.
- **`limit` is overall, not per-object.** It caps the total rows collected across all projects (default 100, max 1000). When the cap is reached the `**Found:**` line is suffixed `+ (limited to N)`; raise `limit` on a large config (e.g. IRP) for the full set.
- **Bilingual.** The `objects` filter is normalized through `MetadataTypeUtils.getAllFqnVariants`, so the leading **TYPE token** may be Russian or English (`Справочник.Catalog` ≡ `Catalog.Catalog`); the object **NAME** segment is programmatic and never translated — pass the real name, not a synonym. Matching is a case-insensitive `contains` against the resolved presentation, so a partial/parent FQN can match child markers.
- **`checkId` matches both id forms.** It is a substring test against the symbolic id **and** the short UID — `checkId="SU"` or `checkId="module-type"` both work; it is literal (not dialect-aware).
- **Flaky output channel.** If the result arrives garbled or as a bare `Error`/`Done` instead of the Markdown payload, do NOT retry-spam. Re-verify independently: check `D:\WS\EDT\.metadata\.log` (it logs the full request/response) and confirm the server is up (`:8765` / `get_edt_version`). Trust the log over the echoed text.
