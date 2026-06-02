# find_references — how to test

**Purpose.** Find all places where a top-level metadata object is used: other metadata objects (forms, roles, subsystems, registers, the Configuration itself), produced-type references, predefined items, field references, and BSL code modules with line numbers. Read-only — never mutates the model.

**Preconditions.** Live EDT + the project is open and indexed in the workspace (BM model built). No open editor / cursor position is required — resolution is by FQN against the configuration, not by editor state. `objectFqn` must be a **top-level** object (exactly `Type.Name`, two segments). The tool runs on the UI thread (`Display.syncExec`), so avoid hammering it concurrently with other UI-thread tools. Does not mutate; no revert needed.

**Call (real, 2026-06-02):**
```
find_references(projectName="TestConfiguration",
                objectFqn="Catalog.Catalog")
```
Optional `limit` (integer, default 100, hard-clamped to max 500). Note it is a result-size *hint*: it caps the overall number of references collected before grouping at `limit*10` across all categories, NOT per category. The Russian type token also works: `objectFqn="Справочник.Catalog"` normalizes to `Catalog.Catalog`.

**Result.** Returned as an embedded Markdown resource (`ResponseType.MARKDOWN`), not JSON. Header `# References to <fqn>`, a `**Total references found:** N` line, a flat list of metadata references (`- <path> - <feature>`), and, when present, a `### BSL Modules` section listing each module with `[Line X; Line Y; ...]`. Real output for `Catalog.Catalog` (9 refs):
```markdown
# References to Catalog.Catalog

**Total references found:** 9

- Catalog.Catalog - Fields.Parent.Type - Type: types
- Catalog.Catalog - Fields.Ref.Type - Type: types
- Catalog.Catalog - Input by string - Description
- Catalog.Catalog - Input by string - Code
- Catalog.Catalog.Form.ItemForm.Form - Attributes.Object.Type - Type: types
- Catalog.Catalog.Form.ItemForm.Form - Items.Attribute.Data path - Type: types
- Catalog.Catalog.Form.ItemForm.Form - Items.Code.Data path - Type: types
- Catalog.Catalog.Form.ItemForm.Form - Items.Description.Data path - Type: types
- Configuration - Catalogs - catalogs
```
Key checks: total count matches the list length; self-references (within `Catalog.Catalog` itself and its forms) are intentionally kept (EDT shows them); the Configuration containment reference is present; produced-type references are tagged `Type: ...`. No `### BSL Modules` section here because TestConfiguration has no BSL that uses this catalog — pick an object that is referenced in code to exercise the line-number path.

**Gotchas.**
- **Structured error contract.** Failures arrive via `ToolResult.error(...)` → `{success:false, error:"..."}` with `isError:true`, NOT as Markdown. A **sub-object FQN** (more than two segments, e.g. `Catalog.Catalog.Attribute.Foo`) yields a specific guided error: "Object not found: ... find_references only supports top-level metadata objects ... Sub-objects such as attributes, forms, commands and tabular sections are not supported". A plain unknown two-segment FQN gives the shorter `Object not found: <fqn>`. Unknown project → `Project not found: <name>`.
- **Top-level only.** Resolution goes through `MetadataTypeUtils.findObject` (English/Russian, singular/plural type token). The object **NAME** segment is programmatic and never translated — only the leading TYPE token may be Russian (`Справочник`/`Catalog`). Do not pass a translated name.
- **`limit` is an overall hint, not per-category.** The schema text says "per category" loosely, but the code caps total collected refs at `limit*10` before grouping. For exhaustiveness on a large object (e.g. on the IRP config) raise `limit` toward 500.
- **BSL line numbers can be `Line 0`.** Line resolution loads the `.bsl` via Xtext and uses `NodeModelUtils`; if the node can't be resolved it falls back to `0`. A `Line 0` is a resolution fallback, not necessarily a real line.
- **Internal/technical refs are filtered.** `dbview`, derived command interface (`cmi`+`deriveddata`), and paths starting with `Value types` / `Form context` / `Db view defs` / `Standard commands` are dropped by design — expect the count to be smaller than a raw cross-reference dump.
- **Flaky output channel.** If the result text arrives garbled or as a bare `Error`/`Done` instead of the Markdown payload, do NOT retry-spam. Re-verify independently: check `D:\WS\EDT\.metadata\.log` (it logs the full request/response) and confirm the server is up (`:8765` / `get_edt_version`). Trust the log over the echoed text.
