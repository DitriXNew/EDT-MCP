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
   - **Run the FULL unit suite before pushing, on top of current master** (merge `origin/master` first). A targeted `-Dtest=` run and a green live stand both miss the build ratchets that new work trips most often:

     | Ratchet | Trips when | Fix |
     |---|---|---|
     | `ToolDocsRatchetTest` | a tool's description, schema or guide changed | regenerate `docs/tools/<tool>.md` (step 6) |
     | `GuideOutputSizeTest`, `ModifyMetadataGuideHeadroomTest` | a guide renders over the `get_tool_guide` cap or eats the merge headroom | condense existing prose so the PR is NET-NEUTRAL on that guide — parallel PRs add to the same guide (`edt-mcp-tool-descriptions`) |
     | `SchemaExecuteParamParityTest` | a declared parameter is not read in `execute()` by its literal name | read it by its declared name |
     | `ToolPreset*Test` | a registered tool belongs to no `ToolGroup` | add it to its group (`edt-mcp-new-tool`) |
     | `ToolSettingsServiceTest` (read-only preset growth) | a writer joins a group the read-only presets disable | register the preset migration the test names |
     | `MessagesParityTest` | a preferences key exists in one locale file only | add it to every `messages*.properties` |
     | `ToolsetsTest`, `ToolGroupTest`, `WriteScopeInventoryTest` | a new tool lacks a toolset/group or a writer is missing from the write inventory | register it in each inventory |
     | `ProjectContextAdoptionRatchetTest` | a new `ResourcesPlugin.getWorkspace()` in `tools/impl` | resolve through `ProjectContext` |
     | e2e runner `test_fixture_hygiene` | fixture objects were added on purpose | `EDT_MCP_UPDATE_FIXTURE_DECLARATIONS=1 python -m unittest discover -s tests/e2e/runner_tests -p "test_*.py"`, commit the pin |
   - **Then run the agent-pack validator: `python docs/validate_agent_skills.py`.** It is a SEPARATE CI job (`Validate business-project skills`) that `compile.sh` does not cover, and it owns its own ratchets — the tool-docs↔registrar match and the single-word tool-name list. On Windows ignore its `CR/CRLF is not allowed` lines: those are the local checkout's line endings, and the committed blobs are LF (`git show HEAD:<file> | grep -c $'\r'` → 0).

   - **Touched the target platform, or a call into an EDT/platform API? Also compile against the older EDTs, once per installation — each with its own flags:**
     - 2026.1: `bash source/verify-oldest-platform.sh <2026.1-install-dir>` (the defaults: JavaSE-17, SVG dropped);
     - 2026.2.0: `bash source/verify-oldest-platform.sh <2026.2.0-install-dir> --ee JavaSE-25 --keep-svg` — without the flags it dies in platform resolution (`osgi.ee` 25 missing) before testing any API.

     The build compiles against the NEWEST platform, and neither the BREE nor the lower-bound imports can express "no signature that only the newer release has" — that binds fine and fails at the call site with `NoSuchMethodError` on the older EDT. The script points the target platform at a local installation and compiles; it restores the working tree on every exit path. Run it against **2026.1** (the oldest supported major) and against **2026.2.0** (the previous service release of the current major). The second one is not optional cover: 1C deletes the previous service release from its channel, so CI builds only against what `ruby/2026.2/` serves today and a local install is the only remaining proof that the single artifact still loads on 2026.2.0.

4. **README updated** — bump the tool count (both places), add/adjust the group table, the flat tool table, and the detailed section; parameters must match the schema.

5. **Live redeploy (Tier 2)** — redeploy the freshly built bundle to the non-elevated dev EDT copy (`edt-redeploy.ps1`; signal `MCP server UP on 8765`, exit 1 ≠ failure). **Anti-stale:** confirm the DEPLOYED jar contains your change (`unzip -p <jar> path/Class.class | grep <literal>`). Wait for both projects `ready`. If EDT wedges → kill + `-clean` relaunch.

6. **Golden + tool docs** — if `tools/list` changed (new tool / description / schema / annotations), regenerate and commit the golden (`EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter tools_list`); the golden snapshot test must pass. A description, schema or GUIDE change also needs its `docs/tools/<tool>.md` page regenerated: `python docs/generate_tool_docs.py --port <port>` against a server running THIS branch's build, then commit only the pages of the tools the branch changed (plus the indexes for a new tool). Without a live stand, generate changed pages from `ToolDocsRatchetTest.pageText(tool)` in a temporary write-mode test, remove that mode, and rerun the read-only ratchet. Never generate pages from an integration build that holds several branches: a page embeds the whole guide, so it would carry the other branches' text.

7. **Full e2e (Tier 3)** — `python tests/e2e/run_all.py --project TestConfiguration` → all pass (a handful of env-gated skips are normal), **fixture clean: True**. (`edt-mcp-e2e-testing`.) For ordering/insert positions, verify optional autosort-present refusals and plugin-absent persistence, with exact model and `.mdo` order and the shared notice/user-action contract; assert no autosort preference writes. Record any unrun environment.

8. **Protocol conformance** — run the official suite against the live server and pin known gaps:
   `npx @modelcontextprotocol/conformance@latest server --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 --expected-failures tests/conformance/baseline.yml`
   → **no NEW failures** beyond the committed baseline. A new failure = a real protocol regression → fix the server. (`tests/conformance/README.md` explains the layer split: e2e = business logic, conformance = protocol.)

9. **Clean git tree** — `git status` clean: no stray files, no leftover fixture churn (the e2e run reverts its fixtures; if not, sync them). The diff should read as exactly the intended change.

10. **Commit** — adversarially review the diff, then commit on the working branch. Pushing, the draft PR and the merge follow the PR rules in CLAUDE.md ("For agents": draft → merge gate → squash-merge).

## Quick "definition of done"
- [ ] No stray Cyrillic / English surface / lowerCamelCase / `ToolResult.error` / tx boundary
- [ ] Unit `XxxToolTest` + e2e `test_<tool>.py` (+ bilingual case if resolution changed)
- [ ] `compile.sh` BUILD SUCCESS (FULL suite, on top of current master) + ratchets green; `docs/validate_agent_skills.py` clean
- [ ] `verify-oldest-platform.sh` green on BOTH local installs — 2026.1 (defaults) and 2026.2.0 (`--ee JavaSE-25 --keep-svg`) (only if the target platform or a platform API call changed)
- [ ] README count + tables + detail updated
- [ ] Redeployed live; deployed jar verified (anti-stale); projects `ready`
- [ ] Golden regenerated + committed (if `tools/list` changed); `docs/tools/<tool>.md` regenerated for every tool whose description/schema/guide changed
- [ ] Full e2e green + fixture clean
- [ ] Conformance: no new failures vs `tests/conformance/baseline.yml`
- [ ] `git status` clean; diff reviewed; committed locally (no push unless asked)
