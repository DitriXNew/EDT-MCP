# get_content_assist — how to test

**Purpose.** Return BSL code-completion (content assist) proposals at a 1-based line/column in a module. It opens the file in the EDT editor, places the caret at the position, and runs the Xtext content assist processor, then returns the proposals as JSON (`displayString`, optional `documentation`).

**Preconditions.**
- Project open in the workspace (use `TestConfiguration`). The target `modulePath` (canonical; deprecated alias `filePath`) is **relative to the project `src/` folder** (e.g. `Configuration/ManagedApplicationModule.bsl`, `CommonModules/MyModule/Module.bsl`).
- The position must be inside a **BSL module** the editor can open as an Xtext editor; otherwise you get `"File is not a BSL module (not an Xtext editor)"`.
- Read-only: it does **not** mutate the model or disk (it only opens/focuses an editor and reveals the caret). No revert needed.
- Runs on the **UI thread** (`Display.syncExec`) and physically opens an editor — needs a live, non-headless EDT workbench (workspace `D:\WS\EDT`, MCP on `:8765`).

**Call (real, against TestConfiguration).** Caret on line 10 (the `Message(...)` call) just inside the `(`, where local var `Greeting` and global functions are in scope:
```
get_content_assist(
  projectName="TestConfiguration",
  modulePath="Configuration/ManagedApplicationModule.bsl",
  line=10, column=11,
  limit=15)
```

**Result.** JSON (`ResponseType.JSON`). Real output captured (EDT 2026.1.1.1), trimmed to the first proposals:
```json
{"success":true,
 "file":"/TestConfiguration/src/Configuration/ManagedApplicationModule.bsl",
 "line":10,"column":11,
 "totalProposals":219,"filteredOut":0,"skipped":0,"returnedProposals":15,
 "proposals":[
   {"displayString":"Greeting"},
   {"displayString":"GlobalSearch : <GlobalSearchManager>"},
   {"displayString":"GetAddressByLocation(Location) : <AddressData,Undefined>"},
   {"displayString":"GetBase64StringFromBinaryData(BinaryData) : <String>"}
 ]}
```
Key fields: `totalProposals` (all proposals before filter/offset/limit), `filteredOut` (dropped by `contains`), `skipped` (dropped by `offset`), `returnedProposals` (size of `proposals`). Each proposal has `displayString`; with `extendedDocumentation=true` it also gets `documentation` (HTML→Markdown via CopyDown, `<style>` stripped). Note the proposals are the **local var `Greeting`** plus platform globals — the local is resolved from the parsed module, proving the editor parsed the file.

**Gotchas.**
- **Readiness race → empty result (the headline trap).** A cold or rapid back-to-back call can return `totalProposals:0` with `proposals:[]` even at a valid position, because the just-opened editor / Xtext index isn't ready yet. Verified live: the warm call above returned `totalProposals:219`; an immediate identical follow-up returned `{"totalProposals":0,"proposals":[]}`. **Treat empty as "retry once after the editor is open / give the index a moment", NOT as a code bug.** Do not retry-spam — a single re-call after the editor is loaded is enough.
- **UI-thread tool.** Avoid hammering this (or other UI-thread tools) concurrently; serialize calls. The whole compute runs in `display.syncExec`.
- **`contains` filter is on `displayString`, case-insensitive, comma-separated OR.** It matches the *display* text (which includes signatures/types like `: <String>`), so filtering for a bare identifier may behave unexpectedly. `filteredOut` reports how many proposals the filter dropped. (When the index is cold and there are zero proposals, `filteredOut` is `0` too — see the race above.)
- **Pagination:** `limit` defaults to a preference (`100`, clamped 1..1000), `offset` skips first N *matching* proposals. Both apply after the `contains` filter.
- **1-based line/column.** `column` is 1-based and may equal `lineLength+1` (caret after last char). Out-of-range → `"Position is outside document bounds"`; bad line → `"Invalid line number: N"`.
- **Bilingual / dialect.** Proposals follow the module's BSL dialect. TestConfiguration uses the **English** dialect, so you see `Message`, `GetBase64StringFromBinaryData`, etc.; a Russian-dialect module yields Cyrillic platform names. The TYPE/keyword side is dialect-dependent — don't assume one language.
- **`modulePath` (canonical; deprecated alias `filePath`) is src-relative**, not an absolute or project-relative-to-root path; it is resolved via `BslModuleUtils.resolveModuleFile`.
- **Structured error contract.** Failures come back as `{"success":false,"error":"..."}` with `isError:true` — never a thrown exception. Common messages: `projectName is required`, `modulePath is required` (deprecated alias `filePath` still accepted), `Invalid line or column number`, `Line and column must be >= 1`, `Project not found: X`, `Project is closed: X`, `File not found: ... in project X`, `File is not a BSL module (not an Xtext editor)`, `No active workbench window` (headless EDT).
- **Flaky output channel.** If the result text comes back as a bare `Error`/`Done` or garbled, don't trust the echo — re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (it logs the full request/response) and re-call once.
