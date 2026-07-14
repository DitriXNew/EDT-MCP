# EDT MCP Proxy

A standalone MCP proxy/router for [EDT MCP Server](../README.md) (issue
[#253](https://github.com/DitriXNew/EDT-MCP/issues/253)).

When you work on several 1C:EDT instances at once, each instance runs its own EDT-MCP
server on its own port (8765, 8766, ...). Instead of reconfiguring the AI client per
instance, point it at **one** endpoint — the proxy on port **8764** — and the proxy routes
every `tools/call` to the EDT instance that owns the requested project.

The proxy is a plain Java process (no Eclipse, no OSGi). The EDT plugin is **not**
modified in any way: the proxy talks to ordinary EDT-MCP servers over their normal
`/mcp` endpoint using the same MCP Streamable HTTP wire contract.

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
java -jar edt-mcp-proxy.jar serve --bind 0.0.0.0  # explicit remote bind (logs a SECURITY warning)
java -jar edt-mcp-proxy.jar serve --config C:\edt\proxy.properties

java -jar edt-mcp-proxy.jar status                # query a running proxy: live EDT backends,
java -jar edt-mcp-proxy.jar status --port 9000    #   project map, duplicates, scan range, last refresh

java -jar edt-mcp-proxy.jar stop                  # gracefully stop a running proxy
java -jar edt-mcp-proxy.jar --help | --version
```

- **`serve [options]`** — starts the proxy in the foreground (see [Configuration](#configuration)
  for every option). This is also the implicit behaviour of a bare invocation.
- **`status [--port N]`** — a CLI *client*: probes the running proxy's `/health` and, when
  alive, its `router_status` tool (via a minimal MCP handshake), then prints a
  human-readable table of `port | projects`, followed by duplicate projects, the
  scan range and the last refresh time. Exits `0` when the proxy answered, `1` with an
  actionable message when it could not be reached (e.g. nothing is listening on that
  port yet).
- **`stop [--port N]`** — asks the running proxy's admin endpoint (`POST
  /admin/shutdown`, loopback only — see [Security](#security)) to shut down gracefully.
  Exits `0` when accepted, `1` with an actionable message when the proxy could not be
  reached.
- **`--help`** — prints the full usage: every subcommand plus the complete `serve`
  option/environment-variable reference. **`--version`** — prints the proxy's version
  (read from the jar manifest's `Implementation-Version`, falling back to `dev` when
  absent, e.g. running from a development classpath).
- An unrecognised subcommand prints the usage and exits `2`.

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
file with the same five keys as the table above (`port`, `scan`, `refresh`, `timeout`,
`bind`) — no new dependency, no new format to learn:

```properties
# edt-mcp-proxy.properties
port=8764
scan=8765-8780
refresh=30
timeout=300
bind=0.0.0.0
```

Point `serve` at it explicitly with `--config <path>`, or drop a file named
`edt-mcp-proxy.properties` next to the jar (`java -jar` resolves the jar's own
directory) and it is picked up automatically — no flag needed. The auto-discovered
file is entirely optional: if it is not there, the proxy just falls back to
environment variables and defaults. An explicitly named file (`--config`) that
cannot be found or read fails startup immediately, naming the path.

## Security

**The proxy binds loopback (`127.0.0.1`) only by default**, mirroring the EDT-MCP plugin's own
default: the proxy forwards `tools/call` to EDT-MCP backends whose tool surface includes
arbitrary-BSL execution (`evaluate_expression`) and destructive operations, so it must not be
reachable from the network unless you explicitly opt in.

Setting `--bind HOST` (or `EDT_MCP_PROXY_HOST`) - e.g. `--bind 0.0.0.0` to listen on all
interfaces, or a specific interface address - opts into that exposure. Startup logs which mode
is active (`loopback only` vs `remote (<host>)`), and a remote bind also logs a `SECURITY`
warning.

> **⚠️ The proxy has no authentication in v1.** Unlike the plugin (which pairs
> `allowRemote` with an optional auth token), a remotely-bound proxy accepts every MCP request
> from any host that can reach the port - including `tools/call` routed to arbitrary BSL on
> every EDT backend it discovers. Loopback is the security boundary; only bind beyond it on a
> trusted, isolated network (e.g. behind your own reverse proxy that adds authentication).

Two further limits guard against resource exhaustion regardless of the bind address:

- **Request body cap** - a `POST /mcp` body over 4 MiB is rejected with `413` before it is
  fully read, so an oversized or unbounded request cannot exhaust heap.
- **Session cap** - the proxy tracks at most 10,000 concurrently open client sessions;
  `initialize` past that cap is refused with a JSON-RPC error asking idle sessions to be closed
  (`DELETE /mcp`) first.
- **Admin endpoint stays loopback-only, always** - `POST /admin/shutdown` (the `stop`
  subcommand's target) is accepted only when the caller's remote address is loopback,
  checked on the actual TCP peer address of every request - **regardless of `--bind`**.
  Even a proxy bound to `0.0.0.0` cannot be remotely shut down; any other HTTP method or a
  non-loopback caller gets `405`/`403`.

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
     duplicate (see [Limitations](#limitations-v1));
   - **unknown** → the proxy rescans **once** immediately (the hot-plug path: the AI
     agent may have just started that EDT) and retries the lookup; if still unknown it
     returns an actionable error listing the live backends with their projects and
     suggesting a retry in ~30 s or a `router_refresh` call.
4. **No project argument** — forwarded to the first live backend (ascending port
   order). With zero live backends the call errors, naming the scan range.

Routed requests and responses are forwarded **byte-for-byte**: the proxy parses the
request JSON only to pick the route and never rewrites the payload of a routed call.
The proxy maintains its own MCP session with each backend (lazy `initialize`
handshake per backend, transparent re-handshake if a backend restarts), so clients
only ever see the proxy's own `Mcp-Session-Id`.

### Other MCP methods

- `initialize` / `ping` — the proxy answers itself and issues its own session id.
- `tools/list` — taken from the first live backend with the two router tools injected;
  the last successful response is cached for zero-backend service.
- `notifications/*` — accepted with `202`, mirroring the plugin.
- Any other method — forwarded raw to the first live backend.

### Router tools

Two extra tools are injected into `tools/list` (both take no parameters):

- **`router_status`** — returns the proxy port, the live backends with their projects,
  detected duplicate project names, the last refresh timestamp, and the scan range.
- **`router_refresh`** — forces an immediate rescan, then returns the same status
  payload. Call it right after starting a new EDT instance to register it without
  waiting for the periodic refresh.

## Endpoints

| Endpoint               | Behaviour                                                            |
|------------------------|-----------------------------------------------------------------------------|
| `GET /health`          | `200` with `{"status":"ok","role":"proxy","backends":<live count>}`  |
| `POST /mcp`            | MCP Streamable HTTP, same wire contract as the plugin (SSE `data:` frames, `Mcp-Session-Id` header) |
| `DELETE /mcp`          | Closes the client's session **on the proxy**; backend sessions are untouched  |
| `POST /admin/shutdown` | Loopback callers only (regardless of `--bind`, see [Security](#security)): `200 {"status":"stopping"}` then the proxy stops gracefully; non-loopback callers get `403`; any other HTTP method gets `405`. This is the `stop` subcommand's target. |

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

- **Duplicate project names are not auto-resolved.** If two EDT instances both serve a
  project with the same name, a call scoped to that project returns an error naming
  both ports — close one of the instances or address its EDT-MCP port directly.
- **Single-machine discovery only.** Backends are found by a localhost port scan;
  there is no remote-backend support and no config-file backend list.
- **No lifecycle management.** The proxy never starts or stops EDT instances; the AI
  agent (or you) does that. The proxy only discovers what is already running.
- **One tool surface.** Tool names are not prefixed per backend; all backends are
  assumed to expose the same EDT-MCP tool set (`tools/list` is taken from the first
  live backend).

## Development

Sources live under `proxy/src/main/java/com/ditrix/edt/mcp/proxy/`; the only
dependency is Gson. Unit tests and in-process integration tests (fake backends on
ephemeral ports — no EDT required) both run with:

```bash
mvn -f proxy/pom.xml clean verify
```

CI: [`.github/workflows/proxy.yml`](../.github/workflows/proxy.yml) builds and tests
the proxy on every change under `proxy/` and uploads the fat jar as a build artifact.
