(ns examples.anthropic.ptc-demo
  "Port of anthropic-cookbooks' tool_use/programmatic_tool_calling_ptc.ipynb
   (https://github.com/anthropics/claude-cookbooks/blob/main/tool_use/programmatic_tool_calling_ptc.ipynb) —
   Programmatic Tool Calling (PTC): Claude writes code that calls tools
   *inside* the Code Execution environment, instead of round-tripping
   through the model for every tool invocation. The cookbook's own scenario
   is used verbatim: a team-expense analysis (examples/ptc_expense_api.clj,
   ported from the notebook's utils/team_expense_api.py) where an
   employee's expenses are 20-50+ metadata-rich line items — exactly the
   kind of bulky payload PTC lets Claude filter/aggregate in code instead of
   the model's own context.

   Two agents, mirroring the notebook's own before/after comparison:
     - `run-agent-without-ptc` — traditional tool calling (baseline). Same
       shape as examples/tool_use.clj's tool-loop, three tools instead of
       one; every tool_use block round-trips through the model.
     - `run-agent-with-ptc`    — the same three tools, plus the
       `code_execution` server tool, wired for PTC.

   What PTC adds on top of ordinary tool calling (`\"tools\"` already passes
   straight through messages-create untouched, same as always):

   1. `allowed_callers` on each tool definition — opts a tool into being
      invoked from the code execution environment, not just directly by the
      model: `[\"code_execution_20250825\"]` (code-execution-only, what this
      demo uses) or `[\"direct\" \"code_execution_20250825\"]` (both). A tool
      with no `allowed_callers` defaults to model-only, unreachable from
      code — see `tools`/`ptc-tools` below.
   2. The `code_execution` tool itself: `{\"type\" \"code_execution_20250825\"
      \"name\" \"code_execution\"}` — a SERVER tool, appended to `ptc-tools`.
      Anthropic's own container executes it; it never needs a client-
      supplied tool_result and never appears as a `tool_use` content block —
      it surfaces as `server_tool_use` (the code Claude wrote) and
      `code_execution_tool_result` (stdout/stderr) instead, both already
      rendered by tools.agents.anthropic.visualize.
   3. The `advanced-tool-use-2025-11-20` beta flag, sent via the client's
      new `:betas` opt (see tools.agents.anthropic's README/client docstring —
      the one library change this demo needed: a generic `:betas` -> comma-
      joined `anthropic-beta` header, correctly combined with the OAuth
      path's own mandatory flag rather than clobbering it).
   4. `\"container\"` — a top-level request-map key (Python's
      `extra_body={\"container\": container_id}`), carrying container state
      across turns for the SAME reason a Python generator holds local
      variables between `yield`s: the code execution environment is
      stateful, and resuming it after a client-supplied tool_result needs to
      resume in the SAME container, not a fresh one. Needs no library
      support at all beyond messages-create's existing data-transparency —
      it's just another key next to \"model\"/\"messages\". The response's own
      \"container\" object (`{\"id\" ... \"expires_at\" ...}`) is read the same
      way, via plain `get-in` on the decoded response map.
   5. Each `tool_use` block's `\"caller\"` field — `{\"type\" \"direct\"}` (the
      model called it directly) or `{\"type\" \"code_execution_20250825\"}`
      (code running in the container called it). Deliberately NOT added to
      tools.agents.anthropic/tool-calls itself (that would widen the library's
      general-purpose tool-calling helper for one beta feature) — see
      `tool-use-blocks` below, a small LOCAL widening scoped to this demo.

   THE RULE THAT MATTERS MOST: only answer tool_use blocks for YOUR OWN
   tools (`local-tool-names` below). `code_execution`'s invocation is never
   itself a `tool_use` block (see point 2), but `run-agent-with-ptc` still
   checks `local-tool-names` defensively before dispatching each block name
   through `run-tool` — the same guard would also cover a differently-
   configured tool list where `allowed_callers` includes \"direct\", so a
   future edit here can't accidentally route a server tool's call into
   `run-tool`.

   Every credential comes from `tools.agents.anthropic/client`'s own env-var
   resolution (ANTHROPIC_API_KEY), never a literal in source — same
   convention as every other examples/*.clj file. `-main` needs a real
   Anthropic account with PTC beta access; the mechanics above are exercised
   without one via a mock server in
   test/tools/agents/anthropic/live_test.cljc's PTC tests."
  (:require [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.visualize :as viz]
            [examples.anthropic.ptc-expense-api :as api]))

(def model "claude-sonnet-4-6")
(def ptc-beta "advanced-tool-use-2025-11-20")

(def query
  "Which engineering team members exceeded their Q3 travel budget? Standard quarterly travel budget is $5,000. However, some employees have custom budget limits. For anyone who exceeded the $5,000 standard budget, check if they have a custom budget exception. If they do, use that custom limit instead to determine if they truly exceeded their budget.")

;; ---------------------------------------------------------------------------
;; Tool definitions — verbatim (descriptions, schemas, input_examples) from
;; the notebook's own `tools` list.
;; ---------------------------------------------------------------------------

(def tools
  [{"name" "get_team_members"
    "description" (str "Returns a list of team members for a given department. Each team member includes their ID, "
                        "name, role, level (junior, mid, senior, staff, principal), and contact information. Use "
                        "this to get a list of people whose expenses you want to analyze. Available departments "
                        "are: engineering, sales, and marketing.\n\n"
                        "RETURN FORMAT: Returns a JSON string containing an ARRAY of team member objects (not "
                        "wrapped in an outer object). Parse with json.loads() to get a list. Example: "
                        "[{\"id\": \"ENG001\", \"name\": \"Alice\", ...}, {\"id\": \"ENG002\", ...}]")
    "input_schema" {"type" "object"
                     "properties" {"department" {"type" "string" "description" "The department name. Case-insensitive."}}
                     "required" ["department"]}
    "input_examples" [{"department" "engineering"} {"department" "sales"} {"department" "marketing"}]}

   {"name" "get_expenses"
    "description" (str "Returns all expense line items for a given employee in a specific quarter. Each expense "
                        "includes extensive metadata: date, category, description, amount (in USD), currency, "
                        "status (approved, pending, rejected), receipt URL, approval chain, merchant name and "
                        "location, payment method, and project codes. An employee may have 20-50+ expense line "
                        "items per quarter, and each line item contains substantial metadata for audit and "
                        "compliance purposes. Categories include: 'travel' (flights, trains, rental cars, taxis, "
                        "parking), 'lodging' (hotels, airbnb), 'meals', 'software', 'equipment', 'conference', "
                        "'office', and 'internet'. IMPORTANT: Only expenses with status='approved' should be "
                        "counted toward budget limits.\n\n"
                        "RETURN FORMAT: Returns a JSON string containing an ARRAY of expense objects (not wrapped "
                        "in an outer object with an 'expenses' key). Parse with json.loads() to get a list "
                        "directly. Example: [{\"expense_id\": \"ENG001_Q3_001\", \"amount\": 1250.50, \"category\": "
                        "\"travel\", ...}, {...}]")
    "input_schema" {"type" "object"
                     "properties" {"employee_id" {"type" "string" "description" "The unique employee identifier"}
                                   "quarter" {"type" "string" "description" "Quarter identifier: 'Q1', 'Q2', 'Q3', or 'Q4'"}}
                     "required" ["employee_id" "quarter"]}
    "input_examples" [{"employee_id" "ENG001" "quarter" "Q3"}
                       {"employee_id" "SAL002" "quarter" "Q1"}
                       {"employee_id" "MKT001" "quarter" "Q4"}]}

   {"name" "get_custom_budget"
    "description" (str "Get the custom quarterly travel budget for a specific employee. Most employees have a "
                        "standard $5,000 quarterly travel budget. However, some employees have custom budget "
                        "exceptions based on their role requirements. This function checks if a specific employee "
                        "has a custom budget assigned.\n\n"
                        "RETURN FORMAT: Returns a JSON string containing a SINGLE OBJECT (not an array). Parse with "
                        "json.loads() to get a dict. Example: {\"user_id\": \"ENG001\", \"has_custom_budget\": "
                        "false, \"travel_budget\": 5000, \"reason\": \"Standard\", \"currency\": \"USD\"}")
    "input_schema" {"type" "object"
                     "properties" {"user_id" {"type" "string" "description" "The unique employee identifier"}}
                     "required" ["user_id"]}
    "input_examples" [{"user_id" "ENG001"} {"user_id" "SAL002"} {"user_id" "MKT001"}]}])

(def ^:private local-tool-names #{"get_team_members" "get_expenses" "get_custom_budget"})

;; PTC-enabled tools: allowed_callers on each of ours, plus the code
;; execution server tool itself — port of the notebook's:
;;   ptc_tools = copy.deepcopy(tools)
;;   for tool in ptc_tools: tool["allowed_callers"] = ["code_execution_20250825"]
;;   ptc_tools.append({"type": "code_execution_20250825", "name": "code_execution"})
(def ptc-tools
  (conj (mapv #(assoc % "allowed_callers" ["code_execution_20250825"]) tools)
        {"type" "code_execution_20250825" "name" "code_execution"}))

(defn- run-tool [name input]
  (cond
    (= name "get_team_members") (api/get-team-members (get input "department"))
    (= name "get_expenses") (api/get-expenses (get input "employee_id") (get input "quarter"))
    (= name "get_custom_budget") (api/get-custom-budget (get input "user_id"))
    :else (throw (ex-info (str "unknown tool: " name) {:tool name}))))

;; ---------------------------------------------------------------------------
;; Traditional tool calling (baseline) — no PTC.
;; ---------------------------------------------------------------------------

(defn run-agent-without-ptc
  "Traditional tool-calling loop over the expense API — the baseline the
   cookbook compares PTC against. on-response (default: no-op) is called
   with every raw messages-create response; wire in
   tools.agents.anthropic.visualize/show-response for the notebook's own
   per-turn rendering (see -main) — left as a callback so this function
   stays pure/testable and an automated mock-server test doesn't spam
   stdout. Returns {:text :api-calls :total-tokens :messages}."
  ([client user-message] (run-agent-without-ptc client user-message (fn [_])))
  ([client user-message on-response]
   (loop [messages (a/add-user-message [] user-message) api-calls 0 total-tokens 0]
     (let [response (a/messages-create client
                       {"model" model "max_tokens" 4000 "tools" tools "messages" messages})
           usage    (get response "usage")
           tokens'  (+ total-tokens (or (get usage "input_tokens") 0) (or (get usage "output_tokens") 0))
           calls'   (inc api-calls)]
       (on-response response)
       (cond
         (= (get response "stop_reason") "end_turn")
         {:text (a/output-text response) :api-calls calls' :total-tokens tokens' :messages messages}

         (a/tool-use? response)
         (let [messages' (a/add-assistant-message messages (get response "content"))
               results   (mapv (fn [{:keys [id name input]}]
                                  (try {:tool-use-id id :content (run-tool name input)}
                                       (catch Exception e {:tool-use-id id :content (str e) :is-error true})))
                                (a/tool-calls response))]
           (recur (a/add-tool-results messages' results) calls' tokens'))

         :else
         {:text (try (a/output-text response)
                     (catch Exception _ (str "Stopped with reason: " (get response "stop_reason"))))
          :api-calls calls' :total-tokens tokens' :messages messages})))))

;; ---------------------------------------------------------------------------
;; Programmatic Tool Calling.
;; ---------------------------------------------------------------------------

(defn- tool-use-blocks
  "Like tools.agents.anthropic/tool-calls, but also surfaces each block's
   \"caller\" field — the one bit of PTC-specific data tool-calls
   deliberately doesn't carry (the library stays data-transparent and
   general-purpose; this is a small widening local to this one demo, not a
   library change)."
  [response]
  (keep (fn [block]
          (when (and (map? block) (= (get block "type") "tool_use"))
            {:id (get block "id") :name (get block "name") :input (get block "input")
             :caller-type (get-in block ["caller" "type"])}))
        (get response "content")))

(defn run-agent-with-ptc
  "Same three tools, wrapped for PTC (see ptc-tools), plus container-id
   threaded across turns. Every dispatched call's caller is tagged via
   :caller-log, not on-response — on-response only ever sees the raw API
   response, not this loop's own dispatch bookkeeping. Returns {:text
   :api-calls :total-tokens :messages :container-id :caller-log},
   :caller-log a vector of {:name :caller-type} in dispatch order — how
   live_test.cljc's mock-server test asserts on direct/code_execution
   routing without scraping stdout."
  ([client user-message] (run-agent-with-ptc client user-message (fn [_])))
  ([client user-message on-response]
   (let [client (assoc client :betas (vec (distinct (conj (vec (:betas client)) ptc-beta))))]
     (loop [messages (a/add-user-message [] user-message) api-calls 0 total-tokens 0
            container-id nil caller-log []]
       (let [request  (cond-> {"model" model "max_tokens" 4000 "tools" ptc-tools "messages" messages}
                         container-id (assoc "container" container-id))
             response (a/messages-create client request)
             usage    (get response "usage")
             tokens'  (+ total-tokens (or (get usage "input_tokens") 0) (or (get usage "output_tokens") 0))
             calls'   (inc api-calls)
             container-id' (or (get-in response ["container" "id"]) container-id)]
         (on-response response)
         (cond
           (= (get response "stop_reason") "end_turn")
           {:text (a/output-text response) :api-calls calls' :total-tokens tokens'
            :messages messages :container-id container-id' :caller-log caller-log}

           (a/tool-use? response)
           (let [messages'  (a/add-assistant-message messages (get response "content"))
                 ;; Only ever dispatch OUR tools — see the ns docstring's
                 ;; "rule that matters most". code_execution's own
                 ;; invocation surfaces as server_tool_use, never as a
                 ;; tool_use block, so this filter is a defensive no-op
                 ;; against THIS demo's ptc-tools — but it's what makes that
                 ;; true by construction rather than by accident.
                 dispatched (filter (comp local-tool-names :name) (tool-use-blocks response))
                 results    (map (fn [{:keys [id name input]}]
                                    (try {:tool-use-id id :content (run-tool name input)}
                                         (catch Exception e {:tool-use-id id :content (str e) :is-error true})))
                                  dispatched)
                 log'       (into caller-log (map #(select-keys % [:name :caller-type]) dispatched))]
             (recur (a/add-tool-results messages' results) calls' tokens' container-id' log'))

           :else
           {:text (try (a/output-text response)
                       (catch Exception _ (str "Stopped with reason: " (get response "stop_reason"))))
            :api-calls calls' :total-tokens tokens' :messages messages
            :container-id container-id' :caller-log caller-log}))))))

;; ---------------------------------------------------------------------------
;; Comparison table — port of the notebook's pandas-based printout, without
;; adding a dependency for a five-row table.
;; ---------------------------------------------------------------------------

(defn- pad-right [v width]
  (let [s (str v)]
    (str s (str/join (repeat (max 0 (- width (count s))) " ")))))

(defn- round1 [n]
  ;; long-truncation rounding to 1 decimal place, not Math/round — see
  ;; examples/ptc_expense_api.clj's identical choice, same portability
  ;; reasoning.
  (/ (double (long (+ (* 10.0 n) 0.5))) 10.0))

(defn comparison-table
  "baseline/ptc are run-agent-without-ptc/run-agent-with-ptc's return maps.
   Returns a printable string, no I/O."
  [baseline ptc]
  (let [tokens-b (:total-tokens baseline)
        tokens-p (:total-tokens ptc)
        reduction (if (pos? tokens-b) (round1 (* 100.0 (/ (- tokens-b tokens-p) tokens-b))) 0.0)]
    (str/join "\n"
      [(str/join (repeat 60 "="))
       "PERFORMANCE COMPARISON"
       (str/join (repeat 60 "="))
       (str (pad-right "Metric" 25) (pad-right "Without PTC" 15) "With PTC")
       (str (pad-right "API calls" 25) (pad-right (:api-calls baseline) 15) (:api-calls ptc))
       (str (pad-right "Total tokens" 25) (pad-right tokens-b 15) tokens-p)
       (str (pad-right "Token reduction" 25) (pad-right "-" 15) (str reduction "%"))])))

;; JVM-only manual entry point (needs a real Anthropic account with PTC beta
;; access) — never exercised by the automated cross-runtime suite, same
;; convention as every other examples/*.clj -main. ANTHROPIC_API_KEY
;; supplies credentials, never a literal in source.
(defn -main [& _]
  (let [client (a/client)]
    (println "=== Traditional tool calling (baseline) ===")
    (let [baseline (run-agent-without-ptc client query viz/show-response)]
      (println "\nResult:" (:text baseline))
      (println "\n=== Programmatic Tool Calling (PTC) ===")
      (let [ptc (run-agent-with-ptc client query viz/show-response)]
        (println "\nResult:" (:text ptc))
        (println (str "\n" (comparison-table baseline ptc)))))))
