[![GitHub all releases](https://img.shields.io/github/downloads/DitriXNew/EDT-MCP/total)](https://github.com/DitriXNew/EDT-MCP/releases)


[![Build & Unit Tests](https://github.com/DitriXNew/EDT-MCP/actions/workflows/build.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/build.yml)
[![Proxy](https://github.com/DitriXNew/EDT-MCP/actions/workflows/proxy.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/proxy.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=bugs)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=coverage)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)

[![E2E 2026.2](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2026.2.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2026.2.yml)

[![Conformance 2026.2](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2026.2.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2026.2.yml)

# EDT MCP Server

MCP (Model Context Protocol) server plugin for 1C:EDT, enabling AI assistants (Claude, GitHub Copilot, Cursor, etc.) to interact with EDT workspace.
It connects an assistant to the projects, diagnostics, code and metadata already available in your EDT workspace.
Use it to inspect a configuration, make edits, validate changes and work with running applications through MCP tools.
The plugin ships both local stdio and HTTP transports; choose the setup your client supports.

- Browse projects, configuration properties, metadata and BSL modules.
- Read diagnostics, validate queries and revalidate objects.
- Search code, inspect types, follow references and call hierarchies.
- Edit BSL, metadata, forms, text templates and XDTO packages.
- Inspect form layouts and capture screenshots.
- Launch clients normally, minimized, on another screen or isolated (Windows/Linux; hidden on macOS); update databases and manage infobase sessions.
- Debug, profile and run YAXUnit tests.
- Compare configurations against git revisions.
- Organize the Navigator with metadata tags and groups.
- Choose tool presets and reveal toolsets on demand.

## Requirements

- 1C:EDT 2026.2.2 (Ruby); compatibility with earlier versions is not guaranteed
- Runs on the JRE bundled with EDT (the plugin runtime floor is Java 17); no separate Java installation is needed

See [compatibility details](docs/features.md) and [CI details](docs/building.md) for the supported platform and build baseline.

## Installation

In EDT, choose **Help → Install New Software...**, add `https://ditrixnew.github.io/EDT-MCP/`,
select **EDT MCP Server Feature**, and restart EDT.

For a one-shot Windows install, close EDT and run this with your actual executable path:

```cmd
"<path-to-EDT>\1cedt.exe" -nosplash -application org.eclipse.equinox.p2.director -repository https://ditrixnew.github.io/EDT-MCP/ -installIU com.ditrix.edt.mcp.server.feature.feature.group -profileProperties org.eclipse.update.reconcile=true
```

**Form screenshots:** before enabling `get_form_screenshot`, read the [JVM flag requirements and form-designer effects](docs/installation.md#required-jvm-flag-for-form-screenshots).

See [installation and preferences](docs/installation.md) for screenshots, updates and preferences, and [status-bar controls](docs/status-bar.md).

## Connecting a client

Use `/mcp/plaintext` on the same HTTP port for clients that need full results in `content[0].text`.
For stdio, add `"-resultMode", "plaintext"` to the launcher arguments (`"structured"` gives JSON results a short text digest).
Without an override, `/mcp` and stdio use EDT's **Plain text mode** preference. Clients can share one EDT with different modes.

For local stdio, use **Window > Preferences > MCP Server > General**: clear **Enable MCP over HTTP**,
then click **Copy stdio .mcp.json** and merge the entry into your client's configuration.
Apply to stop HTTP as well. The bundled bootstrap uses EDT's Java and explicit install/workspace paths.
On Linux, stdio only attaches to an already running EDT; on Windows it starts EDT if needed.

For HTTP clients, click **Start** in the EDT status bar before connecting, or enable **Auto-start** in
**Window > Preferences > MCP Server > General** (off by default); see [HTTP startup](docs/installation.md#starting-http).

For HTTP, the default endpoint is `http://localhost:8765/mcp`. The minimal configurations differ as follows:

| Client | Configuration | Setup |
|---|---|---|
| stdio (including Claude Desktop) | Paste the generated entry; no Node.js needed | [Transport and startup limits](docs/stdio.md) |
| VS Code / GitHub Copilot | `.vscode/mcp.json`, top-level `servers` | [VS Code](docs/clients.md#vs-code--github-copilot) |
| Cursor | `.cursor/mcp.json`, top-level `mcpServers`; use `/mcp/plaintext` | [Cursor](docs/clients.md#cursor-ide) |
| Claude Code | `.claude.json`, top-level `mcpServers`, `type: "http"` | [Claude Code](docs/clients.md#claude-code) |
| Claude Desktop over HTTP | Use the `mcp-remote` stdio bridge (requires Node.js) | [Claude Desktop](docs/clients.md#claude-desktop) |
| Cline | Top-level `mcpServers`, `type: "streamableHttp"` | [Cline](docs/clients.md#cline---extension-for-vscode) |
| Antigravity | Top-level `mcpServers`, `serverUrl` instead of `url` | [Antigravity](docs/clients.md#antigravity) |

VS Code / Copilot:

```json
{"servers": {"EDT MCP Server": {"type": "sse", "url": "http://localhost:8765/mcp"}}}
```

Cursor:

```json
{"mcpServers": {"EDT MCP Server": {"url": "http://localhost:8765/mcp"}}}
```

Claude Code:

```json
{"mcpServers": {"EDT MCP Server": {"type": "http", "url": "http://localhost:8765/mcp"}}}
```

Cline:

```json
{"mcpServers": {"EDTMCPServer": {"type": "streamableHttp", "url": "http://localhost:8765/mcp"}}}
```

Antigravity uses:

```json
{"mcpServers": {"EDTMCPServer": {"serverUrl": "http://localhost:8765/mcp"}}}
```

The [client setup guide](docs/clients.md) includes full snippets, `mcp-remote` and the
[Multi-EDT proxy](docs/clients.md#multi-edt-proxy) for routing between workspaces.

## Tools

The generated [full tool reference](docs/tools/README.md) lists every tool, parameter and guide, grouped by toolset.
For enablement, presets and progressive disclosure,
see [tool management](docs/tool-management.md). Toolsets control visibility; preferences control which tools are enabled.

| Toolset | Purpose | Reference |
|---|---|---|
| `core` | Project/module navigation, source reads and tool discovery | [Core](docs/tools/README.md#core) |
| `metadata` | Discover, create, edit and refactor metadata and DCS | [Metadata](docs/tools/README.md#metadata) |
| `code` | Read/write methods, navigate symbols and inspect calls | [Code](docs/tools/README.md#code) |
| `debug` | Breakpoints, stepping, variables and expressions | [Debug](docs/tools/README.md#debug) |
| `testing` | Run and debug YAXUnit tests | [Testing](docs/tools/README.md#testing) |
| `profiling` | Capture, compare and exchange performance profiles; capture and analyze technological logs | [Profiling](docs/tools/README.md#profiling) |
| `forms` | Inspect form layouts, screenshots and templates | [Forms](docs/tools/README.md#forms) |
| `tags` | Read and filter metadata tags | [Tags](docs/tools/README.md#tags) |
| `translation` | Generate and synchronize translation strings | [Translation](docs/tools/README.md#translation) |
| `project` | Diagnostics, applications, infobases and database updates | [Project](docs/tools/README.md#project) |
| `comparison` | Three-way configuration comparison and merge rules | [Comparison](docs/tools/README.md#comparison) |
| `git` | Git commands and branch/infobase bindings | [Git](docs/tools/README.md#git) |

## Security in brief

HTTP binds to `127.0.0.1` unless remote access is configured; remote access requires an auth token.
Treat connected clients as trusted: tools can edit files, update databases and execute debug expressions.
Windowed EDT asks for human consent before gated destructive operations by default.
**Explicit stdio `-headless` allows everything:** per-tool disabling, read-only presets and the consent window do not apply.
Preview/confirm steps and tool preconditions still apply. See the [security and trust model](docs/security.md).

## Documentation

| Article | Contents |
|---|---|
| [Features](docs/features.md) | Full feature list, compatibility and business-project guidance |
| [Installation](docs/installation.md) | Update site, Windows command, JVM flag, HTTP startup and preferences |
| [Status bar](docs/status-bar.md) | Server status, execution controls and user signals |
| [Clients](docs/clients.md) | Every client's full setup, HTTP bridge and Multi-EDT proxy |
| [stdio](docs/stdio.md) | Bundled bootstrap, local transport, startup modes and limits |
| [Tool management](docs/tool-management.md) | Preference groups, presets, defaults and progressive disclosure |
| [Tool reference](docs/tools/README.md) | Generated index linking to every per-tool page |
| [Tool overviews](docs/tool-overviews.md) | Detailed usage summaries grouped by toolset |
| [Output formats and API](docs/output-formats.md) | Markdown/JSON policies, contracts and HTTP endpoints |
| [Security](docs/security.md) | Trust model, destructive consent, vendor support and infobase authentication |
| [Infobase sessions](docs/features/infobase-sessions.md) | Inspect and clear database-update blockers |
| [Three-way comparison](docs/features/comparison.md) | Compare revisions, inspect nodes and author merge rules |
| [Text templates](docs/features/text-templates.md) | Read and safely update TextDocument template contents |
| [XDTO packages](docs/features/xdto-packages.md) | Author package members and inspect validation results |
| [Metadata tags](docs/features/metadata-tags.md) | Assign, display, filter and store tags |
| [Metadata groups](docs/features/metadata-groups.md) | Organize Navigator collections into folders |
| [Technological log](docs/features/tech-log.md) | Capture 1C technological logs and investigate them problems-first |
| [Guided workflows](docs/guided-workflows.md) | Optional MCP prompts, resources and argument completion |
| [Workmate bridge](docs/workmate-bridge.md) | Integrate with the Workmate assistant |
| [E2E test cases](docs/e2e/mcp-test-cases.md) | Manual and automated tool verification scenarios |
| [Building](docs/building.md) | Maven/Tycho prerequisites, scripts, options and examples |
| [Autopilot](docs/autopilot.md) | Whole-task development skill, workflow and token costs |

For business projects, start with the [agent skills pack](agent/README.md) and [rules pack](rules/README.md).
Contributors should read [CLAUDE.md](CLAUDE.md).

## Building from source

See [docs/building.md](docs/building.md) for prerequisites, script options and examples.
Build from the repository root with `bash source/compile.sh` (JDK 25 and Maven 3.9+).
The output is a p2 update-site archive for installation through EDT.

## License
Copyright (C) 2026 DitriX

Licensed under GNU AGPL v3.0
