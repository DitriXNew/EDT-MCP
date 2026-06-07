---
name: edt-mcp-ready-to-deploy
description: The final "definition of done" / ready-to-deploy checklist for EDT-MCP — the ordered gate to run when a piece of work is finished, before declaring it done or merging. Covers code hygiene, tests written, build+unit, README, live redeploy, golden, full e2e, protocol conformance, and a clean git tree. Use when wrapping up a change, verifying everything still works, or before a commit/merge.
---

# EDT-MCP — ready to deploy (final checklist)

Run this when a piece of work is finished — it is the gate that proves the whole thing still works. Walk it **top to bottom and stop on the first red**; each step proves a layer the previous one can't. Companion skills do the "how": `edt-mcp-build-test`, `edt-mcp-e2e-testing`, `edt-mcp-testing`, `edt-mcp-bilingual`, `edt-mcp-tool-conventions`.

> A green lower tier does NOT prove a higher one. "Compiles" ≠ "tests pass" ≠ "works live" ≠ "protocol-conformant". Don't declare done on review/grep alone.

## The ordered gate

1. **Code hygiene**
   - Surface is English only (tool descriptions, errors, README, skills) — no Russian prose, no transliteration.
   - Cyrillic in code only where it is real 1C/BSL data the code matches or documents (type tokens, BSL keywords, example FQNs) — keep it; regexes use `\uXXXX`. No raw Russian-prose comments. Fix the `1С`→`1C` homoglyph (Cyrillic Es). (See CLAUDE.md don't #7, `edt-mcp-bilingual`.)
   - Parameters lowerCamelCase; errors via `ToolResult.error(...).toJson()`; model touched only inside a transaction boundary; markdown table cells escaped.

2. **Tests written** (the ratchets fail the build otherwise)
   - New/changed tool → an `XxxToolTest` (unit) **and** `tests/e2e/tools/test_<tool>.py` (e2e).
   - Changed metadata/code resolution → a bilingual case (English `Name`, Russian `Name`, synonym). (`edt-mcp-bilingual`.)

3. **Build + unit (Tier 1)** — `bash source/compile.sh` → **BUILD SUCCESS** + every ratchet green (`BuiltInToolTestCoverageTest`, `ToolContractConsistencyTest`, e2e coverage ratchet). Toolchain isn't on PATH — pass `--java-home`/`--maven-home`.

4. **README updated** — bump the tool count (both places), add/adjust the group table, the flat tool table, and the detailed section; parameters must match the schema.

5. **Live redeploy (Tier 2)** — redeploy the freshly built bundle to the non-elevated dev EDT copy (`edt-redeploy.ps1`; signal `MCP server UP on 8765`, exit 1 ≠ failure). **Anti-stale:** confirm the DEPLOYED jar contains your change (`unzip -p <jar> path/Class.class | grep <literal>`). Wait for both projects `ready`. If EDT wedges → kill + `-clean` relaunch.

6. **Golden** — if `tools/list` changed (new tool / description / schema / annotations), regenerate and commit the golden (`EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter tools_list`); the golden snapshot test must pass.

7. **Full e2e (Tier 3)** — `python tests/e2e/run_all.py --project TestConfiguration` → all pass (a handful of env-gated skips are normal), **fixture clean: True**. (`edt-mcp-e2e-testing`.)

8. **Protocol conformance** — run the official suite against the live server and pin known gaps:
   `npx @modelcontextprotocol/conformance@latest server --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 --expected-failures tests/conformance/baseline.yml`
   → **no NEW failures** beyond the committed baseline. A new failure = a real protocol regression → fix the server. (`tests/conformance/README.md` explains the layer split: e2e = business logic, conformance = protocol.)

9. **Clean git tree** — `git status` clean: no stray files, no leftover fixture churn (the e2e run reverts its fixtures; if not, sync them). The diff should read as exactly the intended change.

10. **Commit** — adversarially review the diff, then commit **locally** on the working branch. Do NOT push or merge unless explicitly asked.

## Quick "definition of done"
- [ ] No stray Cyrillic / English surface / lowerCamelCase / `ToolResult.error` / tx boundary
- [ ] Unit `XxxToolTest` + e2e `test_<tool>.py` (+ bilingual case if resolution changed)
- [ ] `compile.sh` BUILD SUCCESS + ratchets green
- [ ] README count + tables + detail updated
- [ ] Redeployed live; deployed jar verified (anti-stale); projects `ready`
- [ ] Golden regenerated + committed (if `tools/list` changed)
- [ ] Full e2e green + fixture clean
- [ ] Conformance: no new failures vs `tests/conformance/baseline.yml`
- [ ] `git status` clean; diff reviewed; committed locally (no push unless asked)
