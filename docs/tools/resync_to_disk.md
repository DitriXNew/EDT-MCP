# resync_to_disk

Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files and report BM-to-disk desync. Walks EVERY top metadata object of the configuration (all kinds) and force-exports each object's .mdo, so objects that exist in the model / Configuration.mdo but have no .mdo on disk are written out. Fixes 'object file does not exist' failures from update_database / XML import caused by an accumulated desync. Read-safe and idempotent: when already in sync it re-exports harmlessly and reports 0 missing. Also CLEANS dangling/orphaned references in Configuration.mdo (unresolved proxies shown by get_project_errors as md-reference-intergrity 'lost reference' warnings that block update_database / XML import), reporting danglingFound + danglingRemovedCount (cleanDanglingReferences, default true; idempotent). Full parameters and examples: call get_tool_guide('resync_to_disk').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| cleanDanglingReferences | — | boolean | When true (default), remove dangling/orphaned references from Configuration.mdo - entries that register an object with no .mdo and no BM body (unresolved proxies), the source of md-reference-intergrity 'lost reference' warnings that block update_database / XML import. Set false to only report danglingFound without removing anything. |
| revalidate | — | boolean | When true, schedule a full project revalidation (clean build) after the export so stale markers refresh. Default: false (export only). |

## Guide
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

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
