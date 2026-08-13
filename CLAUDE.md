# EDT-MCP — code conduct (the minefield map)

A 1C:EDT plugin (Maven/Tycho, Eclipse OSGi) exposing an MCP server that drives EDT. This file is **what NOT to do** and **where to stop and think twice**. Claude Code loads it automatically; the "how to do it right" lives in the skills.

> **Prime directive.** The project is mid-refactor toward shared helpers. Write new code against the **target** architecture (the skills), do NOT copy the existing duplication. Do not grow the debt.

> **Surface is English-only.** Tool descriptions, errors, READMEs, and skills are English — no Russian prose, no transliteration (Russian-in-Latin-letters is garbage). Cyrillic in code is fine where it is real 1C/BSL data the code matches or documents (type tokens, BSL keywords, example FQNs) — keep it, including in comments. Remove only redundant Russian-language glosses where the English already says it, and the `1С`→`1C` homoglyph (Cyrillic Es). Regexes use `\uXXXX` (don't #7).

---

## 📚 The skills (load the relevant one before working — `.claude/skills/`)

The `edt-mcp-*` skills carry the "how to do it right"; each one's description says when to load it, and the `.claude/hooks/edt-skill-router.js` hook auto-suggests the matching skill when you touch a file. **Wrapping up a piece of work is the one you must not skip: `edt-mcp-ready-to-deploy` is the final "definition of done" gate before declaring done or merging.**

---

## ❌ Hard don'ts (violating these = a bug or corruption)

1. **Touch the model only inside a transaction boundary.** Reads in a read boundary, writes in a write boundary (`BmTransactions.read/write`). A real bug of this class happened: `get_project_errors` read markers outside a read transaction (fixed in `25d7851`).
2. **The metadata synonym is keyed by the language CODE** (`getLanguageCode()` → `"ru"`/`"en"`), **never** by `getDefaultLanguage().getName()` (that returns the name "Russian" — it misses the EMap and silently breaks on a multi-language config). Reference: `MetadataLanguageUtils.resolveLanguageCode`.
3. **Do not hardcode `"ru"`** as the language fallback. Use the code of the first configured language.
4. **Do not add hand-rolled resolution.** Project/configuration/module resolution and BM access are copy-pasted dozens of times — don't add another `ResourcesPlugin.getWorkspace()...`. Use `ProjectContext.of(...)` / `AbstractMetadataWriteTool.resolveProjectAndConfig`; metadata type/object resolution goes through `MetadataTypeUtils` / `MetadataNodeResolver` (the shared bilingual resolvers — do NOT rewrite them).
5. **`tools/impl/` holds `IMcpTool` classes only.** No utilities or abstract bases there (use `utils/`, `tools/base/`).
6. **Every parameter read in `execute()` must be declared in `getInputSchema()`** (else it is invisible to schema-driven clients), and vice versa. Parameter names are **lowerCamelCase** (`ToolContractConsistencyTest` fails snake_case).
7. **Cyrillic in regexes goes through `\uXXXX`**, not raw UTF-8 literals (risk of corruption under a non-UTF-8 Tycho build). Reference: `BslSyntaxChecker`. Elsewhere, justified Cyrillic — real 1C type tokens / BSL keywords / 1C terms the code matches or documents — is fine, including in comments and string literals (`MetadataTypeUtils` type tokens, `JUnitMarkdownFormatter` YAXUnit frame tokens). Do NOT transliterate it and do NOT strip it; remove only redundant Russian glosses (where the English already says it) and the `1С`→`1C` homoglyph (Cyrillic Es).
8. **Errors go through `ToolResult.error(...).toJson()`** — not a bare `"Error: …"` string, not an exception escaping the tool. Make them actionable (name the bad value + the fix / sibling tool).
9. **Escape markdown table cells** (`MarkdownUtils` / the shared table builder). An unescaped `|` / newline breaks the table.

---

## 🛑 "Stop and think twice" zones (large blast radius)

| Where | Why it's dangerous | Do this before editing |
|---|---|---|
| `RenameMetadataObjectTool` | **Cascading edits across the whole configuration** — BSL, forms, metadata. A mistake = mass corruption. | Run on a test config; verify the cascade scope; only on an explicit request. |
| BM write tools (`create_metadata`/`modify_metadata`/`delete_metadata` — all FQN-addressed — + `rename_metadata_object` + `adopt_metadata_object`) | Model mutation + transactions + cascade + disk export. | Check the transaction boundary and reversibility; force-export the TOP object. |
| `update_database`, `delete_metadata` (confirm-preview), `delete_project` | Destructive / irreversible. | Only on an explicit user request. |
| `clean_project` | A rebuild/revalidation — discards UNSAVED model changes (recoverable, NOT destructive). | Save unsaved edits first; otherwise safe. |
| `McpServer` (~1000 lines) | Transport + SSE + interruption + tool registry tangled together. | Change one responsibility without touching the others. |
| `Activator` | Service-locator hub + static logging — almost everything depends on it. | Be careful with init/dispose order and signatures. |
| `tags/*` ↔ `groups/*` | Mirror stacks with no shared base. | Change both features in sync (until the shared base is extracted). |
| Form rendering (`get_form_screenshot`, `get_form_layout_snapshot`) | Depends on a JVM flag + native/Java render mode. **A blank result ≠ a code bug.** | Check the memory/skill about the JVM flag before "fixing" it. |
| Metadata formatter layer (`tools/metadata/*`) | The synonym-table output contract (keyed by language CODE) is verified **only** by e2e. | If you change the format, update/run the e2e tests or you silently break it. |

---

## 🌐 Two languages (ru/en) — the main recurring mine

1C is bilingual at several layers; most bugs live here. Any change to resolution/reading/writing/searching of metadata or code: go through the **`edt-mcp-bilingual`** skill. In short:
- Synonym is keyed by language code (don't #2). An object name resolves by its programmatic `Name`, **not** by the synonym; only the TYPE token (e.g. `Справочник`/`Catalog`) is bilingual.
- `search_in_code` is **literal**, not dialect-aware. For identifiers, use the AST tools.
- 1C queries are bilingual; the platform parser is dialect-aware — do not assume a single dialect.

---

## ✅ Before you write code

1. **Is there already a shared helper?** `grep` under `utils/` (`MetadataTypeUtils`, `MetadataNodeResolver`, `ProjectContext`, `BmTransactions`, `JsonUtils`, `Pagination`, `MarkdownUtils`). Don't write the 47th copy.
2. **What's the canonical parameter/error/output?** → `edt-mcp-tool-conventions`.
3. **Is this bilingual?** → `edt-mcp-bilingual`.
4. **God-class / cascade / mirror feature?** → the "think twice" section above.
5. **A new tool?** → `edt-mcp-new-tool`.

---

## 🧪 The testing cycle (four tiers — know which one proves what)

Each tier proves a different layer. A green lower tier does NOT prove the higher one. Do not claim "done" by review/grep alone.

- **Tier 1 — compile + unit tests.** `bash source/compile.sh` (the same flow as CI). Proves Java logic and the build ratchets: `BuiltInToolTestCoverageTest` (every tool has an `XxxToolTest`), `ToolContractConsistencyTest` (lowerCamelCase params), the e2e coverage ratchet. Mechanics + the toolchain/cache gotchas: `edt-mcp-build-test`.
- **Tier 2 — live redeploy.** The ONLY proof of a tool's schema/description/response/behaviour, and of the MCP wire contract. Run against a non-elevated COPY of EDT, never the `Program Files` install. Mechanics: `edt-mcp-testing` + `edt-mcp-build-test`; the anti-stale jar check: `edt-mcp-ready-to-deploy`.
- **Tier 3 — automated black-box e2e (`tests/e2e/`).** Real MCP client → live server → asserts the real effect. The formatter/synonym and error-shape contracts are **e2e-only** — they stay "verify in EDT". Mechanics: `edt-mcp-e2e-testing`.
- **Tier 4 — protocol conformance.** Validates the SERVER against the MCP wire spec; `tests/conformance/baseline.yml` pins the intentional gaps, so a new failure = a protocol regression. Mechanics: `edt-mcp-build-test`.

**Mandatory test minimum for a change:**
- Changed metadata/code resolution → a test for **both** languages (English `Name`, Russian `Name`, synonym). Reference: `WriteModuleSourceToolTest.testResolveRussianObjectName`.
- New/changed tool → an `XxxToolTest` (+ the `test_<tool>.py` e2e file).

> **When the whole change is done, run the `edt-mcp-ready-to-deploy` skill** — the ordered final gate (hygiene → tests written → build+unit → README → live redeploy → golden → full e2e → conformance → clean tree) that confirms everything still works before you call it done or merge.

---

## 🤖 For agents (specifically)

- **Verify the class/method/helper exists** before referencing or calling it — `grep`/`Read`, don't invent (agents have invented non-existent classes).
- **Do not present an undone refactor as fact.** Describe the target state as target; current code may still duplicate.
- **No drive-by "tidy everything" edits.** The refactor proceeds one topic at a time.
- **Destructive actions** (metadata rename/delete, `update_database`, `delete_project`) — only on an explicit request.
- **Commits are local by default** — don't push autonomously; on the default branch, branch first. Review every commit before making it.
- **Validating a PR = answering its comments, not just its code.** Every review comment and review thread gets a REPLY saying what was done (or why it was declined) — silence reads as "ignored". A thread whose point is actually fixed in the branch is then RESOLVED; a thread you disagreed with, deferred, or only partly addressed stays OPEN with the reason stated, so the author decides. Never resolve a thread you did not act on, and never resolve one by pushing a commit without replying. `gh pr view <n> --json reviews,comments` and `gh api .../pulls/<n>/comments` list them; replies go to `gh api repos/<o>/<r>/pulls/<n>/comments/<comment-id>/replies` (the `/pulls/comments/<id>/replies` form 404s); a thread is resolved through `gh api graphql` (`resolveReviewThread`), which needs the thread id from a `reviewThreads` query — `gh pr` alone cannot resolve.
- **Ask the PR reviewer for a review, aimed, in ONE SENTENCE.** After opening a PR and after each round of fixes, comment `@codex review` plus a single sentence naming the riskiest spot to attack — no essays, no lists, no recap of what you already verified. A blind "please review" spends the pass on style; a long brief buries the aim. One pointed sentence has repeatedly found real defects here, including a fix that silently disabled itself. Then answer and resolve every comment per the rule above, and re-trigger after the fixes.
- **A reviewer finding is a lead, not a verdict — check its work.** Its findings have been right about real bugs AND wrong about a "limitation" that the platform grammar disproved. Reproduce every claim against the code or EDT's sources before acting on it, and say in the reply what you checked; never fix on the reviewer's word alone, and never dismiss on your own. Wrong findings get a reasoned decline, not a silent one.
- **An open PR of yours must be SUBSCRIBED to.** As soon as you open or push to one, start a background watch on it and keep it running: report new review comments, new PR comments, and each CI verdict as they land, instead of polling by hand or discovering them a turn later. Unsubscribe on exactly two events — the PR is MERGED/CLOSED, or the user asks you to stop. A watch left unarmed is how a reviewer's finding sits unanswered.

---

## Where to look next

- "How to do it right" — the skills in `.claude/skills/` (auto-suggested by `.claude/hooks/edt-skill-router.js`).
- Build details — README "Building from source".
- Refactor backlog — `.devtool/features/*.md`.
