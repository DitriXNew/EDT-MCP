# import_configuration_from_xml — how to test

**Purpose.** Import a 1C configuration from a directory of XML source files into a **new** EDT project in the workspace — the EDT menu action *Import → Configuration from XML Files*, and the reverse of `export_configuration_to_xml`. It wraps the EDT CLI API `IImportConfigurationFilesApi.importProject(Path importSource, String projectName, String nature, String xmlVersion)` via reflection (no build-time dependency on `com._1c.g5.v8.dt.cli.api`). **MUTATES the workspace**: it creates a brand-new project, then close+open+`refreshLocal(DEPTH_INFINITE)` it to force EDT to re-scan and bring it to the ready state (the CLI API hardcodes `setRefreshProject(false)`, so this nudge is mandatory). This is a heavy, destructive-to-workspace operation — **document the procedure, do not run it live** as a routine smoke test.

**Preconditions.**
- Live EDT workbench, workspace `D:\WS\EDT`, MCP on `:8765`. Confirm liveness with `get_edt_version` and readiness with `list_projects` (`State=ready`) before driving anything.
- The EDT plugin `com._1c.g5.v8.dt.cli.api` must be installed (it ships with EDT 2025.x / 2026.1). If it is missing the tool returns a structured error (`IImportConfigurationFilesApi is not available…`) — that is environment, not a code bug.
- `importPath` must be an **existing directory** of exported XML source (the layout produced by `export_configuration_to_xml` / the platform `DumpConfigToFiles`). The tool normalizes it to an absolute path and rejects a missing path or a file.
- `projectName` must **not** already exist in the workspace — the contract is "import into a NEW project". An existing name is rejected up front.
- Heavy/slow op (full config parse + project scan + infinite-depth refresh). Give it time; a long pause is normal, not a hang.
- It does NOT consult an infobase — it works purely against XML files on disk and the workspace. No infobase-exclusivity lock is involved (unlike `update_database`).

**Call (real or documented).** This is a workspace-mutating create — the safe test is a **self-contained round-trip on a disposable name**: export `TestConfiguration` to a temp dir, import that dir under a throwaway project name, verify, then DELETE the imported project (it is brand-new, so revert = remove it; nothing in the committed `TestConfiguration/` is touched).

Params (from `getInputSchema()` / `execute()` — all four exist in source):
- `importPath` (string, **required**) — filesystem path of the source directory containing XML files.
- `projectName` (string, **required**) — name of the NEW project to create.
- `projectNature` (string, optional) — e.g. `com._1c.g5.v8.dt.core.V8ConfigurationNature`; empty string → EDT auto-detects (tool maps empty → `null`).
- `xmlVersion` (string, optional) — e.g. `8.3.20`; empty string → EDT auto-detects (tool maps empty → `null`).

Full documented test procedure (do NOT run live unless explicitly asked):

```
# 1. SETUP — produce a known-good XML source dir from the existing test project.
#    (export_configuration_to_xml is read-only against TestConfiguration; safe.)
export_configuration_to_xml(projectName="TestConfiguration",
                            outputPath="D:\\WS\\tmp\\tc_xml_export")

# 2. CALL — import that dir under a throwaway project name that does NOT exist yet.
import_configuration_from_xml(importPath="D:\\WS\\tmp\\tc_xml_export",
                              projectName="TC_ImportProbe",
                              projectNature="",   # let EDT auto-detect
                              xmlVersion="")       # let EDT auto-detect

# 3. VERIFY (read-only) — confirm the new project landed and is usable:
list_projects()                                   # TC_ImportProbe present, State=ready, EDT Project=Yes
get_metadata_objects(projectName="TC_ImportProbe")# same objects as TestConfiguration (e.g. Catalog.Catalog)
get_project_errors(projectName="TC_ImportProbe")  # optional: no unexpected import-induced errors

# 4. REVERT — the import created a NEW project; remove it so the workspace is clean.
#    Delete from the workspace (Project Explorer → Delete, or close+remove the
#    workspace project folder D:\WS\EDT\TC_ImportProbe while EDT is down), and
#    remove the temp export dir D:\WS\tmp\tc_xml_export.
#    If you instead re-imported ON TOP of a tracked repo path, revert with:
#      git checkout HEAD -- TestConfiguration
#      git clean -fd -- TestConfiguration
```

Mutation-safety notes (critical):
- Mutating tools run **only** against disposable artifacts — here a throwaway project name (`TC_ImportProbe`) and a temp dir under `D:\WS\tmp`. Never import over the live `IRP` config or onto the tracked `TestConfiguration/src` path.
- Mutate **through MCP** so the EDT model and disk stay in sync. A subsequent `-clean` redeploy (`pwsh D:\Soft\edt-redeploy.ps1`) drops unsaved in-memory edits; an MCP-driven import is persisted to the workspace by the close/open/refresh step, so it survives.
- This tool has **no preview/confirm** parameter (unlike `rename_metadata_object` / `delete_metadata_object`, which return a preview without `confirm` and only apply with `confirm=true`). Every successful `import_configuration_from_xml` call mutates immediately — there is no dry-run.

**Result.** JSON (`ResponseType.JSON`). The success envelope is built by `ToolResult.success().put("importPath", …).put("project", …).put("message", …)`. Representative shape **from source** (not a live run — see the do-not-run note):

```json
{
  "success": true,
  "importPath": "D:\\WS\\tmp\\tc_xml_export",
  "project": "TC_ImportProbe",
  "message": "Configuration imported from XML files."
}
```

- `importPath` echoes the **normalized absolute** path actually used (`Paths.get(...).toAbsolutePath().normalize()`), which may differ from what you passed if you passed a relative path.
- `project` echoes `projectName`.
- `message` is the fixed literal `"Configuration imported from XML files."`.

Error shape **from source** (`ToolResult.error(...)` → `{success:false, error:<msg>}`; the MCP layer also marks the envelope `isError:true`):

```json
{ "success": false, "error": "Project already exists in workspace: TC_ImportProbe. Import requires a new project name." }
```

Exact validation/error messages emitted by `execute()`:
- `"importPath is required"` — empty/missing `importPath`.
- `"projectName is required"` — empty/missing `projectName`.
- `"importPath does not exist: <abs>"` — path not on disk.
- `"importPath is not a directory: <abs>"` — path is a file, not a dir.
- `"Project already exists in workspace: <name>. Import requires a new project name."` — name collision.
- `"IImportConfigurationFilesApi is not available. Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed."` — CLI API missing.
- `"Import failed: <cause>"` — underlying `importProject(...)` threw (`InvocationTargetException`; cause logged via `Activator.logError`).
- `"CLI API mismatch: <msg>"` — reflection `NoSuchMethodException`/`IllegalAccessException` (the EDT API signature changed).
- `"<msg>"` — any other unexpected exception (raw `getMessage()`; coalesced to `"Unknown error"` if null).

**Gotchas.**
- **It always mutates — no preview.** There is no `confirm`/dry-run param. A successful call creates a real workspace project. Treat as you would `clean_project`/`update_database`: only on an explicit request, only against disposable targets.
- **Revert = delete the NEW project, not git.** Because the tool *creates* a project (it refuses to overwrite an existing one), the revert is removing that fresh project + temp XML dir. The `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` reset only applies if you (incorrectly) targeted the tracked repo path; the canonical safe test never does.
- **"New project only" is enforced.** A pre-existing `projectName` is rejected before the API is even called. If you re-run the same probe you must delete the prior project first, or it errors with the collision message.
- **Post-import refresh is essential.** The CLI API hardcodes `setRefreshProject(false)`, leaving `IDtProjectManager.getDtProject(p)` returning null. The tool compensates with close → open → `refreshLocal(DEPTH_INFINITE)`. If immediately after a *successful* import another tool reports the project as not-ready, re-poll `list_projects` until `State=ready` rather than assuming the import failed — a large config can still be indexing.
- **Path is normalized and validated up front.** Relative paths are resolved to absolute; a missing path or a file (not directory) is rejected deterministically with a clear message instead of an opaque API exception. The `importPath` in the result is the normalized form.
- **No infobase exclusivity / no DB.** This works on XML files + workspace only. Unlike `update_database`, no infobase session lock applies — but a heavy import can still keep the workbench busy; don't fire concurrent mutating tools.
- **CLI-API dependency is environment-sensitive.** On a stripped EDT without `com._1c.g5.v8.dt.cli.api` the `api == null` / `NoSuchMethodException` paths fire. That is an install/version issue, not a regression — check `get_edt_version` and the installed plugins.
- **Error contract.** Genuine failures return `{success:false, error:<msg>}` and the envelope carries `isError:true`. A successful import is `{success:true, …}` with no `isError`. Match on a delimiter-free substring of the message (Gson HTML-escapes characters like `'` and `>=` in the JSON text channel), e.g. assert `does not exist` / `already exists` / `is required`, not the full quoted string.
- **Flaky output channel.** If the JSON comes back garbled, truncated, or as a bare `Error`/`Done`, do NOT retry-spam — a re-run after a *real* success will hit the "Project already exists" guard and mislead you. Re-verify independently: the EDT log `D:\WS\EDT\.metadata\.log` records the full request/response and any `Activator.logError` import failure, and `list_projects` / `get_metadata_objects` confirm whether the project actually materialized. Then make at most ONE clean follow-up call.
- **Bilingual.** The XML source carries the configuration's languages; metadata object **NAMEs** in the imported model stay programmatic, the TYPE token (Справочник/Catalog) is bilingual, and synonyms are keyed by language **CODE** (`ru`/`en`), never by the language *name*. The tool itself does no name resolution (it is a bulk file→project import), so the bilingual contract is inherited intact from the source XML — verify object names/synonyms after import with `get_metadata_details`, not by assuming a single dialect.
