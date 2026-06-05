# EDT-MCP — code conduct (the minefield map)

This file is about **what NOT to do** and **where to stop and think twice**. For agents and humans. Claude Code loads it automatically at the start of a session.

"How to do it right" lives in the skills under `.claude/skills/` (`edt-mcp-architecture`, `edt-mcp-bilingual`, `edt-mcp-tool-conventions`, `edt-mcp-new-tool`, `edt-mcp-build-test`). This file is only the prohibitions and danger zones.

> **Prime directive.** The project is mid-refactor toward shared helpers. Write new code against the **target** architecture (the skills), do NOT copy the existing duplication. Do not grow the debt.

---

## ❌ Hard don'ts (violating these = a bug or corruption)

1. **Touch the model only inside a transaction boundary.** Never read the model in a write task, never mutate outside a write task. A real bug of this class already happened: `get_project_errors` read markers outside a read transaction (fixed in `25d7851`). Reads go in a read boundary, writes in a write boundary.
2. **The metadata synonym is keyed by the language CODE** (`getLanguageCode()` → `"ru"`/`"en"`), **never** by `getDefaultLanguage().getName()` (that returns the name "Russian"/"Русский" — it misses the EMap and silently breaks on a multi-language configuration). Reference impl: `MetadataLanguageUtils.resolveLanguageCode` (the shared resolver used by `create_metadata` / `modify_metadata` / the metadata formatters).
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
| BM write tools (`create_metadata`/`modify_metadata`/`delete_metadata` — all FQN-addressed — + `rename_metadata_object`) | Model mutation + transactions + cascade. | Check the transaction boundary and reversibility. |
| `update_database`, `delete_metadata` (FQN-addressed, confirm-preview), `delete_project` | Destructive / irreversible (DB update / object delete / project removal). | Only on an explicit user request. |
| `clean_project` | A rebuild/revalidation — discards UNSAVED model changes (recoverable, NOT destructive; `destructiveHint=false`). | Save unsaved edits first; otherwise safe to run. |
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
- **Tool behaviour against a live EDT** → the automated **black-box e2e suite** at `tests/e2e/` — one `test_<tool>.py` per tool, git-fixture isolation, happy + negative + error-quality, anti-cheat. All 61 tools are covered (a coverage ratchet fails the suite if a tool has none). Adding/editing a test, or adding a tool? Read the **`edt-mcp-e2e-testing`** skill (full guide `tests/e2e/SKILL.md`; the `edt-skill-router` hook auto-suggests it when you touch `tests/e2e/`). Run: `python tests/e2e/run_all.py --project TestConfiguration` (needs a live `:8765`). NB it surfaced real tool bugs → fix-cards (e.g. metadata-write doesn't persist to disk).

---

## 🔨 Build & validate locally (you CAN compile — do it before claiming "done")

This is a Maven/Tycho project under `mcp/`. **A local build is available** — use it to validate changes; do not assume "compile is verified only by review/grep." The full reference is README "Building from source" (and the `edt-mcp-build-test` skill).

- **One command** (compile + unit tests, same as CI): from the repo root
  `bash source/compile.sh` — or to skip Surefire: `bash source/compile.sh --skip-tests`.
- The toolchain is **not on `PATH`** by default; pass it explicitly (paths are environment-specific — discover them, don't hardcode into committed files):
  `bash source/compile.sh --java-home "<JDK17 home>" --maven-home "<maven home>"`.
  CI itself runs `mvn clean verify --batch-mode -T 1C` in `mcp/` on JDK 17.
- **First build is slow** (Tycho pulls the EDT p2 repo from `edt.1c.ru` + Eclipse SDK, hundreds of MB); once the `~/.m2/repository/p2` + `.cache/tycho` caches exist, it runs in ~1 minute. If those caches are absent and there's no network, the build legitimately can't run — say so rather than fake it.
- **Tests need the target platform too** (Mockito/JUnit come from the p2 target, not plain Maven Central) — a green `compile.sh` run is what actually proves Java changes; greps only catch anchor/text problems.
- **Still unrunnable locally without EDT:** the `run_e2e_tests.py` e2e suite needs a live EDT workbench + MCP server. The formatter/synonym and error-shape contracts are e2e-only — those stay "verify in EDT."

---

## 🔁 Live e2e / redeploy loop (validate runtime + schema against a running MCP)

Build + unit tests prove Java logic; **runtime behaviour, the `tools/list` schema, and the MCP wire contract are only proven against a live EDT + running MCP server** (`:8765`). Anything that changes a tool's schema, description, response, or behaviour must be live-checked, not just built. The loop is encapsulated in a redeploy script (e.g. `edt-redeploy.ps1`) — its paths are environment-specific, do NOT hardcode them into committed files.

1. **Test against a non-elevated COPY of EDT**, never the `Program Files` install (that one is elevated → swap/relaunch triggers UAC). Copy EDT once into a writable folder + use a dedicated workspace; always redeploy/relaunch that copy.
2. **Per change:** `compile.sh` → **kill EDT** (`taskkill /IM 1cedt.exe /T /F`; also `1cv8.exe` if an infobase is running) → **swap** the freshly built bundle jar into `<edt-copy>/plugins/` AND patch `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` (Tycho stamps a new qualifier each build, so the jar filename changes) → **relaunch with `-clean`** (forces OSGi to reload) → wait for `:8765` → run live checks → on completion, kill EDT (and the infobase).
3. **The redeploy script exits 1 even on success** — the real signal is the log line `MCP server UP on 8765`. Do not treat exit 1 as failure.
4. **Redeploy WITHOUT a `-Build` flag only swaps the LAST built jar** — run `compile.sh` first (or pass `-Build`), else you ship stale code.
5. **Tycho p2 qualifier collision is real (hit 2026-06-03):** two builds can produce the SAME bundle qualifier and the p2 repository then ships a STALE cached jar even though compilation was fresh. **Verify the DEPLOYED jar actually contains your change** — `unzip -p <jar> path/To/Class.class | grep <new-literal>` (a plain `grep` on the `.jar` is useless: it's a compressed zip). If stale: **commit first** (changes the jgit qualifier) then rebuild, or clear the tycho/p2 cache.
6. **Infobase-dependent tools** (debug / run / YAXUnit / profiling): start the infobase (or a `debug_launch`) before those checks; terminate it and EDT when done.
7. **Testing token auth:** set `mcpAuthToken` in the workspace prefs (`<workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/com.ditrix.edt.mcp.server.prefs`) and relaunch (the token is read per-request), probe with `Authorization: Bearer <token>`, then restore an empty token + relaunch so the dev server returns to no-auth.
8. **Inspect real payloads with `Invoke-RestMethod`** (PowerShell), not `curl` (curl mangles nested JSON quoting). JSON-responseType tools put data in `result.structuredContent`, while `content[0].text` is just a `Done`/`Error` placeholder.

---

## 🤖 For agents (specifically)

- **Verify the class/method/helper actually exists** before referencing or calling it. During the review, agents invented non-existent classes — `grep`/`Read` before asserting.
- **Do not present an undone refactor as fact.** Describe the target state as target; the current code may still duplicate.
- **No drive-by "I'll tidy everything" edits.** The refactor proceeds one topic at a time.
- **Destructive actions (metadata rename/delete, update_database, delete_project) — only on an explicit request.**

---

## Where to look next

- Detailed "how to do it right" — the skills in `.claude/skills/` (some are auto-suggested by the `.claude/hooks/edt-skill-router.js` hook when editing `tools/impl`/metadata/tags/groups, or `tests/e2e/` → `edt-mcp-e2e-testing`).
