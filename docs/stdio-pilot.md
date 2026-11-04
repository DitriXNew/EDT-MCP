# Windowed Windows stdio pilot

The plugin includes a Java 17 bootstrap jar. The client runs it with EDT's stock `1cedtc.exe -startup`; the launcher chooses Java using its normal runtime association, or an explicit `-vm`. The bootstrap checks the running Java against `1cedt.ini`. It never searches PATH or downloads a runtime.

The bootstrap owns UTF-8, newline-delimited JSON-RPC stdin/stdout. It answers `initialize`, `tools/list` and `ping` without starting EDT or opening the endpoint registry. The offline catalog is generated during Maven `process-classes` by executing `BuiltInToolRegistrar` and the same `McpProtocolHandler.toolCatalog` schema rendering used by the live dispatcher. It includes every built-in tool; workspace enablement and disclosure become authoritative after connection. A `notifications/tools/list_changed` notification asks the client to fetch that live catalog.

The first valid `tools/call` starts a background attach/start attempt and promptly returns an error tool result explaining that EDT is connecting/starting and the call has not executed. Retry explicitly. No tool call is queued for later execution. Once connected, calls go to an independent plugin-side Unix-domain socket listener and the shared MCP dispatcher. Each connection retains its own capabilities, request ids and reply/notification sink. The HTTP transport continues to use its existing listener and sessions.

On Windows, the GUI starter uses local WMI `Win32_Process.Create` through Windows PowerShell, with `CREATE_BREAKAWAY_FROM_JOB` in `Win32_ProcessStartup.CreateFlags`. WMI separates it from the client's process tree; the flag also requests separation from WMI provider job limits. It does not inherit the client's stdout. There is no ordinary child-process fallback. **The restricted development sandbox refused WMI access, so GUI lifetime independence remains a release acceptance requirement.** Microsoft's [Job Objects documentation](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects) and [WMI Create reference](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/create-method-in-class-win32-process) describe these distinct job concerns; documentation is not a substitute for testing the actual client and Windows policy.

An EDT launched by this path is intended to open its normal window and stay running after a stdio disconnect. The launch-only JVM property `edt.mcp.stdioOnly=true` suppresses HTTP auto-start for this launch; it does not write the saved HTTP preference. Normally launched EDT keeps its existing HTTP auto-start behavior, and HTTP can still be controlled through the existing preferences. Attaching to an already-open EDT does not change its HTTP listener.

## Client configuration and shipping

Install the plugin and open EDT once. Under **Window > Preferences > MCP Server > General**, click **Copy stdio .mcp.json**. This copies a complete `mcpServers.edt` entry with absolute install, workspace, configuration, launcher, bootstrap and the currently running EDT's Java paths. Carrying the configuration also preserves a per-user p2 installation or an explicitly selected configuration area. Merge it into the client's `.mcp.json`; no new MCP tool or separate download is required. The plugin must already be installed before a cold bootstrap can start an EDT containing the IPC listener.

The embedded jar is extracted atomically to:

```text
%LOCALAPPDATA%\EDT-MCP\bootstrap\<install-path-hash>\edt-mcp-bootstrap.jar
```

This stable entry jar loads a bundled runtime jar named by its SHA-256 from an atomically updated `current` pointer in the same directory. Both jars ship inside the plugin. Old clients keep their original runtime; new clients read the new pointer. Runtime jars are retained, since Windows prevents replacing a loaded jar. The entry has deterministic contents and only needs replacement when its tiny loading ABI changes; such an entry upgrade may require closing stdio clients first.

This path survives bundle-version folder changes. Different installations have different stable entries. Extraction and registry directories have an owner-only ACL. A read-only Program Files installation needs no write. An install-time p2 copy next to the launcher was rejected because updating Program Files would require elevation. A shared EDT configuration path was rejected because it may also be read-only or shared across users; a per-user configuration path would need its own discovery and is less explicit than the selected user directory.

Extraction failure is logged, and configuration export reports it to the user. Do not use a versioned plugin-folder path or weaken an ACL to work around an unsupported restricted-token environment. A real Windows experiment confirmed that replacing a running monolithic bootstrap jar fails with `AccessDeniedException`; the pointer and immutable runtime arrangement addresses that measured limitation.

## Discovery, crashes and retries

The registry contains one atomic JSON record per canonical workspace identity under `%LOCALAPPDATA%\EDT-MCP\ipc`. Records contain the workspace, random generation id and socket name, PID and process start time, plugin and IPC versions, and a random 256-bit handshake secret. Never share registry files or include them in support logs. The UDS preface authenticates that identity before MCP dispatch. Socket paths longer than 100 UTF-8 bytes fail explicitly; there is no TCP fallback.

Publication/removal and the first-call startup mutex use the same per-workspace OS file lock. A durable startup claim prevents another client from launching a second EDT while the first client is gone and EDT is coming up. A fresh claim waits up to 180 seconds; an older claim with a still-live launcher asks the user to inspect the GUI. A locked Eclipse workspace blocks another launch even if IPC is missing. Dead PID/start-time records can be replaced. Shutdown removes only its own generation's record.

A dropped connection never replays an in-flight call. Outstanding requests report an unknown outcome: a write may have committed, so read back before deciding whether to retry. A later explicit call can reconnect to the open EDT or start a replacement after a crash. Transparent recovery of the interrupted call is outside this pilot.

Only windowed Windows EDT is covered. A missing workbench, launcher/IPC incompatibility, blocked WMI, modal startup dialog or restrictive filesystem policy can prevent readiness. If a tool is disabled or another user-owned EDT setting blocks an operation, ask the user and direct them to the EDT UI path in the response. Do not change preferences, consent or autosort settings for them.

The native bootstrap initialize/list/ping path, generated jar contents and targeted transport tests were exercised. The real Claude Code kill-tree/Job Object scenario and full GUI attach/reconnect require the separate acceptance run. This pilot is not yet certified for release.
