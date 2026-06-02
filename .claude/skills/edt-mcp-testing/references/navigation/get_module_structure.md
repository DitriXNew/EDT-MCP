# get_module_structure — how to test

**Purpose.** Outline a single BSL module by `modulePath`: every procedure/function with its signature, line range, export flag, execution context (`&AtServer`/`&AtClient`), and containing region; optionally module-level variables and method doc-comments. Read-only — it never mutates the model.

**Preconditions.**
- Project open and fully indexed in EDT (workspace `D:\WS\EDT`, MCP on `:8765`). Test project: `TestConfiguration`.
- No open editor / cursor position required — the tool loads the `Module` from the BSL EMF model via `BslModuleUtils.loadModule(project, modulePath)`, not from an editor. It does run on the SWT UI thread (`display.syncExec`), so avoid hammering other UI-thread tools concurrently.
- `modulePath` is `src/`-relative, forward-slashed, and ends at the `.bsl` file, e.g. `CommonModules/MyModule/Module.bsl` or `Configuration/ManagedApplicationModule.bsl`.
- Does NOT mutate — no revert needed.

**Call (real, 2026-06-02).** Verified live against `TestConfiguration` (EDT 2026.1.1.1). The TestConfiguration CommonModules (`OK`, `Error`) are effectively empty, so use `Configuration/ManagedApplicationModule.bsl` for a populated result:
```
get_module_structure(
  projectName="TestConfiguration",
  modulePath="Configuration/ManagedApplicationModule.bsl"
)
```
Optional flags (both default `false`): `includeVariables=true` adds a `### Variables` table; `includeComments=true` adds a `Description` column to the methods table.

**Result.** Markdown resource (`ResponseType.MARKDOWN`), delivered as `embedded://structure-<sanitized-modulepath>.md`. Real output captured:
```markdown
## Module Structure: Configuration/ManagedApplicationModule.bsl

**Total:** 2 procedures, 0 functions | **Lines:** 11

### Methods

| # | Type | Name | Export | Context | Lines | Parameters | Region |
|---|------|------|--------|---------|-------|------------|--------|
| 1 | Procedure | BeforeStart | - | - | 2-4 | Cancel | - |
| 2 | Procedure | OnStart | - | - | 6-11 | - | - |
```
Empty-module case (real, `CommonModules/OK/Module.bsl`):
```markdown
## Module Structure: CommonModules/OK/Module.bsl

**Total:** 0 procedures, 0 functions | **Lines:** 1

No methods found in this module.
```
Section order when present: header (`## Module Structure`) → `**Total:** … | **Lines:** …` → `### Regions` (one bullet per region, `name (line START-END)`) → `### Variables` (only with `includeVariables=true`) → `### Methods` table. The methods table columns are `# | Type | Name | Export | Context | Lines | Parameters | Region`, plus a trailing `Description` column only when `includeComments=true` and at least one method has a doc-comment.

**Gotchas.**
- **Success is a plain Markdown string, NOT a `{success:true,…}` JSON envelope.** Assert on Markdown substrings (`## Module Structure:`, `### Methods`, the method name). Do not look for a JSON `success` field on the happy path.
- **Structured error contract.** Failures are JSON `{"success":false,"error":"…"}` with `isError:true`. Triggers: missing `projectName` → `"projectName is required"`; missing `modulePath` → `"modulePath is required. Example: …"`; unknown project → `"Project not found: <name>"`; module not loadable/indexed → `"BSL model is not available for '<modulePath>' …"`. A not-yet-indexed project surfaces as that last error, not an empty outline — re-check after indexing finishes.
- **modulePath is the canonical, literal parameter.** It is the programmatic file path; it is NOT translated and is unrelated to synonyms. Object names in the path are programmatic (`CommonModules/<Name>`), never the bilingual synonym. Wrong slashes/casing or a missing `Module.bsl` suffix yield the "BSL model is not available" error.
- **Bilingual regions handled at parse time.** Region names come from the AST (resolved for both `#Region`/`#Область`), and end lines are matched in the source against both `#EndRegion` and `#КонецОбласти`. You do not pass a dialect; the same call works for ru and en modules. (Region end-line is computed from source, not the AST, because the AST end line of a `RegionPreprocessor` spans to end-of-file.)
- **Flaky output channel.** The result text can drop/garble (you may see a bare `Error`/`Done` instead of the Markdown). Do not retry-spam. Re-verify independently: the EDT log `D:\WS\EDT\.metadata\.log` records the full request/response, and re-issuing the single call once is fine. The call is read-only, so `git status` on `TestConfiguration` should stay clean regardless.
- **`Lines:` total** comes from the module node's end line; a 1-line/empty module reports `Lines: 1` and `No methods found in this module.` — that is a valid result, not a failure.
- **Execution context** column shows pragmas (`&AtServer`, `&AtClient`, joined by `, `) or `-`. CommonModule methods typically show `-` (context is set at the module level, not per-method).
