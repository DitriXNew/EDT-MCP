# EDT MCP Server

MCP (Model Context Protocol) server plugin for 1C:EDT, enabling AI assistants (Claude, GitHub Copilot, Cursor, etc.) to interact with EDT workspace.

## Features

- 🔧 **MCP Protocol 2025-11-25** - Streamable HTTP transport with SSE support
- 📊 **Project Information** - List workspace projects and configuration properties
- 🔴 **Error Reporting** - Get errors, warnings, problem summaries with filters
- 📝 **Check Descriptions** - Get check documentation from markdown files
- 🔄 **Project Revalidation** - Trigger revalidation when validation gets stuck
- 🔖 **Bookmarks & Tasks** - Access bookmarks and TODO/FIXME markers
- 💡 **Content Assist** - Get type info, method hints and platform documentation at any code position
- 🎯 **Status Bar** - Real-time server status indicator with request counter

## Installation

### From Update Site

1. In EDT: **Help → Install New Software...**
2. Add update site URL
3. Select **EDT MCP Server Feature**
4. Restart EDT

### Configuration

Go to **Window → Preferences → MCP Server**:
- **Server Port**: HTTP port (default: 8765)
- **Check descriptions folder**: Path to check description markdown files
- **Auto-start**: Start server on EDT launch
- **Plain text mode (Cursor compatibility)**: Returns results as plain text instead of embedded resources (for AI clients that don't support MCP resources)

## Connecting AI Assistants

### VS Code / GitHub Copilot

Create `.vscode/mcp.json`:
```json
{
  "servers": {
    "EDT MCP Server": {
      "type": "sse",
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

### Cursor IDE

> **Note:** Cursor doesn't support MCP embedded resources. Enable **"Plain text mode (Cursor compatibility)"** in EDT preferences: **Window → Preferences → MCP Server**.

Create `.cursor/mcp.json`:
```json
{
  "mcpServers": {
    "EDT MCP Server": {
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

### Claude Desktop

Add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "EDT MCP Server": {
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

## Available Tools

| Tool | Description |
|------|-------------|
| `get_edt_version` | Returns current EDT version |
| `list_projects` | Lists workspace projects with properties |
| `get_configuration_properties` | Gets 1C configuration properties |
| `get_project_errors` | Returns EDT problems with severity/checkId/objects filters |
| `get_problem_summary` | Problem counts grouped by project and severity |
| `clean_project` | Cleans project markers and triggers full revalidation |
| `revalidate_objects` | Revalidates specific objects by FQN (e.g. "Document.MyDoc") |
| `get_bookmarks` | Returns workspace bookmarks |
| `get_tasks` | Returns TODO/FIXME task markers |
| `get_check_description` | Returns check documentation from .md files |
| `get_content_assist` | Get content assist proposals (type info, method hints) |
| `get_platform_documentation` | Get platform type documentation (methods, properties, constructors) |
| `get_metadata_objects` | Get list of metadata objects from 1C configuration |
| `get_metadata_details` | Get detailed properties of metadata objects (attributes, tabular sections, etc.) |
| `find_references` | Find all references to a metadata object (in metadata, BSL code, forms, roles, etc.) |

### Content Assist Tool

**`get_content_assist`** - Get content assist proposals at a specific position in BSL code. Returns type information, available methods, properties, and platform documentation.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `filePath` | Yes | Path relative to `src/` folder (e.g. `CommonModules/MyModule/Module.bsl`) |
| `line` | Yes | Line number (1-based) |
| `column` | Yes | Column number (1-based) |
| `limit` | No | Maximum proposals to return (default: from preferences) |
| `offset` | No | Skip first N proposals (for pagination, default: 0) |
| `contains` | No | Filter by display string containing these substrings (comma-separated, e.g. `Insert,Add`) |
| `extendedDocumentation` | No | Return full documentation (default: false, only display string) |

**Important Notes:**
1. **Save the file first** - EDT must read the current content from disk to provide accurate proposals
2. **Column position** - Place cursor after the dot (`.`) for method/property suggestions
3. **Pagination** - Use `offset` to get next batch of proposals (e.g., first call with limit=5, second call with offset=5, limit=5)
4. **Filtering** - Use `contains` to filter by method/property name (case-insensitive)
5. **Works for:**
   - Global platform methods (e.g. `NStr(`, `Format(`)
   - Methods after dot (e.g. `Structure.Insert`, `Array.Add`)
   - Object properties and fields
   - Configuration objects and modules

**Example - Get methods for Structure with filter:**
```json
{
  "projectName": "MyProject",
  "filePath": "CommonModules/MyCommonModule/Module.bsl",
  "line": 15,
  "column": 12,
  "contains": "Insert,Add",
  "extendedDocumentation": true
}
```

Returns only methods containing "Insert" or "Add" with full documentation:
```json
{
  "success": true,
  "totalProposals": 8,
  "filteredOut": 6,
  "skipped": 0,
  "returnedProposals": 2,
  "proposals": [
    {
      "displayString": "Insert(Key) ~ Structure",
      "documentation": "Procedure Structure.Insert(Key, [Value])..."
    }
  ]
}
```

### Validation Tools

- **`clean_project`**: Refreshes project from disk, clears all validation markers, and triggers full revalidation using EDT's ICheckScheduler
- **`revalidate_objects`**: Revalidates specific metadata objects by their FQN:
  - `Document.MyDocument`, `Catalog.MyCatalog`, `CommonModule.MyModule`
  - `Document.MyDoc.Form.MyForm` for nested objects

### Project Errors Tool

**`get_project_errors`** - Get detailed configuration problems from EDT with multiple filter options.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | No | Filter by project name |
| `severity` | No | Filter by severity: `ERRORS`, `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `TRIVIAL` |
| `checkId` | No | Filter by check ID substring (e.g. `ql-temp-table-index`) |
| `objects` | No | Filter by object FQNs (array). Returns errors only from specified objects |
| `limit` | No | Maximum results (default: 100, max: 1000) |

**Objects filter format:**
- Array of FQN strings: `["Document.SalesOrder", "Catalog.Products"]`
- Case-insensitive partial matching
- Matches against error location (objectPresentation)
- FQN examples:
  - `Document.SalesOrder` - all errors in document
  - `Catalog.Products` - all errors in catalog
  - `CommonModule.MyModule` - all errors in common module
  - `Document.SalesOrder.Form.ItemForm` - errors in specific form

**Example - Get errors for specific objects:**
```json
{
  "projectName": "MyProject",
  "objects": ["Document.SalesOrder", "Catalog.Products"],
  "severity": "BLOCKER"
}
```

**Example - Get all errors with check ID filter:**
```json
{
  "projectName": "MyProject",
  "checkId": "ql-temp-table"
}
```

### Platform Documentation Tool

**`get_platform_documentation`** - Get documentation for platform types (ValueTable, Array, Structure, Query, etc.) and built-in functions (FindFiles, Message, Format, etc.)

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `typeName` | Yes | Type or function name (e.g. `ValueTable`, `Array`, `FindFiles`, `Message`) |
| `category` | No | Category: `type` (platform types), `builtin` (built-in functions). Default: `type` |
| `projectName` | No | EDT project name (uses first available project if not specified) |
| `memberName` | No | Filter by member name (partial match) - only for `type` category |
| `memberType` | No | Filter: `method`, `property`, `constructor`, `event`, `all` (default: `all`) - only for `type` category |
| `language` | No | Output language: `en` or `ru` (default: `en`) |
| `limit` | No | Maximum results (default: 50) - only for `type` category |

**Example - Platform type documentation:**
```json
{
  "typeName": "ValueTable",
  "memberType": "method",
  "memberName": "Add",
  "language": "ru"
}
```

**Example - Built-in function documentation:**
```json
{
  "typeName": "FindFiles",
  "category": "builtin",
  "language": "en"
}
```

Returns function signature with parameters, types, and return value.

### Metadata Objects Tool

**`get_metadata_objects`** - Get list of metadata objects from 1C configuration.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `metadataType` | No | Filter: `all`, `documents`, `catalogs`, `informationRegisters`, `accumulationRegisters`, `commonModules`, `enums`, `constants`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `commonAttributes`, `eventSubscriptions`, `scheduledJobs` (default: `all`) |
| `nameFilter` | No | Partial name match filter (case-insensitive) |
| `limit` | No | Maximum results (default: 100) |
| `language` | No | Language code for synonyms (e.g. `en`, `ru`). Uses configuration default if not specified |

**Example:**
```json
{
  "projectName": "MyProject",
  "metadataType": "documents",
  "nameFilter": "Sales"
}
```

Returns markdown table with columns: Name, Synonym, Comment, Type, ObjectModule, ManagerModule.

### Metadata Details Tool

**`get_metadata_details`** - Get detailed properties of metadata objects.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqns` | Yes | Array of FQNs (e.g. `["Catalog.Products", "Document.SalesOrder"]`) |
| `full` | No | Return all properties (`true`) or only key info (`false`). Default: `false` |
| `language` | No | Language code for synonyms. Uses configuration default if not specified |

**Example:**
```json
{
  "projectName": "MyProject",
  "objectFqns": ["Document.SalesOrder", "Catalog.Products"],
  "full": true
}
```

Returns markdown with detailed object properties, attributes, tabular sections, forms, commands.

### Find References Tool

**`find_references`** - Find all references to a metadata object. Returns all places where the object is used: in other metadata objects, BSL code, forms, roles, subsystems, etc. Matches EDT's built-in "Find References" functionality.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqn` | Yes | Fully qualified name (e.g. `Catalog.Products`, `Document.SalesOrder`, `CommonModule.Common`) |
| `limit` | No | Maximum results per category (default: 100, max: 500) |

**Example:**
```json
{
  "projectName": "MyProject",
  "objectFqn": "Catalog.Products"
}
```

**Returns markdown with references in EDT-compatible format:**

```markdown
# References to Catalog.Items

**Total references found:** 122

- Catalog.ItemKeys - Attributes.Item.Type - Type: types
- Catalog.ItemKeys.Form.ChoiceForm.Form - Items.List.Item.Data path - Type: types
- Catalog.Items - Attributes.PackageUnit.Choice parameter links - Ref
- Catalog.Items.Form.ItemForm.Form - Items.GroupTop.GroupMainAttributes.Code.Data path - Type: types
- CommonAttribute.Author - Content - metadata
- Configuration - Catalogs - catalogs
- DefinedType.typeItem - Type - Type: types
- EventSubscription.BeforeWrite_CatalogsLockDataModification - Source - Type: types
- Role.FullAccess.Rights - Role rights - object
- Subsystem.Settings.Subsystem.Items - Content - content

### BSL Modules

- CommonModules/GetItemInfo/Module.bsl [Line 199; Line 369; Line 520]
- Catalogs/Items/Forms/ListForm/Module.bsl [Line 18; Line 19]
```

**Reference types included:**
- **Metadata references** - Attributes, form items, command parameters, type descriptions
- **Type usages** - DefinedTypes, ChartOfCharacteristicTypes, type compositions
- **Common attributes** - Objects included in common attribute content
- **Event subscriptions** - Source objects for subscriptions
- **Roles** - Objects with role permissions
- **Subsystems** - Subsystem content
- **BSL code** - References in BSL modules with line numbers

### Output Formats

- **Markdown tools**: `list_projects`, `get_project_errors`, `get_bookmarks`, `get_tasks`, `get_problem_summary`, `get_check_description` - return Markdown as EmbeddedResource with `mimeType: text/markdown`
- **JSON tools**: `get_configuration_properties`, `clean_project`, `revalidate_objects` - return JSON with `structuredContent`
- **Text tools**: `get_edt_version` - return plain text

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | POST | MCP JSON-RPC (initialize, tools/list, tools/call) |
| `/mcp` | GET | Server info |
| `/health` | GET | Health check |

## Status Bar

Click the status indicator in EDT status bar:
- 🟢 Green - Server running
- ⚫ Grey - Server stopped
- **[N]** - Request counter

## Requirements

- 1C:EDT 2025.2 (Ruby) or later
- Java 17+

## Version History

### 1.17.0
- **New**: `find_references` tool - Find all references to a metadata object
  - Returns all places where the object is used: roles, subsystems, forms, type descriptions, etc.
  - Results grouped by category (Subsystems, Roles, Forms, Type descriptions, etc.)
  - Searches through produced types, predefined items, fields
  - Note: BSL code references will be added in future version
### 1.16.0  
- **New**: "Plain text mode (Cursor compatibility)" preference setting
  - When enabled, returns Markdown results as plain text instead of embedded resources
  - Solves compatibility issues with AI clients that don't support MCP embedded resources (e.g., Cursor)
  - Located in: **Window → Preferences → MCP Server**
- **New**: `objects` filter parameter for `get_project_errors` tool
  - Filter errors by specific object FQNs (e.g. `["Document.SalesOrder", "Catalog.Products"]`)
  - Returns only errors from the specified objects
  - FQN matching is case-insensitive and supports partial matches
- **New**: Built-in function documentation in `get_platform_documentation` tool
  - Use `category: "builtin"` to get documentation for global functions (FindFiles, Message, Format, etc.)
  - Returns function signature with parameters, types, optional flags, and return type
  - Supports both English and Russian function names

### 1.9.0
- **Improved**: Enhanced EObject formatting in metadata tools using new `EObjectInspector` utility
  - Smart detection of simple value holders (enums, wrappers) vs complex objects needing expansion
  - Automatic extraction of primary values from wrapper classes (e.g., StandardCommandGroup → category enum)
  - EMF-based detection without hardcoded class names using EAttribute/EReference analysis
  - Better formatting for StandardCommandGroup, Color, Picture, and other wrapper types
- **Internal**: New `EObjectInspector` utility class for EMF EObject type analysis
  - `getFormatStyle()` - Determines SIMPLE_VALUE, REFERENCE, or EXPAND formatting
  - `isSimpleValueHolder()` - Checks if EClass/EObject is a simple wrapper
  - `getPrimaryValue()` - Extracts meaningful value from wrapper objects
  - `formatReference()` - Smart reference formatting for any EObject type
- **Refactored**: `AbstractMetadataFormatter` and `UniversalMetadataFormatter` now use EObjectInspector

### 1.8.0
- **New**: `get_metadata_objects` tool - Get list of metadata objects from 1C configuration
  - Returns Name, Synonym, Comment, Type, ObjectModule, ManagerModule for each object
  - Supports filtering by metadata type (documents, catalogs, registers, commonModules, commonAttributes, eventSubscriptions, scheduledJobs, etc.)
  - Supports partial name filtering (case-insensitive)
  - Uses configuration default language for synonyms
- **New**: `get_metadata_details` tool - Get detailed properties of metadata objects
  - Accepts array of FQNs (e.g. `["Catalog.Products", "Document.SalesOrder"]`)
  - `full` mode for complete property details
  - Type-specific properties (Document: posting, Catalog: hierarchy, Register: periodicity, etc.)

### 1.7.0
- **New**: `get_platform_documentation` tool - Get platform type documentation
  - Returns methods, properties, constructors, events with full documentation
  - Supports all platform types: ValueTable, Array, Structure, Query, Map, etc.
  - Filter by member name or type (method/property/constructor/event)
  - Bilingual output (English/Russian)
  - Uses EDT's IEObjectProvider with TYPE provider for accurate results

### 1.6.16
- **New**: `get_content_assist` tool - Get content assist proposals at any code position
  - Returns type information, methods, properties with full platform documentation
  - Supports global methods and dot-notation methods
  - Pagination with `offset` parameter for large result sets
  - Filtering with `contains` parameter (case-insensitive, comma-separated)
  - Optional extended documentation (default: disabled for faster responses)
  - Uses EDT's ICompletionProposalExtension5 for async documentation retrieval
  - HTML documentation converted to Markdown using CopyDown library
- **Refactored**: Extracted common `escapeForTable()` to `MarkdownUtils` class
- **Fixed**: Removed dead code (`tools/BuildUtils.java`)

### 1.6.10
- **Refactored**: All JSON responses now use Gson serialization instead of manual StringBuilder
- **New**: `ToolResult` class - fluent API for building JSON responses
- **Improved**: More reliable JSON output (no more missing braces or escaping issues)
- **Internal**: Uses `JsonParser.parseString()` for input JSON array parsing

### 1.6.9
- Fixed: `revalidate_objects` JSON response was missing closing brace causing parse error

### 1.6.8
- Fixed: `revalidate_objects` now uses 4-parameter scheduleValidation (without IBmTransaction) to avoid null transaction error

### 1.6.7
- Fixed: `revalidate_objects` now uses bmGetId() (Long) instead of URI - fixes NullPointerException in CheckScheduler

### 1.6.6
- Added: Custom PNG icons for Start (green), Stop (red), Restart (blue) buttons
- Improved: Additional defensive null filtering in revalidate_objects
- Added: Debug logging for object lookup in revalidate_objects

### 1.6.5
- Fixed: Port and checks folder changes now apply immediately when clicking Start/Restart (no need to click Apply first)
- Fixed: Restart button now has a distinct icon (Redo instead of disabled Synced)

### 1.6.4
- Fixed: `revalidate_objects` null URI handling (objects with null bmGetUri() are now skipped)
- Fixed: Full project revalidation when objects array is empty
- Improved: Service retrieval via Activator (not static fields)
- Added: JSON field `objectsSkippedNullUri` for objects with null URI

### 1.6.3
- Fixed: `clean_project` now uses Eclipse CLEAN_BUILD (same as Project → Clean menu)

### 1.6.2
- Fixed: `revalidate_objects` now passes object URI to scheduleValidation (was ClassCastException)

### 1.6.1
- Fixed: Array parameters parsing for `revalidate_objects` tool
- Fixed: Removed JSON duplication in text field (now only in structuredContent)
- Improved: FQN examples in tool descriptions

### 1.6.0
- **New**: `clean_project` tool - clears markers and triggers full revalidation via EDT ICheckScheduler
- **New**: `revalidate_objects` tool - revalidates specific objects by FQN
- Removed old `revalidate_project` (used Eclipse build, not EDT validation)
- Added ICheckScheduler and IBmModelManager service integration

### 1.5.1
- Dynamic file names for EmbeddedResource (e.g., `begin-transaction.md` instead of `tool-result`)
- Added `getResultFileName()` method to IMcpTool interface

### 1.5.0
- Explicit ResponseType per tool (TEXT, JSON, MARKDOWN)
- Markdown returned as EmbeddedResource with mimeType
- JSON returned with structuredContent support

### 1.4.0
- Converted list tools to Markdown output
- Fixed unused code warnings

### 1.3.0
- MCP Protocol 2025-11-25 with Streamable HTTP
- SSE transport support
- Session management with MCP-Session-Id header

### 1.2.0
- EDT IMarkerManager integration
- EDT severity levels (BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)

### 1.0.0
- Initial release

## License

Copyright (c) 2025 DitriX. All rights reserved.

---
*EDT MCP Server v1.16.0*
