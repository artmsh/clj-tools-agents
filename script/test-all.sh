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
echo "── clz ────────────────────────────────────"
# One clz invocation per suite, mirroring bb.edn — not one invocation for all
# eleven files. A clz suite that parks at exit (the openai suite did, for as
# long as its every mock-server test was failing and retrying with real sleeps
# — see the comment in its live-test clz branch: closing a listening socket
# does not unblock an in-progress accept(), so the FuturePool never drains)
# takes down every suite listed after it on a shared command line, and holds
# its fixed mock ports into the next run. Per-suite, that stays contained: the
# stuck suite times out as its own failure with its results already printed,
# and the rest still report.
#
# examples/*.clj is reached via the checked-in src/examples -> ../examples
# symlink: clz test's namespace resolver only honors an implicit literal
# ./src root for transitive requires inside compiled test files — declared
# -L roots (e.g. -L examples) are NOT consulted there (verified empirically;
# a plain `clz script.clj` DOES honor arbitrary -L roots, only `clz test`
# has this narrower behavior). Documented as a platform quirk in README.
clz_suite() {
  local label="$1"; shift
  local files=() namespaces=()
  local seen_ns=0
  for arg in "$@"; do
    if [ "$arg" = "--" ]; then seen_ns=1; continue; fi
    if [ "$seen_ns" -eq 1 ]; then namespaces+=("$arg"); else files+=("$arg"); fi
  done
  local out
  # A hung suite must not hold its ports forever: the next run's Babashka
  # stage binds the same fixed mock ports and would fail with "Address
  # already in use". `timeout -k` escalates to SIGKILL if SIGTERM is ignored.
  # 15 minutes, not 5: the openai suite prints its full summary and only then
  # parks at exit, and a budget shorter than the run itself would report a
  # timeout without ever showing the results.
  out=$(timeout -k 10 900 clz -L src -L test test "${files[@]}" 2>&1) || fail=1
  echo "── clz: $label"
  echo "$out" | tail -4
  # Belt-and-braces: clz test silently drops a file's tests (while still
  # reporting "0 failures, 0 errors" for the rest) if it hits a compile error
  # resolving one of several test files given on one command line — so also
  # check every namespace's header actually ran, and that no compile error
  # string is present anywhere in the output.
  if ! echo "$out" | grep -Eq '^Ran [1-9][0-9]* tests\.$' ||
     ! echo "$out" | grep -q ' 0 failures, 0 errors' ||
     echo "$out" | grep -qi 'compile error\|unhandled exception'; then
    fail=1
  fi
  local ns
  for ns in "${namespaces[@]}"; do
    echo "$out" | grep -q "Testing $ns" || fail=1
  done
}

# `timeout` is GNU coreutils; on macOS it arrives as gtimeout via `brew
# install coreutils`. Fall back to running without a timeout rather than
# skipping the stage.
if ! command -v timeout >/dev/null 2>&1; then
  if command -v gtimeout >/dev/null 2>&1; then
    timeout() { gtimeout "$@"; }
  else
    timeout() { shift 3; "$@"; }   # drop `-k 10 900`
    echo "note: no timeout(1) — a hung clz suite will not be killed"
  fi
fi

clz_suite anthropic \
  test/tools/agents/anthropic_test.cljc \
  test/tools/agents/anthropic/live_test.cljc \
  test/tools/agents/anthropic/visualize_test.cljc \
  -- tools.agents.anthropic-test \
     tools.agents.anthropic.live-test \
     tools.agents.anthropic.visualize-test

clz_suite openai \
  test/tools/agents/openai_test.cljc \
  test/tools/agents/openai/live_test.cljc \
  -- tools.agents.openai-test \
     tools.agents.openai.live-test

clz_suite gemini \
  test/tools/agents/gemini_test.cljc \
  test/tools/agents/gemini/live_test.cljc \
  -- tools.agents.gemini-test \
     tools.agents.gemini.live-test

clz_suite mcp \
  test/tools/agents/mcp_test.cljc \
  test/tools/agents/mcp/conformance_test.cljc \
  test/tools/agents/mcp/client_test.cljc \
  test/tools/agents/mcp/http_test.cljc \
  test/tools/agents/mcp/stdio_test.cljc \
  test/tools/agents/mcp/examples_test.cljc \
  -- tools.agents.mcp-test \
     tools.agents.mcp.conformance-test \
     tools.agents.mcp.client-test \
     tools.agents.mcp.http-test \
     tools.agents.mcp.stdio-test \
     tools.agents.mcp.examples-test

echo
if [ "$fail" -eq 0 ]; then
  echo "all runtimes green"
else
  echo "FAILURES — see above"
fi
exit "$fail"
