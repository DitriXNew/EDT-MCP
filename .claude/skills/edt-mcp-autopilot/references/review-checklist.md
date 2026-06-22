# EDT-MCP review checklist (MUST-ENFORCE)

Reviewers (Phase 6) and the architect (Phase 4) apply this. Each line is a hard rule for this
plugin; a violation is a review problem. Pass the whole file to the build workflow as
`reviewChecklist`.

## Architecture & reuse
- Express metadata/form operations through the unified `create` / `modify` / `delete_metadata`
  tools with FQN addressing; do not add a new per-op tool for something that is an FQN.
- A genuinely new tool needs the full contract: `IMcpTool` impl + registration + toolset group +
  guide + golden entry + `docs/tools/` README + e2e `test_<tool>.py` + unit `<Tool>Test`.
- Reuse the canonical helpers (project/metadata/code/transaction/JSON/form/launch resolvers)
  instead of hand-rolling resolution or duplicating logic.
- `IMcpTool` classes live only in `tools/impl/`; shared logic in `utils/` or `tools/base/`.

## Model mutation & transactions
- Touch the model only inside transaction boundaries (reads in a read tx, writes in a write tx).
- Set state flags (`removed`, `persisted`, ...) only AFTER a successful commit/export, never before.
- Export changed metadata to disk after exiting the transaction; export the correct owning FQN.

## Bilingual safety (RU/EN) — top bug source
- Key synonyms by **language code** (`"ru"` / `"en"`), never by language name.
- Never hardcode `"ru"` as a fallback; use the first configured language's code.
- Resolve objects by programmatic `Name`; type tokens are bilingual; reuse the bilingual resolvers.
- Any metadata/code read/write/resolve/search change must be proven on BOTH the EN and RU paths.

## Unattended-safety
- No modal dialog that waits for a click; run long platform ops in a background `Job`.
- Auto-confirm known dialogs via the shared display filter; never pick a destructive default.
- Respect the user opt-out flags on launch tools (skip auto-confirm / session sweep when off).
- Never block the UI thread; bound every wait with a timeout that returns an actionable error.
- Report-only / preview by default; write-mode tools never claim read-safety.

## Wire contract & error shape
- Schema⇄execute parity: every parameter read in `execute()` is in `inputSchema` and vice versa.
- Parameter names are lowerCamelCase (a ratchet enforces this).
- Emit the same output fields across all branches (hit / timeout / error).
- A wire-field rename keeps a dual-emit deprecated alias for one release — never a hard rename.
- Error sentinel phrases are continuous substrings (e2e regex-matches them); route every error
  through the shared error result, actionable (name the bad value + the fix / sibling tool).
- Escape markdown table cells with the shared builder.

## Forms — strictly reflective
- No compile dependency on the form model package; resolve the `EPackage` from the global registry.
- Resolve enums by literal (not name) via the shared enum helpers.
- No bare `Class.forName()` for non-imported packages; get types from already-resolved `Method`s.
- Fill only unset features; mirror the designer defaults by platform `Version`.
- Form auto-element names come from the project's script variant, never hardcoded.
- Screenshot/snapshot: open + wait for the specific form editor + verify FQN; never the global
  active editor.

## Hygiene & release-readiness
- English-only code/comments/docs/error messages. Cyrillic only where it is real 1C/BSL DATA the
  code matches (type tokens, BSL keywords, example FQNs); use `\uXXXX` escapes in regexes.
- No internal traces in code/guides/docs: no internal issue/task numbers, spike codes, stand or
  host names, machine paths, or "live-verify" jargon.
- EOL = LF in `docs/tools/**` and golden files.
- Never claim "done" by review/grep alone; never present an undone refactor as fact.

## Ratchets (the build/e2e will fail otherwise)
- Every advertised tool has a unit `XxxToolTest` and an e2e `test_<tool>.py`.
- The `tools_list` golden matches; regenerate + review the diff when a tool's wire surface changes.

---

## Escalate to the human (do NOT decide autonomously)

These are "principal design questions" — surface them in `escalations` / post to the issue:
- Wire-contract change (new parameter shape, output structure, field/tool rename).
- Architecture choice (a new tool vs extending a unified one; a new shared abstraction).
- Bilingual semantics (how synonyms are keyed; cross-language token handling).
- Any breaking change (golden change a consumer depends on, formatter output order).
- Destructive operation (rename / delete metadata, update database, delete project) — only on
  explicit request.
- Ambiguous or under-specified requirement where two readings lead to different implementations.

Autonomy zone (no escalation): clear bug fixes that keep the external contract, added tests,
internal refactors with a stable public API, docs/comments.
