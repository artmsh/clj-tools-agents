# toolkit for programmatic agentic coding

Pure-Clojure libraries for talking to LLMs — three provider API clients and
one Model Context Protocol implementation — under one shared base namespace
`tools.agents`. Each is ergonomically modeled on that vendor's own official
Python SDK (`client` value standing in for the SDK's constructor,
resource methods matching its `create(**params)` calls, a typed `ex-info`
error hierarchy matching its exception classes) and runs unmodified on **JVM
Clojure** and **Babashka**.

Merged from the formerly separate `corevector-anthropic` and
`corevector-openai` repos — same architecture in both (one shared
function for network I/O, `tools.agents.http/request!`; hand-rolled portable JSON codec, `ex-info`-with-`:type` error
hierarchy, two-runtime test matrix), now sharing one repo, one CI run, and
one `tools.agents.*` namespace root instead of two `corevector.*` ones.

| Library | Namespace | Docs |
|---|---|---|
| Anthropic Messages API | `tools.agents.anthropic` (+ `.batches`, `.visualize`, `.spec`) | [docs/anthropic.md](docs/anthropic.md) |
| OpenAI Responses & Chat Completions APIs | `tools.agents.openai` | [docs/openai.md](docs/openai.md) |
| OpenAI Agents API (beta) — managed sessions, turns, OpenAI-hosted & self-hosted sandboxes | `tools.agents.openai.agents` | [docs/openai-agents.md](docs/openai-agents.md) |
| Gemini Developer API | `tools.agents.gemini` | [docs/gemini.md](docs/gemini.md) |
| Model Context Protocol, revision 2026-07-28 — server, client, stdio & Streamable HTTP | `tools.agents.mcp` (+ `.server`, `.client`, `.stdio`, `.http`) | [docs/mcp.md](docs/mcp.md) |
| Multi-provider orchestration — call providers concurrently, fuse successful texts | `tools.agents.fusion` | [docs/fusion.md](docs/fusion.md) |

Each doc covers that library's usage, full API parity table against its
vendor SDK, error hierarchy, retries, and platform notes (streaming, HTTP
server hosting). [docs/divergences.md](docs/divergences.md) tabulates every
contract where the four provider clients deliberately behave differently —
each follows its own vendor SDK rather than the others. docs/mcp.md's
"The everything server, row by row" does the same for the MCP library
against the reference `everything` server.

## JSON: one shared hand-rolled codec

No JSON library is available on both runtimes without adding a dependency
(Babashka bundles one, JVM Clojure does not), so every library here uses the
small hand-written codec in `tools.agents.json` (`src/tools/agents/json.cljc`):

- `write-json` encodes nil/bool/number/string/keyword/vector/seq/map. Map keys
  (strings, keywords or symbols) pass through **verbatim**, with no
  kebab↔snake conversion. A ratio is rejected rather than emitted as invalid
  `n/d`. **Every** character below 0x20 is escaped as `\u00XX`, so the output
  is always valid JSON (RFC 8259 §7) and never contains a raw newline, which
  MCP's stdio framing depends on.
- `read-json` decodes objects into maps with **string** keys, arrays into
  vectors, and keeps integers as integers. It rejects leading-zero numbers
  (`010`), which Clojure's reader would otherwise read as octal.
- `read-jsonl` decodes JSON Lines from a String or `java.io.Reader` into a
  lazy seq, skipping blank lines. A parse error's message names the 1-based
  line, and its ex-data carries `:line`.
- `(json/codec {:prefix :encode-type :parse-type})` returns
  `{:read :write :read-jsonl :key->str}` bound to one error contract.

Each client namespace keeps its own public `write-json`/`read-json` as thin
wrappers over a codec bound to that namespace's documented error `:type`
keywords and `<ns>/read-json: ` / `<ns>/write-json: ` message prefixes. For
example, `tools.agents.openai` throws `:tools.agents.openai/json-parse-error`,
and `tools.agents.anthropic` throws `:tools.agents.anthropic.error/json-parse`.

## Refreshable credentials: `tools.agents.token`

Static keys (`:api-key`, `:auth-token`) are resolved once by `client`. A
token that expires goes in `:credential-source` instead, on the anthropic,
openai (and so openai.agents) and gemini clients:

```clojure
(require '[tools.agents.token :as token])

(def src (token/token-cache {:fetch! (fn [] {:token (exchange!) :expires-at epoch-ms})
                             :refresh-skew-ms 60000}))
(oai/client {:credential-source src})
```

- The client calls `(token/token! src)` before **every** attempt, retries
  included. The body is still encoded once.
- `token-cache` returns the cached token until `expires-at` minus the skew
  (capped at half the token's lifetime); `nil` `:expires-at` never expires.
  Refresh is single-flight: concurrent callers share one in-flight `fetch!`.
  A failed fetch throws to every waiter and leaves nothing cached, so the
  next call fetches again. Fetch failures propagate as-is; they are not
  retried as connection errors.
- On a 401 the client calls `(token/invalidate! src used-token)` and retries
  **once**, outside `:max-retries` and without backoff. A second 401 throws
  the usual authentication error (openai also invalidates the token again, as
  openai-python does, so the next call refetches). Static keys never retry a 401.
- `:credential-source` replaces `:api-key`/`:auth-token` and the env vars;
  combining them throws `invalid-credentials` (per-client `:type`).
  Anthropic sends the token as `Authorization: Bearer` plus
  `anthropic-beta: oauth-2025-04-20`, openai as `Authorization: Bearer`,
  gemini as `Authorization: Bearer` instead of `x-goog-api-key`.
- openai only: `:api-key` may be a zero-arg fn (openai-python's callable
  `api_key`, e.g. an Azure Entra token provider). It becomes a source that
  calls the fn before every attempt with no cache and declines
  `invalidate!`, so a 401 is not retried, as in the SDK. See docs/openai.md.
- **Extension point.** Anything satisfying the `tools.agents.token/TokenSource`
  protocol (`-token`, `-invalidate`) is accepted. Workload identity, profile
  files and callable keys (#35–#38) are constructors returning a source,
  wired into each client's private `resolve-client-credentials`; the public
  `resolve-credentials` functions are unchanged.
- **Anthropic Workload Identity Federation** (#35) is the first such source:
  `tools.agents.anthropic.credentials/workload-identity-source`, discovered
  automatically from `ANTHROPIC_FEDERATION_RULE_ID`,
  `ANTHROPIC_ORGANIZATION_ID` and `ANTHROPIC_IDENTITY_TOKEN[_FILE]` when no
  static Anthropic key or token is set. See
  [docs/anthropic.md](docs/anthropic.md#workload-identity-federation).
- **Anthropic profiles** (#36): `ANTHROPIC_PROFILE` / `ANTHROPIC_CONFIG_DIR`
  / `active_config` / `~/.config/anthropic/configs/default.json`, or
  `(anthropic/client {:profile "work"})`. `user_oauth` profiles refresh with
  a `refresh_token` grant and write the new tokens back atomically (0600);
  `oidc_federation` profiles run WIF with a disk cache. See
  [docs/anthropic.md](docs/anthropic.md#profiles).
- **OpenAI Workload Identity Federation** (#38):
  `tools.agents.openai.credentials/workload-identity-source` with a subject
  token provider (`k8s-service-account-token-provider`,
  `gcp-id-token-provider`, `azure-managed-identity-token-provider` or a fn),
  passed explicitly as `:credential-source`; no env vars, as in the SDK.
  Exchanges at `auth.openai.com/oauth/token`, refreshes 1200 s before expiry,
  re-exchanges once on a 401. `admin_api_key` is still not ported. See
  [docs/openai.md](docs/openai.md#workload-identity-federation).

## Usage

```clojure
(require '[tools.agents.anthropic :as anthropic])

(def client (anthropic/client {:api-key (System/getenv "ANTHROPIC_API_KEY")}))
(def messages (anthropic/add-user-message [] "Say hello in one short sentence."))
(-> (anthropic/messages-create client
      {"model" "claude-sonnet-4-6" "max_tokens" 1000 "messages" messages})
    (anthropic/output-text))
```

```clojure
(require '[tools.agents.openai :as oai])

(def client (oai/client {:api-key (System/getenv "OPENAI_API_KEY")}))
(-> (oai/responses-create client
      {"model" "gpt-5.5" "input" "Say hello in one short sentence."})
    (oai/output-text))
```

```clojure
(require '[tools.agents.openai :as oai]
         '[tools.agents.openai.agents :as agents])

(def client (oai/client {:api-key (System/getenv "OPENAI_API_KEY")}))  ;; same client, agents/* just adds "OpenAI-Beta: agents=v1"
(def session (agents/sessions-create client
               {"agent" {"model" "gpt-6-astra" "instructions" "Write clean code, run it, and report the actual output."}
                "environment" {"type" "openai_hosted"}
                "input" "Create tree.py and run it."}))
;; Poll until the session leaves "created"/"in_progress" (or stream: sessions-create-stream + await-root-turn):
(loop [] (if (#{"created" "in_progress"} (get (agents/sessions-retrieve client (get session "id")) "status"))
           (do (Thread/sleep 500) (recur))
           (agents/items-output-text (agents/sessions-items-list client (get session "id") {"order" "asc"}))))
```

```clojure
(require '[tools.agents.gemini :as gemini])

(def client (gemini/client {:api-key (System/getenv "GEMINI_API_KEY")}))
(-> (gemini/generate-content client "gemini-2.5-flash"
      {"contents" [{"role" "user" "parts" [{"text" "Say hello in one short sentence."}]}]})
    (gemini/output-text))
```

```clojure
(require '[tools.agents.mcp.server :as server]
         '[tools.agents.mcp.stdio :as stdio])

(stdio/serve!
 (server/server
  {:name "weather" :version "1.0.0"
   :tools [{:name "get_alerts"
            :description "Get weather alerts for a US state."
            :input-schema {"type" "object"
                           "properties" {"state" {"type" "string"}}
                           "required" ["state"]}
            :handler (fn [ctx args] (fetch-alerts (get args "state")))}]}))
```

See `examples/anthropic/`, `examples/openai/`, `examples/gemini/`,
`examples/mcp/` and `examples/fusion/` for complete, runnable ports of real
usage shapes from each vendor SDK, the MCP reference servers, and
multi-provider orchestration — see each doc's Usage section for the full
list.

## Testing

```
./script/test-all.sh
```

Runs all six suites (core + anthropic + openai + gemini + mcp + fusion) on JVM
Clojure (`clojure -M:test-core` / `-M:test-anthropic` / `-M:test-openai` / `-M:test-gemini` /
`-M:test-mcp` / `-M:test-fusion`) and Babashka (`bb test`), and fails
loudly if either runtime is red for any suite. Hermetic — mock servers and
in-process loopbacks only, no outbound network. See each doc's Testing
section for what each suite covers; the core suite (`tools.agents.json`, the
shared JSON codec, `tools.agents.http`, the shared request function,
`tools.agents.sse`, the pure SSE parser, and `tools.agents.stream`, the
streaming transport — see docs/openai.md's "Streaming transport") is
documented in those namespaces' docstrings.

`script/live-check.sh` is OpenAI's manual, non-CI live smoke check against a
real endpoint — see docs/openai.md's Live smoke check section.

## License

Apache License 2.0 — see `LICENSE`.
