---
name: edt-mcp-e2e-testing
description: How to write and run black-box end-to-end (e2e) tests for the EDT-MCP server. Covers the architecture (shared harness + one file per tool + orchestrator), the git-fixture isolation protocol, happy-path AND negative coverage, error-quality assertions, and the anti-cheat rules. An agent that has never seen this project should be able to write a correct, non-cheating test after reading this.
---

# EDT-MCP E2E Testing — the complete guide

> Audience: an agent (or human) who has **never** seen this project and is asked to write or run an e2e test for one MCP tool. Read this **fully** before writing a single line.

---

## 1. What you are testing (and what you are NOT)

The **EDT-MCP server** is a Java plugin that runs **inside a live 1C:EDT IDE**. It speaks **MCP (JSON-RPC 2.0 over Streamable HTTP)** on `http://127.0.0.1:8765/mcp` and exposes ~61 *tools* that drive a real 1C project (read code, write code, create/delete/rename metadata, debug, profile, render forms, etc.).

You are writing **black-box end-to-end tests**: a Python test process that is a **real MCP client**. It sends real `tools/call` requests to the **real, running** server and asserts on the **real** responses and the **real** on-disk effect.

- You are **NOT** mocking anything. No fakes, no stubs. The server, the EDT model, and the project files are all real.
- This is a **different layer** from the Java/JUnit unit tests. JUnit runs headless (no live EDT) and covers pure logic / schema / argument-validation. It physically *cannot* test what happens against a real loaded project. That is exactly the gap these e2e tests fill.

If a tool "succeeded" in its response but the project did not actually change on disk, that is a **bug** — and a test that only checks the response would miss it. So we verify the **on-disk truth**, see §4.

---

## 2. Architecture (how the suite is laid out)

```
tests/e2e/
  SKILL.md            <- this file (the contract + the rules)
  harness.py          <- THE shared base. Import everything from here. Do NOT duplicate its logic.
  run_all.py          <- the orchestrator. Discovers and runs every test, SERIALLY, with reset between.
  tools/
    test_list_projects.py          <- one file per tool. You write/own exactly one of these.
    test_write_module_source.py
    test_create_metadata.py
    ... (one per tool)
    test_coverage_ratchet.py       <- meta: fails if a tools/list tool has no @e2e_test
```

- **`harness.py`** owns: the HTTP/JSON-RPC client (+ SSE framing), the git-fixture helpers, and all assertion helpers (including error-quality). You call its functions; you never re-implement them.
- **`tools/test_<tool>.py`** — one file per MCP tool. It contains the test functions for that one tool: the happy path(s) **and** the negative matrix. Because each agent owns one file, there are **no merge conflicts** when many agents work in parallel.
- **`run_all.py`** — discovers every `@e2e_test` function, runs them **one at a time** (see §3 — execution is serial), resets the fixture before each, and emits a `--junit-xml` report.

**Golden rule of parallelism:** writing the test files can be parallelized across agents. **Running** them cannot — every test mutates the same `TestConfiguration` project and the same git working tree, so the orchestrator runs them serially with a hard reset between each.

---

## 3. The git-fixture isolation protocol (THE core mechanism)

`TestConfiguration` is a **git-tracked 1C project committed in this repo** (`TestConfiguration/`). That is our test fixture and our source of on-disk truth. The protocol for **every** test:

1. **Before the test — hard reset, never trust the previous test.** The orchestrator calls `reset_fixture()` = `git reset` (unstage) + `git checkout HEAD --` + `git clean -fd` on `TestConfiguration/`. ALL THREE are needed: metadata delete/rename persist to disk **and can land STAGED** in the index, which a plain `git checkout -- ` (restores from the index) would NOT revert. `checkout HEAD --` restores from the commit; `reset` unstages; `clean -fd` removes untracked files.
2. **Run the tool** (one or more `call(...)`).
3. **Assert the effect** — the assertion depends on the tool kind (see §4): on-disk git-diff (file-write), MODEL read-back (metadata-write), or empty-diff (read).
4. **`kind="write-metadata"` tools get extra isolation** (the orchestrator does this, not you): immediately after such a test it runs `reset_fixture()` **then** `reset_model()`. Why: these tools mutate EDT's in-memory BM model, EDT may **async-autosave** it to disk, and a git reset alone cannot undo an *unsaved model* change — `reset_model()` (calls `clean_project`, which refreshes the model from the just-reset clean disk) discards it before the next test.
5. **After the whole run** the final `git status TestConfiguration/` MUST be empty. A run that leaves the project dirty is a failed run.

**Metadata writes now persist to disk** (since commit `71a48ba` — fix-card `metadata-writes-not-persisted-to-disk`, see [[project_metadata_write_persistence]]). `create_metadata` / `modify_metadata` now `forceExport` the mutated top object(s) to `.mdo` after the write; `delete_metadata`/`rename_metadata_object` always did (via the refactoring service); `write_module_source` flushes `.bsl` synchronously. So a metadata-write change IS on disk — but with a **sub-second async lag** after the call returns (the export pipeline drains a beat later). **Consequence:** verify a metadata-write BOTH ways — a **MODEL READ-BACK** (semantic: a read tool sees the change in the model) AND the **on-disk structure** via `poll_diff_contains(...)` (NOT a bare `assert_diff_contains` — poll for the lag). The orchestrator reverts the on-disk `.mdo` via `reset_fixture` (git) and re-syncs the model via `reset_model()`.

---

## 4. How to verify "something really changed" (per tool type)

| Tool kind (`@e2e_test(kind=...)`) | Examples | Happy-path assertion |
|---|---|---|
| **read** | list_projects, read_module_source, get_metadata_details, search_in_code, find_references | response content correct **AND** `assert_no_diff()` (nothing changed on disk — the read-tool guardrail) |
| **write** (file-write) | write_module_source | `assert_diff_contains(...)` — the change is on disk (synchronous `.bsl` flush) |
| **write-metadata** | create / add / delete / rename metadata | **MODEL READ-BACK + on-disk structure.** (1) `call(...)` a read tool (get_metadata_objects / get_metadata_details / get_module_structure) and assert the change in the MODEL (object present/absent, attribute present, renamed). (2) Assert it landed on disk via `poll_diff_contains(...)` (the write persists since the forceExport fix, with a ~1-2s lag → poll, not bare diff). **Assert the STRUCTURE, not just the name** — e.g. `"<name>E2EWeight</name>"` / `"<catalogs>Catalog.<name></catalogs>"`, never the bare name (which can false-match the parent's collection reference). For a **delete** (removal, not addition) use `poll_disk_path_gone("src/.../X.mdo")` (the object file is gone) + `poll_disk_lacks("src/Configuration/Configuration.mdo", "Type.X")` (the collection reference is removed). For delete/rename cover preview (no mutation) + confirm. For rejected/preview calls, `assert_no_diff()`. |
| **action** | clean_project, revalidate_objects, import/export_configuration, update_database | usually `assert_no_diff()` (they touch validation markers / the infobase DB / an external XML dump, NOT project files) + assert the status. Do NOT run a destructive happy mutation (import/update_database) that could corrupt the fixture — cover the error/sentinel matrix. |
| **env-dependent** | get_form_screenshot / get_form_layout_snapshot (JVM render flag), translate_configuration / generate_translation_strings / get_translation_project_info (LanguageTool plugin), debug/profiling (no session/infobase in this env) | **env-robust branch:** assert the REAL observed contract — a structured result IF the dependency is present, OR a clear, actionable **SENTINEL** ("LanguageTool ... not available. Install ...", "no active debug session") if not. Both branches must be mutation-sensitive (a wrong/blank payload fails). |
| **round-trip ID** | get_applications → debug_launch, find_references → read_* | assert the returned ID is consumable by the sibling tool |

**Ground truth depends on the tool:** a **file-write** is proven by the git diff; a **metadata-write** by the model read-back (semantic) AND the on-disk structure (`poll_diff_contains`, asserting the specific element). Always assert WHAT changed (the structural element), never merely that *a* diff exists or that the bare name appears somewhere; and never use `assert_no_diff()` as the *only* assertion (it must accompany a real content/sentinel check).

---

## 5. Negative coverage is MANDATORY (not just happy paths)

For **every** tool, in addition to the happy path, cover the **negative matrix**:

- **Invalid path / non-existent object** — a `modulePath` / `fqn` / `objectName` that does not exist; a non-existent `projectName`.
- **Non-existent lines / ranges** — `startLine`/`endLine` out of range; an `oldSource` (searchReplace) that is not in the file.
- **Invalid parameter combinations** — every XOR branch (both / neither of `modulePath` vs `objectName`), every conditional-required (`formName` when `moduleType=FormModule`, `commandName` when `moduleType=CommandModule`, `oldSource` when `mode=searchReplace`), an invalid `enum` value.
- **Missing required parameter.**

If a tool has XOR / conditional / enum parameters and you only tested the happy path, the test is **incomplete**.

### 5.1 Assert the QUALITY of the error (not just "it failed")

A negative test must assert that the error is a **good** error:

- `result.is_error` is `true` (machine-detectable — the server sets it via `ToolResult.error`).
- The message **names the invalid value** — *which* path / object / parameter was wrong.
- The message is **actionable** — a mini-instruction on what to do next: which sibling tool to use (e.g. *"object not found — use list_projects / get_metadata_objects to find a valid name"*) or how to fix the call (e.g. *"modulePath and objectName are mutually exclusive — pass only one"*).
- It is **NOT** a bare `"Error"`, a raw stack trace, or an opaque exception that leaked out of the tool.

Use `assert_error_quality(err, names=[...], suggests=[...])`.

### 5.2 The audit effect — DO NOT fudge a bad error

These negative tests double as an **audit of error quality**. If a tool's error is vague or not actionable, that is a **finding**, not something to paper over:

- **Do NOT** weaken your assertion to match a bad error message.
- **DO** record it: leave a clear `# AUDIT:` note in the test and report it back so it becomes a **fix-card** (improve the tool's error text). Raising the bar on errors is a goal of this work, not a side effect.

---

## 6. Anti-cheat rules — what makes a test REAL vs a CHEAT

A test that does not fail when the tool is broken is **worse than no test** (it gives false confidence). Apply **mutation thinking** to your own test: *"If the tool under test were broken — returned a no-op, the wrong result, or wrote nothing — would this test FAIL?"* If not, it is a cheat.

A separate **anti-cheat verifier subagent** will read every test and rule REAL or CHEAT. Write tests that pass it. It rejects:

1. Trivially-true asserts (`assert True`; `assert resp is not None` when it always is; asserting a substring that is always present; asserting only "the request didn't error" without checking the effect).
2. A destructive test with **no real on-disk check** (no `git diff`; asserting "diff is non-empty" instead of the **specific** expected change; diffing the wrong path).
3. No per-test reset (`reset_fixture()`) before the test → a stale diff from a previous test is counted as your own.
4. A non-destructive test without `assert_no_diff()` (the empty-diff guardrail is missing).
5. Self-fulfilling: reading the expected value from the same in-memory source you wrote it to, instead of going to disk/git.
6. Swallowed exceptions (`try/except: pass`), unconditional `skip`/`xfail`, commented-out asserts, hardcoded pass.
7. Tolerant matching that hides regressions (`.*` regex, case-folding, normalization that eats the real difference).
8. Coverage-gaming: a "test" that calls the tool and ignores the result just to tick the coverage ratchet.
9. No final `git status` clean → the run leaves the project dirty.
10. A negative test that checks only `is_error` / the fact of failure, but **not the error content** (the specific invalid value + an actionable next step).
11. Only the happy path covered where the tool has XOR / conditional / enum parameters (invalid combinations not tested).
12. A `write-metadata` test that verifies the effect with **only** a MODEL READ-BACK, or **only** an on-disk diff, or `assert_no_diff()` alone — or one whose on-disk check asserts merely the **bare name** (false-matches the parent's collection reference) instead of the **structural element** (`<name>X</name>`, `<catalogs>Type.X</catalogs>`). Metadata writes now persist to disk (§3), so verify BOTH: model read-back AND `poll_diff_contains` of the specific element. For delete/rename, also: a confirm test that doesn't read back the gone/renamed object, or a preview test that doesn't assert the object is UNCHANGED.

---

## 7. Harness API (the contract — call these, do not re-implement)

```python
from harness import (
    call,                                                     # tools/call (the orchestrator does the initialize handshake once)
    assert_ok, assert_error, assert_error_quality,            # outcome + error quality
    assert_contains, assert_not_contains,                     # text content
    assert_no_diff, assert_diff_contains, assert_diff_paths,  # on-disk truth (git)
    read_disk, diff,                                          # rarely needed (prefer the asserts above)
    e2e_test, PROJECT,                                        # PROJECT == "TestConfiguration"
)
# NOTE: reset_fixture() and reset_model() also exist but the ORCHESTRATOR calls them
# for you (reset_fixture before every test; reset_fixture+reset_model after every
# write-metadata test). Do NOT call them in a test unless you genuinely need an
# intermediate reset.
#
# Gated live-infobase helpers (used ONLY by tools/test_live_roundtrip.py, see §8.1):
#   requires_live_infobase(reason)   # first line; raises E2ESkip unless EDT_MCP_LIVE_INFOBASE=1
#   parse_yaxunit_counts(text)       # YAXUnit Markdown summary -> {total,passed,failed,errors,skipped,result}
#   extract_application_id(text)     # pull applicationId out of a debug-launch handle
#   wait_until_no_running_launch(config_name=...) / any_launch_running(config_name=...)  # infobase quiet
#   terminate_all_live_launches()    # blanket all=true kill — available, but the suite prefers a
#                                    # TARGETED terminate_launch by config name (a blanket kill can
#                                    # disturb EDT's shared infobase-connection registry)
#   assert_no_substantive_diff(ctx)  # like assert_no_diff but tolerant of an EDT CRLF .mdo touch
#   TESTS_PROJECT ("<PROJECT>.tests"), LIVE_LAUNCH_CONFIG  # the extension project + the launch config

# --- calling a tool ---
r = call("write_module_source", {"projectName": PROJECT,
                                 "modulePath": "CommonModules/OK/Module.bsl",
                                 "mode": "append", "source": "// x\n"})
r.is_error      # bool   - did the tool report an error (server sets isError)
r.text          # str    - content[0].text (digest/markdown). JSON tools put data in .structured!
r.structured    # dict|None - structuredContent (None for MARKDOWN/TEXT tools; the data for JSON tools)
r.error_text()  # str    - best-effort error message (structured.error, then text)

# --- happy-path assertions ---
assert_ok(r, ctx="append to OK module")          # fails if r.is_error
assert_contains(r.text, "...", ctx="...")        # / assert_not_contains(...)
assert_diff_contains("// x")                      # file-write: the on-disk diff includes this
assert_no_diff()                                  # read/rejected: working tree for TestConfiguration is clean

# --- negative + error-quality ---
e = assert_error(r, ctx="missing projectName")    # asserts is_error; returns the error text
assert_error_quality(e,
    names=["OK/DoesNotExist"],                    # the message names the bad value
    suggests=["list_modules", "modulePath"])       # the message is actionable (mentions a next step/tool)
```

**Test registration:** each test is `@e2e_test(tool="<tool_name>", kind=...)`. The **kind** picks the isolation + the verification idiom:
- `kind="read"` — read/list/search/nav/debug. Guardrail: `assert_no_diff()`.
- `kind="write"` — file-write (write_module_source). Verify with `assert_diff_contains()` (on disk).
- `kind="write-metadata"` — create/add/delete/rename metadata. Verify with MODEL READ-BACK (call a read tool) AND on-disk structure (`poll_diff_contains` of the element, or `poll_disk_path_gone`/`poll_disk_lacks` for delete). The orchestrator runs `reset_fixture()`+`reset_model()` after each such test (you don't).
- `kind="action"` — clean/revalidate/import/export/update_database. Usually `assert_no_diff()` + status.

The orchestrator discovers `@e2e_test` functions, runs them serially, `reset_fixture()` **before each**, and enforces final cleanliness.

```python
@e2e_test(tool="write_module_source", kind="write")
def test_append_adds_line_on_disk():
    r = call("write_module_source", {"projectName": "TestConfiguration",
            "modulePath": "CommonModules/OK/Module.bsl", "mode": "append",
            "source": "// e2e-probe\n"})
    assert_ok(r, "append")
    assert_diff_contains("// e2e-probe")          # proven on disk via git

@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_missing_oldsource_errors_clearly():
    r = call("write_module_source", {"projectName": "TestConfiguration",
            "modulePath": "CommonModules/OK/Module.bsl", "mode": "searchReplace",
            "oldSource": "THIS_DOES_NOT_EXIST", "source": "x"})
    e = assert_error(r, "searchReplace stale oldSource")
    assert_error_quality(e, names=["THIS_DOES_NOT_EXIST"], suggests=["read_module_source"])
    assert_no_diff()                              # a rejected write must NOT touch disk

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_attribute_appears_in_model_and_on_disk():
    parent, new_attr = "Catalog.Catalog", "E2EColor"
    before = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [parent], "full": True})
    assert_not_contains(before.text, new_attr, "absent before (else a no-op passes)")
    # Members are addressed by their full FQN (the create_metadata fold of add_metadata_attribute).
    r = call("create_metadata", {"projectName": PROJECT, "fqn": parent + ".Attribute." + new_attr})
    assert_ok(r, "create attribute member")
    after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [parent], "full": True})
    assert_contains(after.text, new_attr, "MODEL read-back: new attribute is in the model")
    # Verify BOTH ways (see §3): model read-back AND on-disk structure, polling for the export lag.
    poll_diff_contains(new_attr, "ON-DISK: the new attribute reaches the .mdo")

@e2e_test(tool="get_translation_project_info", kind="read")
def test_env_dependent_sentinel_or_real():
    r = call("get_translation_project_info", {"projectName": PROJECT})
    if r.is_error:   # LanguageTool plugin absent in this EDT -> a clear, actionable sentinel
        assert_error_quality(r.error_text(), names=["LanguageTool"], suggests=["Install"])
    else:            # plugin present -> the real structured contract
        assert_contains(r.text, "## Storages", "translation info document")
    assert_no_diff()
```

---

## 8. How to run

The suite requires the **live server up** on `:8765` with `TestConfiguration` loaded (the orchestrator polls `/health` first). Then:

```
python tests/e2e/run_all.py --project TestConfiguration --junit-xml tests/e2e/e2e-results.xml
```

- Execution is **serial** (see §3). Do not try to parallelize the run.
- The run **mutates** `TestConfiguration` and reverts it; on a clean checkout that is expected. The final state must be clean.
- The existing `.github/workflows/e2e-tests.yml` invokes the runner against a running server; keep its CLI flags (`--host/--port/--project/--junit-xml`) stable.
- **No new dependencies** — Python **stdlib only** (`urllib`, `json`, `subprocess` for git, `re`). Do not add pip packages. (Research-backed: the official MCP SDK's in-memory transport doesn't fit a server that lives inside EDT; the hand-rolled client is kept spec-conformant instead — initialize + notifications/initialized handshake, Mcp-Session-Id + MCP-Protocol-Version headers, robust SSE.)
- **Scope:** the suite covers **every tool** + `tools/test_coverage_ratchet.py` (fails if `tools/list` advertises a tool with no `@e2e_test`). It replaced the older `run_e2e_tests.py` monolith (now removed). **Adding a new tool?** add `tools/test_<tool>.py` — the ratchet enforces it.
- **Filter:** `--filter <substr>` runs only tests whose name/tool matches (useful while iterating).
- **Per-test timeout / hang protection:** each test runs under a wall-clock timeout (`--test-timeout`, default **600s**, env `MCP_TEST_TIMEOUT`; the per-CALL HTTP timeout is `MCP_CALL_TIMEOUT`, default 180s). If a test exceeds it, EDT is almost certainly **hung** (the classic case: `clean_project` → `ProjectRestartJob` wedges and the call never returns). That test is reported **`[TIMEOUT]` = a FAILURE** (`<failure type="timeout">` in JUnit) with a clear message, and **every remaining test is `[SKIP]`ped** — a wedged EDT would make them all hang too, so the run aborts fast instead of stalling for minutes. There is **no auto-relaunch**: restart EDT and re-run (optionally `--filter` from where it stopped). Keep the timeout comfortably above the slowest legit test — a `write-metadata` unit chains the test call + `clean_project` + `wait_for_project_ready` (each ≤ `MCP_CALL_TIMEOUT`), so the default 600 sits well above that ~3× budget. **Gotcha:** do not start a full run right after a `-clean` relaunch — let the project finish indexing first, or an early `clean_project` can legitimately run long.
- **Clean tree at session end (`final_cleanup`):** a run STARTS and ENDS with `harness.final_cleanup()` so `git status` is empty afterward — "no diff" is the visible "the session passed and left nothing behind" signal. It reverts **BOTH** fixtures (base `tests/TestConfiguration` + extension `tests/tests`) and `clean_project`s both so EDT's in-memory model is re-synced from the clean disk; without that model sync, a stale model (a manual edit made in the EDT editor, or an unflushed metadata write) would **autosave the change back after the run** and silently re-dirty the tree. The final cleanliness gate is `all_fixtures_status()` (covers both fixtures). NB it WILL wipe an uncommitted manual experiment under either fixture — that is intentional (a session must end clean). On a timeout-abort it reverts git-only (EDT is wedged; model-sync would hang), so post-abort cleanliness is point-in-time only. **Per-test** `assert_no_diff` stays base-scoped (tests only mutate the base, read the extension) — if you ever add an extension-MUTATING tool, give it its own extension-scoped diff assertion, since the per-test gate won't see extension dirt (only the final gate will).

### 8.1 The gated live-infobase round-trip suite (`tools/test_live_roundtrip.py`)

Most runtime tools (YAXUnit, debug, profiling) have **no infobase/session in CI**, so their per-tool files assert the realistic *no-session SENTINEL* (see §4 env-dependent). What a headless run **cannot** prove is a real **end-to-end round-trip**: a real YAXUnit run → parsed pass/fail counts, a real breakpoint **suspend → inspect → resume**, and a launch/session **id minted by one tool and consumed by its siblings**. Those live in **one deliberately cross-tool file** — the only exception to the "one file per tool" rule (the tools it touches are already covered by their own sentinel files; this file is *supplementary* round-trip coverage).

It is **gated** behind `requires_live_infobase()` (first line of every test): each test raises `E2ESkip` — reported as **`[SKIP]`, NOT a failure**, and excluded from the pass/fail denominator — unless `EDT_MCP_LIVE_INFOBASE=1`. So a normal `run_all.py` runs them as skips and stays green; an **attended** operator runs them explicitly:

```
EDT_MCP_LIVE_INFOBASE=1 python tests/e2e/run_all.py --project TestConfiguration --filter test_live_
```

Attended preconditions (the operator owns these): EDT open on the TestConfiguration workspace + MCP up; a runtime-client launch config (default `"TestConfiguration Thin Client"`, override `MCP_LIVE_LAUNCH_CONFIG`); the **YAXUnit engine (`YAxUnit.cfe`) loaded** in the infobase and the `tests` extension applied. The fail-count round-trip uses a dedicated **`tests_FailureDemo`** module (one passing + one *deliberately failing* test, kept separate so `tests_SampleTests` stays green) — a default no-filter YAXUnit run therefore includes one **intentional** failure (that is the point: it proves failures surface). Each live test quiets the infobase at **both** ends — a TARGETED `terminate_launch` on its own config name (a blanket `all=true` kill can disturb EDT's shared infobase-connection registry and break the next launch), plus `wait_for_project_ready` so a debug launch is not fired at a still-indexing project — and passes the launch's minted `applicationId` explicitly to `wait_for_break`. None touch the git source tree, but a live EDT may CRLF-touch a metadata `.mdo` while updating the infobase, so they end with `assert_no_substantive_diff()` (tolerant of a line-ending-only touch, strict on real content/structure changes).

NB this suite surfaced (and fixed, same change-set) two real tool defects a headless run never could: `get_variables` leaked a raw NPE on a live frame whose `hasVariables()` was lazy (`VariableSerializer` now calls `getVariables()` directly), and `resume` with no args dead-ended on a 1C target whose `canResume()` is false while a thread is suspended (`ResumeTool` now falls back to resuming the target's suspended threads).

---

## 9. Conventions checklist (before you say a test file is done)

- [ ] File is `tests/e2e/tools/test_<tool_name>.py`, imports only from `harness`.
- [ ] Correct `kind=` chosen, and the effect is verified the RIGHT way: **on-disk diff** (file-write) / **model read-back** (write-metadata — NOT git-diff) / **`assert_no_diff()`** (read) / **env-robust sentinel branch** (env-dependent). Never `assert_no_diff()` as the only assertion.
- [ ] Negative matrix: invalid path/object, invalid param combinations (every XOR/conditional/enum), missing required, boundaries.
- [ ] Every negative asserts **error quality** (names the bad value + actionable), not just `is_error`.
- [ ] Any vague/non-actionable error is flagged with `# AUDIT:` and reported (not fudged).
- [ ] No anti-pattern from §6. Each test would FAIL if the tool were broken (mutation thinking).
- [ ] Python stdlib only; no `sleep()` (use the harness poll if needed); no `try/except: pass`.

> This file is the **canonical, authoritative guide** for the `edt-mcp-e2e-testing` Claude skill (`.claude/skills/edt-mcp-e2e-testing/SKILL.md` is a thin entrypoint that points here). It is a living document — keep it current as the harness/tests evolve. If something here disagrees with the actual `harness.py`, the harness wins, and this file is corrected to match.
