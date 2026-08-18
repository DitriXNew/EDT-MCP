# apply_quick_fix

Apply EDT's official quick-fix (auto-fix) to one validation marker — the headless counterpart of the 'Quick Fix' action in the problems view. Address the marker by the locator get_project_errors prints: its Check code (+ Module path + Line to narrow); its 'Fix registered' column flags rows whose CHECK TYPE has one (not a guarantee for that exact marker - this tool reports when none is applicable). When the locator matches several markers (or the fix has several variants) the error lists them, each with its location, and you re-call with index / variant. Full parameters and examples: call get_tool_guide('apply_quick_fix').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name the marker belongs to (required). |
| checkId | yes | string | Check id of the marker to fix (required): the 'Check code' from get_project_errors (symbolic id like 'doc-comment-parameter-section', or the short UID). Matched by EXACT case-insensitive equality against either - not a substring, since this mutates the model and a loose match could silently pick the wrong check. |
| modulePath | — | string | Narrow to a BSL module: the 'Module path' from get_project_errors (e.g. 'CommonModules/MyModule/Module.bsl'). Optional but recommended when the same check fires in several modules. |
| line | — | integer | Narrow to the 1-based 'Line' from get_project_errors. Optional; if supplied it must be >= 1 - 0 or negative is rejected rather than treated as 'omitted'. |
| index | — | integer | 1-based selector when the locator still matches several markers (the error lists them). Omit for a single match. If supplied, it must be >= 1 (0 or negative is rejected, never silently treated as 'omitted') and is validated strictly against the CURRENT match count and rejected when out of range - even against a single match - so a stale index from an earlier response is never silently applied to the wrong marker. |
| variant | — | integer | 1-based fix-variant index, required only when the chosen marker's fix exposes more than one variant (the error then lists them). If supplied, it must be >= 1 (0 or negative is rejected, never silently treated as 'omitted') and is validated strictly against the CURRENT variant count and rejected when out of range - even against a single variant - so a stale selector is never silently applied to the wrong fix. |

## Guide
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
**deterministic order** (module path, then line, then target object, then check id, then message)
before indexing it — the underlying marker stream makes no ordering promise, so this keeps a given
`index` pointing at the same marker across calls, independent of stream iteration order. That holds as
long as the candidates can actually be told apart; when two of them cannot be (see the refusal in
*Notes & gotchas*), the tool refuses rather than hand out an index it cannot honour. A chosen
marker's fix variants are ordered on the same principle (description, then details), so a given
`variant` likewise keeps meaning the same fix across calls. When the locator still matches **several**
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
- `location` — where the fix landed: `module:line` for a BSL marker, the target object (e.g.
  `Catalog.Products`) for an object-level one, and the check id when neither could be resolved.
- `appliedVariant` — the fix variant that ran, named the same way the ambiguity listing names it
  (its description, plus its details when those are what tell two variants apart).
- `message` — a human-readable summary.

## Notes & gotchas
- **Not every check has a fix.** Many validations are advisory (style/structure) with no registered
  auto-fix; the **Fix registered** column in `get_project_errors` tells you up front which check TYPES
  have one. That is a type-level flag, not a per-marker guarantee — even a registered check can still
  turn out inapplicable to a particular occurrence, and this tool returns the same clear "no quick-fix
  is available …" error for both cases — fix those by hand via `write_module_source` / `modify_metadata`.
- **Indistinguishable candidates** → "… markers match check '…', and at least two of them are
  object-level markers whose target object could not be resolved right now": object/metadata-level
  markers carry no module path, so their target object is the only thing naming and ordering them. When
  the model is momentarily unavailable and two of them resolve to nothing, no `index` can be trusted —
  and this tool mutates whatever it selects — so it refuses instead of guessing. Usually transient:
  retry in a moment. Narrowing with `modulePath` does NOT help here (these markers have none); if it
  persists, use `get_project_errors` (responseFormat=detailed) to see the affected objects and fix them
  via `modify_metadata`.
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

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
