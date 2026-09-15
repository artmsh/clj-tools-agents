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
;; No streaming — poll until the turn leaves "created"/"in_progress":
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
