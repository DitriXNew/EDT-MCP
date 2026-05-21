# Token economy when working with EDT-MCP

> Every call to the MCP server costs tokens. On SSL (Standard Subsystems Library) configurations, a single `read_module_source` without a range can cost tens of thousands of tokens. These rules save 5-50x depending on the task.

## Principles

1. **Summary first, then details.** "Cheap -> expensive" hierarchy (always start from the left):
   - `get_problem_summary` -> `get_project_errors` (with filters)
   - `get_module_structure` -> `read_method_source` -> `read_module_source`
   - `search_in_code outputMode=count` -> `outputMode=files` -> `outputMode=full`
   - `get_metadata_objects` -> `get_metadata_details`

2. **Read the method, not the module.** Use `read_method_source` instead of `read_module_source`. For SSL modules (6000+ lines) this saves tens of times the tokens. Use `read_module_source` only when the method depends on a `#Region`/`#Область` or on module-level variables.

3. **Use the filters the tool provides.** Do not request everything intending to "filter on your end" — that is the worst token antipattern. See the table below: almost every tool has `nameFilter` / `metadataType` / `severity` / `objects` / `contains` / `checkId` / `fileMask`.

4. **Do not repeat requests.** If the answer is already in the session context — do not call again. If you are worried about "forgetting" — save the result to a temporary project file (`.cache/edt-mcp/...` or similar) and read from there. This is especially relevant for large maps: `get_module_structure`, `list_modules`, `get_metadata_objects`.

5. **Batch where supported.** `get_metadata_details` accepts the `objectFqns` array — build a batch, do not call one object at a time.

6. **Stop at "good enough".** If you already know the method name and need to read its body — call `read_method_source` directly, without a preliminary `get_module_structure`. Do not make "overview" calls without explicit need.

## Per-tool rules

### `get_problem_summary` (cheap)

- A single call returns counters per project and per severity. **Always first** before `get_project_errors`. You instantly see whether it is worth digging deeper.

### `get_project_errors` (expensive)

- `limit` — default 100, max 1000. **Do not raise without need.**
- `severity` — `ERRORS`, `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `TRIVIAL`. Usually the first three matter.
- `objects` — array of FQNs (`['Document.SalesOrder', 'Catalog.Products']`). Narrows by orders of magnitude.
- `checkId` — check code substring (e.g. `ql-temp-table-index`). Useful when you are after a specific class of errors.
- A fresh configuration without fixes can have thousands of errors — **do not pull everything**, filter.

### `search_in_code`

- The count parameter is called **`maxResults`** (not `limit`): default 100, max 500.
- `outputMode`: `count` (number only), `files` (files + counts), `full` (with context, default). Use in this order.
- `metadataType` is more precise than `fileMask`. If you only need common modules — `metadataType: commonModules`.
- `contextLines` — default 2, max 5. No reason to raise it without need.
- This is the **replacement for `Grep` over `.bsl` files**. Do not read a whole module for one line.

### `get_module_structure` (cheap)

- Returns only procedure/function signatures, regions, parameters — without bodies.
- Use **before** `read_module_source`/`read_method_source` for an unfamiliar module.
- `includeVariables: false` (default) — module variables are rarely needed for the map.
- `includeComments: false` (default) — comments also bloat the output.

### `read_method_source` (the preferred way to read BSL)

- Targeted read of a single method by name. If the method is missing — returns the **list of available** names (call again with the right one).
- The response includes `region` — you see which `#Region`/`#Область` it belongs to.

### `read_module_source` (expensive)

- Use **only** if: the method depends on `#Region`/`#Область`, you need to see module directives, or you need several methods together.
- Always pass `startLine`/`endLine` when you can estimate the range.
- The `maxLines` parameter is configurable in `Window -> Preferences -> MCP Server -> Tools` (default 500, max 50000). When the output is clamped the response contains `truncated: true` and `nextStartLine` — continue reading **only if you really need to**, do not auto-fetch the entire module.

### `list_modules`

- A large configuration has thousands of modules. Always filter.
- `metadataType` (e.g. `documents`) or `objectName` (e.g. `Products`) — narrows sharply.
- `nameFilter` — substring of the path.
- `limit` default 200.

### `get_metadata_objects`

- **Always** set `metadataType` (not `all`) if you know the area of interest.
- **Always** set `nameFilter` if you are searching by substring.
- `limit` default 100, max 1000.

### `get_metadata_details`

- Accepts the `objectFqns` array — build a **batch** (`['Catalog.X', 'Document.Y']`), do not call one by one.
- `full: false` (default) — overview. **Sufficient in 90% of cases.**
- `full: true` — only when all properties are needed (attributes, forms, tabular section fields, etc.). Bloats the response several times over.

### `find_references`

- Default 100, max 500. Raise it only for refactoring that requires full coverage.
- Supports **top-level objects only** (`Catalog.X`, `Document.Y`, `CommonModule.Z`). For nested objects it returns an error — go directly to `rename_metadata_object`/`delete_metadata_object`.

### `get_content_assist`

- `extendedDocumentation: true` — **only** when full description is truly needed. Otherwise it heavily bloats the response.
- `contains` — substring filter (comma-separated: `'Insert,Add'`). Trims the response to what is relevant.
- `offset` — pagination is supported. For "next batch" use `offset: 100, limit: 100`.
- `limit` default 100, max 1000. For most tasks 20-50 is enough.

### `get_form_layout_snapshot`

- `mode: compact` (default) — only visual elements with positive bounds and key display properties. **Use by default.**
- `mode: full` — all nodes and all non-containment properties. Tens of thousands of tokens on a complex form. Use **only** if compact does not give the needed detail.

## Caching to project files

If the same large slices are needed many times in a session (project map, list of modules, FQN catalog) — save the result **once** to a temporary markdown file inside the project and work with it:

- Good for: `get_module_structure` of large modules, `list_modules`, `get_metadata_objects` for the configuration, `find_references` for refactoring anchor objects.
- Not good for: `get_project_errors` (gets stale quickly after edits), debug results, `get_form_layout_snapshot` (depends on editor state).
- Put the file under a gitignored path (e.g. `.cache/edt-mcp/<name>.md`). Do not commit such snapshots.

## Antipatterns

- `read_module_source` without `startLine`/`endLine` for an SSL module — consumes the call's budget in one shot.
- `search_in_code outputMode=full` straight away, without estimating the scope via `count` — can return megabytes.
- `get_metadata_details full=true` for overview browsing — almost never necessary.
- `get_content_assist extendedDocumentation=true` for a routine method-name check.
- A `get_metadata_details` loop over single FQNs instead of one batch call.
- Re-issuing the same `get_module_structure` during a session instead of caching the result.
- `get_project_errors` without `severity` and `objects` on a configuration with thousands of problems.
- Using the client's `Read`/`Grep` on `.bsl` files instead of `read_method_source`/`search_in_code` (see `INDEX.md`, NEVER section).

## Pre-request checklist

- [ ] Do I already know the answer from the session context or from a cache file?
- [ ] Is there a "cheap" tool of the same class that gives 80% of the answer? (`*_summary`, `*_structure`, `outputMode: count`)
- [ ] Have I applied every available filter (`metadataType`, `nameFilter`, `severity`, `objects`, `contains`, `fileMask`)?
- [ ] Do I need the full object, or is a method / summary / line range enough?
- [ ] This is the second call with the same parameters this session — why?
