# EDT-MCP Project Overview

## Purpose
MCP (Model Context Protocol) server plugin for 1C:EDT IDE. Provides HTTP API for AI/LLM clients to interact with EDT projects - metadata analysis, BSL code reading, search, content assist, reference finding, etc.

## Tech Stack
- Java 17 (Eclipse/OSGi plugin)
- Maven 3.9.x with Tycho 4.0.8 (Eclipse plugin build system)
- Eclipse Plugin Architecture (OSGi bundles, MANIFEST.MF)
- 1C:EDT APIs (BSL model, metadata, BM engine)
- HTTP server (com.sun.net.httpserver)
- Gson for JSON, CopyDown for HTML→Markdown

## Project Structure
```
mcp/
├── bom/pom.xml (version BOM)
├── pom.xml (parent POM)
├── bundles/com.ditrix.edt.mcp.server/ (main plugin)
│   ├── META-INF/MANIFEST.MF
│   ├── src/com/ditrix/edt/mcp/server/
│   │   ├── Activator.java (OSGi activator, service trackers)
│   │   ├── McpServer.java (HTTP server, tool registration)
│   │   ├── tools/
│   │   │   ├── IMcpTool.java (tool interface)
│   │   │   ├── McpToolRegistry.java
│   │   │   └── impl/ (all tool implementations)
│   │   ├── protocol/ (JsonSchemaBuilder, JsonUtils, McpConstants, ToolResult)
│   │   └── utils/ (MarkdownUtils)
├── features/ (Eclipse feature)
├── repositories/ (p2 repository)
└── targets/ (target platform)
```

## Code Conventions
- Braces on next line (Allman style)
- //$NON-NLS-1$ comments for all string literals
- Copyright header: `/** Copyright (c) 2026 ... */` (DitriX or Diversus)
- Tools implement IMcpTool interface with getName/getDescription/getInputSchema/execute
- UI thread: Display.syncExec() + AtomicReference for results
- Parameters: JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument
- Schema: JsonSchemaBuilder.object().stringProperty().integerProperty().build()
- Error format: "Error: <message>" string for MARKDOWN response type
- 26 MCP tools registered in McpServer.registerTools()

## Build
- `python compile.py` from project root
- Or: `mvn -f mcp/pom.xml clean verify -DskipTests`
- Produces dist/MCP-EDT.v{VERSION}.zip
