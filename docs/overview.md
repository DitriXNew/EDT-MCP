# Overview

> **Build & Unit Tests**, **E2E**, and **MCP Conformance** all run on stock GitHub-hosted runners (cloud CI) — no docker image, no self-hosted runner. E2E and Conformance run against **EDT 2026.2** (build 2026.2, Eclipse 4.38 / Java 25): the setup step installs a headless EDT of that version on the runner via `p2 director`. E2E additionally imports the test fixtures into an empty workspace via the plugin's headless bootstrap (`EDT_MCP_IMPORT_PROJECTS`) and skips the live-infobase tools, so no 1C platform is needed. Each badge reflects its latest run.

> [!TIP]
> **Contributing / making changes?** Read [CLAUDE.md](../CLAUDE.md) first — it's the code-conduct "minefield map": hard don'ts and the stop-and-think-twice zones for this codebase (BM transactions, the bilingual ru/en model, cascading rename, etc.). Detailed how-to lives in the skills under `.claude/skills/`.

> [!TIP]
> **Using EDT-MCP on a 1C business project?** The client-neutral
> [business-project skills pack](../agent/README.md) routes common project tasks to
> compact workflows. The [rules pack](../rules/README.md) provides standing,
> detailed project policy; the two business-project layers are complementary.
> Both are separate from the plugin-contributor skills above.

> [!IMPORTANT]
> **EDT version compatibility:**
> Supports the latest 1C:EDT release, currently **2026.2.2** (Ruby); all CI runs on it.
> **Compatibility with earlier EDT versions is not guaranteed:** the plugin may work
> there, but nothing checks it and such a problem does not block a release.
> The plugin is COMPILED against the 2026.2 target platform but **to Java 17**
> (`release 17` plus `Bundle-RequiredExecutionEnvironment: JavaSE-17`). Building it needs
> a JDK 25 (Tycho 5 reads the platform's Java 25 class files); that is the JDK that
> *runs* the build, not the level it emits.

## Features

- 🔧 **MCP Protocol 2025-11-25** - Streamable HTTP with SSE and bundled stdio transport
- 📊 **Project Information** - List workspace projects and configuration properties
- 🔴 **Error Reporting** - Get errors, warnings, problem summaries with filters
- 📝 **Check Descriptions** - Get check documentation from markdown files
- 🔄 **Project Revalidation** - Trigger revalidation when validation gets stuck
- 🔖 **Bookmarks & Tasks** - Access bookmarks and TODO/FIXME markers
- 💡 **Content Assist** - Get type info, method hints and platform documentation at any code position
- 🧪 **Query Validation** - Validate query text or saved module/method query literals in project context, with syntax/semantic diagnostics and optional DCS mode
- 🧩 **BSL Code Analysis** - Browse modules, inspect structure, read/write methods, search code, and analyze call hierarchy
- 🖼️ **Form Inspection** - Get PNG screenshots and YAML layout snapshots from the form WYSIWYG editor
- 🚀 **Application Management** - Get applications, update database, launch in debug mode, terminate EDT-launched 1C clients
- 🎯 **Status Bar** - Real-time server status with tool name, execution time, and interactive controls
- ⚡ **Interruptible Operations** - Cancel long-running operations and send signals to AI agent
- 🏷️ **Metadata Tags** - Organize objects with custom tags, filter Navigator, keyboard shortcuts (Ctrl+Alt+1-0), multiselect support
- 📁 **Metadata Groups** - Create custom folder hierarchy in Navigator tree per metadata collection, with a toolbar toggle to hide groups temporarily
- ✏️ **Metadata Refactoring** - Create objects/members and generate supported forms; rename resolved references across BSL and metadata; preview deletions and their cleanup scope. Container-only form/XDTO deletes have no cross-object cascade
- 🧭 **Command Interface** - Read and edit a subsystem section's (or the main section's) command interface: command visibility, common and per role, placement into panel groups, and order within a group
- 🛠️ **Tool Management** - Enable/disable tools by group, presets (Analysis Only, Code Review, Development), per-tool parameter defaults

Optional [guided workflows](guided-workflows.md) expose project health through
an MCP prompt, project problems and guides through resource templates, and argument
completion. Enable with `EDT_MCP_GUIDED_WORKFLOWS=true` at EDT launch. Completion is
for prompt/resource-template arguments; it does not autocomplete ordinary tool arguments.
