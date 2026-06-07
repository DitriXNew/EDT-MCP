# rename_metadata_object

Rename a metadata object or attribute, cascading the change across all references in BSL code, forms, and other metadata. Use the two-phase workflow: call without confirm for an indexed preview of every change point, review it, then call again with confirm=true to apply. Full parameters and examples: call get_tool_guide('rename_metadata_object').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name. |
| objectFqn | yes | string | FQN of the object to rename, e.g. 'Catalog.Products' or 'Document.SalesOrder.Attribute.Amount' (Russian type names also accepted). |
| newName | yes | string | New programmatic Name for the object. |
| confirm | — | boolean | true = apply the rename; default false = preview only. |
| disableIndices | — | string | Comma-separated preview '#' indices of OPTIONAL change points to skip, e.g. '2,3,5'. |
| maxResults | — | integer | Max change points shown in the preview (default 20; 0 = no limit). |

## Guide
Renames one metadata object or one of its child members and cascades the rename to every reference across the configuration: BSL code, forms, and other metadata. It is backed by LTK refactoring, so the same change set EDT computes for the IDE rename is what gets applied. The object's identity is its programmatic Name (not its synonym), and only newName is renamed.

## Think twice
This is a CASCADING, hard-to-reverse refactoring: a wrong target or newName can mass-edit BSL, forms and metadata across the whole configuration. Always preview first, run it on a configuration you can revert (version control), and do not execute without an explicit request. After execute, verify with get_project_errors.

## When to use
Use to rename an existing object or member and have all callers updated automatically. To create an object or member use create_metadata; to delete use delete_metadata.

## Two-phase workflow
1. Preview (confirm omitted / false, the default): returns a Markdown report with a change-points table. Each row has a '#' index, the file/location, a description, whether the change is Optional, and whether it is Enabled by default. Nothing is modified.
2. Execute (confirm=true): re-walks the SAME change tree with the SAME '#' numbering and applies the rename, skipping any indices you pass in disableIndices.

## Parameters
- projectName (required): EDT project name.
- objectFqn (required): FQN of the rename target. Top object: 'Type.Name' (e.g. 'Catalog.Products'). Child member: 'Type.Name.ChildType.ChildName' (e.g. 'Document.SalesOrder.Attribute.Amount'). Supported child types: Attribute, TabularSection, Dimension, Resource.
- newName (required): the new programmatic Name. Only this identifier changes.
- confirm (optional, default false): false previews, true applies.
- disableIndices (optional): comma-separated '#' indices from the preview to skip, e.g. '2,3,5'. Only OPTIONAL change points can be disabled; required ones are always applied. One '#' index may span several context rows in the table - skipping it skips them all.
- maxResults (optional, default 20): caps how many change points the preview lists; 0 = no limit. This only trims the preview display, never what execute actually changes.

## Bilingual notes (ru/en)
- objectFqn resolves by the object's programmatic Name; in the FQN only the leading TYPE token may be bilingual (e.g. 'Catalog' or the Russian 'Справочник'). The synonym is never used to locate the target.
- This renames the Name only; it does not touch synonyms. Synonyms stay keyed by language code and are unaffected by the rename.

## Examples
- Preview a top-object rename: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}
- Execute it: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods', confirm: true}
- Rename an attribute, skipping two optional change points: {projectName: 'MyProject', objectFqn: 'Document.SalesOrder.Attribute.Amount', newName: 'Total', confirm: true, disableIndices: '3,4'}
- Russian type token: {projectName: 'MyProject', objectFqn: 'Справочник.Products', newName: 'Goods'}

## Gotchas
- A '#' index is a stable cross-call handle: the index you see in preview is the same one execute uses, so 'skip #N' disables exactly that change. Always read disableIndices from a fresh preview of the same rename.
- disableIndices is ignored for required (non-optional) change points; you cannot skip a change the refactoring deems mandatory.
- maxResults only narrows the preview list; it has no effect when confirm=true.
- An unsupported child type or a malformed FQN is rejected with guidance on the accepted 'Type.Name' / 'Type.Name.ChildType.ChildName' shapes.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
