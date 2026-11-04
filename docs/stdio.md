# Stdio transport

The plugin includes a Java 17 bootstrap for MCP clients that communicate over stdin/stdout. It attaches to EDT over an authenticated local Unix-domain socket (UDS), with no TCP port or separate download. Existing HTTP clients continue to work.

## Set up a client

Install the plugin and open the intended workspace in EDT once. In **Window > Preferences > MCP Server > General**, clear **Enable MCP over HTTP** to select **Copy stdio .mcp.json**, then click it. This changes the copy mode immediately; Apply saves the switch and stops a running HTTP listener. Copying alone does not save it. On an EDT launched with `-Dedt.mcp.stdioOnly=true`, the button already selects stdio while HTTP is stopped. Merge the copied `mcpServers.edt` entry into your client's configuration. It contains explicit absolute paths for this EDT installation and workspace; the working directory is never used for discovery. The configuration contains no handshake secret or socket address.

The HTTP checkbox is on by default for existing installations. Its **MCP over HTTP** group holds all HTTP settings and controls and is hidden when unchecked. Re-enabling and applying starts HTTP only if **Automatically start** is selected; otherwise use **Start**. Neither action restarts EDT or stops IPC. Restore Defaults re-enables and reveals the group. Plain text mode and all other general preferences stay visible and apply independently of transport.

The single configuration button outside the group chooses stdio when the checkbox is off, or when this EDT was launched with `stdioOnly` and HTTP is not running; it chooses HTTP otherwise. The HTTP entry uses the live port (or pending port when stopped) and the saved token. **Copy URL** remains in the HTTP group. The always-visible **stdio: ready/unavailable** line uses the same IPC state as `get_server_status`; it reports listener availability, not a connected client. HTTP status and the stdio-launch note live inside the group. The status-bar icon considers both listeners, and its tooltip lists each transport.

`EDT_MCP_AUTO_START=true` explicitly overrides the HTTP-enabled and auto-start preferences for CI, including when the HTTP checkbox is off. `stdioOnly` still suppresses HTTP auto-start for that launch; users can start HTTP explicitly from the page.

On Windows the command uses EDT's stock `1cedtc.exe` launcher with `-startup`, an explicit `-vm`, and the EDT configuration area. Java comes from the running EDT installation and must be version 17 or newer. A read-only Program Files installation is supported: all extracted files are per-user. On Linux the copied command uses the running EDT's Java with `-jar`; EDT must already be open. Do not replace the selected Java with a `PATH` lookup.

`initialize` and `tools/list` return immediately from the bundled catalog without opening EDT. The catalog is generated from the same tool schemas as the plugin. Once attached, workspace enablement and disclosure become authoritative; `notifications/tools/list_changed` tells the client to fetch the live catalog.

## Optional host without a window

The copy button always produces the windowed configuration. To request headless startup, add the single argument `"-headless"` to the copied `args` array:

```diff
- "-data", "D:\\EDT-workspaces\\ERP"
+ "-data", "D:\\EDT-workspaces\\ERP", "-headless"
```

Selection is explicit; the bootstrap does not infer it from the desktop, environment or workspace. On a Windows cold start, `-headless` uses the same detached WMI mechanism and workspace startup mutex, launching `1cedtc.exe -application com.ditrix.edt.mcp.server.stdioHost -nosplash -data <workspace>` with `-Dedt.mcp.stdioOnly=true`. This host starts IPC and core EDT services without a workbench or HTTP listener. Linux clients can attach to a host started directly with that application; automatic startup remains Windows-only.

There is still one EDT per workspace. A headless client attaches to an existing windowed EDT and receives its full enabled tool set. A windowed client attaches to an existing headless host and receives that host's restricted set. No client starts a second EDT to change host kind, and no bootstrap closes the existing host. To switch back, remove `-headless`, close the current host normally (or wait for idle shutdown), then retry or open the workspace in EDT.

Before attachment, a headless client's generated catalog excludes `WINDOWED_ONLY` tools. After attachment, the actual host determines the rules. `get_server_status.hostKind` reports `WINDOWED` or `HEADLESS`. The explicit headless host ignores tool disabling, read-only presets and progressive disclosure. Its destructive-consent gate always answers ALLOW, with or without `EDT_MCP_DESTRUCTIVE_CONSENT`; preview/confirm and ordinary tool preconditions still apply. A headless client attached to windowed EDT follows every windowed gate. A windowed EDT without a workbench or shell, including during shutdown, still refuses unattended destructive operations.

The inventory is [HostToolCapabilities.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/HostToolCapabilities.java). Each moved entry records real EDT 2026.2.2 evidence. `HEADLESS_DEGRADED` names a measured limitation or an outstanding runtime prerequisite. `WINDOWED_ONLY` names a mandatory window path. Autosort remains the user's setting; tools never change it.

`get_metadata_objects`, `get_metadata_details` and `list_modules` use core services with BM read transactions. Their unit tests exercise English and Cyrillic programmatic Names, language-code synonyms, and metadata TYPE aliases where accepted by the existing schema. Windowed dispatch and formatting stay the same. Lazy template and XDTO content retains its separate rollback transactions.

BSL AST reads, reference lookup, call hierarchy, native query validation, metadata writes and designer writes run on the headless caller through `ReadToolExecution`. BM reads and writes retain their transaction boundaries. Windowed dispatch keeps SWT `syncExec`. `get_symbol_info` uses its existing EMF fallback without an editor, returning the symbol, signature and parameters instead of editor hover documentation. `read_module_source` retains its optional windowed interception footer.

Native content assist still requires a viewer: the real viewer-free probe reaches `BslProposalProvider.createProposals:623 -> getDocEvent:7056 -> ITextViewer.getTextWidget`. Rendering and editor navigation have separate window requirements; their restrictions are unrelated to consent or tool preferences.

Headless database updates use core synchronization callbacks: confirmed restructuring is accepted, external changes follow `override/import/cancel`, and unknown platform questions fail with an actionable error. They never enter the application-update or restructure dialogs. A bounded caller reports an unknown outcome if the platform does not finish; inspect the database before retrying. Windowed update delegates and dialogs keep their existing behavior.

The stdio drivers share 22 windowed response pins through `tests/e2e/read_host_contracts.py`. With the base and extension fixtures imported, 18 responses match byte for byte. The differences are `symbol-Calc` and `symbol-Вычисление` (EMF summaries, pinned in `stdio-read-headless.json`) and `source-full-Calc` and `source-range-Calc` (the optional interception footer). The Linux driver also checks real content from additional reads and confirmed English/Cyrillic writes by reading the exported files before another MCP call can repair them. Filesystem-wide module listing checks paths separately because traversal order is unspecified.

A headless host exits cleanly after 30 minutes with no connected client and no running MCP tool or tracked background job, stopping project contexts and saving the workspace. Set the host JVM property `-Dedt.mcp.headlessIdleMinutes=<minutes>` to change the interval; `0` disables it. The timer restarts when the last client and last tool finish. A disconnected client's tool retains its lease until the operation actually finishes, even if its response waiter has detached. Windowed EDT continues to stay open until the user closes it.

The owner's ERP measurements motivate this option: about 1.3 GB less RAM and 38% less CPU. The measured opening difference was only 2–3 seconds; this mode does not promise faster opening.

The host and bootstrap have separate standard streams. EDT's log4j console appender writes to the host's stdout, never to the client's JSON-RPC stream. The WMI launch does not relay host output into the bootstrap; direct Linux launches must redirect the host's stdout/stderr to a log file. The bootstrap emits only JSON-RPC frames on stdout and diagnostics on stderr. Host failures retain their exception and stack trace in the EDT workspace log without opening an error dialog.

## First call and reconnect

The first `tools/call` attaches to an existing compatible EDT, waiting up to two seconds for connection setup before dispatching that invocation. It needs no preliminary retry when the connection is ready within that limit. A slow or unavailable connection returns an actionable error without queuing the invocation.

On Windows, when no live endpoint exists, the first call starts a normal EDT window and returns promptly:

> EDT is starting. Retry the same call in about 15 seconds. No tool ran.

Retry explicitly after the interval. A cold workspace may need several attempts while EDT indexes projects or waits for a startup dialog. The startup mutex and durable claim prevent two racing clients from starting two EDTs for one workspace. If automatic startup is unavailable or blocked by Windows policy, ask the user to open that workspace in EDT, then retry.

Closing the client closes its bootstrap and leaves windowed EDT open. A headless host follows the idle policy above. A later client attaches to that EDT. Each connection keeps its own capabilities, request ids and response/notification sinks. A dropped connection never replays a tool invocation. An interrupted call reports an unknown outcome: a write may have committed, so read back its state before deciding whether to retry.

Windows automatic startup uses local WMI `Win32_Process.Create` with `CREATE_BREAKAWAY_FROM_JOB`. EDT runs outside the client's process tree and kill-on-close Job Object. There is no ordinary child-process fallback. This requires WMI access in the interactive user's session. EDT started this way suppresses HTTP auto-start for that launch without changing the saved preference. Attaching to an open EDT leaves its HTTP listener as configured.

## Shipping and private storage

Both bootstrap jars ship inside the plugin and are extracted atomically on plugin activation. The stable entry path is:

```text
%LOCALAPPDATA%\EDT-MCP\bootstrap\<install-path-hash>\edt-mcp-bootstrap.jar
```

On POSIX, the root is `$XDG_RUNTIME_DIR/edt-mcp`, or `$XDG_STATE_HOME/edt-mcp` (default `~/.local/state/edt-mcp`). The entry loads an immutable runtime jar selected by an atomic `current` pointer. Bundle version changes do not alter the client command.

After publishing `current`, extraction removes older runtime files whose names are exactly 64 lowercase hexadecimal characters plus `.jar`. It keeps the current runtime and its immediate predecessor, recorded in `previous`, even when extracting the same build again. This lets an entry that verified the preceding runtime finish opening it during an update; it does not cover an entry paused across multiple runtime updates. Links and reparse points are refused through the private-storage checks. Locked files and other cleanup I/O failures are skipped independently, logged only at debug level, and retried on later extractions. The stable entry jar and other filenames stay. An entry-loader ABI upgrade can require closing clients before extraction succeeds.

The same private root contains `ipc/` with one endpoint record per canonical workspace. Records hold a generation id, PID/start time, plugin/IPC versions, bootstrap discovery path and handshake secret. Never copy registry records into support logs. Directories are owner-only (Windows ACLs or POSIX `0700`); regular IPC files and POSIX sockets use `0600`. Symbolic links and directory reparse points are refused. Stale records are checked against PID and start time; shutdown removes only its own generation. Socket addresses remain inside the private registry directory.

IPC startup failure logs a warning and keeps HTTP available. `get_server_status` reports `transports.http` and `transports.ipc` without revealing the secret or socket path.

The status response always emits `transports`. Its output schema keeps the field optional: only `success` is required at the top level. When `transports` is present, both `http` and `ipc` are required.

## Limits

- Headless support is optional and restricted by the explicit tool inventory; mandatory workbench paths are refused.
- Automatic GUI startup is Windows-only. Linux supports attachment to an already running EDT host; macOS is unverified.
- Windows WMI policy, restricted filesystem tokens, modal startup dialogs or incompatible plugin/IPC versions can prevent readiness.
- UDS paths must fit within 100 UTF-8 bytes; there is no TCP fallback. Use a short private user directory if necessary.
- No transparent resume of interrupted calls. Agents must never automatically replay an uncertain write.
- User-owned settings remain the user's decision. Ask the user using only the EDT UI path supplied by the response; do not change preferences or consent on their behalf.
