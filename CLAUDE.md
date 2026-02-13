# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EDT MCP Server — Eclipse RCP plugin for 1C:EDT that implements the Model Context Protocol (MCP), enabling AI assistants to interact with EDT workspaces. Java 17, OSGi architecture, ~5K LOC.

- **Plugin ID:** `com.ditrix.edt.mcp.server`
- **Version:** 1.20.1-SNAPSHOT
- **Target platform:** EDT 2025.2 (Ruby)
- **License:** EPL 2.0

## Critical Rules

- **DO NOT BUILD YOURSELF, ASK THE EXPERT!** The build requires EDT SDK and Tycho — do not run `mvn` commands without user approval.
- **ALL CODE AND INTERFACE MUST BE IN ENGLISH.**
- Before committing, verify `git diff --cached` contains no private data (real project names, paths, IPs, keys).

## Build System

Maven + Tycho 4.0.5 (Eclipse plugin builder). Multi-module structure:

```
mcp/
├── bom/         — Bill of Materials, parent POM with build plugin versions
├── bundles/     — Main plugin bundle (com.ditrix.edt.mcp.server)
├── features/    — Eclipse feature for installation
├── targets/     — Target platform definition (EDT 2025.2)
└── repositories/ — P2 update site output
```

Build: `build.cmd [EDT_INSTALL_DIR]` — auto-detects EDT, injects Directory location into target, runs Maven, restores target. Requires Maven 3.9+ and JDK 17 in PATH.

Build output: `mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository/` (P2 update site) and `.zip` archive.

No test suite in the repository. Manual testing via EDT workspace.

## Architecture

### Core Server

- **Activator** (`server/Activator.java`) — OSGi entry point. Initializes 11 EDT service trackers, creates `McpServer` singleton.
- **McpServer** (`server/McpServer.java`) — Embedded HTTP server (`com.sun.net.httpserver`), 4-thread pool. Endpoints: `POST /mcp` (JSON-RPC), `GET /mcp` (info), `GET /health`. Registers all tools, tracks active tool execution, supports user interrupt signals.
- **McpServerStartup** (`server/McpServerStartup.java`) — `IStartup` hook for auto-start on EDT launch.
- **McpProtocolHandler** (`protocol/McpProtocolHandler.java`) — MCP JSON-RPC 2.0 processor. Handles `initialize`, `tools/list`, `tools/call`. Supports Streamable HTTP transport with SSE.

### Tool System

- **IMcpTool** (`tools/IMcpTool.java`) — Interface all tools implement: `getName()`, `getDescription()`, `getInputSchema()`, `execute(params)`, `getResponseType()`.
- **McpToolRegistry** (`tools/McpToolRegistry.java`) — Thread-safe singleton (ConcurrentHashMap). Tools register/unregister at runtime.
- **26 tool implementations** in `tools/impl/` — each tool is a separate class. Response types: TEXT, JSON, or MARKDOWN. Includes 6 BSL module analysis tools (read_module_source, get_module_structure, list_modules, search_in_code, read_method_source, get_method_call_hierarchy).

### Major Features

- **Tags** (`tags/`) — Persistent metadata tagging stored in `.settings/metadata-tags.yaml`. TagService, UI dialogs, keyboard shortcuts (Ctrl+Alt+1-0), Navigator decorator, refactoring sync.
- **Groups** (`groups/`) — Custom folder hierarchy in Navigator per metadata collection. Stored in `.settings/groups.yaml`. IGroupService interface with internal impl, content/label providers, Navigator filter.
- **User Signals** (`UserSignal.java`, `ui/`) — Status bar with real-time tool execution info. Users can send cancel/retry/background/expert/custom signals to interrupt MCP calls.

### Key Utilities

- **ProjectStateChecker** (`utils/`) — Validates project is open and validation complete before tool execution.
- **LifecycleWaiter** (`utils/`) — Waits for validation completion and project context availability.
- **MarkdownUtils** (`utils/`) — Markdown table escaping for tool responses.
- **BslModuleUtils** (`utils/`) — BSL module operations: file resolution, FQN-to-path mapping, source reading with line ranges, Xtext AST loading, method extraction (signatures, pragmas, regions), project-wide BSL file scanning.
- **Metadata formatters** (`tools/metadata/`) — Format metadata object details for tool responses.

### Preferences

Stored in Eclipse preference store. Constants in `preferences/PreferenceConstants.java`:
- Server port (default 8765), auto-start, check descriptions folder path, plain text mode (Cursor compatibility), default/max result limits.

### Bundled Libraries

Located in `bundles/com.ditrix.edt.mcp.server/lib/`:
- `snakeyaml-2.2.jar` — YAML parsing for tags/groups storage
- `jsoup-1.17.2.jar` — HTML processing for documentation
- `copy-down-1.1.jar` — HTML to Markdown conversion

## Source Layout

All Java source is under `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/`:

| Package | Purpose |
|---------|---------|
| `(root)` | Activator, McpServer, UserSignal, ActiveToolCall |
| `tools/` | IMcpTool, McpToolRegistry |
| `tools/impl/` | 26 tool implementations |
| `tools/metadata/` | Metadata formatting helpers |
| `protocol/` | McpProtocolHandler, constants, JSON schema builder |
| `protocol/jsonrpc/` | JSON-RPC request/response models |
| `preferences/` | Preference page, initializer, constants |
| `tags/` | Tag management service, storage, models |
| `groups/` | Group service, content providers, filter |
| `groups/handlers/` | Navigator group command handlers |
| `groups/ui/` | Group editing dialogs |
| `handlers/` | Navigator toolbar handlers (expand/collapse) |
| `ui/` | Status bar contribution, user signal dialogs |
| `utils/` | Markdown, project state, lifecycle, build utilities |

## Adding a New MCP Tool

1. Create a class implementing `IMcpTool` in `tools/impl/`.
2. Implement `getName()`, `getDescription()`, `getInputSchema()`, `execute()`.
3. Override `getResponseType()` if not MARKDOWN (default).
4. Register in `McpServer.registerTools()` via `toolRegistry.register(new YourTool())`.

## Check Descriptions

The `checks/` directory at repository root contains 100+ markdown files documenting EDT validation checks (e.g., `bsl-variable-name-invalid.md`). These are served by the `get_check_description` tool.

## Client Connection

Default server URL: `http://localhost:8765/mcp`

MCP config files for different clients:
- VS Code: `.vscode/mcp.json` (type: `sse`)
- Claude Code: `.claude.json` (type: `http`)
- Cursor: `.cursor/mcp.json`
- Claude Desktop: `claude_desktop_config.json`
