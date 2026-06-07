#!/usr/bin/env bash
# Launch headless EDT under Xvfb and wait for the MCP server on :8765.
# The container then stays up as "the live MCP server" for the e2e / conformance
# CI jobs to connect to (run them with the container's :8765 published/reachable).
set -euo pipefail

WS="${EDT_WORKSPACE:-/opt/edt-workspace}"
mkdir -p "$WS"

# The on-disk launcher name after a director install of com._1c.g5.v8.dt.rcp is
# conventionally 1cedt; fall back to the generic eclipse launcher if absent.
LAUNCHER=/opt/edt/1cedt
[ -x "$LAUNCHER" ] || LAUNCHER=/opt/edt/eclipse

echo "[entrypoint] launching EDT ($LAUNCHER) under Xvfb, workspace=$WS ..."
xvfb-run -a -s "-screen 0 1920x1080x24" \
  "$LAUNCHER" -data "$WS" -clean -nosplash --launcher.suppressErrors \
  -vmargs -Xmx4g &

echo "[entrypoint] waiting for MCP server on :8765 ..."
for i in $(seq 1 120); do
  if curl -sf http://127.0.0.1:8765/health >/dev/null 2>&1; then
    echo "[entrypoint] MCP server UP on 8765"
    break
  fi
  sleep 5
done

# Keep the container alive (EDT runs in the background above).
wait
