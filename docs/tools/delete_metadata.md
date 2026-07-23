# delete_metadata

Delete a metadata node (object or member, including a FORM object 'Type.Object.Form.Name', a FORM member - item / attribute / command / handler - or an XDTO package member 'XDTOPackage.<Package>.ObjectType.<Name>' / '...Property.<Name>' / '...ObjectType.<Type>.Property.<Name>') addressed by a 1C full-name FQN, cascading the cleanup of all references in BSL code, forms and other metadata. Two-phase: call without confirm to preview what would be removed, then confirm=true to apply (deletion is hard to reverse). If the node is still referenced by metadata the refactoring cannot auto-clean, a confirm=true delete is BLOCKED and the referencing objects are listed; pass force=true to delete anyway (those references are left dangling). Full parameters and examples: call get_tool_guide('delete_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | Full-name FQN of the node to delete (required), e.g. 'Catalog.Products' or 'Document.SalesOrder.Attribute.Amount' (type / kind tokens may be English or Russian; the Name parts are the programmatic Name, not the synonym). |
| confirm | — | boolean | true = execute the deletion; default false = preview only. |
| force | — | boolean | true = delete even when the node is still referenced by other metadata that the refactoring cannot auto-clean (those incoming references are left dangling). Default false = on confirm=true the deletion is BLOCKED and the referencing objects are listed (independent of 'confirm', which is the preview gate). |

## Guide
Deletes one metadata node (a top-level object or one of its members) addressed by a 1C full-name FQN, and cascades the cleanup to every reference across the configuration: BSL code, forms and other metadata. Backed by EDT's md-refactoring service, so the same reference cleanup EDT computes for the IDE delete is what gets applied. The target's identity is its programmatic Name (not its synonym). Replaces the former delete_metadata_object.

## Think twice
This is a CASCADING, hard-to-reverse deletion: a wrong target can mass-edit BSL, forms and metadata across the whole configuration. Always preview first, run it on a configuration you can revert (version control), and do not execute without an explicit request. After execute, verify with get_project_errors.

## When to use
Use to remove an existing node and have all references cleaned automatically. To rename instead use rename_metadata_object; to create use create_metadata.

## Two-phase workflow
1. Preview (confirm omitted / false): returns the refactoring title, the refactoring items, and - when the node is still referenced by metadata the refactoring CANNOT auto-clean - marks `blocking=true` and lists those `blockingReferences` (referencingObject, reference feature, targetObject FQN) plus `blockingReferencesCount`; a confirm=true delete would be refused unless force=true. Nothing is modified.
2. Execute (confirm=true): performs the delete refactoring. If blocking references remain and force is not set, the delete is REFUSED with action='blocked' and the blocking referencers listed (nothing is changed). Otherwise returns action='executed'.

## Reference blocking and force
`confirm` and `force` are independent. `confirm` is the preview gate (it decides preview vs execute). `force` is the reference override: on a confirm=true call, if EDT's delete refactoring reports incoming references it cannot auto-clean (e.g. a Catalog still used as another object's attribute Type), the delete is BLOCKED (action='blocked', success=false) and the referencing objects are listed - exactly as the EDT/Configurator UI does. Pass `force=true` to delete anyway: the node is removed and those incoming references are left DANGLING (the response then carries forced=true and the danglers under blockingReferences). Prefer removing the references first; force is a last resort. References the refactoring CAN auto-clean (BSL usages, form bindings) never block - they are cleaned automatically. Every response that carries `blockingReferences` / `blockingReferencesCount` also duplicates them as `affectedReferences` / `affectedReferencesCount` - deprecated legacy aliases kept for one release for wire compatibility; read the `blocking*` keys.

## Parameter details
- `projectName` (required) - EDT project name.
- `fqn` (required) - the delete target. Top object: 'Type.Name' (e.g. 'Catalog.Products' deletes the whole catalog). Member: 'Type.Name.Kind.Name', including a NESTED member (e.g. 'Catalog.X.TabularSection.T.Attribute.A'). Any node create_metadata can address - an attribute / tabular section / dimension / resource / enum value / command / template / recalculation / type-specific child - can be deleted by its FQN.
- `confirm` (optional, default false) - false previews, true applies.
- `force` (optional, default false) - on confirm=true, delete even when blocking incoming references remain (they are left dangling). Does nothing in preview.

## Form object
An owned form OBJECT is addressed by the SAME 4-part FQN create_metadata uses to make it: `Catalog.X.Form.FormName` (the form token is bilingual - `Form` / `Forms` / `Форма` / `Формы`). Deleting it removes the form from its owner's `forms` collection AND its content `Form.form`, and clears any default-form setting on the owner that pointed at it (`defaultObjectForm` / `defaultListForm` / ...), so no dangling reference is left. The same two-phase preview/confirm applies (preview lists the form as a single `{name, type}` item, `blockingReferencesCount` is 0). `force` is ignored (an owned form never blocks). The change persists to the owner `.mdo` on disk. A `CommonForm.Name` is NOT an owned form - it is a real top object, deleted through the normal cascading refactoring path above.

## Form members
A FORM member is addressed like its create/modify FQN: `Catalog.X.Form.F.<Kind>.Name` (or `CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / Decoration / Table, or an event Handler (`...Form.F.Handler.Event`, item-level `...Field.Item.Handler.Event`). The same two-phase preview/confirm applies; deleting a Group / Table cascades its contained subtree. Unlike the mdclass path there is NO reference cascade for forms: a cross-reference to the removed member (a field's dataPath, a button's command) is NOT rewritten - re-check with get_metadata_details afterwards. The change persists to the form's `Form.form` on disk. For a form member the preview's `items` list the removed element + its contained descendants as `{name, type}` and `blockingReferencesCount` is 0 (no cascade is computed for forms). `force` has no effect on form-member deletes: form members never block (there is no reference cascade to refuse), so the flag is simply ignored.

## Bilingual (ru/en)
Resolves by the programmatic Name; only the leading TYPE token and the child KIND tokens are dialect-aware (English or Russian). The synonym is never used to locate the target.

## Examples
- Preview: `{projectName: 'P', fqn: 'Catalog.Products'}`
- Execute: `{projectName: 'P', fqn: 'Catalog.Products', confirm: true}`
- Delete one attribute: `{projectName: 'P', fqn: 'Document.SalesOrder.Attribute.Amount', confirm: true}`
- Force-delete a still-referenced object (leaves danglers): `{projectName: 'P', fqn: 'Catalog.Products', confirm: true, force: true}`

## Gotchas
- A malformed nested FQN with an odd trailing token (e.g. 'Catalog.Products.Attribute') is rejected as not found, so a nested delete never silently falls back to the parent.
- Deletion targets the programmatic Name; passing a synonym will not resolve.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
