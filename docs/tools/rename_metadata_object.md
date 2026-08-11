# rename_metadata_object

Rename a metadata object, one of its members, or a managed-form element (attribute / command / field / button / group / decoration / table / attribute column), cascading the change across the references EDT resolves for it in BSL code, forms, and other metadata. Use the two-phase workflow: call without confirm for an indexed preview of every change point, review it, then call again with confirm=true to apply. Full parameters and examples: call get_tool_guide('rename_metadata_object').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name. |
| objectFqn | yes | string | FQN of the rename target: an object ('Catalog.Products'), a member ('Document.SalesOrder.Attribute.Amount'), or a managed-form element ('Catalog.Products.Form.ItemForm.Field.Price', 'CommonForm.Settings.Group.Main', 'Catalog.Products.Form.ItemForm.Attribute.Rows.Column.Price'). Russian type and kind tokens are also accepted. |
| newName | yes | string | New programmatic Name for the rename target (the object, member or form element addressed by objectFqn). |
| confirm | — | boolean | true = apply the rename; default false = preview only. |
| disableIndices | — | string | Comma-separated preview '#' indices of OPTIONAL change points to skip, e.g. '2,3,5'. |
| maxResults | — | integer | Max change points shown in the preview (default 20; 0 = no limit). |
| timeout | — | integer | How long to wait for the cascade itself, in seconds (default 420, clamped to 60..3600). On expiry the call fails with a timeout error naming the stage it reached instead of waiting forever; EDT may still finish the rename afterwards, so verify the model. Does not cover the pre-flight index drain (a separate 60s bound). |

## Guide
Renames one metadata object, one of its child members, or one managed-form element, and cascades the rename to every reference EDT resolves for the target: BSL code, forms, and other metadata. It is backed by LTK refactoring, so the same change set EDT computes for the IDE rename is what gets applied. The object's identity is its programmatic Name (not its synonym), and only newName is renamed (for a FORM element EDT additionally refreshes the derived title - see below).

## Think twice
This is a CASCADING, hard-to-reverse refactoring: a wrong target or newName can mass-edit BSL, forms and metadata across the whole configuration. Always preview first, run it on a configuration you can revert (version control), and do not execute without an explicit request. After execute, verify with get_project_errors.

## When to use
Use to rename an existing object, member or form element and have all callers updated automatically. To create one use create_metadata; to delete one use delete_metadata.

## Two-phase workflow
1. Preview (confirm omitted / false, the default): returns a Markdown report with a change-points table. Each row has a '#' index, the file/location, a description, whether the change is Optional, and whether it is Enabled by default. Nothing is modified.
2. Execute (confirm=true): re-walks the SAME change tree with the SAME '#' numbering and applies the rename, skipping any indices you pass in disableIndices.

## Form elements
A managed-form element is addressed exactly as create_metadata / modify_metadata / delete_metadata address it: `Catalog.X.Form.F.<Kind>.Name` or `CommonForm.F.<Kind>.Name`, Kind = Attribute / Command / Field / Button / Group / Decoration / Table, plus a COLUMN of a collection-typed form attribute as `...Form.F.Attribute.AttrName.Column.ColName`. Such an FQN is dispatched to a dedicated branch BEFORE the mdclass path and renamed through EDT's OWN form refactoring - the same one the designer's rename uses - so the preview table, the '#' indices, disableIndices, the confirm gate and the timeout all behave identically.

What the cascade covers: the form's own references to the element and the `Items.<Name>` / `Элементы.<Имя>` occurrences in the form module are rewritten, and EDT additionally renames the designer-owned children whose names it derives from the owner (an extended tooltip, a context menu, an auto command bar) so they keep following the new name. It also refreshes the element's DERIVED TITLE - renaming an untitled `Group.TitleProbeGroup` to `TitleProbeGroupRenamed` leaves it titled `Title probe group renamed` - so a form rename is not a name-only edit the way an mdclass rename is; set an explicit title with modify_metadata if you need one that does not follow the name. What it deliberately does NOT cover: an element name inside a STRING LITERAL is left alone - a literal is not a reference the refactoring can prove - so review string uses yourself afterwards. The scope is the form: unlike an mdclass rename this is not a configuration-wide cascade.

Shapes this branch refuses, each with its own error:
- an event handler (`...Form.F.Handler.OnOpen`, `...Form.F.Command.X.Handler.Action`) - the leaf of a handler FQN is an EVENT name and the platform owns it; to point the handler at a different BSL procedure use modify_metadata with the 'procedure' property, or rename the procedure in the form module;
- a bare `...Form.F.Column.C` - a column belongs to a collection form attribute, so address it on its owner (`...Form.F.Attribute.AttrName.Column.C`);
- a designer-owned AutoCommandBar / ContextMenu / ExtendedTooltip addressed DIRECTLY - the platform derives its name from the element that owns it and refuses a direct rename; rename the OWNING element instead and its auto children follow;
- a newName already taken by a sibling - form attributes and form items are separate namespaces, so the clash is looked for in the addressed one.

The preview is rendered by the same code minus the two mdclass-only inputs: the supplemental full-text exact-match scan does not run for a form target, so `debugExactMatches` is 0 and every listed change point comes from EDT's own form refactoring. That is not a cold index - there is nothing missing.

A form OBJECT is not a rename target: `Catalog.X.Form.ItemForm` resolves to nothing here. (`CommonForm.Name` is a top-level metadata object and renames through the ordinary top-object path.)

## Parameter details
- projectName (required): EDT project name.
- objectFqn (required): FQN of the rename target. Top object: 'Type.Name' (e.g. 'Catalog.Products'). Child member: 'Type.Name.ChildType.ChildName' (e.g. 'Document.SalesOrder.Attribute.Amount') - supported child types: Attribute, TabularSection, Dimension, Resource. Managed-form element: 'Type.Object.Form.FormName.<Kind>.Name' (e.g. 'Catalog.Products.Form.ItemForm.Field.Price') or 'CommonForm.FormName.<Kind>.Name', Kind = Attribute / Command / Field / Button / Group / Decoration / Table, plus a COLUMN of a collection-typed form attribute as '...Form.F.Attribute.AttrName.Column.ColName'. Type and kind tokens may be English or Russian, singular or plural.
- newName (required): the new programmatic Name. It must be a legal 1C identifier - starting with a letter or underscore, then letters, digits and underscores only (Cyrillic letters count as letters); the check is the platform's own predicate, it applies to every target alike, and it runs among the argument guards - before the project is drained and before anything is resolved, so a malformed name never costs you a wait. Pass the NAME, not an FQN: a dotted value like `Bad.Name` is refused, because the write would otherwise succeed and leave an element no FQN can address. What it does NOT judge is length: an over-long name is a validation marker for the platform to raise, not something this tool refuses.
- confirm (optional, default false): false previews, true applies.
- disableIndices (optional): comma-separated '#' indices from the preview to skip, e.g. '2,3,5'. Only OPTIONAL change points can be disabled; required ones are always applied. One '#' index may span several context rows in the table - skipping it skips them all.
- maxResults (optional, default 20): caps how many change points the preview lists; 0 = no limit. This only trims the preview display, never what execute actually changes.
- timeout (optional, default 420): how long to wait for the cascade itself, in seconds, clamped to 60..3600. It bounds the rename only - the pre-flight index drain that runs before it has its own separate 60s bound, so the worst-case call is a minute longer than this. Raise it for a very large configuration; the default can also be changed in Preferences > MCP Server > Tools > `rename_metadata_object`.

## Timeout, and what the model is left in
The cascade runs on EDT's UI thread and cannot be preempted, so `timeout` bounds only how long the CALL waits - it does not stop the rename. When it expires the call fails with an error that names the stage the rename had reached, because that stage is what decides your next move:
- the rename never STARTED (the deadline elapsed while it was still queued and cancelling it kept it from starting) - the model is untouched and nothing needs checking; EDT's job scheduler is saturated, so retry when it is less busy or raise `timeout`;
- a PREVIEW (confirm not set) can never apply anything, so its timeout means only that the change points were not computed in time - nothing was or will be renamed;
- on an execute, still building the refactoring or still at the destructive-operation consent gate - nothing was rewritten yet, but the rename is not cancelled and may still apply;
- past the consent gate, in the apply phase - the configuration may be PARTIALLY renamed; inspect it with `get_project_errors` plus `get_metadata_details` on the target (on its OWNER for a member, on its FORM for a form element - `get_metadata_objects` lists top-level collections and can show none of those), then reload with `clean_project` (or revert in version control) before renaming again;
- apply phase finished - the rename is in the model apart from any change point that failed or was skipped, and it is the report listing those that was lost; confirm rather than repeat it.

In every case verify the model before retrying: a retry against an already-renamed target fails with "Object not found" ("Form element not found" for a form element). The default is set above the worst measured legitimate wait (301s, EDT waiting out its own derived-data timeout inside the refactoring's batch session), so lowering it trades a hang for the riskier failure of reporting a rename that then lands anyway.

## Bilingual notes (ru/en)
- objectFqn resolves by the object's programmatic Name; in the FQN only the leading TYPE token may be bilingual (e.g. 'Catalog' or the Russian 'Справочник'). In a FORM FQN the form token and the element KIND token are bilingual too ('Form'/'Форма', 'Field'/'Поле', 'Group'/'Группа', ..., singular or plural). The synonym is never used to locate the target.
- This renames the Name only; it does not touch synonyms. Synonyms stay keyed by language code and are unaffected by the rename. A form ELEMENT is the one exception, and it is EDT's behaviour, not ours: EDT's form rename also refreshes the element's derived `title` (measured - renaming an untitled `Group.TitleProbeGroup` to `TitleProbeGroupRenamed` writes the title `Title probe group renamed`). Set an explicit title with modify_metadata if you need one that does not follow the name.

## Examples
- Preview a top-object rename: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}
- Execute it: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods', confirm: true}
- Rename an attribute, skipping two optional change points: {projectName: 'MyProject', objectFqn: 'Document.SalesOrder.Attribute.Amount', newName: 'Total', confirm: true, disableIndices: '3,4'}
- Rename a form field: {projectName: 'MyProject', objectFqn: 'Catalog.Products.Form.ItemForm.Field.Price', newName: 'Cost', confirm: true}
- Rename a form group (its auto children follow): {projectName: 'MyProject', objectFqn: 'CommonForm.Settings.Group.Main', newName: 'Primary', confirm: true}
- Russian type token: {projectName: 'MyProject', objectFqn: 'Справочник.Products', newName: 'Goods'}

## Gotchas
- A '#' index is a stable cross-call handle: the index you see in preview is the same one execute uses, so 'skip #N' disables exactly that change. Always read disableIndices from a fresh preview of the same rename.
- disableIndices is ignored for required (non-optional) change points; you cannot skip a change the refactoring deems mandatory.
- maxResults only narrows the preview list; it has no effect when confirm=true.
- An unsupported child type or a malformed FQN is rejected with guidance on the accepted 'Type.Name' / 'Type.Name.ChildType.ChildName' / form-element shapes.
- For a form element the rename is scoped to that form (its model plus its module), and a string literal naming the element is never rewritten - grep for the old name if the form's code builds item names as text.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
