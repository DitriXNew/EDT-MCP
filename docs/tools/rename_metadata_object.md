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
| timeout | — | integer | How long to wait for the cascade itself, in seconds (default 420, clamped to 60..3600). On expiry the call fails with a timeout error naming the stage it reached instead of waiting forever; EDT may still finish the rename afterwards, so verify the model. Does not cover the pre-flight index drain (a separate 60s bound). |

## Guide
Renames one metadata object or one of its child members and cascades the rename to every reference across the configuration: BSL code, forms, and other metadata. It is backed by LTK refactoring, so the same change set EDT computes for the IDE rename is what gets applied. The object's identity is its programmatic Name (not its synonym), and only newName is renamed.

## Think twice
This is a CASCADING, hard-to-reverse refactoring: a wrong target or newName can mass-edit BSL, forms and metadata across the whole configuration. Always preview first, run it on a configuration you can revert (version control), and do not execute without an explicit request. After execute, verify with get_project_errors.

## When to use
Use to rename an existing object or member and have all callers updated automatically. To create an object or member use create_metadata; to delete use delete_metadata.

## Two-phase workflow
1. Preview (confirm omitted / false, the default): returns a Markdown report with a change-points table. Each row has a '#' index, the file/location, a description, whether the change is Optional, and whether it is Enabled by default. Nothing is modified.
2. Execute (confirm=true): re-walks the SAME change tree with the SAME '#' numbering and applies the rename, skipping any indices you pass in disableIndices.

## Parameter details
- projectName (required): EDT project name.
- objectFqn (required): FQN of the rename target. Top object: 'Type.Name' (e.g. 'Catalog.Products'). Child member: 'Type.Name.ChildType.ChildName' (e.g. 'Document.SalesOrder.Attribute.Amount'). Supported child types: Attribute, TabularSection, Dimension, Resource.
- newName (required): the new programmatic Name. Only this identifier changes.
- confirm (optional, default false): false previews, true applies.
- disableIndices (optional): comma-separated '#' indices from the preview to skip, e.g. '2,3,5'. Only OPTIONAL change points can be disabled; required ones are always applied. One '#' index may span several context rows in the table - skipping it skips them all.
- maxResults (optional, default 20): caps how many change points the preview lists; 0 = no limit. This only trims the preview display, never what execute actually changes.
- timeout (optional, default 420): how long to wait for the cascade itself, in seconds, clamped to 60..3600. It bounds the rename only - the pre-flight index drain that runs before it has its own separate 60s bound, so the worst-case call is a minute longer than this. Raise it for a very large configuration; the default can also be changed in Preferences > MCP Server > Tools > `rename_metadata_object`.

## Timeout, and what the model is left in
The cascade runs on EDT's UI thread and cannot be preempted, so `timeout` bounds only how long the CALL waits - it does not stop the rename. When it expires the call fails with an error that names the stage the rename had reached, because that stage is what decides your next move:
- the rename never STARTED (the deadline elapsed while it was still queued and cancelling it kept it from starting) - the model is untouched and nothing needs checking; EDT's job scheduler is saturated, so retry when it is less busy or raise `timeout`;
- a PREVIEW (confirm not set) can never apply anything, so its timeout means only that the change points were not computed in time - nothing was or will be renamed;
- on an execute, still building the refactoring or still at the destructive-operation consent gate - nothing was rewritten yet, but the rename is not cancelled and may still apply;
- past the consent gate, in the apply phase - the configuration may be PARTIALLY renamed; inspect it with `get_metadata_objects` / `get_project_errors` and reload with `clean_project` (or revert in version control) before renaming again;
- apply phase finished - the rename is in the model apart from any change point that failed or was skipped, and it is the report listing those that was lost; confirm rather than repeat it.

In every case verify the model before retrying: a retry against an already-renamed object fails with "Object not found". The default is set above the worst measured legitimate wait (301s, EDT waiting out its own derived-data timeout inside the refactoring's batch session), so lowering it trades a hang for the riskier failure of reporting a rename that then lands anyway.

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
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
