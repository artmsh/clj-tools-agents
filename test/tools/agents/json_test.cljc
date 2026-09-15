(ns tools.agents.json-test
  "The shared JSON codec. Pure logic — zero I/O, identical on JVM Clojure and
   Babashka. Run by the core suite (tools.agents.core.test-runner); each
   client suite keeps one test pinning its own error `:type` keywords and
   message prefixes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.json :as json]))

(defn- thrown
  "The exception `f` throws, or nil."
  [f]
  (try (f) nil (catch Exception e e)))

;; ---------------------------------------------------------------------------
;; Encoding
;; ---------------------------------------------------------------------------

(deftest write-json-scalars
  (is (= "null" (json/write-json nil)))
  (is (= "true" (json/write-json true)))
  (is (= "false" (json/write-json false)))
  (is (= "42" (json/write-json 42)))
  (is (= "-7" (json/write-json -7)))
  (is (= "1.5" (json/write-json 1.5)))
  (is (= "\"hi\"" (json/write-json "hi")))
  (is (= "\"hi\"" (json/write-json :hi))))

(deftest write-json-collections
  (is (= "[1,2,3]" (json/write-json [1 2 3])))
  (is (= "[1,2,3]" (json/write-json '(1 2 3))))
  (is (= "[1,2,3]" (json/write-json (map inc [0 1 2]))))
  (is (= "{}" (json/write-json {})))
  (is (contains? #{"{\"a\":1,\"b\":2}" "{\"b\":2,\"a\":1}"} (json/write-json {:a 1 "b" 2})))
  (is (= "{\"max_tokens\":10}" (json/write-json {:max_tokens 10})))
  (is (= "{\"maxOutputTokens\":10}" (json/write-json {:maxOutputTokens 10})))
  (is (= "{\"sym\":1}" (json/write-json {'sym 1}))))

(deftest write-json-string-escaping
  (is (= "\"a\\nb\"" (json/write-json "a\nb")))
  (is (= "\"a\\rb\"" (json/write-json "a\rb")))
  (is (= "\"a\\tb\"" (json/write-json "a\tb")))
  (is (= "\"a\\bb\"" (json/write-json "a\bb")))
  (is (= "\"a\\fb\"" (json/write-json "a\fb")))
  (is (= "\"a\\\"b\"" (json/write-json "a\"b")))
  (is (= "\"a\\\\b\"" (json/write-json "a\\b"))))

(deftest write-json-escapes-every-control-character
  ;; RFC 8259 §7: a raw character below 0x20 inside a string is invalid JSON.
  ;; The MCP stdio transport also frames one message per LINE, so no encoded
  ;; message may contain a raw newline.
  (is (= "\"a\\u0000b\"" (json/write-json (str "a" (char 0) "b"))))
  (is (= "\"a\\u0001b\"" (json/write-json (str "a" (char 1) "b"))))
  (is (= "\"a\\u001fb\"" (json/write-json (str "a" (char 31) "b"))))
  (is (= "{\"k\\u0002\":\"\\u000b\"}" (json/write-json {(str "k" (char 2)) (str (char 11))})))
  (testing "no character below 0x20 survives encoding"
    (let [all-c0 (apply str (map char (range 0x20)))
          out    (json/write-json {"s" all-c0 "v" [all-c0]})]
      (is (not-any? #(< (int %) 0x20) out))
      (is (= {"s" all-c0 "v" [all-c0]} (json/read-json out)))))
  (testing "0x20 and above pass through unescaped"
    (is (= "\" ~ä\"" (json/write-json " ~ä")))))

(deftest write-json-rejects-unsupported
  (doseq [bad [(fn []) 2/3 {"max_tokens" 2/3} [#{1}] {1 "numeric key"}]]
    (testing (pr-str bad)
      (let [e (thrown #(json/write-json bad))]
        (is (some? e))
        (is (= ::json/encode-error (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) "tools.agents.json/write-json: unsupported value: "))))))

(deftest write-json-handles-long-strings
  (let [payload (str/join (repeat 50000 "x"))]
    (is (= payload (json/read-json (json/write-json payload))))))

(deftest json-key->str-coerces-verbatim
  (is (= "a" (json/json-key->str :a)))
  (is (= "a" (json/json-key->str "a")))
  (is (= "a_b" (json/json-key->str 'a_b)))
  (is (= "max-tokens" (json/json-key->str :max-tokens)))
  (is (= ::json/encode-error (:type (ex-data (thrown #(json/json-key->str 1)))))))

;; ---------------------------------------------------------------------------
;; Decoding
;; ---------------------------------------------------------------------------

(deftest read-json-scalars
  (is (= nil (json/read-json "null")))
  (is (= true (json/read-json "true")))
  (is (= false (json/read-json "false")))
  (is (= 42 (json/read-json "42")))
  (is (integer? (json/read-json "42")))
  (is (= -7 (json/read-json " -7 ")))
  (is (= 1.5 (json/read-json "1.5")))
  (is (= 100.0 (json/read-json "1e2")))
  (is (= "hi" (json/read-json "\"hi\""))))

(deftest read-json-string-escapes
  (is (= "a\nb" (json/read-json "\"a\\nb\"")))
  (is (= "a\tb" (json/read-json "\"a\\tb\"")))
  (is (= "a\"b" (json/read-json "\"a\\\"b\"")))
  (is (= "a\\b" (json/read-json "\"a\\\\b\"")))
  (is (= "a/b" (json/read-json "\"a\\/b\"")))
  (is (= "é" (json/read-json "\"\\u00e9\"")))
  (is (= "a\nb" (json/read-json "\"a\\u000ab\"")))
  (is (= "ä" (json/read-json "\"ä\""))))

(deftest read-json-decodes-surrogate-pairs
  ;; A code point above the BMP arrives as a surrogate PAIR of escapes; the
  ;; two decoded halves concatenate to the one supplementary character.
  (is (= "🎉" (json/read-json "\"\\ud83c\\udf89\"")))
  (is (= "a🎉b" (json/read-json "\"a\\ud83c\\udf89b\"")))
  (let [s (json/read-json "\"\\ud83c\\udf89\"")]
    (is (= 1 (.codePointCount ^String s 0 (count s))))))

(deftest read-json-collections
  (is (= [1 2 3] (json/read-json "[1,2,3]")))
  (is (vector? (json/read-json "[1,2,3]")))
  (is (= {} (json/read-json "{}")))
  (is (= [] (json/read-json "[ ]")))
  (is (= {"a" 1 "b" [1 2 {"c" true}]} (json/read-json "{\"a\":1,\"b\":[1,2,{\"c\":true}]}")))
  (is (= {"a" 1} (json/read-json " { \"a\" : 1 } "))))

(deftest read-json-object-keys-are-strings
  (let [decoded (json/read-json "{\"model\":\"x\"}")]
    (is (= "x" (get decoded "model")))
    (is (not (contains? decoded :model)))))

(deftest read-json-round-trips
  (doseq [v [nil true false 42 -7 1.5 "hi" "a\nb" "äöü" "日本語" "🎉" []
             [1 "two" nil] {} {"a" 1 "b" [true nil]}
             {"model" "m" "max_tokens" 10 "messages" [{"role" "user" "content" "hi"}]
              "temperature" 1.0 "ok" true "nothing" nil}]]
    (is (= v (json/read-json (json/write-json v))) (pr-str v))))

(deftest read-json-rejects-leading-zero-numbers
  ;; JSON forbids a leading zero followed by more digits; Clojure's reader
  ;; would otherwise misread such a token as OCTAL ("010" -> 8).
  (doseq [bad ["010" "-010" "{\"input_tokens\":010}"]]
    (testing bad
      (is (= ::json/parse-error (:type (ex-data (thrown #(json/read-json bad)))))))))

(deftest read-json-handles-long-strings
  ;; Regression: parse-string used to spread one argument per character
  ;; through `(apply str pieces)`. Both the escape-free fast path and the
  ;; escaped slow path are exercised at length.
  (doseq [n [600 5000 50000]]
    (testing (str "plain, " n " chars")
      (let [text (str/join (repeat n "x"))]
        (is (= text (get (json/read-json (str "{\"t\":" (json/write-json text) "}")) "t")))))
    (testing (str "escaped, " n " chars")
      (let [text (str/join (repeat (quot n 10) "abcdefghi\n"))]
        (is (= text (get (json/read-json (str "{\"t\":" (json/write-json text) "}")) "t")))))))

(deftest read-json-malformed-throws
  (doseq [bad ["" "{" "{bad" "[1,2" "\"unterminated" "\"bad \\q escape\"" "\"\\u00\""
               "not json at all" "{\"a\":}" "{\"a\" 1}" "[1 2]" "tru"]]
    (testing (pr-str bad)
      (let [e (thrown #(json/read-json bad))]
        (is (some? e))
        (is (= ::json/parse-error (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) "tools.agents.json/read-json: "))))))

;; ---------------------------------------------------------------------------
;; JSON Lines
;; ---------------------------------------------------------------------------

(deftest read-jsonl-decodes-one-value-per-line
  (is (= [{"a" 1} [2] "three" nil]
         (json/read-jsonl "{\"a\":1}\n[2]\n\"three\"\nnull\n")))
  (testing "no trailing newline, CRLF line endings"
    (is (= [1 2] (json/read-jsonl "1\r\n2"))))
  (testing "blank and whitespace-only lines are skipped"
    (is (= [1 2] (json/read-jsonl "\n1\n\n   \n\t\n2\n\n"))))
  (testing "empty input"
    (is (= [] (json/read-jsonl "")))))

(deftest read-jsonl-reads-from-a-reader
  (with-open [r (java.io.StringReader. "{\"id\":\"a\"}\n\n{\"id\":\"b\"}\n")]
    (is (= ["a" "b"] (mapv #(get % "id") (json/read-jsonl r))))))

(deftest read-jsonl-is-lazy
  ;; A malformed later line must not stop earlier values from being consumed.
  (let [s (json/read-jsonl "1\n2\n{bad\n")]
    (is (= [1 2] (take 2 s)))
    (is (some? (thrown #(doall s))))))

(deftest read-jsonl-errors-carry-the-line-number
  (let [e (thrown #(doall (json/read-jsonl "{\"ok\":1}\n\n{bad\n")))]
    (is (= ::json/parse-error (:type (ex-data e))))
    (is (= 3 (:line (ex-data e))))
    (is (str/starts-with? (ex-message e) "tools.agents.json/read-jsonl: line 3: "))))

;; ---------------------------------------------------------------------------
;; Per-caller error contract
;; ---------------------------------------------------------------------------

(deftest codec-carries-the-callers-error-contract
  (let [{:keys [read write read-jsonl key->str]}
        (json/codec {:prefix "my.ns" :encode-type :my.ns/enc :parse-type :my.ns/parse})]
    (is (= {"a" [1 "x\u0001"]} (read (write {:a [1 (str "x" (char 1))]}))))
    (is (= [1 2] (read-jsonl "1\n2")))
    (is (= "k" (key->str :k)))
    (testing "encode"
      (doseq [f [#(write 2/3) #(key->str 1)]]
        (let [e (thrown f)]
          (is (= :my.ns/enc (:type (ex-data e))))
          (is (str/starts-with? (ex-message e) "my.ns/write-json: ")))))
    (testing "parse"
      (let [e (thrown #(read "{bad"))]
        (is (= :my.ns/parse (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) "my.ns/read-json: "))))
    (testing "jsonl"
      (let [e (thrown #(doall (read-jsonl "1\nnope")))]
        (is (= {:type :my.ns/parse :line 2} (ex-data e)))
        (is (str/starts-with? (ex-message e) "my.ns/read-jsonl: line 2: "))))))

(deftest codec-fills-missing-opts-from-defaults
  (let [{:keys [read]} (json/codec {:prefix "only.prefix"})
        e (thrown #(read "{bad"))]
    (is (= ::json/parse-error (:type (ex-data e))))
    (is (str/starts-with? (ex-message e) "only.prefix/read-json: "))))
