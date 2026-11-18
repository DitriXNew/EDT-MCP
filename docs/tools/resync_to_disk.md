# resync_to_disk

Write the in-memory model back out to the on-disk src/ files and report model-to-disk desync; fixes 'object file does not exist' failures and dangling Configuration.mdo references, and re-saves named objects or forms (objects) from the model without changing them. Direction MODEL -> DISK, the opposite of clean_project. Dangling references are REPORTED by default, not removed. Three opt-in modes are DESTRUCTIVE: re-exporting every object or re-saving named ones overwrites on-disk edits - a value the model does not hold, such as an enum literal EDT dropped on load, is lost for good - and removing dangling references rewrites Configuration.mdo. Role-rights entries whose object no longer exists are also REPORTED by default; call once to review them, then again with cleanOrphanRoleRights=true to remove the proven-absent ones. Parameters and examples: get_tool_guide('resync_to_disk').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| cleanDanglingReferences | — | boolean | When true, REMOVE dangling/orphaned references from Configuration.mdo - entries that register an object with no .mdo and no BM body (unresolved proxies), the source of md-reference-intergrity 'lost reference' warnings that block update_database / XML import. Destructive: rewrites Configuration.mdo. Default: false - only report danglingFound + danglingDetails without changing anything. |
| cleanOrphanRoleRights | — | boolean | When true, REMOVE the role-rights entries (with their RLS) whose target object is proven absent - rewrites those roles' Rights.rights. Entries judged undetermined are never removed. Default: false - only report orphanRoleRights. |
| fullExport | — | boolean | When true, rewrite EVERY .mdo and every Form.form from the model (other content files, such as templates and rights, are not rewritten): a full disk refresh, slow on a large configuration - the export runs on the UI thread. Default: false - export only the objects whose .mdo is missing on disk. |
| objects | — | array | FQNs of metadata objects or forms to re-save from the model as they stand, unchanged, e.g. ['Catalog.Goods.Form.ItemForm'] (a form rewrites its Form.form) or ['Catalog.Goods']; Russian type tokens accepted. Needs overwriteDiskEdits=true; not combinable with fullExport, cleanDanglingReferences or cleanOrphanRoleRights. |
| overwriteDiskEdits | — | boolean | Required confirmation for fullExport=true and for objects: re-exporting from the in-memory model OVERWRITES on-disk edits, and a value EDT dropped on load (e.g. an unknown enum literal) is lost for good. Default: false - both are rejected unless this is true. |
| revalidate | — | boolean | When true, schedule a full project revalidation (clean build) after the export so stale markers refresh. Default: false (export only). |

## Guide
Bulk re-synchronize the in-memory EDT model to the on-disk `src/` files, and report (optionally remove) dangling/orphaned references in `Configuration.mdo` and role-rights entries on deleted objects. Use it to fix `update_database` / XML-import failures that complain about missing object files or "lost reference" warnings, when the BM model and disk have drifted apart, and to re-save named objects or forms from the model (`objects`).

- **Direction: MODEL -> DISK** - it writes the in-memory model out to the `src/` files (`.mdo` and `Form.form`).
- **Paired tool:** `clean_project` is the reverse, **DISK -> MODEL** - it rebuilds the model from disk and DISCARDS any unsaved in-memory model edits. Save (or resync) pending model changes before running `clean_project`.

## When to use
- `update_database` or `import_configuration_from_xml` fails with "object file does not exist - /Subsystems/X.mdo; /Roles/Y.mdo; ..." - the object lives in the model and in `Configuration.mdo` but has no `.mdo` on disk.
- `get_project_errors` shows `md-reference-intergrity` warnings like "a lost reference is set in field X at position N" - `Configuration.mdo` still registers an object whose body was lost (a dangling/orphaned entry that `delete_metadata` cannot remove, because there is no object to delete).
- A role's rights mention an object that is gone - `get_metadata_details` on the role marks the row `(unresolved)`, or `modify_metadata` refuses a rights edit on that role. This happens when an object is deleted OUTSIDE EDT (a git merge, a file removed on disk); `delete_metadata` itself cleans the role's entries.
- After a batch of older edits, you want every `.mdo` and `Form.form` queued for rewrite and that queue drained before exporting or updating the database (use `fullExport=true` for that full refresh).
- You want ONE object or form written back exactly as the model holds it - for example a hand-written `Form.form` normalized to what EDT loaded: `objects: ['Catalog.Goods.Form.ItemForm'], overwriteDiskEdits: true`. Run `validate_form_model` on a form first: what EDT dropped while loading the file is not in the model, and the re-save removes it from the file for good.

## What it does
1. Walks EVERY metadata top object of the configuration via the BM model (all kinds) and computes which of them have no `.mdo` under `src/` - the `missingBefore` set, the real desync.
2. Force-exports ONLY that missing subset (the same per-object export path the write tools use), so the absent files are restored without re-serializing the whole configuration on the UI thread. `fullExport=true` instead rewrites every `.mdo` and every `Form.form` from the model; other content files (templates, `Rights.rights`, command interfaces) are not rewritten. A full disk refresh, slow on a large configuration.
3. Integrity-checks `src/<TypeDir>/<Name>/<Name>.mdo` before AND after the export, reporting the missing set and anything still missing afterwards.
4. Scans the `Configuration`'s many-valued metadata reference collections (`subsystems`, `commonForms`, `webServices`, ...) for UNRESOLVED EMF PROXIES - entries that point at an object with no `.mdo` and no BM body - and REPORTS them (`danglingFound` + `danglingDetails`). Only when `cleanDanglingReferences=true` does it also REMOVE them and re-export `Configuration.mdo` - a destructive opt-in that rewrites `Configuration.mdo`.
5. Scans every role's rights for entries whose target object does not resolve and judges each one: `absent` (the object is proven gone from the configuration, or the entry names no target at all), or `undetermined` (absence cannot be proven - the model or its reference index is still being computed (validation still running does not hold the verdict back), the role is adopted or the project is an extension, the address cannot be walked, or the object exists and only the reference is stale). It REPORTS them in `orphanRoleRights`. Only when `cleanOrphanRoleRights=true` does it REMOVE the `absent` entries (with their rights and RLS), re-judging each one inside the write transaction, and re-export each changed role and its `Rights.rights`. `undetermined` entries are never removed. RLS field references that no longer resolve are reported in `orphanRlsFields` and never removed.

## Scoped re-save (`objects`)
`objects` replaces all of the above with one step: each FQN is resolved and its file is re-written from the model, unchanged. Nothing is scanned, repaired or removed.
- A top object (`Catalog.Goods`, `CommonModule.Utils`, `Subsystem.Sales`) rewrites its own file (`Goods.mdo`).
- A form (`Catalog.Goods.Form.ItemForm`, `CommonForm.Main`) rewrites its `Form.form` - not the owner's `.mdo`. The content-form FQN EDT itself uses (`Catalog.Goods.Form.ItemForm.Form`) works too.
- Type tokens are bilingual: `Справочник.Goods.Форма.ItemForm` is the same form. The object and form names are programmatic Names.
- Other content stored in a file of its own (template content such as `CommonTemplate.Print.Template`, a role's rights) is refused: re-saving it is not supported. Re-save the owning object for its `.mdo`; edit a template with `modify_metadata`.
- A nested member (`Catalog.Goods.Attribute.Weight`) is stored in its owner's file and is refused with the owner to re-save instead; an unknown FQN is refused by name. When any entry is refused, nothing is written.
- It requires `overwriteDiskEdits=true` and refuses `fullExport`, `cleanDanglingReferences` and `cleanOrphanRoleRights` in the same call. `revalidate` still applies.

## Parameter details
- `projectName` (required) - the EDT project to re-synchronize.
- `cleanDanglingReferences` (default `false` - report-only) - set `true` to REMOVE the dangling/orphaned proxy entries from `Configuration.mdo`. Destructive: rewrites `Configuration.mdo`. The default run only reports them (`danglingFound` + `danglingDetails`) and changes nothing.
- `cleanOrphanRoleRights` (default `false` - report-only) - set `true` to REMOVE the role-rights entries judged `absent`. Destructive: rewrites those roles' `Rights.rights`, including any RLS the entry carried (`hasRls` shows which do). Call once without it, review `orphanRoleRights`, then call again with it.
- `fullExport` (default `false`) - set `true` to rewrite EVERY `.mdo` and every `Form.form` instead of only the missing subset; other content files are not rewritten. Heavier (the export runs on the UI thread); use it for a deliberate full disk refresh. Because it re-writes those files from the model, it OVERWRITES any on-disk edits, so it must be confirmed with `overwriteDiskEdits=true`.
- `objects` - FQNs to re-save from the model without changing them; see "Scoped re-save" above.
- `overwriteDiskEdits` (default `false`) - required confirmation for `fullExport=true` and for `objects`: re-writing from the in-memory model OVERWRITES any on-disk edits, and a value EDT dropped while loading a file is lost for good. Both are rejected with an error unless this is `true`. Ignored otherwise (the default missing-only resync only writes the absent files).
- `revalidate` (default `false`) - after the export, run a full clean build so stale validation markers refresh. Heavier; leave off for a fast run.

## What you get
JSON: `success`, `objectsExported` (the missing subset by default; every `.mdo` and `Form.form` with `fullExport=true`), `totalTopObjects` (the metadata objects walked; with `fullExport=true` `objectsExported` is larger, as it also counts the content forms), `fullExport` / `revalidate` / `cleanDanglingReferences` (the request flags echoed back), `missingBeforeCount` + `missingBefore` (the real desync), `stillMissingCount` + `stillMissing` (anything not fixed), `danglingFound`, `danglingRemovedCount`, `danglingRemoved` / `danglingDetails` (`{field, lostFqn, position}` per entry), `cleanOrphanRoleRights` (echoed), `orphanRoleRightsFound` (the `absent` ones), `orphanRoleRightsUndetermined`, `orphanRoleRightsRemovedCount`, `orphanRoleRights` (listed up to 500; `{role, target, verdict, rights, hasRls, reason?}` per entry - `rights` as `Name=allowed|denied|default`), `orphanRlsFieldsFound` (the total) + `orphanRlsFields` (`{role, target, right, field}`), an optional `danglingWarning` / `orphanRoleRightsWarning` / `revalidateWarning`, and a human-readable `message`.

With `objects`: `success`, `projectName`, `objectsExported`, `revalidate`, `message`, and `resaved` - one `{object, fqn, file, state}` per request: the address as given, the top object re-saved, its project-relative file, and `state` read after the export finished: `written` (new time stamp or size), `unchanged` (same time stamp and size, so a rewrite is not proven), `missing` (no file afterwards) or `unknown` (EDT named no file).

## Notes & gotchas
- With default parameters the run writes nothing on an in-sync project: the missing set is empty, so there is nothing to export, the dangling scan is report-only, and the report shows `missingBeforeCount: 0` / `danglingFound: 0`. Running it twice is idempotent.
- `cleanDanglingReferences=true` is DESTRUCTIVE: it removes the dangling entries from the model and rewrites `Configuration.mdo`. Run the default report-only mode first and review `danglingDetails` before opting in.
- The removal is reported (`danglingRemovedCount` / `danglingRemoved`) only after the BM transaction actually committed; if the write task fails, the response carries `danglingWarning` and claims no removal.
- Proxy detection reads references WITHOUT resolving them (`eGet(ref, false)`) and only treats an entry as dangling when it is a genuinely unresolvable proxy (a not-yet-loaded but PRESENT object stays); a valid reference is never removed.
- An `undetermined` entry whose `reason` says the object exists is a stale link, not an orphan: `clean_project` re-reads the role and re-links it. Until then, as with an `absent` one, EDT cannot edit that role's rights.
- Types with no `src/` directory layout (Language, Style, the Configuration root) are skipped, not reported as missing.
- After it returns, re-check with `get_project_errors` / `get_problem_summary`; once the dangling entries are removed the `md-reference-intergrity` warnings should be gone and `update_database` / `export_configuration_to_xml` should unblock.

## Vendor support
The orphaned role-rights sweep leaves a role that vendor support locks untouched: its entries stay, are reported with the reason, and the result carries a warning naming the role. The other roles are cleaned as usual. Allow changes to the role in EDT's support settings to clean it too.

With `cleanDanglingReferences=true`, a configuration that vendor support does not allow to change (or whose check cannot be answered) keeps its dangling entries: they are still listed in `danglingFound` / `danglingDetails`, nothing is removed, and `danglingWarning` says why. The rest of the resync runs as usual. Ask the user to allow changes in EDT's support settings, then run the cleanup again.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
