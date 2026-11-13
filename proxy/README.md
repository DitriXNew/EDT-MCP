# EDT MCP Proxy

A standalone MCP proxy/router for [EDT MCP Server](../README.md) (issue
[#253](https://github.com/DitriXNew/EDT-MCP/issues/253)).

When you work on several 1C:EDT instances at once, each instance runs its own EDT-MCP
server on its own port (8765, 8766, ...). Instead of reconfiguring the AI client per
instance, point it at **one** endpoint — the proxy on port **8764** — and the proxy routes
every `tools/call` to the EDT instance that owns the requested project.

The proxy is a plain Java process (no Eclipse, no OSGi). It talks to EDT-MCP servers
over their normal `/mcp` endpoint using the same MCP Streamable HTTP wire contract.
Current plugins report their workspace identity in `list_projects(format=json)`;
older plugins remain routable by unique project names.

Workspace routing and hot-plug are supported. Backend ports and proxy startup still
use the configuration below. Automatic port fallback and plugin-managed proxy
startup await an owner decision on migrating existing direct `:8765` clients and
installing/updating the separate proxy process with the p2 plugin. This release does
not yet provide the complete zero-configuration workflow proposed in #468.

## Quick start

Build (requires JDK 17+ and Maven):

```bash
mvn -f proxy/pom.xml clean verify
```

This produces a self-contained fat jar `proxy/target/edt-mcp-proxy-0.1.0-SNAPSHOT.jar`.

Run:

```bash
java -jar edt-mcp-proxy.jar --port 8764 --scan 8765-8774
```

On startup the proxy logs one line to stdout:

```
edt-mcp-proxy listening on :8764, scanning 8765-8774
```

Then connect your MCP client (Claude, Copilot, Cursor, ...) to
`http://127.0.0.1:8764/mcp` — exactly as you would connect it to a single EDT-MCP
server, just a different port.

## CLI subcommands

Allure-style subcommands (a bare invocation with no subcommand — options only, or
none at all — is an alias for `serve`, for backward compatibility with the CLI before
subcommands existed):

```bash
java -jar edt-mcp-proxy.jar serve                 # server on :8764, scan 8765-8774 (foreground)
java -jar edt-mcp-proxy.jar serve --port 9000 --scan 8765-8780 --refresh 30 --timeout 300
java -jar edt-mcp-proxy.jar serve --bind 0.0.0.0  # requires configured client authentication
java -jar edt-mcp-proxy.jar serve --config C:\edt\proxy.properties

java -jar edt-mcp-proxy.jar status                # query a running proxy: live EDT backends,
java -jar edt-mcp-proxy.jar status --port 9000    #   project map, duplicates, scan range, last refresh

java -jar edt-mcp-proxy.jar stop                  # gracefully stop a running proxy
java -jar edt-mcp-proxy.jar --help | --version
```

- **`serve [options]`** — starts the proxy in the foreground (see [Configuration](#configuration)
  for every option). This is also the implicit behaviour of a bare invocation.
- **`status [--port N] [--config PATH]`** — a CLI *client*: probes the running proxy's `/health` and, when
  alive, its `router_status` tool (via a minimal MCP handshake), then prints a
  human-readable table of `port | projects`, followed by duplicate projects, the
  scan range and the last refresh time. Exits `0` when the proxy answered, `1` with an
  actionable message when it could not be reached (e.g. nothing is listening on that
  port yet).
- **`stop [--port N] [--config PATH]`** — asks the running proxy's admin endpoint (`POST
  /admin/shutdown`, loopback only — see [Security](#security)) to shut down gracefully.
  Exits `0` when accepted, `1` with an actionable message when the proxy could not be
  reached.
- **`--help`** — prints the full usage: every subcommand plus the complete `serve`
  option/environment-variable reference. **`--version`** — prints the proxy's version
  (read from the jar manifest's `Implementation-Version`, falling back to `dev` when
  absent, e.g. running from a development classpath).
- An unrecognised subcommand prints the usage and exits `2`.

`status` and `stop` resolve the port and proxy token from the same config file and
environment as `serve`; `--port` overrides the configured port. They do not need the
backend tokens or the environment variables containing those tokens. `status` closes
its temporary MCP session after reading the result.

## Configuration

| CLI flag         | Environment variable    | Config file key | Default     | Meaning                                    |
|------------------|-------------------------|------------------|-------------|--------------------------------------------|
| `--port N`       | `EDT_MCP_PROXY_PORT`    | `port`           | `8764`      | Port the proxy listens on                  |
| `--scan FROM-TO` | `EDT_MCP_PROXY_SCAN`    | `scan`           | `8765-8774` | Backend port scan range (inclusive)        |
| `--refresh N`    | `EDT_MCP_PROXY_REFRESH` | `refresh`        | `20`        | Periodic registry refresh interval, seconds |
| `--timeout N`    | `EDT_MCP_PROXY_TIMEOUT` | `timeout`        | `300`       | Timeout per forwarded call, seconds        |
| `--bind HOST`    | `EDT_MCP_PROXY_HOST`    | `bind`           | *(unset)*   | Bind `HOST` instead of loopback only (e.g. `0.0.0.0`) - see [Security](#security) |

Precedence: **CLI flags &gt; environment variables &gt; config file &gt; defaults.**
`--help` prints usage and exits. An invalid value fails startup with an actionable
error message that names the source it came from (a CLI flag, an environment
variable, or `config file <name>: <key>`).

### Config file

A plain [`java.util.Properties`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Properties.html)
file with the listener keys above and optional authentication keys:

```properties
# edt-mcp-proxy.properties
port=8764
scan=8765-8780
refresh=30
timeout=300
bind=0.0.0.0
authTokenEnv=PROXY_CLIENT_TOKEN
```

Point `serve` at it explicitly with `--config <path>`, or drop a file named
`edt-mcp-proxy.properties` next to the jar (`java -jar` resolves the jar's own
directory) and it is picked up automatically — no flag needed. The auto-discovered
file is entirely optional: if it is not there, the proxy just falls back to
environment variables and defaults. An explicitly named file (`--config`) that
cannot be found or read fails startup immediately, naming the path.

### Client and backend tokens

These are **independent credentials**:

- `authToken` or `authTokenEnv`: the token clients present to the proxy. The latter
  contains an environment variable **name**, not the token itself.
- `backend.<port>.token` or `backend.<port>.tokenEnv`: the token the proxy presents
  to the EDT instance on that local port. Each EDT can have a different token.
  Omit the entry only for an EDT that does not require authentication.
- `EDT_MCP_PROXY_AUTH_TOKEN`: an environment override for `authToken`/`authTokenEnv`.
  It is also used by `status` and `stop`.

Configure only one source per token. A configured empty token, missing environment
variable, malformed bearer value, or invalid backend credential key fails startup;
it never silently disables authentication. Proxy client tokens are ASCII bearer values
without whitespace (up to 4096 characters). Backend tokens follow EDT's normalization:
surrounding whitespace is trimmed; all printable ASCII punctuation (for example `@`
and `!`) and internal spaces are accepted. The normalized value must be non-empty and
at most 4096 characters. Non-ASCII and internal tab/control characters are rejected
because Java HttpClient serializes HTTP/1.1 headers as ASCII (replacing Latin-1 with
`?`) and the HTTP server rewrites tabs to spaces. These transport limits are narrower
than EDT's token validator: a token usable with another client may need to be changed
before using this proxy. Unsupported values are rejected at startup without exposing
the token. Use independently generated tokens with at least
32 random bytes for the proxy and each EDT. There are no CLI options containing tokens,
so they need not appear in process arguments or shell history.

Example configuration for two authenticated EDT instances:

```properties
port=8764
scan=8765-8774
bind=0.0.0.0
authTokenEnv=PROXY_CLIENT_TOKEN
backend.8765.tokenEnv=EDT_FIRST_TOKEN
backend.8766.tokenEnv=EDT_SECOND_TOKEN
```

Set those environment variables in the account/environment that starts the proxy,
then run `java -jar edt-mcp-proxy.jar serve --config <path>`. Literal `authToken`
and `backend.<port>.token` values in an access-restricted properties file are also
supported. Keep that file outside source control and shared folders. The checked-in
[example](edt-mcp-proxy.example.properties) contains variable names only.

A LAN client connects to `http://<proxy-host>:8764/mcp` and includes this header on
**every** request, including initialize, notifications and session deletion:

```http
Authorization: Bearer <proxy-token>
```

When migrating a client from a direct EDT connection on `:8765` to the proxy on
`:8764`, change **both the URL and the client token**. Use `authToken` (or the value
referenced by `authTokenEnv`), not `backend.8765.token`. A valid EDT token is not
automatically a valid proxy token; presenting it to the proxy returns `401` when
the configured credentials differ. Restart or reconnect the client after saving
its settings so existing sessions use the new endpoint and header.

All clients holding that token can access all discovered EDT tools. This is a shared
credential, not per-client accounts or project ACLs. Backend tokens are never sent
to clients. The client's Authorization header is never forwarded to an EDT; only
that port's configured token is used for discovery, handshake, retries and calls.
Unconfigured ports receive no token. `/health` probes receive no token either.

Tokens are loaded once at startup. To rotate a token or change an EDT's port, update
the configuration/environment and restart the proxy; update clients when rotating
the proxy token. A token rejected by an EDT yields an explicit authentication error
and `authenticationError` in that backend's `router_status` entry. If some selected
EDTs reject authentication, `list_projects` returns the available projects with
warnings identifying the rejected ports. JSON results include a `warnings` array
(also when JSON is delivered as plain text); Markdown begins with a partial-result
warning. A selected EDT that returns an empty project list still counts as a successful
response. If no usable project list is available, the call remains an error with the
authentication diagnostics. Other EDT instances remain directly routable through the
proxy; select an accessible `workspacePath` to list only that workspace.

## Security

**The proxy binds loopback (`127.0.0.1`) only by default**, mirroring the EDT-MCP plugin's own
default: the proxy forwards `tools/call` to EDT-MCP backends whose tool surface includes
arbitrary-BSL execution (`evaluate_expression`) and destructive operations, so it must not be
reachable from the network unless you explicitly opt in.

Setting `--bind HOST` (or `EDT_MCP_PROXY_HOST`) - e.g. `--bind 0.0.0.0` to listen on all
interfaces, or a specific interface address - opts into that exposure. A resolved
non-loopback address **requires a proxy client token**; startup refuses to open the
listener otherwise. Configuring any backend token also requires client authentication,
even on loopback, so protected EDT tools cannot be exposed through an unauthenticated
local proxy. A token-free loopback proxy remains compatible with token-free EDTs.

Authentication is checked before reading a request body or looking up its session,
on every `/mcp` method. Missing, wrong or duplicate Authorization headers return
`401` with a Bearer challenge. Token digests are compared in constant time. The
public `/health` endpoint reports only liveness, the proxy role and backend count.

**Bearer authentication does not encrypt HTTP.** For network traffic, use a TLS
reverse proxy or VPN, restrict the firewall to intended clients, and keep EDT ports
bound to loopback. When using a TLS reverse proxy, forward Authorization unchanged
and restrict the direct HTTP listener to that reverse proxy. **Allow only the exact
`/mcp` path (and optionally `/health`) through the reverse proxy. Do not forward
`/admin/` or any other path; do not proxy `/` as a catch-all.** The TCP peer seen by
this service is the reverse proxy, so a loopback upstream does not prove that the
original client is local. Native TLS and OAuth are not provided by this module.

Two further limits guard against resource exhaustion regardless of the bind address:

- **Request body cap** - a `POST /mcp` body over 4 MiB is rejected with `413` before it is
  fully read, so an oversized or unbounded request cannot exhaust heap.
- **Session cap** - the proxy tracks at most 10,000 concurrently open client sessions;
  `initialize` past that cap is refused with a JSON-RPC error asking idle sessions to be closed
  (`DELETE /mcp`) first.
- **Admin endpoint checks the immediate TCP peer** - `POST /admin/shutdown` (the `stop`
  subcommand's target) requires a loopback peer and the proxy token when configured.
  Direct non-loopback callers get `403`, and other HTTP methods get `405`.
  A reverse proxy connected through loopback satisfies that peer check: it **must
  exclude `/admin/` from forwarding**, even for clients with a valid MCP token.

The `status` and `stop` commands use the configured `bind` address, with the same
CLI (`--bind HOST`) > environment (`EDT_MCP_PROXY_HOST`) > file precedence as `serve`.
When no bind is specified, both the server and CLI follow the JVM's loopback-address
policy, including `java.net.preferIPv6Addresses`.
Wildcard IPv4/IPv6 binds use loopback in their own address family; concrete addresses
are used directly. All status requests, including session cleanup, use that one
resolved destination without HTTP redirects or an HTTP proxy. For a concrete
non-loopback bind, `stop` refuses before sending a request: use the service manager
or server console instead. No fallback to an unrelated loopback listener is made.
Backend HTTP connections also go directly to the configured local EDT ports, without
following redirects or using the JVM's default HTTP proxy selector.

## How routing works

### Discovery

On startup, and then every `--refresh` seconds, the proxy scans localhost ports in the
`--scan` range, probes `GET /health` on each, and calls `list_projects` on every live
backend to build the *project → backend* routing table. A backend whose `list_projects`
fails stays registered with an empty project set (it still serves unscoped calls and
fan-out).

### Routing rules for `tools/call`

1. **`router_status` / `router_refresh`** — answered by the proxy itself (see below).
2. **`list_projects`** — fanned out to **all** live backends; the project arrays are
   merged into a single response (ordered by backend port).
3. **Project-scoped call** — if the tool arguments contain `projectName` (or `project`),
   the call is routed to the backend that owns that project:
   - owned by exactly one backend → forwarded there;
   - owned by **more than one** backend → error naming the ports that hold the
     duplicate and their `workspacePath` values; pass the chosen workspace explicitly;
   - **unknown** → the proxy rescans **once** immediately (the hot-plug path: the AI
     agent may have just started that EDT) and retries the lookup; if still unknown it
     returns an actionable error listing the live backends with their projects and
     suggesting a retry in ~30 s or a `router_refresh` call.
4. **`workspacePath` supplied** — restricts routing to that exact workspace, with or
   without `projectName`. A missing workspace triggers one immediate rescan, then an
   actionable error. An invalid or blank selector is refused. A selector never falls
   back to another workspace. `list_projects` with this selector lists only that workspace.
5. **No project or workspace argument** — forwarded to the first live backend (ascending port
   order). With zero live backends the call errors, naming the scan range.

Routed responses are forwarded **byte-for-byte** (except the existing structured-content
opt-out). Requests without `workspacePath` are also unchanged; the proxy consumes that
selector before sending a selected request to the backend. It verifies the workspace
in the same backend MCP session before forwarding, including after a stale-session
retry, so a replacement EDT on the same port cannot receive a call for another workspace.
The proxy maintains its own MCP session with each backend (lazy `initialize`
handshake per backend, transparent re-handshake if a backend restarts), so clients
only ever see the proxy's own `Mcp-Session-Id`.

### Duplicate project names

`list_projects` shows a **Workspace** column, and each project in its JSON response
has `workspacePath` when the backend reports it. `router_status.backends` also carries
the path, including for empty workspaces. Project `path` continues to mean the project
location, which may be outside the workspace.

The proxy adds an optional `workspacePath` string to backend tool schemas in
`tools/list`. Copy the path exactly from discovery; no path normalization or directory
name guessing is applied. For example, these are distinct calls through the same proxy:

```json
{"name":"get_configuration_properties","arguments":{"projectName":"ERP","workspacePath":"C:\\Work\\ERP-task-1"}}
{"name":"get_configuration_properties","arguments":{"projectName":"ERP","workspacePath":"C:\\Work\\ERP-task-2"}}
```

If both copies report the same workspace, routing remains ambiguous and the proxy
refuses the call. If an older plugin does not report `workspacePath`, update that
plugin before selecting it this way. A unique project name continues to work without
the additional argument.

### Other MCP methods

- `initialize` / `ping` — the proxy answers itself and issues its own session id.
- `tools/list` — prefers an authenticated backend supporting the machine project-list
  contract, with the two router tools injected. A rejected/unreachable donor is skipped;
  the next eligible backend is tried, then the last cached descriptors. With no cache
  and no eligible donor, authentication failures remain explicit in the response.
- `notifications/*` — accepted with `202`, mirroring the plugin.
- Any other method — forwarded raw to the first live backend.

### Router tools

Two extra tools are injected into `tools/list` (both take no parameters):

- **`router_status`** — returns the proxy port, the live backends with their workspace paths and projects,
  detected duplicate project names, the last refresh timestamp, and the scan range.
- **`router_refresh`** — forces an immediate rescan, then returns the same status
  payload. Call it right after starting a new EDT instance to register it without
  waiting for the periodic refresh.

## Endpoints

| Endpoint               | Behaviour                                                            |
|------------------------|-----------------------------------------------------------------------------|
| `GET /health`          | `200` with `{"status":"ok","role":"proxy","backends":<live count>}`  |
| `POST /mcp`            | Requires the proxy token when configured; MCP Streamable HTTP with SSE `data:` frames and `Mcp-Session-Id` |
| `DELETE /mcp`          | Requires the same authentication as POST; closes the client's session **on the proxy**; backend sessions are untouched |
| `POST /admin/shutdown` | Requires the proxy token when configured and an immediate loopback TCP peer: `200 {"status":"stopping"}` then graceful shutdown; authenticated non-loopback peers get `403`; other authenticated HTTP methods get `405`. Reverse proxies must never forward this path. This is the `stop` subcommand's target. |

## Zero-backend behaviour

The proxy **stays alive with zero backends** — it never exits just because no EDT is
running:

- `initialize` and `ping` keep working (the proxy answers them itself);
- `tools/list` serves the last cached backend list (with the router tools injected)
  or, with no cache yet, a minimal list containing only `router_status` and
  `router_refresh`;
- `tools/call list_projects` returns a `"No running EDT backends"` JSON-RPC error;
- `router_status` reports 0 backends.

As soon as an EDT instance comes up in the scan range it is picked up by the periodic
refresh, by an explicit `router_refresh`, or by the on-miss rescan triggered by the
next project-scoped call.

If a backend dies mid-call, the proxy refreshes the registry and returns an error of
the form `backend at :<port> stopped responding; refreshed registry; retry` — simply
retry the call.

## Autostart

**Deliberately per-user, never a system-wide Windows service or a system `systemd`
unit:** a system-wide service runs as a single OS account for *everyone*, but the
proxy scans *that account's own* localhost ports for EDT instances — on a shared
host (e.g. an RDP terminal server with several developers logged in at once), each
user needs their *own* proxy bound to their *own* session, not one shared instance
fighting over port 8764. Both recipes below start the proxy under the invoking
user's own account, with no administrator/root privileges required.

### Windows (Task Scheduler, per-user)

Register a logon task for the *current* user (no admin rights needed) with
`schtasks`:

```powershell
schtasks /Create /SC ONLOGON /TN edt-mcp-proxy /RL LIMITED /TR "\"C:\Path\To\jdk-17\bin\javaw.exe\" -jar \"C:\Path\To\edt-mcp-proxy.jar\" serve --port 8764 --scan 8765-8774"
```

- `/SC ONLOGON` runs it every time this user logs on; `/RL LIMITED` runs it with
  the user's normal (non-elevated) rights, matching the proxy's own
  loopback-by-default posture.
- Use `javaw.exe` (not `java.exe`) so no console window lingers.
- Remove it with `schtasks /Delete /TN edt-mcp-proxy /F`.
- Equivalently, via the GUI: **Task Scheduler → Create Task…** → trigger **At log
  on** (current user) → action **Start a program** with `Program: javaw.exe`,
  `Arguments: -jar C:\Path\To\edt-mcp-proxy.jar serve --port 8764 --scan 8765-8774`.
- To stop the running instance from a script (e.g. before an update), use the CLI
  itself: `java -jar edt-mcp-proxy.jar stop`.

### Linux (`systemd --user`)

`~/.config/systemd/user/edt-mcp-proxy.service`:

```ini
[Unit]
Description=EDT MCP Proxy (router for EDT-MCP instances)
After=default.target

[Service]
ExecStart=/usr/bin/java -jar %h/edt-mcp-proxy/edt-mcp-proxy.jar serve --port 8764 --scan 8765-8774
ExecStop=/usr/bin/java -jar %h/edt-mcp-proxy/edt-mcp-proxy.jar stop
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now edt-mcp-proxy
```

No `sudo` anywhere: `systemctl --user` manages units under the invoking user's own
session manager, so the proxy starts/stops with that user's login session — exactly
the per-user model the Windows recipe above gives you too. (If your distribution
does not start user services without an active login, enable lingering once with
`loginctl enable-linger $USER`.)

## Limitations (v1)

- **Duplicate project names require an explicit workspace.** The proxy refuses an
  ambiguous call and reports the available paths; the agent must ask which workspace
  the user intends. It never picks a duplicate by port order.
- **Single-machine discovery only.** Backends are found by a localhost port scan;
  there is no remote-backend support and no config-file backend list.
- **No lifecycle management.** The proxy never starts or stops EDT instances; the AI
  agent (or you) does that. The proxy only discovers what is already running.
- **One tool surface.** Tool names are not prefixed per backend; all backends are
  assumed to expose the same EDT-MCP tool set (`tools/list` uses an eligible donor,
  preferring one that supports the machine project-list contract).

## Development

Sources live under `proxy/src/main/java/com/ditrix/edt/mcp/proxy/`; the only
dependency is Gson. Unit tests and in-process integration tests (fake backends on
ephemeral ports — no EDT required) both run with:

```bash
mvn -f proxy/pom.xml clean verify
```

CI: [`.github/workflows/proxy.yml`](../.github/workflows/proxy.yml) builds and tests
the proxy on every change under `proxy/` and uploads the fat jar as a build artifact.
