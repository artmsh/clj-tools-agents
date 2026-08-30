# clj-tools-agents

Pure-Clojure clients for LLM provider APIs, under one shared base namespace
`tools.agents`. Each provider is ergonomically modeled on that vendor's own
official Python SDK (`client` config map standing in for the SDK's
constructor, resource methods matching its `create(**params)` calls, a typed
`ex-info` error hierarchy matching its exception classes) and runs unmodified
on **JVM Clojure**, **Babashka**, and **[clz](https://forgejo.lan/art/clz)**.

Merged from the formerly separate `corevector-anthropic` and
`corevector-openai` repos — same architecture in both (single leaf for
network I/O, hand-rolled portable JSON codec, `ex-info`-with-`:type` error
hierarchy, three-runtime test matrix), now sharing one repo, one CI run, and
one `tools.agents.*` namespace root instead of two `corevector.*` ones.

| Library | Namespace | Docs |
|---|---|---|
| Anthropic Messages API | `tools.agents.anthropic` (+ `.visualize`, `.spec`) | [docs/anthropic.md](docs/anthropic.md) |
| OpenAI Responses & Chat Completions APIs | `tools.agents.openai` | [docs/openai.md](docs/openai.md) |

Each doc covers that library's usage, full API parity table against its
vendor SDK, error hierarchy, retries, and platform notes (streaming,
non-standard-port TLS under clz, `clz test`'s `examples/` namespace
resolution). "Divergences from tools.agents.anthropic" in docs/openai.md
covers the handful of places the two deliberately behave differently —
each follows its own vendor SDK rather than the other.

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

See `examples/anthropic/` and `examples/openai/` for complete,
runnable-against-a-mock-server ports of real usage shapes from each vendor
SDK — see each doc's Usage section for the full list.

## Testing

```
./script/test-all.sh
```

Runs both suites (anthropic + openai) on JVM Clojure (`clojure -M:test-anthropic`
/ `-M:test-openai`), Babashka (`bb test`), and clz (`clz test`), and fails
loudly if any of the three is red for either suite. Hermetic — mock servers
only, no outbound network. See each doc's Testing section for what each suite
covers.

`script/live-check.sh` is OpenAI's manual, non-CI live smoke check against a
real endpoint — see docs/openai.md's Live smoke check section.

## License

Apache License 2.0 — see `LICENSE`.
