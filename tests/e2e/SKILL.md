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
    test_create_metadata_object.py
    ... (one per tool)
```

- **`harness.py`** owns: the HTTP/JSON-RPC client (+ SSE framing), the git-fixture helpers, and all assertion helpers (including error-quality). You call its functions; you never re-implement them.
- **`tools/test_<tool>.py`** — one file per MCP tool. It contains the test functions for that one tool: the happy path(s) **and** the negative matrix. Because each agent owns one file, there are **no merge conflicts** when many agents work in parallel.
- **`run_all.py`** — discovers every `@e2e_test` function, runs them **one at a time** (see §3 — execution is serial), resets the fixture before each, and emits a `--junit-xml` report.

**Golden rule of parallelism:** writing the test files can be parallelized across agents. **Running** them cannot — every test mutates the same `TestConfiguration` project and the same git working tree, so the orchestrator runs them serially with a hard reset between each.

---

## 3. The git-fixture isolation protocol (THE core mechanism)

`TestConfiguration` is a **git-tracked 1C project committed in this repo** (`TestConfiguration/`). That is our test fixture and our source of on-disk truth. The protocol for **every** test:

1. **Before the test — hard reset, never trust the previous test:**
   `git checkout -- TestConfiguration/` + `git clean -fd TestConfiguration/`
   (`checkout` reverts modified/deleted tracked files; `clean -fd` removes new files created by `create_*` tools.) The orchestrator does this for you via `reset_fixture()`. Each test starts from the committed baseline.
2. **Run the tool** (one or more `call(...)`).
3. **Assert the on-disk effect via git:**
   - **Destructive / write tool** → the working tree must show the **expected** change: `assert_diff_contains(...)` / `assert_diff_paths(...)`.
   - **Non-destructive / read tool** → the working tree must be **clean**: `assert_no_diff()`. This is a guardrail: a read tool that secretly mutates the project is a bug, and this catches it.
4. **After the whole run — guarantee cleanliness:** the orchestrator runs `finalize_clean()`; the final `git status TestConfiguration/` MUST be empty. A run that leaves the project dirty is a failed run.

**EDT in-memory note:** EDT reads the project files live, so after a `git checkout` it re-syncs from disk on its own (verified: a reverted module reads back empty). You do **not** need an explicit EDT refresh for normal file reverts. The one real risk is a tool leaving an **unsaved/dirty editor** hanging in EDT (stale in-memory state could later overwrite the disk). Write tools here persist synchronously, so this is not a concern in practice — but if you ever see a `reset_fixture()` not "stick", that is the cause; report it.

**On-disk timing:** `write_module_source` flushes to the `.bsl` **synchronously** — `git diff` sees it immediately, no polling needed. XML-writing tools (`create_metadata_object`, `delete_metadata_object`, `rename_metadata_object`) may differ; if a diff is not immediately visible, `harness.py` provides a short poll-with-timeout — use it, do not `sleep()` blindly.

---

## 4. How to verify "something really changed" (per tool type)

| Tool kind | Examples | Happy-path assertion |
|---|---|---|
| **read / list / search / navigate** | list_projects, read_module_source, get_metadata_details, search_in_code, find_references | response shape/content is correct **AND** `assert_no_diff()` (nothing changed on disk) |
| **write** | write_module_source, create/add/delete/rename metadata, form-edit | `assert_diff_contains(...)` — the expected lines are added/removed in the expected file(s). Optionally also a wire read-back via the sibling read tool (the client's view). |
| **action / status** | clean_project, revalidate_objects, import/export_configuration, update_database | diff on the artifacts the action touches (export → a new XML file; etc.) or `assert_no_diff()` if it legitimately changes nothing in the project tree |
| **round-trip ID** | get_applications → debug_launch, find_references → read_* | assert the returned ID is consumable by the sibling tool |

The **on-disk diff is the ground truth** for persistence. A wire read-back (calling the read tool) confirms the *client's view* but goes through the same in-memory model; prefer the git diff as the primary persistence proof, the read-back as a complement.

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

---

## 7. Harness API (the contract — call these, do not re-implement)

```python
from harness import (
    call, reset_fixture, diff, read_disk,
    assert_ok, assert_error, assert_error_quality,
    assert_no_diff, assert_diff_contains, assert_diff_paths,
    e2e_test,
)

# --- calling a tool ---
r = call("write_module_source", {"projectName": "TestConfiguration",
                                 "modulePath": "CommonModules/OK/Module.bsl",
                                 "mode": "append", "source": "// x\n"})
r.is_error      # bool   - did the tool report an error (server sets isError)
r.text          # str    - content[0].text (or the embedded resource text)
r.structured    # dict|None - structuredContent (None for MARKDOWN/TEXT tools)

# --- happy-path assertions ---
assert_ok(r, ctx="append to OK module")          # fails if r.is_error
assert_diff_contains("// x")                      # the on-disk working-tree diff includes this
assert_diff_paths(["TestConfiguration/src/CommonModules/OK/Module.bsl"])
assert_no_diff()                                  # working tree for TestConfiguration is clean

# --- negative + error-quality ---
e = assert_error(r, ctx="missing projectName")    # asserts is_error; returns the error text
assert_error_quality(e,
    names=["OK/DoesNotExist"],                    # the message names the bad value
    suggests=["list_modules", "modulePath"])       # the message is actionable (mentions a next step/tool)

# --- direct disk read (rarely needed; prefer diff) ---
content = read_disk("src/CommonModules/OK/Module.bsl")
```

**Test registration:** each test is a function decorated with `@e2e_test(tool="<tool_name>", kind="read|write|action")`. The orchestrator discovers them, calls `reset_fixture()` **before each**, runs it, records pass/fail, and enforces cleanliness. Do not call `reset_fixture()` yourself unless a single test needs an intermediate reset.

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
- **No new dependencies** — Python **stdlib only** (`urllib`, `json`, `subprocess` for git, `re`). Do not add pip packages.

---

## 9. Conventions checklist (before you say a test file is done)

- [ ] File is `tests/e2e/tools/test_<tool_name>.py`, imports only from `harness`.
- [ ] Happy path(s): correct effect asserted (on-disk diff for write, `assert_no_diff()` for read).
- [ ] Negative matrix: invalid path/object, invalid param combinations (every XOR/conditional/enum), missing required.
- [ ] Every negative asserts **error quality** (names the bad value + actionable), not just `is_error`.
- [ ] Any vague/non-actionable error is flagged with `# AUDIT:` and reported (not fudged).
- [ ] No anti-pattern from §6. Each test would FAIL if the tool were broken (mutation thinking).
- [ ] Python stdlib only; no `sleep()` (use the harness poll if needed); no `try/except: pass`.

> This skill is a living document. As the harness and tests evolve, it is updated. If something here disagrees with the actual `harness.py`, the harness wins — and this file is corrected to match.
