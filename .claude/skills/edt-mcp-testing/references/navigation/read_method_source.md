# read_method_source — how to test

**Purpose.** Reads a single procedure/function from a BSL module by `modulePath` + `methodName` (case-insensitive), instead of reading the whole (often huge) module. Returns YAML frontmatter (`projectName`, `module`, `method`, `type`, `export`, `startLine`, `endLine`, `totalLines`, plus `region` when the method sits inside a `#Region`) followed by the method source in a fenced ` ```bsl ` block. Read-only — never mutates.

**Preconditions.** Project open in the workspace (e.g. `TestConfiguration`). No open editor, cursor position, or saved selection is required. The tool first tries the BSL EMF model (`BslModuleUtils.loadModule`, runs on the UI thread via `display.syncExec`); if the EMF model isn't available it falls back to a pure text scan (`findMethodViaText`) over the file read from workspace/filesystem (UTF-8 BOM aware). Either path works headless. Does not mutate; no revert needed.

**Call (real, 2026-06-02):**
```
read_method_source(projectName="TestConfiguration", modulePath="Configuration/ManagedApplicationModule.bsl", methodName="OnStart")
```

**Result** (MARKDOWN response → delivered as an embedded resource `embedded://method-<methodname>.md`, here `embedded://method-onstart.md`). Note the preceding doc-comment line `// TEST DEBUG` is folded into the output, so `startLine` points at the comment, not the `Procedure` keyword:
```
---
projectName: TestConfiguration
module: Configuration/ManagedApplicationModule.bsl
method: OnStart
type: Procedure
export: false
startLine: 5
endLine: 11
totalLines: 11
---
```bsl
// TEST DEBUG
Procedure OnStart()
	Greeting = "Debug e2e OK";
	Sum = 40 + 2;
	Total = Sum * 10;
	Message(Greeting + " | sum=" + Sum + " | total=" + Total);
EndProcedure
```
```

**Call (not-found, exercises the listing path):**
```
read_method_source(projectName="TestConfiguration", modulePath="Configuration/ManagedApplicationModule.bsl", methodName="DoesNotExist")
```
**Result** — when the method is missing the tool stays MARKDOWN (NOT a JSON error) and returns an informational listing of every method in the module (`buildMethodNotFoundResponse` / `buildTextMethodNotFoundResponse`). Representative shape (derived from source; method set matches the module above):
```
Error: Method 'DoesNotExist' not found in Configuration/ManagedApplicationModule.bsl

**Available methods** (2):

- BeforeStart
- OnStart
```

**Gotchas.**
- **Not-found is informational MARKDOWN, not a JSON error.** A missing method returns the `Error: Method '<name>' not found …` + `**Available methods**` listing above (success channel). Use that listing to discover the correct `methodName` — it is the intended "did you mean" affordance, so don't treat the leading word `Error:` as a hard failure.
- **Real failures use the structured error contract.** Validation/lookup errors go through `ToolResult.error(...).toJson()` → `{"success":false,"error":"..."}` with `isError:true` (no frontmatter). Exact messages from the source: `"projectName is required"`, `"modulePath is required"`, `"methodName is required"`, `"Project not found: <name>"`, `"File not found: src/<modulePath>"`, `"reading file: <msg>"`.
- **Flaky output channel.** If you see only a bare `Error`/`Done` instead of the frontmatter+fence (or the listing), that's the tool-output channel dropping the payload — NOT a code bug. Do not retry-spam. Re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (it records the full request/response).
- **UI-thread tool.** The EMF path runs inside `display.syncExec` on the SWT UI thread; avoid hammering this concurrently with other UI-thread tools (form rendering, etc.).
- **`methodName` is case-insensitive**; it resolves by the method's programmatic identifier. `type` reports `Function` vs `Procedure`; `export: true/false` reflects the `Export`/`Экспорт` keyword. The doc-comment block immediately preceding the method (adjacent `//` lines, no blank gap) is included, which shifts `startLine` earlier than the keyword line.
- **`modulePath` is `src/`-relative**, `/` separators, no leading `src/` (e.g. `CommonModules/MyModule/Module.bsl`, `Configuration/ManagedApplicationModule.bsl`). It is the canonical module parameter.
- **Bilingual 1C.** The method NAME is programmatic and never translated. The metadata TYPE token folder in `modulePath` (`CommonModules`/`ОбщиеМодули`, etc.) can vary with the configuration language, but the object/method name segments are literal. If unsure of the exact path or method name, list them with `get_module_structure` / `list_modules` rather than guessing a translation.
- **Resource filename** is derived from `methodName`: lowercased, prefixed `method-`, suffixed `.md` (e.g. `method-onstart.md`); falls back to `method-source.md` when `methodName` is absent.
