---
name: edt-mcp-e2e-testing
description: How to write/run the AUTOMATED black-box e2e suite (tests/e2e/) that covers all 61 EDT-MCP tools against a live server with git-fixture isolation, happy + negative + error-quality coverage, and an anti-cheat bar. Use when adding/editing a test in tests/e2e/, adding a new tool (the coverage ratchet requires a test), or running the suite. (For the older MANUAL per-tool reference checklists see edt-mcp-testing; for Java unit/build see edt-mcp-build-test.)
---

# EDT-MCP — automated black-box e2e suite (tests/e2e/)

> **The full, authoritative, always-current guide is `tests/e2e/SKILL.md` in the repo. READ IT before writing or running a test** — it has the harness API, the worked examples, and the anti-cheat anti-pattern list. This skill is the entrypoint + the rules in brief; the repo file wins on any disagreement (it lives with the harness).

## What this is
A Python (stdlib-only) **black-box** suite: a real MCP client hits the **live** server on `:8765` and asserts the **real** effect. One file per tool: `tests/e2e/tools/test_<tool>.py`. Shared base: `harness.py`. Runner: `run_all.py` (serial, `--junit-xml`). All 61 tools covered (~452 tests) + `test_coverage_ratchet.py`. Different layer from Java/JUnit (which is headless and can't touch a real project).

## Run it
```
python tests/e2e/run_all.py --project TestConfiguration --junit-xml tests/e2e/e2e-results.xml
```
Needs a LIVE server on `:8765` + `TestConfiguration` loaded (the dev EDT copy — see the project memory / edt-redeploy loop). Execution is **serial** (one fixture, one git tree); authoring can be parallel (one agent per tool file). `--filter <substr>` runs a subset.

## The rules in brief (details in tests/e2e/SKILL.md)
1. **One file per tool**, `tests/e2e/tools/test_<tool>.py`, importing ONLY from `harness`. Adding a new MCP tool? add its test file — the **coverage ratchet** fails the suite otherwise.
2. **git-fixture isolation:** `TestConfiguration` is a committed fixture. The orchestrator resets it before every test (`git reset` + `git checkout HEAD --` + `git clean -fd`). Read tools must leave it clean (`assert_no_diff()`).
3. **Verify the effect the RIGHT way per `kind=`:**
   - `read` → `assert_no_diff()` + content.
   - `write` (file-write, write_module_source) → `assert_diff_contains()` (on disk).
   - `write-metadata` (create/add/delete/rename) → **MODEL READ-BACK + on-disk structure.** Call a read tool and assert the change in the model, AND assert it landed on disk via `poll_diff_contains` of the **structural element** (`<name>X</name>`, `<catalogs>Type.X</catalogs>` — not the bare name). Metadata writes now persist to disk via forceExport (~1-2s lag → poll). The orchestrator runs `reset_fixture`+`reset_model` after these.
   - `action` → usually `assert_no_diff()` + status; never run a destructive happy mutation that corrupts the fixture.
   - **env-dependent** (form-render JVM flag, LanguageTool, debug no-session) → env-robust branch: real result OR a clear actionable **sentinel**.
4. **Negative coverage is MANDATORY** (not just happy): invalid path/object, every XOR/conditional/enum, missing-required, boundaries. Assert **error QUALITY** (`assert_error_quality`): names the bad value + actionable next step. A vague/non-actionable tool error → `# AUDIT:` note + a fix-card; **do NOT weaken the test** to a bad error.
5. **Anti-cheat (mutation thinking):** "would this test FAIL if the tool were broken?" If not, it's a CHEAT. No trivial asserts, no `assert_no_diff()`-only, no swallowed exceptions, no tolerant matching. A separate anti-cheat reviewer rules REAL/CHEAT.

## Don't
- Don't add pip dependencies (stdlib only; the client is kept spec-conformant by hand on purpose).
- Don't assert a metadata-write effect with the BARE name in the diff (it false-matches the parent's collection reference) — assert the structural element via `poll_diff_contains`, paired with a model read-back. (Metadata writes DO persist now, with a ~1-2s lag.)
- Don't parallelize the RUN (serial only). Don't call `reset_fixture`/`reset_model` in a test (the orchestrator owns them).

Related: `edt-mcp-tool-conventions` (param/error/output canon), `edt-mcp-bilingual` (ru/en), `edt-mcp-build-test` (Java unit/build), `edt-mcp-testing` (manual per-tool checklists).
