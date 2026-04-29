[![GitHub all releases](https://img.shields.io/github/downloads/DitriXNew/EDT-MCP/total)](https://github.com/DitriXNew/EDT-MCP/releases)
![EDT](https://img.shields.io/badge/EDT-2026.1+-blue?style=plastic)
# EDT MCP Server

MCP (Model Context Protocol) server plugin for 1C:EDT, enabling AI assistants (Claude, GitHub Copilot, Cursor, etc.) to interact with EDT workspace.

> [!IMPORTANT]
> **EDT version compatibility:**
> EDT 2025.x is supported up to and including plugin version **1.26.1**.
> Starting from plugin version **1.27.0**, only **EDT 2026.1+** is supported.

## Features

- 🔧 **MCP Protocol 2025-11-25** - Streamable HTTP transport with SSE support
- 📊 **Project Information** - List workspace projects and configuration properties
- 🔴 **Error Reporting** - Get errors, warnings, problem summaries with filters
- 📝 **Check Descriptions** - Get check documentation from markdown files
- 🔄 **Project Revalidation** - Trigger revalidation when validation gets stuck
- 🔖 **Bookmarks & Tasks** - Access bookmarks and TODO/FIXME markers
- 💡 **Content Assist** - Get type info, method hints and platform documentation at any code position
- 🧪 **Query Validation** - Validate 1C query text in project context (syntax + semantic errors, optional DCS mode)
- 🧩 **BSL Code Analysis** - Browse modules, inspect structure, read/write methods, search code, and analyze call hierarchy
- 🖼️ **Form Screenshot Capture** - Get PNG screenshots from the form WYSIWYG editor for visual inspection
- 🚀 **Application Management** - Get applications, update database, launch in debug mode
- 🎯 **Status Bar** - Real-time server status with tool name, execution time, and interactive controls
- ⚡ **Interruptible Operations** - Cancel long-running operations and send signals to AI agent
- 🏷️ **Metadata Tags** - Organize objects with custom tags, filter Navigator, keyboard shortcuts (Ctrl+Alt+1-0), multiselect support
- 📁 **Metadata Groups** - Create custom folder hierarchy in Navigator tree per metadata collection
- ✏️ **Metadata Refactoring** - Rename/delete metadata objects with full cascading updates across BSL code, forms and metadata; add new attributes to existing objects
- 🛠️ **Tool Management** - Enable/disable tools by group, presets (Analysis Only, Code Review, Development), per-tool parameter defaults

## Installation

**Only EDT 2026.1+** (for plugin v1.27.0+; use plugin v1.26.1 for EDT 2025.x)

### From Update Site

1. In EDT: **Help → Install New Software...**
2. Add update site URL: `https://ditrixnew.github.io/EDT-MCP/`
3. Select **EDT MCP Server Feature**
4. Restart EDT

### From Windows command line</strong> - "one shot" very fast install

Close your EDT (!) and run:

```bash
rem Here  "%VER_EDT% = 2025.2.3+30"  just for example - please, set YOUR actual version !
set VER_EDT=2025.2.3+30

"\your\path\to\EDT\components\1c-edt-%VER_EDT%-x86_64\1cedt.exe" -nosplash ^
    -application org.eclipse.equinox.p2.director ^
    -repository https://ditrixnew.github.io/EDT-MCP/ ^
	-installIU com.ditrix.edt.mcp.server.feature.feature.group ^
	-profileProperties org.eclipse.update.reconcile=true
```

### Installation Result

<details>
Once the installation has been completed successfully, we will see the following:

![MCP Server After Install](img/AfterInstall.png)
</details>

After that, EDT will automatically monitor the update site and install available updates when detected.

As well, we can also manually check via **Help → About → Installation Details → Select MCP → Update**

### Configuration

Go to **Window → Preferences → MCP Server**. The settings page has two tabs:

#### General Tab

- **Server Port**: HTTP port (default: 8765)
- **Check descriptions folder**: Path to check description markdown files
- **Auto-start**: Start server on EDT launch
- **Plain text mode (Cursor compatibility)**: Returns results as plain text instead of embedded resources (for AI clients that don't support MCP resources)
- **Show tags in Navigator**: Display tags as decorations in the Navigator tree
- **Tag decoration style**: How tags are displayed — all tags as suffix, first tag only, or tag count
- **Server control**: Start, stop, and restart the MCP server directly from preferences

#### Tools Tab

Manage which tools are available to AI assistants. Tools are organized into groups that can be enabled or disabled together. See [Tool Management](#tool-management) for details.

![MCP Server Settings](img/Settings.png)

## Status Bar Controls

The MCP server status bar shows real-time execution status with interactive controls.

**Status Indicator:**
- 🟢 **Green** - Server running, idle
- 🟡 **Yellow blinking** - Tool is executing
- ⚪ **Grey** - Server stopped

![Status Bar Menu](img/StatusButtons.png)

<details>
<summary><strong>User Signal Controls</strong> - Send signals to AI agent during tool execution</summary>

**During Tool Execution:**
- Shows tool name (e.g., `MCP: update_database`)
- Shows elapsed time in MM:SS format
- Click to access control menu

When a tool is executing, you can send signals to the AI agent to interrupt the MCP call:

| Button | Description | When to Use |
|--------|-------------|-------------|
| **Cancel Operation** | Stops the MCP call and notifies agent | When you want to cancel a long-running operation |
| **Retry** | Tells agent to retry the operation | When an EDT error occurred and you want to try again |
| **Continue in Background** | Notifies agent the operation is long-running | When you want agent to check status periodically |
| **Ask Expert** | Stops and asks agent to consult with you | When you need to provide guidance |
| **Send Custom Message...** | Send a custom message to agent | For any custom instruction |

**How it works:**
1. When you click a button, a dialog appears showing the message that will be sent to the agent
2. You can edit the message before sending
3. The MCP call is immediately interrupted and returns control to the agent
4. The EDT operation continues running in the background
5. Agent receives a response like:
```
USER SIGNAL: Your message here

Signal Type: CANCEL
Tool: update_database
Elapsed: 20s

Note: The EDT operation may still be running in background.
```

**Use cases:**
- Long-running operations (full database update, project validation) blocking the agent
- Need to give the agent additional instructions
- EDT showed an error dialog and you want agent to retry
- Want to switch agent's focus to a different task

</details>

## Tool Management

Control which MCP tools are exposed to AI assistants. This lets you reduce context window usage and restrict AI to read-only operations when needed.

### Tool Groups

All 55 tools are organized into 9 semantic groups:

| Group | Description | Tools |
|-------|-------------|-------|
| **Core / Project** | EDT version, project listing, configuration, validation, XML export/import | `get_edt_version`, `list_projects`, `get_configuration_properties`, `clean_project`, `revalidate_objects`, `get_check_description`, `export_configuration_to_xml`, `import_configuration_from_xml` |
| **Errors & Problems** | Error reporting, bookmarks, tasks | `get_problem_summary`, `get_project_errors`, `get_bookmarks`, `get_tasks` |
| **Code Intelligence** | Content assist, documentation, metadata browsing | `get_content_assist`, `get_platform_documentation`, `get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `get_subsystem_content`, `find_references` |
| **Tags** | Tag management | `get_tags`, `get_objects_by_tags` |
| **Applications & Testing** | App management, database updates, testing | `get_applications`, `list_configurations`, `update_database`, `debug_launch`, `run_yaxunit_tests` |
| **Debugging** | Breakpoints, stepping, variable inspection | `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `wait_for_break`, `get_variables`, `step`, `resume`, `evaluate_expression`, `debug_yaxunit_tests`, `debug_status`, `start_profiling`, `get_profiling_results` |
| **BSL Code** | Module browsing, code reading/writing, search | `read_module_source`, `write_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `read_method_source`, `get_method_call_hierarchy`, `go_to_definition`, `get_symbol_info`, `get_form_screenshot`, `validate_query` |
| **Refactoring** | Metadata rename, delete, add attributes | `rename_metadata_object`, `delete_metadata_object`, `add_metadata_attribute` |
| **Translation (LanguageTool)** | Translation strings generation, configuration synchronization, project info | `generate_translation_strings`, `translate_configuration`, `get_translation_project_info` |

Enable or disable entire groups or individual tools from the **Tools** tab in **Window → Preferences → MCP Server**. Disabled tools are filtered out of `tools/list` responses. If a client calls a disabled tool directly through `tools/call`, the server returns a message explaining that the tool is disabled.

### Presets

Quickly switch between common tool configurations using presets:

| Preset | Description |
|--------|-------------|
| **All Tools** | All 55 tools enabled (default) |
| **Analysis Only** | Read-only analysis — Core, Errors, Code Intelligence, Tags |
| **Code Review** | Analysis + BSL code reading (excludes `write_module_source`) |
| **Development** | Full development without debugging tools |

Select a preset from the dropdown in the Tools tab. The preset auto-detects based on the current enabled/disabled state and shows "Custom" when the configuration doesn't match any built-in preset.

### Per-Tool Parameter Defaults

Some tools have configurable default values for parameters like result limits. These defaults are used when the AI client doesn't specify the parameter explicitly:

| Tool | Parameter | Default | Range |
|------|-----------|---------|-------|
| `get_project_errors` | Result limit | 100 | 1–1000 |
| `get_bookmarks` | Result limit | 100 | 1–1000 |
| `get_tasks` | Result limit | 100 | 1–1000 |
| `get_metadata_objects` | Result limit | 100 | 1–1000 |
| `get_content_assist` | Result limit | 100 | 1–1000 |
| `search_in_code` | Max results | 100 | 1–500 |
| `search_in_code` | Context lines | 2 | 0–5 |

Configure these in the Tools tab by selecting a tool that has configurable parameters — the parameter editors appear in the details panel below the tool tree.

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

<details>
<summary><strong>Other AI Assistants</strong> - Cursor, Claude Code, Claude Desktop</summary>

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

### Claude Code

> **Note:** By editing the file `.claude.json` can be added to the MCP either to a specific project or to any project (at the root). If there is no mcpServers section, add it.

Add to `.claude.json` (in Windows `%USERPROFILE%\.claude.json`):
```json
"mcpServers": {
  "EDT MCP Server": {
    "type": "http",
    "url": "http://localhost:8765/mcp"
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

### Cline - extension for VSCode.

```json
{
  "mcpServers": {
    "EDTMCPServer": {
      "type": "streamableHttp",
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

### Antigravity

```json
{
    "mcpServers": {
        "EDTMCPServer": {
            "serverUrl": "http://localhost:8765/mcp"
        }
    }
}
```

</details>

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
| `list_subsystems` | List 1C subsystems (flat table with FQN, synonym, content/children counts; recursive by default) |
| `get_subsystem_content` | Get content of a specific 1C subsystem by FQN: properties, included metadata objects, nested subsystems |
| `find_references` | Find all references to a metadata object (in metadata, BSL code, forms, roles, etc.) — top-level objects only |
| `rename_metadata_object` | Rename a metadata object or attribute with full refactoring: cascading updates in BSL code, forms, and metadata. Preview + confirm workflow |
| `delete_metadata_object` | Delete a metadata object or attribute with reference cleanup. Preview + confirm workflow |
| `add_metadata_attribute` | Add a new attribute to a metadata object (Catalog, Document, Register, etc.) |
| `get_tags` | Get list of all tags defined in the project with descriptions and object counts |
| `get_objects_by_tags` | Get metadata objects filtered by tags with tag descriptions and object FQNs |
| `get_applications` | Get list of applications (infobases) for a project with update state |
| `list_configurations` | List EDT launch configurations (runtime-client + Attach) with current running / suspended state |
| `update_database` | Update database (infobase) with full or incremental update mode — by `launchConfigurationName` or `projectName + applicationId` |
| `debug_launch` | Launch application in debug mode — by `launchConfigurationName` (any type, incl. Attach to 1C:Enterprise Debug Server) or `projectName + applicationId` |
| `run_yaxunit_tests` | Run YAXUnit tests for a project: launches 1C with `RunUnitTests`, parses JUnit XML, returns Markdown report |
| `debug_yaxunit_tests` | Launch YAXUnit tests in DEBUG mode so breakpoints fire (autonomous LLM debug cycle) |
| `set_breakpoint` | Set a 1C BSL line breakpoint (accepts EDT module path or absolute path) |
| `remove_breakpoint` | Remove a breakpoint by id or by project+module+line |
| `list_breakpoints` | List active line breakpoints, optionally filtered by project |
| `wait_for_break` | Block until a debug suspend (e.g. breakpoint hit) on the given application |
| `get_variables` | Read variables from a stack frame of a suspended thread (lazy expand for nested) |
| `step` | Step over / into / out of a suspended thread, returns the new snapshot |
| `resume` | Resume a suspended thread (or all threads of a debug target) |
| `evaluate_expression` | Evaluate a BSL expression in the context of a suspended frame |
| `debug_status` | Report active debug launches: mode, suspend state, thread count, top frame |
| `start_profiling` | Toggle performance measurement (замер производительности) on the active debug target |
| `get_profiling_results` | Get profiling results: per-module, per-line call counts, timing and coverage |
| `get_form_screenshot` | Capture PNG screenshot of form WYSIWYG editor (embedded image resource) |
| `list_modules` | List all BSL modules in a project with module type and parent object |
| `get_module_structure` | Get BSL module structure: procedures/functions, signatures, regions, parameters |
| `read_module_source` | Read BSL module source code with YAML frontmatter metadata (full file or line range) |
| `write_module_source` | Write BSL source code to metadata object modules (searchReplace, replace, append) with syntax check |
| `read_method_source` | Read a specific procedure/function from a BSL module by name |
| `search_in_code` | Full-text/regex search across BSL modules with outputMode: full/count/files |
| `get_method_call_hierarchy` | Find method callers or callees via semantic BSL analysis |
| `go_to_definition` | Navigate to symbol definition (method by name, metadata object by FQN) |
| `get_symbol_info` | Get type/hover info about a symbol at a BSL code position (inferred types, signatures, docs) |
| `validate_query` | Validate 1C query text in project context (syntax + semantic errors, optional DCS mode) |
| `export_configuration_to_xml` | Export an EDT configuration project to a directory of XML files (EDT menu: Export → Configuration to XML Files) |
| `import_configuration_from_xml` | Import a configuration from a directory of XML files into a new EDT project (reverse of export) |
| `generate_translation_strings` | LanguageTool: generate translation strings (.lstr/.trans/.dict) for a configuration project, with translation storage and collection options. EDT menu: Translation → Generate translation strings |
| `translate_configuration` | LanguageTool: propagate dictionary changes from the configured dictionary storage projects (or from in-configuration storages) into translated artifacts. EDT menu: Translation → Translate configuration |
| `get_translation_project_info` | LanguageTool diagnostics: project translation storages and available translation provider IDs |

<details>
<summary><strong>Tool Details</strong> - Parameters and usage examples for each tool</summary>

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

### Validation Tools

- **`clean_project`**: Refreshes project from disk, clears all validation markers, and triggers full revalidation using EDT's ICheckScheduler
- **`revalidate_objects`**: Revalidates specific metadata objects by their FQN:
  - `Document.MyDocument`, `Catalog.MyCatalog`, `CommonModule.MyModule`
  - `Document.MyDoc.Form.MyForm` for nested objects
- **`validate_query`**: Validates query language text in project context and returns syntax/semantic errors.
  - Parameters: `projectName` (required), `queryText` (required), `dcsMode` (optional, default `false`)
  - Use `dcsMode=true` for Data Composition System (DCS) queries

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

### Metadata Details Tool

**`get_metadata_details`** - Get detailed properties of metadata objects.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqns` | Yes | Array of FQNs (e.g. `["Catalog.Products", "Document.SalesOrder"]`) |
| `full` | No | Return all properties (`true`) or only key info (`false`). Default: `false` |
| `language` | No | Language code for synonyms. Uses configuration default if not specified |

### Subsystem Tools

#### List Subsystems Tool

**`list_subsystems`** - List 1C subsystems of a configuration as a flat table with FQN, synonym, command interface flag, and counts of objects/children. Recursively walks the subsystem tree by default; nested FQN format is `Subsystem.Parent.Subsystem.Child`.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `nameFilter` | No | Partial name match filter (case-insensitive, matches `Name` only) |
| `recursive` | No | Include nested subsystems (default: `true`) |
| `limit` | No | Maximum number of results (default: from preferences) |
| `language` | No | Language code for synonyms. Uses configuration default if not specified |

**Returns markdown table:**

```markdown
## Subsystems: MyProject

**Total:** 4 subsystems

| FQN | Synonym | Comment | InCommandInterface | Content | Children |
|-----|---------|---------|--------------------|---------|----------|
| Subsystem.Sales | Продажи |  | Yes | 23 | 2 |
| Subsystem.Sales.Subsystem.Orders | Заказы |  | Yes | 5 | 0 |
| Subsystem.Sales.Subsystem.Pricing | Ценообразование |  | No | 8 | 0 |
| Subsystem.Administration | Администрирование |  | Yes | 14 | 0 |
```

#### Get Subsystem Content Tool

**`get_subsystem_content`** - Get detailed content of a specific 1C subsystem: properties, the list of metadata objects included in the subsystem, and nested child subsystems. Subsystem is identified by FQN.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `subsystemFqn` | Yes | Subsystem FQN, e.g. `Subsystem.Sales` or `Subsystem.Sales.Subsystem.Orders` |
| `recursive` | No | Include objects from nested subsystems in `Content` (deduplicated). Default: `false` |
| `language` | No | Language code for synonyms. Uses configuration default if not specified |

**Returns markdown:**

```markdown
# Subsystem: Sales (Продажи)

## Properties

| Property | Value |
|----------|-------|
| FQN | Subsystem.Sales |
| Name | Sales |
| Synonym | Продажи |
| Include In Command Interface | Yes |
| Include Help In Contents | Yes |
| Use One Command | No |

## Content — 23 objects

| Type | Name | Synonym | FQN |
|------|------|---------|-----|
| Catalog | Products | Номенклатура | Catalog.Products |
| CommonModule | SalesAPI | API продаж | CommonModule.SalesAPI |
| Document | SalesOrder | Заказ покупателя | Document.SalesOrder |

## Child Subsystems — 2

| FQN | Synonym | Content | Children |
|-----|---------|---------|----------|
| Subsystem.Sales.Subsystem.Orders | Заказы | 5 | 0 |
| Subsystem.Sales.Subsystem.Pricing | Ценообразование | 8 | 0 |
```

### Find References Tool

**`find_references`** - Find all references to a metadata object. Returns all places where the object is used: in other metadata objects, BSL code, forms, roles, subsystems, etc. Matches EDT's built-in "Find References" functionality.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqn` | Yes | Fully qualified name (e.g. `Catalog.Products`, `Document.SalesOrder`, `CommonModule.Common`) |
| `limit` | No | Maximum results per category (default: 100, max: 500) |

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

> **Note:** `find_references` supports top-level metadata objects only (e.g. `Catalog.DataAreas`, `CommonModule.Saas`). Passing a sub-object FQN such as `Catalog.DataAreas.Attribute.DataAreaStatus` returns a descriptive error indicating that sub-objects are not supported. Use `rename_metadata_object` or `delete_metadata_object` to work with attributes and nested objects.

### Metadata Refactoring Tools

#### Rename Metadata Object Tool

**`rename_metadata_object`** - Rename a metadata object or attribute with full refactoring support. All references in BSL code, forms, and metadata are updated automatically.

**Workflow:**
1. Call without `confirm` to preview all change points
2. Review change point indices and optionally skip some with `disableIndices`
3. Call with `confirm=true` to apply

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqn` | Yes | FQN of the object to rename. Top-level: `Catalog.Products`. Nested: `Document.SalesOrder.Attribute.Amount` |
| `newName` | Yes | New name for the object |
| `confirm` | No | `true` to execute the rename. Default `false` = preview only |
| `disableIndices` | No | Comma-separated indices of optional change points to skip (e.g. `'2,3,5'`) |
| `maxResults` | No | Max change points to show in preview (default: 20, `0` = no limit) |

**Supported child types in FQN:** `Attribute`, `TabularSection`, `Dimension`, `Resource`

#### Delete Metadata Object Tool

**`delete_metadata_object`** - Delete a metadata object or attribute. References in BSL code, forms, and other metadata are cleaned up automatically.

**Workflow:**
1. Call without `confirm` to preview affected references and problems
2. Call with `confirm=true` to apply

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `objectFqn` | Yes | FQN of the object to delete (e.g. `Catalog.Products`, `Document.SalesOrder.Attribute.Amount`) |
| `confirm` | No | `true` to execute the deletion. Default `false` = preview only |

#### Add Metadata Attribute Tool

**`add_metadata_attribute`** - Add a new attribute to a metadata object via BM write transaction. The attribute is created with default properties.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `parentFqn` | Yes | FQN of the parent object (e.g. `Catalog.Products`, `Document.SalesOrder`) |
| `attributeName` | Yes | Name for the new attribute |

**Supported parent types:** `Catalog`, `Document`, `ExchangePlan`, `ChartOfCharacteristicTypes`, `ChartOfAccounts`, `ChartOfCalculationTypes`, `BusinessProcess`, `Task`, `DataProcessor`, `Report`, `InformationRegister`, `AccumulationRegister`, `AccountingRegister`

### Tag Management Tools

#### Get Tags Tool

**`get_tags`** - Get list of all tags defined in the project. Tags are user-defined labels for organizing metadata objects.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |

**Returns:** Markdown table with tag name, color, description, and number of assigned objects.

#### Get Objects By Tags Tool

**`get_objects_by_tags`** - Get metadata objects filtered by tags. Returns objects that have any of the specified tags.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `tags` | Yes | Array of tag names to filter by (e.g. `["Important", "NeedsReview"]`) |
| `limit` | No | Maximum objects per tag (default: 100) |

**Returns:** Markdown with sections for each tag including:
- Tag color and description
- Table of object FQNs assigned to the tag
- Summary with total objects found

### Application Management Tools

#### Get Applications Tool

**`get_applications`** - Get list of applications (infobases) for a project. Returns application ID, name, type, and current update state. Use this to get application IDs for `update_database` and `debug_launch` tools.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |

#### List Configurations Tool

**`list_configurations`** - List EDT launch configurations (runtime-client + Attach + other 1C types) with their current running state. Discovery step that precedes `debug_launch`, `run_yaxunit_tests`, `debug_yaxunit_tests` and `update_database`: once the MCP client knows the exact configuration name, it can target it by name without juggling `projectName + applicationId` pairs.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `type` | No | Filter: `attach` (RemoteRuntime + LocalRuntime), `client` (RuntimeClient), `all` (default — any 1C/EDT launch config) |
| `projectName` | No | Project-name filter |

**Returns:** per configuration — `name`, `type` (full type id), `attach` flag, `applicationId` (real or synthetic `attach:<name>`), `project`, `infobaseAlias`, `debugServerUrl`, `running`, `mode`, `suspended`.

#### Update Database Tool

**`update_database`** - Update database (infobase) configuration. Supports full and incremental update modes.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `launchConfigurationName` | No (preferred) | Exact EDT runtime-client launch configuration name (from `list_configurations`). When given, `projectName` and `applicationId` are derived from the config. |
| `projectName` | If no name | EDT project name |
| `applicationId` | If no name | Application ID from `get_applications` |
| `fullUpdate` | No | If true - full reload, if false - incremental update (default: false) |
| `autoRestructure` | No | Automatically apply restructurization if needed (default: true) |

#### Debug Launch Tool

**`debug_launch`** - Start an EDT debug session. Works for both runtime-client configs (spawns `1cv8c`) and **Attach to 1C:Enterprise Debug Server** configs (attaches to a running `ragent`/`rphost`, required for debugging server-side code — HTTP services, server calls, scheduled and background jobs).

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `launchConfigurationName` | No (preferred) | Exact name of an EDT debug launch configuration (runtime client or Attach). Use this for Attach configs or to pick a specific client config by name. |
| `projectName` | If no name | EDT project name |
| `applicationId` | If no name | Application ID from `get_applications` (runtime-client launches only) |
| `updateBeforeLaunch` | No | If true - update database before launching (default: true, ignored for Attach) |

**Notes:**
- Requires a launch configuration to be created in EDT first (Run → Run Configurations...).
- For an Attach config, `debug_launch` returns `applicationId: "attach:<name>"` — a stable synthetic id used by `wait_for_break`, `resume`, `debug_status` and friends.
- If the config is already running in debug mode, the tool short-circuits with `alreadyRunning: true` instead of spawning a duplicate launch.
- If no configuration exists, returns list of available configurations (runtime client + attach) so the MCP client can discover what's on offer.
- `updateBeforeLaunch=true` skips update if database is already up to date.

#### Run YAXUnit Tests Tool

**`run_yaxunit_tests`** - Run YAXUnit tests for a 1C:Enterprise project. Launches the application with the `RunUnitTests` startup parameter, polls until the launch terminates, parses the JUnit XML report and returns a Markdown summary. The full Markdown report is also written to `report.md` next to `junit.xml` so it can be read directly from disk.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `launchConfigurationName` | No (preferred) | Exact EDT runtime-client launch configuration name (from `list_configurations`). When given, `projectName` and `applicationId` are derived from the config. |
| `projectName` | If no name | EDT project name |
| `applicationId` | If no name | Application ID from `get_applications` |
| `extensions` | No | Comma-separated extension names to filter tests by extension |
| `modules` | No | Comma-separated common-module names to run (e.g. `OM_tmrlGlovoCatalog`) |
| `tests` | No | Comma-separated test names in `Module.Method` format |
| `timeout` | No | Polling window in seconds (default: 60). On expiry returns **Pending**; call again to keep waiting |

**Notes:**
- Requires a launch configuration in EDT for the project/application and the YAXUnit extension installed in the infobase.
- The launch is **not** terminated when the polling window expires — call the tool again with identical arguments to keep waiting and fetch the result once 1C closes.
- Reports are stored under `%TEMP%/edt-mcp-yaxunit/<sanitized-key>_<sha1>/` (`junit.xml` + `report.md` + `xUnitParams.json`). The directory name is derived from `projectName:applicationId:filterHash` — sanitized and suffixed with a SHA-1 hash to avoid collisions. A fresh `junit.xml` (younger than 5 minutes) is reused without restarting 1C.

#### Server-Side Debugging (Attach to 1C:Enterprise Debug Server)

The debug tools also drive **Attach** launch configurations (`com._1c.g5.v8.dt.debug.core.RemoteRuntime` / `LocalRuntime`), which is the only way to debug server-side BSL via MCP: HTTP services, server calls, scheduled jobs, background jobs, external connections running inside `rphost`.

**Prerequisites on the 1C side:**
- Cluster `ragent` launched with `-debug -http` (HTTP debugger, typical port `1550`).
- For published infobases: debug flag enabled in the `.vrd` (Apache `wsap24.dll` or IIS).
- In EDT, create a launch configuration of type *Attach to 1C:Enterprise Debug Server* with the infobase alias / UUID and the debug-server URL (e.g. `http://localhost:1550`).

**Workflow:**

1. `list_configurations({type: "attach"})` — discover available Attach configs and see which one is already running.
2. `debug_launch({launchConfigurationName: "<name>"})` — attach to `rphost` (or short-circuit with `alreadyRunning: true` if the session is live). Returns `applicationId: "attach:<name>"`.
3. `set_breakpoint` on the suspect HTTP-service handler / server procedure.
4. Trigger the server-side call (e.g. `curl http://host/base/hs/your-endpoint`).
5. `wait_for_break` — returns `threadId`, `frameRef`, suspended line.
6. `evaluate_expression({frameRef, expression: "Request.QueryOptions[\"..\"]"})` — inspect request parameters, catalog refs, etc. (`get_variables` also works for most frames; use `evaluate_expression` as a fallback when the attach frame doesn't expose variables eagerly.)
7. `step` / `resume` — finish the call; the HTTP request on the client returns.

Attach launches register in the same snapshot / thread / frame registry as runtime-client launches — `debug_status` reports `applicationId`, `launchConfiguration`, `configurationType`, `attach: true`, `suspended`, `suspendedAt` and a `registered` flag.

#### Debug Inspection Tools

A family of MCP tools that lets the LLM set breakpoints, inspect runtime state and walk the BSL stack while a 1C application is running under the EDT debugger. Combined with `debug_yaxunit_tests`, this gives a fully autonomous debugging cycle: the LLM writes a YAXUnit test, sets a breakpoint inside the suspect code, launches the test in DEBUG mode, waits for the breakpoint to fire, inspects variables, evaluates expressions, steps through the code, and resumes — all without a human clicking inside EDT.

**End-to-end LLM debug cycle:**

1. `set_breakpoint` — set a line breakpoint on the suspect module/line.
2. `debug_yaxunit_tests` — launch YAXUnit (filtered to a single test) in DEBUG mode.
3. `wait_for_break` — block until the breakpoint fires; returns a snapshot with `threadId`, frames and stable `frameRef`s.
4. `get_variables` — read variables of the top frame (or any frame); pass `expandPath` to drill into nested Структуры/Массивы.
5. `evaluate_expression` — run an arbitrary BSL expression in the suspended frame to test a hypothesis.
6. `step` — step over / into / out and re-snapshot.
7. `resume` — let the test finish.

**Notes:**
- `set_breakpoint` accepts either an EDT module path (`CommonModules/Foo/Module.bsl`) or an absolute filesystem path; auto-detected.
- `wait_for_break` does **not** terminate the launch on timeout — call it again to keep waiting.
- `frameRef` and `threadId` are reissued on every SUSPEND event. After `resume`/`step` the previous ids become stale (the tool returns a clear error).
- `evaluate_expression` runs arbitrary BSL inside the running 1C process. Use it deliberately.
- The actual 1C BSL breakpoint class is loaded via reflection at runtime — if the EDT version exposes it under a different name, `Activator.logError` will surface the failure and the breakpoint falls back to a marker shim.

### BSL Code Analysis Tools

#### List Modules Tool

**`list_modules`** - List all BSL modules in an EDT project. Can filter by metadata type or specific object name. Returns module path, type, and parent object.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `metadataType` | No | Filter: `all`, `documents`, `catalogs`, `commonModules`, `informationRegisters`, `accumulationRegisters`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `constants`, `commonCommands`, `commonForms`, `webServices`, `httpServices` (default: `all`) |
| `objectName` | No | Name of specific metadata object to list modules for (e.g. `Products`) |
| `nameFilter` | No | Substring filter on module path (case-insensitive) |
| `limit` | No | Maximum results (default: 200, max: 1000) |

#### Get Module Structure Tool

**`get_module_structure`** - Get structure of a BSL module: all procedures/functions with signatures, line numbers, regions, execution context (`&AtServer`, `&AtClient`), export flag, and parameters.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `modulePath` | Yes | Path from `src/` folder (e.g. `CommonModules/MyModule/Module.bsl`) |
| `includeVariables` | No | Include module-level variable declarations (default: `false`) |
| `includeComments` | No | Include doc-comments for methods (default: `false`) |

**Returns:** Markdown with:

- Module summary (procedure/function counts, total lines)
- Regions list with line ranges
- Methods table: type, name, export, context, lines, parameters, region, description (when `includeComments=true`)
- Variables table: name, export flag, line, region (when `includeVariables=true`)

#### Read Module Source Tool

**`read_module_source`** - Read BSL module source code from EDT project. Returns source with YAML frontmatter metadata (`startLine`, `endLine`, `totalLines`). Supports reading full file or a specific line range. The per-call line limit is configurable in **Window → Preferences → MCP Server → Tools** (`maxLines`, default 5000).

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `modulePath` | Yes | Path from `src/` folder (e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ObjectModule.bsl`) |
| `startLine` | No | Start line number (1-based, inclusive). If omitted, reads from beginning |
| `endLine` | No | End line number (1-based, inclusive). If omitted, reads to end |

**Returns:** Markdown with YAML frontmatter followed by a fenced `bsl` code block containing clean source (no line-number prefixes). Frontmatter fields:

- `projectName`, `module` — echo of input parameters
- `startLine`, `endLine` — actual 1-based range returned (omitted for an empty file)
- `totalLines` — total line count of the file
- `truncated: true` — present only when the requested range was clamped by the configured line limit (`maxLines` setting)

#### Write Module Source Tool

**`write_module_source`** - Write BSL source code to 1C metadata object modules. Modes: searchReplace (content-based find and replace, default), replace (replace entire file), append (add to end). Specify modulePath or objectName + moduleType. Automatically checks BSL syntax before writing.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `modulePath` | No* | Path from `src/` folder (e.g. `Documents/MyDoc/ObjectModule.bsl`). Alternative to objectName + moduleType |
| `objectName` | No* | Full object name (e.g. `Document.MyDoc`, `CommonModule.MyModule`). Supports Russian names |
| `moduleType` | No | Module type: `ObjectModule` (default), `ManagerModule`, `FormModule`, `CommandModule`, `RecordSetModule` |
| `source` | Yes | BSL source code to write. For `searchReplace`: new code replacing `oldSource`. For `replace`: complete module content. For `append`: code to add |
| `oldSource` | No** | Existing code to find and replace (required for `searchReplace` mode). Must match exactly one location in the file. Serves as proof that you have read the current file content |
| `mode` | No | Write mode: `searchReplace` (default), `replace`, `append` |
| `formName` | No | Form name, required when `moduleType=FormModule` |
| `commandName` | No | Command name, required when `moduleType=CommandModule` |
| `skipSyntaxCheck` | No | Skip BSL syntax validation (default: `false`). Checks balanced `Procedure/EndProcedure`, `Function/EndFunction`, `If/EndIf`, `While/EndDo`, `For/EndDo`, `Try/EndTry` |

*One of `modulePath` or `objectName` is required.

**Required for `searchReplace` mode.

**Notes:**

- **Content-based editing**: `searchReplace` mode finds `oldSource` in the file and replaces it with `source`. If `oldSource` is not found or matches multiple locations, the operation fails safely. This eliminates line-number drift issues when making multiple edits
- Creates new module file if it does not exist (only in `replace` mode)
- Preserves UTF-8 BOM encoding
- Syntax check validates the complete resulting file, not just the inserted fragment

#### Read Method Source Tool

**`read_method_source`** - Read a specific procedure/function from a BSL module by name. Returns method source code with line numbers and signature. If method not found, returns list of all available methods.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `modulePath` | Yes | Path from `src/` folder (e.g. `CommonModules/MyModule/Module.bsl`) |
| `methodName` | Yes | Name of the procedure/function to read (case-insensitive) |

**Returns:** Method source code with:

- Method type (Procedure/Function), signature, export flag
- Line range and line count
- Source code with line numbers

#### Search in Code Tool

**`search_in_code`** - Full-text search across all BSL modules in a project. Supports plain text and regex patterns, case sensitivity, context lines around matches, and file path filtering.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `query` | Yes | Search string or regex pattern |
| `caseSensitive` | No | Case-sensitive search (default: `false`) |
| `isRegex` | No | Treat query as regular expression (default: `false`) |
| `maxResults` | No | Maximum number of matches to return with context (default: 100, max: 500) |
| `contextLines` | No | Lines of context before/after each match (default: 2, max: 5) |
| `fileMask` | No | Filter by module path substring (e.g. `CommonModules` or `Documents/SalesOrder`) |
| `outputMode` | No | Output mode: `full` (matches with context, default), `count` (only total count, fast), `files` (file list with match counts, no context) |
| `metadataType` | No | Filter by metadata type: `documents`, `catalogs`, `commonModules`, `informationRegisters`, `accumulationRegisters`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `constants`, `commonCommands`, `commonForms`, `webServices`, `httpServices` |

#### Get Method Call Hierarchy Tool

**`get_method_call_hierarchy`** - Find method call hierarchy: who calls this method (callers) or what this method calls (callees). Uses semantic BSL analysis via BM-index, not text search.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `modulePath` | Yes | Path from `src/` folder (e.g. `CommonModules/MyModule/Module.bsl`) |
| `methodName` | Yes | Name of the procedure/function (case-insensitive) |
| `direction` | No | `callers` (who calls this method, default) or `callees` (what this method calls) |
| `limit` | No | Maximum results (default: 100, max: 500) |

**Notes:**

- Requires EMF model (BSL AST) — does not work in text fallback mode
- `callers` uses IReferenceFinder to search across the entire project
- `callees` traverses the method's AST to find all invocations

### Go To Definition Tool

**`go_to_definition`** - Navigate to the definition of a symbol. Resolves method calls like `CommonModuleName.MethodName` to the actual definition with source code, signature, and location. Also resolves metadata object FQNs like `Catalog.Products`. Supports both English and Russian metadata type names.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `symbol` | Yes | Symbol to find definition for. Formats: `ModuleName.MethodName` (method in a common module), `MethodName` (method in context module, requires `modulePath`), `Catalog.Products` (metadata object FQN). Russian metadata type names are also supported |
| `modulePath` | No | Context module path from `src/` folder (e.g. `Documents/SalesOrder/ObjectModule.bsl`). Required when symbol is an unqualified method name |
| `includeSource` | No | Include method source code in the response (default: `true`) |

**Returns:** Markdown with:

- Method signature, export flag, line range
- Source code with line numbers (when `includeSource=true`)
- File path for navigation
- For metadata objects: FQN, synonym, available modules

### Get Symbol Info Tool

**`get_symbol_info`** - Get type and hover information about a symbol at a specific position in a BSL module. Returns inferred types, signatures, and documentation — the same info that EDT shows on mouse hover. Useful for understanding variable types in dynamically-typed BSL code.

**Parameters:**
| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name |
| `filePath` | Yes | Path to BSL file relative to project's `src/` folder (e.g. `CommonModules/MyModule/Module.bsl`) |
| `line` | Yes | Line number (1-based) |
| `column` | Yes | Column number (1-based) |

**Returns:** Markdown with symbol information. Uses a multi-level approach:

1. **Editor hover** (best): Returns inferred types, method signatures, documentation — same as IDE hover tooltip
2. **EObject analysis** (fallback): Returns structural info — symbol kind, name, signature, export flag, line range
3. **EMF model** (last resort): Basic node info without opening editor

**Use cases:**

- Determine the inferred type of a variable (BSL is dynamically typed)
- Get method signature and documentation at a call site
- Inspect property types on objects accessed via dot notation
- Understand platform method parameter types

### Configuration XML Export / Import

These tools sit in the Core / Project group and wrap the official 1C EDT workspace CLI APIs (`com._1c.g5.v8.dt.cli.api.workspace.*`) via reflection — keeping zero compile-time dependency on those APIs while still surfacing them to AI assistants.

**`export_configuration_to_xml`** — Export an EDT configuration project to a directory of XML source files. Equivalent of EDT menu *Export → Configuration to XML Files* and the 1C platform `DumpConfigToFiles` command. Wraps `IExportConfigurationFilesApi.exportProject(String projectName, Path outputPath)`.

| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | EDT project name to export |
| `outputPath` | Yes | Filesystem path of the output directory. Resolved to an absolute path. Created automatically if it does not exist; an existing file (not a directory) at that path is rejected with a clear error |

**`import_configuration_from_xml`** — Import a configuration from a directory of XML files into a new EDT project in the workspace. Reverse of `export_configuration_to_xml`. Wraps `IImportConfigurationFilesApi.importProject(Path importSource, String projectName, String nature, String xmlVersion)`. After the API call the tool also closes/opens/refreshes the new project to trigger EDT's project lifecycle (the underlying CLI API hardcodes `setRefreshProject(false)` and would otherwise leave the project unindexed), so the imported project is ready to use without manual GUI intervention.

| Parameter | Required | Description |
|-----------|----------|-------------|
| `importPath` | Yes | Filesystem path of the source directory containing XML files. Resolved to an absolute path. Must exist and be a directory; otherwise rejected with a clear error before the API call |
| `projectName` | Yes | Name of the new EDT project to create in the workspace |
| `projectNature` | No | EDT project nature ID (e.g. `com._1c.g5.v8.dt.core.V8ConfigurationNature`); empty/omitted = let EDT auto-detect |
| `xmlVersion` | No | XML format version (e.g. `8.3.20`); empty/omitted = let EDT auto-detect |

### LanguageTool Tools

LanguageTool is installed separately via *Help → Install New Software* on both EDT 2025.x and 2026.1; it is not bundled with the EDT base distribution. These tools wrap the official 1C CLI APIs (`com.e1c.langtool.v8.dt.cli.api.*`) via reflection, so this plugin builds without a compile-time dependency on LanguageTool. When LanguageTool is not installed, every tool returns a clear "API not available" error instead of failing.

#### Concepts

The three tools touch four kinds of objects, and a clear mental model helps choose the right one:

- **Configuration project** — the regular EDT project of your application. Has the `V8ConfigurationNature` and contains all metadata (`Catalogs`, `Documents`, etc.) plus declared `Languages`. `generate_translation_strings` MUST run on this project.
- **Dictionary storage project** — *plain* Eclipse project (NOT a 1C-EDT project) with the `dependentProjectNature`, used by LangTool as an external location to keep `.lstr` / `.trans` / `.dict` files. It is created as a regular empty Eclipse project and is **not** intrinsically linked to any configuration. The link is established from the configuration side: in the configuration project's settings the user points to this Eclipse project as an external dictionary storage. The configuration project itself can also serve as its own storage — in that case the dictionaries live inside it.
- **Storage** — a logical destination inside the LangTool configuration that decides where each generated key is written. The configuration project declares several storages in `.settings/translation_storages.yml` (`edit:default`, `dictionary:common-camelcase`, `context:model`, etc.); each storage is bound either to the configuration itself or to one of the dictionary storage projects from the previous bullet. Use `get_translation_project_info` to enumerate the storages declared on a given project.
- **Translation provider** — an integration that can pre-fill values for newly generated keys (Google, Microsoft, Yandex, history-based, etc.). Used only when `fillUpType=FROM_PROVIDER`. Use `get_translation_project_info` to enumerate available IDs.

#### Typical workflow

1. **Discover** — call `get_translation_project_info` once on the configuration project to learn which storages and providers are available. Cache the result; it does not change between dictionary edits.
2. **Generate** — call `generate_translation_strings` against the configuration project, passing the target languages. This populates placeholder keys in the storages declared on the project. The action is idempotent: re-running it adds only new keys that did not exist yet.
3. **Translate** — fill in the placeholder values. This step happens outside the MCP tools: edit the `.lstr` / `.trans` / `.dict` files (in whichever dictionary storage project — or in the configuration itself — the storages route them to), either by hand or by feeding them to an LLM. The plugin treats this step as a black box.
4. **Synchronize** — call `translate_configuration` on the source configuration project. This reads the dictionaries from the storages bound to the configuration and regenerates the translated artifacts. This is the action a translator runs after every batch of dictionary edits.
5. **Iterate** — typical real-world flow alternates between steps 2–4: source code changes add new translatable strings → `generate_translation_strings` extends the dictionaries → translator fills the new keys → `translate_configuration` propagates them.

A practical example of this loop is automating the translation of an actively-developing upstream library (Russian → English/de/ro/etc.) on every release: a CI script calls these tools after a `git pull` to extend the dictionaries with newly added strings, runs the translator over the new keys, then synchronizes — producing fresh translated XML sources without manual clicks in the EDT UI.

#### Notes and gotchas

- `generate_translation_strings` rejects non-configuration projects (dictionary storage projects, extensions, plain Eclipse projects) with an explicit error before contacting LanguageTool. The check uses `IProject.hasNature(V8ConfigurationNature)`.
- A dictionary storage project does not need any special setup beyond being created as an empty Eclipse project and being registered in the configuration's translation settings — there is no MCP tool for that registration step (it is a one-time GUI action in the configuration project's properties).
- `providerId` is meaningful **only** when `fillUpType=FROM_PROVIDER`. Passing it with any other `fillUpType` is silently ignored (the suffix is appended only for FROM_PROVIDER), and forgetting it when the mode is FROM_PROVIDER returns a fail-fast error before the underlying API is called.
- `translate_configuration` does NOT touch user dictionaries — it only re-derives the translated artifacts from them. Edits to `.lstr` / `.trans` / `.dict` are the translator's responsibility.
- All three tools surface the underlying LangTool exceptions verbatim under `error` when something goes wrong inside LanguageTool itself, so an AI agent can retry with adjusted parameters or escalate to the user.

**`generate_translation_strings`** — wraps `IGenerateTranslationStringsApi.generateTranslationStrings(...)`. Equivalent of EDT menu *Translation → Generate translation strings*. Invoked on a **configuration project** (`V8ConfigurationNature`); a dictionary storage project (the plain Eclipse project where dictionaries live) is the wrong target. Produces placeholder keys in `.lstr` / `.trans` / `.dict` files routed by the configuration's translation storages. The translator (or LLM) then fills in values.

| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | Configuration project name (`V8ConfigurationNature`) |
| `targetLanguages` | Yes | Target language codes to generate strings for, e.g. `["en"]` |
| `storageId` | No | Storage ID to write generated keys into. Default: `edit:default`. Use `get_translation_project_info` to list available storages |
| `collectInterface` | No | Generate interface (`.lstr`) keys. Default: `true` |
| `collectModel` | No | Generate model (`.trans`) keys. Default: `true` |
| `collectModelType` | No | Model collection mode: `ANY` \| `NONE` \| `COMPUTED_ONLY` \| `UNKNOWN_ONLY` \| `TAGS_ONLY`. Default: `ANY` |
| `fillUpType` | No | Pre-fill new keys with values from: `NOT_FILLUP` \| `FROM_SOURCE_LANGUAGE` \| `FROM_PROVIDER`. Default: `NOT_FILLUP` |
| `providerId` | No | Translation provider ID (used only when `fillUpType=FROM_PROVIDER`). Use `get_translation_project_info` to list available providers |

**Returns:** Markdown with YAML frontmatter (`tool`, `project`, `targetLanguages`, `storageId`, `collectInterface`, `collectModel`, `collectModelType`, `fillUpType`, `status`) followed by a brief textual confirmation.

**`translate_configuration`** — wraps `ISynchronizeProjectApi.synchronizeProject(IDtProject, List<String>)`. Equivalent of EDT menu *Translation → Translate configuration*. Reads the dictionaries from the storages bound to the configuration (these may live in external dictionary storage projects or inside the configuration itself) and regenerates the translated artifacts. This is the main action a translator runs after editing dictionaries.

| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | Project name (typically the source project) |
| `targetLanguages` | Yes | Target language codes to synchronize, e.g. `["en"]` |

**Returns:** Markdown with YAML frontmatter (`tool`, `project`, `targetLanguages`, `status`) followed by a brief textual confirmation.

**`get_translation_project_info`** — wraps `IProjectInformationApi`. Diagnostic tool that returns the translation storage IDs declared on a project (e.g. `edit:default`, `dictionary:common-camelcase`, `dictionary:common`, `context:model`, `context:interface`) and the available translation provider IDs (Google, Microsoft, Yandex, history, etc.).

| Parameter | Required | Description |
|-----------|----------|-------------|
| `projectName` | Yes | Project name |

**Returns:** Markdown with YAML frontmatter (`tool`, `project`, `storagesCount`, `providersCount`) followed by `## Storages` and `## Translation providers` sections, each rendered as a bulleted list.

### Output Formats

- **Markdown tools**: `list_projects`, `get_project_errors`, `get_bookmarks`, `get_tasks`, `get_problem_summary`, `get_check_description`, all LanguageTool tools - return Markdown as EmbeddedResource with `mimeType: text/markdown`
- **JSON tools**: `get_configuration_properties`, `clean_project`, `revalidate_objects`, `export_configuration_to_xml`, `import_configuration_from_xml` - return JSON with `structuredContent`
- **Text tools**: `get_edt_version` - return plain text

</details>

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | POST | MCP JSON-RPC (initialize, tools/list, tools/call) |
| `/mcp` | GET | Server info |
| `/health` | GET | Health check |

## Metadata Tags

Organize your metadata objects with custom tags for easier navigation and filtering.

### Why Use Tags?

Tags help you:
- Group related objects across different metadata types (e.g., all objects for a specific feature)
- Quickly find objects in large configurations
- Filter the Navigator to focus on specific areas of the project
- Share object organization with your team via version control

### Getting Started

**Assigning Tags to Objects:**

1. Right-click on any metadata object in the Navigator
2. Select **Tags** from the context menu
3. Check the tags you want to assign, or select **Manage Tags...** to create new ones

![Tags Context Menu](img/tags-context-menu.png)

**Managing Tags:**

In the Manage Tags dialog you can:
- Create new tags with custom names, colors, and descriptions
- Edit existing tags (name, color, description)
- Delete tags
- See all available tags for the project

![Manage Tags Dialog](img/tags-manage-dialog.png)

### Viewing Tags in Navigator

Tagged objects show their tags as a suffix in the Navigator tree:

![Navigator with Tags](img/tags-navigator.png)

**To enable/disable tag display:**
- **Window → Preferences → General → Appearance → Label Decorations**
- Toggle "Metadata Tags Decorator"

### Filtering Navigator by Tags

Filter the entire Navigator to show only objects with specific tags:

1. Click the tag filter button in the Navigator toolbar (or right-click → **Tags → Filter by Tag...**)
2. Select one or more tags
3. Click **Set** to apply the filter

![Filter by Tag Dialog](img/tags-filter-dialog.png)

The Navigator will show only:
- Objects that have ANY of the selected tags
- Parent folders containing matching objects

**To clear the filter:** Click **Turn Off** in the dialog or use the toolbar button again.

### Keyboard Shortcuts for Tags

Quickly toggle tags on selected objects using keyboard shortcuts:

| Shortcut | Action |
|----------|--------|
| **Ctrl+Alt+1** | Toggle 1st tag |
| **Ctrl+Alt+2** | Toggle 2nd tag |
| **...** | ... |
| **Ctrl+Alt+9** | Toggle 9th tag |
| **Ctrl+Alt+0** | Toggle 10th tag |

**Features:**
- Works with multiple selected objects
- Supports cross-project selection (each object uses tags from its own project)
- Pressing the same shortcut again removes the tag (toggle behavior)
- Tag order is configurable in the Manage Tags dialog (Move Up/Move Down buttons)

**To customize shortcuts:** Window → Preferences → General → Keys → search for "Toggle Tag"

### Filtering Untagged Objects

Find metadata objects that haven't been tagged yet:

1. Open Filter by Tag dialog (toolbar button or Tags → Filter by Tag...)
2. Check the **"Show untagged objects only"** checkbox
3. Click **Set**

The Navigator will show only objects that have no tags assigned, making it easy to identify objects that need categorization.

### Multi-Select Tag Assignment

Assign or remove tags from multiple objects at once:

1. Select multiple objects in the Navigator (Ctrl+Click or Shift+Click)
2. Right-click → **Tags**
3. Select a tag to toggle it on/off for ALL selected objects

**Behavior:**
- ✓ Checked = all selected objects have this tag
- ☐ Unchecked = none of the selected objects have this tag
- When objects are from different projects, only objects from projects that have the tag will be affected

### Tag Filter View

For advanced filtering across multiple projects, use the Tag Filter View:

**Window → Show View → Other → MCP Server → Tag Filter**

This view provides:
- **Left panel**: Select tags from all projects in your workspace
- **Right panel**: See all matching objects with search and navigation
- **Search**: Filter results by object name using regex
- **Double-click**: Navigate directly to the object

### Where Tags Are Stored

Tags are stored in `.settings/metadata-tags.yaml` file in each project. This file:
- Can be committed to version control (VCS friendly)
- Is automatically updated when you rename or delete objects
- Uses YAML format for easy readability

**Example:**
```yaml
assignments:
  CommonModule.Utils:
    - Utils
  Document.SalesOrder:
    - Important
    - Sales
tags:
  - color: '#FF0000'
    description: Critical business logic
    name: Important
  - color: '#00FF00'
    description: ''
    name: Utils
  - color: '#0066FF'
    description: Sales department documents
    name: Sales
```

## Metadata Groups

Organize your Navigator tree with custom groups to create a logical folder structure for metadata objects.

### Why Use Groups?

Groups help you:
- Create custom folder hierarchy in the Navigator tree
- Organize objects by business area, feature, or any logical structure
- Navigate large configurations faster with nested groups
- Separate grouped objects from ungrouped ones

### Getting Started

**Creating a Group:**

1. Right-click on any metadata folder (e.g., Catalogs, Common modules) in the Navigator
2. Select **New Group...** from the context menu
3. Enter the group name and optional description
4. Click **OK** to create the group

![New Group Context Menu](img/groups-context-menu.png)

**Create Group Dialog:**

![New Group Dialog](img/groups-new-dialog.png)

**Adding Objects to a Group:**

1. Right-click on any metadata object in the Navigator
2. Select **Add to Group...**
3. Choose the target group from the list

![Add to Group Menu](img/groups-add-remove-menu.png)

**Removing Objects from a Group:**

1. Right-click on an object inside a group
2. Select **Remove from Group**

### Viewing Groups in Navigator

Grouped objects appear inside their group folders in the Navigator tree:

![Navigator with Groups - Common Modules](img/groups-navigator-common-modules.png)

![Navigator with Groups - Catalogs](img/groups-navigator-catalogs.png)

**Key Features:**
- Groups are created per metadata collection (Catalogs, Common modules, Documents, etc.)
- Objects inside groups are still accessible via standard EDT navigation
- Ungrouped objects appear at the end of the list

### Group Operations

| Action | How to Do It |
|--------|--------------|
| Create group | Right-click folder → **New Group...** |
| Add object to group | Right-click object → **Add to Group...** |
| Remove from group | Right-click object in group → **Remove from Group** |
| Copy group name | Select group → **Ctrl+C** |
| Delete group | Right-click group → **Delete** |
| Rename group | Right-click group → **Rename...** |

### Where Groups Are Stored

Groups are stored in `.settings/groups.yaml` file in each project. This file:
- Can be committed to version control (VCS friendly)
- Uses YAML format for easy readability
- Is automatically updated when you rename or delete objects

**Example:**
```yaml
groups:
- name: "Products & Inventory"
  description: "Product and inventory catalogs"
  path: Catalog
  order: 0
  children:
    - Catalog.ItemKeys
    - Catalog.Items
    - Catalog.ItemSegments
    - Catalog.Units
    - Catalog.UnitsOfMeasurement
- name: "Organization"
  description: "Organization structure catalogs"
  path: Catalog
  order: 1
  children:
    - Catalog.Companies
    - Catalog.Stores
- name: "Core Functions"
  description: "Core shared functions used across the application"
  path: CommonModule
  order: 0
  children:
    - CommonModule.CommonFunctionsClient
    - CommonModule.CommonFunctionsServer
    - CommonModule.CommonFunctionsClientServer
- name: "Localization"
  description: "Multi-language support modules"
  path: CommonModule
  order: 1
  children:
    - CommonModule.Localization
    - CommonModule.LocalizationClient
    - CommonModule.LocalizationServer
    - CommonModule.LocalizationReuse
```

## Requirements

- 1C:EDT 2025.2 (Ruby) or later
- Java 17+

## License
# Copyright (C) 2026 DitriX
# Licensed under GNU AGPL v3.0
