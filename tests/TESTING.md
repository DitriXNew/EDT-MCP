# Testing EDT MCP Server

## Architecture

The testing infrastructure consists of two layers:

### 1. Unit Tests (Tycho Surefire)

Located in `mcp/tests/com.ditrix.edt.mcp.server.tests/`

These are JUnit 4 tests that run inside the Eclipse/Tycho build without requiring a running EDT instance. They cover:

- **Protocol layer**: `JsonSchemaBuilder`, `JsonUtils`, `GsonProvider`, `McpConstants`
- **JSON-RPC DTOs**: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`
- **Tool results**: `ToolResult`, `ToolCallResult`, `ToolsListResult`

**Running locally:**
```bash
cd mcp
mvn clean verify
```

Unit tests run automatically during the Maven build. Results are in:
```
mcp/tests/com.ditrix.edt.mcp.server.tests/target/surefire-reports/
```

### 2. E2E Tests (Python HTTP client)

Located in `tests/e2e/run_e2e_tests.py`

These tests send real HTTP requests to a running MCP server and validate every tool. They require:
- A running EDT instance with the MCP plugin installed
- The `TestConfiguration` project loaded in EDT

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

**E2E tests cover:**

| Category | Tools |
|----------|-------|
| Protocol | health, initialize, tools/list, error handling |
| Standalone | get_edt_version, list_projects, get_platform_documentation, get_check_description |
| Project | get_configuration_properties, get_metadata_objects, get_metadata_details, get_problem_summary, get_project_errors, get_tags, get_bookmarks, get_tasks |
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
1. Build the plugin via Tycho
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
