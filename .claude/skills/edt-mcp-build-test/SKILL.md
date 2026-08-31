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

## Live redeploy (Tier 2 — the only proof of runtime behaviour)

A green build proves Java logic; only a redeploy proves a tool's schema, description, response and behaviour. The loop itself (non-elevated EDT copy, `edt-redeploy.ps1`, `MCP server UP on 8765`, exit 1 ≠ failure, anti-stale jar check, kill + `-clean` when EDT wedges) is in `edt-mcp-testing` and `edt-mcp-ready-to-deploy`. The traps that belong to the build:

- **Redeploy without a `-Build` flag only swaps the LAST built jar** — run `compile.sh` first (or pass `-Build`), else you ship stale code and validate the previous build.
- **Kill the whole stand before swapping**: `taskkill /IM 1cedt.exe /T /F`, plus `1cv8.exe` if an infobase is running. Terminate both again when done.
- **Inspect payloads with `Invoke-RestMethod`** (PowerShell), not `curl` — curl mangles nested JSON. Tools with a JSON responseType put the data in `result.structuredContent`; `content[0].text` is only a `Done`/`Error` placeholder.
- **Infobase-dependent tools** (debug / run / YAXUnit / profiling) need the infobase (or a `launch`) started first.

## Unit tests — conventions

- One `XxxToolTest` per tool (`tools/impl/`), JUnit4.
- Base pattern: `tool.execute(params)` + a sentinel-message check (e.g. "Project not found") for argument validation. Reference: `WriteModuleSourceToolTest`.
- **Bilingual invariant**: for tools that resolve metadata/code, a case with a Russian identifier/synonym (reference `WriteModuleSourceToolTest.testResolveRussianObjectName`). See skill `edt-mcp-bilingual`.

## Coverage gate

`BuiltInToolTestCoverageTest` (unit) fails the build if a registered tool has no `XxxToolTest`; the e2e coverage ratchet (`tools/test_coverage_ratchet.py`) fails the suite if a `tools/list` tool has no `test_<tool>.py`. Adding a tool without a test fails the build.

## E2E

`tests/e2e/run_all.py` (+ `tools/test_<tool>.py`, one per tool) runs scenarios against the live server and `TestConfiguration/` with git-fixture isolation. The round-trip of Cyrillic synonyms and the synonym-keyed-by-language-code check live in `test_create_metadata.py` / `test_get_metadata_details.py`. A new tool — add `tools/test_<tool>.py`. Full guide: `edt-mcp-e2e-testing` (and `tests/e2e/SKILL.md`).

## Protocol conformance

A separate gate from the e2e business-logic suite: the official `modelcontextprotocol/conformance` suite validates the SERVER against the MCP wire spec (handshake, capabilities, session-id, `isError`, `ping`, SSE). Lives in `tests/conformance/` (`baseline.yml` pins the intentional gaps; `README.md` explains the layer split). Run: `npx @modelcontextprotocol/conformance@latest server --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 --expected-failures tests/conformance/baseline.yml`.

## Before committing
- [ ] Build passes
- [ ] Unit tests green; a new/changed tool has a test
- [ ] If metadata/code resolution is touched — a bilingual case exists
- [ ] (if applicable) the e2e scenario is updated
- [ ] For a finished piece of work, run the full `edt-mcp-ready-to-deploy` checklist (build → README → live redeploy → golden → full e2e → conformance → clean tree)
