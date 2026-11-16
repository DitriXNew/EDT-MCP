# Connecting AI Assistants

## stdio

In EDT, open **Window > Preferences > MCP Server > General** and clear **Enable MCP over HTTP** to select **Copy stdio .mcp.json**, then click it. Apply if you also want to stop HTTP; copying alone does not save the checkbox. A stdio-only launch with HTTP stopped already selects this button. Merge the ready entry into your client's configuration. The bootstrap ships inside the plugin, uses explicit installation/workspace paths and EDT's Java, and connects locally without a TCP port.

On Windows, the first tool call opens a normal EDT window if necessary; closing the client leaves that windowed EDT running. Add the explicit `-headless` argument to request an optional host without a window on the next cold start. Its catalog excludes mandatory UI tools, and it saves and exits after 30 idle minutes by default. Existing hosts are shared regardless of client mode. Linux supports attachment to a running host. Windows automatic startup needs WMI access, short UDS paths are required, and interrupted calls are never replayed. See [stdio setup and limits](stdio.md). HTTP configuration below remains supported.

**What the headless host can do.** Everything except six tools that need a real window: `get_content_assist`, `open_editor`, `get_form_screenshot`, `get_form_layout_snapshot`, `get_template_screenshot` and `edt_dialog`. BSL analysis, metadata and form writes, rename, delete and database update all run without a window. `get_symbol_info` returns the signature and parameters without the editor hover documentation.

> [!WARNING]
> **stdio with `-headless` allows everything, by design.** Choosing this mode is the decision to allow every operation. There is no interaction on this host, so there is nothing to agree to, and no setting or safeguard limits it: per-tool disabling, read-only presets and the destructive-consent window do not apply. Add `-headless` only for an agent you trust with the whole workspace.

Preview/confirm steps and tool preconditions still apply, and autosort stays your setting. A headless client that attaches to an already open windowed EDT follows the windowed rules.

## VS Code / GitHub Copilot

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

## Cursor IDE

> **Note:** Cursor doesn't support MCP embedded resources. Enable **"Plain text mode (Cursor compatibility)"** in EDT preferences: **Window → Preferences → MCP Server**. It moves a result into `content[0].text`; a JSON tool still returns its `structuredContent` as well, so a client that enforces the declared `outputSchema` is satisfied too.

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

## Claude Code

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

## Claude Desktop

Copy the bundled [stdio configuration](stdio.md) into `claude_desktop_config.json`. To bridge an existing HTTP endpoint instead, [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) requires [Node.js](https://nodejs.org/):
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

## Cline - extension for VSCode.

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

## Antigravity

```json
{
    "mcpServers": {
        "EDTMCPServer": {
            "serverUrl": "http://localhost:8765/mcp"
        }
    }
}
```

## Multi-EDT Proxy

The proxy supports [client bearer authentication and separate tokens for each EDT](../proxy/README.md#client-and-backend-tokens).
Network listeners require a proxy token; use HTTPS through a reverse proxy or a VPN to encrypt LAN traffic.
An HTTPS reverse proxy must forward only `/mcp` (and optionally `/health`), never `/admin/` or a catch-all `/` route; see [proxy security](../proxy/README.md#security).

Running more than one EDT instance at once? [`edt-mcp-proxy`](../proxy/) is a standalone router that exposes a single, stable MCP endpoint on `:8764` and forwards each call to the right EDT-MCP instance by `projectName`, discovering live instances in the background. It ships as `edt-mcp-proxy-<version>.jar` alongside the plugin archive in every [release](https://github.com/DitriXNew/EDT-MCP/releases). See [proxy/README.md](../proxy/README.md) for setup, CLI options and configuration.

For duplicate project names, the proxy reports `workspacePath` in `list_projects` and
`router_status`; pass that exact value alongside `projectName` to select the intended
workspace. Backend ports and starting the proxy still require the documented setup;
automatic port fallback and automatic proxy startup are pending the migration and
distribution decisions described in the proxy README.

