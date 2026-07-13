#!/usr/bin/env bash
# edt-pin.sh — pin guard for the 1C:EDT base the CI e2e / conformance gate validates against.
#
# WHY. The public 1C:EDT p2 channel  https://edt.1c.ru/downloads/releases/ruby/<channel>/
# is a SIMPLE p2 repository that is MUTATED IN PLACE on every point-release — there is NO
# immutable per-release URL to pin to. So the EDT-base GitHub cache, keyed only by the channel
# URL, silently goes stale: an OLD cached base gets overlaid with the NEWLY-served EDT bundles,
# and the p2 director then cannot pick a consistent servlet-api / jetty wiring for
# dt.html -> activedocument.ui (a felix uses-constraint conflict) — the systemic, intermittent
# e2e / conformance failure.
#
# WHAT. We PIN the EDT build qualifier each channel is expected to serve, DETECT what the
# channel actually serves right now (the com._1c.g5.v8.dt.core version inside content.xml.xz),
# and FAIL LOUDLY when 1C ships a newer build — forcing a conscious re-pin. The detected
# qualifier is echoed on stdout for the cache key, so the base cache tracks the served build
# and can never be stale (a new build => a new key => a fresh, self-consistent base).
#
# USAGE.  edt-pin.sh <channel> <edt-p2-url>
#   <channel>     the ruby/<channel>/ segment, e.g. 2026.1
#   <edt-p2-url>  the full p2 URL (trailing slash), e.g. https://.../ruby/2026.1/
# Prints ONE line on stdout: the build qualifier to fold into the cache key. All human /
# annotation output goes to stderr. Exit 1 on a confirmed drift or an unknown channel.
#
# TO RE-PIN after 1C ships a new build: bump the qualifier in the PIN MAP below to the value
# the failure message reports, then re-run — the base cache refreshes automatically.

set -uo pipefail

log() { echo "$@" >&2; } # keep stdout clean for the single machine-readable value

CHANNEL="${1:-}"
EDT_P2="${2:-}"
if [ -z "$CHANNEL" ] || [ -z "$EDT_P2" ]; then
  log "::error::edt-pin.sh: usage: edt-pin.sh <channel> <edt-p2-url>"
  exit 1
fi

# ── PIN MAP (single source of truth) ──────────────────────────────────────────────────
# channel -> the com._1c.g5.v8.dt.core build qualifier the CI base is validated against.
case "$CHANNEL" in
  2026.1) EDT_EXPECTED="27.0.2.v202607090722" ;; # 1C:EDT 2026.1.2
  *)
    log "::error::edt-pin.sh: no pinned EDT build for channel '$CHANNEL'. Add it to the PIN MAP in .github/scripts/edt-pin.sh."
    exit 1
    ;;
esac

# ── DETECT the build the channel currently serves ─────────────────────────────────────
# content.xml.xz is small (~270 KB). A transient network failure here must NOT become a new
# CI flake, so on a fetch/parse failure we WARN and fall back to the pinned qualifier for the
# cache key — skipping only the drift comparison, never blocking the job on a flake.
WORK="$(mktemp -d)"
EDT_ACTUAL=""
if curl -fsSL --retry 3 --retry-delay 10 "${EDT_P2}content.xml.xz" -o "$WORK/content.xml.xz" 2>/dev/null; then
  EDT_ACTUAL="$(xz -dc "$WORK/content.xml.xz" 2>/dev/null \
    | grep -oE "id='com\._1c\.g5\.v8\.dt\.core' version='[^']+'" | head -1 \
    | sed -E "s/.*version='([^']+)'.*/\1/")"
fi
rm -rf "$WORK"

if [ -z "$EDT_ACTUAL" ]; then
  log "::warning::edt-pin.sh: could not read the served EDT build from ${EDT_P2}content.xml.xz (network flake?). Skipping the drift check; pinning the cache key to $EDT_EXPECTED."
  echo "$EDT_EXPECTED"
  exit 0
fi

log "[edt-pin] channel $CHANNEL serves com._1c.g5.v8.dt.core=$EDT_ACTUAL (pinned expected: $EDT_EXPECTED)"

# ── GUARD: fail loudly on a confirmed drift ───────────────────────────────────────────
if [ "$EDT_ACTUAL" != "$EDT_EXPECTED" ]; then
  log "::error::EDT $CHANNEL channel now serves $EDT_ACTUAL but CI is pinned to $EDT_EXPECTED. 1C shipped a newer EDT point-release. Review it, bump the '$CHANNEL' qualifier in the PIN MAP in .github/scripts/edt-pin.sh to $EDT_ACTUAL, and re-verify — the EDT-base cache refreshes automatically on the new qualifier."
  exit 1
fi

log "[edt-pin] OK: channel $CHANNEL matches the pinned EDT build."
echo "$EDT_ACTUAL"
