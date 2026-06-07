---
name: edt-mcp-build-test
description: How to build the EDT-MCP Eclipse plugin (Tycho/Maven) and run its unit and e2e tests, plus the test conventions for this repo. Use when building the plugin, running or writing tests, or verifying a change before committing.
---

# EDT-MCP — build and tests

## Layout

- Maven/Tycho reactor: `mcp/` (bom, bundles, features, repositories, targets, tests).
- Unit tests: `mcp/tests/com.ditrix.edt.mcp.server.tests/src` (JUnit4, a plug-in fragment).
- E2E: `tests/e2e/run_all.py` + `tools/test_<tool>.py` (Python; runs the MCP server against `TestConfiguration/`).

## Build

A Tycho build from `mcp/` (Maven, JDK 17). The artifact is a p2 update-site in `repositories/com.ditrix.edt.mcp.server.repository/target`.

**A local build is available — use it to validate Java edits** (don't claim "verified by review/grep only"). The canonical script is `source/compile.sh` (it reproduces the CI flow `mvn clean verify -T 1C` from `.github/workflows/build.yml`):

```bash
# from the repo root: compile + unit tests
bash source/compile.sh
# compile only (no Surefire) — faster
bash source/compile.sh --skip-tests
```

- The toolchain (JDK 17 + Maven 3.9+) is often **not on `PATH`** — pass it explicitly: `--java-home <JDK17 home> --maven-home <maven home>` (or env `JAVA_HOME`/`MAVEN_HOME`). The exact paths are **machine-specific — discover them on the spot**, don't hardcode into committed files. Exact options are in README "Building from source".
- **The first build is slow**: Tycho pulls the EDT p2 repository (`edt.1c.ru`) + the Eclipse SDK (hundreds of MB). Once the caches are warm (`~/.m2/repository/p2`, `.cache/tycho`) it runs in ~1 minute. If the caches are absent and there's no network, the build legitimately can't run — say so, don't fake "green".
- **Unit tests need the target platform too** (Mockito/JUnit come from the p2 target, not plain Maven Central) — a green `compile.sh` is the real proof for Java edits; grep only catches anchor/text problems.

## Unit tests — conventions

- One `XxxToolTest` per tool (`tools/impl/`), JUnit4.
- Base pattern: `tool.execute(params)` + a sentinel-message check (e.g. "Project not found") for argument validation. Reference: `WriteModuleSourceToolTest`.
- **Bilingual invariant**: for tools that resolve metadata/code, a case with a Russian identifier/synonym (reference `WriteModuleSourceToolTest.testResolveRussianObjectName`). See skill `edt-mcp-bilingual`.

## Coverage gate

`BuiltInToolTestCoverageTest` (unit) fails the build if a registered tool has no `XxxToolTest`; the e2e coverage ratchet (`tools/test_coverage_ratchet.py`) fails the suite if a `tools/list` tool has no `test_<tool>.py`. Adding a tool without a test fails the build.

## E2E

`tests/e2e/run_all.py` (+ `tools/test_<tool>.py`, one per tool) runs scenarios against the live server and `TestConfiguration/` with git-fixture isolation. The round-trip of Cyrillic synonyms and the synonym-keyed-by-language-code check live in `test_create_metadata.py` / `test_get_metadata_details.py`. A new tool — add `tools/test_<tool>.py`. Full guide: `edt-mcp-e2e-testing` (and `tests/e2e/SKILL.md`).

## Before committing
- [ ] Build passes
- [ ] Unit tests green; a new/changed tool has a test
- [ ] If metadata/code resolution is touched — a bilingual case exists
- [ ] (if applicable) the e2e scenario is updated
