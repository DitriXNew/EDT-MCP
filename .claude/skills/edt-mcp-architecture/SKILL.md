---
name: edt-mcp-architecture
description: Map of the EDT-MCP plugin's target architecture — where the shared helpers live, the layering rules, and the canonical way to do project/metadata/code resolution. Use when starting work in this plugin, deciding where new code belongs, or before adding/refactoring an MCP tool.
---

# EDT-MCP — target architecture

A 1C:EDT plugin with an MCP server (~62 tools). This skill maps **how it should be** (the project is mid-refactor toward shared helpers). If a helper below does not yet exist in code, it is a refactor task — see the card in `.devtool/features/`.

## Package layout

Root: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server`

| Package | Holds | Rule |
|---|---|---|
| `tools/impl/` | one class per MCP tool (`implements IMcpTool`) | **registered tools only**; no utilities/bases here |
| `tools/` | `IMcpTool`, `McpToolRegistry` | the contract and the registry |
| `tools/metadata/` | metadata formatters | output rendering for read tools |
| `utils/` | shared helpers | reusable pieces go here |
| `protocol/` | MCP/JSON-RPC layer: `ToolResult`, `JsonUtils`, `JsonSchemaBuilder` | response/schema assembly |
| `tags/`, `groups/` | two Navigator features | must share a common base (see below) |
| `McpServer.java`, `Activator.java` | transport and OSGi lifecycle | not a tool catalog |

## Canonical shared APIs (use, don't duplicate)

> All helpers below exist in code. Verify the exact method name before calling (`grep`/Read).

- **Project resolution** → `ProjectContext.of(projectName)` (`.exists()`, `.project()`, `.notFoundMessage(name)`). Do not repeat `ResourcesPlugin.getWorkspace()...` by hand. (Metadata write tools get project+config via `AbstractMetadataWriteTool.resolveProjectAndConfig`.)
- **BM model access** → `BmTransactions.read(model, name, op)` / `write(model, name, op)`. Reads in a read boundary, writes in a write boundary. Persist a metadata write to disk via `BmTransactions.forceExportToDisk(project, [topFqn, configFqn])` (a bare write only enqueues the async export).
- **Tool parameters** → `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray` + `JsonUtils.requireArguments(params, ...)`. Don't parse the `Map` by hand. (There is no `ToolParams` class.)
- **Canonical key names** → `McpKeys` (`protocol/McpKeys.java`) for the param/result keys shared across many tools: `PROJECT_NAME`, `APPLICATION_ID`, `MODULE_PATH`, `LIMIT` (input params) and `MESSAGE`, `ACTION`, `PROJECT`, `ERROR` (result keys). Use these in `getInputSchema()`/`execute()`/result-building instead of bare `"projectName"` literals; file-specific keys stay as `private static final String` constants in the tool. (`error`/`success` are owned by `ToolResult` — only hand-built error fields outside it use `McpKeys.ERROR`.)
- **Metadata object/type resolution (bilingual)** → `MetadataTypeUtils` (`findObject`, `normalizeFqn`); an existing node/member by FQN — `MetadataNodeResolver.resolveExisting`/`resolveForCreate`; a form — `FormStructureReader.resolveMdForm`. These are the shared bilingual resolvers — don't write your own.
- **Synonym language** → `MetadataLanguageUtils.resolveLanguageCode(config, explicit)` + `getSynonymForLanguage(map, code)`. The synonym key is the **language code** (`getLanguageCode()`), NOT `getName()`.
- **Result/error** → `ToolResult`: `ToolResult.success().put(...)` / `ToolResult.error(...)`. Errors only via `ToolResult.error(...).toJson()`, never a bare string, never an exception escaping the tool.
- **Pagination** → the shared `Pagination` (`utils/Pagination.java`; one `limit`/`offset`, one "truncated" format).
- **Markdown tables** → the shared builder (escapes `|`/newline), not a per-tool StringBuilder.
- **Tool registration** → `BuiltInToolRegistrar`, not a list inside `McpServer`. EDT services — via `Activator.getDefault().getXxx()` or `ServiceAccess.get(IFoo.class)` (+ the package in MANIFEST Import-Package).

## What NOT to do (anti-patterns)

- Don't copy project/module/BM resolution into each tool — call the shared helper.
- Don't put utilities or abstract bases in `tools/impl/` — only `IMcpTool` classes (`move-nontool-helpers-out-of-impl`).
- Don't grow god-classes: `RenameMetadataObjectTool`/`FindReferencesTool` share `MetadataReferenceLocator` (`extract-metadata-reference-locator`); `McpServer` is being split (`decompose-mcpserver`).
- `tags/*` and `groups/*` — don't copy a third such stack; both features should inherit a common association-storage/service/refactoring base (`extract-tags-groups-shared-base`).

## Related

- Two-language (ru/en) correctness — the `edt-mcp-bilingual` skill.
- Cross-tool contract (parameter naming, errors, output) — the `edt-mcp-tool-conventions` skill.
- Adding a new tool — the `edt-mcp-new-tool` skill.
- Build and tests — the `edt-mcp-build-test` skill.
- The full refactor task list — the `.devtool/features/*.md` board.
