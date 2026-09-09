# tools.agents.converge

`tools.agents.converge` is a small orchestration namespace for calling
several LLM providers together. It delegates HTTP and provider semantics to
`tools.agents.openai` and `tools.agents.anthropic` and adds three
higher-level operations:

- call one provider spec and normalize the result;
- call multiple provider specs concurrently;
- converge successful provider texts into a deterministic answer map.

The namespace currently supports provider specs for `:openai` and
`:anthropic`.

Migrated from clz's embedded `clz.llm` namespace, where this module
originated as a demonstration of clz calling out to this library — the
underlying logic is unchanged.

## Demo

Run the bundled offline demo:

```bash
clojure -Sdeps '{:paths ["src" "."]}' -M -e "(require 'examples.converge.provider-demo) (examples.converge.provider-demo/-main)"
# or on Babashka:
bb -cp src:. -e "(require 'examples.converge.provider-demo) (examples.converge.provider-demo/-main)"
```

The demo uses local `:call` hooks, so it does not require network access or
API keys. It prints a converged answer from two successful mock providers and
keeps one simulated provider failure as structured result data.

## Provider Specs

A provider spec is a map with a required `:provider` key:

```clojure
{:provider :openai
 :id :concise
 :model "gpt-4.1-mini"
 :system "Answer in one sentence."
 :api-key "test-or-real-key"
 :base-url "https://api.openai.com/v1"}
```

```clojure
{:provider :anthropic
 :id :reviewer
 :model "claude-3-5-haiku-latest"
 :system "Point out risks and uncertainty."
 :max-tokens 1024
 :api-key "test-or-real-key"
 :base-url "https://api.anthropic.com"}
```

Useful keys:

| Key | Meaning |
| --- | --- |
| `:provider` | `:openai` or `:anthropic` |
| `:id` | Stable identifier in normalized results |
| `:model` | Provider model name |
| `:system` | Provider-specific system prompt |
| `:api-key` | Explicit API key override |
| `:auth-token` | Explicit bearer/OAuth token override (`:anthropic` only, forwarded to `tools.agents.anthropic/client`) |
| `:base-url` | Explicit endpoint override, useful for local tests |
| `:max-tokens` | Anthropic `max_tokens`, defaults to `1024` |
| `:call` | Test/demo hook: `(fn [spec prompt] {:text ... :response ...})` |

## Examples

Call one provider:

```clojure
(require '[tools.agents.converge :as converge])

(converge/provider-call
 {:provider :openai
  :id :primary
  :model "gpt-4.1-mini"
  :system "Answer tersely."
  :api-key "..."
  :base-url "https://api.openai.com/v1"}
 "Summarize structural sharing.")
```

Successful result shape:

```clojure
{:id :primary
 :provider :openai
 :status :ok
 :text "..."
 :response {...}}
```

Failed provider calls are captured instead of aborting multi-provider flows:

```clojure
{:id :primary
 :provider :openai
 :status :error
 :error #error {:cause "tools.agents.openai/responses-create: ..." ...}}
```

Run providers concurrently:

```clojure
(converge/parallel
 [{:provider :openai
   :id :concise
   :model "gpt-4.1-mini"
   :system "Answer in one sentence."
   :api-key "..."}
  {:provider :anthropic
   :id :reviewer
   :model "claude-3-5-haiku-latest"
   :system "List one risk."
   :api-key "..."}]
 "Should parser tables be data-first?")
```

Converge provider texts:

```clojure
(converge/converge
 [{:provider :openai
   :id :concise
   :call (fn [_spec prompt]
           {:text (str "Short answer: " prompt)
            :response {:mock true}})}
  {:provider :anthropic
   :id :reviewer
   :call (fn [_spec prompt]
           {:text (str "Review note: " prompt)
            :response {:mock true}})}]
 "Keep tokenizer fast paths explicit?")
```

Converged result shape:

```clojure
{:status :ok
 :answer "Short answer: ...\nReview note: ..."
 :texts ["Short answer: ..." "Review note: ..."]
 :results [{:id :concise ...}
           {:id :reviewer ...}]}
```

If every provider fails, `:status` is `:error`, `:answer` is `nil`, and
`:results` still contains one error result per provider.

## Cheatsheet

| Function | Input | Output |
| --- | --- | --- |
| `provider-call` | provider spec, prompt string | normalized result map |
| `parallel` | provider spec vector, prompt string | result vector in spec order |
| `converge` | provider spec vector, prompt string | answer map with texts/results |

Normalized result fields:

| Field | Meaning |
| --- | --- |
| `:id` | provider `:id`, or `:provider` if no id was supplied |
| `:provider` | provider kind |
| `:status` | `:ok` or `:error` |
| `:text` | successful extracted text |
| `:response` | raw provider response map |
| `:error` | captured provider exception |

Converged result fields:

| Field | Meaning |
| --- | --- |
| `:status` | `:ok` when at least one provider succeeded, else `:error` |
| `:answer` | successful texts joined with newlines, or `nil` |
| `:texts` | vector of successful provider texts |
| `:results` | all normalized provider results in provider spec order |

Provider behavior:

| Provider | Low-level call | Text extractor | System handling |
| --- | --- | --- | --- |
| `:openai` | `tools.agents.openai/responses-create` | `tools.agents.openai/output-text` | prepended to input |
| `:anthropic` | `tools.agents.anthropic/messages-create` | `tools.agents.anthropic/output-text` | `"system"` request field |

## Notes

- `parallel` uses `future` and `deref`; result ordering is deterministic even
  though provider calls run concurrently.
- `converge` is intentionally local and deterministic. It does not call a
  third judge model.
- Provider retries and tool-call helpers are supplied by
  `tools.agents.openai`/`tools.agents.anthropic`. `tools.agents.converge`
  itself only normalizes and coordinates provider calls.
- An unsupported `:provider` (without a `:call` hook) throws `ex-info` with
  `{:type :tools.agents.converge/unsupported-provider}`.
