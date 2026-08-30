(ns tools.agents.anthropic.specs.alpha
  "Inferred-spec cache for tools.agents.anthropic — the `.spec/` half of the
   clojure-spec-check skill's discovery order, holding ONLY the delta the
   shipped `tools.agents.anthropic.spec` does not already cover.

   TWO KEY NAMESPACES ON PURPOSE, neither of them this file's own:

     - `::a/foo`     => :tools.agents.anthropic/foo — NEW keys, registered under
                        the SOURCE namespace (the skill's `:income.bets/bet`
                        convention), so callers find them where they expect.
     - `::aspec/foo` => :tools.agents.anthropic.spec/foo — EXISTING keys, reused
                        verbatim, never re-`s/def`ed.

   The shipped spec ns is `tools.agents.anthropic.spec`, so its own `::`
   shorthand registered everything under `:tools.agents.anthropic.spec/*`, NOT
   under `:tools.agents.anthropic/*` (verified against a live `(s/registry)`
   dump, and matching how spec_test.clj/README both address them:
   `::spec/request-map`). Bare `::` here would resolve to a THIRD namespace
   (`:tools.agents.anthropic.specs.alpha/*`) and silently reference unregistered
   keys, so every key below is written through an alias instead — `::a/` for
   new, `::aspec/` for reused.

   THE STRING-KEYED TRAP applies here identically — see the shipped ns
   docstring. Wire-shaped values (a response map, a content block) are
   STRING-keyed, so `s/keys` is blind to them and `has-key` below is used
   instead; only genuinely keyword-keyed surfaces (client opts, tool-call
   descriptors) get `s/keys`.

   WHAT IS DELIBERATELY ABSENT, and why (do not \"complete\" this file
   without asking first) — three `:ret`s, each omitted because the code does
   not prove the value it would assert:
     - `:ret` for `request-with-retries!` — it returns whatever the caller's
       own `attempt-fn` returned; there is no library-side contract to state,
       and `any?` would be a spec that catches nothing.
     - `:ret` for `messages-create` and `count-tokens` — their docstrings say
       \"the decoded response map\", but `decode-or-throw!` returns whatever
       `read-json` produced, so a 2xx body of `null` or `[…]` yields a
       non-map. `map?` would assert more than the code guarantees.

   INSTRUMENTATION INTERACTION, KNOWN AND INTENDED: several fdefs below
   constrain arguments the function itself rejects at runtime with a typed
   `ex-info` (`write-json` on a ratio, `output-text` on a response with no
   \"content\" array). Under instrumentation the spec error preempts that
   typed throw. This is the shipped `content-blocks` fdef's own established
   precedent — it likewise excludes the bare-scalar input that
   `:invalid-content-block` exists to reject — and it is safe under the
   project's discipline of instrumenting BY SYMBOL inside a try/finally
   (spec_test.clj) rather than globally. A bare `(stest/instrument)` is a
   different matter: see this file's report notes."
  (:require [clojure.spec.alpha :as s]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.spec :as aspec]))

;; ---------------------------------------------------------------------------
;; Wire-shaped (string-keyed) helper — a local mirror of the shipped ns's own
;; `has-key`, which is `defn-` (private) and therefore not reusable from here.
;; ---------------------------------------------------------------------------

(defn- has-key
  "True when string-keyed map m has key k satisfying pred."
  [k pred]
  (fn [m] (and (map? m) (contains? m k) (pred (get m k)))))

;; ---------------------------------------------------------------------------
;; JSON codec — write-json/read-json/json-key->str
;; ---------------------------------------------------------------------------

;; write-json's map keys, exactly: string/keyword/symbol pass through `name`
;; verbatim, anything else throws :json-encode (json-key->str's own cond).
(s/def ::a/json-key
  (s/or :string string? :keyword keyword? :symbol symbol?))

;; Every number EXCEPT a ratio — write-json rejects ratios explicitly (they
;; would stringify as unquoted `n/d`, invalid JSON) and accepts the rest.
;; KNOWN GAP, matching the code rather than papering over it: ##Inf/##NaN are
;; `number?` and non-ratio, so this admits them — and write-json emits bare
;; `Infinity`/`NaN`, which is NOT valid JSON and which read-json cannot parse
;; back. Reported, not silently narrowed here.
(s/def ::a/json-number (s/and number? #(not (ratio? %))))

(defn- json-array?
  "The three collection shapes write-json encodes as a JSON array. A set is
   deliberately NOT one of them — write-json throws :json-encode on a set."
  [x]
  (or (vector? x) (list? x) (seq? x)))

;; The full recursive domain write-json can encode. :map precedes :array so a
;; map is never first offered to the array branch.
(s/def ::a/json-value
  (s/or :null    nil?
        :boolean boolean?
        :number  ::a/json-number
        :string  string?
        :keyword keyword?
        :map     (s/map-of ::a/json-key ::a/json-value)
        :array   (s/coll-of ::a/json-value :kind json-array?)))

;; What read-json PRODUCES: objects -> string-keyed maps, arrays -> vectors,
;; numbers stay numbers, plus the three scalars. Narrower than ::a/json-value
;; (no keywords, no lists, object keys are always strings) because it
;; describes decoder output, not encoder input.
(s/def ::a/decoded-json
  (s/or :null    nil?
        :boolean boolean?
        :number  number?
        :string  string?
        :object  (s/map-of string? ::a/decoded-json)
        :array   (s/coll-of ::a/decoded-json :kind vector?)))

(s/fdef a/json-key->str
  :args (s/cat :k ::a/json-key)
  :ret string?)

(s/fdef a/write-json
  :args (s/cat :v ::a/json-value)
  :ret string?)

(s/fdef a/read-json
  :args (s/cat :s string?)
  :ret ::a/decoded-json)

;; ---------------------------------------------------------------------------
;; Credential resolution
;; ---------------------------------------------------------------------------

;; resolve-credentials' return: LITERALLY {:api-key s} or {:auth-token s} —
;; a one-entry map, never both keys. The count predicate is what makes that
;; "exactly one" rather than `s/or`'s weaker "at least one" — the same bug the
;; shipped ::resolved-client was fixed for. It must sit BEFORE the `s/or` in
;; this `s/and` for the same reason documented there: `s/and` threads each
;; spec's CONFORMED value onward and `s/or` conforms to a tagged pair, which a
;; later predicate would silently see instead of the real map.
(s/def ::a/credentials
  (s/and map?
         #(= 1 (count %))
         (s/or :api-key    (s/keys :req-un [::aspec/api-key])
               :auth-token (s/keys :req-un [::aspec/auth-token]))))

;; getenv-fn is `ifn?` and no narrower: spec1 cannot state the arity/return of
;; a function VALUE (that needs s/fspec + generative testing, and test.check
;; is not a dependency of this project). Its real contract — (fn [String] ->
;; String-or-nil) — is stated here in prose instead of being faked.
(s/fdef a/resolve-credentials
  :args (s/cat :opts ::aspec/client-opts :getenv-fn ifn?)
  :ret ::a/credentials)

;; ---------------------------------------------------------------------------
;; Retries
;; ---------------------------------------------------------------------------

;; :args only — see the ns docstring on why there is no honest :ret here.
(s/fdef a/request-with-retries!
  :args (s/cat :max-retries ::aspec/max-retries :attempt-fn ifn?))

;; The production binding of *sleep-fn*; `ifn?` for the same reason as
;; getenv-fn above (its real contract is (fn [seconds] -> ignored)).
(s/def ::a/sleep-fn ifn?)

;; ---------------------------------------------------------------------------
;; Request maps — messages-create / count-tokens
;; ---------------------------------------------------------------------------

(defn- either-key
  "True when map m carries k under EITHER its string spelling or the matching
   keyword, with a value satisfying pred. The two-keying counterpart of
   `has-key` above."
  [k pred]
  (let [kw (keyword k)]
    (fn [m]
      (and (map? m)
           (or (and (contains? m k) (pred (get m k)))
               (and (contains? m kw) (pred (get m kw))))))))

;; A messages-create/count-tokens REQUEST as those two functions actually
;; accept it — under either keying.
;;
;; This does NOT replace, relax, or redefine the shipped
;; `::aspec/request-map`, which stays exactly as it is: string-keyed-only, and
;; still the correct spec for validating a value destined for the WIRE
;; (spec_test.clj:29's "a keyword-keyed lookalike is not a request-map"
;; assertion continues to hold, unchanged). The two answer different
;; questions. The shipped one asks "is this the wire shape?"; this one asks
;; "will messages-create accept this?" — and the answer to the second is
;; genuinely broader, because the library encodes map keys VERBATIM via
;; `name` (README's parity table documents `:max_tokens` as equally valid)
;; and `messages-create` itself reads both spellings, e.g. `(or (true? (get
;; request :stream)) (true? (get request "stream")))`.
;;
;; Both of these pinned shapes validate:
;;   {"model" "m" "messages" []}                        README / every example
;;   {:model "m" :max_tokens 1 :messages [] :stream true}  anthropic_test.cljc:330
;;
;; Deliberately no stricter than the shipped analogue: "messages" is checked
;; for `vector?` only, not element-by-element, exactly as ::aspec/request-map
;; does — everything else in the map (system/tools/thinking/container/…)
;; passes through unchecked, which is the library's data-transparency
;; contract and not this spec's business to tighten.
;;
;; NOTE the `:stream true` shape above validates on purpose: it is a
;; documented call that then throws :streaming-unsupported. Keeping it inside
;; the args domain is what stops instrumentation from preempting that typed
;; throw (unlike P1/P2 in the ns docstring, where the shape refusal IS the
;; contract).
(s/def ::a/request-map-either-keyed
  (s/and map?
         (either-key "model" string?)
         (either-key "messages" vector?)))

;; :args only — see the ns docstring for why neither gets a :ret.
;; The `client` argument is ::aspec/resolved-client, the shipped spec for
;; "the map `client` RETURNS": verified that every messages-create/
;; count-tokens call site in test/ and examples/ builds its client through
;; `a/client` (ptc_demo's `(assoc client :betas …)` included, which only adds
;; a key).
(s/fdef a/messages-create
  :args (s/cat :client ::aspec/resolved-client :request ::a/request-map-either-keyed))

(s/fdef a/count-tokens
  :args (s/cat :client ::aspec/resolved-client :request ::a/request-map-either-keyed))

;; ---------------------------------------------------------------------------
;; Response readers
;; ---------------------------------------------------------------------------

;; What output-text requires: a response map whose "content" is a VECTOR.
;; Anything else is the exact condition output-text throws
;; :invalid-response for, so it is excluded from the args domain rather than
;; admitted and left to fail at runtime — the shipped content-blocks fdef's
;; own precedent for a top-level shape refusal. The stricter conditions
;; output-text ALSO enforces (at least one "text" block; each with a string
;; "text" value) are item-level, not shape-level, and stay runtime errors —
;; same split content-blocks' fdef already draws.
(s/def ::a/response-map (s/and map? (has-key "content" vector?)))

(s/fdef a/output-text
  :args (s/cat :response ::a/response-map)
  :ret string?)

;; tool-use?/tool-calls are TOTAL over any map by contract — tool-calls'
;; docstring is explicit that a response with no "content" array returns []
;; rather than throwing, so ::a/response-map would be wrong here, not merely
;; strict.
(s/fdef a/tool-use?
  :args (s/cat :response map?)
  :ret boolean?)

(s/fdef a/tool-calls
  :args (s/cat :response map?)
  :ret (s/coll-of ::aspec/tool-call :kind vector?))

;; ---------------------------------------------------------------------------
;; Message content-block DSL — constructors
;; ---------------------------------------------------------------------------

;; The trailing `opts` every constructor merges onto its block. Plain `map?`
;; on purpose: it carries arbitrary wire fields (the docstring's own example
;; is {"cache_control" {"type" "ephemeral"}}), and write-json accepts both
;; string and keyword keys, so neither string-keyed-map? nor s/keys applies.
(s/def ::a/block-opts map?)

(s/fdef a/text
  :args (s/cat :s string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/image-base64
  :args (s/cat :media-type string? :data string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/image-url
  :args (s/cat :url string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/document-base64
  :args (s/cat :media-type string? :data string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/document-url
  :args (s/cat :url string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/document-text
  :args (s/cat :s string? :opts (s/? ::a/block-opts))
  :ret ::aspec/content-block-map)

(s/fdef a/thinking
  :args (s/cat :thinking-text string? :signature string?)
  :ret ::aspec/content-block-map)

;; :id/:name/:input reuse the shipped keys tool-calls' ::aspec/tool-call is
;; built from — tool-use is that function's inverse (rehydrating a stored
;; tool_use block), so the two share one domain by construction.
(s/fdef a/tool-use
  :args (s/cat :id ::aspec/id :name ::aspec/name :input ::aspec/input)
  :ret ::aspec/content-block-map)

;; ---------------------------------------------------------------------------
;; Message builders
;; ---------------------------------------------------------------------------

;; The widened `content` argument shared by add-user-message/add-assistant-
;; message — the SAME form the shipped `content-blocks` fdef already uses for
;; its `items`, promoted to a registered key so the three stay in lockstep
;; instead of drifting apart as three copies.
(s/def ::a/content-input
  (s/nilable (s/or :text string? :block map? :coll coll?)))

;; `messages` is ::aspec/messages-vector by direct sibling precedent: the
;; shipped add-tool-results fdef specs the identical parameter that way.
(s/fdef a/add-user-message
  :args (s/cat :messages ::aspec/messages-vector :content ::a/content-input)
  :ret ::aspec/messages-vector)

(s/fdef a/add-assistant-message
  :args (s/cat :messages ::aspec/messages-vector :content ::a/content-input)
  :ret ::aspec/messages-vector)

;; add-tool-result destructures nothing itself — it forwards into
;; add-tool-results' ::aspec/tool-result-descriptor, so each argument reuses
;; that descriptor's own key specs (::aspec/content is the alias for
;; ::aspec/tool-result-content) rather than restating them.
(s/fdef a/add-tool-result
  :args (s/cat :messages ::aspec/messages-vector
               :tool-use-id ::aspec/tool-use-id
               :content ::aspec/content
               :is-error (s/? boolean?))
  :ret ::aspec/messages-vector)
