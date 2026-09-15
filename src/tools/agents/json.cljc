(ns tools.agents.json
  "The one JSON codec shared by every client in this repo (anthropic, openai,
   gemini, mcp). Pure, portable clojure.core: runs unmodified on JVM Clojure
   and Babashka, with no dependency — Babashka bundles a JSON library, JVM
   Clojure does not, and adding one would break the zero-dependency contract.

   It supports exactly what the wire protocols need:
   nil/bool/number/string/keyword/vector/seq/map. Map keys are strings,
   keywords or symbols, passed through VERBATIM (no kebab<->snake
   conversion — callers type wire keys like \"max_tokens\" literally).
   Decoding yields maps with STRING keys, vectors, and numbers that stay
   numbers (integers stay integers).

   `write-json` \\u00XX-escapes EVERY character below 0x20, not only the five
   with short escapes: RFC 8259 §7 forbids raw control characters inside a
   string, and the MCP stdio transport's newline framing depends on an
   encoded message never containing a raw newline.

   ERROR CONTRACT. Every failure is an ex-info whose message starts with
   `<prefix>/write-json: `, `<prefix>/read-json: ` or `<prefix>/read-jsonl: `
   and whose ex-data carries `:type` — `:encode-type` for encoding,
   `:parse-type` for decoding. Each client namespace documents its own
   prefix and type keywords, so it passes them as `opts`:

       (json/codec {:prefix      \"tools.agents.openai\"
                    :encode-type :tools.agents.openai/json-encode-error
                    :parse-type  :tools.agents.openai/json-parse-error})

   Every public fn takes `opts` as an optional first argument; without it,
   `default-opts` applies."
  (:require [clojure.string :as str]))

(def default-opts
  "Error prefix and ex-data `:type` keywords used when no opts are given."
  {:prefix      "tools.agents.json"
   :encode-type ::encode-error
   :parse-type  ::parse-error})

;; ---------------------------------------------------------------------------
;; Encoding
;; ---------------------------------------------------------------------------

(defn- encode-error! [{:keys [prefix encode-type]} v]
  (throw (ex-info (str prefix "/write-json: unsupported value: " (pr-str v))
                  {:type encode-type})))

(defn json-key->str
  "Coerce a map key (string/keyword/symbol) to its wire string form, verbatim
   — no case conversion. Throws the same typed encode ex-info as the rest of
   `write-json` on anything else."
  ([k] (json-key->str default-opts k))
  ([opts k]
   (cond
     (string? k) k
     (keyword? k) (name k)
     (symbol? k) (name k)
     :else (encode-error! opts k))))

(defn- control-char? [c] (< (int c) 0x20))

(defn- escape-controls
  "Slow path: \\u00XX-escape every remaining character below 0x20. Entered
   only when one is actually present, so an ordinary string pays a single
   linear scan and no allocation."
  [s]
  (str/join (map (fn [c] (if (control-char? c) (format "\\u%04x" (int c)) (str c))) s)))

(defn- encode-string [s]
  (let [escaped (-> s
                    (str/replace "\\" "\\\\")   ;; MUST run first — later rules insert backslashes
                    (str/replace "\"" "\\\"")
                    (str/replace "\n" "\\n")
                    (str/replace "\r" "\\r")
                    (str/replace "\t" "\\t")
                    (str/replace "\b" "\\b")
                    (str/replace "\f" "\\f"))]
    (str "\""
         (if (some control-char? escaped) (escape-controls escaped) escaped)
         "\"")))

(defn write-json
  "Encode a Clojure value as a JSON string. Map keys may be strings, keywords
   or symbols (encoded via `name`, verbatim). Keyword values are encoded the
   same way as strings. A ratio is rejected rather than emitted as invalid
   `n/d`. The output never contains a raw character below 0x20."
  ([v] (write-json default-opts v))
  ([opts v]
   (cond
     (nil? v) "null"
     (true? v) "true"
     (false? v) "false"
     (string? v) (encode-string v)
     (keyword? v) (encode-string (name v))
     (and (number? v) (ratio? v)) (encode-error! opts v)
     (number? v) (str v)
     (map? v) (str "{"
                   (str/join "," (map (fn [[k val]]
                                        (str (encode-string (json-key->str opts k)) ":" (write-json opts val)))
                                      v))
                   "}")
     (or (vector? v) (list? v) (seq? v)) (str "[" (str/join "," (map #(write-json opts %) v)) "]")
     :else (encode-error! opts v))))

;; ---------------------------------------------------------------------------
;; Decoding. Every helper takes `fail`, a fn of one message that throws the
;; caller's typed parse ex-info.
;; ---------------------------------------------------------------------------

(defn- ws-char? [c] (contains? #{" " "\t" "\n" "\r"} c))
(defn- digit-str? [c] (contains? #{"0" "1" "2" "3" "4" "5" "6" "7" "8" "9"} c))

(defn- peek-char [s i]
  (when (< i (count s)) (subs s i (inc i))))

(defn- skip-ws [s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (ws-char? (subs s i (inc i))))
        (recur (inc i))
        i))))

(declare parse-value)

(defn- parse-literal [fail s i lit val]
  (let [end (+ i (count lit))]
    (if (and (<= end (count s)) (= (subs s i end) lit))
      [val end]
      (fail (str "invalid literal at position " i)))))

(defn- int-leading-zero?
  "True when tok is an integer token (no '.'/'e'/'E') with a disallowed
   leading zero, e.g. \"010\" or \"-010\". JSON's number grammar forbids this
   shape, but Clojure's reader silently treats such tokens as OCTAL literals
   (\"010\" -> 8) — so parse-number must reject them itself rather than hand
   them to `read-string`."
  [tok]
  (let [digits (if (str/starts-with? tok "-") (subs tok 1) tok)]
    (and (> (count digits) 1)
         (str/starts-with? digits "0")
         (every? digit-str? (map str digits)))))

(defn- parse-number [fail s i]
  (let [n (count s) start i]
    (loop [j i]
      (if (and (< j n)
               (let [c (subs s j (inc j))]
                 (or (digit-str? c) (contains? #{"-" "+" "." "e" "E"} c))))
        (recur (inc j))
        (if (= j start)
          (fail (str "invalid number at position " i))
          (let [tok (subs s start j)]
            (if (and (not (str/includes? tok "."))
                     (not (str/includes? tok "e"))
                     (not (str/includes? tok "E"))
                     (int-leading-zero? tok))
              (fail (str "invalid number (leading zero) at position " i))
              [(read-string tok) j])))))))

(defn- parse-string-escaped
  "Slow path for a JSON string that actually contains a backslash escape.
   i points at the opening quote. A surrogate pair (`\\ud83c\\udf89`) needs
   no special case: its two halves decode to two UTF-16 chars whose
   concatenation IS the supplementary code point."
  [fail s i]
  (let [n (count s)]
    (loop [j (inc i) pieces []]
      (when (>= j n) (fail "unterminated string"))
      (let [c (subs s j (inc j))]
        (cond
          (= c "\"") [(str/join pieces) (inc j)]

          (= c "\\")
          (do
            (when (>= (inc j) n) (fail "unterminated escape"))
            (let [esc (subs s (inc j) (+ j 2))]
              (cond
                (= esc "\"") (recur (+ j 2) (conj pieces "\""))
                (= esc "\\") (recur (+ j 2) (conj pieces "\\"))
                (= esc "/")  (recur (+ j 2) (conj pieces "/"))
                (= esc "n")  (recur (+ j 2) (conj pieces "\n"))
                (= esc "r")  (recur (+ j 2) (conj pieces "\r"))
                (= esc "t")  (recur (+ j 2) (conj pieces "\t"))
                (= esc "b")  (recur (+ j 2) (conj pieces "\b"))
                (= esc "f")  (recur (+ j 2) (conj pieces "\f"))
                (= esc "u")
                (do
                  (when (> (+ j 6) n) (fail "unterminated unicode escape"))
                  (let [code (Integer/parseInt (subs s (+ j 2) (+ j 6)) 16)]
                    (recur (+ j 6) (conj pieces (str (char code))))))
                :else (fail (str "invalid escape at position " j)))))

          ;; Literal run: take it to the next quote or backslash in ONE piece,
          ;; so the piece count tracks the number of escapes rather than the
          ;; length of the string.
          :else
          (let [k (loop [k j]
                    (if (or (>= k n)
                            (= (subs s k (inc k)) "\"")
                            (= (subs s k (inc k)) "\\"))
                      k
                      (recur (inc k))))]
            (recur k (conj pieces (subs s j k)))))))))

(defn- parse-string
  "Decode a JSON string starting at the opening quote i. Fast path: scan to
   the closing quote and, if no backslash was seen on the way, one `subs` IS
   the result — no per-character work. Only a string that actually contains
   an escape falls through to parse-string-escaped."
  [fail s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (if (>= j n)
        (fail "unterminated string")
        (let [c (subs s j (inc j))]
          (cond
            (= c "\"") [(subs s (inc i) j) (inc j)]
            (= c "\\") (parse-string-escaped fail s i)
            :else (recur (inc j))))))))

(defn- parse-array [fail s i]
  (let [i (skip-ws s (inc i))]
    (if (= (peek-char s i) "]")
      [[] (inc i)]
      (loop [i i acc []]
        (let [[v i2] (parse-value fail s i)
              acc (conj acc v)
              i3 (skip-ws s i2)
              c (peek-char s i3)]
          (cond
            (= c ",") (recur (skip-ws s (inc i3)) acc)
            (= c "]") [acc (inc i3)]
            :else (fail (str "expected ',' or ']' at position " i3))))))))

(defn- parse-object [fail s i]
  (let [i (skip-ws s (inc i))]
    (if (= (peek-char s i) "}")
      [{} (inc i)]
      (loop [i i acc {}]
        (when (not= (peek-char s i) "\"") (fail (str "expected string key at position " i)))
        (let [[k i2] (parse-string fail s i)
              i3 (skip-ws s i2)]
          (when (not= (peek-char s i3) ":") (fail (str "expected ':' at position " i3)))
          (let [i4 (skip-ws s (inc i3))
                [v i5] (parse-value fail s i4)
                acc (assoc acc k v)
                i6 (skip-ws s i5)
                c (peek-char s i6)]
            (cond
              (= c ",") (recur (skip-ws s (inc i6)) acc)
              (= c "}") [acc (inc i6)]
              :else (fail (str "expected ',' or '}' at position " i6)))))))))

(defn- parse-value [fail s i]
  (let [i (skip-ws s i)
        c (peek-char s i)]
    (cond
      (nil? c) (fail "unexpected end of input")
      (= c "{") (parse-object fail s i)
      (= c "[") (parse-array fail s i)
      (= c "\"") (parse-string fail s i)
      (= c "t") (parse-literal fail s i "true" true)
      (= c "f") (parse-literal fail s i "false" false)
      (= c "n") (parse-literal fail s i "null" nil)
      (or (digit-str? c) (= c "-")) (parse-number fail s i)
      :else (fail (str "unexpected character '" c "' at position " i)))))

(defn read-json
  "Decode a JSON string into a Clojure value: objects become maps with STRING
   keys, arrays become vectors, numbers stay numbers (integers stay
   integers). Throws a typed parse ex-info on malformed input — never
   returns a partial result."
  ([s] (read-json default-opts s))
  ([{:keys [prefix parse-type]} s]
   (let [fail (fn [msg] (throw (ex-info (str prefix "/read-json: " msg) {:type parse-type})))]
     (try
       (first (parse-value fail s (skip-ws s 0)))
       (catch Exception e
         (throw (ex-info (str prefix "/read-json: malformed JSON: " (str e))
                         {:type parse-type})))))))

(declare ^:private read-lines)

(defn read-jsonl
  "Decode JSON Lines (one JSON value per line) from a String or a
   java.io.Reader into a LAZY seq of decoded values. Blank (whitespace-only)
   lines are skipped. A malformed line throws, when that element is realized,
   a typed parse ex-info whose message starts with `<prefix>/read-jsonl: line
   N: ` (N is 1-based and counts blank lines) and whose ex-data also carries
   `:line`. A Reader is consumed lazily and is NOT closed here — the caller
   owns it and must realize the seq before closing it."
  ([src] (read-jsonl default-opts src))
  ([opts src] (read-lines (partial read-json opts) opts src)))

(defn- read-lines
  [read-fn {:keys [prefix parse-type]} src]
  (let [rdr (java.io.BufferedReader.
             (if (string? src) (java.io.StringReader. ^String src) ^java.io.Reader src))]
    (->> (line-seq rdr)
         (map-indexed vector)
         (remove (fn [[_ line]] (str/blank? line)))
         (map (fn [[idx line]]
                (try
                  (read-fn line)
                  (catch Exception e
                    (throw (ex-info (str prefix "/read-jsonl: line " (inc idx) ": " (ex-message e))
                                    {:type parse-type :line (inc idx)}
                                    e)))))))))

(defn codec
  "A codec map for one error contract (see the ns docstring):
   {:read f :write f :read-jsonl f :key->str f}, each fn of one argument."
  ([] (codec default-opts))
  ([opts]
   (let [opts (merge default-opts opts)]
     {:read       (partial read-json opts)
      :write      (partial write-json opts)
      :read-jsonl (partial read-jsonl opts)
      :key->str   (partial json-key->str opts)})))

;; ---------------------------------------------------------------------------
;; Caller-supplied codecs (a client's :json option)
;; ---------------------------------------------------------------------------

(defn codec-map?
  "True when x can serve as a client's `:json` option: a map whose :read and
   :write are fns (:read String -> value, :write value -> String)."
  [x]
  (boolean (and (map? x)
                (let [ok? (fn [f] (or (fn? f) (var? f)))]
                  (and (ok? (:read x)) (ok? (:write x)))))))

(defn- wrap-errors [f prefix fn-name type]
  (fn [x]
    (try
      (f x)
      (catch Exception e
        (if (= type (:type (ex-data e)))
          (throw e)
          (throw (ex-info (str prefix "/" fn-name ": " (or (ex-message e) (.getName (class e))))
                          {:type type}
                          e)))))))

(defn wrap-codec
  "Bind a caller-supplied codec {:read (fn [s]) :write (fn [v])} to one error
   contract (opts as for `codec`), returning a full codec map. Any exception
   the caller's fns throw becomes this contract's typed ex-info (message
   `<prefix>/read-json: ` / `<prefix>/write-json: `, the original as cause);
   one already carrying the contract's :type passes through. :read-jsonl is
   derived from :read with `read-jsonl`'s laziness, blank-line and `:line`
   rules. :key->str stays the built-in coercion. Wrapping twice is harmless."
  [user-codec opts]
  (let [{:keys [prefix encode-type parse-type] :as opts} (merge default-opts opts)
        rd (wrap-errors (:read user-codec) prefix "read-json" parse-type)]
    {:read       rd
     :write      (wrap-errors (:write user-codec) prefix "write-json" encode-type)
     :read-jsonl (partial read-lines rd opts)
     :key->str   (partial json-key->str opts)}))
