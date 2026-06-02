# export_configuration_to_xml — how to test

**Purpose.** Wraps the EDT context-menu action *Export → Configuration to XML Files* — dumps an open EDT configuration project to a directory of XML source files (the same format the 1C platform's `DumpConfigToFiles` produces). Internally calls `IExportConfigurationFilesApi.exportProject(String projectName, Path outputPath)` via reflection (no build-time dependency on `com._1c.g5.v8.dt.cli.api`). Returns a JSON status (`ResponseType.JSON`). This tool **writes to disk** at `outputPath` and can be large/slow on a real configuration — treat it as a heavy, side-effecting tool, even though it does not mutate the EDT model or the infobase.

**Preconditions.**
- Live EDT copy running, workspace `D:\WS\EDT`, MCP server on `:8765`. Redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (may exit 1 but printing `MCP server UP on 8765` = success).
- The project named by `projectName` must be open in the workspace (use `TestConfiguration`; confirm with `list_projects` first).
- The EDT plugin `com._1c.g5.v8.dt.cli.api` must be installed — it provides `IExportConfigurationFilesApi`. If absent, the tool returns the structured error `"IExportConfigurationFilesApi is not available. Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed."` (this is the API the redeployed EDT 2026.1 copy ships; do not "fix" the tool if the plugin is simply missing).
- `outputPath` should point at a **scratch directory you own and can delete** — NOT inside the repo `TestConfiguration/` tree (that would dirty the git working tree the mutation-safety policy relies on). Use e.g. a temp folder under `D:\WS\` or the OS temp dir.

**Behavior from source (so you know what to verify).**
- Both `projectName` and `outputPath` are required; empty/missing → structured error before any work.
- `outputPath` is normalized to an absolute path (`Paths.get(...).toAbsolutePath().normalize()`). If it exists but is a **file** (not a directory) → error `"outputPath exists but is not a directory: <abs>"`. Otherwise the tool **creates the directory** (`Files.createDirectories`) — it is fine for the directory to not exist yet.
- On success it invokes `exportProject(projectName, outputPath)`; the actual XML files are written by the EDT CLI API into `outputPath`.

**Call (real or documented).** Documented — not run live (heavy disk write; needs the CLI API plugin). Representative args against `TestConfiguration`, exporting to a scratch dir:

```
export_configuration_to_xml(
    projectName="TestConfiguration",
    outputPath="D:\\WS\\export-test\\TestConfiguration-xml"
)
```

Full test procedure (this tool writes to disk; clean up the dir afterward — it does NOT touch the repo or the model, so there is no `git checkout` revert, just delete the output dir):

1. **Setup.** Confirm the project is open: `list_projects()` and check `TestConfiguration` is present and open. Choose a scratch `outputPath` OUTSIDE the repo, e.g. `D:\WS\export-test\TestConfiguration-xml`. Ensure it does not already exist as a file.
2. **Call.** Run `export_configuration_to_xml` with the args above.
3. **Verify (success JSON).** Assert the response is `success:true` and that `outputPath` echoes the normalized absolute path you passed.
4. **Verify (files on disk).** Confirm XML files were actually written under `outputPath`. From PowerShell: `Get-ChildItem -Recurse "D:\WS\export-test\TestConfiguration-xml" | Measure-Object` (expect a non-zero count). A real export contains a top-level `Configuration.xml` plus subfolders (e.g. `CommonModules\`, and for `TestConfiguration` a `Catalogs\` / `Documents\` tree per its content). There is no dedicated MCP "read a directory" tool — verification of disk output is filesystem-side (PowerShell), not via another MCP read tool.
5. **Verify (no model/repo change).** This tool does not mutate the EDT model or the infobase, so `git status` on `TestConfiguration/` must stay clean. If it shows changes, you exported into the wrong directory — investigate before continuing.
6. **REVERT / cleanup.** Delete the scratch output directory: `Remove-Item -Recurse -Force "D:\WS\export-test\TestConfiguration-xml"`. (No `git checkout`/`git clean` is needed because nothing under the repo was touched — but if you mistakenly exported into the repo tree, restore with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.)

**Result.** Representative shape from source (`ToolResult.success()` + the three `put(...)` calls), NOT a live capture. JSON (`ResponseType.JSON`):

```json
{
  "success": true,
  "project": "TestConfiguration",
  "outputPath": "D:\\WS\\export-test\\TestConfiguration-xml",
  "message": "Configuration exported to XML files."
}
```

`outputPath` in the response is the **normalized absolute** path (so it may differ from the raw string you passed if you passed a relative one). On the error path the shape is `{"success": false, "error": "<message>"}` (with the MCP `isError:true` flag set).

**Gotchas.**
- **Heavy / slow / large disk write — do not run live unattended.** On a real configuration the export can produce thousands of files and take a long time. For `TestConfiguration` (small) it is quick, but the call still blocks while the CLI API writes every file. Run it deliberately, not in a tight loop.
- **Pick `outputPath` outside the repo.** Exporting into `D:\GitHub\EDT-MCP\TestConfiguration\` (or any tracked path) dirties the git working tree the mutation-safety policy depends on. Use a scratch dir and delete it after. Mutation-safety revert (`git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`) is only the fallback for an accidental in-repo export.
- **Directory is auto-created; only a pre-existing FILE at that path errors.** Missing parent dirs are created (`Files.createDirectories`). The only path-shape error is "exists but is a file."
- **No model mutation, no infobase exclusivity, no JVM flag.** Unlike `update_database` / form-screenshot tools, this is a read-of-model → write-to-disk operation: it does not require infobase exclusivity, does not need the `nativeFormBufferedLayoutRender` JVM flag, and does not change the EDT model. So a `-clean` redeploy losing in-memory edits is not a concern here.
- **Reflection / CLI-API dependency.** The tool resolves the API through `Activator.getDefault().getExportConfigurationFilesApi()` (an OSGi `ServiceTracker`). If `com._1c.g5.v8.dt.cli.api` is not installed the API is `null` and you get the "not available" error — that is an environment problem, not a tool bug. A `NoSuchMethodException`/`IllegalAccessException` surfaces as `"CLI API mismatch: <msg>"` (signals an EDT version whose `exportProject(String, Path)` signature changed). An exception thrown by the underlying export surfaces as `"Export failed: <cause message>"` (the original cause is unwrapped from `InvocationTargetException` and logged via `Activator.logError`).
- **Error contract.** Genuine failures come back as `{"success":false,"error":"…"}` with `isError:true`. Validation triggers from source: `"projectName is required"`, `"outputPath is required"`, `"outputPath exists but is not a directory: <abs>"`. A nonexistent or not-open project is not pre-validated here — it falls through to the CLI API and surfaces as `"Export failed: …"`.
- **JSON-escaping in the error text.** `ToolResult.toJson()` HTML-escapes characters like `>`, `&`, `=`, and the apostrophe `'` to `\uXXXX`. If you assert on an error string, match a delimiter-free substring (e.g. `not a directory`, `is required`), never a raw quoted path or `>=`.
- **Flaky output channel.** If the echoed result is garbled (a bare `Error`/`Done` instead of the JSON), do NOT retry-spam — re-verify the truth independently: check files under `outputPath` (PowerShell), and read the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response and any `export_configuration_to_xml failed` entries). Trust the log + disk over the dropped echo.
- **Bilingual note.** `projectName` is the workspace project name (programmatic, not a synonym) and is not translated. The exported XML uses 1C's own bilingual conventions internally (type tokens may be bilingual; object Names are programmatic; synonyms are keyed by language CODE) — but this tool exposes no language parameter and does not transform names; it just dumps whatever the model holds.
