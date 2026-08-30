(ns tools.agents.openai.specs.alpha
  "Inferred clojure.spec.alpha (spec1) definitions for tools.agents.openai,
   cached under .spec/ by the clojure-spec-check skill. Nothing in the shipped
   library requires this ns; load it explicitly on JVM Clojure:

     clj -Sdeps '{:aliases {:spec {:extra-paths [\".spec\"]}}}' -M:spec
     (require 'tools.agents.openai.specs.alpha)

   JVM Clojure only. clz has no clojure.spec.alpha at all and babashka does
   not bundle it — the same constraint tools.agents.anthropic.spec documents.

   Data specs are registered under the SOURCE namespace's keywords
   (:tools.agents.openai/…, spelled ::oai/… via the alias below), not under this
   ns, so callers find them where they expect.

   THE STRING-KEYED TRAP (inherited from tools.agents.anthropic.spec): the wire
   format — response maps, message maps, content blocks — is STRING-keyed by
   design (read-json decodes objects into string-keyed maps; the message
   helpers build {\"role\" … \"content\" …}). `s/keys` only ever matches
   KEYWORD keys, so `(s/keys :req-un [::output])` would pass `{}` and fail a
   real response alike. Wire-shaped values below therefore use `s/and` +
   `has-key`; genuinely keyword-keyed surfaces (client opts, ex-data) use
   ordinary `s/keys`.

   AND THE OPENAI-SPECIFIC TWIST: unlike the Anthropic sibling, an outbound
   REQUEST map here may be string-keyed OR keyword-keyed (write-json encodes
   both via `name`, verbatim — see the README parity table and
   tools.agents.openai-test/stream-true-rejected-with-keyword-key-too). So
   ::oai/request-map below CANNOT be spelled the sibling's way: its
   `(has-key \"model\" string?)` sees string keys only and rejects the pinned
   keyword-keyed call. It is either-keyed instead.

   WHY THE fdef :args SPECS ARE LOOSE ON THE ACCESSORS: this library treats
   structurally malformed input as a DOCUMENTED typed-ex-info outcome, not as
   a precondition violation — tools.agents.openai-test pins `{}`,
   `{\"output\" \"not-an-array\"}`, `{\"choices\" []}`, `(write-json (fn []))`
   etc. as legal calls with a specific :type in ex-data. A tight :args spec
   would convert those documented throws into spec errors under instrument.
   The well-formed success shapes live in the ::oai/responses-response /
   ::oai/completion-response DATA specs instead, for callers who want to
   assert them explicitly.

   NOTE: s/def and s/fdef are 2-arg macros with no docstring slot, so the
   explanatory comments sit ABOVE each form."
  (:require [clojure.spec.alpha :as s]
            [tools.agents.openai :as oai]))

;; ---------------------------------------------------------------------------
;; Wire-shaped (string-keyed) helper — NOT s/keys, see ns docstring.
;; ---------------------------------------------------------------------------

(defn- has-key
  "True when map m has STRING key k with a value satisfying pred. The building
   block every wire-shaped spec below is made of, since s/keys cannot see
   string keys at all."
  [k pred]
  (fn [m] (and (map? m) (contains? m k) (pred (get m k)))))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

;; Exactly what read-json can produce: objects become STRING-keyed maps,
;; arrays become vectors, numbers stay numbers, and "null" becomes nil.
;; Keywords never appear on the decode side (they are an encode-only input).
;; Recursive by keyword reference, which spec resolves lazily at check time.
(s/def ::oai/json-value
  (s/or :null   nil?
        :bool   boolean?
        :number number?
        :string string?
        :array  (s/coll-of ::oai/json-value :kind vector?)
        :object (s/map-of string? ::oai/json-value)))

;; :args is `any?` on purpose, and this is NOT a dodge: write-json's documented
;; contract is "anything in, typed :json-encode-error out" — openai_test.cljc
;; pins `(write-json (fn []))` and `(write-json {"max_output_tokens" 2/3})` as
;; legal calls that must throw tools.agents.openai/json-encode-error. Narrowing
;; :args to the encodable value set would replace those documented throws with
;; spec errors under instrument. :ret carries the real information.
(s/fdef oai/write-json
  :args (s/cat :v any?)
  :ret string?)

(s/fdef oai/read-json
  :args (s/cat :s string?)
  :ret ::oai/json-value)

;; ---------------------------------------------------------------------------
;; Credentials / client (genuinely keyword-keyed — ordinary s/keys applies)
;; ---------------------------------------------------------------------------

(s/def ::oai/api-key string?)
(s/def ::oai/organization string?)
(s/def ::oai/project string?)
(s/def ::oai/base-url string?)

;; INPUT domain only — deliberately just `number?`, not a non-negative int.
;; `client` CLAMPS a negative to 0 and falls back to default-max-retries for a
;; non-number, and openai_test.cljc pins `(client {… :max-retries -3})` => 0 as
;; a legal call. A `(s/and int? #(>= % 0))` here (the sibling library's
;; spelling, where nothing clamps) would turn that pinned call into a spec
;; error under instrument. The post-resolution invariant is asserted on
;; ::oai/resolved-client instead, where it actually holds.
(s/def ::oai/max-retries number?)

;; The map passed to `client` / `resolve-credentials`. Every key is optional:
;; :api-key falls back to OPENAI_API_KEY (or throws :missing-credentials at
;; call time — a runtime concern resolve-credentials owns, not a static shape
;; concern), :organization/:project to OPENAI_ORG_ID/OPENAI_PROJECT_ID,
;; :base-url to OPENAI_BASE_URL then default-base-url, :max-retries to
;; default-max-retries.
(s/def ::oai/client-opts
  (s/keys :opt-un [::oai/api-key ::oai/organization ::oai/project
                   ::oai/base-url ::oai/max-retries]))

;; resolve-credentials' return: exactly {:api-key s}. Unlike the Anthropic
;; sibling there is no api-key/auth-token split — every OpenAI request is
;; `Authorization: Bearer <api-key>`, so no xor predicate is needed here.
(s/def ::oai/resolved-credentials
  (s/keys :req-un [::oai/api-key]))

;; The map `client` RETURNS. Credential resolution and :max-retries clamping
;; have both already run by this point, hence the nat-int? predicate that
;; ::oai/max-retries itself must not carry. :organization/:project are absent
;; (never present-and-empty) when unset — the SDK's Omit() behavior.
;;
;; The predicate sits AFTER s/keys deliberately: s/and threads each spec's
;; CONFORMED value into the next, and s/keys conforms as identity, so the
;; predicate still sees the real map (the trap tools.agents.anthropic.spec
;; documents for s/or, which does NOT conform as identity).
(s/def ::oai/resolved-client
  (s/and (s/keys :req-un [::oai/api-key ::oai/base-url ::oai/max-retries]
                 :opt-un [::oai/organization ::oai/project])
         #(nat-int? (:max-retries %))))

(s/fdef oai/resolve-credentials
  ;; getenv-fn is `ifn?`, not `s/fspec`: conforming an fspec generatively
  ;; exercises the function and needs org.clojure/test.check, which this
  ;; project does not depend on.
  :args (s/cat :opts ::oai/client-opts :getenv-fn ifn?)
  :ret ::oai/resolved-credentials)

(s/fdef oai/client
  :args (s/cat :opts (s/? ::oai/client-opts))
  :ret ::oai/resolved-client)

;; ---------------------------------------------------------------------------
;; Retry policy
;; ---------------------------------------------------------------------------

;; A response header map as the three runtime leaves actually deliver it, per
;; `header-value`'s own docstring: nil or a map whose keys may be strings
;; (clz keeps the wire casing "Retry-After"; java.net.http and
;; babashka.http-client lower-case theirs) or keywords, and whose values are a
;; single string or a vector of strings for a multi-value header.
(s/def ::oai/http-headers
  (s/nilable
    (s/map-of (s/or :string string? :keyword keyword?)
              (s/or :single string? :multi (s/coll-of string? :kind vector?)))))

;; nilable status: retryable-status?/should-retry? guard `(and status …)`
;; explicitly, and post-json! can reach them with a nil :status from a
;; response map that carried none. openai_test.cljc pins `(retryable-status?
;; nil)` => false.
(s/def ::oai/http-status (s/nilable int?))

(s/fdef oai/retryable-status?
  :args (s/cat :status ::oai/http-status)
  :ret boolean?)

;; :ret is (s/nilable int?) and NOT nat-int?: an HTTP-date already in the past
;; legitimately yields a NEGATIVE delta (openai_test.cljc pins -60000), which
;; the caller then filters with the SDK's `0 < v <= MAX_RETRY_AFTER_DELAY`
;; gate. nil means "unspecified / unparseable".
(s/fdef oai/parse-retry-after-ms
  :args (s/cat :headers ::oai/http-headers :now-ms int?)
  :ret (s/nilable int?))

(s/fdef oai/should-retry?
  :args (s/cat :status ::oai/http-status :headers ::oai/http-headers :now-ms int?)
  :ret boolean?)

;; retries-taken is nat-int?: the SDK derives it as
;; `max_retries - remaining_retries` and post-json!'s loop starts it at 0.
;; rand-fn (4-arity only) must return a double in [0.0, 1.0); `ifn?` for the
;; same test.check reason as getenv-fn above.
(s/fdef oai/retry-delay-ms
  :args (s/cat :retries-taken nat-int?
               :headers ::oai/http-headers
               :now-ms int?
               :rand-fn (s/? ifn?))
  :ret nat-int?)

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

;; Every :type keyword tools.agents.openai itself can throw — the error-hierarchy
;; tables in README, cross-checked against every ex-info in openai.cljc. A
;; closed set on purpose: an ex-data :type this library never documents
;; throwing is exactly the case worth catching, not silently accepting.
;; NOTE the -error suffix on the status-derived types (bad-request-error, …)
;; which the Anthropic sibling's keywords do NOT carry, and that :conflict-error
;; exists here but not there (Anthropic's SDK has no ConflictError).
(s/def ::oai/error-type
  #{:tools.agents.openai/bad-request-error
    :tools.agents.openai/authentication-error
    :tools.agents.openai/permission-denied-error
    :tools.agents.openai/not-found-error
    :tools.agents.openai/conflict-error
    :tools.agents.openai/unprocessable-entity-error
    :tools.agents.openai/rate-limit-error
    :tools.agents.openai/internal-server-error
    :tools.agents.openai/api-status-error
    :tools.agents.openai/api-connection-error
    :tools.agents.openai/json-encode-error
    :tools.agents.openai/json-parse-error
    :tools.agents.openai/missing-credentials
    :tools.agents.openai/streaming-unsupported
    :tools.agents.openai/tls-port-unsupported
    :tools.agents.openai/invalid-response
    :tools.agents.openai/invalid-content-shape})

(s/def ::oai/type ::oai/error-type)
(s/def ::oai/status (s/nilable int?))
(s/def ::oai/body (s/nilable string?))
;; Error-path only, and only from the two resource methods — a successful call
;; returns the decoded response verbatim with nothing of ours added (unlike
;; openai-python, which also exposes retries_taken on success).
(s/def ::oai/retries-taken nat-int?)

;; Shape of every ex-info this library throws: (s/valid? ::oai/ex-data (ex-data e)).
;; :status/:body are present-but-nil on the accessor and connection errors and
;; absent entirely on the json-codec and missing-credentials throws, so both are
;; :opt-un rather than :req-un.
(s/def ::oai/ex-data
  (s/keys :req-un [::oai/type] :opt-un [::oai/status ::oai/body ::oai/retries-taken]))

;; ---------------------------------------------------------------------------
;; Request map (wire — string-keyed OR keyword-keyed) + the resource methods
;; ---------------------------------------------------------------------------

;; EITHER-KEYED on purpose, and this is the one place this library's spec must
;; NOT follow tools.agents.anthropic.spec's ::request-map. write-json encodes map
;; keys via `name`, verbatim, so `{"model" "gpt-5.5" …}` and
;; `{:model "gpt-5.5" …}` are equally valid outbound requests — the README
;; parity table documents it and openai_test.cljc:449
;; (stream-true-rejected-with-keyword-key-too) pins the keyword spelling. The
;; sibling's `(has-key "model" string?)` cannot see `:model` at all and rejects
;; that pinned call outright — verified empirically before this spec was
;; written:
;;   {:model "m", :input "hi", :stream true} - failed: (has-key "model" string?)
;;
;; "model" is the only required key. The endpoint-specific companion
;; ("input" for /responses, "messages" for /chat/completions) is deliberately
;; NOT required: everything past `model` is passed through unvalidated, and a
;; gateway may legitimately fill in the rest.
(s/def ::oai/request-map
  (s/and map? #(string? (or (get % "model") (get % :model)))))

;; :client is `map?`, NOT ::oai/resolved-client — the same
;; documented-typed-throw rule as the accessors above, and pinned:
;; openai_test.cljc:436 (hand-built-client-without-api-key-is-rejected-per-fn)
;; calls both resource methods with `{:base-url "http://127.0.0.1:1/v1"}` — no
;; :api-key at all — and requires a catchable :missing-credentials ex-info.
;; Requiring ::oai/resolved-client here would turn that pinned call into a spec
;; error under instrument. Assert ::oai/resolved-client on the value `client`
;; RETURNS instead, which is where the invariant actually holds.
;;
;; No :ret. Both methods return `(read-json body)` verbatim, so the only
;; verifiable :ret would be ::oai/json-value — and checking it at all requires
;; a real network round-trip, which this spec run does not make.
(s/fdef oai/responses-create
  :args (s/cat :client map? :request ::oai/request-map))

(s/fdef oai/chat-completions-create
  :args (s/cat :client map? :request ::oai/request-map))

;; ---------------------------------------------------------------------------
;; Responses / Chat Completions response shapes (wire — string-keyed)
;; ---------------------------------------------------------------------------

;; The well-formed success shape output-text reads. Deliberately NOT used as
;; output-text's :args spec — see the ns docstring; anything short of this is a
;; documented typed throw, not an illegal call.
(s/def ::oai/responses-response
  (s/and map? (has-key "output" vector?)))

;; One element of a Chat Completions "choices" array, as completion-text reads
;; it. "content" is intentionally unconstrained here: it is a string, JSON null
;; (tool-call responses), or ABSENT entirely (refusal-only messages) — all
;; three are real, documented, correctly-handled shapes.
(s/def ::oai/choice
  (s/and map? (has-key "message" map?)))

(s/def ::oai/completion-response
  (s/and map?
         (has-key "choices" #(and (vector? %) (seq %)))
         #(every? (partial s/valid? ::oai/choice) (get % "choices"))))

;; :ret string? — never nil: output-text returns "" for a reasoning-only or
;; refusal-only response, the documented contract of Python's
;; Response.output_text, and the deliberate divergence from
;; tools.agents.anthropic/output-text (which throws instead).
(s/fdef oai/output-text
  :args (s/cat :response map?)
  :ret string?)

;; :ret is nilable: "content" being JSON null on a tool-call/refusal response
;; yields nil, matching Python's `.content` being None there.
(s/fdef oai/completion-text
  :args (s/cat :completion map?)
  :ret (s/nilable string?))

;; ---------------------------------------------------------------------------
;; Message list (wire — string-keyed)
;; ---------------------------------------------------------------------------

;; The four add-*-message helpers produce exactly these four roles.
;; Chat Completions takes the vector as "messages"; the Responses API takes the
;; same shape as "input".
(s/def ::oai/message-map
  (s/and map?
         (has-key "role" #{"user" "assistant" "developer" "system"})
         #(contains? % "content")))

(s/def ::oai/messages-vector
  (s/coll-of ::oai/message-map :kind vector?))

;; The four helpers share one arg shape:
;;
;;  :messages — (s/nilable coll?). The body is `(conj (vec messages) …)`, so any
;;    seqable works and nil yields []. Note nothing in the repo actually PASSES
;;    nil (every test/example passes [] or a vector); the nilability is read off
;;    that defensive `vec` call.
;;
;;  :text — ::oai/json-value, not string?. The param is named `text` and every
;;    README/example/test call passes a string, but the helper only conj's the
;;    value into {"role" … "content" text}, and both APIs accept an ARRAY of
;;    content parts there for multimodal input. `string?` would reject that
;;    legitimate call under instrument; ::oai/json-value keeps it while still
;;    rejecting a genuinely unencodable value (a fn, an object). Edge: its
;;    :array branch is `:kind vector?`, so a lazy-seq of content parts fails
;;    even though write-json would encode it — pass a vector.
;;
;;  :ret — (s/coll-of map? :kind vector?), deliberately NOT
;;    ::oai/messages-vector: `(add-user-message [{"role" "banana"}] "hi")` is a
;;    legal call (the helper appends without inspecting what is already there)
;;    whose result would fail ::oai/messages-vector on the PRE-EXISTING element,
;;    blaming the helper for its caller's input.

(s/fdef oai/add-user-message
  :args (s/cat :messages (s/nilable coll?) :text ::oai/json-value)
  :ret (s/coll-of map? :kind vector?))

(s/fdef oai/add-assistant-message
  :args (s/cat :messages (s/nilable coll?) :text ::oai/json-value)
  :ret (s/coll-of map? :kind vector?))

(s/fdef oai/add-developer-message
  :args (s/cat :messages (s/nilable coll?) :text ::oai/json-value)
  :ret (s/coll-of map? :kind vector?))

(s/fdef oai/add-system-message
  :args (s/cat :messages (s/nilable coll?) :text ::oai/json-value)
  :ret (s/coll-of map? :kind vector?))
