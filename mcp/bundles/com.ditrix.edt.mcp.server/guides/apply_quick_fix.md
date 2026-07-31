## Guide

Applies EDT's **official quick-fix** (auto-fix) to a single validation marker — the headless
counterpart of the **Quick Fix** action in the EDT *Problems* view. It runs the platform's own fix
through `IFixManager` (prepare → list applicable variants → select → execute → finish), so the result
is exactly what the IDE would produce.

## When to use
- After `get_project_errors` flags a problem whose **Fix** column says `yes` — apply the official fix
  instead of hand-editing the source.
- Iterating a clean-up loop: `get_project_errors` (responseFormat=detailed) → `apply_quick_fix` →
  re-run `get_project_errors` to confirm the marker is gone (and pick up any follow-up markers).

## How the marker is addressed (no opaque id)
EDT validation markers have **no stable per-marker id**, so the tool addresses the marker by the same
**locator** `get_project_errors` prints:
- `checkId` (required) — the row's **Check code** (symbolic id like `doc-comment-parameter-section`, or
  the short UID). Matched case-insensitively against both.
- `modulePath` (optional) — the row's **Module path** (e.g. `CommonModules/MyModule/Module.bsl`), to
  narrow when the same check fires in several modules.
- `line` (optional) — the row's **Line**.

The tool streams the project's markers and selects the one matching that locator. When the locator
still matches **several** markers (e.g. two parameter-doc problems on the same line), the error lists
them with a 1-based index — re-call adding `index=<n>`. When the chosen marker's fix offers **several
variants** (e.g. "add to Public region" vs "Private"), the error lists those — re-call adding
`variant=<n>`.

## Parameter details
- `projectName` (required) — the EDT project the marker belongs to.
- `checkId` (required) — see above.
- `modulePath`, `line` (optional) — narrow the locator to a BSL position.
- `index` (optional) — 1-based selector among markers that share the locator.
- `variant` (optional) — 1-based selector among the chosen fix's variants.

## What you get
A JSON result:
- `success` — `true` when the fix was applied.
- `checkId` — the marker's check.
- `location` — where the fix landed (`module:line`, or the check id for an object-level marker).
- `appliedVariant` — the description of the fix variant that ran.
- `message` — a human-readable summary.

## Notes & gotchas
- **Not every check has a fix.** Many validations are advisory (style/structure) with no registered
  auto-fix; the **Fix** column in `get_project_errors` tells you up front which are fixable, and this
  tool returns a clear "no quick-fix is available …" error for the rest — fix those by hand via
  `write_module_source` / `modify_metadata`.
- **No match** → "No marker matches check '…'": the locator hit nothing. Re-read `get_project_errors`
  (responseFormat=detailed); line numbers and the marker set change after each edit/rebuild.
- The fix **mutates the source** through the platform's own change processor; re-validate afterwards to
  see the updated marker list. There is no dry-run — inspect the marker (and `get_check_description`)
  first if unsure.

## Maintainer note
After adding/changing this tool, the `tools/list` golden snapshot (`tools_list.golden.json`) MUST be
regenerated against the live server on the EDT stand — it cannot be hand-edited.
