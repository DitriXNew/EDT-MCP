---
name: edt-mcp-tool-conventions
description: Cross-tool consistency contract for EDT-MCP tools — parameter naming, error shape, output format, pagination, not-found semantics, schema/README alignment. Use when editing any class under tools/impl, adding a parameter, changing a tool's output, or reviewing a tool for consistency.
---

# EDT-MCP — tool contract (consistency)

The same concept must behave the same way across every tool. This skill is the canon to hold to.

## Parameter naming

| Concept | Canonical | Anti-example |
|---|---|---|
| Project | `projectName` | `project` |
| Module | `modulePath` (from `src/`; the resolver also accepts an absolute path) | `module`, or a decomposed `objectName+moduleType+formName+commandName` |
| Fully-qualified name | `fqn` | — |
| Object name | `objectName` | — |
| Synonym / language | `synonym` / `language` | — |
| Limit / offset | `limit` / `offset` | `maxResults`, `maxDepth` |

Parameter names are **lowerCamelCase** — `ToolContractConsistencyTest` fails snake_case. When renaming a parameter, accept the old name as a documented alias for one release, then deprecate.

## Read parameters typed

Read arguments via `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray`; check required ones via `JsonUtils.requireArguments(params, "a", "b")` (returns a ready error-JSON or null). Don't parse a raw `Map<String,String>` by hand. (There is no typed `ToolParams` class — `JsonUtils` is the canon.)

**Every parameter read in `execute()` must be declared in `getInputSchema()`** (otherwise it is invisible to schema-driven clients) and vice versa.

Use `JsonUtils.parseIntegralInt` for exact integer validation and render whole transport numbers without `.0`; index errors name the destination collection's valid range.

## Error contract

- Errors only via `ToolResult.error(...)` (machine-readable: success=false + message/code). Never return a bare `"Error: …"` / `"Project not found"` string, never let an exception escape the tool.
- "project not found" — one text/shape through the shared `ProjectContext` resolver.
- Reject absent/null/blank `tools/call` `params.name` before registry lookup with JSON-RPC `-32602` and a `tools/list` hint; a nonblank unknown name remains `-32601`. These are protocol errors, not tool-result errors.
- Describe platform exceptions through `PlatformFailures.describe`; add the bad input and recovery action at the call site.

## Output format

- Similar tools use the same format (don't make one JSON and another markdown without a reason). The plugin default is markdown (see `IMcpTool.ResponseType`).
- JSON fields use one style (don't mix camelCase/snake_case in similar responses).
- Ordering responses report observed model order and moves, not the planned permutation; check optional autosort policy before manual-order writes. Governed responses share `autosort:{plugin,governs,setting,order,effect,userAction}` plus the same plain message; omit the block when inactive. `setting` is a human scope; `userAction` asks the user through verified EDT UI labels and forbids agent preference changes.
- not-found — one semantics: an empty list for "list", an error for "get a specific one". Don't mix null/empty/error.
- Canonicalize create/modify/delete FQN echoes, preview/batch rows, follow-up hints and write-guard refusals with `MetadataTypeUtils.toCanonicalEnglishFqn`: English structural tokens, unchanged programmatic Names. Delete scope hashes use the canonical address as well as the removal scope.
- Base success claims on observed effects: XML export requires a new or proven rewritten `Configuration.xml` (newer mtime or changed size/digest). Export submission is not completion; `dcs` requires an observed shared export drain. An error after a committed write carries `mutationCommitted`; recover/read back before retrying.
- Mutation reports must follow read-back: a successful native call or deletion identity alone does not prove the resulting state. Infobase writers share `InfobaseBindings` and report `preservedBindings`, `restoredBindings`, `removedBindings`, and `unrestoredBindings`; incomplete recovery is an error. Claim removal only after observing the old binding gone. UUID-wide cleanup requires confirmed global deregistration; partial dissociation remains project-scoped.
- Infobase recovery keeps differing non-null choices and restores cleared bindings; manual clears during the call are restored too. Do not infer user intent from preference/launch listeners. Explicit `setDefault=true` wins for the target project's default.

### Response format policy (Markdown vs JSON)

`structuredContent`/JSON is for the client to consume (UI rendering, verbatim round-trip of identifiers, a declared `outputSchema`), NOT for the agent to read. **MARKDOWN is the default** (token-efficient, readable).

A tool returns JSON only when its result carries:

- **(a)** round-trip IDs another tool consumes;
- **(b)** machine-structured positions (e.g. error line/column);
- **(c)** a declared `outputSchema`;
- **(d)** UI-rendered data.

Action/confirmation/status results with none of these return MARKDOWN. `write_module_source` is the reference MARKDOWN action tool; `AbstractMetadataWriteTool` subclasses stay JSON because they return the created object's round-trip FQN.

Tool families that stay JSON, and why:

- metadata-writes (`create_metadata`, `sort_metadata`, `modify_metadata`, `delete_metadata`) → addressed by **FQN** *(a)*;
- debug / profiling tools → launch / application / breakpoint **IDs** + live session state *(a)*;
- `validate_query` → error **line/col** *(b)*;
- `list_configurations` → config **identities** *(a)*;
- `clean_project`, `update_database` → destructive status whose JSON shape is consumed by e2e *(d-like)*.

Markdown action tools (`revalidate_objects`, `export_configuration_to_xml`, `import_configuration_from_xml`, `write_module_source`) emit status + paths/counts only — no round-trip data. Build the body with `FrontMatter` + a markdown summary; any table goes through `MarkdownUtils` (escapes every cell); errors still via `ToolResult.error(...)`.

## Pagination

Use the shared `Pagination` (`utils/Pagination.java`: one `limit`/`offset`, one truncation-notice format). Don't invent a per-tool scheme.

## Result / markdown assembly

Through `ToolResult`/`JsonUtils` and the shared markdown-table builder, not a hand-rolled StringBuilder.

## Schema ↔ code ↔ README

- For mode-dependent JSON results, keep branch-specific output keys optional unless present in every branch. `validate_query` text mode has a top-level verdict; module mode has per-literal verdicts in `queries` plus `skipped` and counts. Extraction counts do not prove runtime-query coverage.
- `BuiltInToolRegistrar` is the source of truth for the tool set; the README catalog + the e2e golden (`tests/e2e/tools_list.golden.json`) must match it. A new tool changes the golden → regenerate (`EDT_MCP_UPDATE_GOLDEN=1`).
- Every tool: a non-empty meaningful description and a description for every parameter.
- Match `outputSchema` to every successful `structuredContent` branch. Preserve response shapes in use; prefer precise `oneOf` for disjoint field shapes, with each branch's item types and required members. Build regression responses through production code.

## Class placement

`tools/impl/` holds `IMcpTool` classes only. Utilities/abstract bases go in `utils/`/`tools/base/`. Project/model resolution goes through the shared helpers (skill `edt-mcp-architecture`). ru/en correctness — skill `edt-mcp-bilingual`.
