# MCP protocol conformance

This is the **protocol** gate. It checks that the EDT-MCP *server* obeys the MCP
wire spec — initialize handshake, capability/version negotiation, `Mcp-Session-Id`,
`Accept`/`Content-Type`, `isError` semantics, `ping`, SSE streams, DNS-rebinding
protection. It is run by the **official** suite [`modelcontextprotocol/conformance`](https://github.com/modelcontextprotocol/conformance),
which connects to the server as a real MCP client.

## Two gates, do not mix
- **`tests/e2e/`** — the tool **business logic**: on-disk effects, happy/negative
  paths, error quality, anti-cheat. Our value-add; nothing official replaces it.
- **`tests/conformance/`** (this) — **protocol compliance**, validated by the
  official suite. Different layer, authoritative external tool.

## Run it (needs a live server on :8765)
```
npx @modelcontextprotocol/conformance@latest server \
  --url http://127.0.0.1:8765/mcp \
  --spec-version 2025-11-25 \
  --expected-failures tests/conformance/baseline.yml
```
With the baseline, the run is GREEN as long as only the pinned (intentional)
gaps fail. A failure of any scenario **not** in `baseline.yml` is a real protocol
regression → fix the server. If a pinned scenario starts passing, drop it from
the baseline.

- List scenarios: `npx @modelcontextprotocol/conformance@latest list --server`
- Verbose (JSON): add `--verbose`.

## Baseline (`baseline.yml`)
Captured 2026-06-07: **8 passed / 24 failed**, the 24 all intentional.
- **Passing (8):** server-initialize, ping, tools-list, server-sse-multiple-streams (2), resources-list, dns-rebinding-protection (2).
- **Pinned failures (24):** the `tools-call-*` conformance-fixture-tool scenarios (a production server doesn't ship test fixtures) + unadvertised capabilities (logging, completion, elicitation, resources-read, prompts). See the comments in `baseline.yml`.

One real bug was found and fixed during the first run: `ping` returned
`-32601 Method not found` (it MUST return `{}`) — fixed in commit `6935ee9`.

## CI
`.github/workflows/conformance.yml` runs this on **stock GitHub-hosted runners**
(`ubuntu-latest`) — no docker image, no self-hosted runner. A `build` job builds
the plugin once; a `conformance` job then runs against **EDT 2026.1** (currently
build 2026.1.2), and the [`setup-edt`](../../.github/actions/setup-edt/action.yml)
composite action materializes Eclipse + 1C:EDT of that version (from the public p2
via `p2 director`) + the built plugin and boots EDT headless under Xvfb, so the
conformance client can hit the local `:8765`. Protocol-only conformance needs no
EDT project and no 1C platform license, so it runs unattended in the cloud.

The plugin is compiled against the 2026.1 target, so this runs the plugin on the
same EDT version it is built against. The gate stays parameterized by EDT version
(a thin `conformance-2026.1.yml` caller) so a future matrix is a one-line add.

The headless-EDT boot is new — the first real CI run validates it end-to-end (the
job uploads the EDT log as an artifact for diagnosis). Until it's confirmed green,
also run the gate locally per the dev loop (see the `edt-mcp-ready-to-deploy` skill).

The repo shows a **MCP Conformance** badge (README) reflecting the latest run.
