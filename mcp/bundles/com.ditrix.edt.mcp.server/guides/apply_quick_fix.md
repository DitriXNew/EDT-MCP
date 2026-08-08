Applies EDT's **official quick-fix** (auto-fix) to a single validation marker — the headless
counterpart of the **Quick Fix** action in the EDT *Problems* view. It runs the platform's own fix
through `IFixManager` (prepare → list applicable variants → select → execute → finish), so the result
is exactly what the IDE would produce.

## When to use
- After `get_project_errors` flags a problem whose **Fix registered** column says `yes` — apply the
  official fix instead of hand-editing the source. That column means the CHECK TYPE has a fix
  registered, not a promise this exact marker will produce one — this tool's own context-specific
  filtering can still report "no quick-fix available" for a particular occurrence; just try it.
- Iterating a clean-up loop: `get_project_errors` (responseFormat=detailed) → `apply_quick_fix` →
  re-run `get_project_errors` to confirm the marker is gone (and pick up any follow-up markers).

## How the marker is addressed (no opaque id)
EDT validation markers have **no stable per-marker id**, so the tool addresses the marker by the same
**locator** `get_project_errors` prints:
- `checkId` (required) — the row's **Check code** (symbolic id like `doc-comment-parameter-section`, or
  the short UID). Matched by **exact** case-insensitive equality against either — not a substring: this
  tool mutates the model, so a loose needle like `doc` (which would happily substring-match several
  unrelated checks in `get_project_errors`) could silently auto-fix the wrong one here.
- `modulePath` (optional) — the row's **Module path** (e.g. `CommonModules/MyModule/Module.bsl`), to
  narrow when the same check fires in several modules.
- `line` (optional) — the row's **Line**.

The tool streams the project's markers, filters to the locator, and sorts the result into a
**deterministic order** (module path, then line, then check id, then message) before indexing it — the
underlying marker stream makes no ordering promise, so this keeps a given `index` pointing at the same
marker across calls, independent of stream iteration order. When the locator still matches **several**
markers (e.g. two parameter-doc problems on the same line), the error lists them with a 1-based index —
re-call adding `index=<n>`. When the chosen marker's fix offers **several variants** (e.g. "add to
Public region" vs "Private"), the error lists those — re-call adding `variant=<n>`.

## Parameter details
- `projectName` (required) — the EDT project the marker belongs to.
- `checkId` (required) — see above.
- `modulePath`, `line` (optional) — narrow the locator to a BSL position.
- `index` (optional) — 1-based selector among markers that share the locator. Validated strictly against the CURRENT match count when supplied - out of range is rejected even against a single match, so a stale index from an earlier response can never silently apply to the wrong marker.
- `variant` (optional) — 1-based selector among the chosen fix's variants. Same strict validation as `index`.

## What you get
A JSON result:
- `success` — `true` when the fix was applied.
- `checkId` — the marker's check.
- `location` — where the fix landed (`module:line`, or the check id for an object-level marker).
- `appliedVariant` — the description of the fix variant that ran.
- `message` — a human-readable summary.

## Notes & gotchas
- **Not every check has a fix.** Many validations are advisory (style/structure) with no registered
  auto-fix; the **Fix registered** column in `get_project_errors` tells you up front which check TYPES
  have one. That is a type-level flag, not a per-marker guarantee — even a registered check can still
  turn out inapplicable to a particular occurrence, and this tool returns the same clear "no quick-fix
  is available …" error for both cases — fix those by hand via `write_module_source` / `modify_metadata`.
- **No match** → "No marker matches check '…'": the locator hit nothing. Re-read `get_project_errors`
  (responseFormat=detailed); line numbers and the marker set change after each edit/rebuild.
- The fix **mutates the source** through the platform's own change processor; re-validate afterwards to
  see the updated marker list. There is no dry-run — inspect the marker (and `get_check_description`)
  first if unsure.
- **Excluded from the read-only presets.** The *Analysis Only* and *Code Review* Tools-tab presets
  disable this tool alongside the other write-capable ones, even though it sits in the `PROBLEMS` group
  next to read-only tools like `get_project_errors`. An installation that saved one of those presets
  before this tool existed is migrated once, on upgrade, to disable it there too.

## Maintainer note
After adding/changing this tool, the `tools/list` golden snapshot (`tools_list.golden.json`) MUST be
regenerated against the live server on the EDT stand — it cannot be hand-edited.
