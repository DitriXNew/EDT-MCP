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
`.github/workflows/conformance.yml` runs this against a live server on manual
dispatch. The MCP server runs **inside EDT**, so the job needs an EDT host —
either a **self-hosted runner with EDT** (like `e2e-tests.yml`) or the **headless
EDT image** in [`docker/`](../../docker/README.md) (EDT assembled from its public
p2 via `p2 director`, run under Xvfb on a stock Linux runner). Until that is wired
up, run the gate locally per the dev loop (see the `edt-mcp-ready-to-deploy` skill).

The repo shows a **MCP Conformance** badge (README) reflecting the latest run.
