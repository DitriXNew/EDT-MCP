#!/usr/bin/env bash
# Retry Maven builds only when every [ERROR] line is a recognised transient p2
# repository/network diagnostic. Enumerating every possible real build failure
# cannot be completed, so an unknown error must fail rather than risk being masked
# by a retry that turns a red build green.

set -euo pipefail

log="${RUNNER_TEMP:-/tmp}/mvn.log"
sleep_seconds="${MVN_RETRY_SLEEP_SECONDS:-30}"

# Transient p2/network diagnostics and Maven trailer noise allowed on [ERROR] lines.
# Trailer entries are anchored to the [ERROR] line start so their text cannot
# launder a real failure message that merely contains the same words.
# Extend this allowlist when another unambiguously retryable diagnostic is identified.
transient_error_allowlist='Could not mirror artifact|Unable to read repository|Connection reset|HTTP code: 50[0-9]|Return code is: 50[0-9]|Could not resolve target platform specification|Failed to resolve target definition|Cannot resolve target definition|No repository found at|Failed to load p2 repository|Read timed out|Connection timed out|UnknownHostException|^[[:space:]]*\[ERROR\][[:space:]]+(re-run Maven|For more information about the errors|After correcting the problems|To see the full stack trace|Re-run Maven using|\[Help [0-9]+\])|^[[:space:]]*\[ERROR\][[:space:]]*$'

for attempt in 1 2 3; do
  if mvn "$@" 2>&1 | tee "$log"; then
    exit 0
  fi

  error_lines="$(grep -E '^[[:space:]]*\[ERROR\]' "$log" || true)"
  if [[ -z "$error_lines" ]]; then
    echo "::error::Maven build failed; not retrying (no [ERROR] lines found in attempt log)"
    exit 1
  fi

  while IFS= read -r error_line; do
    if ! [[ "$error_line" =~ $transient_error_allowlist ]]; then
      echo "::error::Maven build failed; not retrying (unrecognised error: $error_line)"
      exit 1
    fi
  done <<< "$error_lines"

  if (( attempt < 3 )); then
    echo "::warning::transient p2 repository failure (attempt $attempt/3); cooling ${sleep_seconds}s"
    sleep "$sleep_seconds"
  fi
done

exit 1
