(ns live-check
  "Live smoke check against a real OpenAI-compatible endpoint. NOT part of the
   automated suite (`script/test-all.sh` is hermetic — mock servers only); this
   is the manual counterpart that proves the client works against a live
   gateway rather than only against a mock this repo wrote itself.

   Env:
     OPENAI_BASE_URL  API root INCLUDING /v1   (default http://omniroute.lan/v1)
     OPENAI_API_KEY   bearer token             (required)
     OPENAI_MODEL     model id to ask for      (default vag/inclusionai/ling-3.0-flash-free)

   Run (from the repo root):
     clojure -M script/live_check.clj
     bb script/live_check.clj

   Exits 1 if the Chat Completions call fails. The Responses-API probe is
   reported but not fatal: OpenAI-compatible gateways commonly implement only
   /v1/chat/completions, and a 404 there is information, not a client bug."
  (:require [tools.agents.openai :as oai]))

(defn- env [name default]
  (let [v (System/getenv name)]
    (if (seq v) v default)))

(defn -main [& _]
  (let [base-url (env "OPENAI_BASE_URL" "http://omniroute.lan/v1")
        model    (env "OPENAI_MODEL" "vag/inclusionai/ling-3.0-flash-free")
        client   (oai/client {:base-url base-url})
        prompt   "Reply with exactly one word: pong"]
    (println "base-url   :" base-url)
    (println "model      :" model)
    (println "max-retries:" (:max-retries client))
    (println)

    (println "── POST /chat/completions ─────────────────")
    (let [messages (-> []
                       (oai/add-developer-message "Answer with a single word, no punctuation.")
                       (oai/add-user-message prompt))
          ;; "stream" false is explicit on purpose: OmniRoute (and some other
          ;; OpenAI-compatible gateways) default `auto/*` model ids to SSE, and
          ;; a streamed body would reach this non-streaming client as
          ;; `data: {...}` chunks and fail to parse. Sending false is valid
          ;; OpenAI and a no-op against api.openai.com itself.
          resp     (oai/chat-completions-create
                     client {"model" model "stream" false "messages" messages "max_tokens" 256})
          text     (oai/completion-text resp)]
      (println "id         :" (get resp "id"))
      (println "model      :" (get resp "model"))
      (println "usage      :" (pr-str (get resp "usage")))
      (println "text       :" (pr-str text))
      ;; throw rather than exit: a non-zero exit code falls out of an
      ;; uncaught throw on both runtimes anyway.
      (when-not (string? text)
        (throw (ex-info "live-check: completion-text did not return a string"
                        {:text text}))))

    (println)
    (println "── POST /responses ────────────────────────")
    (try
      (let [resp (oai/responses-create client {"model" model "stream" false
                                               "input" prompt "max_output_tokens" 256})]
        (println "output-text:" (pr-str (oai/output-text resp))))
      (catch Exception e
        (println "not available on this endpoint (non-fatal):")
        (println "  " (:type (ex-data e)) "-" (ex-message e))))

    (println)
    (println "live check OK")))

;; bb runs the file top-to-bottom; `clojure -M <file>` does too. Neither
;; auto-invokes -main, so call it explicitly.
(-main)
