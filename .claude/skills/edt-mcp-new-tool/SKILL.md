---
name: edt-mcp-new-tool
description: Canonical, battle-tested checklist for adding a new MCP tool to the EDT-MCP plugin the right way — the IMcpTool surface, shared resolvers, schema, registration, MANIFEST, the two MANDATORY test ratchets (unit + e2e), the golden snapshot, README, and the full compile→review→redeploy→live contour. Use when asked to add / create / scaffold a new MCP tool / EDT tool.
---

# EDT-MCP — adding a new MCP tool (canonical)

The procedure that actually compiles, passes every ratchet, and validates live. It tracks the real current API — **verify each class/method before use** (`grep`/Read, don't invent). Companion skills: `edt-mcp-architecture` (where things live), `edt-mcp-tool-conventions` (naming/errors/output), `edt-mcp-bilingual` (ru/en), `edt-mcp-e2e-testing` (e2e suite), `edt-mcp-build-test` (build).

> A new tool touches MANY places. Miss one and either a ratchet goes red (unit-coverage, e2e-coverage, tool-contract, golden) or the live `tools/list` drifts. Walk the whole list.

## Steps

1. **Class** in `tools/impl/XxxTool.java`: `implements IMcpTool` (read/action) or **`extends AbstractMetadataWriteTool`** for a tool that MUTATES the model — the base runs `executeOnUiThread(params)` on the UI thread (model mutation is safe only there), gives `resolveProjectAndConfig(projectName)` + `unwrapCauseMessage(e)` + default `getResponseType()=JSON`. `tools/impl/` holds the tool only (utilities/bases → `utils/`, `tools/base/`). Declare `public static final String NAME = "xxx";` (snake_case: `get_`/`list_`/`create_`/`adopt_`…).

2. **IMcpTool surface**:
   - `getName()` → `NAME`.
   - `getDescription()` — short, for the AI client (what it does + when). **End** with `"… Full parameters and examples: call get_tool_guide('<name>')."` (a unit test checks this; keep the description compact — the `tools/list` budget is shared).
   - `getInputSchema()` via `JsonSchemaBuilder.object().stringProperty(name, desc[, required]).integerProperty(...).enumProperty(name, desc, "a","b").objectArrayProperty(...).build()`. **Required is the boolean 3rd argument of a property; there is NO `.required(...)` method.** Parameter names are **lowerCamelCase** (`ToolContractConsistencyTest` fails snake_case). Canonical: `projectName`, `fqn`, `modulePath`, `limit`/`offset` (see `edt-mcp-tool-conventions`).
   - `getOutputSchema()` — declare the JSON result keys (for JSON tools).
   - `getGuide()` — full how-to (parameters, examples, gotchas, how to revert). Served on-demand through the guide-resource channel — put DETAILS here, not in the description.
   - `getResponseType()` — TEXT/JSON/MARKDOWN/YAML/IMAGE (may be omitted when extending AbstractMetadataWriteTool → JSON).
   - **Every parameter read in execute() must be in the schema, and vice versa.**

3. **execute / executeOnUiThread**:
   - Arguments — `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray`; required ones — `JsonUtils.requireArguments(params, "projectName", "fqn")` (returns a ready error-JSON or null).
   - Project/configuration — `ProjectContext.of(name)` (utils) or `resolveProjectAndConfig(name)` (write base). NOT `ResourcesPlugin.getWorkspace()…` by hand.
   - **Model only inside a transaction boundary** (hard rule, CLAUDE.md): reads in a read boundary, writes via `BmTransactions.write(...)`. A bare write only enqueues the async `.mdo` export — persist via `BmTransactions.forceExportToDisk(project, [topObjectFqn, configurationFqn])` (pass the **TOP** FQN; for a member, its parent top; add the `Configuration` FQN = `((IBmObject)config).bmGetFqn()` because its collection changed).
   - Metadata resolution by FQN — use the SHARED resolvers (don't write the 47th copy): `MetadataTypeUtils.normalizeFqn/findObject` (bilingual), `MetadataNodeResolver.resolveExisting` (existing) / `resolveForCreate` (new), `FormStructureReader.resolveMdForm` (forms — expects `Type.Name.Forms.FormName` or `CommonForm.Name`). Synonym is keyed by the language CODE (see `edt-mcp-bilingual`).
   - EDT services: typed via `Activator.getDefault().getXxx()` (`getConfigurationProvider`, `getV8ProjectManager`, `getDtProjectManager`, `getBmModelManager`, `getMdRefactoringService`) or `ServiceAccess.get(IFoo.class)` for a wired service (binding `.toService()`) — and **add the package to MANIFEST Import-Package** (step 5).
   - **Errors ONLY via `ToolResult.error(msg).toJson()`** (no exceptions escaping the tool, no bare `"Error: …"`). Success — `ToolResult.success().put(k, v)….toJson()`. An error must be actionable (name the bad value + how to fix / sibling tool).

4. **Registration** — in `tools/BuiltInToolRegistrar`: `import …impl.XxxTool;` + `registry.register(new XxxTool());` (next to siblings).

5. **MANIFEST.MF Import-Package** — add each NEW imported `com._1c.g5.*` package (e.g. `com._1c.g5.v8.dt.md.extension.adopt`). A miss is a runtime `ClassNotFound`, not a compile error.

6. **Unit test — MANDATORY** (`mcp/tests/.../tools/impl/XxxToolTest.java`). `BuiltInToolTestCoverageTest` fails the build if a registered tool has no `XxxToolTest`. Contract (no live runtime): NAME, `getResponseType()`, description contains `get_tool_guide('<name>')`, schema contains every parameter, the required array is correct (and excludes optional ones), output-schema keys. Deeper behaviour is e2e.

7. **e2e test — MANDATORY** (`tests/e2e/tools/test_<tool>.py`). The e2e coverage ratchet fails if a tool in `tools/list` has none. Read `edt-mcp-e2e-testing`. happy + negative + error-quality; anti-cheat ("would the test fail if the tool were broken?"). A mutating tool: prefer non-mutating cases headless (the harness resets only the BASE fixture per-test, not the extension) + validate the mutating happy path LIVE; for a write-metadata tool that runs headless, check both the model read-back AND the on-disk structure (`poll_diff_contains`).

8. **Golden** — a new tool changes `tools/list`; regenerate and commit: `EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter test_tools_list_matches_committed_golden_snapshot`, then `git add tests/e2e/tools_list.golden.json`.

9. **README** — bump the tool COUNT (two places), add the tool to its group table, to the flat tool table, and to the detailed section (parameters must match the schema).

10. **Full contour** (don't say "done" without it): `bash source/compile.sh` (compile + unit tests + every ratchet) → adversarial **Opus review** → redeploy to the dev EDT copy (`edt-redeploy.ps1`; the signal is the log line `MCP server UP on 8765`) → **live validation** against `:8765` → commit. A freshly deployed tool is NOT in this session's MCP deferred list — to check it live, call it through the e2e harness client (`python` → `harness.initialize(); harness.call("<name>", {...})`) or `Invoke-RestMethod`, not the tool's MCP wrapper. See `edt-mcp-build-test` and the dev-loop memory.

## Gotchas
- **`((IBmObject)x).bmGetFqn()` is valid ONLY on a TOP object** — on a member (attribute/form/…) it throws "may be called on top objects only". For a top, use `bmGetTopObject().bmGetFqn()`; for a member, report the input FQN.
- Model writes happen on the UI thread (the write base does this; a bare `IMcpTool` must wrap in `Display.syncExec` itself).
- `forceExportToDisk` wants the TOP FQN; a member change exports the parent top + (for a new top) the `Configuration` FQN.
- Cyrillic in string literals/regexes goes through `\uXXXX` (Tycho non-UTF-8 safety); surface text is English only.

## Readiness checklist
- [ ] Class in `tools/impl/`, NAME snake_case, parameters lowerCamelCase, every parameter in the schema
- [ ] description → `get_tool_guide('<name>')`; getGuide/getOutputSchema present
- [ ] Shared resolvers + `ToolResult.error`; model only in a tx boundary; persist (forceExport) if a write
- [ ] Registered in `BuiltInToolRegistrar`; new packages in MANIFEST Import-Package
- [ ] `XxxToolTest` (unit ratchet) + `test_<tool>.py` (e2e ratchet)
- [ ] Golden regenerated; README count + group + table + detail
- [ ] `compile.sh` green → Opus review → redeploy → live validation → commit
