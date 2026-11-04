# Stdio transport

The plugin includes a Java 17 bootstrap for MCP clients that communicate over stdin/stdout. It attaches to windowed EDT over an authenticated local Unix-domain socket (UDS), with no TCP port or separate download. Existing HTTP clients continue to work.

## Set up a client

Install the plugin and open the intended workspace in EDT once. In **Window > Preferences > MCP Server > General**, click **Copy stdio .mcp.json**. Merge the copied `mcpServers.edt` entry into your client's configuration. It contains explicit absolute paths for this EDT installation and workspace; the working directory is never used for discovery. The configuration contains no handshake secret or socket address.

On Windows the command uses EDT's stock `1cedtc.exe` launcher with `-startup`, an explicit `-vm`, and the EDT configuration area. Java comes from the running EDT installation and must be version 17 or newer. A read-only Program Files installation is supported: all extracted files are per-user. On Linux the copied command uses the running EDT's Java with `-jar`; EDT must already be open. Do not replace the selected Java with a `PATH` lookup.

`initialize` and `tools/list` return immediately from the bundled catalog without opening EDT. The catalog is generated from the same tool schemas as the plugin. Once attached, workspace enablement and disclosure become authoritative; `notifications/tools/list_changed` tells the client to fetch the live catalog.

## First call and reconnect

The first `tools/call` attaches to an existing compatible EDT, waiting up to two seconds for connection setup before dispatching that invocation. It needs no preliminary retry when the connection is ready within that limit. A slow or unavailable connection returns an actionable error without queuing the invocation.

On Windows, when no live endpoint exists, the first call starts a normal EDT window and returns promptly:

> EDT is starting. Retry the same call in about 15 seconds. No tool ran.

Retry explicitly after the interval. A cold workspace may need several attempts while EDT indexes projects or waits for a startup dialog. The startup mutex and durable claim prevent two racing clients from starting two EDTs for one workspace. If automatic startup is unavailable or blocked by Windows policy, ask the user to open that workspace in EDT, then retry.

Closing the client closes its bootstrap and leaves EDT open. A later client attaches to that EDT. Each connection keeps its own capabilities, request ids and response/notification sinks. A dropped connection never replays a tool invocation. An interrupted call reports an unknown outcome: a write may have committed, so read back its state before deciding whether to retry.

Windows automatic startup uses local WMI `Win32_Process.Create` with `CREATE_BREAKAWAY_FROM_JOB`. EDT runs outside the client's process tree and kill-on-close Job Object. There is no ordinary child-process fallback. This requires WMI access in the interactive user's session. EDT started this way suppresses HTTP auto-start for that launch without changing the saved preference. Attaching to an open EDT leaves its HTTP listener as configured.

## Shipping and private storage

Both bootstrap jars ship inside the plugin and are extracted atomically on plugin activation. The stable entry path is:

```text
%LOCALAPPDATA%\EDT-MCP\bootstrap\<install-path-hash>\edt-mcp-bootstrap.jar
```

On POSIX, the root is `$XDG_RUNTIME_DIR/edt-mcp`, or `$XDG_STATE_HOME/edt-mcp` (default `~/.local/state/edt-mcp`). The entry loads an immutable runtime jar selected by an atomic `current` pointer. Bundle version changes do not alter the client command. Existing clients retain their runtime until they close; old runtime jars are retained because Windows locks loaded jars. An entry-loader ABI upgrade can require closing clients before extraction succeeds.

The same private root contains `ipc/` with one endpoint record per canonical workspace. Records hold a generation id, PID/start time, plugin/IPC versions, bootstrap discovery path and handshake secret. Never copy registry records into support logs. Directories are owner-only (Windows ACLs or POSIX `0700`); regular IPC files and POSIX sockets use `0600`. Symbolic links and directory reparse points are refused. Stale records are checked against PID and start time; shutdown removes only its own generation. Socket addresses remain inside the private registry directory.

IPC startup failure logs a warning and keeps HTTP available. `get_server_status` reports `transports.http` and `transports.ipc` without revealing the secret or socket path.

## Limits

- Windowed EDT only. Headless execution and editor-free tools are outside this transport's scope.
- Automatic GUI startup is Windows-only. Linux supports attachment to an already open EDT; macOS is unverified.
- Windows WMI policy, restricted filesystem tokens, modal startup dialogs or incompatible plugin/IPC versions can prevent readiness.
- UDS paths must fit within 100 UTF-8 bytes; there is no TCP fallback. Use a short private user directory if necessary.
- No transparent resume of interrupted calls. Agents must never automatically replay an uncertain write.
- User-owned settings remain the user's decision. Ask the user using only the EDT UI path supplied by the response; do not change preferences or consent on their behalf.
