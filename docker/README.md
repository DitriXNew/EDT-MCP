# Headless EDT image for CI

`edt-ci.Dockerfile` assembles a **headless 1C:EDT + MCP server** so the
`e2e-tests.yml` and `conformance.yml` workflows have a live `:8765` to test —
without a manual product download and without a permanently-logged-in desktop.

## How it works
- EDT is **installed from its public p2 update-site** (`https://edt.1c.ru/downloads/releases/ruby/2025.2/`) via the Eclipse **`p2 director`** into a base Eclipse Platform 4.30 (2023-12) — the *same* p2 our Tycho build already pulls from, so no 1C account is needed at build time and no product archive is downloaded by hand.
- The runnable IDE is the product IU **`com._1c.g5.v8.dt.rcp`** (verified from the live p2 metadata); `-p2.os linux -p2.ws gtk -p2.arch x86_64` is required so the Linux launcher + `1cedt.ini` materialize.
- EDT is a GTK/SWT GUI app → it runs under **Xvfb** (a virtual display; no monitor/desktop needed). For pure automation, EDT also ships a headless CLI (`1cedtcli`) that needs no display.
- The MCP server is a plugin; it auto-binds `:8765` when the workbench opens. The built bundle jar is dropped into `/opt/edt/dropins/`.

## Build & run
```bash
# 1. build the MCP bundle (produces the jar)
bash source/compile.sh --skip-tests
# 2. build the image (point MCP_BUNDLE at the built jar)
docker build -f docker/edt-ci.Dockerfile \
  --build-arg MCP_BUNDLE=mcp/bundles/com.ditrix.edt.mcp.server/target/com.ditrix.edt.mcp.server-1.0.0-SNAPSHOT.jar \
  -t edt-ci .
# 3. run it (publishes the MCP port)
docker run --rm -p 8765:8765 edt-ci
# 4. once "MCP server UP on 8765" prints, run a gate against it, e.g. conformance:
npx @modelcontextprotocol/conformance@latest server \
  --url http://127.0.0.1:8765/mcp --spec-version 2025-11-25 \
  --expected-failures tests/conformance/baseline.yml
```
For the e2e gate, the workspace must also contain the test project — import
`tests/TestConfiguration` into `$EDT_WORKSPACE` (a follow-up step / layer).
Conformance (protocol only) needs no project.

## ⚠️ Status: UNVERIFIED — needs one real build + boot
The Dockerfile is grounded in the live p2 metadata (IUs, versions, os/ws/arch
filters all read from the repo), but it has **not** been built and booted
end-to-end yet. Before relying on it, confirm on a real machine:
1. `p2 director` resolves the full Eclipse 4.30 closure from the two repos (if it
   reports a missing IU, add that repo to `-repository`, don't blindly chase refs).
2. The exact on-disk launcher name in `/opt/edt` (assumed `1cedt`).
3. The workbench actually starts under Xvfb and the MCP server binds `:8765`.
4. The apt dep list is sufficient (esp. whether `libwebkit2gtk` is strictly needed).

## License note
The EDT IDE is free to download/use (download needs a free 1C account). NOT fully
confirmed: whether `1cedtcli` trips a "special license" for CLI use, and any
operation that launches the **1C:Enterprise platform / an infobase** (debug,
update_database, YAXUnit) needs the **platform** licensed separately. Conformance
and most read e2e don't launch the platform; infobase-dependent e2e do.

## Distribution note
Installing from 1C's official p2 at build time is "install via update-site", not
redistributing the product. If you push a built image to a registry you are
shipping EDT — keep such a registry **private** and check 1C's terms. Building the
image on the runner (or a self-hosted runner with EDT pre-installed) avoids
shipping it at all.
