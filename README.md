[![GitHub all releases](https://img.shields.io/github/downloads/DitriXNew/EDT-MCP/total)](https://github.com/DitriXNew/EDT-MCP/releases)

[![Build & Unit Tests](https://github.com/DitriXNew/EDT-MCP/actions/workflows/build.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/build.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=bugs)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=DitriXNew_EDT-MCP&metric=coverage)](https://sonarcloud.io/summary/new_code?id=DitriXNew_EDT-MCP)

[![E2E 2025.2](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2025.2.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2025.2.yml)
[![E2E 2026.1](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2026.1.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/e2e-2026.1.yml)

[![Conformance 2025.2](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2025.2.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2025.2.yml)
[![Conformance 2026.1](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2026.1.yml/badge.svg)](https://github.com/DitriXNew/EDT-MCP/actions/workflows/conformance-2026.1.yml)

> **Build & Unit Tests**, **E2E**, and **MCP Conformance** all run on stock GitHub-hosted runners (cloud CI) — no docker image, no self-hosted runner. E2E and Conformance run **per EDT version** (2025.2 and 2026.1, each its own badge): the setup step installs a headless EDT of that version on the runner via `p2 director`. E2E additionally imports the test fixtures into an empty workspace via the plugin's headless bootstrap (`EDT_MCP_IMPORT_PROJECTS`) and skips the live-infobase tools, so no 1C platform is needed. Each badge reflects its latest run. (New CI badges render once these workflows land on the default branch.)

# EDT MCP Server

MCP (Model Context Protocol) server plugin for 1C:EDT, enabling AI assistants (Claude, GitHub Copilot, Cursor, etc.) to interact with EDT workspace.

> [!TIP]
> **Contributing / making changes?** Read [CLAUDE.md](CLAUDE.md) first — it's the code-conduct "minefield map": hard don'ts and the stop-and-think-twice zones for this codebase (BM transactions, the bilingual ru/en model, cascading rename, etc.). Detailed how-to lives in the skills under `.claude/skills/`.

> [!IMPORTANT]
> **EDT version compatibility:**
> EDT 2025.2+ is supported

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
- 🖼️ **Form Inspection** - Get PNG screenshots and YAML layout snapshots from the form WYSIWYG editor
- 🚀 **Application Management** - Get applications, update database, launch in debug mode, terminate EDT-launched 1С clients
- 🎯 **Status Bar** - Real-time server status with tool name, execution time, and interactive controls
- ⚡ **Interruptible Operations** - Cancel long-running operations and send signals to AI agent
- 🏷️ **Metadata Tags** - Organize objects with custom tags, filter Navigator, keyboard shortcuts (Ctrl+Alt+1-0), multiselect support
- 📁 **Metadata Groups** - Create custom folder hierarchy in Navigator tree per metadata collection, with a toolbar toggle to hide groups temporarily
- ✏️ **Metadata Refactoring** - Create top-level objects with EDT default content; rename/delete metadata objects with full cascading updates across BSL code, forms and metadata; add new attributes to existing objects
- 🛠️ **Tool Management** - Enable/disable tools by group, presets (Analysis Only, Code Review, Development), per-tool parameter defaults

## Installation

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

### Required JVM flag for form screenshots

The `get_form_screenshot` and `get_form_layout_snapshot` tools need EDT to be launched with the following JVM flag:

```
-DnativeFormBufferedLayoutRender=true
```

**Without it**, both tools return blank output (gray PNG / empty `elements` list).

**Why:** EDT's `NativeRenderService` reads `nativeFormBufferedLayoutRender` once at class-load time. If it was unset at JVM startup, the singleton `HippoLayoutService` is constructed without its offscreen buffer handler, the C++ form renderer never writes captureable pixels back to Java, and the screenshot helper falls through to an SWT `Control.print()` of the native window — which on Windows produces a gray rectangle. Setting the flag at runtime via reflection does not help because the singleton has already been built.

**How to add it (persistent, recommended):**

1. Close EDT.
2. Open `1cedt.ini` (next to `1cedt.exe`, e.g. `C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\1cedt.ini`).
3. After the `-vmargs` line, add:

   ```
   -DnativeFormBufferedLayoutRender=true
   ```
4. Start EDT.

**How to add it (one-shot, no install changes):**

```cmd
"<path-to-EDT>\1cedt.exe" -data "<workspace>" -vmargs -DnativeFormBufferedLayoutRender=true
```

The same flag is also recommended for production EDT use — it enables the buffered native renderer that EDT itself benefits from.

If your screenshots still come back blank after adding the flag, verify with `-vmargs` actually appears before it in `1cedt.ini` (Eclipse stops parsing `-vmargs` block once it hits a non-`-D` line) and that EDT was fully restarted.

### Configuration

Go to **Window → Preferences → MCP Server**. The settings page has two tabs:

> [!NOTE]
> **Display language.** The settings page and the tag dialogs are bilingual (Russian / English) and follow the Eclipse/EDT display language — the same `-nl` launch argument (or OS locale) that localizes the rest of EDT. Launch EDT with `-nl ru` for Russian or `-nl en` for English; any other locale falls back to English. The MCP tool surface itself (tool names, descriptions and errors) stays English regardless, as it is the AI wire contract.

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

All 67 tools are organized into 9 semantic groups:

| Group | Description | Tools |
|-------|-------------|-------|
| **Core / Project** | EDT version, server self-diagnosis, on-demand tool guides, project listing, configuration, validation, XML export/import, project removal, project creation (configuration / extension / externalObjects) | `get_edt_version`, `get_server_status`, `get_tool_guide`, `list_projects`, `get_configuration_properties`, `clean_project`, `revalidate_objects`, `get_check_description`, `export_configuration_to_xml`, `import_configuration_from_xml`, `delete_project`, `create_project` |
| **Errors & Problems** | Error reporting and workspace markers (bookmarks, tasks) | `get_problem_summary`, `get_project_errors`, `get_markers` |
| **Code Intelligence** | Content assist, documentation, metadata browsing | `get_content_assist`, `get_platform_documentation`, `get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `get_subsystem_content`, `find_references` |
| **Tags** | Tag management | `get_tags`, `get_objects_by_tags` |
| **Applications & Testing** | App management, infobase create/delete, database updates, launch, termination, testing | `get_applications`, `create_infobase`, `delete_infobase`, `list_configurations`, `update_database`, `debug_launch`, `terminate_launch`, `run_yaxunit_tests` |
| **Debugging** | Breakpoints, stepping, variable inspection and modification | `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `wait_for_break`, `get_variables`, `set_variable`, `step`, `resume`, `evaluate_expression`, `debug_yaxunit_tests`, `debug_status`, `start_profiling`, `stop_profiling`, `get_profiling_results` |
| **BSL Code** | Module browsing, code reading/writing, search, form layout inspection | `read_module_source`, `write_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `read_method_source`, `get_method_call_hierarchy`, `go_to_definition`, `get_symbol_info`, `get_form_layout_snapshot`, `get_form_screenshot`, `get_template_screenshot`, `validate_query` |
| **Refactoring** | Metadata create (objects, members and form members), rename, delete, set properties, adopt into an extension | `create_metadata`, `rename_metadata_object`, `delete_metadata`, `modify_metadata`, `adopt_metadata_object` |
| **Translation (LanguageTool)** | Translation strings generation, configuration synchronization, project info | `generate_translation_strings`, `translate_configuration`, `get_translation_project_info` |

Enable or disable entire groups or individual tools from the **Tools** tab in **Window → Preferences → MCP Server**. Disabled tools are filtered out of `tools/list` responses. If a client calls a disabled tool directly through `tools/call`, the server returns a message explaining that the tool is disabled.

### Presets

Quickly switch between common tool configurations using presets:

| Preset | Description |
|--------|-------------|
| **All Tools** | All 67 tools enabled (default) |
| **Analysis Only** | Read-only analysis — Core, Errors, Code Intelligence, Tags |
| **Code Review** | Analysis + BSL code reading (excludes `write_module_source`) |
| **Development** | Full development without debugging tools |

Select a preset from the dropdown in the Tools tab. The preset auto-detects based on the current enabled/disabled state and shows "Custom" when the configuration doesn't match any built-in preset.

### Per-Tool Parameter Defaults

Some tools have configurable default values for parameters like result limits. These defaults are used when the AI client doesn't specify the parameter explicitly:

| Tool | Parameter | Default | Range |
|------|-----------|---------|-------|
| `get_project_errors` | Result limit | 100 | 1–1000 |
| `get_markers` | Result limit | 100 | 1–1000 |
| `get_metadata_objects` | Result limit | 100 | 1–1000 |
| `get_content_assist` | Result limit | 100 | 1–1000 |
| `search_in_code` | Max results | 100 | 1–500 |
| `search_in_code` | Context lines | 2 | 0–5 |
| `read_module_source` | Max lines | 500 | 100–50000 |
| `terminate_launch` | Termination timeout (sec) | 10 | 1–120 |

Configure these in the Tools tab by selecting a tool that has configurable parameters — the parameter editors appear in the details panel below the tool tree.

### Progressive Tool Disclosure (dynamic toolsets)

For clients that work better with a small initial tool surface, the server can expose
only a **core** toolset up front and reveal the rest on demand — shrinking the
always-loaded `tools/list`. This is **off by default** (the full list is exposed,
unchanged); turn it on with the **Progressive disclosure** preference (or the
`EDT_MCP_PROGRESSIVE_DISCLOSURE=true` environment variable for headless/CI runs).

When on, only the `core` toolset (navigation, source read, metadata discovery, and the
two management tools) appears in `tools/list`. To use more tools:

1. Call **`list_toolsets`** to see the groups (`core`, `metadata`, `code`, `debug`,
   `testing`, `profiling`, `forms`, `tags`, `translation`, `project`) and their tools.
2. Call **`enable_toolset`** with `toolsets=[ids]` (e.g. `["code","debug"]`).
3. **Re-request `tools/list`** — the revealed tools now appear.

This server does not push `notifications/tools/list_changed`, so the client must
re-list after enabling (the `enable_toolset` response says so). A hidden tool is only
hidden from `tools/list`; it remains callable by name. These progressive-disclosure
toolsets are distinct from the **Tool Groups** above (which the Tools tab uses to
enable/disable tools persistently).

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

`claude_desktop_config.json` accepts only stdio servers (a `command`), not a direct `url`, so bridge the HTTP endpoint with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) (requires [Node.js](https://nodejs.org/)):
```json
{
  "mcpServers": {
    "EDT MCP Server": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8765/mcp"]
    }
  }
}
```
> The **Settings → Connectors** ("custom connector") route needs an HTTPS server reachable from Anthropic's cloud, so a plain-HTTP `localhost` endpoint must go through the `mcp-remote` stdio bridge above. You *could* expose it over an HTTPS tunnel (ngrok / Cloudflare Tunnel) to use a connector — but this server can write code into your configuration and start a debugger, so putting it on the public internet is a serious security risk and is not recommended.

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

The full per-tool reference lives in **[docs/tools/](docs/tools/)** — one page per
tool (what it does, every parameter, and how it works), generated from the live MCP
server (`get_tool_guide`) so it never drifts from the code. Index below; re-generate
with `python docs/generate_tool_docs.py`.

<!-- TOOLS-INDEX:START -->
<!-- generated by docs/generate_tool_docs.py — do not edit by hand -->

**76 tools**, grouped by toolset. Full per-tool pages under [docs/tools/](docs/tools/).

### Core

> Always-on essentials: project/module navigation, source read, metadata discovery, and the toolset-management tools (list_toolsets / enable_toolset).

| Tool | Description |
|------|-------------|
| [`enable_toolset`](docs/tools/enable_toolset.md) | Reveal (or hide) tool groups for progressive disclosure. Pass toolsets=[ids] from list_toolsets to reveal them, then RE-REQUEST tools/list to see the newly r… |
| [`get_edt_version`](docs/tools/get_edt_version.md) | Returns the running 1C:EDT version as a plain version string. Returns "Unknown" when the version cannot be determined. |
| [`get_metadata_details`](docs/tools/get_metadata_details.md) | Get detailed properties of one or more 1C metadata objects (basic info by default, or every reflected section with 'full: true'). Use it after get_metadata_o… |
| [`get_metadata_objects`](docs/tools/get_metadata_objects.md) | Get a flat list of 1C configuration metadata objects (Name, Synonym, Comment, Type, ObjectModule, ManagerModule) as a Markdown table. Use it to discover what… |
| [`get_module_structure`](docs/tools/get_module_structure.md) | Get structure of a BSL module: all procedures/functions with signatures, line numbers, regions, execution context (&AtServer, &AtClient), export flag, and pa… |
| [`get_server_status`](docs/tools/get_server_status.md) | Self-diagnosis snapshot of the running MCP server: listening port, MCP protocol version, plugin version, EDT version, enabled/total tool counts, the plainTex… |
| [`get_tool_guide`](docs/tools/get_tool_guide.md) | Get the full on-demand how-to for a tool: its description, every parameter (type, required, allowed values) and extended examples/preconditions kept OUT of t… |
| [`list_modules`](docs/tools/list_modules.md) | List BSL modules in an EDT project as a table (module path, module type, parent type, parent name). Use it to discover module paths before reading or editing… |
| [`list_projects`](docs/tools/list_projects.md) | List all workspace projects with properties (name, path, type, natures) |
| [`list_toolsets`](docs/tools/list_toolsets.md) | List the tool groups (toolsets) used by progressive tool disclosure: each toolset's id, title, description, member tools, and whether it is currently visible… |
| [`read_module_source`](docs/tools/read_module_source.md) | Read BSL module source code from an EDT project, whole file or a line range. Returns YAML frontmatter (including a contentHash revision token to round-trip i… |
| [`search_in_code`](docs/tools/search_in_code.md) | Literal/regex full-text search across all BSL modules in a project. Matching is purely textual and NOT ru/en dialect-aware, so a query in one BSL language wo… |

### Metadata

> Metadata objects: discovery, create/modify/delete/rename/adopt, subsystems, configuration.

| Tool | Description |
|------|-------------|
| [`adopt_metadata_object`](docs/tools/adopt_metadata_object.md) | Adopt a base-configuration metadata object or member (object / form / attribute / tabular section / ...) into a configuration EXTENSION so the extension can… |
| [`create_launch_config`](docs/tools/create_launch_config.md) | Create a 1C:EDT runtime-client launch configuration (thin/thick/web). The SAME config works for both run and debug (mode is chosen at launch time by debug_la… |
| [`create_metadata`](docs/tools/create_metadata.md) | Create a metadata node addressed by a 1C full-name FQN: a top-level object (Catalog.Products) or a subordinate member (Catalog.Products.Attribute.Weight, Inf… |
| [`delete_launch_config`](docs/tools/delete_launch_config.md) | Delete a 1C:EDT launch configuration by name (runtime client or Attach). Destructive: guarded by a confirm-preview - call without confirm to preview (no chan… |
| [`delete_metadata`](docs/tools/delete_metadata.md) | Delete a metadata node (object or member, including a FORM object 'Type.Object.Form.Name' or a FORM member - item / attribute / command / handler) addressed… |
| [`export_common_picture`](docs/tools/export_common_picture.md) | Export a 1C CommonPicture (общая картинка) as PNG and list its picture variants (dpi, theme, interface variant, direction, template flag, glyph size). Resolv… |
| [`get_configuration_properties`](docs/tools/get_configuration_properties.md) | Get 1C:Enterprise configuration properties (name, synonym, comment, script variant, compatibility mode, etc.) |
| [`get_subsystem_content`](docs/tools/get_subsystem_content.md) | Get one 1C subsystem's content: properties, its metadata objects (Type/Name/Synonym/FQN) and child subsystems, identified by FQN (e.g. 'Subsystem.Sales.Subsy… |
| [`list_common_pictures`](docs/tools/list_common_pictures.md) | List a 1C configuration's CommonPicture objects and the variants each carries in its Picture.zip (DPI, theme, interface variant, template flag, glyph size, p… |
| [`list_configurations`](docs/tools/list_configurations.md) | List EDT launch configurations (runtime client + Attach + other 1C types) with their running state. This is the discovery step before debug_launch / run_yaxu… |
| [`list_subsystems`](docs/tools/list_subsystems.md) | List 1C subsystems of a configuration as a flat table (FQN, Synonym, Comment, InCommandInterface, content count, children count). Walks the whole tree by def… |
| [`modify_metadata`](docs/tools/modify_metadata.md) | Set properties of a metadata node (object or member, including a FORM member - item / attribute / command) addressed by a 1C full-name FQN, as properties=[{n… |
| [`rename_metadata_object`](docs/tools/rename_metadata_object.md) | Rename a metadata object or attribute, cascading the change across all references in BSL code, forms, and other metadata. Use the two-phase workflow: call wi… |

### Code

> BSL code: write/read methods, call hierarchy, go-to-definition, references, content assist, queries.

| Tool | Description |
|------|-------------|
| [`find_references`](docs/tools/find_references.md) | Find every place a metadata object is used: BSL code modules (with line numbers), other metadata, forms, roles, subsystems, etc. Pass the object FQN; the typ… |
| [`get_content_assist`](docs/tools/get_content_assist.md) | Get code-completion proposals at a 1-based line/column in a BSL module - the members, globals and variables valid at that caret (e.g. after a '.'). May retur… |
| [`get_method_call_hierarchy`](docs/tools/get_method_call_hierarchy.md) | Find a BSL method's call hierarchy: who calls it (callers, default) or what it calls (callees), via semantic AST analysis that resolves ru/en spellings (unli… |
| [`get_outgoing_structures`](docs/tools/get_outgoing_structures.md) | For each outgoing qualified call in a BSL module (or one method), report the top-level literal keys of the Structure passed as its first argument (local .Ins… |
| [`get_symbol_info`](docs/tools/get_symbol_info.md) | Get type/hover info about a symbol at a position in a BSL module. Returns inferred types, signatures, and documentation. |
| [`go_to_definition`](docs/tools/go_to_definition.md) | Go to the definition of a symbol (the inverse of find_references): a qualified method 'ModuleName.MethodName', a bare 'MethodName' (also pass modulePath), or… |
| [`read_method_source`](docs/tools/read_method_source.md) | Read a specific procedure/function from a BSL module by name. Returns source code with metadata. Lists available methods if not found. Use this for one metho… |
| [`validate_query`](docs/tools/validate_query.md) | Validate 1C:Enterprise query language (QL) text against a project, returning syntax and semantic errors with line numbers. Use to check a query before embedd… |
| [`write_module_source`](docs/tools/write_module_source.md) | Write BSL source code to a 1C metadata object module. Use to edit a module: searchReplace a fragment (default, needs oldSource), replace the whole file, or a… |

### Debug

> Runtime debugging: launch/attach, breakpoints, step/resume, variables, expression evaluation.

| Tool | Description |
|------|-------------|
| [`debug_launch`](docs/tools/debug_launch.md) | Start an EDT debug session: either an existing config by launchConfigurationName (runtime client OR Attach, the latter needed to debug server-side code), or… |
| [`debug_status`](docs/tools/debug_status.md) | Report active debug sessions: applicationId (real or synthetic 'attach:<name>' / 'launch:<name>'), launch configuration name/type, mode (debug/run), whether… |
| [`evaluate_expression`](docs/tools/evaluate_expression.md) | Evaluate a BSL expression in the context of a suspended stack frame. Pass frameRef from wait_for_break and the expression text. WARNING: this executes arbitr… |
| [`get_applications`](docs/tools/get_applications.md) | Get list of applications (infobases) for a project. Returns application ID, name, type, and update state. Application ID is required for update_database and… |
| [`get_variables`](docs/tools/get_variables.md) | Read variables from a stack frame of a suspended debug thread. Pass frameRef from wait_for_break (preferred) or threadId+frameIndex. Use expandPath to drill… |
| [`list_breakpoints`](docs/tools/list_breakpoints.md) | List active line breakpoints. Optionally filter by projectName. |
| [`remove_breakpoint`](docs/tools/remove_breakpoint.md) | Remove a 1C BSL line breakpoint. Either pass breakpointId (returned from set_breakpoint) or projectName+module+lineNumber to look it up by coordinates. |
| [`resume`](docs/tools/resume.md) | Resume a suspended debug thread or all threads of a debug target. Pass threadId (from wait_for_break) or applicationId. applicationId accepts ANY id form for… |
| [`set_breakpoint`](docs/tools/set_breakpoint.md) | Set a line breakpoint on a 1C BSL module. Accepts either an EDT module-relative path (e.g. 'CommonModules/Foo/Module.bsl') or an absolute filesystem path. Us… |
| [`step`](docs/tools/step.md) | Step a suspended debug thread. kind ∈ {over, into, out}. Blocks until the next SUSPEND event (or timeout) and returns the new frame snapshot. |
| [`terminate_launch`](docs/tools/terminate_launch.md) | Terminate one or more 1C launches started from THIS EDT instance; externally launched 1C clients are never touched. Select ONE target mode: launchConfigurati… |
| [`wait_for_break`](docs/tools/wait_for_break.md) | Wait for a debug suspend event (e.g. breakpoint hit) on the given application. Returns the suspended thread/frame snapshot, or {hit:false} on timeout. applic… |

### Testing

> YAXUnit unit testing: run and debug test suites.

| Tool | Description |
|------|-------------|
| [`debug_yaxunit_tests`](docs/tools/debug_yaxunit_tests.md) | Deprecated alias for run_yaxunit_tests with debug=true. Launches YAXUnit tests in DEBUG mode so breakpoints fire, then call wait_for_break to inspect. Prefer… |
| [`run_yaxunit_tests`](docs/tools/run_yaxunit_tests.md) | Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. Polls for up to `timeout` seconds, then returns the report or **Pending** (… |

### Profiling

> Performance profiling: start/stop a measurement and read the results.

| Tool | Description |
|------|-------------|
| [`get_profiling_results`](docs/tools/get_profiling_results.md) | Get profiling (performance measurement) results after a debug session: per-module, per-line call count, timing and percentage. Returns only the MOST RECENT m… |
| [`start_profiling`](docs/tools/start_profiling.md) | Start performance measurement on the active debug target. Enables line-level profiling: call counts and timing for every executed BSL line. Start-only and id… |
| [`stop_profiling`](docs/tools/stop_profiling.md) | Stop performance measurement on the active debug target. Counterpart to start_profiling: deterministically switches profiling off. Idempotent: if profiling i… |

### Forms

> Form and template rendering: form layout snapshot, form screenshot, template screenshot.

| Tool | Description |
|------|-------------|
| [`get_form_layout_snapshot`](docs/tools/get_form_layout_snapshot.md) | Return a YAML snapshot of a form's calculated WYSIWYG layout (bounds, element types, display properties) as text; use it to inspect or compare what a form ac… |
| [`get_form_screenshot`](docs/tools/get_form_screenshot.md) | Capture a PNG screenshot of a form's WYSIWYG editor; pass formPath to open the form automatically or omit it to shoot the active editor. Requires EDT launche… |
| [`get_template_screenshot`](docs/tools/get_template_screenshot.md) | Capture a PNG screenshot of a 1C template (a SpreadsheetDocument print form) as EDT renders it, so its layout and text are visible to an AI. Works for a comm… |

### Tags

> Tag-based organization: list tags and find objects by tag.

| Tool | Description |
|------|-------------|
| [`get_objects_by_tags`](docs/tools/get_objects_by_tags.md) | Get metadata objects filtered by tags. Returns objects that have any of the specified tags, including tag descriptions and object FQNs. |
| [`get_tags`](docs/tools/get_tags.md) | Get list of all tags defined in the project. Tags are user-defined labels for organizing metadata objects. Returns tag name, color, description, and number o… |

### Translation

> Configuration translation via LanguageTool: extract, translate, project info.

| Tool | Description |
|------|-------------|
| [`generate_translation_strings`](docs/tools/generate_translation_strings.md) | Generate translation strings (.lstr/.trans/.dict) for a configuration project: scans translatable features and writes the resulting keys into the project's s… |
| [`get_translation_project_info`](docs/tools/get_translation_project_info.md) | Return LanguageTool metadata for a project: the translation storages declared on it and the available translation provider IDs. Use it to check whether a dic… |
| [`translate_configuration`](docs/tools/translate_configuration.md) | Run EDT 'Translate configuration' on a configuration project - reads the dictionaries from the storages bound to it (external dictionary storage projects wit… |

### Project

> Project operations: clean/revalidate, update DB, export/import XML, problems and markers, docs.

| Tool | Description |
|------|-------------|
| [`build_external_objects`](docs/tools/build_external_objects.md) | Build (compile to disk) the external data processors/reports of an EDT external-object project to .epf/.erf files. Build ONE object with objectName, or ALL o… |
| [`clean_project`](docs/tools/clean_project.md) | Clean EDT project and trigger full revalidation. Direction: DISK -> MODEL - re-imports the on-disk src/ .mdo files into the in-memory model. Refreshes files… |
| [`create_infobase`](docs/tools/create_infobase.md) | Create a new FILE infobase (1C database) OR register an existing one, and bind it to a configuration project so it appears in get_applications. mode='create'… |
| [`create_project`](docs/tools/create_project.md) | Create a NEW 1C project in the EDT workspace. projectKind selects the kind: 'configuration' (standalone), 'extension' (bound to a base configuration), or 'ex… |
| [`delete_infobase`](docs/tools/delete_infobase.md) | Remove a FILE infobase association from a configuration project OR delete a standalone (autonomous) server application. Destructive: guarded by a confirm-pre… |
| [`delete_project`](docs/tools/delete_project.md) | Remove an EDT project from the workspace, optionally deleting its files from disk (deleteContent). Destructive: guarded by a confirm-preview - call without c… |
| [`export_configuration_to_xml`](docs/tools/export_configuration_to_xml.md) | Export an EDT configuration project to XML files (EDT menu: Export -> Configuration to XML Files). Equivalent of 1C platform DumpConfigToFiles. |
| [`get_check_description`](docs/tools/get_check_description.md) | Get detailed description of an EDT check by its ID. Returns markdown content with check explanation, examples, and how to fix. Accepts the symbolic check id… |
| [`get_markers`](docs/tools/get_markers.md) | List workspace markers: bookmarks and/or task markers (TODO, FIXME, XXX, HACK). Filter by markerKind (bookmark \| task; omit to list both), projectName, fileP… |
| [`get_platform_documentation`](docs/tools/get_platform_documentation.md) | Look up 1C:Enterprise platform documentation for built-in types (ValueTable, Array, Structure) and global built-in functions, including their methods, proper… |
| [`get_problem_summary`](docs/tools/get_problem_summary.md) | Get problem summary with counts grouped by project and EDT severity level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL). Use this for severity totals on… |
| [`get_project_errors`](docs/tools/get_project_errors.md) | List EDT configuration problems (validation markers) with optional project / severity / check-id / object filters. Each row carries the check code, message,… |
| [`import_configuration_from_xml`](docs/tools/import_configuration_from_xml.md) | Import a configuration from a directory of XML files into a NEW EDT project (EDT menu: Import); the reverse of export_configuration_to_xml. The projectName m… |
| [`resync_to_disk`](docs/tools/resync_to_disk.md) | Bulk re-synchronize the in-memory BM model to the on-disk src/ .mdo files and report BM-to-disk desync. Direction: MODEL -> DISK (writes the model out to src… |
| [`revalidate_objects`](docs/tools/revalidate_objects.md) | Revalidate EDT project or specific objects. If objects array is empty or missing, revalidates entire project. FQN examples: 'Document.SalesOrder', 'Catalog.P… |
| [`set_infobase_credentials`](docs/tools/set_infobase_credentials.md) | Store infobase connection credentials (user/password) so update_database and debug_launch can authenticate the update agent on an infobase that has a user li… |
| [`update_database`](docs/tools/update_database.md) | Apply configuration changes to an application's database (infobase), full or incremental. Target by launchConfigurationName (preferred) or projectName + appl… |

<!-- TOOLS-INDEX:END -->

## Output Formats

Each tool declares a response type (`IMcpTool.getResponseType()`). **The default — and the most common type — is Markdown**: any tool that does not override `getResponseType()` returns Markdown as an EmbeddedResource with `mimeType: text/markdown`. The non-default types are enumerated exhaustively below; everything not listed here is Markdown.

#### Response format policy (Markdown vs JSON)

`structuredContent`/JSON exists for clients to consume, not for the agent to read: it is justified only by verbatim round-trip of identifiers, machine-structured positions, a declared `outputSchema`, or UI-rendered data. **Markdown is the default** because it is more token-efficient and directly readable.

A tool returns **JSON** only when its result carries at least one of:

- **(a) round-trip IDs** another tool consumes (e.g. a created object's FQN, a launch/application ID, a breakpoint ID);
- **(b) machine-structured positions** (e.g. an error line/column);
- **(c) a declared `outputSchema`**;
- **(d) UI-rendered data**.

An action/confirmation/status result with **none** of these returns **Markdown**. `write_module_source` is the reference Markdown action tool; `revalidate_objects`, `export_configuration_to_xml`, and `import_configuration_from_xml` follow it (status + paths/counts, no round-trip data).

Which tool families stay JSON, and why:

- **metadata-writes** (`create_metadata`, `modify_metadata`, `delete_metadata`, via `AbstractMetadataWriteTool`) — return the edited object's round-trip **FQN** *(a)*;
- **debug / profiling tools** — return launch / application / breakpoint IDs and live session state consumed by follow-up calls *(a)*;
- **`validate_query`** — returns the error **line/column** *(b)*;
- **`list_configurations`** — returns config **identities** consumed by other tools *(a)*;
- **`clean_project`** / **`update_database`** — return a destructive status whose JSON shape is asserted by e2e *(d-like contract)*.

Errors are reported the same way regardless of a tool's normal format — see the **Error contract** below.

- **Markdown tools** (the default): every tool that is not listed under another type below, returned as an EmbeddedResource with `mimeType: text/markdown`. This includes all read/list/search/navigation tools that emit human-readable reports — for example `list_projects`, `list_modules`, `list_subsystems`, `list_configurations`*, `get_project_errors`, `get_markers`, `get_problem_summary`, `get_check_description`, `get_metadata_objects`, `get_metadata_details`, `get_module_structure`, `get_subsystem_content`, `get_symbol_info`, `get_method_call_hierarchy`, `get_objects_by_tags`, `get_tags`, `get_platform_documentation`, `find_references`, `go_to_definition`, `search_in_code`, `read_module_source`, `read_method_source`, `write_module_source`, `rename_metadata_object`, `run_yaxunit_tests`, `terminate_launch`, `revalidate_objects`, `export_configuration_to_xml`, `import_configuration_from_xml`, and all three LanguageTool tools (`generate_translation_strings`, `translate_configuration`, `get_translation_project_info`). (*`list_configurations` is the exception among the `list_*` tools — it returns JSON; see below.)
- **YAML tools**: `get_configuration_properties` — returns a human-readable YAML body as an EmbeddedResource (resource named `*.yaml`, `mimeType: text/yaml`).
- **JSON tools** (return JSON with `structuredContent`): `get_server_status`, `get_applications`, `create_infobase`, `delete_infobase`, `get_content_assist`, `get_variables`, `get_profiling_results`, `list_configurations`, `list_breakpoints`, `set_breakpoint`, `remove_breakpoint`, `step`, `resume`, `wait_for_break`, `debug_launch`, `debug_status`, `debug_yaxunit_tests`, `evaluate_expression`, `start_profiling`, `stop_profiling`, `validate_query`, `clean_project`, `update_database`, `delete_project`, plus the metadata-write tools that inherit JSON from `AbstractMetadataWriteTool` (`create_metadata`, `modify_metadata`, `delete_metadata`).
- **Text tools** (plain text): `get_edt_version`, `get_form_layout_snapshot`.
- **Image tools**: `get_form_screenshot` — returns the rendered form as an EmbeddedResource with an `image/*` `mimeType`.

#### Error contract

Whatever a tool's normal output format above, it reports a **failure** the same way: a JSON payload `{"success": false, "error": "<message>"}` that the server delivers as a structured tool error (`isError: true`) regardless of the declared response type. The `error` field is always present (a `null` exception message is coalesced to `Unknown error`) and the message carries no redundant `Error:` prefix. A client detects failure via `isError` / `success: false` and reads `error` for the reason — no markdown parsing required. Success and purely *informational* results (for example "No references found", or a not-found accompanied by a list of valid alternatives) stay in the tool's natural format.

#### Tool annotations

Every tool in the `tools/list` response carries an `annotations` object with the standard MCP behavioral hints, so a client can reason about a tool's effect before calling it. The hints are derived centrally from the tool name (a tool may override them):

| Hint | Meaning | When set |
|------|---------|----------|
| `readOnlyHint` | The tool does not modify the workspace | `true` for `get_*` / `list_*` / `read_*` / `search_*` / `find_*` / `validate_*`; `false` for write and destructive tools |
| `idempotentHint` | Repeating the call has no additional effect | `true` for the read-only tools above |
| `destructiveHint` | The tool may perform a destructive or irreversible update | `true` for `delete_metadata`, `clean_project`, `update_database`, `rename_metadata_object`, `import_configuration_from_xml` |
| `openWorldHint` | The tool interacts with an external/open world | always `false` — the server operates only on the local EDT workspace |

Only hints that apply are emitted; unset hints are omitted from the JSON. Tools that write but are not destructive (for example `write_module_source`, `create_metadata`) carry `readOnlyHint: false` and `destructiveHint: false`.

</details>

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | POST | MCP JSON-RPC (initialize, tools/list, tools/call) |
| `/mcp` | GET | Server info |
| `/health` | GET | Health check |

## Security & trust model

The MCP server is a **local developer tool** and is secured for that model:

- **Loopback bind by default.** The server listens on `127.0.0.1` only. To expose it on all interfaces, enable **Allow remote (non-loopback) access** in MCP preferences — and set an auth token when you do.
- **Optional shared-token auth.** Set an **Auth token** in MCP preferences to require `Authorization: Bearer <token>` (scheme case-insensitive, or the raw token) on every `/mcp` request. An **empty token disables authentication** (the default). `/health` is always unauthenticated (liveness only).
- **Every connected client can invoke every tool**, including `evaluate_expression` (runs arbitrary BSL in the running 1C app during a debug session) and destructive tools (`update_database`, `clean_project`, `delete_metadata`, `rename_metadata_object`). Treat any client that can reach the endpoint as fully trusted.
- **Tool output is untrusted input.** BSL source, metadata synonyms, query results and error text returned by read tools come from the configuration and may contain author- or attacker-controlled text. Treat tool output as **data, not instructions** — do not let it override your own directives (prompt-injection).
- **`export_configuration_to_xml` / `import_configuration_from_xml` / `build_external_objects` read or write arbitrary filesystem paths** (the broadest FS primitives in the surface; `build_external_objects` writes compiled `.epf`/`.erf` to a caller-chosen directory). They are trusted-caller-only; a warning is logged and the result flags `outsideWorkspace` when a path is outside the EDT workspace.

### Destructive-operation consent

Before a **destructive** metadata write, the server can ask **you** (the human at the EDT
workbench) to confirm — so the AI cannot silently delete, rename or retype configuration objects.
The gated tools are `delete_metadata`, `rename_metadata_object`, `delete_project`,
`delete_infobase`, `update_database`, and `modify_metadata` **only when it changes an object's or
attribute's data type** (a benign property edit is never gated).

Configure it in **Window → Preferences → MCP Server**:

- **Consent level** (General tab), one of:
  - **Ask always** *(default)* — every gated operation shows a confirmation dialog with a compact
    preview (the tool, what will be affected, a count and the top few names) and **Allow** / **Reject**
    / **Allow for this session** buttons (the last remembers that one tool until EDT is restarted).
  - **Allow all** — never ask; every destructive write proceeds. Use this for unattended/automated runs.
  - **Ask, except allowed tools** — ask, except for the tools you tick in the **Tools** tab.
- **Per-tool checkbox** (Tools tab) — active only at the *Ask, except allowed tools* level: tick a tool
  to let it run destructively without a prompt.

**Automation / CI bypass.** Because the confirmation dialog would block a headless or automated run,
set the environment variable **`EDT_MCP_DESTRUCTIVE_CONSENT=allow`** on the EDT process before launch —
it overrides the preference and lets every gated operation proceed without a dialog (the same knob the
e2e suite uses). When there is no active workbench window (a headless server), the gate never blocks
either. The dialog only ever appears on a live UI session at the *Ask* level.

### Infobase authentication dialog

When a target infobase has a **user list**, connecting to it during `update_database` or `debug_launch`
can raise 1C's blocking **"Configure Infobase access Settings"** login dialog. To keep unattended MCP
calls from hanging on it, the server **auto-cancels that dialog while a tool is running** — the
MCP-triggered connect fails fast with a hint to `set_infobase_credentials` instead of blocking forever
(issue #194). Store the credentials once with `set_infobase_credentials` and the connect no longer
needs the dialog.

The auto-cancel is **activity-scoped**: it fires only while an MCP tool is in flight (plus a short grace
window for the asynchronous read-back that follows a tool). So when the **MCP server is idle**, you can
open EDT's **"Configure Infobase access"** dialog **by hand in the GUI** and use it normally — the
credentials you enter are stored into EDT's encrypted **Secure Storage** (the leak-free path), exactly
as the configurator does it. Only dialogs raised by MCP activity are cancelled; a human configuring
credentials between agent runs is never interrupted. (The Secure-Storage password-hint dialog stays
suppressed unconditionally — it is internal and never human-configured.)

**Automation / CI bypass.** The auto-cancel is **on by default**. To turn it **off** — e.g. to debug the
login flow interactively, or on a stand where you want the prompt to appear even during MCP calls — set
the environment variable **`EDT_MCP_SUPPRESS_AUTH_DIALOG=false`** (also `0` / `no`) on the EDT process
before launch. Any other value — or leaving it unset — keeps auto-cancel enabled, so an unattended run
never regresses into a hang.

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
- Use the **Hide Groups** toggle button in the Navigator toolbar to temporarily hide virtual group folders and show grouped objects in their original collections again

### Group Operations

| Action | How to Do It |
|--------|--------------|
| Create group | Right-click folder → **New Group...** |
| Add object to group | Right-click object → **Add to Group...** |
| Remove from group | Right-click object in group → **Remove from Group** |
| Copy group name | Select group → **Ctrl+C** |
| Delete group | Right-click group → **Delete** |
| Rename group | Right-click group → **Rename...** |
| Hide/show groups | Click **Hide Groups** in the Navigator toolbar |

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

## Building from source

The plugin is a Maven/Tycho project under [mcp/](mcp/). CI builds it via [.github/workflows/build.yml](.github/workflows/build.yml); the same flow can be run locally with [source/compile.sh](source/compile.sh).

### Prerequisites

- JDK 17 (e.g. Temurin / Oracle JDK)
- Apache Maven 3.9+ (no `mvnw` wrapper is committed — install Maven manually or via a package manager: `winget`, Homebrew, `apt`, SDKMAN, etc.)
- `bash` (Git Bash on Windows works) and either `zip` or the `jar` binary that ships with the JDK
- Network access to `https://edt.1c.ru/`, `https://download.eclipse.org/` and Maven Central — Tycho downloads the EDT p2 repository and Eclipse SDK on the first run (hundreds of MB, cached afterwards under `~/.m2/`)

### Quick start

```bash
# from the repo root
bash source/compile.sh --skip-tests
```

Output:

```
source/dist/MCP-EDT.v<VERSION>.zip
```

This is a valid p2 update site — install via EDT → *Help → Install New Software → Add → Archive…*.

### Script options

`source/compile.sh` accepts every path as a flag (with matching environment-variable fallback) so it can be driven from CI or run against an out-of-tree checkout:

| Flag | ENV fallback | Default | Meaning |
|---|---|---|---|
| `--skip-tests` | — | off | Skip Maven Surefire tests |
| `--version X.Y.Z` | — | parsed from `README.md`, falls back to `dev` | Version label used in the output zip name |
| `--archive-prefix PREFIX` | — | `MCP-EDT.v` | Archive name prefix (final name: `<prefix><version>.zip`) |
| `--project-root PATH` | `EDT_MCP_PROJECT_ROOT` | parent of script dir | Repo root containing `mcp/` |
| `--mcp-dir PATH` | — | `<project-root>/mcp` | Maven project directory |
| `--repo-dir PATH` | — | `<project-root>/mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository` | Tycho p2 output to repackage |
| `--output-dir PATH` | `EDT_MCP_OUTPUT_DIR` | `<script-dir>/dist` | Where the final zip lands |
| `--java-home PATH` | `JAVA_HOME` | — | JDK 17 home; if set, prepended to `PATH` for Maven |
| `--maven-home PATH` | `MAVEN_HOME` / `M2_HOME` | — | Maven home (uses `<maven-home>/bin/mvn`); otherwise falls back to `mvn` on `PATH` |
| `-h`, `--help` | — | — | Show help |

### Examples

```bash
# Self-contained invocation, no env tweaks required
bash source/compile.sh \
    --java-home "/c/Program Files/Java/jdk-17" \
    --maven-home /d/Soft/maven \
    --skip-tests \
    --version 1.27.1

# Drop the artifact somewhere else
bash source/compile.sh --output-dir /tmp/edt-mcp-builds

# Same, configured via environment
JAVA_HOME="/c/Program Files/Java/jdk-17" \
MAVEN_HOME=/d/Soft/maven \
EDT_MCP_OUTPUT_DIR=/tmp/edt-mcp-builds \
bash source/compile.sh
```

### Notes

- A full first build pulls the EDT 2025.2 / 2026.1 p2 repository (depending on `mcp/targets/default/default.target`) and the Eclipse 2023-12 release — expect several minutes. Subsequent builds run in ~1 minute thanks to the local p2 cache.
- The output zip uses forward-slash entries (produced by `jar` when `zip` is unavailable) so it installs cleanly on both Windows and Linux EDT instances.
- `source/dist/` is gitignored; only the script itself is tracked.

## AI-assisted development: the `edt-mcp-autopilot` skill

This repository ships a Claude Code project skill at `.claude/skills/edt-mcp-autopilot/` that takes a whole task or issue end-to-end with minimal human input, using a multi-agent, Spec-Driven (SDD) pipeline. The code-conduct it follows lives in [CLAUDE.md](CLAUDE.md) and the sibling skills under `.claude/skills/`.

**Pipeline (phases):** study the task → documentation researchers → several waves of code researchers (loop-until-dry) → adversarial critics (refuted findings dropped, "rework" bounced back) → an architect that synthesises a spec and a file-disjoint developer partition → N parallel developers → a 3–4 reviewer loop until clean → build + unit tests → live-stand scenarios → a Russian issue comment and a pull request.

**How to run (Claude Code):** the skill is auto-discovered from `.claude/skills/`. Invoke it with

```
/edt-mcp-autopilot <issue number or task description>
```

It runs unattended: the only mandatory human touchpoint is confirming the live-stand result before shipping; principal design questions are posted to the issue and polled until answered. The heavy fan-out runs through the Claude Code `Workflow` tool (the two scripts under `references/`), orchestrated by the main session as a "conductor". Scale the fan-out with the `size` argument (`small` / `medium` / `large`).

### ⚠️ This skill consumes a LOT of tokens

It deliberately spawns many subagents — documentation and code researchers in waves, a critic panel, parallel developers, and a cyclic reviewer pool. Measured cost per real task in this repository (subagent tokens only — the conductor, the Maven build and the live-stand verification add more on top):

| Phase | Subagents | Subagent tokens | Wall time |
|---|---|---|---|
| Discover (research → critics → architect) | ~8–9 | ~0.6–0.9 M | ~15 min |
| Build (parallel developers → reviewer loop) | ~11–15 | ~0.7–0.8 M | ~15 min |

So a single task's discover + build phases alone are roughly **20–25 subagents and ~1.3–1.8 M subagent tokens**; a full end-to-end task (with the conductor, the builds, golden regeneration and live-stand checks) typically runs **several million tokens and 30–60+ minutes**. A misconfigured run can be far worse — an early, pre-hardened version once burned ~9.8 M tokens / ~210 agents on a no-op before the guard rails were added.

Use it for substantial whole-task work — a feature, a non-trivial bug, or a new tool — **not** for small edits or quick questions, where working directly is far cheaper.

## Requirements

- 1C:EDT 2025.2 (Ruby) or later
- Java 17+

## License
# Copyright (C) 2026 DitriX
# Licensed under GNU AGPL v3.0
