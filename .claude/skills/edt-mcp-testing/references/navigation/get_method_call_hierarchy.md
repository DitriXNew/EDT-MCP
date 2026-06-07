# get_method_call_hierarchy — how to test

**Purpose.** For a BSL procedure/function (identified by `modulePath` + `methodName`), list either its callers (who invokes it, default) or its callees (what it invokes). Read-only semantic AST analysis — no model mutation.

**Preconditions.**
- TestConfiguration open in the workspace, EDT up, MCP on `:8765` (verify with `get_edt_version`).
- No open editor or cursor position needed — the tool loads the BSL EMF model itself; it is NOT a UI-cursor tool.
- Pick a method that actually exists. `Configuration/ManagedApplicationModule.bsl` has `OnStart` (calls `Message(...)`) and `BeforeStart` — handy fixtures. The `CommonModules/OK` and `CommonModules/Error` modules are near-empty.
- Does NOT mutate. No revert needed.

**Call (real).** Callees of `OnStart`:
```
get_method_call_hierarchy(
  projectName="TestConfiguration",
  modulePath="Configuration/ManagedApplicationModule.bsl",
  methodName="OnStart",
  direction="callees")
```

**Result.** Markdown resource (`ResponseType.MARKDOWN`, served at `embedded://call-hierarchy-<method>-<direction>.md`). Real output:
```
## Call Hierarchy: Configuration/ManagedApplicationModule.bsl :: OnStart

**Direction:** Callees (what this method calls)
**Total calls found:** 1

| # | Called Method | Line | Call Code |
|---|--------------|------|-----------|
| 1 | Message | 10 | `Message(Greeting + " \| sum=" + Sum + " \| total=" + Total)` |
```
- Callers table columns: `# | Module | Method | Line | Call Code` (Module/Method = the calling site's module path and containing method).
- Callees table columns: `# | Called Method | Line | Call Code`.
- Header line reports `Total references found:`/`Total calls found:` (the full count); when the count exceeds `limit` the header adds `(showing first N)` and only N rows are listed.

Empty result (real — `OnStart` has no callers):
```
## Call Hierarchy: Configuration/ManagedApplicationModule.bsl :: OnStart

**Direction:** Callers (who calls this method)
**Total references found:** 0

No callers found.
```
(Callees variant prints `No calls found in this method.`)

**Gotchas.**
- **Two different error shapes — don't assume JSON.**
  - *Method not found* returns a plain MARKDOWN string (NOT structured JSON): it starts with `Error: Method '<name>' not found in <modulePath>` followed by an `**Available methods**` bullet list. Real:
    ```
    Error: Method 'NoSuchMethod' not found in Configuration/ManagedApplicationModule.bsl

    **Available methods** (2):

    - BeforeStart
    - OnStart
    ```
    Use that list to spot the right name/casing — do NOT retry-spam guesses.
  - *Other failures* (missing `projectName`/`modulePath`/`methodName`, `direction` other than `callers`/`callees`, project not found, or "Could not load EMF model for …") return the structured `ToolResult.error` JSON: `{"success":false,"error":"…"}` with `isError:true`. So a failed call may be EITHER a markdown `Error:` string or `{success:false}` JSON depending on which check failed.
- **methodName is case-insensitive** (`equalsIgnoreCase`); `onstart` resolves the same as `OnStart`.
- **direction** defaults to `callers`; only `callers`/`callees` are valid (also case-insensitive). Anything else → structured error `direction must be 'callers' or 'callees'`.
- **limit** clamps to `[1, 500]`, default 100. The header's total is the true count even when rows are truncated.
- **modulePath is src-relative** (`Configuration/ManagedApplicationModule.bsl`, `CommonModules/MyModule/Module.bsl`) — the canonical module parameter. It must point at a real `.bsl`; otherwise you get the `Could not load EMF model …` JSON error.
- **Callers ≠ text search.** BSL calls aren't indexed cross-references, so the tool text-prefilters modules mentioning the name, then parses each and matches by the resolved AST feature URI (with a `Module.Method` qualifier / same-module fallback when the resolver left feature entries empty). A method called only indirectly (string-built name, reflection-style dispatch) won't appear.
- **Callees is a flat AST walk of the method body**, so it lists every invocation by name including platform globals (`Message`, `StrLen`, …) and duplicates — it is not deduplicated and does not resolve targets to modules.
- **Bilingual:** `methodName` and `modulePath` are programmatic identifiers — never translated. Type tokens in the path are irrelevant here (it's a file path, not a metadata FQN). Don't translate the method name to find its Russian "equivalent"; there isn't one.
- **Table cells are escaped** via the shared `MarkdownUtils.escapeForTable` (the real `Message(...)` snippet above shows `|` rendered as `\|`); long call snippets are comment-stripped and smart-truncated to `Name(...)`.
- **Flaky output channel:** runs on the SWT UI thread (`display.syncExec`). If the echoed payload looks dropped/garbled (bare `Error`/`Done`), don't hammer it — re-verify against `D:\WS\EDT\.metadata\.log` (full request/response) and avoid firing UI-thread tools concurrently.
