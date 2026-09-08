# clj-tools-agents

Pure-Clojure libraries for talking to LLMs — three provider API clients and
one Model Context Protocol implementation — under one shared base namespace
`tools.agents`. Each is ergonomically modeled on that vendor's own official
Python SDK (`client` config map standing in for the SDK's constructor,
resource methods matching its `create(**params)` calls, a typed `ex-info`
error hierarchy matching its exception classes) and runs unmodified on **JVM
Clojure** and **Babashka**.

Merged from the formerly separate `corevector-anthropic` and
`corevector-openai` repos — same architecture in both (single leaf for
network I/O, hand-rolled portable JSON codec, `ex-info`-with-`:type` error
hierarchy, two-runtime test matrix), now sharing one repo, one CI run, and
one `tools.agents.*` namespace root instead of two `corevector.*` ones.

| Library | Namespace | Docs |
|---|---|---|
| Anthropic Messages API | `tools.agents.anthropic` (+ `.visualize`, `.spec`) | [docs/anthropic.md](docs/anthropic.md) |
| OpenAI Responses & Chat Completions APIs | `tools.agents.openai` | [docs/openai.md](docs/openai.md) |
| Gemini Developer API | `tools.agents.gemini` | [docs/gemini.md](docs/gemini.md) |
| Model Context Protocol, revision 2026-07-28 — server, client, stdio & Streamable HTTP | `tools.agents.mcp` (+ `.server`, `.client`, `.stdio`, `.http`) | [docs/mcp.md](docs/mcp.md) |

Each doc covers that library's usage, full API parity table against its
vendor SDK, error hierarchy, retries, and platform notes (streaming, HTTP
server hosting). "Divergences from tools.agents.anthropic" in docs/openai.md
covers the handful of places the two deliberately behave differently —
each follows its own vendor SDK rather than the other. docs/mcp.md's
"The everything server, row by row" does the same for the MCP library
against the reference `everything` server.

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

See `examples/anthropic/`, `examples/openai/`, `examples/gemini/` and
`examples/mcp/` for complete, runnable ports of real usage shapes from each
vendor SDK and from the MCP reference servers — see each doc's Usage section
for the full list.

## Testing

```
./script/test-all.sh
```

Runs all four suites (anthropic + openai + gemini + mcp) on JVM Clojure
(`clojure -M:test-anthropic` / `-M:test-openai` / `-M:test-gemini` /
`-M:test-mcp`) and Babashka (`bb test`), and fails loudly if either runtime
is red for any suite. Hermetic — mock servers and in-process loopbacks only,
no outbound network. See each doc's Testing section for what each suite
covers.

`script/live-check.sh` is OpenAI's manual, non-CI live smoke check against a
real endpoint — see docs/openai.md's Live smoke check section.

## License

Apache License 2.0 — see `LICENSE`.
