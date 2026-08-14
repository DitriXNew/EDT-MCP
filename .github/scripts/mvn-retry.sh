#!/usr/bin/env bash
# Guard Maven builds against transient edt.1c.ru p2 outages without ever masking
# real compilation or test failures: those failures must not become green on retry.

set -euo pipefail

log="${RUNNER_TEMP:-/tmp}/mvn.log"
sleep_seconds="${MVN_RETRY_SLEEP_SECONDS:-30}"

for attempt in 1 2 3; do
  if mvn "$@" 2>&1 | tee "$log"; then
    exit 0
  fi

  if grep -qiE "COMPILATION ERROR|Compilation failure" "$log"; then
    echo "::error::Maven build failed: compilation failure"
    exit 1
  fi
  if grep -qi "There are test failures" "$log"; then
    echo "::error::Maven build failed: test failures"
    exit 1
  fi
  if grep -qi "There are test errors" "$log"; then
    echo "::error::Maven build failed: test errors"
    exit 1
  fi

  if ! grep -qiE "Could not mirror artifact|Unable to read repository|edt\.1c\.ru.*(Connection reset|HTTP code: 50[0-9]|Return code is: 50[0-9])|(Connection reset|HTTP code: 50[0-9]|Return code is: 50[0-9]).*edt\.1c\.ru" "$log"; then
    echo "::error::Maven build failed (not a transient edt.1c.ru error)"
    exit 1
  fi

  echo "::warning::transient edt.1c.ru failure (attempt $attempt/3); cooling ${sleep_seconds}s"
  sleep "$sleep_seconds"
done

exit 1
