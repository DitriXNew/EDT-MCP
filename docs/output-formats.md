# Output Formats

Each tool declares a response type (`IMcpTool.getResponseType()`), with some selecting a format per call. **The default is Markdown**: a tool without a response-type override returns an EmbeddedResource with `mimeType: text/markdown`. The non-default types and per-call variants are enumerated below; everything else is Markdown.

## Response format policy (Markdown vs JSON)

`structuredContent`/JSON exists for clients to consume, not for the agent to read: it is justified only by verbatim round-trip of identifiers, machine-structured positions, a declared `outputSchema`, or UI-rendered data. **Markdown is the default** because it is more token-efficient and directly readable.

The e2e harness validates successful `structuredContent` against cached `tools/list` output schemas. Schemas preserve branch-specific shapes: `modify_metadata.applied` is a string array for property writes and an object of integer counts `{rights, templates, roleProperties}` for Role payloads. Template content edits use the separate `template_content` envelope, with `method` and a `template` result containing spreadsheet counts or a text content hash.

A tool returns **JSON** only when its result carries at least one of:

- **(a) round-trip IDs** another tool consumes (e.g. a created object's FQN, a launch/application ID, a breakpoint ID);
- **(b) machine-structured positions** (e.g. an error line/column);
- **(c) a declared `outputSchema`**;
- **(d) UI-rendered data**.

An action/confirmation/status result with **none** of these returns **Markdown**. `write_module_source` is the reference Markdown action tool; `revalidate_objects`, `export_configuration_to_xml`, and `import_configuration_from_xml` follow it (status + paths/counts, no round-trip data).

Which tool families stay JSON, and why:

- **metadata-writes** (`create_metadata`, `sort_metadata`, `modify_metadata`, `delete_metadata`, via `AbstractMetadataWriteTool`) — return the edited object's round-trip **FQN** *(a)*. `delete_metadata` also accepts `operations: [{action: "delete", fqn: "..."}]` instead of `fqn` for 1..100 members of one form: preview, then `confirm:true`. Its batch mode uses one model transaction/export, aggregates preflight errors with zero applied writes, and preserves committed operation indexes on export refusals. `atomic` defaults to true; false is refused. Attributes/columns, multiple containers, create/modify actions, `force:true`, `timeout` and unknown fields are unsupported. Disk-export state and written-object markers remain separate from model atomicity. See the [batch contract](../mcp/bundles/com.ditrix.edt.mcp.server/guides/delete_metadata.md#atomic-single-form-batch).
- **debug / profiling tools** — return launch / application / breakpoint IDs and live session state consumed by follow-up calls *(a)*;
- **`validate_query`** — returns the error **line/column** *(b)*; complete native DBView validation reports `VALID`, `INVALID`, or `PARTIAL`; query-bearing DCS/DynamicList writes share this validator and refuse proven errors before mutation.
- **`list_configurations`** — returns config **identities** consumed by other tools *(a)*;
- **`clean_project`** / **`update_database`** / **`infobase_sessions`** — return machine-readable status whose JSON shape is asserted by e2e *(d-like contract)*.

Errors are reported the same way regardless of a tool's normal format — see the **Error contract** below.

- **Markdown tools** (the default): tools without another response type below, delivered as an EmbeddedResource with `mimeType: text/markdown`. Examples include module/source reads, metadata details, code search, reference navigation, comparison, profiling results, job polling, YAXUnit reports, XML import/export and translation tools.
- **YAML tools**: `get_configuration_properties` — returns a human-readable YAML body as an EmbeddedResource (resource named `*.yaml`, `mimeType: text/yaml`).
- **JSON tools** (return JSON with `structuredContent`): `adopt_metadata_object`, `apply_quick_fix`, `build_external_objects`, `business_process_route_map`, `clean_project`, `clone_metadata_object`, `create_git_branch`, `create_infobase`, `create_launch_config`, `create_metadata`, `create_project`, `debug_pause`, `debug_status`, `delete_infobase`, `delete_launch_config`, `delete_metadata`, `delete_project`, `enable_toolset`, `evaluate_expression`, `export_common_picture`, `get_applications`, `get_content_assist`, `get_event_log`, `get_mcp_history`, `get_outgoing_structures`, `get_server_status`, `get_variables`, `git`, `infobase_extensions`, `infobase_sessions`, `launch`, `list_breakpoints`, `list_configurations`, `list_toolsets`, `modify_metadata`, `project_checks`, `remove_breakpoint`, `resume`, `resync_to_disk`, `set_branch_infobase`, `set_breakpoint`, `set_error_breakpoint`, `set_infobase_credentials`, `set_variable`, `sort_metadata`, `start_profiling`, `step`, `stop_profiling`, `switch_git_branch`, `update_database`, `validate_form_model`, `validate_query`, `wait_for_break`.
- **Per-call formats**: `list_projects` defaults to Markdown and returns JSON for `format=json`; `get_project_errors` returns JSON for nonempty `objectFqns` and Markdown otherwise; `dcs` defaults to Markdown and returns a JSON page envelope for bare-schema `get` with `format=xml`. Inspect the result mode before assuming one uniform envelope.
- **Text tools** (plain text): `get_edt_version`, `get_form_layout_snapshot`.
- **Image tools**: `get_form_screenshot` and `get_template_screenshot` return rendered PNG images as EmbeddedResources with an `image/*` `mimeType`.

## Saved-module query validation

`validate_query` requires `projectName` and exactly one of `queryText` or `modulePath`.
Use `modulePath="CommonModules/MyModule/Module.bsl"` to scan saved BSL, with optional
case-insensitive `method="BuildQuery"` to restrict the scan. It extracts standalone
SELECT/ВЫБРАТЬ literals, decodes continuations and escaped quotes, and returns per-literal
verdicts in `queries` with diagnostics mapped to module lines. `skipped` reports dynamic
concatenations and keyword-leading strings without query structure; an empty scan suggests
`queryText`. Runtime substitutions are not reconstructed. `dcsMode` applies in either mode.

## DCS lifecycle and values

Creating or changing an empty template to `templateType=DataCompositionSchema` exports `Template.dcs`
in that call; an empty schema supports `dcs get format="xml"`. `create_metadata` with
`setAsDefault=true` on a Report / ExternalReport template also makes it the report's
`mainDataCompositionSchema` (DataCompositionSchema implied). Query data sets with
`autoFillFields=true` fill fields and missing parameters synchronously before export.
Explicit fields default auto-fill to false; otherwise it defaults to true.
LocalString output values accept strings, `{kind:"string",value:...}`, and language maps;
field and conditional `appearance` accept bare values or `{kind,value}`. Discover
`role.periodType` and restriction dictionaries through `dcs action="options", type="field"`.
Writes wait for export off the UI thread; `Export scheduled` is submission and
`Export drained: true` confirms queue completion. A post-commit export failure carries
`mutationCommitted`; check project state, resync and read back before retrying.

## DCS XML transfers

`dcs` with `action="get"`, `type="schema"`, and `format="xml"` returns
`{success,totalChars,offset,hasMore,nextOffset?,hash,xml}`. Begin at `offset=0`, append
`xml`, and while `hasMore` is true repeat with the numeric `nextOffset`; require the
20-character `hash` to stay the same on every page so a mid-transfer schema change is
detected. The server measures each escaped JSON envelope and shrinks its XML chunk before
returning if necessary, so the 100000-character content guard never truncates XML. Each
page request re-serializes the whole schema, making transfer cost O(pages × schema size);
raise `limit` when the client tolerates larger results to reduce the page count. Concatenate
all chunks, then send the WHOLE document in ONE `replace` as `body.xml`, or in parts as
`body.xmlChunk` with `offset`, `totalChars` and `uploadId` (nothing is written until the part
that reaches `totalChars`). See [`dcs`](tools/dcs.md) for the paging loop, the chunked upload
and the replacement example.

## Error contract

Whatever a tool's normal output format above, it reports a **failure** the same way: a JSON payload `{"success": false, "error": "<message>"}` that the server delivers as a structured tool error (`isError: true`) regardless of the declared response type. The `error` field is always present (a `null` exception message is coalesced to `Unknown error`) and the message carries no redundant `Error:` prefix. A client detects failure via `isError` / `success: false` and reads `error` for the reason — no markdown parsing required. Success and purely *informational* results (for example "No references found", or a not-found accompanied by a list of valid alternatives) stay in the tool's natural format.

`tools/call` with absent, null or blank `params.name` returns JSON-RPC `-32602` with a
`tools/list` hint; a nonblank unknown name returns `-32601`.

Create/modify/delete echoes, previews, batch rows, follow-up hints and write-guard
refusals use canonical English structural FQN tokens, preserving programmatic Names.
Form-structure headings likewise use `Catalog.X.Form.Y`. Delete scope hashes include
the canonical address, so equivalent Russian/English spellings share a token.

`export_configuration_to_xml` reports success only when `Configuration.xml` is created
or proven rewritten by this call (newer mtime or changed size/SHA-256). A stale root
alone does not prove export; an unproven rewrite suggests a fresh output directory.

`get_mcp_history` classifies status and error statistics from the full delivered response
before capping stored bodies at 20000 characters. A capped body may be invalid JSON;
use the recorded status. Background completion preserves that status and updates the
same interrupted-call row without changing its delivered response or statistics.

## Tool annotations

Every tool in the `tools/list` response carries an `annotations` object with the standard MCP behavioral hints, so a client can reason about a tool's effect before calling it. The hints are derived centrally from the tool name (a tool may override them):

| Hint | Meaning | When set |
|------|---------|----------|
| `readOnlyHint` | The tool does not modify the workspace | `true` for `get_*` / `list_*` / `read_*` / `search_*` / `find_*` / `validate_*` and for `go_to_definition`, `debug_status`, `wait_for_break`; `false` for write and destructive tools |
| `idempotentHint` | Repeating the call has no additional effect | `true` for the read-only tools above |
| `destructiveHint` | The tool may perform a destructive or irreversible update | `true` for tools such as `delete_metadata`, `update_database`, `infobase_sessions`, `rename_metadata_object`, `delete_project`, and `cancel_job` |
| `openWorldHint` | The tool interacts with an external/open world | `false` for every tool but `git`, which sets it `true`: `push` / `pull` / `fetch` reach a remote |

Only hints that apply are emitted; unset hints are omitted from the JSON. Tools that write but are not destructive (for example `write_module_source`, `create_metadata`) carry `readOnlyHint: false` and `destructiveHint: false`.

</details>

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | POST | MCP JSON-RPC (initialize, tools/list, tools/call) |
| `/mcp` | GET | Server info |
| `/health` | GET | Health check |

