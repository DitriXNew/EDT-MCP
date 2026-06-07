# delete_metadata

Delete a metadata node (object or member, including a FORM member - item / attribute / command / handler) addressed by a 1C full-name FQN, cascading the cleanup of all references in BSL code, forms and other metadata. Two-phase: call without confirm to preview what would be removed, then confirm=true to apply (deletion is hard to reverse). Full parameters and examples: call get_tool_guide('delete_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | Full-name FQN of the node to delete (required), e.g. 'Catalog.Products' or 'Document.SalesOrder.Attribute.Amount' (type / kind tokens may be English or Russian; the Name parts are the programmatic Name, not the synonym). |
| confirm | — | boolean | true = execute the deletion; default false = preview only. |

## Guide
Deletes one metadata node (a top-level object or one of its members) addressed by a 1C full-name FQN, and cascades the cleanup to every reference across the configuration: BSL code, forms and other metadata. Backed by EDT's md-refactoring service, so the same reference cleanup EDT computes for the IDE delete is what gets applied. The target's identity is its programmatic Name (not its synonym). Replaces the former delete_metadata_object.

## Think twice
This is a CASCADING, hard-to-reverse deletion: a wrong target can mass-edit BSL, forms and metadata across the whole configuration. Always preview first, run it on a configuration you can revert (version control), and do not execute without an explicit request. After execute, verify with get_project_errors.

## When to use
Use to remove an existing node and have all references cleaned automatically. To rename instead use rename_metadata_object; to create use create_metadata.

## Two-phase workflow
1. Preview (confirm omitted / false): returns the refactoring title, the refactoring items, and the affected references (referencingObject, reference feature, targetObject FQN) plus a count. Nothing is modified.
2. Execute (confirm=true): performs the delete refactoring. Returns action='executed'.

## Parameters
- `projectName` (required) - EDT project name.
- `fqn` (required) - the delete target. Top object: 'Type.Name' (e.g. 'Catalog.Products' deletes the whole catalog). Member: 'Type.Name.Kind.Name', including a NESTED member (e.g. 'Catalog.X.TabularSection.T.Attribute.A'). Any node create_metadata can address - an attribute / tabular section / dimension / resource / enum value / command / template / recalculation / type-specific child - can be deleted by its FQN.
- `confirm` (optional, default false) - false previews, true applies.

## Form members
A FORM member is addressed like its create/modify FQN: `Catalog.X.Form.F.<Kind>.Name` (or `CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / Decoration / Table, or an event Handler (`...Form.F.Handler.Event`, item-level `...Field.Item.Handler.Event`). The same two-phase preview/confirm applies; deleting a Group / Table cascades its contained subtree. Unlike the mdclass path there is NO reference cascade for forms: a cross-reference to the removed member (a field's dataPath, a button's command) is NOT rewritten - re-check with get_metadata_details afterwards. The change persists to the form's `Form.form` on disk. For a form member the preview's `items` list the removed element + its contained descendants as `{name, type}` and `affectedReferencesCount` is 0 (no cascade is computed for forms).

## Bilingual (ru/en)
Resolves by the programmatic Name; only the leading TYPE token and the child KIND tokens are dialect-aware (English or Russian). The synonym is never used to locate the target.

## Examples
- Preview: `{projectName: 'P', fqn: 'Catalog.Products'}`
- Execute: `{projectName: 'P', fqn: 'Catalog.Products', confirm: true}`
- Delete one attribute: `{projectName: 'P', fqn: 'Document.SalesOrder.Attribute.Amount', confirm: true}`

## Gotchas
- A malformed nested FQN with an odd trailing token (e.g. 'Catalog.Products.Attribute') is rejected as not found, so a nested delete never silently falls back to the parent.
- Deletion targets the programmatic Name; passing a synonym will not resolve.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
