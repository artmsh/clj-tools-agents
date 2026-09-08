(ns tools.agents.anthropic.spec
  "Optional clojure.spec.alpha (spec1 — NOT `clojure.alpha.spec`/spec2, which
   is git-dep-only, never released to Maven Central, and absent from
   Babashka) definitions for tools.agents.anthropic.

   This file is a plain .clj, never required by tools.agents.anthropic.cljc
   itself — require it explicitly yourself when you want the specs.

   THE STRING-KEYED TRAP: the Messages API wire format (request/response
   maps — \"model\"/\"max_tokens\"/\"messages\"/content blocks/etc.) is
   STRING-keyed by design (see tools.agents.anthropic's ns docstring — no
   kebab<->snake conversion, data-transparency contract matching the
   original Zig builtin and the Python SDK's literal dict keys). `s/keys`
   only ever matches KEYWORD keys, so a naive
   `(s/def ::request (s/keys :req-un [::model ::max_tokens]))` would look
   plausible and validate NOTHING about a real request map — it would pass
   `{}` and fail `{\"model\" \"x\" \"max_tokens\" 1}` alike, since neither has
   a `:model`/`:max_tokens` keyword key at all. The specs below split
   accordingly:
     - genuinely keyword-keyed surfaces (client opts, ex-data, the tool-
       calling/DSL descriptor maps) use ordinary `s/keys`.
     - wire-shaped values (request maps, message maps, content blocks) use
       `s/and`/explicit string-key predicates instead — see `has-key` below.

   NOTE: unlike `defn`/`def`, `s/def` and `s/fdef` are 2-arg macros (key,
   spec-form) with no docstring slot — the explanatory comments below sit
   ABOVE each s/def rather than inside it."
  (:require [clojure.spec.alpha :as s]
            [tools.agents.anthropic :as a]))

;; ---------------------------------------------------------------------------
;; Wire-shaped (string-keyed) helpers — NOT s/keys, see ns docstring.
;; ---------------------------------------------------------------------------

(defn string-keyed-map? [m]
  (and (map? m) (every? string? (keys m))))

(defn- has-key
  "True when string-keyed map m has key k satisfying pred. The building
   block every wire-shaped spec below is made of, since s/keys can't see
   string keys at all."
  [k pred]
  (fn [m] (and (map? m) (contains? m k) (pred (get m k)))))

;; Any Messages API content block — text/image/document/thinking/tool_use/
;; tool_result/etc. Deliberately loose (just "a map with a string \"type\""):
;; the block schema varies per type and this library stays data-transparent
;; rather than modeling Anthropic's full content-block schema itself.
(s/def ::content-block-map
  (s/and map? (has-key "type" string?)))

(s/def ::message-map
  (s/and map?
         (has-key "role" #{"user" "assistant"})
         #(contains? % "content")))

(s/def ::messages-vector (s/coll-of ::message-map :kind vector?))

;; A messages-create/count-tokens request map's minimum required shape.
;; Everything else in the map (system/temperature/stop_sequences/thinking/
;; tools/etc.) passes through unchecked — see tools.agents.anthropic's own
;; data-transparency contract, which this spec does not relax.
(s/def ::request-map
  (s/and map?
         (has-key "model" string?)
         (has-key "messages" vector?)))

;; ---------------------------------------------------------------------------
;; Client (genuinely keyword-keyed — ordinary s/keys applies)
;; ---------------------------------------------------------------------------

(s/def ::api-key string?)
(s/def ::auth-token string?)
(s/def ::base-url string?)
(s/def ::max-retries (s/and int? #(>= % 0)))

;; The map passed to `client`. Neither :api-key nor :auth-token is required
;; here — `client` falls back to ANTHROPIC_API_KEY/ANTHROPIC_AUTH_TOKEN env
;; vars, or throws :missing-credentials, at call time; that's a runtime
;; concern resolve-credentials owns, not a static shape concern this spec
;; should duplicate.
(s/def ::client-opts
  (s/keys :opt-un [::api-key ::auth-token ::base-url ::max-retries]))

;; The AnthropicClient record `client` RETURNS — unlike ::client-opts,
;; credential resolution has
;; already run by this point, so exactly one of :api-key/:auth-token is
;; guaranteed present (resolve-credentials' whole contract). The xor
;; predicate is load-bearing: `s/or` alone only enforces "at least one" (a
;; map with BOTH keys satisfies either branch and would wrongly pass), which
;; is weaker than "exactly one".
;;
;; The predicate must come BEFORE the `s/or` in this `s/and`, not after:
;; `s/and` threads each spec's CONFORMED value into the next, and `s/or`
;; conforms to a tagged pair like [:api-key {...}], not the original map —
;; a predicate placed after it would see that tuple and silently pass
;; (keyword lookup on a vector is just nil, so `(:api-key m)` and
;; `(:auth-token m)` both read nil, always non-truthy `and`). Verified
;; empirically: with the predicate last, `(s/valid? ::resolved-client
;; {... :api-key "k" :auth-token "t"})` was still `true`. `s/keys`, unlike
;; `s/or`, conforms as identity, so putting the predicate right after it
;; (still seeing the real map) works.
(s/def ::resolved-client
  (s/and (s/keys :req-un [::base-url ::max-retries])
         (fn [m] (not (and (:api-key m) (:auth-token m))))
         (s/or :api-key (s/keys :req-un [::api-key])
               :auth-token (s/keys :req-un [::auth-token]))))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

;; Every :type keyword tools.agents.anthropic itself can throw — see the
;; error-hierarchy table in README. A closed set on purpose: an ex-data :type
;; this library didn't document throwing is exactly the case worth catching
;; in a spec, not silently accepting as "some keyword or other".
(s/def ::error-type
  #{:tools.agents.anthropic.error/bad-request
    :tools.agents.anthropic.error/authentication
    :tools.agents.anthropic.error/permission-denied
    :tools.agents.anthropic.error/not-found
    :tools.agents.anthropic.error/unprocessable-entity
    :tools.agents.anthropic.error/rate-limit
    :tools.agents.anthropic.error/internal-server
    :tools.agents.anthropic.error/api-status
    :tools.agents.anthropic.error/api-connection
    :tools.agents.anthropic.error/json-encode
    :tools.agents.anthropic.error/json-parse
    :tools.agents.anthropic.error/missing-credentials
    :tools.agents.anthropic.error/invalid-max-retries
    :tools.agents.anthropic.error/streaming-unsupported
    :tools.agents.anthropic.error/invalid-response
    :tools.agents.anthropic.error/no-text-content
    :tools.agents.anthropic.error/invalid-content-shape
    :tools.agents.anthropic.error/invalid-content-block})

(s/def ::type ::error-type)
(s/def ::status (s/nilable int?))
(s/def ::body (s/nilable string?))
(s/def ::headers (s/nilable map?))

;; Shape of every ex-info this library throws: (s/valid? ::ex-data (ex-data e)).
(s/def ::ex-data
  (s/keys :req-un [::type] :opt-un [::status ::body ::headers]))

;; ---------------------------------------------------------------------------
;; Tool calling / message content-block DSL (keyword-keyed at the outer
;; wrapper — see tools.agents.anthropic's `tool-calls`/`add-tool-results`)
;; ---------------------------------------------------------------------------

(s/def ::id string?)
(s/def ::name string?)
(s/def ::input map?)

;; One element of (tool-calls response) — :input stays unvalidated (arbitrary
;; caller-defined JSON per the tool's own input_schema).
(s/def ::tool-call
  (s/keys :req-un [::id ::name ::input]))

(s/def ::tool-use-id string?)
;; NOTE: registered under ::tool-result-content but req-un'd below as
;; ::content — add-tool-results/add-tool-result actually destructure
;; :content (see anthropic.cljc, and every README/example call site), NOT
;; :tool-result-content. An earlier version of this spec required
;; :tool-result-content directly, which rejected every real, documented
;; call shape once instrumented, and silently accepted a differently-keyed
;; map whose :content the real function would just drop — verified
;; empirically. ::content aliases this spec so req-un sees the right key
;; name while the descriptive var name stays self-explanatory.
;;
;; The VALUE shape must match add-tool-results' actual (widened) runtime
;; behavior, not just its final wire shape: :content accepts a plain
;; string, a single content-block map (e.g. an image block — the
;; docstring's own non-text-output example, wrapped into a one-element
;; array), or a mixed seq of strings/maps (normalized via content-blocks).
;; An earlier version of this spec only accepted `string?` or `(s/coll-of
;; ::content-block-map)` — the exact same defect class as the key-mismatch
;; bug above: instrumenting add-tool-results and calling it with a bare
;; image-block map or a mixed string/map seq (both real, documented,
;; correctly-handled shapes after add-tool-results was widened to
;; normalize :content) threw a spec-conformance error — verified
;; empirically. :block must precede :blocks: a bare map satisfies `coll?`
;; (so :blocks' `s/coll-of` would try to match it too, iterating its
;; MapEntry pairs and failing) — checking :block first means a bare map is
;; recognized as itself rather than misread as a malformed collection.
(s/def ::tool-result-content
  (s/or :text   string?
        :block  ::content-block-map
        :blocks (s/coll-of (s/or :text string? :block ::content-block-map))))
(s/def ::content ::tool-result-content)
(s/def ::is-error boolean?)

;; One element of add-tool-results' `results` argument.
(s/def ::tool-result-descriptor
  (s/keys :req-un [::tool-use-id ::content] :opt-un [::is-error]))

;; ---------------------------------------------------------------------------
;; A couple of fdefs, as a worked example of instrumenting this library —
;; run (require '[clojure.spec.test.alpha :as st]) (st/instrument) yourself
;; to turn these on; NOT invoked automatically by requiring this ns (spec
;; conventionally leaves that opt-in to the caller).
;; ---------------------------------------------------------------------------

(s/fdef a/client
  :args (s/cat :opts (s/? ::client-opts))
  :ret ::resolved-client)

(s/fdef a/content-blocks
  :args (s/cat :items (s/nilable (s/or :text string? :block map? :coll coll?)))
  :ret (s/coll-of ::content-block-map :kind vector?))

(s/fdef a/add-tool-results
  :args (s/cat :messages ::messages-vector :results (s/coll-of ::tool-result-descriptor))
  :ret ::messages-vector)
