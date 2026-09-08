#!/usr/bin/env bash
# Run all four suites (anthropic + openai + gemini + mcp) on every supported runtime.
# Any failure fails the script.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "── JVM Clojure ────────────────────────────"
# `clojure`, not `clj`: the latter wraps the former in rlwrap, which aborts
# with "My terminal reports width=0" when there is no TTY — i.e. under CI.
clojure -M:test-anthropic || fail=1
clojure -M:test-openai    || fail=1
clojure -M:test-gemini    || fail=1
clojure -M:test-mcp       || fail=1

echo
echo "── Babashka ───────────────────────────────"
bb test || fail=1

echo
if [ "$fail" -eq 0 ]; then
  echo "all runtimes green"
else
  echo "FAILURES — see above"
fi
exit "$fail"
