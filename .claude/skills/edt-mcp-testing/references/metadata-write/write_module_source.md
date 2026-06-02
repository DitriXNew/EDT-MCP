# write_module_source — how to test

**Purpose.** Write BSL source code into a 1C metadata object module's `.bsl` file. **MUTATES the `.bsl` on disk** (via `IFile.setContents(..., FORCE|KEEP_HISTORY)`, or `IFile.create(...)` for a new file). Three modes (param `mode`, source constants `MODE_*`): `searchReplace` (**default** — find `oldSource`, replace the single match with `source`), `replace` (overwrite the entire file; the **only** mode that may create a new file), `append` (add `source` to the end). Target the module by `modulePath` (src-relative) **or** by the decomposed `objectName` + `moduleType` (+ `formName`/`commandName`). By default it runs a balanced-block BSL syntax check and **blocks the write on errors**; pass `skipSyntaxCheck=true` to force. Source-verified: `WriteModuleSourceTool` (`getInputSchema`/`execute`/`resolveModulePath`/`applySearchReplace`/`writeFile`).

**Preconditions.**
- Live EDT workbench, workspace `D:\WS\EDT`, MCP on `:8765`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project open in the workspace (e.g. `TestConfiguration`). `ProjectContext.of(projectName).exists()` must be true, else `"Project not found: <name>"`.
- The target `.bsl` must already exist for `searchReplace`/`append` (only `replace` may create a new file — see error `"File not found: src/<path>. Only 'replace' mode can create new files."`). The file is resolved by `BslModuleUtils.resolveModuleFile(project, modulePath)`.
- No infobase / no DB / no AST-BM model needed — the tool reads and writes the file directly (`BslModuleUtils.readFileLines`/`readFileText`, UTF-8 BOM aware) and the syntax check is a pure line scan (`BslSyntaxChecker.check`). There is **no infobase-exclusivity lock** (unlike `update_database`) and **no form-render JVM flag** dependency.
- For `searchReplace` you must first know the exact current content — read it with `read_module_source`; the `oldSource` you pass proves you read it and must match **exactly one** location.

**Call (real or documented).** This MUTATES a tracked file, so the safe test is **mutate-then-revert on `TestConfiguration` only**. `CommonModules/Error/Module.bsl` is a one-line module (`Error`) — ideal for a deterministic round trip.

Full documented test procedure (run **only** on `TestConfiguration`; revert after):

```
# 1. READ the current content first — searchReplace requires an exact oldSource,
#    and reading proves the current state so you can verify + revert deterministically.
read_module_source(projectName="TestConfiguration",
                   modulePath="CommonModules/Error/Module.bsl")
#    -> body fence contains exactly:  Error

# 2. CALL — searchReplace (default mode): replace the single occurrence.
write_module_source(
  projectName="TestConfiguration",
  modulePath="CommonModules/Error/Module.bsl",
  mode="searchReplace",
  oldSource="Error",
  source="// probe\nError")

# 3. VERIFY (read-only) — confirm the edit landed via the matching READ tool:
read_module_source(projectName="TestConfiguration",
                   modulePath="CommonModules/Error/Module.bsl")
#    -> body fence now shows:  // probe  then  Error  (totalLines: 2)

# 4. REVERT — restore the tracked file to its committed state:
#      git checkout HEAD -- TestConfiguration
#      git clean -fd  -- TestConfiguration
#    Re-run read_module_source to confirm it is back to the single line "Error".
```

Alternative targeting — decomposed `objectName` + `moduleType` (resolved by `resolveModulePath`, which maps the metadata type to its source directory via `MetadataTypeUtils.getDirectoryName` and picks the file name from `moduleType`):

```
# Same Error common module, addressed without modulePath. CommonModule's default
# moduleType is "Module" -> CommonModules/Error/Module.bsl
write_module_source(projectName="TestConfiguration",
                    objectName="CommonModule.Error",
                    mode="append",
                    source="// appended")
```

Other `moduleType` → path mappings (from `resolveModulePath`, applied under the type's directory `<dir>/<Name>/`):
- `Module` → `<dir>/<Name>/Module.bsl` (default for `CommonModule`/`CommonForm`/`WebService`/`HTTPService`)
- `ObjectModule` → `<dir>/<Name>/ObjectModule.bsl` (default for ordinary objects, e.g. `Document.MyDoc`)
- `ManagerModule` → `<dir>/<Name>/ManagerModule.bsl`
- `RecordSetModule` → `<dir>/<Name>/RecordSetModule.bsl`
- `FormModule` → `<dir>/<Name>/Forms/<formName>/Module.bsl` (**`formName` required**, except `CommonForm` → `<dir>/<Name>/Module.bsl`)
- `CommandModule` → `<dir>/<Name>/Commands/<commandName>/CommandModule.bsl` (**`commandName` required**, except `CommonCommand` → `<dir>/<Name>/CommandModule.bsl`; default `moduleType` for `CommonCommand`)

Mutation-safety notes (critical):
- Mutating tools run **only** on `TestConfiguration` (the small, disposable test project). Never write into the live config or any tracked source you cannot reset.
- Mutate **through MCP** so the EDT model and disk stay in sync. A later `-clean` redeploy (`pwsh D:\Soft\edt-redeploy.ps1`) drops unsaved in-memory editor edits; an MCP-driven write is persisted to disk (and the workspace refreshed via `setContents`/`create`), so it survives the relaunch.
- This tool has **no preview/confirm** parameter (unlike `rename_metadata_object` / `delete_metadata_object`, which return a preview *without* `confirm` and only apply with `confirm=true`). Every successful `write_module_source` call mutates immediately — there is no dry-run. The closest safety rail is the default syntax check, which only *blocks* a malformed write; it does not preview a good one.
- Revert is always `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` (the `git clean` also removes a file that `replace` mode may have newly created).

**Result.** MARKDOWN response (`ResponseType.MARKDOWN`), delivered as an embedded resource (filename from `getResultFileName`: `write-<modulepath-dashed-lowercased>.md`, or `write-module-source.md` when only `objectName` was given). Success is a YAML frontmatter block built by `FrontMatter` then the literal body `File written successfully`. **Representative success shape from source** (`execute()` steps 10–11; field order is insertion order) — for an existing-file `searchReplace` with the syntax check on:

```
---
tool: write_module_source
projectName: TestConfiguration
modulePath: CommonModules/Error/Module.bsl
mode: searchReplace
status: success
linesAfter: 2
syntaxCheck: passed
linesBefore: 1
---
File written successfully
```

Field meaning (from source):
- `tool` — constant `"write_module_source"`.
- `projectName` / `modulePath` — echoed; `modulePath` is the **resolved** src-relative path (so when you passed `objectName`+`moduleType`, this shows what `resolveModulePath` produced).
- `mode` — the effective mode (`searchReplace` if you omitted `mode`).
- `status` — `"success"`.
- `linesAfter` — line count after the write (`newLines.size()`).
- `syntaxCheck` — `"passed"` when the check ran and passed, or `"skipped"` when `skipSyntaxCheck=true`.
- `linesBefore` — present **only when the file already existed** (original line count).
- `newFile: true` — present **only when `replace` mode created a new file** (and then `linesBefore` is absent).

**Gotchas.**
- **Two output channels — success vs failure differ.** SUCCESS is the MARKDOWN frontmatter + `File written successfully`. Every FAILURE (including the syntax-check block) is JSON via `ToolResult.error(...).toJson()` → `{"success":false,"error":"…"}` with `isError:true`. Don't expect frontmatter on errors; don't expect JSON on success.
- **`searchReplace` match semantics are exact and single-occurrence.** `applySearchReplace` works on the **raw** file text (with `\r\n` normalized to `\n`, preserving the trailing newline). Zero matches → `"oldSource not found in current file content. The file may have changed since last read … Please read the file again with read_module_source."` Two-or-more matches → `"oldSource found multiple times in the file (<n> occurrences). Provide a larger, more specific oldSource fragment that matches exactly one location."` Fix by re-reading and supplying a larger, unique `oldSource` — do **not** retry-spam.
- **Mode/required-param errors (all JSON `{success:false,error}`):** `"projectName is required"`; `"source is required"` (`source` may be empty string for `replace`/`append`, but not absent); `"source exceeds maximum allowed length (500000 characters)"`; `"invalid mode '<m>'. Allowed: searchReplace, replace, append"`; `"oldSource is required for searchReplace mode"`; `"either modulePath or objectName is required"`; `"modulePath must not contain '..'"` (path-traversal guard); `"only .bsl module files can be written"`; `"Project not found: <name>"`; `"File not found: src/<path>. Only 'replace' mode can create new files."`; `"Failed to write file: <msg>"` (caught I/O exception).
- **objectName-resolution errors** surface through the structured contract too (the internal `"Error:"`-prefixed string from `resolveModulePath` is unwrapped into `ToolResult.error`): `"objectName must be in format 'Type.Name' …"`; `"unknown metadata type: <t>"`; `"metadata type '<t>' has no source directory"`; `"formName is required when moduleType=FormModule"`; `"commandName is required when moduleType=CommandModule"`; `"unknown moduleType: <m>. Allowed: ObjectModule, ManagerModule, FormModule, CommandModule, RecordSetModule, Module"`.
- **Syntax check blocks the write (this is the safety rail, not a bug).** With `skipSyntaxCheck` unset/false, `BslSyntaxChecker.check(newLines)` validates balanced `Procedure/EndProcedure`, `Function/EndFunction`, `If/EndIf`, `While/EndDo`, `For/EndDo`, `Try/EndTry`. On failure the file is **not** written and the error reads `"BSL syntax check failed. Write blocked.\n\nErrors:\n- <each error>\n\nPass skipSyntaxCheck=true to force write."` This is dialect-aware/keyword-based, not a full parser — it only checks block balance. Use `skipSyntaxCheck=true` for intentional fragments (rare) and re-validate afterward with `get_project_errors`.
- **`replace` is the dangerous mode.** It overwrites the **entire** file content and is the only mode that creates a new file (`newFile: true`). Always read first; an empty/wrong `source` here silently wipes the module. Prefer `searchReplace` for surgical edits.
- **Encoding/EOL contract.** Source is normalized `\r\n`→`\n` on input; `writeFile` joins with `\n` and **always appends a trailing newline**. BOM is preserved for existing files (`detectBom`) and **added for new files** (new BSL files are written with a UTF-8 BOM). If you diff a written file, expect LF line endings + leading BOM.
- **Flaky output channel — re-verify, don't retry a mutator.** If the result comes back garbled, truncated, or as a bare `Error`/`Done`, do **not** blindly re-call: a second `append` would double-append, and a second `searchReplace` would now report "not found" (the first one already changed the text) and mislead you. Re-verify independently with `read_module_source`, and check the EDT log `D:\WS\EDT\.metadata\.log` for the full request/response. Then make at most ONE corrected follow-up.
- **Bilingual.** `objectName` is resolved through `MetadataTypeUtils`: the **TYPE token is bilingual** (`Document`/`Документ`, `CommonModule`/`ОбщийМодуль` map to the same English singular + source directory), but the object **NAME** segment is programmatic/literal — pass the real name, never a synonym. Synonyms (keyed by language **CODE** `ru`/`en`) are not involved in path resolution at all. The BSL `source` text itself is written verbatim; this tool does not translate keywords (and `search_in_code`/syntax checks are literal/keyword-based, not synonym-aware).
