(ns tools.agents.mcp.examples-test
  "The two example servers, driven through a real client over the in-process
   loopback.

   `examples.mcp.weather` is the deliverable that verifies this library
   against the official 'Build an MCP server' tutorial: every assertion below
   is on the tutorial's own strings and shapes. `examples.mcp.everything` is
   the port of the reference everything server; the assertions there
   concentrate on the places where the port DIVERGES from the original,
   because those are the claims that could be wrong.

   Both servers take their impure inputs — the HTTP fetch, the clock, the
   sleep, the notification sink — as arguments, so every test here is exact
   rather than approximate, and runs on both runtimes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [examples.mcp.everything :as everything]
            [examples.mcp.weather :as weather]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.client :as client]
            [tools.agents.mcp.http :as http]
            [tools.agents.mcp.server :as server]))

;; ---------------------------------------------------------------------------
;; Loopback
;; ---------------------------------------------------------------------------

(defn connect
  "An MCP client speaking to `srv` through `server/handle` directly."
  ([srv] (connect srv nil))
  ([srv caps]
   (client/client
    {:name "examples-test" :version "1"
     :capabilities (or caps {"elicitation" {"url" {}} "sampling" {} "roots" {}})
     :input-handlers
     {"elicitation/create"
      (fn [req] (if (= "url" (get-in req ["params" "mode"]))
                  {"action" "accept"}
                  {"action" "accept" "content" {"name" "Ada" "check" true}}))
      "sampling/createMessage"
      (fn [_req] {"model" "test-model" "role" "assistant"
                  "content" {"type" "text" "text" "sampled reply"}})
      "roots/list" (fn [_req] {"roots" [{"uri" "file:///work" "name" "work"}]})}
     :send! (fn [req] (:response (server/handle srv req)))})))

;; ---------------------------------------------------------------------------
;; examples.mcp.weather — the build-server tutorial
;; ---------------------------------------------------------------------------

(def alerts-payload
  {"features"
   [{"properties" {"event" "Flood Warning" "areaDesc" "Sacramento County"
                   "severity" "Moderate" "description" "Rivers are high."
                   "instruction" "Avoid low crossings."}}
    ;; A feature missing every optional field, to exercise format_alert's
    ;; defaults.
    {"properties" {}}]})

(def points-payload
  {"properties" {"forecast" "https://api.weather.gov/gridpoints/TEST/1,2/forecast"}})

(defn periods [n]
  {"properties"
   {"periods" (mapv (fn [i] {"name" (str "Period " i) "temperature" (+ 50 i)
                             "temperatureUnit" "F" "windSpeed" "5 mph"
                             "windDirection" "NW" "detailedForecast" (str "Forecast " i)})
                    (range n))}})

(defn canned-fetch [responses]
  (fn [url]
    (some (fn [[pattern v]] (when (str/includes? url pattern) v)) responses)))

(def weather-fetch
  (canned-fetch [["/alerts/active" alerts-payload]
                 ["/points/" points-payload]
                 ["/forecast" (periods 7)]]))

(deftest format-alert-matches-the-tutorial-layout
  (is (= (str "\nEvent: Flood Warning"
              "\nArea: Sacramento County"
              "\nSeverity: Moderate"
              "\nDescription: Rivers are high."
              "\nInstructions: Avoid low crossings.\n")
         (weather/format-alert (first (get alerts-payload "features")))))
  (testing "every missing field falls back to the tutorial's own default"
    (let [s (weather/format-alert {"properties" {}})]
      (is (str/includes? s "Event: Unknown"))
      (is (str/includes? s "Description: No description available"))
      (is (str/includes? s "Instructions: No specific instructions provided")))))

(deftest get-alerts-covers-the-three-branches
  (is (str/includes? (weather/get-alerts weather-fetch "CA") "Event: Flood Warning"))
  (is (str/includes? (weather/get-alerts weather-fetch "CA") "\n---\n")
      "multiple alerts are separated as the tutorial separates them")
  (is (= "Unable to fetch alerts or no alerts found."
         (weather/get-alerts (constantly nil) "CA")))
  (is (= "Unable to fetch alerts or no alerts found."
         (weather/get-alerts (constantly {}) "CA")))
  (is (= "No active alerts for this state."
         (weather/get-alerts (constantly {"features" []}) "CA"))))

(deftest get-forecast-walks-points-then-forecast
  (let [text (weather/get-forecast weather-fetch 38.58 -121.49)]
    (is (str/includes? text "Period 0:"))
    (is (str/includes? text "Temperature: 50°F"))
    (is (str/includes? text "Wind: 5 mph NW"))
    (testing "only the first five periods, exactly as the tutorial slices them"
      (is (str/includes? text "Period 4:"))
      (is (not (str/includes? text "Period 5:"))))))

(deftest get-forecast-error-paths-mirror-make-nws-request
  (is (= "Unable to fetch forecast data for this location."
         (weather/get-forecast (constantly nil) 1 2)))
  (is (= "Unable to fetch detailed forecast."
         (weather/get-forecast (canned-fetch [["/points/" points-payload]]) 1 2))))

(deftest weather-server-exposes-exactly-the-tutorial-surface
  (let [c (connect (weather/weather-server weather-fetch))
        d (client/discover! c)]
    (is (= {"name" "weather" "version" "1.0.0"} (get-in d ["_meta" mcp/meta-server-info])))
    (is (= {"tools" {}} (get d "capabilities"))
        "the tutorial server has tools and nothing else")
    (let [tools (client/list-all-tools! c)]
      (is (= ["get_alerts" "get_forecast"] (mapv #(get % "name") tools)))
      (is (= "Get weather alerts for a US state." (get (first tools) "description")))
      (is (= "Get weather forecast for a location." (get (second tools) "description")))
      (is (= {"type" "object"
              "properties" {"state" {"type" "string"
                                     "description" "Two-letter US state code (e.g. CA, NY)"}}
              "required" ["state"]}
             (get (first tools) "inputSchema"))))
    (is (str/includes? (mcp/output-text (client/call-tool! c "get_alerts" {"state" "CA"}))
                       "Flood Warning"))
    (is (str/includes? (mcp/output-text (client/call-tool! c "get_forecast"
                                                           {"latitude" 38.58 "longitude" -121.49}))
                       "Period 0:"))))

;; ---------------------------------------------------------------------------
;; examples.mcp.everything
;; ---------------------------------------------------------------------------

(defn everything-fixture
  "A server plus the state and the notification sink it was built with, so a
   test can assert on both sides of a `touch-resource` call."
  ([] (everything-fixture nil))
  ([caps]
   (let [store (everything/new-store)
         sent (atom [])
         srv (everything/everything-server
              {:store store
               :notify! (fn [n] (swap! sent conj n) 1)
               :timestamp-fn (constantly "TIMESTAMP")
               :sleep-fn (fn [_ms] nil)})]
     {:server srv :store store :sent sent :client (connect srv caps)})))

(deftest everything-advertises-every-capability-it-implements
  (let [{:keys [client]} (everything-fixture)
        d (client/discover! client)]
    (is (= {"name" "everything" "version" "1.0.0" "title" "Everything Example Server"
            "description" "Exercises every capability of MCP revision 2026-07-28."}
           (get-in d ["_meta" mcp/meta-server-info])))
    (is (= {"tools" {} "prompts" {} "completions" {} "logging" {}
            "resources" {"subscribe" true "listChanged" true}}
           (get d "capabilities")))))

(deftest tools-that-map-straight-across-from-the-reference-server
  (let [{:keys [client]} (everything-fixture)]
    (is (= "Echo: hello" (mcp/output-text (client/call-tool! client "echo" {"message" "hello"}))))
    (is (= "The sum of 2 and 3 is 5."
           (mcp/output-text (client/call-tool! client "get-sum" {"a" 2 "b" 3}))))
    (testing "get-tiny-image returns the reference server's own PNG, verbatim"
      (let [content (get (client/call-tool! client "get-tiny-image" {}) "content")]
        (is (= ["text" "image" "text"] (mapv #(get % "type") content)))
        (is (= everything/mcp-tiny-image (get (second content) "data")))
        (is (= "image/png" (get (second content) "mimeType")))
        (is (str/starts-with? everything/mcp-tiny-image "iVBORw0KGgo")
            "a PNG signature, not an accidental truncation")))
    (testing "structured content is returned both ways"
      (let [r (client/call-tool! client "get-structured-content" {"location" "Chicago"})]
        (is (= {"temperature" 26 "conditions" "Sunny" "humidity" 45} (get r "structuredContent")))
        (is (= {"temperature" 26 "conditions" "Sunny" "humidity" 45}
               (mcp/read-json (mcp/output-text r))))))
    (testing "annotations carry audience and priority"
      (let [c (first (get (client/call-tool! client "get-annotated-message"
                                             {"messageType" "error"})
                          "content"))]
        (is (= 1.0 (get-in c ["annotations" "priority"])))
        (is (= ["user" "assistant"] (get-in c ["annotations" "audience"])))))
    (testing "resource links point at URIs the templates actually serve"
      (let [content (get (client/call-tool! client "get-resource-links" {"count" 3}) "content")
            links (filter #(= "resource_link" (get % "type")) content)]
        (is (= 3 (count links)))
        (doseq [l links]
          (is (map? (client/read-resource! client (get l "uri")))
              (str "unreadable link: " (get l "uri")))))))
  (testing "bad arguments are protocol errors, not results"
    (let [{:keys [client]} (everything-fixture)]
      (doseq [[t args] [["get-sum" {"a" "x" "b" 1}]
                        ["get-annotated-message" {"messageType" "nope"}]
                        ["get-structured-content" {"location" "Atlantis"}]
                        ["get-resource-links" {"count" 99}]
                        ["get-resource-reference" {"resourceType" "text" "resourceId" 0}]]]
        (let [e (try (client/call-tool! client t args) nil (catch Exception ex ex))]
          (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))) t))))))

(deftest generated-resources-are-a-pure-function-of-their-inputs
  ;; The reference server stamps `new Date()`; here the clock is injected, so
  ;; the bytes are assertable.
  (let [{:keys [client]} (everything-fixture)]
    (is (= "Resource 3: This is a plaintext resource created at TIMESTAMP"
           (get-in (client/read-resource! client (everything/text-resource-uri 3))
                   ["contents" 0 "text"])))
    (is (= "Resource 4: This is a base64 blob created at TIMESTAMP"
           (http/base64-decode
            (get-in (client/read-resource! client (everything/blob-resource-uri 4))
                    ["contents" 0 "blob"]))))
    (testing "a template serves ids far beyond the hundred that are listed"
      (is (= "Resource 9999: This is a plaintext resource created at TIMESTAMP"
             (get-in (client/read-resource! client (everything/text-resource-uri 9999))
                     ["contents" 0 "text"]))))
    (testing "a non-positive id is -32602, this revision's resource-not-found"
      (let [e (try (client/read-resource! client (everything/text-resource-uri 0))
                   nil (catch Exception ex ex))]
        (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))))))))

(deftest resources-paginate-but-tools-do-not
  ;; DIVERGENCE from a naive port: the reference server's PAGE_SIZE covers its
  ;; hundred resources only. A server whose purpose is to be explored must not
  ;; hide six of its sixteen tools behind a cursor.
  (let [{:keys [client]} (everything-fixture)
        page (client/list-resources! client)]
    (is (= 10 (count (get page "resources"))))
    (is (some? (get page "nextCursor")))
    (is (= 107 (count (client/list-all-resources! client)))
        "the reference server's seven documents plus this port's hundred numbered")
    (is (= 16 (count (get (client/list-tools! client) "tools"))))
    (is (nil? (get (client/list-tools! client) "nextCursor")))))

(deftest logging-is-per-request-not-per-session
  ;; DIVERGENCE: toggle-simulated-logging kept a per-session interval running.
  ;; logging/setLevel is gone and the level is a per-request _meta field, so a
  ;; cross-request toggle cannot exist.
  (let [{:keys [server]} (everything-fixture)
        call (fn [meta-extra]
               (server/handle
                server
                (mcp/request 1 "tools/call"
                             {"name" "emit-simulated-logs" "arguments" {}
                              "_meta" (mcp/request-meta
                                       (merge {:protocol-version mcp/latest-protocol-version
                                               :client-capabilities {}}
                                              meta-extra))})))]
    (testing "a request that did not opt in receives no notifications/message at all"
      (let [r (call nil)]
        (is (empty? (:notifications r)))
        (is (str/includes? (mcp/output-text (get-in r [:response "result"]))
                           "did not carry"))))
    (testing "a request that did opt in receives exactly the levels at or above it"
      (let [r (call {:log-level "error"})]
        (is (= ["error" "critical" "alert" "emergency"]
               (mapv #(get-in % ["params" "level"]) (:notifications r))))
        (is (str/includes? (mcp/output-text (get-in r [:response "result"]))
                           "at or above error"))))))

(deftest progress-is-reported-only-when-a-token-was-sent
  (let [{:keys [server]} (everything-fixture)
        call (fn [token]
               (server/handle
                server
                (mcp/request 1 "tools/call"
                             {"name" "trigger-long-running-operation"
                              "arguments" {"duration" 0 "steps" 3}
                              "_meta" (mcp/request-meta
                                       {:protocol-version mcp/latest-protocol-version
                                        :client-capabilities {}
                                        :progress-token token})})))]
    (is (empty? (:notifications (call nil))))
    (let [ns* (:notifications (call "p"))]
      (is (= 3 (count ns*)))
      (is (= [1 2 3] (mapv #(get-in % ["params" "progress"]) ns*)))
      (is (= 3 (get-in (first ns*) ["params" "total"]))))))

(deftest session-resources-became-server-minted-handles
  ;; DIVERGENCE: the reference server's demo://resource/session/<name> lived
  ;; for the lifetime of a connection. SEP-2567 deleted sessions, so the
  ;; server mints an explicit handle and the client passes it back.
  (let [{:keys [client store]} (everything-fixture)
        r (client/call-tool! client "register-resource" {"name" "notes" "text" "remember this"})
        link (first (filter #(= "resource_link" (get % "type")) (get r "content")))]
    (is (= "demo://resource/registered/r1" (get link "uri")))
    (is (= {:name "notes" :text "remember this"} (get-in @store [:resources "r1"])))
    (testing "and it reads back by URI, with no connection involved"
      (is (= "remember this"
             (get-in (client/read-resource! client (get link "uri")) ["contents" 0 "text"]))))
    (testing "an unknown handle is -32602"
      (let [e (try (client/read-resource! client (everything/registered-uri "nope"))
                   nil (catch Exception ex ex))]
        (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))))))))

(deftest touching-a-resource-pushes-to-the-subscription-sink
  ;; DIVERGENCE: toggle-subscriber-updates drove a per-session interval. Here
  ;; the tool bumps a version and the TRANSPORT fans the notification out to
  ;; whatever subscriptions/listen streams are open.
  (let [{:keys [client sent store]} (everything-fixture)]
    (client/call-tool! client "register-resource" {"name" "n" "text" "t"})
    (let [out (mcp/output-text (client/call-tool! client "touch-resource" {"handle" "r1"}))]
      (is (str/includes? out "version 2"))
      (is (str/includes? out "notified 1 subscription stream(s)")))
    (is (= [{"jsonrpc" "2.0" "method" "notifications/resources/updated"
             "params" {"uri" "demo://resource/registered/r1"}}]
           @sent))
    (is (= 2 (get-in @store [:versions "demo://resource/registered/r1"])))
    (testing "an unknown handle is refused before anything is notified"
      (reset! sent [])
      (let [e (try (client/call-tool! client "touch-resource" {"handle" "nope"})
                   nil (catch Exception ex ex))]
        (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))))
        (is (= [] @sent))))))

(deftest server-initiated-requests-became-input-required-results
  ;; DIVERGENCE, and the largest one: all four of these were server-to-client
  ;; JSON-RPC requests. Under MRTR the server answers and the CLIENT retries.
  (let [{:keys [client]} (everything-fixture)]
    (is (str/includes? (mcp/output-text (client/call-tool! client "trigger-elicitation-request" {}))
                       "Elicitation accept."))
    (is (= "URL elicitation accept for https://example.test."
           (mcp/output-text (client/call-tool! client "trigger-url-elicitation"
                                               {"url" "https://example.test"}))))
    (is (str/includes? (mcp/output-text (client/call-tool! client "trigger-sampling-request"
                                                           {"prompt" "hi"}))
                       "sampled reply"))
    (is (= [{"uri" "file:///work" "name" "work"}]
           (mcp/read-json (mcp/output-text (client/call-tool! client "get-roots-list" {})))))))

(deftest the-first-leg-of-each-mrtr-tool-is-a-well-formed-ask
  (let [{:keys [server]} (everything-fixture)
        ask (fn [tool args]
              (get-in (server/handle
                       server
                       (mcp/request 1 "tools/call"
                                    {"name" tool "arguments" args
                                     "_meta" (mcp/request-meta
                                              {:protocol-version mcp/latest-protocol-version
                                               :client-capabilities {"elicitation" {"url" {}}
                                                                     "sampling" {} "roots" {}}})}))
                      [:response "result"]))]
    (let [r (ask "trigger-elicitation-request" {})]
      (is (= "input_required" (get r "resultType")))
      (is (= "form" (get-in r ["inputRequests" "form" "params" "mode"])))
      (is (= everything/elicitation-schema
             (get-in r ["inputRequests" "form" "params" "requestedSchema"]))))
    (let [r (ask "trigger-url-elicitation" {"url" "https://x.test"})]
      (is (= "url" (get-in r ["inputRequests" "url" "params" "mode"])))
      (is (= "trigger-url-elicitation:https://x.test" (get r "requestState"))))
    (let [r (ask "trigger-sampling-request" {"prompt" "hi" "maxTokens" 7})]
      (is (= 7 (get-in r ["inputRequests" "sample" "params" "maxTokens"])))
      (is (= "You are a helpful test server."
             (get-in r ["inputRequests" "sample" "params" "systemPrompt"]))))))

(deftest capability-gated-tools-are-listed-and-refuse-at-call-time
  ;; DIVERGENCE: the reference server registered these tools only if the
  ;; connected client had declared the capability. SEP-2567 forbids a list
  ;; result that varies per connection, so they are always listed and the
  ;; refusal moved to the call — which also tells the client author WHY.
  (let [{:keys [client]} (everything-fixture {})
        names (set (mapv #(get % "name") (client/list-all-tools! client)))]
    (is (contains? names "trigger-elicitation-request"))
    (is (contains? names "trigger-sampling-request"))
    (is (contains? names "get-roots-list"))
    (doseq [[tool required] [["trigger-elicitation-request" {"elicitation" {}}]
                             ["trigger-url-elicitation" {"elicitation" {"url" {}}}]
                             ["trigger-sampling-request" {"sampling" {}}]
                             ["get-roots-list" {"roots" {}}]]]
      (let [e (try (client/call-tool! client tool {}) nil (catch Exception ex ex))]
        (is (= :tools.agents.mcp.error/missing-client-capability (:type (ex-data e))) tool)
        (is (= required (get (:data (ex-data e)) "requiredCapabilities")) tool))))
  (testing "url elicitation needs elicitation.url specifically, not merely elicitation"
    (let [{:keys [client]} (everything-fixture {"elicitation" {}})
          e (try (client/call-tool! client "trigger-url-elicitation" {}) nil (catch Exception ex ex))]
      (is (= {"elicitation" {"url" {}}} (get (:data (ex-data e)) "requiredCapabilities"))))))

(deftest prompts-and-completions
  (let [{:keys [client]} (everything-fixture)]
    (is (= "This is a simple prompt without arguments."
           (get-in (client/get-prompt! client "simple-prompt") ["messages" 0 "content" "text"])))
    (is (= "What's weather in Kyiv, Ukraine?"
           (get-in (client/get-prompt! client "args-prompt" {"city" "Kyiv" "state" "Ukraine"})
                   ["messages" 0 "content" "text"])))
    (is (= "What's weather in Kyiv?"
           (get-in (client/get-prompt! client "args-prompt" {"city" "Kyiv"})
                   ["messages" 0 "content" "text"])))
    (testing "resource-prompt embeds the resource it names"
      (let [m (client/get-prompt! client "resource-prompt"
                                  {"resourceType" "Text" "resourceId" "5"})]
        (is (= "demo://resource/dynamic/text/5"
               (get-in m ["messages" 1 "content" "resource" "uri"])))
        (is (= "Resource 5: This is a plaintext resource created at TIMESTAMP"
               (get-in m ["messages" 1 "content" "resource" "text"])))))
    (testing "completions narrow the second argument by the first"
      (is (= ["Engineering"]
             (get-in (client/complete! client (client/prompt-ref "completable-prompt")
                                       {"name" "department" "value" "Eng"})
                     ["completion" "values"])))
      (is (= ["Alice"]
             (get-in (client/complete! client (client/prompt-ref "completable-prompt")
                                       {"name" "name" "value" "A"}
                                       {"arguments" {"department" "Engineering"}})
                     ["completion" "values"])))
      (is (= []
             (get-in (client/complete! client (client/prompt-ref "completable-prompt")
                                       {"name" "name" "value" "A"})
                     ["completion" "values"]))
          "no department chosen yet means no candidates"))))

(deftest static-documents-are-served-in-a-stable-order
  (let [{:keys [client]} (everything-fixture)
        rs (take 7 (client/list-all-resources! client))]
    (is (= ["architecture" "extension" "features" "how-it-works"
            "instructions" "startup" "structure"]
           (mapv #(get % "name") rs))
        "the reference server's seven docs/ filenames, sorted by key")
    (is (str/includes? (get-in (client/read-resource! client (get (first rs) "uri"))
                               ["contents" 0 "text"])
                       "tools.agents.mcp.server/handle"))))

(deftest the-tasks-tools-are-deliberately-absent
  ;; Tasks left the core protocol for the io.modelcontextprotocol/tasks
  ;; extension in this revision, so the reference server's three task tools
  ;; have no core equivalent to port.
  (let [{:keys [client]} (everything-fixture)
        names (set (mapv #(get % "name") (client/list-all-tools! client)))]
    (doseq [n ["start-task" "get-task-status" "cancel-task" "list-tasks"]]
      (is (not (contains? names n)) n))))
