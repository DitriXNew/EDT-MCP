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

## Configuration

| CLI flag         | Environment variable    | Default     | Meaning                                    |
|------------------|-------------------------|-------------|--------------------------------------------|
| `--port N`       | `EDT_MCP_PROXY_PORT`    | `8764`      | Port the proxy listens on                  |
| `--scan FROM-TO` | `EDT_MCP_PROXY_SCAN`    | `8765-8774` | Backend port scan range (inclusive)        |
| `--refresh N`    | `EDT_MCP_PROXY_REFRESH` | `20`        | Periodic registry refresh interval, seconds |
| `--timeout N`    | `EDT_MCP_PROXY_TIMEOUT` | `300`       | Timeout per forwarded call, seconds        |
| `--bind HOST`    | `EDT_MCP_PROXY_HOST`    | *(unset)*   | Bind `HOST` instead of loopback only (e.g. `0.0.0.0`) - see [Security](#security) |

Precedence: **CLI flags win over environment variables win over defaults.**
`--help` prints usage and exits. An invalid value fails startup with an actionable
error message.

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

| Endpoint      | Behaviour                                                                     |
|---------------|-------------------------------------------------------------------------------|
| `GET /health` | `200` with `{"status":"ok","role":"proxy","backends":<live count>}`           |
| `POST /mcp`   | MCP Streamable HTTP, same wire contract as the plugin (SSE `data:` frames, `Mcp-Session-Id` header) |
| `DELETE /mcp` | Closes the client's session **on the proxy**; backend sessions are untouched  |

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

## Running as a service

### Linux (systemd)

`/etc/systemd/system/edt-mcp-proxy.service`:

```ini
[Unit]
Description=EDT MCP Proxy (router for EDT-MCP instances)
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/edt-mcp-proxy/edt-mcp-proxy.jar --port 8764 --scan 8765-8774
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now edt-mcp-proxy
```

> Tip: since the proxy scans localhost, run it as the same user that runs EDT (or as a
> user service via `systemctl --user`) so it lives and dies with your desktop session.

### Windows (Task Scheduler)

Create a per-user task (no admin rights needed): **Task Scheduler → Create Task…** →
trigger **At log on** → action **Start a program** with

```
Program:   C:\Path\To\jdk-17\bin\javaw.exe
Arguments: -jar C:\Path\To\edt-mcp-proxy.jar --port 8764 --scan 8765-8774
```

Use `javaw.exe` (not `java.exe`) to avoid a lingering console window. Alternatively,
drop a shortcut with the same command line into `shell:startup`.

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
