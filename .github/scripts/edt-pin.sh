#!/usr/bin/env bash
# edt-pin.sh — pin guard for the 1C:EDT base the CI e2e / conformance gate validates against.
#
# WHY. The public 1C:EDT p2 channel  https://edt.1c.ru/downloads/releases/ruby/<channel>/
# is a SIMPLE p2 repository that is MUTATED IN PLACE on every point-release — there is NO
# immutable per-release URL to pin to, and the PREVIOUS service release's jars are DELETED.
# So the EDT-base GitHub cache, keyed only by the channel URL, silently goes stale: an OLD
# cached base gets overlaid with the NEWLY-served EDT bundles, and the p2 director then cannot
# pick a consistent servlet-api / jetty wiring for dt.html -> activedocument.ui (a felix
# uses-constraint conflict) — the systemic, intermittent e2e / conformance failure.
#
# WHAT. We PIN the EDT build qualifier each channel is expected to serve, DETECT what the
# channel actually serves right now (the com._1c.g5.v8.dt.core version inside content.xml.xz),
# and FAIL LOUDLY when 1C ships a newer build — forcing a conscious re-pin. The detected
# qualifier is echoed on stdout for the cache key, so the base cache tracks the served build
# and can never be stale (a new build => a new key => a fresh, self-consistent base).
#
# WHAT ELSE (added after the 2026-09-18 outage). A p2 repository has TWO indexes that must
# agree: content.xml.xz says which units EXIST, artifacts.xml.xz says which jars are STORED.
# Resolution reads the first and downloads per the second, so when they disagree EVERY EDT
# bundle 404s and Tycho reports it as `bundleLocation can't be null for artifact …` — a message
# that names neither the repository nor the 404. That outage cost ~40 minutes to diagnose and
# was invisible to this guard, which compared the PIN against ONE index and passed green while
# the channel was unbuildable. We now read both and refuse on a disagreement, with the reason
# spelled out. The disagreement need not originate at 1C: a CDN edge serving one index from a
# stale cache while the other is fresh produces exactly the same unbuildable view, and that view
# is what the client actually gets — which is precisely why the check belongs on the client.
#
# USAGE.  edt-pin.sh <channel> <edt-p2-url>
#   <channel>     the ruby/<channel>/ segment, e.g. 2026.2
#   <edt-p2-url>  the full p2 URL (trailing slash), e.g. https://.../ruby/2026.2/
# Prints ONE line on stdout: the build qualifier to fold into the cache key. All human /
# annotation output goes to stderr. Exit 1 on a confirmed drift, an inconsistent channel, or an
# unknown channel.
#
# TO RE-PIN after 1C ships a new build: bump the qualifier in the PIN MAP below to the value
# the failure message reports, then re-run — the base cache refreshes automatically. Re-pinning
# is a ONE-WAY door: the previous service release is deleted from the channel, so from then on
# it can only be validated against a LOCAL installation (source/verify-oldest-platform.sh).

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
  2026.2) EDT_EXPECTED="28.0.0.v202609171902" ;; # 1C:EDT 2026.2.1 — Eclipse 4.38 / Java 25
  2026.1) EDT_EXPECTED="27.0.2.v202607090722" ;; # 1C:EDT 2026.1.2 — Eclipse 4.30 / Java 17
  *)
    log "::error::edt-pin.sh: no pinned EDT build for channel '$CHANNEL'. Add it to the PIN MAP in .github/scripts/edt-pin.sh."
    exit 1
    ;;
esac

# ── DETECT what the channel currently serves ─────────────────────────────────────────
# Both indexes are small (~300-430 KB). A transient network failure here must NOT become a new
# CI flake, so on a fetch/parse failure we WARN and skip only the comparison that needed it —
# never blocking the job on a flake.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Reads EVERY com._1c.g5.v8.dt.core version out of an xz-compressed p2 index at the channel root:
# a repository may list several at once (old and new coexist mid-publish). $1 = index file name,
# $2 = a label for the warning. Prints the versions ascending, one per line, or nothing.
read_versions() {
  local name="$1" label="$2" out=""
  if curl -fsSL --retry 3 --retry-delay 10 "${EDT_P2}${name}" -o "$WORK/$name" 2>/dev/null; then
    out="$(xz -dc "$WORK/$name" 2>/dev/null \
      | grep -oE "id='com\._1c\.g5\.v8\.dt\.core' version='[^']+'" \
      | sed -E "s/.*version='([^']+)'.*/\1/" | sort -uV)"
  fi
  if [ -z "$out" ]; then
    log "::warning::edt-pin.sh: could not read the served EDT build from ${EDT_P2}${name} (${label}; network flake?)."
  fi
  printf '%s' "$out"
}

# The served build is the HIGHEST version the metadata lists - the one p2 resolves.
EDT_ACTUAL="$(read_versions content.xml.xz 'metadata index' | tail -n 1)"
EDT_ARTIFACTS="$(read_versions artifacts.xml.xz 'artifact index')"

# ── GUARD 1: the build the metadata resolves must be STORED ──────────────────────────
# Only decidable when BOTH were read; one missing is a flake, not a verdict. Resolution follows
# content.xml.xz and downloads per artifacts.xml.xz, so a resolved build with no stored jars means
# every EDT bundle 404s. Other stored versions alongside it are harmless.
if [ -n "$EDT_ACTUAL" ] && [ -n "$EDT_ARTIFACTS" ] && ! printf '%s\n' "$EDT_ARTIFACTS" | grep -qxF "$EDT_ACTUAL"; then
  log "::error::EDT $CHANNEL channel is INCONSISTENT: its metadata index (content.xml.xz) resolves $EDT_ACTUAL, but its artifact index (artifacts.xml.xz) stores only: $(printf '%s' "$EDT_ARTIFACTS" | tr '\n' ' '). Resolution follows the metadata and downloads per the artifacts, so every EDT bundle will 404 and Tycho will report it only as \"bundleLocation can't be null for artifact ...\". Nothing in this repository can fix that, and purging the Tycho p2 cache does NOT help - the inconsistent view is upstream (1C mid-publish, or a CDN edge serving one index stale). Re-run once ${EDT_P2}artifacts.xml.xz stores $EDT_ACTUAL."
  exit 1
fi

if [ -z "$EDT_ACTUAL" ]; then
  log "::warning::edt-pin.sh: skipping the drift check; pinning the cache key to $EDT_EXPECTED."
  echo "$EDT_EXPECTED"
  exit 0
fi

log "[edt-pin] channel $CHANNEL serves com._1c.g5.v8.dt.core=$EDT_ACTUAL (pinned expected: $EDT_EXPECTED)"

# ── GUARD 2: fail loudly on a confirmed drift ────────────────────────────────────────
if [ "$EDT_ACTUAL" != "$EDT_EXPECTED" ]; then
  log "::error::EDT $CHANNEL channel now serves $EDT_ACTUAL but CI is pinned to $EDT_EXPECTED. 1C shipped a newer EDT point-release. Review it, bump the '$CHANNEL' qualifier in the PIN MAP in .github/scripts/edt-pin.sh to $EDT_ACTUAL, and re-verify - the EDT-base cache refreshes automatically on the new qualifier. Note that $EDT_EXPECTED is then GONE from the channel: from that point it can only be validated against a local installation (source/verify-oldest-platform.sh)."
  exit 1
fi

log "[edt-pin] OK: channel $CHANNEL matches the pinned EDT build, and the artifact index stores it."
echo "$EDT_ACTUAL"
