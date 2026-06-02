# get_symbol_info — how to test

**Purpose.** Returns hover/type info for the symbol at a 1-based `line`/`column` in a BSL module: the same content EDT shows on mouse-hover (inferred types, parameter docs, signatures). Source: `GetSymbolInfoTool.java`. Falls back to structural EObject analysis (an EMF table) when hover yields nothing.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`, project `TestConfiguration` open.
- Needs a module with real BSL. The `CommonModules/OK` and `CommonModules/Error` modules in TestConfiguration are empty stubs — use `Configuration/ManagedApplicationModule.bsl` (has `BeforeStart(Cancel)` and `OnStart()` with variable assignments and a `Message(...)` call).
- Resolves through the UI thread + an open Xtext editor: the tool calls `IDE.openEditor` on the workbench, reads hover via reflection, and **closes the editor afterward if it opened it** (no tab pollution). If there is no active workbench window/page or the editor won't open, it falls back to a headless EMF model load (`getSymbolInfoViaEmf`).
- Read-only. Does **not** mutate the model or disk. No revert needed.

**Call (real, 2026-06-02).** Point `line`/`column` at the first character of an identifier. Best clean result is a variable read inside a method body:
```
get_symbol_info(
  projectName="TestConfiguration",
  modulePath="Configuration/ManagedApplicationModule.bsl",
  line=7, column=2)        # the "Greeting" in: Greeting = "Debug e2e OK";
```

**Result.** `ResponseType.MARKDOWN` — returned as a resource (`embedded://symbol-info-<line>-<col>.md`), NOT JSON, wrapped in YAML frontmatter (`projectName`, `module`, `line`, `column`). The body is hover Markdown (HTML→Markdown via CopyDown). Real output for the call above:
```
---
projectName: TestConfiguration
module: Configuration/ManagedApplicationModule.bsl
line: 7
column: 2
---
Variable <[String](v8help:/8.5.1/SyntaxHelperLanguage/def_String)\>\* Greeting

\[Thin client, thick client (managed application), mobile application (client), web-client, mobile client\]

\* — calculated by the built-in language type system.
```
A platform parameter gives a documentation-rich hover (real, `line=2, column=23` → the `Cancel` param of `BeforeStart`):
```
Parameter <[Boolean](.../def_Boolean.html)\> Cancel

Indicates that the program start is canceled. ...
Default value: [False](.../def_BooleanFalse.html).
```
Representative EMF-fallback shape (the `buildEObjectInfo` Markdown table) — emitted when hover is empty but the EObject resolves; e.g. on a method name it is a `Method`/`Function` row set:
```
| Property | Value |
|---|---|
| **Symbol** | `OnStart` |
| **Kind** | Procedure |
| **Signature** | `Procedure OnStart()` |
| **Export** | No |
| **Lines** | 6 - 11 |
```
Other table `Kind`s the fallback can emit: `Parameter`, `StaticFeatureAccess`, `DynamicFeatureAccess`, `Invocation`, `Module`, or a generic `Token`/`Grammar` pair when only a leaf node resolves.

**Gotchas.**
- **UI-thread + editor.** Runs on `Display.syncExec`; avoid hammering this concurrently with other UI-thread tools (`get_form_screenshot`, `get_content_assist`). A blank/odd result is often an editor/render-mode issue, not a code bug.
- **Annotation-hover quirk (observed live).** At some positions the configured hover is the annotation hover, and the tool returns its raw `toString()`, e.g. `com._1c.g5.v8.dt.bsl.ui.hover.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@46df7d9b` (seen at `line=6,col=11` = `OnStart` decl, and `line=10,col=2` = `Message(`). This is a non-fatal, low-value payload — point the position at an identifier read inside a body (like `line=7,col=2`) for the type hover instead.
- **Empty-position pre-check.** Whitespace, end-of-line, and inside a `//` comment return the literal `No symbol at this position.` (verified at `line=3,col=5`, inside `//TODO`). Comment detection ignores `//` inside string literals. If hover/EObject both find nothing on a real token you get `No symbol found at this position.`.
- **Structured error contract.** Failures arrive as `{success:false, error:"…"}` with `isError:true` (the harness/protocol diverts a JSON error payload). The tool also converts its internal `"Error: …"` sentinels (no workbench/page, editor failures, position out of bounds, not a BSL Xtext editor) into that same `ToolResult.error` JSON at one boundary. Concrete errors: `projectName is required`, `modulePath is required` (deprecated alias `filePath` still accepted), `Invalid line or column number`, `Line and column must be >= 1`, `Project not found: …`, `Project is closed: …`, `File not found: … in project …`, `Could not get symbol info`.
- **Flaky output channel.** The result text sometimes drops to a bare `Error`/`Done` instead of the real payload (this happened here on the file-not-found probe). Do NOT retry-spam — re-verify from the EDT log `D:\WS\EDT\.metadata\.log` (full request/response) and `git status`.
- **`line`/`column` are 1-based** and parsed leniently (`33` or `33.0` both accepted). `modulePath` is `src/`-relative (canonical; deprecated alias `filePath`; resolved via `BslModuleUtils.resolveModuleFile`).
- **Bilingual.** The hover text language (e.g. "Variable"/"Parameter", platform doc) follows EDT's UI locale, not a tool arg. Object NAMEs in BSL are programmatic (never translated); only platform TYPE tokens/keywords are dialect-aware.
