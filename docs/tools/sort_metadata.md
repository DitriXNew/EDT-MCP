# sort_metadata

Inspect current metadata order with mode=list, or reorder by Name, explicit order, or one sibling move. Review order-sensitive changes: register DIMENSIONS define indexes and query speed; enum values affect ORDER BY/comparisons; subsystem content affects command interface order. Web-service parameter order defines handler signatures. Other ordered collections can affect presentation or positional access. Autosort maintains governed lists; ask the user about manual order. Full parameters and examples: call get_tool_guide('sort_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | Configuration or extension project. |
| fqn | yes | string | Level: Configuration, Configuration.Catalogs, Catalog.X, or Catalog.X.Attributes. TYPE/KIND tokens accept Russian/English; Names are programmatic. |
| mode | — | string (one of: alphabetical, explicit, move, list) | Default alphabetical; list reads current order without writing. Explicit/move require a collection fqn. |
| recursive | — | boolean | Default false. Alphabetical only: also order owned descendants. |
| items | — | array | Explicit mode: sibling Names in desired order; unlisted members follow stably. |
| objectName | — | string | Move mode: one sibling's programmatic Name. |
| direction | — | string (one of: up, down) | Move mode: up/down, mutually exclusive with before/after. |
| positions | — | integer | Positive move distance; default 1. Clamped at collection ends. |
| before | — | string | Move mode: place objectName before this sibling Name. |
| after | — | string | Move mode: place objectName after this sibling Name. |

## Guide
Reads current metadata order with `mode:"list"`, or orders stored lists by programmatic Name and force-exports every changed file owner. This is a reversible model write, without a consent gate. Save the returned original positions or use version control to restore the previous order.

## Scope

| fqn | Default (`recursive:false`) | `recursive:true` |
|---|---|---|
| `Configuration` | Each ordered named metadata collection on the configuration, separately | Those lists and all owned metadata below their objects |
| `Configuration.Catalogs` (also `Configuration.CommonModules`, etc.) | Only that list in `Configuration.mdo` | Also each listed object's member collections and their descendants |
| `Catalog.X` | Each direct ordered named member/reference collection, separately | Also tabular sections' attributes and other owned descendants |
| `Catalog.X.TabularSections` | Only the tabular-section list | Also each section's attributes |
| `Catalog.X.TabularSection.Lines.Attributes` | Only the selected nested member list | Also owned descendants, if any |
| `Subsystem.Sales.Content` | Only the content reference list | Same list; referenced content targets are never traversed |

An object address selects its own collections, never its siblings. A member object address selects that member's collections; use its parent's collection address to move the member itself. `Configuration` is the root token, not `Configuration.<configurationName>`.

TYPE and KIND tokens accept English/Russian, singular/plural (for example `Справочник.Products.Реквизиты`). Collection feature names such as `attributes`, `forms`, `templates`, `content`, and `registerRecords` are also accepted, case-insensitively. Names are programmatic identifiers; synonyms never identify members. Empty lists succeed. Scalars, unordered, derived, transient, non-writable and non-metadata collections are refused. Form content, XDTO package content and predefined data are separate models outside this tool's named-metadata list scope.

Recursive traversal follows containment and owned top-object registrations (configuration objects, nested subsystems, external-data-source tables/cubes/dimension tables). It does not follow content targets, default roles, input-by-string references, or other arbitrary references. Those ordered reference lists themselves can still be sorted. Each owned object is visited once.

## Modes

- `list`: reads only the selected level's current model order, without sorting, writing, exporting, or checking vendor edit permission. Requires `recursive:false` and no items/move parameters. Use it before sorting and for read-back; `get_metadata_objects` alphabetizes its presentation and cannot report stored order. Configuration-root details/properties do not expose these lists.
- `alphabetical` (default): stable, case-insensitive Name order using the shared ё/е normalization. It changes order, never Names or synonyms. Equal normalized names retain their relative order; governed lists use EDT's autosort comparator instead.
- `explicit`: provide `items:["Third","First"]`. Listed siblings come first in that order; every unlisted member follows in its original relative order. `[]` leaves the order unchanged. Missing or repeated names are refused before any moves.
- `move`: provide `objectName` and exactly one of `direction:"up"|"down"`, `before:"Sibling"`, or `after:"Sibling"`. With direction, `positions` is a positive integer, default 1; moving past either end clamps to the first/last position. `positions` is invalid with before/after. A missing object/sibling or self anchor is refused.

Explicit/move require a collection FQN and `recursive:false`. They cannot ambiguously apply one list of Names across multiple object collections. Sibling name matching ignores case and preserves stored spelling. A heterogeneous reference list with repeated Names supports alphabetical only.

Active V8 code style autosort maintains governed lists with its native comparator, refuses explicit/move before writing, and is reported by `mode:list`. An already ordered list returns `alreadySorted:true` without a write transaction. For manual order, ask the user through the response’s verified EDT UI path; never change that setting yourself.

## Order-sensitive collections

Register DIMENSIONS order defines indexes and affects query speed. Enum value order affects ORDER BY and comparisons. Subsystem content affects command interface order. Web-service parameter order defines handler signatures and positional argument types. Other ordered lists can affect presentation or positional consumers, including register-record lists, method parameters, and service routing declarations. Review dependent code and interfaces after reordering. These lists are allowed, never blocked for sensitivity.

Every reordered collection carries an explicit `warning`. `sensitiveCollections` lists every changed collection and its warning, including recursive descendants, without truncation. Required warnings name dimensions/indexes, enum comparisons, and subsystem command interfaces explicitly. An unchanged collection generates no warning.

## Extensions and persistence

The selected project's own Configuration and collections are used. An extension's lists include its adopted and native objects; both may be moved within those lists. This does not reorder the base project's lists and does not adopt absent base members. Vendor-locked owners are refused through the shared support guard. All owner guards and ordering validation run before the first list move; a failed transaction rolls back the whole operation.

Moves use BM transactions on the UI thread and preserve object identity. A changed configuration list exports Configuration; member lists export their top owner (including separately stored nested subsystems or external-data-source tables). The shared export barrier waits before answering. As with other metadata writers, export-queue completion is not an independent byte-content check: read the `.mdo` and model back for acceptance. An export refusal after a commit carries `mutationCommitted`; do not blindly retry a relative move.

## Response

`mode:"list"` returns `{success:true, mode:"list", fqn, collections:[{fqn,order,autosort}]}` in stored EList order. It omits moved/alreadySorted/persisted: an observed unsorted list was not sorted or exported.

Sorting `collections` contains every list at the requested level, with its `fqn`, observed `autosort` policy, complete resulting `order`, `moved:[{name,from,to}]`, and `alreadySorted`. Positions are 1-based and include every member whose position changed, including displaced siblings. Root `alreadySorted` is true only if nothing changed anywhere in the selected scope.

Recursive `subCollections` contains the first 100 summaries (`fqn`, `size`, `movedCount`, `alreadySorted`, optional `warning`); `subCollectionCount` gives the full count and `truncated` says whether the summary cap was exceeded. The cap affects output only: all descendants are processed. Address a reported sub-collection directly to retrieve its full resulting order; use `mode:"list"` to observe it without mutation. Changed-collection warnings are never hidden by the summary cap. `autosortCollections` lists the first 100 maintained collections, including unchanged lists; `autosortCollectionCount` gives the total. Every governed row carries the same `autosort` block: `plugin`, `governs` (canonical collection), `setting` (human scope), `order` (`ascending|descending, natural|plain`), `effect`, and `userAction`. The plain message repeats the effect and instruction to ask the user through `Project > Properties > V8 > Metadata objects sort > Auto sort`; it forbids agent setting changes. Ungoverned rows omit the block.

## Examples

```json
{"projectName":"TestConfiguration","fqn":"Configuration.CommonModules","mode":"list"}
{"projectName":"TestConfiguration","fqn":"Configuration.CommonModules"}
{"projectName":"TestConfiguration","fqn":"Catalog.Catalog","recursive":true}
{"projectName":"TestConfiguration","fqn":"Catalog.Catalog.Attributes","mode":"explicit","items":["Code","Description"]}
{"projectName":"TestConfiguration","fqn":"Configuration.Catalogs","mode":"move","objectName":"Catalog","direction":"down","positions":3}
{"projectName":"TestConfiguration","fqn":"Configuration.Catalogs","mode":"move","objectName":"Catalog","before":"OtherCatalog"}
```

Use existing Names in the last two examples. To undo, pass the saved full original order with explicit mode for each changed collection, or restore the exported files with version control and refresh EDT's model. `create_metadata.insertPosition` handles placement while creating a new metadata object/member.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
