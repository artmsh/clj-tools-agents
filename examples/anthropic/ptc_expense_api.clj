(ns examples.anthropic.ptc-expense-api
  "Port of anthropic-cookbooks' tool_use/utils/team_expense_api.py — a mock
   third-party expense-management API used by examples/ptc_demo.clj. Three
   read functions, each returning a pre-serialized JSON STRING (not a plain
   Clojure value), exactly like the Python original: this matters to the
   demo, since the model (or the code running in its Code Execution
   environment) receives JSON text over the wire and has to parse it itself,
   same as it would from any real HTTP API.

   - get-team-members: all employees in a department (id/name/role/level).
   - get-expenses: an employee's expense line items for a quarter — 20-50
     records, each carrying the same audit-trail metadata (receipt URL,
     approval chain, merchant, project code) the real cookbook's version
     does, which is the whole point: this is the bulky, metadata-rich
     payload PTC lets Claude filter/aggregate in the code execution
     environment instead of the model's own context.
   - get-custom-budget: an employee's quarterly travel budget — standard
     $5,000 unless they're one of the five names below with an exception.

   Team roster and the custom-budget table are copied verbatim from the
   Python original (same IDs/names/roles/budget amounts) for realism.
   Expense generation is NOT a port of Python's `random.seed(hash(...))` +
   Mersenne-Twister sequence — reproducing that bit-for-bit in Clojure would
   need a from-scratch Mersenne-Twister implementation for a result that
   isn't even the point of the demo. What matters is plausible, DETERMINISTIC
   data that varies by (employee-id, quarter): this uses a small hand-rolled
   linear-congruential generator seeded from the employee-id/quarter string
   instead, with the same field shape as the original."
  (:require [clojure.string :as str]
            [tools.agents.anthropic :as a]))

;; ---------------------------------------------------------------------------
;; Team roster — verbatim from utils/team_expense_api.py.
;; ---------------------------------------------------------------------------

(def ^:private teams
  {"engineering"
   [{"id" "ENG001" "name" "Alice Chen" "role" "Senior Software Engineer" "level" "senior" "email" "alice.chen@company.com" "department" "engineering"}
    {"id" "ENG002" "name" "Bob Martinez" "role" "Staff Engineer" "level" "staff" "email" "bob.martinez@company.com" "department" "engineering"}
    {"id" "ENG003" "name" "Carol White" "role" "Software Engineer" "level" "mid" "email" "carol.white@company.com" "department" "engineering"}
    {"id" "ENG004" "name" "David Kim" "role" "Principal Engineer" "level" "principal" "email" "david.kim@company.com" "department" "engineering"}
    {"id" "ENG005" "name" "Emma Johnson" "role" "Junior Software Engineer" "level" "junior" "email" "emma.johnson@company.com" "department" "engineering"}
    {"id" "ENG006" "name" "Frank Liu" "role" "Senior Software Engineer" "level" "senior" "email" "frank.liu@company.com" "department" "engineering"}
    {"id" "ENG007" "name" "Grace Taylor" "role" "Software Engineer" "level" "mid" "email" "grace.taylor@company.com" "department" "engineering"}
    {"id" "ENG008" "name" "Henry Park" "role" "Staff Engineer" "level" "staff" "email" "henry.park@company.com" "department" "engineering"}]
   "sales"
   [{"id" "SAL001" "name" "Irene Davis" "role" "Account Executive" "level" "mid" "email" "irene.davis@company.com" "department" "sales"}
    {"id" "SAL002" "name" "Jack Wilson" "role" "Senior Account Executive" "level" "senior" "email" "jack.wilson@company.com" "department" "sales"}
    {"id" "SAL004" "name" "Leo Garcia" "role" "Regional Sales Director" "level" "staff" "email" "leo.garcia@company.com" "department" "sales"}
    {"id" "SAL006" "name" "Nathan Scott" "role" "VP of Sales" "level" "principal" "email" "nathan.scott@company.com" "department" "sales"}]
   "marketing"
   [{"id" "MKT001" "name" "Olivia Thompson" "role" "Marketing Manager" "level" "senior" "email" "olivia.thompson@company.com" "department" "marketing"}
    {"id" "MKT004" "name" "Rachel Lee" "role" "Director of Marketing" "level" "staff" "email" "rachel.lee@company.com" "department" "marketing"}]})

(defn get-team-members
  "Returns a JSON string: an ARRAY of team-member objects for department
   (case-insensitive), or {\"error\" ...} for an unknown department."
  [department]
  (let [dept (str/lower-case (str department))]
    (if-let [members (get teams dept)]
      (a/write-json members)
      (a/write-json {"error" (str "Department '" dept "' not found. Available departments: "
                                   (str/join ", " (keys teams)))}))))

;; ---------------------------------------------------------------------------
;; Custom budget table — verbatim from utils/team_expense_api.py.
;; ---------------------------------------------------------------------------

(def ^:private custom-budgets
  {"ENG002" {"user_id" "ENG002" "has_custom_budget" true "travel_budget" 8000
             "reason" "Staff engineer with regular client site visits" "currency" "USD"}
   "ENG004" {"user_id" "ENG004" "has_custom_budget" true "travel_budget" 12000
             "reason" "Principal engineer leading distributed team across multiple offices" "currency" "USD"}
   "SAL004" {"user_id" "SAL004" "has_custom_budget" true "travel_budget" 15000
             "reason" "Regional sales director covering west coast territory" "currency" "USD"}
   "SAL006" {"user_id" "SAL006" "has_custom_budget" true "travel_budget" 20000
             "reason" "VP of Sales with extensive client travel requirements" "currency" "USD"}
   "MKT004" {"user_id" "MKT004" "has_custom_budget" true "travel_budget" 10000
             "reason" "Director of Marketing attending industry conferences and partner meetings" "currency" "USD"}})

(defn get-custom-budget
  "Returns a JSON string: a SINGLE OBJECT — user-id's custom budget if it has
   one, else the standard $5,000 quarterly travel budget."
  [user-id]
  (a/write-json
    (or (get custom-budgets user-id)
        {"user_id" user-id "has_custom_budget" false "travel_budget" 5000
         "reason" "Standard quarterly travel budget" "currency" "USD"})))

;; ---------------------------------------------------------------------------
;; Expense generation — deterministic per (employee-id, quarter) via a small
;; hand-rolled LCG, NOT java.util.Random/clojure.core/hash: the fixtures must
;; be byte-identical wherever this example runs, so it uses plain integer
;; arithmetic over char codes rather than anything whose seeding or hashing
;; could differ.
;; ---------------------------------------------------------------------------

(defn- string-seed [s]
  (reduce (fn [acc ch] (mod (+ (* acc 31) (int ch)) 0x7fffffff)) 7 s))

(defn- lcg-next [state] (mod (+ (* state 1103515245) 12345) 0x80000000))

(defn- lcg-int
  "Advance state once; returns [next-state value-in-[lo,hi]]."
  [state lo hi]
  (let [state' (lcg-next state)
        span   (inc (- hi lo))]
    [state' (+ lo (mod state' span))]))

(defn- lcg-double
  "Advance state once; returns [next-state value-in-[0.0,1.0)]."
  [state]
  (let [state' (lcg-next state)]
    [state' (/ (double state') (double 0x80000000))]))

(defn- lcg-choice [state coll]
  (let [[state' i] (lcg-int state 0 (dec (count coll)))]
    [state' (nth coll i)]))

(defn- lcg-weighted-choice
  "coll is a seq of [value weight] pairs, weights need not sum to 1."
  [state coll]
  (let [total (reduce + (map second coll))
        [state' r] (lcg-double state)
        target (* r total)]
    [state' (loop [items coll acc 0.0]
              (let [[v w] (first items)
                    acc' (+ acc w)]
                (if (or (empty? (rest items)) (< target acc')) v (recur (rest items) acc'))))]))

(def ^:private expense-categories
  ;; [category description min-amount max-amount] — trimmed from the
  ;; Python original's 23-entry table to a representative subset per
  ;; category; every category name the tool description promises still
  ;; appears at least once.
  [["travel" "Flight to client meeting" 400 1500]
   ["travel" "Rental car" 100 500]
   ["travel" "Taxi/Uber" 20 200]
   ["lodging" "Hotel stay" 150 900]
   ["meals" "Client dinner" 50 250]
   ["meals" "Team lunch" 20 100]
   ["software" "SaaS subscription" 10 200]
   ["equipment" "Monitor" 200 800]
   ["conference" "Conference ticket" 500 2500]
   ["office" "Office supplies" 10 100]
   ["internet" "Mobile data" 30 100]])

(def ^:private merchants
  {"travel" ["United Airlines" "Delta" "Enterprise Rent-A-Car"]
   "lodging" ["Marriott" "Hilton" "Airbnb"]
   "meals" ["Olive Garden" "Starbucks" "Chipotle"]
   "software" ["AWS" "GitHub" "Linear"]
   "equipment" ["Amazon" "Best Buy" "B&H Photo"]
   "conference" ["EventBrite" "AWS re:Invent"]
   "office" ["Staples" "Amazon"]
   "internet" ["Verizon" "T-Mobile"]})

(def ^:private cities ["San Francisco, CA" "New York, NY" "Austin, TX" "Seattle, WA" "Denver, CO"])
(def ^:private managers ["Sarah Johnson" "Michael Chen" "Emily Rodriguez"])
(def ^:private project-codes ["PROJ-1001" "PROJ-2001" "DEPT-ENG" "CLIENT-A"])
(def ^:private status-weights [["approved" 0.85] ["pending" 0.10] ["rejected" 0.05]])

(def ^:private quarter-months
  {"Q1" [1 2 3] "Q2" [4 5 6] "Q3" [7 8 9] "Q4" [10 11 12]})

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn get-expenses
  "Returns a JSON string: an ARRAY of expense-line-item objects for
   employee-id in quarter (\"Q1\".. \"Q4\"), 20-50 line items, sorted by date.
   {\"error\" ...} for an unrecognized quarter."
  [employee-id quarter]
  (let [q (str/upper-case (str quarter))
        months (get quarter-months q)]
    (if-not months
      (a/write-json {"error" (str "Invalid quarter '" quarter "'. Must be Q1, Q2, Q3, or Q4")})
      (let [seed0 (string-seed (str employee-id q))
            [seed1 n] (lcg-int seed0 20 50)
            year 2024
            [_final-seed expenses]
            (reduce
              (fn [[state acc] i]
                (let [[state [category desc min-amt max-amt]] (lcg-choice state expense-categories)
                      [state month] (lcg-choice state months)
                      [state day] (lcg-int state 1 28)
                      [state amt-frac] (lcg-double state)
                      ;; round to cents via `long` truncation, not Math/round
                      ;; — long is already relied on elsewhere in this
                      ;; library's portable (no #?() needed) code (e.g.
                      ;; visualize.cljc's group-digits), unlike Math/round.
                      raw-amount (+ min-amt (* amt-frac (- max-amt min-amt)))
                      amount (/ (double (long (+ (* 100.0 raw-amount) 0.5))) 100.0)
                      [state status] (lcg-weighted-choice state status-weights)
                      [state store] (lcg-choice state (get merchants category ["Unknown Merchant"]))
                      [state city] (lcg-choice state cities)
                      [state approver] (lcg-choice state managers)
                      [state project] (lcg-choice state project-codes)
                      date (str year "-" (pad2 month) "-" (pad2 day))
                      expense {"expense_id" (str employee-id "_" q "_" (pad2 i))
                               "date" date
                               "category" category
                               "description" desc
                               "amount" amount
                               "currency" "USD"
                               "status" status
                               "receipt_url" (str "https://receipts.company.com/" employee-id "/" q "/" (pad2 i) ".pdf")
                               "approved_by" (when (= status "approved") approver)
                               "store_name" store
                               "store_location" city
                               "payment_method" "corporate_card"
                               "project_code" project
                               "notes" (str "Business expense: " desc)}]
                  [state (conj acc expense)]))
              [seed1 []]
              (range n))]
        (a/write-json (vec (sort-by #(get % "date") expenses)))))))
