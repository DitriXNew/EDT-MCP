# EDT-MCP — code conduct (the minefield map)

A 1C:EDT plugin (Maven/Tycho, Eclipse OSGi) exposing an MCP server (~62 tools) that drives EDT. This file is **what NOT to do** and **where to stop and think twice**. Claude Code loads it automatically; the "how to do it right" lives in the skills.

> **Prime directive.** The project is mid-refactor toward shared helpers. Write new code against the **target** architecture (the skills), do NOT copy the existing duplication. Do not grow the debt.

> **Surface is English-only.** Tool descriptions, errors, READMEs, and skills are English — no Russian prose, no transliteration (Russian-in-Latin-letters is garbage). Cyrillic in code is fine where it is real 1C/BSL data the code matches or documents (type tokens, BSL keywords, example FQNs) — keep it, including in comments. Remove only redundant Russian-language glosses where the English already says it, and the `1С`→`1C` homoglyph (Cyrillic Es). Regexes use `\uXXXX` (don't #7).

---

## 📚 The skills (load the relevant one before working — `.claude/skills/`)

The `.claude/hooks/edt-skill-router.js` hook auto-suggests these when you touch matching files.

| Skill | When to load it |
|---|---|
| `edt-mcp-architecture` | Starting work; deciding where new code belongs; the map of shared helpers + layering. |
| `edt-mcp-tool-conventions` | Editing any `tools/impl` class; parameter naming, error shape, output format (Markdown vs JSON), pagination. |
| `edt-mcp-bilingual` | Any read/write/resolve/search of metadata or BSL — the ru/en correctness checklist (the #1 bug source). |
| `edt-mcp-new-tool` | Adding/scaffolding a tool — the full contract: IMcpTool surface, schema, registration, MANIFEST, the two test ratchets, golden, README, the compile→review→redeploy→live contour. |
| `edt-mcp-build-test` | Building the plugin + running unit/e2e tests; the test conventions. |
| `edt-mcp-e2e-testing` | Writing/running the **automated** black-box suite `tests/e2e/` (one `test_<tool>.py` per tool). |
| `edt-mcp-testing` | **Manual** per-tool live validation — `references/<family>/<tool>.md`: exact call, expected result, gotchas. |
| `edt-mcp-yaxunit` | Writing/running YAXUnit 1C unit tests via `run_yaxunit_tests` / `debug_yaxunit_tests`. |
| `edt-mcp-ready-to-deploy` | **Wrapping up — the final "definition of done" gate.** Run when a piece of work is finished, before declaring it done or merging (hygiene → tests → build → README → live redeploy → golden → full e2e → conformance → clean tree). |

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

## 🧪 The testing cycle (three tiers — know which one proves what)

Each tier proves a different layer. A green lower tier does NOT prove the higher one. Do not claim "done" by review/grep alone.

**Tier 1 — compile + unit tests (Java logic + ratchets).**
- One command, same as CI: `bash source/compile.sh` (add `--skip-tests` to skip Surefire).
- The toolchain is **not on `PATH`** — pass it: `bash source/compile.sh --java-home "<JDK17 home>" --maven-home "<maven home>"`. CI runs `mvn clean verify --batch-mode -T 1C` in `mcp/` on JDK 17.
- **First build is slow** (Tycho pulls the EDT p2 repo + Eclipse SDK, hundreds of MB); ~1 min once `~/.m2/repository/p2` + `.cache/tycho` are warm. No caches + no network ⇒ it legitimately can't run — say so, don't fake green.
- Unit tests need the target platform (Mockito/JUnit come from the p2 target). A green `compile.sh` is the real proof for Java changes. Ratchets that fail the build: `BuiltInToolTestCoverageTest` (every tool has an `XxxToolTest`), `ToolContractConsistencyTest` (lowerCamelCase params), the e2e coverage ratchet.

**Tier 2 — live redeploy loop (runtime behaviour + `tools/list` schema + MCP wire contract).** Only this proves anything a tool's schema/description/response/behaviour. Encapsulated in a redeploy script (e.g. `edt-redeploy.ps1`; paths are environment-specific — do NOT hardcode them into committed files).
- **Test against a non-elevated COPY of EDT**, never the `Program Files` install (elevated → swap/relaunch triggers UAC). Copy EDT once into a writable folder + a dedicated workspace.
- **Per change:** `compile.sh` → **kill EDT** (`taskkill /IM 1cedt.exe /T /F`; also `1cv8.exe` if an infobase runs) → **swap** the freshly built bundle jar into `<edt-copy>/plugins/` AND patch `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` (Tycho stamps a new qualifier each build → the jar filename changes) → **relaunch with `-clean`** (forces OSGi reload) → wait for `:8765` → run live checks → kill EDT (and the infobase) when done.
- **The redeploy script exits 1 even on success** — the real signal is the log line `MCP server UP on 8765`. Don't treat exit 1 as failure.
- **Redeploy WITHOUT a `-Build` flag only swaps the LAST built jar** — run `compile.sh` first (or pass `-Build`), else you ship stale code.
- **Tycho p2 qualifier collision is real:** two builds can produce the SAME qualifier, and p2 then ships a STALE cached jar despite fresh compilation. **Verify the DEPLOYED jar contains your change** — `unzip -p <jar> path/To/Class.class | grep <new-literal>` (a plain `grep` on the `.jar` is useless — it's a compressed zip). If stale: **commit first** (changes the jgit qualifier) then rebuild, or clear the tycho/p2 cache.
- **If EDT wedges** (project stuck `building`, `clean_project` never returns, a per-test TIMEOUT): kill + `-clean` relaunch autonomously, then wait for projects `ready` before a full run.
- **Inspect payloads with `Invoke-RestMethod`** (PowerShell), not `curl` (curl mangles nested JSON). JSON-responseType tools put data in `result.structuredContent`; `content[0].text` is just a `Done`/`Error` placeholder.
- **Infobase-dependent tools** (debug / run / YAXUnit / profiling): start the infobase (or a `debug_launch`) first; terminate it + EDT when done.

**Tier 3 — automated black-box e2e (`tests/e2e/`).** Real MCP client → live server → asserts the real effect. One `test_<tool>.py` per tool; git-fixture isolation; happy + negative + error-quality; anti-cheat; a coverage ratchet fails the suite if a tool has none. Run: `python tests/e2e/run_all.py --project TestConfiguration` (needs a live `:8765`). Read `edt-mcp-e2e-testing` (full guide `tests/e2e/SKILL.md`) before adding/editing a test. The formatter/synonym and error-shape contracts are **e2e-only** — they stay "verify in EDT".

**Tier 4 — protocol conformance.** The official `modelcontextprotocol/conformance` suite validates the SERVER against the MCP wire spec (handshake, capabilities, session-id, `isError`, `ping`, SSE) — a separate layer from the e2e business-logic gate. `tests/conformance/` holds `baseline.yml` (the pinned intentional gaps) + `README.md` (the layer split). Run: `npx @modelcontextprotocol/conformance@latest server --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 --expected-failures tests/conformance/baseline.yml` → green when only the pinned gaps fail; a new failure = a protocol regression. CI: `.github/workflows/conformance.yml` (needs a self-hosted runner with EDT).

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

---

## Where to look next

- "How to do it right" — the skills in `.claude/skills/` (auto-suggested by `.claude/hooks/edt-skill-router.js`).
- Build details — README "Building from source".
- Refactor backlog — `.devtool/features/*.md`.
