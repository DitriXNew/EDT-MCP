Bulk re-synchronize the in-memory EDT model to the on-disk `src/` `.mdo` files, and clean dangling/orphaned references out of `Configuration.mdo`. Use it to fix `update_database` / XML-import failures that complain about missing object files or "lost reference" warnings, when the BM model and disk have drifted apart.

## When to use
- `update_database` or `import_configuration_from_xml` fails with "object file does not exist - /Subsystems/X.mdo; /Roles/Y.mdo; ..." - the object lives in the model and in `Configuration.mdo` but has no `.mdo` on disk.
- `get_project_errors` shows `md-reference-intergrity` warnings like "a lost reference is set in field X at position N" - `Configuration.mdo` still registers an object whose body was lost (a dangling/orphaned entry that `delete_metadata` cannot remove, because there is no object to delete).
- After a batch of older edits, you want to guarantee every metadata object's `.mdo` is actually written out before exporting or updating the database.

## What it does
1. Walks EVERY metadata top object of the configuration via the BM model (all kinds) and force-exports each object's `.mdo` under `src/`, so any object missing on disk is written out. The same per-object export path the write tools use, run over the whole list.
2. Integrity-checks `src/<TypeDir>/<Name>/<Name>.mdo` before AND after the export, reporting the set that was missing (the real desync) and anything still missing afterwards.
3. Scans the `Configuration`'s many-valued metadata reference collections (`subsystems`, `commonForms`, `webServices`, ...) for UNRESOLVED EMF PROXIES - entries that point at an object with no `.mdo` and no BM body - and, when `cleanDanglingReferences` is true, removes them and re-exports `Configuration.mdo`.

## Parameter details
- `projectName` (required) - the EDT project to re-synchronize.
- `cleanDanglingReferences` (default `true`) - remove the dangling/orphaned proxy entries from `Configuration.mdo`. Set `false` to only REPORT them (`danglingFound` + `danglingDetails`) without changing anything.
- `revalidate` (default `false`) - after the export, run a full clean build so stale validation markers refresh. Heavier; leave off for a fast export-only run.

## What you get
JSON: `success`, `objectsExported`, `totalTopObjects`, `missingBeforeCount` + `missingBefore` (the real desync), `stillMissingCount` + `stillMissing` (anything not fixed), `cleanDanglingReferences`, `danglingFound`, `danglingRemovedCount`, `danglingRemoved` / `danglingDetails` (`{field, lostFqn, position}` per entry), an optional `danglingWarning` / `revalidateWarning`, and a human-readable `message`.

## Notes & gotchas
- Read-safe and idempotent: when the project is already in sync it just re-exports (no model change) and reports `missingBeforeCount: 0` / `danglingFound: 0`. Running it twice is harmless.
- Proxy detection reads references WITHOUT resolving them (`eGet(ref, false)`) and only treats an entry as dangling when it is a genuinely unresolvable proxy (a not-yet-loaded but PRESENT object stays); a valid reference is never removed.
- Types with no `src/` directory layout (Language, Style, the Configuration root) are skipped, not reported as missing.
- After it returns, re-check with `get_project_errors` / `get_problem_summary`; the `md-reference-intergrity` warnings should be gone and `update_database` / `export_configuration_to_xml` should unblock.
