# EDT-MCP — code conduct (the minefield map)

This file is about **what NOT to do** and **where to stop and think twice**. For agents and humans. Claude Code loads it automatically at the start of a session.

"How to do it right" lives in the skills under `.claude/skills/` (`edt-mcp-architecture`, `edt-mcp-bilingual`, `edt-mcp-tool-conventions`, `edt-mcp-new-tool`, `edt-mcp-build-test`). This file is only the prohibitions and danger zones.

> **Prime directive.** The project is mid-refactor toward shared helpers. Write new code against the **target** architecture (the skills), do NOT copy the existing duplication. Do not grow the debt.

---

## ❌ Hard don'ts (violating these = a bug or corruption)

1. **Touch the model only inside a transaction boundary.** Never read the model in a write task, never mutate outside a write task. A real bug of this class already happened: `get_project_errors` read markers outside a read transaction (fixed in `25d7851`). Reads go in a read boundary, writes in a write boundary.
2. **The metadata synonym is keyed by the language CODE** (`getLanguageCode()` → `"ru"`/`"en"`), **never** by `getDefaultLanguage().getName()` (that returns the name "Russian"/"Русский" — it misses the EMap and silently breaks on a multi-language configuration). Reference impl: `CreateMetadataObjectTool.resolveLanguage`.
3. **Do not hardcode `"ru"`** as the language fallback. Use the code of the first configured language.
4. **Do not add more hand-rolled resolution.** Project/configuration/module resolution and BM access are already copy-pasted dozens of times — do not add yet another `ResourcesPlugin.getWorkspace()...` copy. Metadata type/object resolution goes through the existing `MetadataTypeUtils` (it is the shared bilingual resolver — do NOT rewrite it). Shared `ProjectContext`/`BmTransactions` are being introduced by the refactor — if they don't exist yet, leave a TODO, but do not copy the old boilerplate.
5. **`tools/impl/` holds `IMcpTool` classes only.** No utilities or abstract bases there (use `utils/`, `tools/base/`).
6. **Every parameter read in `execute()` must be declared in `getInputSchema()`** (otherwise it is invisible to schema-driven clients), and vice versa.
7. **Cyrillic in regexes goes through `\uXXXX`**, not raw UTF-8 literals (risk of corruption under a non-UTF-8 Tycho build). Reference: `BslSyntaxChecker`.
8. **Errors from new code go through `ToolResult.error(...)`**, not a bare `"Error: …"` string and not a thrown exception escaping the tool.
9. **Escape markdown table cells.** An unescaped `|` / newline breaks the table (this is a real bug in several tools). Use the shared table builder.

---

## 🛑 "Stop and think twice" zones (large blast radius)

| Where | Why it's dangerous | Do this before editing |
|---|---|---|
| `RenameMetadataObjectTool` | **Cascading edits across the whole configuration** — BSL code, forms, metadata (README:50). A mistake = mass corruption. | Run on a test configuration; verify the cascade scope; don't run it without an explicit request. |
| BM write tools (`Create`/`Add`/`Delete`/`Rename`Metadata) | Model mutation + transactions + cascade. | Check the transaction boundary and reversibility. |
| `update_database`, `clean_project`, `delete_metadata_object` | Destructive / irreversible. | Only on an explicit user request. |
| `McpServer` (~1000 lines) | Transport + SSE + interruption + tool registry are tangled together. | Change one responsibility without touching the others. |
| `Activator` | Service-locator hub + static logging — almost everything depends on it. | Be careful with init/dispose order and signatures. |
| `tags/*` ↔ `groups/*` | Mirror stacks with no shared base: a change in one is almost always needed in the other. | Change both features in sync (until the shared base is extracted). |
| Form rendering (`get_form_screenshot`, `get_form_layout_snapshot`) | Depends on a JVM flag and the native/Java render mode. **A blank result ≠ a code bug.** | Check the memory/skill about the JVM flag and render mode before "fixing" it. |
| Metadata formatter layer (`tools/metadata/*`) | The output contract (the synonym table) is verified **only** by e2e (`run_e2e_tests.py:596` `_assert_synonym_language_code`). | If you change the format, update/run e2e or you silently break it. |

---

## 🌐 Two languages (ru/en) — the main recurring mine

1C is bilingual at several layers; most bugs live here. Any change to resolution/reading/writing/searching of metadata or code: go through the checklist in the **`edt-mcp-bilingual`** skill. In short:
- Synonym is keyed by language code (see don't #2). An object name resolves by its programmatic `Name`, **not** by the synonym; only the TYPE token (Справочник/Catalog) may be bilingual.
- `search_in_code` is **literal**, not dialect-aware: searching an English keyword won't find its Russian equivalent. For identifiers, use the AST tools.
- 1C queries are bilingual; the platform parser is dialect-aware — do not assume a single dialect.

---

## ✅ Before you write code

1. **Is there already a shared helper?** `grep` under `utils/`; `MetadataTypeUtils` is the shared bilingual resolver. Don't write the 47th copy.
2. **What's the canonical parameter/error/output?** → `edt-mcp-tool-conventions` (`projectName`, `modulePath`, `fqn`, `limit/offset`; errors via `ToolResult.error`).
3. **Is this bilingual?** → `edt-mcp-bilingual`.
4. **Is it a god-class / cascade / mirror feature?** → the "think twice" section above.
5. **A new tool?** → `edt-mcp-new-tool` (contract, schema, registration, README, test).

---

## 🧪 Tests — the mandatory minimum

- Changed metadata/code resolution → **a test for both languages** (English `Name`, Russian `Name`, synonym). Reference: `WriteModuleSourceToolTest.testResolveRussianObjectName`.
- New/changed tool → an `XxxToolTest` (argument validation; for metadata/code, a bilingual case).
- Right now ~42 of ~58 tools have no unit test — **do not grow** that debt.

---

## 🤖 For agents (specifically)

- **Verify the class/method/helper actually exists** before referencing or calling it. During the review, agents invented non-existent classes — `grep`/`Read` before asserting.
- **Do not present an undone refactor as fact.** Describe the target state as target; the current code may still duplicate.
- **No drive-by "I'll tidy everything" edits.** The refactor proceeds one topic at a time.
- **Destructive actions (metadata rename/delete, update_database, clean_project) — only on an explicit request.**

---

## Where to look next

- Detailed "how to do it right" — the skills in `.claude/skills/` (some are auto-suggested by the `.claude/hooks/edt-skill-router.js` hook when editing `tools/impl`/metadata/tags/groups).
