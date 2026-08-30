(ns tools.agents.anthropic.visualize.specs.alpha
  "Inferred-spec cache for tools.agents.anthropic.visualize — a SECOND file
   because the skill's `.spec/` layout is one file per SOURCE namespace
   (`<ns>.specs.alpha` on the standard munged path), and visualize.cljc is a
   source namespace of its own, not part of tools.agents.anthropic.

   Keys are registered under the source namespace `::viz/*` =>
   :tools.agents.anthropic.visualize/*, so nothing here can collide with the
   shipped :tools.agents.anthropic.spec/* keys even where the NAME repeats.
   That repetition is real and worth knowing about: `:tools.agents.anthropic
   .spec/type` is the closed set of thrown-error keywords, while
   `::viz/type` below is a content-block type string like \"text\". They
   never interfere because `s/keys :req-un` resolves the fully-qualified key
   it is handed and only maps it onto the unqualified lookup — but writing
   bare `::type` in either file would silently reach for the wrong one.

   NILABILITY HERE IS OBSERVED, NOT GUESSED. `parse-response` reads its
   fields with `(get response k default)`, whose default fires only when the
   key is ABSENT — a key present with an explicit JSON `null` decodes to nil
   and passes straight through. Verified directly: `(:role (parse-response
   {}))` is \"unknown\" but `(:role (parse-response {\"role\" nil}))` is nil;
   `:model`/`:stop-reason` are nil whenever absent. The project's own tests
   pin the same shape (visualize_test.cljc asserts `(nil? (:model parsed))`,
   and renders a message with `:model nil` / `:stop-reason nil`), so the
   nilable specs below match tested behavior rather than inference."
  (:require [clojure.spec.alpha :as s]
            [tools.agents.anthropic.visualize :as viz]
            ;; registers :tools.agents.anthropic/json-value, reused by format-json
            [tools.agents.anthropic.specs.alpha]
            [tools.agents.anthropic :as a]))

;; ---------------------------------------------------------------------------
;; parse-content-block / parse-response output shapes
;; ---------------------------------------------------------------------------

;; A content block's "type" string ("text"/"tool_use"/...), or "unknown" for a
;; non-map block. Nilable strictly because of the null-vs-absent gap above:
;; `{"type" nil}` yields :type nil, not the "unknown" default.
(s/def ::viz/type (s/nilable string?))

;; Always a map: the block itself, or a synthesized {"text" s} / {"raw" s}.
(s/def ::viz/data map?)

(s/def ::viz/parsed-block (s/keys :req-un [::viz/type ::viz/data]))

(s/def ::viz/role (s/nilable string?))
(s/def ::viz/content (s/coll-of ::viz/parsed-block :kind vector?))
(s/def ::viz/model (s/nilable string?))
(s/def ::viz/stop-reason (s/nilable string?))
(s/def ::viz/usage map?)

;; parse-response's return, and therefore render-message/visualize-message's
;; argument. All five keys are :req-un — parse-response always emits all five,
;; which is what "as produced by parse-response" means in those docstrings.
(s/def ::viz/parsed-message
  (s/keys :req-un [::viz/role ::viz/content ::viz/model ::viz/stop-reason ::viz/usage]))

;; parse-content-block is TOTAL by construction — a map/string/else `cond`
;; with an explicit :else arm that swallows anything (verified: `(parse-
;; content-block 42)` => {:type "unknown" :data {"raw" "42"}}). `any?` is the
;; honest domain of a deliberately total function, not a widening to dodge an
;; unknown contract.
(s/fdef viz/parse-content-block
  :args (s/cat :block any?)
  :ret ::viz/parsed-block)

(s/fdef viz/parse-response
  :args (s/cat :response map?)
  :ret ::viz/parsed-message)

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(s/def ::viz/color? boolean?)
(s/def ::viz/auto-show? boolean?)

(s/def ::viz/render-opts (s/keys :opt-un [::viz/color?]))

;; max-length feeds `truncate`, which does `(subs s 0 max-len)` — a negative
;; value throws StringIndexOutOfBounds, so the domain is nat-int?, not int?.
(s/fdef viz/format-json
  :args (s/cat :data ::a/json-value :max-length (s/? nat-int?))
  :ret string?)

;; Pure: builds and returns the panel string, prints nothing.
(s/fdef viz/render-message
  :args (s/cat :message ::viz/parsed-message :opts (s/? ::viz/render-opts))
  :ret string?)

;; The printing half — `println`'s own nil return, not a passthrough.
(s/fdef viz/visualize-message
  :args (s/cat :message ::viz/parsed-message :opts (s/? ::viz/render-opts))
  :ret nil?)

(s/fdef viz/show-response
  :args (s/cat :response map? :opts (s/? ::viz/render-opts))
  :ret nil?)

;; ---------------------------------------------------------------------------
;; Capture session
;; ---------------------------------------------------------------------------

;; An atom holding the captured-response vector. Specced as IDeref rather than
;; `(instance? clojure.lang.Atom %)` so the predicate stays true of any
;; deref-able the docstring's "a plain map holding an atom" contract admits;
;; the atom's CONTENTS are deliberately not asserted here, since a spec that
;; derefs would make validity time-varying.
(s/def ::viz/responses #(instance? clojure.lang.IDeref %))

(s/def ::viz/visualizer
  (s/keys :req-un [::viz/responses ::viz/auto-show? ::viz/color?]))

(s/def ::viz/visualizer-opts (s/keys :opt-un [::viz/auto-show? ::viz/color?]))

(s/fdef viz/visualizer
  :args (s/cat :opts (s/? ::viz/visualizer-opts))
  :ret ::viz/visualizer)

;; capture!/show-all!/clear! all return viz itself — a threading contract the
;; docstrings state outright ("Returns viz").
(s/fdef viz/capture!
  :args (s/cat :viz ::viz/visualizer :response map?)
  :ret ::viz/visualizer)

(s/fdef viz/show-all!
  :args (s/cat :viz ::viz/visualizer)
  :ret ::viz/visualizer)

(s/fdef viz/clear!
  :args (s/cat :viz ::viz/visualizer)
  :ret ::viz/visualizer)
