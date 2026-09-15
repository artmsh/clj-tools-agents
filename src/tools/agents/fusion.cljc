(ns tools.agents.fusion
  "Call several LLM provider specs concurrently and fuse their successful
   text responses into one deterministic answer map.

   Not a provider itself — orchestration across `tools.agents.anthropic` and
   `tools.agents.openai` (or any `:call` hook standing in for a provider, e.g.
   in tests). Migrated from clz's embedded `clz.llm` namespace, where this
   module originated as a demonstration of clz calling out to this library."
  (:require [tools.agents.anthropic :as a]
            [tools.agents.openai :as o]))

(defn- provider-id [spec]
  (or (:id spec) (:provider spec)))

(defn- provider-options [spec]
  (let [opts {}
        opts (if (contains? spec :api-key) (assoc opts :api-key (:api-key spec)) opts)
        opts (if (contains? spec :auth-token) (assoc opts :auth-token (:auth-token spec)) opts)
        opts (if (contains? spec :credential-source) (assoc opts :credential-source (:credential-source spec)) opts)
        opts (if (contains? spec :base-url) (assoc opts :base-url (:base-url spec)) opts)
        opts (if (contains? spec :http) (assoc opts :http (:http spec)) opts)
        opts (if (contains? spec :json) (assoc opts :json (:json spec)) opts)]
    opts))

(defn- prompt-with-system [spec prompt]
  (if (:system spec)
    (str (:system spec) "\n\n" prompt)
    prompt))

(defn- ok-result [spec text response]
  {:id (provider-id spec)
   :provider (:provider spec)
   :status :ok
   :text text
   :response response})

(defn- error-result [spec err]
  {:id (provider-id spec)
   :provider (:provider spec)
   :status :error
   :error err})

(defn- call-openai [spec prompt]
  (let [client (o/client (provider-options spec))
        response (o/responses-create client
                   {"model" (:model spec)
                    "input" (prompt-with-system spec prompt)})]
    {:text (o/output-text response)
     :response response}))

(defn- call-anthropic [spec prompt]
  (let [client (a/client (provider-options spec))
        request (merge (if (:system spec) {"system" (:system spec)} {})
                       {"model" (:model spec)
                        "max_tokens" (or (:max-tokens spec) 1024)
                        "messages" [{"role" "user" "content" prompt}]})
        response (a/messages-create client request)]
    {:text (a/output-text response)
     :response response}))

(defn provider-call
  "Call one provider spec with `prompt` and normalize the outcome to
   {:id :provider :status (:ok|:error) :text :response} or
   {:id :provider :status :error :error}.

   `spec` needs :provider (:openai or :anthropic) plus :model and
   credentials, or a :call (fn [spec prompt] -> {:text :response}) hook that
   bypasses the network entirely — the shape tests and demos use."
  [spec prompt]
  (try
    (let [raw (if (:call spec)
                ((:call spec) spec prompt)
                (case (:provider spec)
                  :openai (call-openai spec prompt)
                  :anthropic (call-anthropic spec prompt)
                  (throw (ex-info (str "unsupported provider: " (:provider spec))
                                   {:type :tools.agents.fusion/unsupported-provider
                                    :provider (:provider spec)}))))]
      (ok-result spec (:text raw) (:response raw)))
    (catch Exception e
      (error-result spec e))))

(defn parallel
  "Call every spec in `provider-specs` concurrently against `prompt`, returning
   normalized results (see `provider-call`) in the same order as the input
   specs — not completion order."
  [provider-specs prompt]
  (let [futs (doall (map (fn [spec] (future (provider-call spec prompt))) provider-specs))]
    (vec (map deref futs))))

(defn- ok-result? [result]
  (= :ok (:status result)))

(defn- join-lines [texts]
  (when (seq texts)
    (reduce (fn [acc text] (str acc "\n" text)) (first texts) (rest texts))))

(defn fuse
  "Run `parallel` over `provider-specs` and join the successful texts into one
   newline-separated answer.

   Returns {:status (:ok if any provider succeeded, else :error)
            :answer joined text, or nil if every provider failed
            :texts vector of successful texts, in input-spec order
            :results every provider-call result, in input-spec order}."
  [provider-specs prompt]
  (let [results (parallel provider-specs prompt)
        successes (filter ok-result? results)
        texts (vec (map :text successes))]
    {:status (if (seq texts) :ok :error)
     :answer (join-lines texts)
     :texts texts
     :results results}))
