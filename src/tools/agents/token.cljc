(ns tools.agents.token
  "Refreshable credentials shared by every client: the `TokenSource` protocol
   and `token-cache`, a single-flight cache over a user-supplied fetch fn.

   A client built with `:credential-source <TokenSource>` asks the source for
   a token before EVERY request attempt (`token!`). On a 401 it calls
   `invalidate!` with the token that attempt sent and, when that returns
   true, retries once outside its :max-retries budget. Static keys
   (:api-key, :auth-token) never take that path.

   EXTENSION POINT (#35-#38): workload identity federation, profile files and
   callable keys are all constructors that return a TokenSource: usually a
   `token-cache` whose :fetch! performs the provider's token exchange or
   file read, or a custom `reify` for sources with other semantics (e.g. a
   fn re-invoked per request whose invalidate! returns false). Clients depend
   only on this protocol, never on a concrete source.

   Runs on JVM Clojure and Babashka (promise, locking, reify of a protocol).")

(defprotocol TokenSource
  (-token [source]
    "Return a token String valid for one request attempt, fetching if needed.")
  (-invalidate [source used-token]
    "Drop the cached token after the server rejected `used-token` (nil = drop
     whatever is cached). Return true when a later -token call may return a
     different token, i.e. when retrying is worthwhile."))

(defn token-source?
  "True when x satisfies TokenSource and can be passed as :credential-source."
  [x]
  (satisfies? TokenSource x))

(defn token!
  "A valid token String from source, refreshing it when needed."
  [source]
  (-token source))

(defn invalidate!
  "Tell source the server rejected a token. With `used-token`, the cache is
   cleared only if it still holds that token, so concurrent 401s on the same
   stale token trigger one refresh, not one per request. Returns true when a
   retry may get a different token."
  ([source] (-invalidate source nil))
  ([source used-token] (-invalidate source used-token)))

(def default-refresh-skew-ms
  "Refresh this long before :expires-at. Capped per token at half its
   lifetime (openai-python's `expires_in - min(buffer, expires_in/2)`), so a
   short-lived token is not refetched on every call."
  60000)

(defn- invalid-fetch-result! [msg]
  ;; ex-data never carries the fetch result: it holds the token.
  (throw (ex-info (str "tools.agents.token/token-cache: :fetch! " msg)
                  {:type :tools.agents.token/invalid-fetch-result})))

(defn- check-fetch-result [result]
  (when-not (map? result)
    (invalid-fetch-result! "must return a map {:token String :expires-at epoch-ms-or-nil}"))
  (let [{:keys [token expires-at]} result]
    (when-not (and (string? token) (seq token))
      (invalid-fetch-result! "returned no :token (a non-empty String)"))
    (when-not (or (nil? expires-at) (integer? expires-at))
      (invalid-fetch-result! "returned a non-integer :expires-at (epoch millis or nil)"))
    result))

(defn- refresh-at
  "Epoch ms after which the token must be refetched, or nil for never."
  [expires-at fetched-at skew-ms]
  (when expires-at
    (let [lifetime (max 0 (- expires-at fetched-at))]
      (- expires-at (min skew-ms (quot lifetime 2))))))

(defn- unwrap [outcome]
  (if (contains? outcome :error)
    (throw (:error outcome))
    (:token outcome)))

(defn token-cache
  "A TokenSource caching the result of `fetch!`.

   opts:
     :fetch!          (fn [] {:token String :expires-at epoch-ms-or-nil}),
                      required. nil :expires-at caches the token until
                      invalidate!. Called with no lock held.
     :refresh-skew-ms refresh this many ms before :expires-at, capped at half
                      the token's lifetime; default `default-refresh-skew-ms`
     :now-ms          clock, (fn [] epoch-ms); default System/currentTimeMillis

   token! returns the cached token while fresh. Otherwise exactly one caller
   runs fetch!; concurrent callers wait on that same in-flight fetch. A
   failed fetch throws its exception to the fetching caller and every waiter,
   leaves no in-flight state behind, and the next token! fetches again.
   A malformed fetch result throws :tools.agents.token/invalid-fetch-result.

   invalidate! clears the cached token (compare-and-clear with a used token)
   and returns true."
  [{:keys [fetch! refresh-skew-ms now-ms]
    :or   {refresh-skew-ms default-refresh-skew-ms
           now-ms          #(System/currentTimeMillis)}}]
  (when-not (ifn? fetch!)
    (throw (ex-info "tools.agents.token/token-cache: :fetch! must be a function"
                    {:type :tools.agents.token/invalid-options})))
  (when-not (and (integer? refresh-skew-ms) (>= refresh-skew-ms 0))
    (throw (ex-info (str "tools.agents.token/token-cache: :refresh-skew-ms must be a non-negative integer, got: "
                         (pr-str refresh-skew-ms))
                    {:type :tools.agents.token/invalid-options})))
  (let [lock  (Object.)
        ;; {:token :refresh-at :inflight promise}; read and written under lock.
        state (atom {})]
    (reify TokenSource
      (-token [_]
        (let [[role x] (locking lock
                         (let [{:keys [token inflight] :as s} @state
                               ra (:refresh-at s)]
                           (cond
                             (and token (or (nil? ra) (< (now-ms) ra))) [:cached token]
                             inflight                                    [:wait inflight]
                             :else (let [p (promise)]
                                     (swap! state assoc :inflight p)
                                     [:fetch p]))))]
          (case role
            :cached x
            :wait   (unwrap @x)
            :fetch  (let [p       x
                          outcome (atom {:error (ex-info "tools.agents.token/token-cache: fetch aborted"
                                                         {:type :tools.agents.token/fetch-aborted})})]
                      (try
                        (let [{:keys [token expires-at]} (check-fetch-result (fetch!))]
                          (reset! outcome {:token token :expires-at expires-at}))
                        (catch Throwable e
                          (reset! outcome {:error e}))
                        (finally
                          (let [o @outcome]
                            (locking lock
                              (swap! state (fn [s]
                                             (cond-> (dissoc s :inflight)
                                               (contains? o :token)
                                               (assoc :token (:token o)
                                                      :refresh-at (refresh-at (:expires-at o) (now-ms)
                                                                              refresh-skew-ms)))))))
                          (deliver p @outcome)))
                      (unwrap @outcome)))))
      (-invalidate [_ used-token]
        (locking lock
          (when (or (nil? used-token) (= used-token (:token @state)))
            (swap! state dissoc :token :refresh-at)))
        true))))
