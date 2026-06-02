# read_module_source — how to test

**Purpose.** Reads a BSL module by `modulePath` (relative to the project's `src/` folder), optionally a line range. Returns YAML frontmatter (`projectName`, `module`, `startLine`, `endLine`, `totalLines`; plus `truncated`/`nextStartLine`/`hint` when clamped) followed by clean source in a fenced ` ```bsl ` block (no line-number prefixes). Read-only — never mutates.

**Preconditions.** Project open in the workspace (e.g. `TestConfiguration`). No open editor, cursor position, or built index is required — the tool reads the file from the workspace/filesystem directly (`BslModuleUtils.readFileLines`, UTF-8 BOM aware), independent of any AST/BM model. Does not mutate; no revert needed.

**Call (real, 2026-06-02) — whole 1-line module:**
```
read_module_source(projectName="TestConfiguration", modulePath="CommonModules/Error/Module.bsl")
```
**Result** (MARKDOWN response → delivered as an embedded resource `embedded://source-<modulepath-dashed>.md`):
```
---
projectName: TestConfiguration
module: CommonModules/Error/Module.bsl
startLine: 1
endLine: 1
totalLines: 1
---
```bsl
Error
```
```

**Call (real, 2026-06-02) — empty module (edge case):**
```
read_module_source(projectName="TestConfiguration", modulePath="CommonModules/OK/Module.bsl")
```
**Result** — `startLine`/`endLine` are omitted, `totalLines: 0`, empty code fence:
```
---
projectName: TestConfiguration
module: CommonModules/OK/Module.bsl
totalLines: 0
---
```bsl
```
```

**Call — line range (also exercises the truncation contract):**
```
read_module_source(projectName="TestConfiguration", modulePath="CommonModules/Error/Module.bsl", startLine=1, endLine=200)
```
Range is clamped to actual line count; if a requested range exceeds the configured `maxLines` parameter (default 500), the output is truncated and frontmatter gains:
```
truncated: true
nextStartLine: <to+1>
hint: Output clamped to the configured line limit. To continue reading, call read_module_source again with the same projectName and modulePath and startLine=<to+1>. For an overview of procedures, functions and regions, call get_module_structure.
```
To page through a long module: re-call with `startLine=nextStartLine` until `truncated` is absent.

**Gotchas.**
- **Two output channels in one tool.** SUCCESS is MARKDOWN (frontmatter + bsl fence). FAILURE is JSON via `ToolResult.error(...).toJson()` → `{"success":false,"error":"..."}` with `isError:true`. Don't expect frontmatter on errors.
- **Representative error (live not-found call returned a bare "Error" — flaky output channel, NOT retried).** From the source, a missing file yields:
  ```json
  {"success":false,"error":"File not found: src/CommonModules/DoesNotExist/Module.bsl. Use format like 'CommonModules/ModuleName/Module.bsl' or 'Documents/DocName/ObjectModule.bsl'"}
  ```
  Other validation errors (same JSON shape): `"projectName is required"`, `"modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'"`, `"Project not found: <name>"`, `"Error reading file: <msg>"`. If you only see a bare `Error`/`Done`, that's the flaky tool-output channel — re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (it records the full request/response); don't hammer the call.
- **`modulePath` is `src/`-relative**, with `/` separators and **no leading `src/`** — e.g. `CommonModules/MyModule/Module.bsl`, `Documents/SalesOrder/ObjectModule.bsl`. The resolver tries `src/<modulePath>` first, then scans other top-level folders, so non-standard layouts still resolve. An absolute path is NOT what this param expects (canonical form is src-relative).
- **`startLine`/`endLine` are 1-based, inclusive.** They are clamped to `[1, totalLines]` and `endLine` is floored to `from`; out-of-range values silently clamp rather than error. Both optional; omit for the whole file.
- **Object NAME in the path is programmatic, never translated** (bilingual 1C). The metadata TYPE token folder can appear as `CommonModules`/`ОбщиеМодули` etc. depending on the configuration's language, but the object's own name segment is literal. If unsure of the exact path, list it with `list_modules` / `get_module_structure` rather than guessing a translation.
- **Resource filename** is derived from `modulePath`: slashes/backslashes → `-`, lowercased, prefixed `source-` and suffixed `.md` (e.g. `source-commonmodules-error-module.bsl.md`).
