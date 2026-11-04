---
name: edt-mcp-new-tool
description: Canonical, battle-tested checklist for adding a new MCP tool to the EDT-MCP plugin the right way — the IMcpTool surface, shared resolvers, schema, registration, MANIFEST, the two MANDATORY test ratchets (unit + e2e), the golden snapshot, README, and the full compile→review→redeploy→live contour. Use when asked to add / create / scaffold a new MCP tool / EDT tool.
---

# EDT-MCP — adding a new MCP tool (canonical)

The procedure that actually compiles, passes every ratchet, and validates live. It tracks the real current API — **verify each class/method before use** (`grep`/Read, don't invent). Companion skills: `edt-mcp-architecture` (where things live), `edt-mcp-tool-conventions` (naming/errors/output), `edt-mcp-bilingual` (ru/en), `edt-mcp-e2e-testing` (e2e suite), `edt-mcp-build-test` (build).

> A new tool touches MANY places. Miss one and either a ratchet goes red (unit-coverage, e2e-coverage, tool-contract, golden) or the live `tools/list` drifts. Walk the whole list.

## Steps

1. **Class** in `tools/impl/XxxTool.java`: `implements IMcpTool` (read/action) or **`extends AbstractMetadataWriteTool`** for a tool that MUTATES the model — the base runs `executeOnUiThread(params)` on the UI thread (model mutation is safe only there), gives `resolveProjectAndConfig(projectName)` + `unwrapCauseMessage(e)` + default `getResponseType()=JSON`. `tools/impl/` holds the tool only (utilities/bases → `utils/`, `tools/base/`); `ToolImplementationPackageRatchetTest` checks every production top-level class there, including subpackages and unregistered classes, and accepts inherited `IMcpTool` implementations. Declare `public static final String NAME = "xxx";` (snake_case: `get_`/`list_`/`create_`/`adopt_`…).

2. **IMcpTool surface**:
   - `getName()` → `NAME`.
   - `getDescription()` — short, for the AI client (what it does + when). **End** with `"… Full parameters and examples: call get_tool_guide('<name>')."` (a unit test checks this; keep the description compact — the `tools/list` budget is shared).
   - `getInputSchema()` via `JsonSchemaBuilder.object().stringProperty(name, desc[, required]).integerProperty(...).enumProperty(name, desc, "a","b").objectArrayProperty(...).build()`. **Required is the boolean 3rd argument of a property; there is NO `.required(...)` method.** Parameter names are **lowerCamelCase** (`ToolContractConsistencyTest` fails snake_case). Canonical: `projectName`, `fqn`, `modulePath`, `limit`/`offset` (see `edt-mcp-tool-conventions`).
   - `getOutputSchema()` — JSON tools declare their result keys in a permissive object success envelope (`BuiltInToolOutputSchemaTest`), requiring only keys shared by every result mode (text and module validation have different envelopes); non-JSON tools leave it absent. Exercise each success shape through `harness.call()`, which validates the structured payload against the advertised schema.
   - **Guide** — full how-to (parameters, examples, gotchas, how to revert), served on-demand through the guide-resource channel. Do NOT override `getGuide()`: the default loads the bundled Markdown `guides/<name>.md` via `GuideLoader`. Author the guide as that file (one per tool); the body is the `## Guide` section (the renderer adds the `# <name>` H1, description and the `## Parameters` table itself, so use `## Parameter details` for param prose). `GuideCoverageTest` fails the build if a registered tool has no guide. Override `getGuide()` only for a guide that must be computed at runtime.
   - `getResponseType()` — TEXT/JSON/MARKDOWN/YAML/IMAGE (may be omitted when extending AbstractMetadataWriteTool → JSON).
   - **Every parameter read in execute() must be in the schema, and vice versa.**

3. **execute / executeOnUiThread**:
   - Arguments — `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray`; required ones — `JsonUtils.requireArguments(params, "projectName", "fqn")` (returns a ready error-JSON or null).
   - Project/configuration — `ProjectContext.of(name)` (utils) or `resolveProjectAndConfig(name)` (write base). NOT `ResourcesPlugin.getWorkspace()…` by hand.
   - **Model only inside a transaction boundary**: reads via `BmTransactions.read`, writes via `BmTransactions.write`. Submit export with `forceExportToDisk(project, [topObjectFqn, configurationFqn])` (member → parent top; include Configuration when its collection changes). Reuse `WriteScope` + `WriteExportBarrier` off SWT and outside the transaction; the write base already does this. Test observed output, not just submission, and preserve `mutationCommitted` on post-commit errors.
   - Metadata resolution by FQN — use `MetadataTypeUtils.normalizeFqn/findObject`, `MetadataNodeResolver.resolveExisting` / `resolveForCreate`, `FormStructureReader.resolveMdForm` (`Type.Name.Form.FormName` or `CommonForm.Name`). Canonicalize echoes/refusals with `toCanonicalEnglishFqn`; keep programmatic Names. Synonyms use language codes.
   - EDT services: typed via `Activator.getDefault().getXxx()` (`getConfigurationProvider`, `getV8ProjectManager`, `getDtProjectManager`, `getBmModelManager`, `getMdRefactoringService`) or `ServiceAccess.get(IFoo.class)` for a wired service (binding `.toService()`) — and **add the package to MANIFEST Import-Package** (step 5).
   - **Errors ONLY via `ToolResult.error(msg).toJson()`** (no exceptions escaping the tool, no bare `"Error: …"`). Success — `ToolResult.success().put(k, v)….toJson()`. An error must be actionable (name the bad value + how to fix / sibling tool).

4. **Registration** — in `tools/BuiltInToolRegistrar`: `import …impl.XxxTool;` + `registry.register(new XxxTool());` (next to siblings). Then place the tool in its **`ToolGroup`** (`preferences/ToolGroup.java`) and its toolset (`Toolsets.java`) — the preset and toolset ratchets fail for an ungrouped tool, and an ungrouped WRITER silently stays enabled in the read-only presets. A writer added to a group those presets disable also needs a registered preset migration (`ToolSettingsServiceTest` names it).

5. **MANIFEST.MF Import-Package** — add each NEW imported `com._1c.g5.*` package (e.g. `com._1c.g5.v8.dt.md.extension.adopt`). A miss is a runtime `ClassNotFound`, not a compile error.

6. **Unit test — MANDATORY** (`mcp/tests/.../tools/impl/XxxToolTest.java`). `BuiltInToolTestCoverageTest` fails the build if a registered tool has no `XxxToolTest`. Contract (no live runtime): NAME, `getResponseType()`, description contains `get_tool_guide('<name>')`, schema contains every parameter, the required array is correct (and excludes optional ones), output-schema keys. Deeper behaviour is e2e.

7. **e2e test — MANDATORY** (`tests/e2e/tools/test_<tool>.py`). The e2e coverage ratchet fails if a tool in `tools/list` has none. Read `edt-mcp-e2e-testing`. happy + negative + error-quality; anti-cheat ("would the test fail if the tool were broken?"). Writers belong in `MODEL_MUTATION_TOOLS` in `tests/e2e/harness.py`; register any read-only mode exemption in `_is_read_only_model_action`. Exercise real mutations, assert disk structure before model read-back, and restore extension fixtures with the shared helper in `finally`. `WriteScopeInventoryTest`, `ToolsetsTest` and `ToolGroupTest` must pass.

8. **Golden** — a new tool changes `tools/list`; regenerate and commit: `EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter test_tools_list_matches_committed_golden_snapshot`, then `git add tests/e2e/tools_list.golden.json`.

9. **README + tool docs** — update counts and preference-group membership; write a concise README usage overview with the target, defaults and required gates. The generator preserves these overviews while refreshing toolset membership and links. Generate `docs/tools/<name>.md` and its index entry (`edt-mcp-ready-to-deploy` step 6); `ToolDocsRatchetTest` and `docs/validate_agent_skills.py` require them.

10. **Full contour** (don't say "done" without it): `bash source/compile.sh` (compile + unit tests + every ratchet) → adversarial **Opus review** → redeploy to the dev EDT copy (`edt-redeploy.ps1`; the signal is the log line `MCP server UP on 8765`) → **live validation** against `:8765` → commit. A freshly deployed tool is NOT in this session's MCP deferred list — to check it live, call it through the e2e harness client (`python` → `harness.initialize(); harness.call("<name>", {...})`) or `Invoke-RestMethod`, not the tool's MCP wrapper. See `edt-mcp-build-test` and the dev-loop memory.

## Gotchas
- **`((IBmObject)x).bmGetFqn()` is valid ONLY on a TOP object** — for export use `bmGetTopObject().bmGetFqn()`; for a member echo canonicalize its addressed FQN instead of calling `bmGetFqn()` on the member. Collection echoes use `MetadataOrdering.collectionAddress`; a collection FQN is not a file-owner FQN.
- Model writes happen on the UI thread (the write base does this; a bare `IMcpTool` must wrap in `Display.syncExec` itself).
- `forceExportToDisk` wants the TOP FQN; a member change exports the parent top + (for a new top) the `Configuration` FQN.
- Escape Cyrillic with `\uXXXX` in regexes only. Keep real 1C/BSL/type/name data as Cyrillic in other strings and comments; surface prose is English only.

## Readiness checklist
- [ ] Class in `tools/impl/`, NAME snake_case, parameters lowerCamelCase, every parameter in the schema
- [ ] description → `get_tool_guide('<name>')`; `guides/<name>.md` authored; getOutputSchema present
- [ ] Shared resolvers + `ToolResult.error`; model only in a tx boundary; persist (forceExport) if a write
- [ ] Registered in `BuiltInToolRegistrar`; new packages in MANIFEST Import-Package
- [ ] `XxxToolTest` (unit ratchet) + `test_<tool>.py` (e2e ratchet)
- [ ] Golden regenerated; README count + group + table + detail
- [ ] `compile.sh` green → Opus review → redeploy → live validation → commit
