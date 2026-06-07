# rename_metadata_object — how to test

**Purpose.** Rename a metadata object or one of its child elements (attribute / tabular section / dimension / resource) with **full cascade refactoring**: EDT rewrites every reference in BSL code, forms, queries (DCS / dynamic-list), and other metadata. Two-phase contract: call **without** `confirm` to get a *preview* (a numbered table of change points), then call **with** `confirm=true` to apply, optionally skipping individual change points by their preview `#` index. Source: `RenameMetadataObjectTool` → `IMdRefactoringService.createMdObjectRenameRefactoring(targetObject, newName)` → preview via `buildPreview(...)` / apply via `performRename(...)`.

> **THINK TWICE — mass-corruption risk.** This is the single most dangerous read/write tool in the set: a wrong rename cascades edits across the *whole* configuration (BSL, forms, metadata) and a mistake means widespread corruption. It is `confirm`-gated by design. Run it **only** on `TestConfiguration`, **only** on an explicit request, and **always preview first**. Do not run a real rename as part of routine reference drafting — the procedure below is documented from source, not auto-executed.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project **`State=ready`** — poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext + BM index rebuilds for a while). A cold/partial index means the exact-match / code-context pipeline returns fewer change points than reality; do not confirm against a partial preview.
- The OSGi services must be available in the runtime: `IConfigurationProvider` (`Activator.getConfigurationProvider()`), `IMdRefactoringService` (`Activator.getMdRefactoringService()`), and the BM model manager. They are present in the normal EDT runtime; if absent the tool returns a service-unavailable error.
- Runs on the SWT **UI thread** (`PlatformUI.getWorkbench().getDisplay().syncExec`). Do not hammer it concurrently with other UI-thread tools (`get_symbol_info`, `get_content_assist`, screenshots).
- **Mutate THROUGH MCP.** A confirmed rename mutates the in-memory BM model *and* the source tree. After a `-clean` redeploy EDT discards unsaved in-memory edits, so always drive the change through this tool (not a hand edit of the `.mdo`) to keep model + disk in sync.
- **No infobase exclusivity needed.** Unlike `update_database`, this tool touches the source tree / model, not the infobase DB. It does *not* require freeing the IB. (But to later see the rename in a running app you would `update_database` separately.)

**Call (real or documented).** Representative target against `TestConfiguration` (`Catalog.Catalog` exists, with attribute `Attribute` and form `ItemForm`).

Phase 1 — preview (safe, read-only; this is what you actually run first):
```
rename_metadata_object(
  projectName="TestConfiguration",
  objectFqn="Catalog.Catalog.Attribute.Attribute",
  newName="AttributeRenamed"
)
```
- `confirm` omitted ⇒ defaults to `false` ⇒ **preview only, nothing is written**.
- `objectFqn` accepts a top-level object (`Catalog.Catalog`) or a nested child `Type.Name.ChildType.ChildName`. Supported child types (case-insensitive, EN + RU): `Attribute`/`реквизит`, `TabularSection`/`табличнаячасть`, `Dimension`/`измерение`, `Resource`/`ресурс` (singular or plural). The leading TYPE token may be Russian (`Справочник.Catalog…`) — it is normalized via `MetadataTypeUtils.normalizeFqn` + `findObject`.
- `maxResults` (integer, default 20, `0` = no limit) caps how many change points the preview *table* shows; the YAML `totalChanges` still reports the full count.

Phase 2 — apply (DESTRUCTIVE; explicit-request-only):
```
rename_metadata_object(
  projectName="TestConfiguration",
  objectFqn="Catalog.Catalog.Attribute.Attribute",
  newName="AttributeRenamed",
  confirm=true,
  disableIndices="2,3"          // optional: skip these preview '#' indices
)
```
- `disableIndices` is a comma-separated list of preview `#` indices to **skip**. The numbering is the single source of truth shared by preview and execute (`collectFlatChanges` ↔ `walkLeafChanges`): **a preview `#` index maps to the same leaf change at execute time** — so copy indices straight from the preview table. Only *optional* (Skippable=`yes`) change points can actually be disabled; passing a non-optional or unknown index is silently ignored. One index may render as several context rows — skipping it skips them all.

**Full test procedure (explicit-request-only; preview-then-confirm-then-revert).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`. Confirm the repo is clean for that subtree first (`git status -- TestConfiguration`) so the post-revert diff is meaningful.
2. **Capture the before-state.** Note what you are about to rename and what references it. For `Catalog.Catalog.Attribute.Attribute`, e.g. `get_metadata_details(projectName="TestConfiguration", objectFqn="Catalog.Catalog")` to see the attribute, and `find_references` / `search_in_code` for any BSL/form usages. Save the old name.
3. **Preview (no `confirm`).** Call the Phase-1 form. Inspect the returned Markdown: the YAML `totalChanges` / `enabledChanges` / `problems`, the **Change Points** table (each row has `#`, Type, Description, Line, Col, Default ✅/❌, Skippable yes/no, Project, FQN), the **Code Context** `bsl` snippets (±3 lines, `>>>` marks the hit line, with the containing method signature), and any **Problems** list. **Do not proceed if `problems` is non-zero or the table is suspiciously short** (partial index) — investigate first.
4. **Decide skips (optional).** If some optional change points should not be touched, note their `#` indices for `disableIndices`.
5. **Apply (`confirm=true`).** Call the Phase-2 form. Expect the "Rename Completed" Markdown with a `## Performed` list (and `## Errors` empty).
6. **Verify the cascade.**
   - **Metadata:** `get_metadata_details(projectName="TestConfiguration", objectFqn="Catalog.Catalog")` → the attribute is now `AttributeRenamed`; old name gone.
   - **Disk:** independently confirm the `.mdo` changed — `git status -- TestConfiguration` should show `Catalogs/Catalog/Catalog.mdo` (and any touched `.bsl` / form) as modified.
   - **Code/forms:** re-run the `find_references` / `search_in_code` from step 2 — old-name references should be gone and the new name present at the previewed lines.
   - **Sanity:** `get_project_errors(projectName="TestConfiguration")` — a correct cascade should not introduce new unresolved-reference errors.
7. **REVERT (mandatory).** Restore the source tree:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   Then bring the **in-memory model** back in line with the reverted disk — the safest reset is a `pwsh D:\Soft\edt-redeploy.ps1` `-clean` relaunch (it reloads the model from disk), or re-import. Do **not** leave EDT running with a model that no longer matches the reverted `.mdo` files.

**Result.** Embedded Markdown resource (`ResponseType.MARKDOWN`), with YAML frontmatter. Two shapes — **representative, from source (`buildPreview` / `performRename`), not a live capture.**

Preview (`confirm` omitted/false):
```markdown
---
action: preview
objectFqn: Catalog.Catalog.Attribute.Attribute
newName: AttributeRenamed
totalChanges: 5
enabledChanges: 5
problems: 0
debugExactMatches: 2
---

# Refactoring Preview: Rename `Catalog.Catalog.Attribute.Attribute` → `AttributeRenamed`

**Total change points:** 5 | **Enabled by default:** 5

## Change Points

| # | Type | Description | Line | Col | Default | Skippable | Project | FQN |
|---|------|-------------|------|-----|---------|-----------|---------|-----|
| 0 | rename | Rename attribute | — | — | ✅ | no | TestConfiguration | Catalog.Catalog |
| 1 | bslRef | Update reference | 12 | 9 | ✅ | yes | TestConfiguration | CommonModule.OK |
| 2 | bslRef | Update reference | 34 | 15 | ✅ | yes | TestConfiguration | Catalog.Catalog.Form.ItemForm.Form |

## Code Context

### #1 — `Procedure DoWork()` · CommonModule.OK:12
```bsl
   9:     // ...
  10:     Value = 0;
  11:
  12: >>> Item = Catalogs.Catalog.FindByAttribute(...);
  13:     Return Item;
```

> To execute, call with `confirm=true`.
> Use `disableIndices='1,2,3'` to skip change points by their `#` index (optional only; one index may span several context rows - skipping it skips them all).
```

Apply (`confirm=true`):
```markdown
---
action: executed
objectFqn: Catalog.Catalog.Attribute.Attribute
newName: AttributeRenamed
disabledCount: 0
performedCount: 1
errors: 0
---

# Rename Completed: `Catalog.Catalog.Attribute.Attribute` → `AttributeRenamed`

## Performed

- Rename Catalog.Catalog.Attribute.Attribute to AttributeRenamed
```

Field/shape notes (from source):
- **`action`** — `preview` vs `executed`; the YAML is the quickest programmatic discriminator.
- **`totalChanges` vs the table** — `totalChanges` is the full leaf count; the table is clamped to `maxResults` (a trailing `| ... | _N more_ |` row and a "_Showing X of Y_" line appear when clamped). `enabledChanges` counts leaves enabled by default.
- **`#` index** — global, stable across preview→execute (one per leaf change; composites get no index). This is exactly what `disableIndices` consumes.
- **Type** — `rename` for the core metadata rename leaf (non-optional), `bslRef` for a code/form/query reference edit.
- **`problems`** — count of `IRefactoringProblem`s (e.g. `CleanReferenceProblem`); a non-empty **Problems** section lists `referencingFqn → feature | objectFqn`. Treat any problem as a stop-and-investigate signal.
- **`debugExactMatches`** — diagnostic count from the full-text-search exact-match pipeline; informational only.
- Apply: **`performedCount`** = refactorings successfully `perform()`ed, **`errors`** = failed ones (each listed under `## Errors` as `<title>: <message>`), **`disabledCount`** = size of the `disableIndices` set.

**Gotchas.**
- **Cascade = mass-corruption risk; preview before every confirm.** The whole point is that one rename rewrites BSL, forms, queries and metadata across the configuration. Always read the preview, check `problems == 0`, and only then `confirm=true`. Explicit-request-only, `TestConfiguration` only.
- **Mutation safety / revert.** After a confirmed rename, revert with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`, then `-clean`-relaunch EDT so the in-memory model reloads from the reverted disk (otherwise the model and `.mdo` files disagree until restart). Mutate *through MCP*, never by hand-editing `.mdo`, so model + disk stay in sync.
- **Index identity is the contract.** `disableIndices` uses the preview `#` numbers verbatim — they are guaranteed to map to the same leaf at execute time (preview `collectFlatChanges` and execute `walkLeafChanges` share the numbering). Only Skippable=`yes` (optional) indices are honored; the core `rename` leaf and any non-optional ref edit cannot be disabled, and unknown indices are ignored without error.
- **FQN resolution & not-found.** `resolveObject` splits on `.`: `Type.Name` for top-level, then `ChildType.ChildName` pairs for nesting. A bad/unknown FQN returns a guided `ToolResult.error`: *"Object not found: <fqn>. Check the FQN format … Supported child types: Attribute, TabularSection, Dimension, Resource."* `findChild` matches the child **NAME** case-insensitively but only those four child types — anything else (e.g. `Form`, `Command`) yields `getterName == null` ⇒ not found.
- **Error contract.** Genuine failures use `ToolResult.error(...)` ⇒ `{success:false, error:"…"}` with `isError:true` (JSON envelope, *not* the Markdown payload). Cases from source: `projectName is required …`, `objectFqn is required …`, `newName is required …`, `Project not found: <name>`, `Configuration provider not available`, `Could not get configuration for project: <name>`, `IMdRefactoringService not available`, `Object not found: <fqn> …`, `Failed to create rename refactoring for: <fqn>`. A successful preview/apply is *Markdown*, not the JSON envelope — discriminate on `action:` in the YAML, or on `isError`.
- **No JSON HTML-escaping (only the error path is JSON).** `GsonProvider` uses `disableHtmlEscaping()`, so `ToolResult.toJson()` keeps `>`, `<`, `&`, `=`, and the apostrophe `'` RAW (not `\uXXXX`). If you assert on an *error* string, match a delimiter-free substring (e.g. `is required`, `not found`) for robustness, never a raw `'…'` or `>=`. The Markdown success path is not JSON at all (but table cells escape `|` via `escapeMarkdownCell`).
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done`), do **NOT** retry-spam — a retry of the `confirm=true` call could apply (or partially apply) a *second* rename. Re-verify independently: check `get_metadata_details` for the new/old name, `git status -- TestConfiguration`, and the EDT log `D:\WS\EDT\.metadata\.log` (the tool logs preview-mapping diagnostics and any `Error performing rename refactoring: <title>` / `Error in rename_metadata_object`). Trust the log + model state over the echoed text.
- **Bilingual.** The leading **TYPE token** is bilingual (`Справочник`/`Catalog`, and child-type tokens `реквизит`/`Attribute` etc., singular or plural) and is normalized before resolution. The object/child **NAME** is programmatic and is *never* translated — pass the real `Name`, not a synonym. `newName` sets the programmatic `Name`; synonyms (keyed by language **code** `ru`/`en`) are a separate concern and are not what this tool's `newName` changes. Searching for old references with `search_in_code` is literal/not dialect-aware — prefer `find_references` (AST) to confirm the cascade.
