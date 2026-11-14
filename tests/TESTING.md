# Testing EDT MCP Server

## Architecture

The testing infrastructure consists of a build step plus two test layers:

### 0. Build

**Requirements:**

- **JDK 25** to run the build (CI: Temurin 25, `.github/workflows/build.yml`). Tycho 5.0.4
  (`mcp/bom/pom.xml`) needs Maven on JDK 21+, and the unit tests run the EDT 2026.2 platform
  bundles, which are Java 25 class files. The plugin itself is still compiled to Java 17
  (`release 17`, `Bundle-RequiredExecutionEnvironment: JavaSE-17`) - the JDK that builds and the
  level built to are separate.
- **Maven 3.9.x** (CI pins 3.9.16, `.github/actions/setup-maven`): Maven 3.10 breaks Tycho 5.0.4
  with `Invalid Collect Request: null -> [...]`.
- **A short temporary directory** (Windows). The IPC unit tests open their private socket under
  `java.io.tmpdir`, and `IpcListener` refuses a socket path longer than 100 UTF-8 bytes, so a long
  `TEMP` fails those tests. An 8.3 short name in `TEMP` (`C:\Users\LONGNA~1\...`) also fails tests
  that compare it with the real path. Point both variables at a short plain directory for the
  build, e.g. in Git Bash `export TEMP='C:\T' TMP='C:\T'` (create the directory first).

The plugin must be built with the official build script `source/compile.sh`. It runs the Tycho build (`mvn clean verify`, which also executes the unit tests) and packages the p2 update site into a versioned zip:
```bash
bash source/compile.sh --java-home /usr/lib/jvm/temurin-25-jdk-amd64   # adjust the JDK 25 path
```
Outputs:
- update-site zip: `source/dist/MCP-EDT.v<version>.zip`
- p2 repository: `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository`

To run only the unit tests (without packaging the archive) you can invoke Maven directly:
```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64   # adjust to your JDK 25 path
cd mcp
mvn clean verify
```

### 1. Unit Tests (Tycho Surefire)

Located in `mcp/tests/com.ditrix.edt.mcp.server.tests/`

These are JUnit 4 tests that run inside the Eclipse/Tycho build without requiring a running EDT instance. They cover:

- **Protocol layer**: `JsonSchemaBuilder`, `JsonUtils`, `GsonProvider`, `McpConstants`
- **JSON-RPC DTOs**: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`
- **Tool results**: `ToolResult`, `ToolCallResult`, `ToolsListResult`

Unit tests run automatically during the build (see **0. Build**). Results are in:
```
mcp/tests/com.ditrix.edt.mcp.server.tests/target/surefire-reports/
```

### 2. E2E Tests (Python HTTP client)

Located in `tests/e2e/` — a modular black-box suite: `harness.py` (HTTP/SSE client +
git-fixture isolation + assertions), `run_all.py` (serial runner, discovery,
`--junit-xml`, `--filter`), and one `tools/test_<tool>.py` per tool. A coverage ratchet
(`tools/test_coverage_ratchet.py`) fails the suite if `tools/list` advertises a tool with
no test.

These tests send real HTTP requests to a running MCP server and validate every tool. They require:
- A running EDT instance with the MCP plugin installed
- The `TestConfiguration` project loaded in EDT

**Prerequisite — install the plugin in EDT manually.** Build it first (see
**0. Build**). There is currently no automated/headless installation, so the
plugin must be installed by hand before running E2E:

1. In EDT: **Help → Install New Software… → Add… → Archive…**, select
   `source/dist/MCP-EDT.v<version>.zip` (or **Local…** → the `repository` folder),
   install `com.ditrix.edt.mcp.server.feature`, restart EDT.
2. Import the fixture projects **in place** (no copy into the workspace) from the
   same checkout that runs the suite, and wait until derived data is built. The
   harness resets them with git relative to its own repository root, so a copy, or
   another clone, is never reset:
   - `tests/TestConfiguration` - project `TestConfiguration` (the base configuration);
   - `tests/tests` - project `TestConfiguration.tests` (the extension);
   - `tests/ExternalObjects` - project `ExternalObjects`;
   - `tests/SupportedConfiguration` - project `SupportedConfiguration` (vendor support).

   CI imports all four; `ExternalObjects` and `SupportedConfiguration` are needed only by
   the tests that address them.

   Instead of the import wizard, EDT can import them at startup: set
   `EDT_MCP_IMPORT_PROJECTS` on the EDT process to the four directories, separated by
   the platform path separator (`;` on Windows, `:` on Linux), as CI does.
3. Make sure the MCP server is listening on port 8765 (the plugin starts it
   automatically inside the EDT workbench).

> Why manual: the system EDT install lives under a read-only path (e.g.
> `/opt/...`), so a headless `p2 director` install needs elevated rights, and the
> MCP server only starts inside the full EDT workbench (it is skipped in the
> headless `1cedtcli` runtime). Until a dedicated EDT runner/Docker image exists
> (see "Future: Full CI Pipeline"), install the plugin through the EDT UI.

**Running locally:**
```bash
# Make sure EDT is running with MCP server on port 8765
python tests/e2e/run_all.py

# Or with custom settings:
python tests/e2e/run_all.py --host localhost --port 8765 --project TestConfiguration

# Run a subset (substring match on tool/test name):
python tests/e2e/run_all.py --filter modify_metadata

# Generate JUnit XML report:
python tests/e2e/run_all.py --junit-xml results.xml
```

#### The stand profile the suite expects

CI (`.github/workflows/e2e-tests.yml`) runs the suite against one stand profile. A local stand that
differs fails or skips a few tests for reasons unrelated to the code:

- **Tool profile - `tools/list` golden.** `tools/list` advertises only the enabled tools, and
  `tools_list.golden.json` is taken in the CI profile: `git` and `ask_workmate` disabled,
  `get_form_screenshot` enabled (the shipped defaults also disable `get_form_screenshot`). Before the first start of
  EDT, write `<workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/com.ditrix.edt.mcp.server.prefs`:
  ```
  eclipse.preferences.version=1
  mcpDisabledTools=git,ask_workmate
  mcpToolPrefsMigration=15
  ```
  The migration stamp keeps the one-shot migration that disables `get_form_screenshot` from
  undoing the choice. Leaving `git` or `ask_workmate` enabled also fails the tests that expect them
  off. When the golden test fails on a different tool SET, its message names the tools on each side.
- **Destructive consent and the workspace.** Start EDT with `EDT_MCP_DESTRUCTIVE_CONSENT=allow`, or
  the confirming calls wait for a consent dialog and time out. Set `EDT_MCP_EDT_WORKSPACE` for the
  test process to the directory EDT got through `-data`: the fixture projects live in the checkout,
  so the harness cannot find the workspace's `.metadata` from their paths.
- **EDT UI language.** The windowed `get_symbol_info` contract pins EDT's editor hover byte for byte,
  and EDT renders the hover in its UI language (`-nl`, or the OS locale). Both EDT UI languages are
  pinned: English in `tests/e2e/resources/stdio-read-windowed.json`, Russian in
  `stdio-read-windowed-ru.json`. The language is read from the hover's own type links (an English
  hover links `[Number](...def_Number)`, a Russian one `[Число](...def_Number)`). Where a test meets
  a message EDT localizes, it checks a stable field instead (e.g. `validate_query` checks the error
  `code`, not the message text).
- **1C:Enterprise platform.** CI has no platform. `import_project_from_file`'s
  `test_without_a_platform_the_call_is_refused_at_once` pins the refusal without one; on an EDT with an
  installed platform its damaged probe file is admitted to the conversion, the test checks that
  nothing was created and skips, naming the reason. The real conversions, and the other
  live-infobase tests, run only with `EDT_MCP_LIVE_INFOBASE=1`.

Each mutating test isolates itself with a **git fixture**: it snapshots the
`TestConfiguration/` tree, runs, then restores it (`git checkout` + `git clean`), so a
failed test never leaks artifacts into the next one. The runner reports `fixture clean:
True/False` at the end; a dirty fixture fails the run even if every functional test passed.

**E2E tests cover all tools** — one `tools/test_<tool>.py` per tool (happy path + negative
+ error-quality + anti-cheat), enforced by the coverage ratchet. The unified metadata CRUD
tools are covered by `test_create_metadata.py`, `test_modify_metadata.py`,
`test_delete_metadata.py`, and `test_get_metadata_details.py` (basic / full / `assignable`).

### Launch-preset wire checks

Set `EDT_MCP_TOOL_PRESET=analysis-only` or `EDT_MCP_TOOL_PRESET=code-review` on the **EDT
process before startup**, alongside the other `EDT_MCP_*` launch overrides. Set
`EDT_MCP_PROGRESSIVE_DISCLOSURE=false` there to expose the complete preset. The preset is
session-only: stored Tools preferences are untouched by launch-preset resolution and registry
reads, including migration state. Predefined override IDs are `all-tools`, `analysis-only`,
`code-review`, and `development`. IDs are case-insensitive and trimmed.
Resolution happens once on first use, including bridge calls and manual starts without early
startup. An unset variable, a blank value or `custom` uses the stored selection; explicit
`custom` logs one INFO line explaining that there is no launch override. An unknown ID is
ignored with one WARNING listing only the predefined override IDs and the stored-selection hint.

The Tools tab loads saved settings, runs their normal migrations, and shows the active launch
preset in a read-only note. Unchanged OK/Apply preserves the saved selection and preset ID;
intentional edits are saved for a launch without the override and do not change this session.
Use a fresh workspace for each CI preset launch: these wire checks prove the advertised
catalogue, while the unit tests prove that a session override is not persisted.

Launch EDT separately for each read-only preset, and pass the **same** preset to the test
process. With clean fixtures, run from `tests/e2e`:

```bash
EDT_MCP_TOOL_PRESET=analysis-only python -m tools.test_preset_launch
# Restart EDT with EDT_MCP_TOOL_PRESET=code-review, then:
EDT_MCP_TOOL_PRESET=code-review python -m tools.test_preset_launch
```

Use `MCP_HOST`, `MCP_PORT`, and `MCP_PROJECT` for non-default harness settings. The module
uses the shared MCP handshake and HTTP client to check `tools/list`: every tool lacking
`readOnlyHint=true` is absent except `compare_configurations`, `enable_toolset`, and
`revalidate_objects`; all six #641 writers are absent. Read-tool controls distinguish the
two presets and reject an empty or narrowed catalogue. It never calls a writer and checks
fixture cleanliness before and after. Without the preset variable it reports a clear skip;
other presets skip this read-only policy check.

Run this module directly for preset launches: `run_all.py` always invokes `clean_project`
during model setup/cleanup, and the read-only presets disable that tool. A normal default
suite discovers these tests and skips them when `EDT_MCP_TOOL_PRESET` is unset. Presets cannot
be switched by the test; each wire check requires an EDT restart.

## Test Configuration

The `tests/TestConfiguration/` directory contains a minimal 1C:Enterprise configuration for testing:

- **Catalog.Catalog** — with ItemForm
- **CommonModule.OK** — empty, valid module
- **CommonModule.Error** — module with intentional error
- **CommonForm.Form** — common form
- **CommonAttribute.CommonAttribute** — common attribute
- **Subsystem.Subsystem** — subsystem
- **SessionParameter.SessionParameter** — session parameter

## GitHub Actions

### build.yml (automatic)
Runs unit tests on every push/PR to master. Test results are published to PR checks.

### e2e-2026.2.yml → e2e-tests.yml (automatic)
`e2e-2026.2.yml` runs on every push/PR to master and calls the reusable `e2e-tests.yml`: on a stock
GitHub-hosted runner it installs a headless EDT 2026.2, imports the four fixture projects through
`EDT_MCP_IMPORT_PROJECTS`, writes the tool profile above and runs the suite in four shards. No 1C
platform is installed, so the live-infobase tests skip.

### Future: Full CI Pipeline
For fully automated E2E on GitHub Actions, the plan is:
1. Build the plugin via `source/compile.sh` (Tycho)
2. Install EDT headless (if a headless runner/Docker image becomes available)
3. Import TestConfiguration
4. Start MCP server
5. Run E2E tests
6. Publish results

## Project Structure

```
EDT-MCP/
├── mcp/
│   ├── bundles/
│   │   └── com.ditrix.edt.mcp.server/        # Main plugin
│   ├── tests/
│   │   ├── pom.xml                             # Tests parent
│   │   └── com.ditrix.edt.mcp.server.tests/   # Unit test fragment
│   │       ├── META-INF/MANIFEST.MF
│   │       ├── pom.xml
│   │       └── src/                            # JUnit tests
│   └── pom.xml                                 # Root (includes tests module)
├── tests/
│   ├── e2e/
│   │   ├── harness.py                          # HTTP/SSE client + git-fixture + asserts
│   │   ├── run_all.py                          # Serial E2E runner (--filter, --junit-xml)
│   │   └── tools/test_<tool>.py                # One black-box test file per tool
│   ├── TestConfiguration/                      # Fixture: base configuration (project TestConfiguration)
│   ├── tests/                                  # Fixture: extension (project TestConfiguration.tests)
│   ├── ExternalObjects/                        # Fixture: external data processors / reports
│   └── SupportedConfiguration/                 # Fixture: configuration on vendor support
└── .github/workflows/
    ├── build.yml                               # CI with unit tests
    ├── e2e-2026.2.yml                          # E2E caller (EDT 2026.2)
    └── e2e-tests.yml                           # Reusable E2E workflow
```
