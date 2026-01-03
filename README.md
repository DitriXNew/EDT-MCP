# EDT MCP Server

MCP (Model Context Protocol) server plugin for 1C:EDT, enabling AI assistants like Claude, GitHub Copilot, and others to interact with EDT workspace.

## Features

- 🔧 **MCP Protocol Support** - Full JSON-RPC implementation of MCP protocol version 2025-03-26
- 📊 **Project Information** - List all workspace projects with their properties
- ⚙️ **Configuration Access** - Get 1C:Enterprise configuration properties
- 🔴 **Error Reporting** - Get project errors, warnings, problem summaries with filters
- � **Check Descriptions** - Get check documentation from markdown files
- �🔄 **Project Revalidation** - Trigger project revalidation when validation gets stuck
- 🔖 **Bookmarks** - Access workspace bookmarks with filters
- 📝 **Tasks** - Get TODO, FIXME, and other task markers
- 🎯 **Status Bar Integration** - Real-time server status indicator in EDT status bar
- 🔄 **Auto-start Option** - Automatically start server when EDT launches

## Installation

### From Update Site

1. In EDT, go to **Help → Install New Software...**
2. Add update site: `https://your-update-site-url/`
3. Select **EDT MCP Server Feature**
4. Click **Next** and follow the installation wizard
5. Restart EDT when prompted

### From ZIP File

1. Download the plugin ZIP file
2. In EDT, go to **Help → Install New Software...**
3. Click **Add... → Archive...**
4. Select the downloaded ZIP file
5. Follow the installation wizard

## Configuration

After installation, go to **Window → Preferences → MCP Server**:

- **Server Port**: HTTP port for MCP server (default: 8765)
- **Check descriptions folder**: Path to folder containing check description markdown files
- **Automatically start with EDT**: Enable to start server on EDT launch
- **Start/Stop/Restart**: Manual server control buttons

## Connecting to VS Code

### Method 1: Using MCP Extension

1. Install the [MCP Extension](https://marketplace.visualstudio.com/items?itemName=anthropic.claude-vscode) in VS Code
2. Add the following to your VS Code settings (`.vscode/settings.json` or global settings):

```json
{
  "mcp.servers": {
    "edt-mcp-server": {
      "type": "http",
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

### Method 2: Manual Configuration

Create a file `mcp.json` in your project root:

```json
{
  "mcpServers": {
    "edt-mcp-server": {
      "type": "http", 
      "url": "http://localhost:8765/mcp",
      "transport": "http"
    }
  }
}
```

## Available Tools

### get_edt_version

Returns the current 1C:EDT version.

**Parameters:** None

**Example Response:**
```json
{
  "content": [{"type": "text", "text": "2025.2.0.454"}]
}
```

### list_projects

Lists all workspace projects with their properties.

**Parameters:** None

**Example Response:**
```json
{
  "content": [{
    "type": "text", 
    "text": [
      {
        "name": "MyConfiguration",
        "path": "C:\\Projects\\MyConfiguration",
        "open": true,
        "accessible": true,
        "edtProject": true,
        "natures": ["com._1c.g5.v8.dt.core.V8ConfigurationNature"]
      }
    ]
  }]
}
```

### get_configuration_properties

Returns detailed 1C:Enterprise configuration properties.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Project name. If not specified, returns first configuration project |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "name": "DemoConfiguration",
      "synonym": {"en": "Demo Configuration", "ru": "Демо Конфигурация"},
      "scriptVariant": "English",
      "defaultRunMode": "ManagedApplication",
      "dataLockControlMode": "Managed",
      "compatibilityMode": "8.5.1",
      "modalityUseMode": "DontUse",
      "usePurposes": ["PersonalComputer"],
      "vendor": "My Company",
      "version": "1.0.0",
      "projectName": "DemoConfiguration"
    }
  }]
}
```

### revalidate_project

Triggers full project revalidation. Useful when validation gets stuck.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Project name. If not specified, revalidates all EDT projects |
| clean | boolean | No | If true, performs clean build (default: false) |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "projectsTriggered": 1,
      "projects": "MyConfiguration",
      "buildType": "INCREMENTAL",
      "message": "Revalidation triggered. Check project markers for results."
    }
  }]
}
```

### get_problem_summary

Returns problem counts grouped by project and EDT severity level.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Filter by project name |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "totals": {
        "ERRORS": 0,
        "BLOCKER": 0,
        "CRITICAL": 0,
        "MAJOR": 3,
        "MINOR": 1,
        "TRIVIAL": 0,
        "total": 4
      },
      "projects": [
        {
          "project": "MyConfiguration",
          "ERRORS": 0,
          "BLOCKER": 0,
          "CRITICAL": 0,
          "MAJOR": 3,
          "MINOR": 1,
          "TRIVIAL": 0,
          "total": 4
        }
      ]
    }
  }]
}
```

### get_project_errors

Returns detailed EDT configuration problems (check violations) with optional filters.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Filter by project name |
| severity | string | No | Filter by EDT severity: "ERRORS", "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL" |
| checkId | string | No | Filter by check ID substring (e.g. "ql-temp-table-index", "method-param") |
| limit | integer | No | Maximum results (default: 100, max: 1000) |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "count": 4,
      "limit": 100,
      "hasMore": false,
      "errors": [
        {
          "project": "MyConfiguration",
          "checkId": "SU137",
          "message": "New temporary table should have indexes",
          "severity": "MAJOR",
          "location": "Query",
          "object": "Report.MyReport.Template.Template.Template"
        },
        {
          "project": "MyConfiguration",
          "checkId": "SU79",
          "message": "Feature access \"Value\" has no return type",
          "severity": "MAJOR",
          "location": "line 17",
          "object": "Document.MyDocument.Form.DocumentForm.Form.Module"
        }
      ]
    }
  }]
}
```

### get_bookmarks

Returns bookmarks from the workspace.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Filter by project name |
| filePath | string | No | Filter by file path substring |
| limit | integer | No | Maximum results (default: 100, max: 1000) |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "count": 2,
      "limit": 100,
      "hasMore": false,
      "bookmarks": [
        {
          "project": "MyConfiguration",
          "message": "Important function",
          "path": "/MyConfiguration/src/CommonModules/MyModule/Module.bsl",
          "line": 42
        }
      ]
    }
  }]
}
```

### get_tasks

Returns tasks (TODO, FIXME, etc.) from the workspace.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| projectName | string | No | Filter by project name |
| filePath | string | No | Filter by file path substring |
| priority | string | No | Filter by priority: "high", "normal", "low" |
| limit | integer | No | Maximum results (default: 100, max: 1000) |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "count": 3,
      "limit": 100,
      "hasMore": false,
      "tasks": [
        {
          "project": "MyConfiguration",
          "message": "TODO: Implement validation",
          "path": "/MyConfiguration/src/CommonModules/MyModule/Module.bsl",
          "line": 15,
          "priority": "normal",
          "type": "TODO"
        },
        {
          "project": "MyConfiguration",
          "message": "FIXME: Performance issue",
          "path": "/MyConfiguration/src/CommonModules/MyModule/Module.bsl",
          "line": 30,
          "priority": "high",
          "type": "FIXME"
        }
      ]
    }
  }]
}
```

### get_check_description

Returns the description of an EDT check by its check ID. Check descriptions are read from markdown files stored in a configurable folder.

**Configuration:** 
Go to **Window → Preferences → MCP Server** and set the **Check descriptions folder** to the folder containing `.md` files with check descriptions. Each file should be named `{checkId}.md` (e.g., `begin-transaction.md`).

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| checkId | string | Yes | The check ID to get description for (e.g., "begin-transaction", "ql-temp-table-index") |

**Example Response:**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": true,
      "checkId": "begin-transaction",
      "description": "# Begin Transaction\n\n## Description\n\nThis check verifies that BeginTransaction() is properly matched with CommitTransaction() or RollbackTransaction()...\n\n## How to Fix\n\n..."
    }
  }]
}
```

**Error Response (check not found):**
```json
{
  "content": [{
    "type": "text",
    "text": {
      "success": false,
      "checkId": "unknown-check",
      "error": "Check description file not found: unknown-check.md"
    }
  }]
}
```

### POST /mcp

Main MCP JSON-RPC endpoint. Accepts standard MCP protocol messages.

**Supported Methods:**
- `initialize` - Initialize MCP session
- `tools/list` - List available tools
- `tools/call` - Execute a tool

### GET /mcp

Returns server information:
```json
{
  "name": "edt-mcp-server",
  "version": "1.2.3",
  "edt_version": "2025.2.0.454",
  "status": "running"
}
```

### GET /health

Health check endpoint:
```json
{
  "status": "ok",
  "edt_version": "2025.2.0.454"
}
```

## Testing with curl

```bash
# Initialize
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"test"}},"id":1}'

# List tools
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":2}'

# Get EDT version
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_edt_version"},"id":3}'

# List projects
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"list_projects"},"id":4}'

# Get configuration properties
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_configuration_properties","arguments":{"projectName":"MyProject"}},"id":5}'

# Revalidate project
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"revalidate_project","arguments":{"projectName":"MyProject","clean":false}},"id":6}'

# Get problem summary
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_problem_summary"},"id":7}'

# Get project errors (with filters)
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_project_errors","arguments":{"severity":"MAJOR","limit":50}},"id":8}'

# Get project errors by check ID
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_project_errors","arguments":{"checkId":"ql-temp-table"}},"id":9}'

# Get bookmarks
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_bookmarks"},"id":10}'

# Get tasks (TODO, FIXME)
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_tasks","arguments":{"priority":"high"}},"id":11}'

# Get check description
curl -X POST http://localhost:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_check_description","arguments":{"checkId":"begin-transaction"}},"id":12}'
```

## Status Bar

The plugin adds a status indicator to the EDT status bar:

- 🟢 **Green circle** - Server is running
- ⚫ **Grey circle** - Server is stopped
- **[N]** - Number of processed requests

Click on the circle to access Start/Stop/Restart menu.

## Architecture

```
com.ditrix.edt.mcp.server/
├── Activator.java                    # Plugin lifecycle, service trackers
├── McpServer.java                    # HTTP server
├── protocol/
│   ├── McpConstants.java             # Protocol constants
│   ├── McpProtocolHandler.java       # JSON-RPC handler
│   └── JsonUtils.java                # JSON utilities
├── tools/
│   ├── IMcpTool.java                 # Tool interface
│   ├── McpToolRegistry.java          # Tool registry
│   └── impl/
│       ├── GetEdtVersionTool.java         # EDT version
│       ├── ListProjectsTool.java          # Project listing
│       ├── GetConfigurationPropertiesTool.java  # 1C configuration properties
│       ├── GetProjectErrorsTool.java      # EDT configuration problems (IMarkerManager)
│       ├── GetProblemSummaryTool.java     # Problem counts by severity
│       ├── RevalidateProjectTool.java     # Project revalidation
│       ├── GetBookmarksTool.java          # Workspace bookmarks
│       ├── GetTasksTool.java              # TODO/FIXME tasks
│       └── GetCheckDescriptionTool.java   # Check descriptions from .md files
├── ui/
│   └── McpStatusContribution.java    # Status bar widget
└── preferences/
    ├── PreferenceConstants.java
    ├── PreferenceInitializer.java
    └── McpServerPreferencePage.java
```

## Adding Custom Tools

To add a new tool, implement the `IMcpTool` interface:

```java
public class MyCustomTool implements IMcpTool {
    
    @Override
    public String getName() {
        return "my_custom_tool";
    }
    
    @Override
    public String getDescription() {
        return "Description of my tool";
    }
    
    @Override
    public String getInputSchema() {
        return "{\"type\": \"object\", \"properties\": {" +
            "\"param1\": {\"type\": \"string\", \"description\": \"First parameter\"}" +
            "}, \"required\": [\"param1\"]}";
    }
    
    @Override
    public String execute(Map<String, String> params) {
        String param1 = params.get("param1");
        // Your logic here
        return "{\"result\": \"success\"}";
    }
}
```

Register in `McpServer.registerTools()`:
```java
registry.register(new MyCustomTool());
```

## Requirements

- 1C:EDT 2025.2 (Ruby) or later
- Java 17+

## Version History

### 1.2.4
- Added get_check_description tool for retrieving check documentation
- Added preference for configuring check descriptions folder path

### 1.2.0
- Integrated EDT IMarkerManager API for configuration problems
- Added EDT severity levels (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
- Added checkId field to error responses

### 1.1.0
- Refactored architecture with tool registry pattern
- Improved status bar with colored circle indicator
- Better error handling and logging
- Preparation for future tool extensions

### 1.0.0
- Initial release
- Basic MCP protocol support
- Three core tools: get_edt_version, list_projects, get_configuration_properties

## License

Copyright (c) 2025 DitriX. All rights reserved.

## Author

**DitriX** - [GitHub](https://github.com/ditrix)

---

*EDT MCP Server v1.2.4*
