#!/usr/bin/env bash
# Run all three suites (anthropic + openai + mcp) on every supported runtime.
# Any failure fails the script.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "── JVM Clojure ────────────────────────────"
# `clojure`, not `clj`: the latter wraps the former in rlwrap, which aborts
# with "My terminal reports width=0" when there is no TTY — i.e. under CI.
clojure -M:test-anthropic || fail=1
clojure -M:test-openai    || fail=1
clojure -M:test-mcp       || fail=1

echo
echo "── Babashka ───────────────────────────────"
bb test || fail=1

echo
echo "── clz ────────────────────────────────────"
# examples/*.clj is reached via the checked-in src/examples -> ../examples
# symlink: clz test's namespace resolver only honors an implicit literal
# ./src root for transitive requires inside compiled test files — declared
# -L roots (e.g. -L examples) are NOT consulted there (verified empirically;
# a plain `clz script.clj` DOES honor arbitrary -L roots, only `clz test`
# has this narrower behavior). Documented as a platform quirk in README.
clz_out=$(clz -L src -L test test \
                test/tools/agents/anthropic_test.cljc \
                test/tools/agents/anthropic/live_test.cljc \
                test/tools/agents/anthropic/visualize_test.cljc \
                test/tools/agents/openai_test.cljc \
                test/tools/agents/openai/live_test.cljc \
                test/tools/agents/mcp_test.cljc \
                test/tools/agents/mcp/conformance_test.cljc \
                test/tools/agents/mcp/client_test.cljc \
                test/tools/agents/mcp/http_test.cljc \
                test/tools/agents/mcp/stdio_test.cljc \
                test/tools/agents/mcp/examples_test.cljc 2>&1) || fail=1
echo "$clz_out" | tail -6
# Belt-and-braces: clz test silently drops a file's tests (while still
# reporting "0 failures, 0 errors" for the rest) if it hits a compile error
# resolving one of several test files given on one command line — so also
# check every suite's header actually ran, and that no compile error string
# is present anywhere in the output.
if ! echo "$clz_out" | grep -Eq '^Ran [1-9][0-9]* tests\.$' ||
   ! echo "$clz_out" | grep -q ' 0 failures, 0 errors' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.anthropic-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.anthropic.live-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.anthropic.visualize-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.openai-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.openai.live-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp.conformance-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp.client-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp.http-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp.stdio-test' ||
   ! echo "$clz_out" | grep -q 'Testing tools.agents.mcp.examples-test' ||
   echo "$clz_out" | grep -qi 'compile error\|unhandled exception'; then
  fail=1
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "all runtimes green"
else
  echo "FAILURES — see above"
fi
exit "$fail"
