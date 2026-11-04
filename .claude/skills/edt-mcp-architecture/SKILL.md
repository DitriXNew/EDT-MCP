---
name: edt-mcp-architecture
description: Map of the EDT-MCP plugin's target architecture — where the shared helpers live, the layering rules, and the canonical way to do project/metadata/code resolution. Use when starting work in this plugin, deciding where new code belongs, or before adding/refactoring an MCP tool.
---

# EDT-MCP — target architecture

A 1C:EDT plugin with an MCP server. This skill maps **how it should be** (the project is mid-refactor toward shared helpers). If a helper below does not yet exist in code, it is a refactor task — see the card in `.devtool/features/`.

## Package layout

Root: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server`

| Package | Holds | Rule |
|---|---|---|
| `tools/impl/` | one class per MCP tool (`implements IMcpTool`) | **registered tools only**; no utilities/bases here |
| `tools/` | `IMcpTool`, `McpToolRegistry` | the contract and the registry |
| `tools/metadata/` | metadata formatters | output rendering for read tools |
| `utils/` | shared helpers | reusable pieces go here |
| `protocol/` | MCP/JSON-RPC layer: `ToolResult`, `JsonUtils`, `JsonSchemaBuilder` | response/schema assembly |
| `ipc/` | Java 17 bootstrap, framing, private registry, handshake and Windows GUI starter | no Eclipse/OSGi dependency in the embedded runtime |
| `transport/` | plugin-side IPC listener and bootstrap extraction | use the shared `McpProtocolHandler`; IPC failure must not stop HTTP |
| `tags/`, `groups/` | two Navigator features | must share a common base (see below) |
| `McpServer.java`, `Activator.java` | transport and OSGi lifecycle | not a tool catalog |

## Canonical shared APIs (use, don't duplicate)

> All helpers below exist in code. Verify the exact method name before calling (`grep`/Read).

- **Project resolution** → `ProjectContext.of(projectName)` (`.exists()`, `.project()`, `.notFoundMessage(name)`). Do not repeat `ResourcesPlugin.getWorkspace()...` by hand. (Metadata write tools get project+config via `AbstractMetadataWriteTool.resolveProjectAndConfig`.)
- **BM model access** → `BmTransactions.read(model, name, op)` / `write(model, name, op)`. Submit disk export via `BmTransactions.forceExportToDisk(project, [topFqn, configFqn])`; submission alone does not confirm persistence. Reuse `tools/base/WriteExportBarrier` with `WriteScope` after leaving SWT and the BM transaction; metadata writes and `dcs` share its bounded drain.
- **Tool parameters** → `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray` + `JsonUtils.requireArguments(params, ...)`. Don't parse the `Map` by hand. (There is no `ToolParams` class.)
- **Canonical key names** → `McpKeys` (`protocol/McpKeys.java`) for the param/result keys shared across many tools: `PROJECT_NAME`, `APPLICATION_ID`, `MODULE_PATH`, `LIMIT` (input params) and `MESSAGE`, `ACTION`, `PROJECT`, `ERROR` (result keys). Use these in `getInputSchema()`/`execute()`/result-building instead of bare `"projectName"` literals; file-specific keys stay as `private static final String` constants in the tool. (`error`/`success` are owned by `ToolResult` — only hand-built error fields outside it use `McpKeys.ERROR`.)
- **Metadata object/type resolution (bilingual)** → `MetadataTypeUtils` (`findObject`, `normalizeFqn`); an existing node/member by FQN — `MetadataNodeResolver.resolveExisting`/`resolveForCreate`; a form — `FormStructureReader.resolveMdForm`. These are the shared bilingual resolvers — don't write your own.
- **Collection order** → `MetadataOrdering` for bilingual levels, moves and insert bounds; `MetadataAutoSort.forProject` for optional V8 code style policy. Reuse its native comparator and shared notice/refusal; autosort preferences are read-only to tools. Ask the user through the notice’s EDT UI path for any manual-order setting change.
- **Address rendering** → `MetadataTypeUtils.toCanonicalEnglishFqn` for write refusals/echoes and form-structure headings. Navigator tags/groups persist EClass-token/name pairs (`CatalogAttribute.Width`); retain `EObjectFqnNavigator` for these instead of substituting `MetadataNodeResolver` kind aliases.
- **Stored queries** → `ModuleQueryExtractor` for detached saved-BSL literal extraction and module-coordinate mapping; `QueryValidationService` for the verdict in both text and module modes. DCS auto-fill belongs in `DcsQueryAutoFill`, inside the existing write transaction, not a queued designer job.
- **Platform failures** → `PlatformFailures.describe(Throwable)`; reuse its status/exception traversal, including debug exception precedence, instead of tool-local unwrapping.
- **Synonym language** → `MetadataLanguageUtils.resolveLanguageCode(config, explicit)` + `getSynonymForLanguage(map, code)`. The synonym key is the **language code** (`getLanguageCode()`), NOT `getName()`.
- **Result/error** → `ToolResult`: `ToolResult.success().put(...)` / `ToolResult.error(...)`. Errors only via `ToolResult.error(...).toJson()`, never a bare string, never an exception escaping the tool.
- **Output schemas** → use `JsonSchemaBuilder` in `protocol/`; use `property(name, description, propertySchema)` for composed schemas such as `oneOf`, rather than adding a shape-specific builder method.
- **Application defaults** → `ApplicationSupport.getDefaultApplicationIdBounded` for the effective default; `getStoredDefaultApplicationId` for snapshots of the explicit choice. The effective getter may infer a sole application or clear a stale choice. Write defaults through `IApplicationManager.setDefaultApplication`, never preferences.
- **Infobase writes** → `InfobaseBindings.capture(manager).run(...)` for shared snapshot, operation, verification, recovery and reporting. Keep infobase writes coordinated through `InfobaseMutationCoordinator`; use `ApplicationDefaultChange` for creation rollback and explicit `setDefault` handling.
- **Standalone servers** → public `utils/StandaloneServerSupport` for WST service/application/module lookup and server operations; keep reflection out of individual tools.
- **Pagination** → the shared `Pagination` (`utils/Pagination.java`; one `limit`/`offset`, one "truncated" format).
- **Markdown tables** → the shared builder (escapes `|`/newline), not a per-tool StringBuilder.
- **Tool registration** → `BuiltInToolRegistrar`, not a list inside `McpServer`. EDT services — via `Activator.getDefault().getXxx()` or `ServiceAccess.get(IFoo.class)` (+ the package in MANIFEST Import-Package).
- **Transport ownership** → classify history status from the complete response before capping bodies; preserve it on background completion. Clear the single interrupt slot by call identity (`clearActiveToolCall(call)`); finishing an older call must not clear a newer one, and older calls are not restored.
- **Connection state** → keep capabilities, request ids and response/notification sinks per connection. Never use a mutable last-client slot across HTTP/IPC. Never replay an invocation after disconnect. Attach is cross-platform; automatic windowed startup is Windows-only. See `docs/stdio.md`.

## What NOT to do (anti-patterns)

- Don't copy project/module/BM resolution into each tool — call the shared helper.
- Don't put utilities or abstract bases in `tools/impl/` — only `IMcpTool` classes.
- Don't grow god-classes: `RenameMetadataObjectTool`/`FindReferencesTool` share `MetadataReferenceLocator` (`extract-metadata-reference-locator`); `McpServer` is being split (`decompose-mcpserver`).
- `tags/*` and `groups/*` — don't copy a third such stack; both features should inherit a common association-storage/service/refactoring base (`extract-tags-groups-shared-base`).

## Related

- Two-language (ru/en) correctness — the `edt-mcp-bilingual` skill.
- Cross-tool contract (parameter naming, errors, output) — the `edt-mcp-tool-conventions` skill.
- Adding a new tool — the `edt-mcp-new-tool` skill.
- Build and tests — the `edt-mcp-build-test` skill.
- The full refactor task list — the `.devtool/features/*.md` board.
