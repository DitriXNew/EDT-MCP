# resync_to_disk

Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files and report BM-to-disk desync. Walks EVERY top metadata object of the configuration (all kinds), reports the objects whose .mdo is missing on disk (missingBefore), and force-exports that missing subset so the files are restored (fullExport=true re-exports every object instead). Fixes 'object file does not exist' failures from update_database / XML import caused by an accumulated desync. Dangling/orphaned references in Configuration.mdo (unresolved proxies shown by get_project_errors as md-reference-intergrity 'lost reference' warnings that block update_database / XML import) are REPORTED by default (danglingFound + danglingDetails); set cleanDanglingReferences=true to REMOVE them - destructive: rewrites Configuration.mdo. Full parameters and examples: call get_tool_guide('resync_to_disk').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| cleanDanglingReferences | — | boolean | When true, REMOVE dangling/orphaned references from Configuration.mdo - entries that register an object with no .mdo and no BM body (unresolved proxies), the source of md-reference-intergrity 'lost reference' warnings that block update_database / XML import. Destructive: rewrites Configuration.mdo. Default: false - only report danglingFound + danglingDetails without changing anything. |
| fullExport | — | boolean | When true, force-export EVERY metadata top object's .mdo (a full disk refresh; slow on a large configuration - the export runs on the UI thread). Default: false - export only the objects whose .mdo is missing on disk. |
| revalidate | — | boolean | When true, schedule a full project revalidation (clean build) after the export so stale markers refresh. Default: false (export only). |

## Guide
Bulk re-synchronize the in-memory EDT model to the on-disk `src/` `.mdo` files, and report (optionally remove) dangling/orphaned references in `Configuration.mdo`. Use it to fix `update_database` / XML-import failures that complain about missing object files or "lost reference" warnings, when the BM model and disk have drifted apart.

## When to use
- `update_database` or `import_configuration_from_xml` fails with "object file does not exist - /Subsystems/X.mdo; /Roles/Y.mdo; ..." - the object lives in the model and in `Configuration.mdo` but has no `.mdo` on disk.
- `get_project_errors` shows `md-reference-intergrity` warnings like "a lost reference is set in field X at position N" - `Configuration.mdo` still registers an object whose body was lost (a dangling/orphaned entry that `delete_metadata` cannot remove, because there is no object to delete).
- After a batch of older edits, you want to guarantee every metadata object's `.mdo` is actually written out before exporting or updating the database (use `fullExport=true` for that full refresh).

## What it does
1. Walks EVERY metadata top object of the configuration via the BM model (all kinds) and computes which of them have no `.mdo` under `src/` - the `missingBefore` set, the real desync.
2. Force-exports ONLY that missing subset (the same per-object export path the write tools use), so the absent files are restored without re-serializing the whole configuration on the UI thread. `fullExport=true` re-exports every object instead (a full disk refresh; slow on a large configuration).
3. Integrity-checks `src/<TypeDir>/<Name>/<Name>.mdo` before AND after the export, reporting the missing set and anything still missing afterwards.
4. Scans the `Configuration`'s many-valued metadata reference collections (`subsystems`, `commonForms`, `webServices`, ...) for UNRESOLVED EMF PROXIES - entries that point at an object with no `.mdo` and no BM body - and REPORTS them (`danglingFound` + `danglingDetails`). Only when `cleanDanglingReferences=true` does it also REMOVE them and re-export `Configuration.mdo` - a destructive opt-in that rewrites `Configuration.mdo`.

## Parameter details
- `projectName` (required) - the EDT project to re-synchronize.
- `cleanDanglingReferences` (default `false` - report-only) - set `true` to REMOVE the dangling/orphaned proxy entries from `Configuration.mdo`. Destructive: rewrites `Configuration.mdo`. The default run only reports them (`danglingFound` + `danglingDetails`) and changes nothing.
- `fullExport` (default `false`) - set `true` to force-export EVERY metadata top object's `.mdo` instead of only the missing subset. Heavier (the export runs on the UI thread); use it for a deliberate full disk refresh.
- `revalidate` (default `false`) - after the export, run a full clean build so stale validation markers refresh. Heavier; leave off for a fast run.

## What you get
JSON: `success`, `objectsExported` (the missing subset by default; every object with `fullExport=true`), `totalTopObjects`, `fullExport` / `revalidate` / `cleanDanglingReferences` (the request flags echoed back), `missingBeforeCount` + `missingBefore` (the real desync), `stillMissingCount` + `stillMissing` (anything not fixed), `danglingFound`, `danglingRemovedCount`, `danglingRemoved` / `danglingDetails` (`{field, lostFqn, position}` per entry), an optional `danglingWarning` / `revalidateWarning`, and a human-readable `message`.

## Notes & gotchas
- With default parameters the run writes nothing on an in-sync project: the missing set is empty, so there is nothing to export, the dangling scan is report-only, and the report shows `missingBeforeCount: 0` / `danglingFound: 0`. Running it twice is idempotent.
- `cleanDanglingReferences=true` is DESTRUCTIVE: it removes the dangling entries from the model and rewrites `Configuration.mdo`. Run the default report-only mode first and review `danglingDetails` before opting in.
- The removal is reported (`danglingRemovedCount` / `danglingRemoved`) only after the BM transaction actually committed; if the write task fails, the response carries `danglingWarning` and claims no removal.
- Proxy detection reads references WITHOUT resolving them (`eGet(ref, false)`) and only treats an entry as dangling when it is a genuinely unresolvable proxy (a not-yet-loaded but PRESENT object stays); a valid reference is never removed.
- Types with no `src/` directory layout (Language, Style, the Configuration root) are skipped, not reported as missing.
- After it returns, re-check with `get_project_errors` / `get_problem_summary`; once the dangling entries are removed the `md-reference-intergrity` warnings should be gone and `update_database` / `export_configuration_to_xml` should unblock.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
