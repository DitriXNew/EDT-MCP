---
name: edt-mcp-e2e-testing
description: How to write/run the AUTOMATED black-box e2e suite (tests/e2e/) that covers every EDT-MCP tool against a live server with git-fixture isolation, happy + negative + error-quality coverage, and an anti-cheat bar. Use when adding/editing a test in tests/e2e/, adding a new tool (the coverage ratchet requires a test), or running the suite. (For the older MANUAL per-tool reference checklists see edt-mcp-testing; for Java unit/build see edt-mcp-build-test.)
---

# EDT-MCP — automated black-box e2e suite (tests/e2e/)

> **The full, authoritative, always-current guide is `tests/e2e/SKILL.md` in the repo. READ IT before writing or running a test** — it has the harness API, the worked examples, and the anti-cheat anti-pattern list. This skill is the entrypoint + the rules in brief; the repo file wins on any disagreement (it lives with the harness).

## What this is
A Python (stdlib-only) **black-box** suite: a real MCP client hits the **live** server on `:8765` and asserts the **real** effect. One file per tool: `tests/e2e/tools/test_<tool>.py`. Shared base: `harness.py`. Runner: `run_all.py` (serial, `--junit-xml`, per-test timeout). Every registered tool covered (62 today; the coverage ratchet `test_coverage_ratchet.py` fails the suite if a `tools/list` tool has no test). Different layer from Java/JUnit (which is headless and can't touch a real project).

## Run it
```
python tests/e2e/run_all.py --project TestConfiguration --junit-xml tests/e2e/e2e-results.xml
```
Needs a LIVE server on `:8765` + `TestConfiguration` loaded (the dev EDT copy — see the project memory / edt-redeploy loop). Execution is **serial** (one fixture, one git tree); authoring can be parallel (one agent per tool file). `--filter <substr>` runs a subset.

## Launch-preset wire checks

For `EDT_MCP_TOOL_PRESET=analysis-only` or `code-review`, restart EDT for each value and
pass the same variable to `python -m tools.test_preset_launch` from `tests/e2e`. Set
`EDT_MCP_PROGRESSIVE_DISCLOSURE=false` on EDT. IDs are case-insensitive and trimmed.
The override is resolved once on first use, including bridge calls and manual starts without
early startup; stored Tools preferences are untouched by resolution and registry reads,
including migration state. Predefined override IDs are `all-tools`, `analysis-only`,
`code-review`, and `development`. Unknown IDs produce one WARNING listing only those IDs and
the stored-selection hint, then use stored settings. Unset/blank values and `custom` use the
stored selection; explicit `custom` logs one INFO line explaining that there is no launch override.
The Tools tab loads saved settings with their normal migrations and shows a read-only launch
note. Unchanged OK/Apply preserves the saved selection and preset ID; deliberate edits apply
on a launch without the override. Use a fresh workspace for each CI preset launch; wire checks
prove the catalogue, and unit tests prove that the override is not persisted.
The module skips without a selected read-only preset. Use its direct entry point: the full runner's mandatory
`clean_project` setup/cleanup is disabled by these presets. See the launch-preset section
of `tests/e2e/SKILL.md` and `tests/TESTING.md` for the wire assertions and clean-fixture recipe.

Use `harness.call()` for tool calls: it validates successful `structuredContent` against output schemas cached from `tools/list`. Treat a schema failure as a test failure; keep disk and model assertions.

## The rules in brief (details in tests/e2e/SKILL.md)
1. **One file per tool**, `tests/e2e/tools/test_<tool>.py`, importing ONLY from `harness`. Adding a new MCP tool? add its test file — the **coverage ratchet** fails the suite otherwise.
2. **git-fixture isolation:** `TestConfiguration` is a committed fixture. The orchestrator resets it before every test (`git reset` + `git checkout HEAD --` + `git clean -fd`). Read tools must leave it clean (`assert_no_diff()`).
3. **Verify the effect the RIGHT way per `kind=`:**
   - `read` → `assert_no_diff()` + content.
   - `write` (file-write, write_module_source) → `assert_diff_contains()` (on disk).
   - `write-metadata` → **on-disk structure + MODEL READ-BACK.** For exports submitted by the call, assert bytes immediately BEFORE another MCP call; metadata writes and `dcs` share `WriteExportBarrier`. A drained queue is not proof of correct bytes. Poll only effects not covered by that submission (generic delete's own removed file, rename/build-external paths). Then read back the model. Use structural elements (`<name>X</name>`, `<catalogs>Type.X</catalogs>`), never bare names. The orchestrator owns fixture/model resets.
   - `action` → usually `assert_no_diff()` + status; never run a destructive happy mutation that corrupts the fixture.
   - **env-dependent** (form-render JVM flag, LanguageTool, debug no-session) → env-robust branch: real result OR a clear actionable **sentinel**.
4. **Negative coverage is MANDATORY** (not just happy): invalid path/object, every XOR/conditional/enum, missing-required, boundaries. Assert **error QUALITY** (`assert_error_quality`): names the bad value + actionable next step. A vague/non-actionable tool error → `# AUDIT:` note + a fix-card; **do NOT weaken the test** to a bad error.
5. **Anti-cheat (mutation thinking):** "would this test FAIL if the tool were broken?" If not, it's a CHEAT. No trivial asserts, no `assert_no_diff()`-only, no swallowed exceptions, no tolerant matching. A separate anti-cheat reviewer rules REAL/CHEAT.
6. **Infobase bindings:** use `snapshot_application_bindings(project)` from `harness` before and after refusals and writes; it reads the effective default and RuntimeClient IDs through MCP. Check after-state independently of `removedBindings`/`restoredBindings`. Global deregistration also needs read-back in another project sharing its UUID. Gate mutations that require a platform with `requires_live_infobase`; use mocked EDT services for reset/recovery/failure orders without a platform. The effective default can be inferred, so this snapshot alone does not prove an explicit default preference survived.

For order assertions use `metadata_collection_order` or `metadata_collection_state` (`sort_metadata` mode=list), then parse the exact `.mdo` sequence. Branch on the observed `autosort` block’s presence; validate its user-action message and exercise manual modes on an ungoverned collection and verify governed refusals.

## Focused contract checks
- Use `harness._post` for malformed JSON-RPC requests: missing/null/blank tool names yield `-32602`; nonblank unknown names yield `-32601`. `call()` requires a tool name and cannot express this matrix.
- History: force an error marker beyond the recorder's 20000-character cap, then check `status:error`, error filtering and stats. Capped bodies need not parse as JSON; background completion must preserve the delivered status and single row. Interrupt-slot overlap needs a unit test and a manual status-bar check.
- `validate_query`: cover saved whole-module/method scans in both dialects, continuation/quote coordinate mapping, dynamic/no-structure skips, empty scans, XOR inputs and missing methods. Create dedicated test modules; do not alter shared fixture modules and their search totals.
- `dcs`: assert empty `Template.dcs` and auto-filled fields/parameters on disk immediately after writing, then read back the model/XML. Cover all LocalString forms, both appearance locations and model-derived `options type=field` dictionaries. XML export tests must verify a new/rewritten root, not just nonempty output directories.

## Don't
- Don't add pip dependencies (stdlib only; the client is kept spec-conformant by hand on purpose).
- Don't hide an early-returning writer with polling or an intervening MCP call when its own export is covered by the barrier; assert the structural bytes immediately, paired with model read-back.
- Don't parallelize the RUN (serial only). Don't call `reset_fixture`/`reset_model` in a test (the orchestrator owns them).

## Protocol conformance (a separate gate)
This suite tests tool **business logic**. The MCP **protocol** itself (initialize handshake, capabilities/version negotiation, `Mcp-Session-Id`, `Accept`/`Content-Type`, `isError`, `ping`, SSE) is a different layer, checked by the official `modelcontextprotocol/conformance` suite — see `tests/conformance/` (`tests/conformance/README.md`). Run: `npx @modelcontextprotocol/conformance@latest server --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 --expected-failures tests/conformance/baseline.yml` → green when only the pinned (intentional) gaps fail. Don't mix the two gates.

Related: `edt-mcp-tool-conventions` (param/error/output canon), `edt-mcp-bilingual` (ru/en), `edt-mcp-build-test` (Java unit/build), `edt-mcp-testing` (manual per-tool checklists), `edt-mcp-ready-to-deploy` (final pre-merge checklist).
