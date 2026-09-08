#!/usr/bin/env bash
# Live smoke check against a real OpenAI-compatible endpoint, on both
# runtimes. NOT part of CI — script/test-all.sh stays hermetic. This is the
# manual counterpart that proves the client works against a live gateway
# rather than only against a mock this repo wrote itself.
#
#   OPENAI_API_KEY=... ./script/live-check.sh
#   OPENAI_BASE_URL=https://api.openai.com/v1 OPENAI_MODEL=gpt-5.5 ./script/live-check.sh
#
# Defaults to the homelab OmniRoute gateway; override OPENAI_BASE_URL /
# OPENAI_MODEL for anything else.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${OPENAI_BASE_URL:=http://omniroute.lan/v1}"
: "${OPENAI_MODEL:=vag/inclusionai/ling-3.0-flash-free}"
export OPENAI_BASE_URL OPENAI_MODEL

if [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "OPENAI_API_KEY is not set — refusing to run." >&2
  exit 2
fi

fail=0
for runtime in "clojure -M" "bb"; do
  echo "── ${runtime} ─────────────────────────────"
  # shellcheck disable=SC2086
  $runtime script/live_check.clj || fail=1
  echo
done

if [ "$fail" -eq 0 ]; then
  echo "live check green on all runtimes"
else
  echo "LIVE CHECK FAILURES — see above"
fi
exit "$fail"
