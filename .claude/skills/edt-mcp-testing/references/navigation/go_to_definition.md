# go_to_definition — how to test

**Purpose.** Resolves a symbol to its definition: a method (`ModuleName.MethodName` or bare `MethodName` + `modulePath`) gets its source/signature/location; a metadata FQN (`Catalog.Products`, Russian type names too) gets its kind and list of available BSL modules. Read-only — the semantic inverse of `find_references`.

**Preconditions.** Project open in the workspace and indexed (Xtext/BSL index built; right after a `-clean` redeploy give EDT a moment to finish indexing). No open editor needed — resolution is model/file based, not editor-position based (the tool takes a `symbol` string, not a cursor line/offset). Does NOT mutate; no revert required. `TestConfiguration` works for the metadata-FQN and not-found paths; its `CommonModules/OK` and `CommonModules/Error` are intentionally stub one-token files with no real methods, so use a richer config (e.g. IRP) for a method-resolution success.

**Call (real).** Metadata FQN against TestConfiguration (verified 2026-06-02):
```
go_to_definition(projectName="TestConfiguration", symbol="Catalog.Catalog")
```
Method form (qualified — resolves a CommonModule method; bare name needs `modulePath`):
```
go_to_definition(projectName="<ConfigWithCode>", symbol="MyCommonModule.MyMethod", includeSource=true)
go_to_definition(projectName="<ConfigWithCode>", symbol="MyMethod", modulePath="Documents/SalesOrder/ObjectModule.bsl")
```

**Result.** Markdown resource (`ResponseType.MARKDOWN`), saved as `definition-<symbol>.md`, with YAML frontmatter + body.

Metadata FQN — real output (TestConfiguration, 2026-06-02):
```
---
projectName: TestConfiguration
kind: MetadataObject
type: Catalog
name: Catalog
---
No BSL modules found for this object.

*Use `get_metadata_details` for full object properties, or `read_module_source`/`read_method_source` to read specific modules.*
```
Frontmatter keys for a metadata hit: `projectName`, `kind: MetadataObject`, `type`, `name`. Body lists `### Available Modules` (the object's `.bsl` paths under `src/`) when any exist.

Method hit — representative shape (derived from source `formatMethodDefinition`; TestConfiguration has no real methods to demo):
```
---
projectName: <project>
module: CommonModules/MyModule/Module.bsl
method: MyMethod
type: Function          # or Procedure
export: true
startLine: 42           # includes the doc-comment block above the method
endLine: 67
totalLines: 310
region: Public          # containing #Region, if any
qualifiedName: MyModule.MyMethod   # only for the qualified form
---
```bsl
// doc-comment ... + Procedure/Function ... EndProcedure/EndFunction
```
```
`includeSource=false` omits the fenced `bsl` block (frontmatter only). EMF/Xtext model is used when available; if not, a text-scan fallback produces the same frontmatter (no `export`/`region` guaranteed).

Not-found — real output (TestConfiguration, 2026-06-02): a Markdown suggestion page (NOT a JSON error), starting `## Symbol not found: Catalog.DoesNotExist`, with "Did you mean?" (similar objects, when the type is recognized), the full `### Supported Metadata Types` list, a note that Russian type names work (Справочник/Документ/РегистрСведений), and a `**Tip:**` about `modulePath` for non-common modules.

**Gotchas.**
- **Two error contracts.** Argument/structural failures return the structured `{success:false,error}` JSON with `isError:true` — empty `projectName` ("projectName is required"), empty `symbol` ("symbol is required"), `projectName` not found ("Project not found: …"), a bare method name with no `modulePath` ("modulePath is required when symbol is an unqualified method name…"), or a missing module via the text fallback ("Module not found: src/…"). But a *resolvable shape that simply isn't found* (unknown `Type.Object`, or method-not-found inside a real module) returns a helpful **Markdown** suggestion page, not the JSON error. Don't assert a JSON error for "symbol exists nowhere".
- **Resolution order for two-part symbols.** `firstPart.secondPart` is tried as `CommonModule.method` FIRST, then as a metadata FQN. A CommonModule whose name collides with a metadata type token would win as a module — keep test names unambiguous.
- **Bilingual.** The TYPE token is bilingual/normalized (`Справочник`→`Catalog`, plural and singular accepted via `MetadataTypeUtils.normalizeFqn`/`findObject`); the object NAME is programmatic and never translated — pass the exact `Name`. Russian type names are supported on input and echoed back as the normalized English type in frontmatter.
- **`modulePath` is the canonical module param**, `src/`-relative (e.g. `CommonModules/MyModule/Module.bsl`); required only for bare unqualified method names.
- **UI-thread + index.** `execute()` runs on the SWT display thread (`syncExec`) and needs the BSL index ready. Don't hammer UI-thread tools concurrently; if results look dropped/garbled (flaky output channel — a bare "Error"/"Done" instead of the payload), do NOT retry-spam: re-verify via the EDT log `D:\WS\EDT\.metadata\.log` (full request/response) and `get_edt_version` for liveness, then make ONE clean call.
- **`startLine` includes the doc-comment** above the method (scans back over adjacent `//` lines), so it can be earlier than the `Procedure`/`Function` keyword line.
