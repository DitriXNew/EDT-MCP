# Testing EDT MCP Server

## Architecture

The testing infrastructure consists of a build step plus two test layers:

### 0. Build

**Requirements:** JDK 17 is mandatory. The project targets `JavaSE-17`, and the Tycho 4.0.5 / Eclipse Compiler (ECJ 3.36) build fails on newer JDKs (e.g. JDK 25 fails with `Cannot invoke "java.nio.file.FileSystem.getPath(...)" because "this.fs" is null`). Make sure `JAVA_HOME` points to a JDK 17 before building.

The plugin must be built with the official build script `source/compile.sh`. It runs the Tycho build (`mvn clean verify`, which also executes the unit tests) and packages the p2 update site into a versioned zip:
```bash
bash source/compile.sh --java-home /usr/lib/jvm/java-17-openjdk-amd64   # adjust JDK 17 path
```
Outputs:
- update-site zip: `source/dist/MCP-EDT.v<version>.zip`
- p2 repository: `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository`

To run only the unit tests (without packaging the archive) you can invoke Maven directly:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # adjust to your JDK 17 path
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

Located in `tests/e2e/run_e2e_tests.py`

These tests send real HTTP requests to a running MCP server and validate every tool. They require:
- A running EDT instance with the MCP plugin installed
- The `TestConfiguration` project loaded in EDT

**Prerequisite — install the plugin in EDT manually.** Build it first (see
**0. Build**). There is currently no automated/headless installation, so the
plugin must be installed by hand before running E2E:

1. In EDT: **Help → Install New Software… → Add… → Archive…**, select
   `source/dist/MCP-EDT.v<version>.zip` (or **Local…** → the `repository` folder),
   install `com.ditrix.edt.mcp.server.feature`, restart EDT.
2. Import the `TestConfiguration` project into the EDT workspace and wait until
   derived data is built.
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
python tests/e2e/run_e2e_tests.py

# Or with custom settings:
python tests/e2e/run_e2e_tests.py --host localhost --port 8765 --project TestConfiguration

# Wait for server to start (useful for CI):
python tests/e2e/run_e2e_tests.py --wait 300

# Generate JUnit XML report:
python tests/e2e/run_e2e_tests.py --junit-xml results.xml
```

When all E2E tests pass, the runner automatically **cleans up** artifacts it created
(`E2EChk*` metadata objects): deletes each object via `delete_metadata_object`,
runs `clean_project`, then verifies every object is gone via `get_metadata_details`.
If cleanup fails, the process exits with code 1 even though the functional tests passed.

**E2E tests cover:**

| Category | Tools |
|----------|-------|
| Protocol | health, initialize, tools/list, error handling |
| Standalone | get_edt_version, list_projects, get_platform_documentation, get_check_description |
| Project | get_configuration_properties, get_metadata_objects, get_metadata_details, get_problem_summary, get_project_errors, get_tags, get_bookmarks, get_tasks, create_metadata_object |
| BSL Code | list_modules, get_module_structure, read_module_source, read_method_source, search_in_code |
| Advanced | find_references, get_applications, get_form_screenshot |

## Test Configuration

The `TestConfiguration/` directory contains a minimal 1C:Enterprise configuration for testing:

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

### e2e-tests.yml (manual)
Triggered via `workflow_dispatch`. Requires a running MCP server (self-hosted runner or tunnel).

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
│   └── e2e/
│       └── run_e2e_tests.py                    # E2E test script
├── TestConfiguration/                          # Test 1C configuration
│   └── src/
└── .github/workflows/
    ├── build.yml                               # CI with unit tests
    └── e2e-tests.yml                           # E2E test workflow
```
