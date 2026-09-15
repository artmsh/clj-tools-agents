(ns tools.agents.retry
  "The one retry loop every client in this repo runs its requests through.

   `with-retries` owns only the loop's skeleton: the attempt counter, the
   :max-retries budget, sleep-then-retry, and the credential 401 retry that
   happens once per call OUTSIDE the budget and without sleeping. Everything
   that follows a vendor SDK is passed in as a fn: which outcomes are
   retryable, the backoff/jitter formula and server retry headers, what a
   401 does to the credential, and the error a spent budget turns into. The
   helper never interprets a delay (anthropic sleeps in seconds, openai and
   gemini in milliseconds): it hands `:delay`'s value straight to `:sleep!`.

   Pure clojure.core plus a try/catch, identical on both runtimes. It lives
   here, not in tools.agents.http, because that namespace performs exactly
   one exchange, and because a retry loop that is not an HTTP request (a
   token exchange inside a credential source) uses it too.")

(defn- default-finish [outcome]
  (if (contains? outcome :error)
    (throw (:error outcome))
    (:response outcome)))

(defn with-retries
  "Run one logical call: attempts until an outcome is neither an auth retry
   nor a retry within budget, then return `(finish outcome)`.

   An OUTCOME is a map describing one attempt:
     {:response r}  `attempt` returned r (any value, a non-2xx response too)
     {:error e}     `attempt` threw e (and `rethrow?` said to keep it)
   plus :prepared (what `prepare` returned), :retries-taken (retries spent
   before this attempt) and :auth-retried? (the 401 retry already happened).

   opts:
     :max-retries      retries after the first attempt; required, >= 0.
     :attempt          (fn [prepared]) -> response; one exchange, may throw.
                       Required.
     :prepare          (fn [retries-taken]) -> prepared, called before EVERY
                       attempt and OUTSIDE the try, so its exceptions (a
                       token fetch failure) propagate as-is and are never
                       retried. Default: returns nil.
     :rethrow?         (fn [exception]) -> truthy to propagate an attempt's
                       exception at once instead of making it an {:error}
                       outcome (an already-typed permanent error). Default:
                       none rethrown.
     :unauthorized?    (fn [outcome]) -> truthy for a 401. Default: never.
     :on-unauthorized  (fn [outcome]) -> truthy when the credential that
                       attempt sent was invalidated, so an immediate retry
                       may carry a fresh one. Called on the first 401 only,
                       unless :invalidate-every-401? is true. A truthy
                       return on the first 401 retries at once: no sleep,
                       :retries-taken unchanged. Default: absent, no 401
                       retry.
     :invalidate-every-401?
                       true to call :on-unauthorized on a 401 after the
                       auth retry as well (its result is then ignored), so a
                       token rejected twice is not reused by the next call.
     :retryable?       (fn [outcome]) -> truthy to retry, consulted only
                       while :retries-taken < :max-retries and after the 401
                       step declined. Default: never.
     :delay            (fn [outcome]) -> value passed to :sleep! before the
                       retry. Required when :retryable? can be truthy.
     :sleep!           (fn [delay]). Required when :retryable? can be truthy.
                       Pass a fn that dereferences the client's rebindable
                       sleep var at call time (e.g. #(*sleep-fn* %)).
     :finish           (fn [outcome]) -> the call's value, or throws the
                       client's typed error. Called for the settled outcome:
                       a success, a non-retryable failure, or the one that
                       spent the budget. Default: rethrow :error, else
                       return :response."
  [{:keys [max-retries prepare attempt rethrow? unauthorized? on-unauthorized
           invalidate-every-401? retryable? sleep! finish]
    delay-fn :delay
    :or   {prepare (constantly nil) rethrow? (constantly false)
           unauthorized? (constantly false) retryable? (constantly false)
           finish default-finish}}]
  (loop [retries-taken 0
         auth-retried? false]
    (let [prepared (prepare retries-taken)
          outcome  (merge (try {:response (attempt prepared)}
                               (catch Exception e
                                 (if (rethrow? e) (throw e) {:error e})))
                          {:prepared prepared :retries-taken retries-taken :auth-retried? auth-retried?})]
      (cond
        (and on-unauthorized
             (unauthorized? outcome)
             (if auth-retried?
               (do (when invalidate-every-401? (on-unauthorized outcome)) false)
               (on-unauthorized outcome)))
        (recur retries-taken true)

        (and (< retries-taken max-retries) (retryable? outcome))
        (do (sleep! (delay-fn outcome))
            (recur (inc retries-taken) auth-retried?))

        :else
        (finish outcome)))))
