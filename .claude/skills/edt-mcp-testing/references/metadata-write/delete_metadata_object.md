# delete_metadata_object — how to test

**Purpose.** Delete a metadata object or a nested member (attribute / tabular section / dimension / resource) **with full refactoring**: EDT cleans up every reference to it across BSL code, forms, and other metadata. Source: `DeleteMetadataObjectTool` (extends `AbstractMetadataWriteTool`) → `IMdRefactoringService.createMdObjectDeleteRefactoring(List<MdObject>)`, then `IRefactoring.getStatus()/getItems()` for preview and `IRefactoring.perform()` for execute.

> **DESTRUCTIVE — but reversible on the *source* tree.** This mutates the EDT model (deletes the object and rewrites every referencing artifact). Unlike `update_database` it operates on the **source**, so `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` *does* undo it — provided the deletion was done **through MCP** so model + disk stayed in sync (after a `-clean` redeploy EDT discards unsaved in-memory edits). Treat as **explicit-request-only**; run it **only on `TestConfiguration`**, and **only with `confirm=true` after** reviewing the preview.

**Two-phase contract (from source).**
- `confirm` absent / `false` (default) → **preview only**: builds the refactoring, returns `action:"preview"` with the affected references — **nothing is mutated** (`buildPreview` only reads `getItems()`/`getStatus()`).
- `confirm=true` → **execute**: calls `refactoring.perform()`, returns `action:"executed"`. This is the only path that mutates.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project **`State=ready`** — poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext index rebuilds for a while). A still-indexing project can make reference cleanup incomplete.
- The `IMdRefactoringService` OSGi service must be available (it is in the normal EDT runtime); the tool returns `"IMdRefactoringService not available"` otherwise.
- Test base: **`TestConfiguration`** (small — `Catalog.Catalog` with attribute `Attribute` and form `ItemForm`, two `CommonModule`s). Use a **throwaway** member you created yourself for the live test; do **not** delete a real configuration object.
- This tool runs on the **SWT UI thread** (`AbstractMetadataWriteTool.execute` marshals via `Display.syncExec`). No JVM flag is needed (unlike the form-render tools). No infobase / exclusive-access requirement either — it edits the source model, not a running infobase.

**Call (DOCUMENTED — preview is safe to run; execute is explicit-request-only).** Parameters declared in `getInputSchema()`: `projectName` (required), `objectFqn` (required), `confirm` (optional boolean, default false).

Preview a whole top-level object (safe — no mutation):
```
delete_metadata_object(
  projectName="TestConfiguration",
  objectFqn="Catalog.Catalog"
)
```

Execute a delete (mutates — only after reviewing the preview, explicit request):
```
delete_metadata_object(
  projectName="TestConfiguration",
  objectFqn="Catalog.Catalog.Attribute.Attribute",
  confirm=true
)
```

**Full test procedure (mutate-then-revert; preview is safe, execute is explicit-request-only).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`.
2. **Create a throwaway target through MCP** (so model + disk stay in sync, and the revert is trivial). Add a temporary attribute on `Catalog.Catalog` with `add_metadata_attribute` (e.g. name `TmpToDelete`). Verify it exists with `get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")` — `TmpToDelete` should appear in its attributes.
3. **Preview first (no `confirm`).** `delete_metadata_object(projectName="TestConfiguration", objectFqn="Catalog.Catalog.Attribute.TmpToDelete")`. Expect `action:"preview"`, an `items` array (refactoring items), `affectedReferences` (each with `referencingObject` / `reference` / `targetObject`) and `affectedReferencesCount`. **Nothing has changed yet** — confirm with `get_metadata_details` that `TmpToDelete` is still present.
4. **Execute (`confirm=true`).** `delete_metadata_object(projectName="TestConfiguration", objectFqn="Catalog.Catalog.Attribute.TmpToDelete", confirm=true)`. Expect `action:"executed"`, `message:"Delete refactoring completed successfully."`.
5. **Verify the deletion.** `get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")` — `TmpToDelete` must be **gone** from the attribute list. (For a top-level object delete, verify with `get_metadata_objects` that the object no longer appears.) Also check `get_project_errors`/`get_problem_summary` to confirm reference cleanup left no dangling references.
6. **REVERT the source.** `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`. Because the throwaway attribute was added and deleted entirely through MCP, the working tree returns to its committed state. After reverting, a `-clean` redeploy (or letting EDT re-read the workspace) realigns the in-memory model with disk.

**Result.** JSON envelope (`getResponseType()` = `ResponseType.JSON`). Shapes below are **representative, built from source** (`buildPreview` / `performDelete`) — *not a live capture*. Field order is not guaranteed (Gson over a `HashMap`); only the inner `items`/`affectedReferences` lists are insertion-ordered (`LinkedHashMap`).

Preview (`confirm` omitted):
```json
{
  "success": true,
  "action": "preview",
  "objectFqn": "Catalog.Catalog.Attribute.TmpToDelete",
  "refactoringTitle": "Delete",
  "items": [
    { "name": "Catalog.Catalog.Attribute.TmpToDelete", "optional": false, "checked": true }
  ],
  "affectedReferences": [
    {
      "referencingObject": "CommonModule.Error",
      "reference": "module",
      "targetObject": "Catalog.Catalog.Attribute.TmpToDelete"
    }
  ],
  "affectedReferencesCount": 1,
  "message": "Preview of delete refactoring. References listed above will be cleaned up. Call with confirm=true to apply."
}
```

Execute (`confirm=true`):
```json
{
  "success": true,
  "action": "executed",
  "objectFqn": "Catalog.Catalog.Attribute.TmpToDelete",
  "message": "Delete refactoring completed successfully."
}
```

Field meaning (from source):
- **`action`** — `"preview"` (no confirm) or `"executed"` (confirm=true). Use it to tell the two phases apart.
- **`objectFqn`** — the **normalized** FQN that was resolved (`MetadataTypeUtils.normalizeFqn` runs before resolution, so the echo may differ from a raw lowercase/alias input).
- **`refactoringTitle`** — `IRefactoring.getTitle()` (preview only).
- **`items`** — refactoring items: `name`, `optional` (`isOptional()`), `checked` (`isChecked()`) (preview only).
- **`affectedReferences`** — one entry per `IRefactoringProblem`. For a `CleanReferenceProblem` it carries `referencingObject` (the BM FQN of the artifact that points at the target, `bmGetFqn()`) and `reference` (the EMF feature name, `EStructuralFeature.getName()`); every problem also carries `targetObject` (`bmGetFqn()` of the object being deleted). A problem with no extractable fields is skipped (preview only).
- **`affectedReferencesCount`** — size of `affectedReferences` (preview only).
- **`message`** — preview hint, or `"Delete refactoring completed successfully."` on execute.

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}`, and the protocol layer (`McpProtocolHandler`) flags the payload with `isError:true`. Cases from source:
- Missing args: `"projectName is required. Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}"`; `"objectFqn is required. Examples: 'Catalog.Products' …"`.
- Resolution (`resolveProjectAndConfig`): `"Project not found: <name>"`; `"Configuration provider not available"`; `"Could not get configuration for project: <name>"`.
- Service: `"IMdRefactoringService not available"`.
- Object not found / malformed FQN: `"Object not found: <fqn>. Check the FQN format: 'Type.Name' … Supported child types: Attribute, TabularSection, Dimension, Resource."` — this is returned both for a genuinely missing object **and** for a malformed nested FQN with an odd trailing token (see `isValidFqnArity`: only 2, 4, 6, … parts are valid).
- Refactoring creation: `"Failed to create delete refactoring for: <fqn>"`.
- Execute failure (caught in `performDelete`): `"Delete failed: <message>"` (and the exception is logged via `Activator.logError`). Any other exception from the UI-thread body is caught by `AbstractMetadataWriteTool.execute` and returned as `ToolResult.error(e.getMessage())`.

Representative error shape:
```json
{
  "success": false,
  "error": "Object not found: Catalog.Nonexistent. Check the FQN format: ...",
  "isError": true
}
```

**Gotchas.**
- **Always preview before you confirm.** The preview is non-mutating and shows the exact cascade (`affectedReferences`). Calling with `confirm=true` blind can rewrite many artifacts at once. The two phases are distinguished by `action` in the response, not by a separate tool.
- **Reversible on source, but only if mutated through MCP.** `git checkout`/`git clean` on `TestConfiguration` restores the tree. After a `-clean` redeploy EDT loses unsaved in-memory edits, so do the delete **through MCP** (model + disk in sync), never by hand-editing `.mdo`. Run **only on `TestConfiguration`**, **only on explicit request**.
- **Cascade / blast radius.** This is a refactoring delete: it removes the object *and* cleans references in BSL, forms, and other metadata. On a real configuration the cascade can be large — verify the scope in the preview first, and after execute check `get_project_errors` / `get_problem_summary` for any dangling references the cleanup missed.
- **Top-level vs nested FQN arity.** A top-level FQN is `Type.Name` (2 parts); each nested level adds a `.ChildType.ChildName` pair (4, 6, … parts). A malformed FQN with an odd trailing token is deliberately rejected as "Object not found" so a nested delete can **never** silently fall back to deleting the parent object (see `isValidFqnArity`). Nested child types accepted: `Attribute`, `TabularSection`, `Dimension`, `Resource` (and their plural / Russian forms).
- **No JVM flag, no infobase exclusivity.** Unlike `get_form_screenshot` (needs `-DnativeFormBufferedLayoutRender=true`) and `update_database` (needs exclusive infobase access), this tool only needs the workbench up and the project `ready`. It runs on the SWT UI thread, so a modal dialog popping up in EDT could block it in an automated run.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done`), do **not** retry-spam — a blind retry of `confirm=true` could attempt a second delete (which then fails as "Object not found", but still noise). Re-verify independently: check the object via `get_metadata_details` / `get_metadata_objects`, and read the EDT log at `D:\WS\EDT\.metadata\.log` (the tool logs delete-refactoring errors via `Activator.logError`).
- **JSON tool with HTML/char escaping.** Output is the `ToolResult` JSON envelope; `ToolResult.toJson()` HTML-escapes `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`. If you assert on text, match a delimiter-free substring (e.g. `not found`, `completed successfully`), never raw `'…'` or `>=`.
- **Bilingual.** The metadata **TYPE token** may be English or Russian (`Catalog`/`Справочник`, and nested `Attribute`/`Реквизит`, `TabularSection`/`ТабличнаяЧасть`, `Dimension`/`Измерение`, `Resource`/`Ресурс` — all handled in `findChild`). The **object NAME is the programmatic `Name`, not the synonym / display name** (top-level resolution via `MetadataTypeUtils.findObject`, nested via case-insensitive `getName()` match). Do not pass a localized synonym as the object name. Synonyms themselves are keyed by language CODE elsewhere in the model; they play no role in resolution here.
```
