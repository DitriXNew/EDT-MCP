# delete_metadata — how to test

**Purpose.** Delete a metadata node — a top-level **object** or a subordinate **member** (attribute / tabular section / dimension / resource / enum value) — addressed by a 1C full-name FQN, **with full cascade refactoring**: EDT cleans up every reference to the node across BSL code, forms, and other metadata (via the EDT refactoring service). Two-phase contract: call **without** `confirm` to get a *preview* (the affected-references list/count), then call **with** `confirm=true` to apply. It is addressed by the unified `fqn` parameter and the shared metadata-node resolver.

> **DESTRUCTIVE and hard to reverse (`destructiveHint`).** A confirmed delete removes the node *and* rewrites every referencing artifact across the configuration. It operates on the **source** tree (not the infobase DB), so `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` *does* undo it — **provided the deletion was done through MCP** so model + disk stayed in sync (after a `-clean` redeploy EDT discards unsaved in-memory edits). Treat as **explicit-request-only**; run happy paths **only on `TestConfiguration`** against a **throwaway target you created yourself**, and **only with `confirm=true` after** reviewing the preview.

**Two-phase contract (from source / e2e).**
- `confirm` absent / `false` (default) → **preview only**: builds the refactoring and returns `action:"preview"` with the affected references — **nothing is mutated**.
- `confirm=true` → **execute**: performs the delete and returns `action:"executed"`. This is the only path that mutates.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project **`State=ready`** — poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext + BM index rebuilds for a while). A still-indexing project can make the reference cleanup incomplete; do not confirm against a partial preview.
- The EDT refactoring OSGi service must be available in the runtime (it is in a normal EDT workbench); the tool returns a service-unavailable error otherwise.
- Test base: **`TestConfiguration`** (small — `Catalog.Catalog` with attribute `Attribute` and form `ItemForm`; `CommonModule.Error` / `OK` / `Calc`; `CommonForm.Form`, `Subsystem.Subsystem`, `CommonAttribute.CommonAttribute`, `SessionParameter.SessionParameter`). For a live delete, target a **throwaway** node you created yourself, not a real configuration object.
- Runs on the **SWT UI thread**. No JVM flag is needed (unlike the form-render tools) and no infobase / exclusive-access requirement (it edits the source model, not a running infobase). A modal dialog popping up in EDT could block it in an automated run.

**Call (representative — preview is safe to run; execute is explicit-request-only).** Parameters: `projectName` (required), `fqn` (required), `confirm` (optional boolean, default false).

Preview a member (safe — no mutation):
```
delete_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Products.Attribute.Weight"
)
```

Execute a delete (mutates — only after reviewing the preview, explicit request):
```
delete_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog.Attribute.E2EDelAttr",
  confirm=true
)
```

FQN forms (EN/RU TYPE tokens both accepted):
- Top-level object: `Catalog.Products`, `CommonModule.Calc`, `Document.Order`.
- Member node: `Catalog.Products.Attribute.Weight`, `Document.Order.TabularSection.Goods`, `InformationRegister.Prices.Dimension.Product`, `AccumulationRegister.Stock.Resource.Quantity`.

**Full test procedure (mutate-then-revert; preview is safe, execute is explicit-request-only).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`. Confirm the subtree is clean first (`git status -- TestConfiguration`) so the post-revert diff is meaningful.
2. **Create a throwaway target through MCP** (so model + disk stay in sync and the revert is trivial). Add a uniquely-named temporary attribute on `Catalog.Catalog`:
   `create_metadata(projectName="TestConfiguration", fqn="Catalog.Catalog.Attribute.E2EDelAttr")`, then `wait_for_project_ready` / poll `list_projects` until `ready`. Verify it exists with `get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")` — `E2EDelAttr` should appear in the attribute list.
3. **Preview first (no `confirm`).** `delete_metadata(projectName="TestConfiguration", fqn="Catalog.Catalog.Attribute.E2EDelAttr")`. Expect `action:"preview"`, an `items` array (refactoring items), `affectedReferencesCount`, and a `message` instructing you to re-call with `confirm=true`. **Nothing has changed yet** — re-confirm with `get_metadata_details` that `E2EDelAttr` is still present, and `git status -- TestConfiguration` should show no diff.
4. **Execute (`confirm=true`).** `delete_metadata(projectName="TestConfiguration", fqn="Catalog.Catalog.Attribute.E2EDelAttr", confirm=true)`. Expect `action:"executed"` and the echoed `fqn`.
5. **Verify the deletion in BOTH model and disk.**
   - **Model (member):** `get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")` — `E2EDelAttr` must be **gone** from the attribute list, and the **parent `Catalog.Catalog` must survive** the member delete (`get_metadata_objects(metadataType="catalogs")` still lists `Catalog`).
   - **Model (top-level object):** for a whole-object delete (e.g. `CommonModule.Calc`), `get_metadata_objects(projectName="TestConfiguration", metadataType="commonModules")` no longer lists the object, while a **sibling survives** (e.g. `OK` still present).
   - **Disk:** independently confirm the source tree changed. For a top-level object delete the object's own `.mdo` is removed (e.g. `src/CommonModules/Calc/Calc.mdo` gone) **and** the collection reference is stripped from `src/Configuration/Configuration.mdo`. For a member delete the owning `.mdo` (e.g. `Catalogs/Catalog/Catalog.mdo`) is modified — confirm via `git status -- TestConfiguration`.
   - **Sanity:** `get_project_errors` / `get_problem_summary` — the cascade cleanup should leave no dangling-reference errors.
6. **REVERT the source (mandatory).** `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`. Because the throwaway node was added and deleted entirely through MCP, the working tree returns to its committed state. Then realign the **in-memory model** with the reverted disk — a `pwsh D:\Soft\edt-redeploy.ps1` `-clean` relaunch reloads the model from disk. Do **not** leave EDT running with a model that no longer matches the reverted `.mdo` files.

**Result.** JSON envelope (`ResponseType.JSON`; the payload is in `structuredContent`, while `content[0].text` is just a `Done`/`Error` placeholder). Shapes below are **representative, based on what `test_delete_metadata.py` asserts** — *not a live capture*. Field order is not guaranteed; only the inner `items` / `affectedReferences` lists are insertion-ordered.

Preview (`confirm` omitted):
```json
{
  "success": true,
  "action": "preview",
  "fqn": "Catalog.Catalog.Attribute.E2EDelAttr",
  "refactoringTitle": "Delete",
  "items": [
    { "name": "Catalog.Catalog.Attribute.E2EDelAttr", "optional": false, "checked": true }
  ],
  "affectedReferences": [
    {
      "referencingObject": "CommonModule.Calc",
      "reference": "module",
      "targetObject": "Catalog.Catalog.Attribute.E2EDelAttr"
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
  "fqn": "CommonModule.Calc"
}
```

Field meaning:
- **`action`** — `"preview"` (no confirm) or `"executed"` (confirm=true). The quickest discriminator between the two phases.
- **`fqn`** — the resolved/echoed target FQN (normalized before resolution, so the echo may differ from a raw lowercase / alias input). The e2e suite asserts the response echoes the requested `fqn`.
- **`refactoringTitle`** — the refactoring title (preview only).
- **`items`** — refactoring items: `name`, `optional`, `checked` (preview only).
- **`affectedReferences`** — one entry per cleanup problem: `referencingObject` (the artifact that points at the target), `reference` (the EMF feature name), `targetObject` (the node being deleted) (preview only).
- **`affectedReferencesCount`** — size of `affectedReferences`; the e2e preview test asserts this key is present (preview only).
- **`message`** — the preview hint; it **must** mention `confirm=true` (the e2e preview test asserts this).

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}`, and the protocol layer flags the payload with `isError:true`. The e2e negative matrix asserts each error names the offending input and suggests a remedy, and that the project is left **unchanged on disk** (`assert_no_diff`). Cases:
- **Missing `projectName`** → error naming `projectName`, suggesting it is `required` and pointing at `list_projects`. The sibling object must **not** be deleted.
- **Missing `fqn`** → error naming `fqn`, suggesting it is `required`.
- **Non-existent project** → error naming the bad project, suggesting `not found` + `list_projects`.
- **Non-existent node** (e.g. `CommonModule.DoesNotExist_e2e`) → error naming the bad FQN, suggesting the `Type.Name` format (with a `Catalog.Products` example) and **listing supported member kinds incl. `EnumValue`**. A sibling (e.g. `OK`) must survive.
- **Malformed nested FQN whose child does not exist** (e.g. `Catalog.Catalog.Attribute.NoSuchAttr_e2e`) → error naming the bad FQN, suggesting `not found`, **and the parent `Catalog.Catalog` must NOT be deleted** as a side effect (the arity/child guard prevents a nested delete from silently falling back to the parent).
- **Bare token without a dot** (e.g. `JustAName`) → error naming the token, suggesting the `Type.Name` format.

Representative error shape:
```json
{
  "success": false,
  "error": "Object not found: CommonModule.DoesNotExist_e2e. Check the FQN format: 'Type.Name' (e.g. 'Catalog.Products'). Supported member kinds: Attribute, TabularSection, Dimension, Resource, EnumValue.",
  "isError": true
}
```

**Gotchas.**
- **Always preview before you confirm.** The preview is non-mutating and shows the exact cascade (`affectedReferences`). Calling with `confirm=true` blind can rewrite many artifacts at once. The two phases are distinguished only by `action` in the response, not by a separate tool. The e2e preview test verifies the preview lists change points (`items`, `affectedReferencesCount`) **and leaves disk untouched** (`assert_no_diff`).
- **Member delete must not touch the parent.** Deleting `Catalog.Catalog.Attribute.X` removes only that member; the parent `Catalog.Catalog` survives. A failed nested-attribute delete (bad child name) likewise must not delete the parent — the FQN arity/child guard makes a nested delete *never* silently fall back to the object.
- **Reversible on source, but only if mutated through MCP.** `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` restores the tree; then `-clean`-relaunch EDT so the in-memory model reloads from the reverted disk (otherwise model and `.mdo` disagree until restart). Do the delete **through MCP**, never by hand-editing `.mdo`. Run **only on `TestConfiguration`**, **only on explicit request**.
- **Cascade / blast radius.** This is a refactoring delete: it removes the node *and* cleans references in BSL, forms, and other metadata. On a real configuration the cascade can be large — verify the scope in the preview first, and after execute check `get_project_errors` / `get_problem_summary` for any dangling references the cleanup missed.
- **Verify model AND disk.** A confirmed delete must show up in both: gone from the model read-back (`get_metadata_details` / `get_metadata_objects`) **and** on disk (the object's `.mdo` removed and the `Configuration.mdo` collection entry stripped for a top-level object; the owning `.mdo` modified for a member). Do not trust one channel alone.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done`), do **not** retry-spam — a blind retry of `confirm=true` could attempt a second delete (which then fails as "not found", but adds noise). Re-verify independently: check the node via `get_metadata_details` / `get_metadata_objects`, `git status -- TestConfiguration`, and the EDT log at `D:\WS\EDT\.metadata\.log`.
- **JSON tool, no HTML escaping (error path).** `GsonProvider` uses `disableHtmlEscaping()`, so `ToolResult.toJson()` keeps characters like `'`, `<`, `>`, `&`, `=` RAW (not `\uXXXX`); if you assert on an error string, match a delimiter-free substring (e.g. `not found`, `required`, `Type.Name`) for robustness, never raw `'…'`. The e2e error-quality checks deliberately assert on plain tokens (`projectName`, `fqn`, `not found`, `EnumValue`) for exactly this reason.
- **Bilingual.** The metadata **TYPE token** may be English or Russian (`Catalog`/`Справочник`, and member tokens `Attribute`/`Реквизит`, `TabularSection`/`ТабличнаяЧасть`, `Dimension`/`Измерение`, `Resource`/`Ресурс`). The **node NAME is the programmatic `Name`, not the synonym / display name** — do not pass a localized synonym as the node name. Synonyms are keyed by language CODE elsewhere in the model and play no role in FQN resolution here.
