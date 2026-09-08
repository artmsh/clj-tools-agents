(ns tools.agents.anthropic.visualize
  "Port of anthropic-cookbooks' tool_use/utils/visualize.py — a terminal
   tree+panel renderer for a Claude API response (role, model, stop_reason,
   token usage, and every content block: text, tool_use, tool_result,
   server_tool_use, code_execution_tool_result, and any unrecognized type as
   a raw-JSON fallback).

   Python name -> here: parse_response -> parse-response, parse_content_block
   -> parse-content-block, format_json -> format-json, visualize_message ->
   visualize-message (side-effecting: prints), class `visualize` ->
   `visualizer` (a plain map holding an atom, same 'no OO here' translation
   this library already applies to `client` — see README's parity table for
   that precedent), show_response -> show-response.

   NO DEPENDENCY: the Python original leans on the `rich` library (Tree,
   Panel, Syntax, Text, Console) for layout and syntax highlighting. There is
   no rich-equivalent available on both runtimes without adding one, so this
   is a small hand-rolled ANSI tree+panel renderer — same zero-dependency,
   data-transparency spirit as tools.agents.anthropic's own JSON codec.

   NO RUNTIME-SPECIFIC LEAF AT ALL: rendering text to a string and
   `println`-ing it needs no HTTP client, so unlike tools.agents.anthropic
   (whose `http-post!` is a reader conditional) this file has none.

   PLAIN ASCII (`+`, `-`, `|`, backtick) for all structural drawing —
   classic `tree`-CLI style, not Rich's rounded Unicode boxes. Every
   structural character is then one column wide, which is what makes the
   count-based width math below correct without a display-width table.
   Response TEXT CONTENT (arbitrary, possibly non-ASCII) is still truncated
   via `subs` at the same char counts as the Python original (1000/2000/500)
   for parity.

   split-lines and strip-ansi below are hand-rolled char-scans rather than a
   regex replace/split, to match tools.agents.anthropic's own JSON parser —
   hand-rolled there for a different reason (fine-grained error positions),
   and giving a ready-made peek-char/subs idiom to reuse here.

   parse-content-block also collapses a 3-way branch the Python original
   needs (isinstance(dict) / hasattr(SDK object, gives Anthropic SDK
   Message/ContentBlock instances) / str fallback) into ONE code path:
   tools.agents.anthropic's messages-create always returns a plain decoded
   (string-keyed) map — there is no second, object-typed representation to
   bridge here, so the whole hasattr/model_dump dance simply doesn't exist
   on this side."
  (:require [clojure.string :as str]
            [tools.agents.anthropic :as a]))

;; ---------------------------------------------------------------------------
;; ANSI color — a tiny SGR-code table, applied per already-positioned text
;; fragment (not re-measured afterward) so panel/tree width math never has to
;; reason about color at all except via strip-ansi/visible-length below.
;; ---------------------------------------------------------------------------

;; Built via `char`: a literal ESC has no string-literal escape in Clojure.
(def ^:private ESC (str (char 27)))

(def ^:private ansi-codes
  {:cyan "36" :green "32" :yellow "33" :red "31" :magenta "35" :white "37"
   :dim "2" :dim-white "2;37" :bold "1" :bold-cyan "1;36" :bold-yellow "1;33"})

(defn- colorize [color? style s]
  (if color?
    (str ESC "[" (get ansi-codes style "0") "m" s ESC "[0m")
    s))

(defn- strip-ansi
  "s with every ESC[...m SGR sequence removed — used only for panel-border
   width math. Manual char-scan (see ns docstring) rather than a regex
   replace, joining the scanned pieces with `str/join` rather than spreading
   a per-character vector through `apply str`."
  [s]
  (let [n (count s)]
    (loop [i 0 pieces []]
      (if (>= i n)
        (str/join pieces)
        (if (= (subs s i (inc i)) ESC)
          (let [end (loop [j (inc i)]
                      (if (or (>= j n) (= (subs s j (inc j)) "m"))
                        (inc j)
                        (recur (inc j))))]
            (recur end pieces))
          (recur (inc i) (conj pieces (subs s i (inc i)))))))))

(defn- visible-length
  "Width of s once SGR sequences are discounted. The `index-of` guard is the
   whole `{:color? false}` path — the one the docstring recommends for CI
   output and log files — which would otherwise rebuild every line
   character-by-character only to measure it."
  [s]
  (if (str/index-of s ESC)
    (count (strip-ansi s))
    (count s)))

;; ---------------------------------------------------------------------------
;; Small portable helpers — truncation, thousands-separator, line splitting.
;; ---------------------------------------------------------------------------

(defn- truncate
  ([s max-len] (truncate s max-len "\n... (truncated)"))
  ([s max-len marker] (if (> (count s) max-len) (str (subs s 0 max-len) marker) s)))

(defn- repeat-str
  "s repeated n times, joined — `str/join`, not `apply str` over n
   arguments; see strip-ansi's docstring."
  [n s]
  (str/join (repeat n s)))

(defn- group-digits
  "1234567 -> \"1,234,567\". Hand-rolled — see ns docstring's constraint 2."
  [n]
  (let [s (str (long n))
        neg? (str/starts-with? s "-")
        digits (if neg? (subs s 1) s)
        len (count digits)
        head-len (let [r (mod len 3)] (if (zero? r) 3 r))
        head (subs digits 0 head-len)
        rest-groups (loop [i head-len groups []]
                      (if (>= i len)
                        groups
                        (recur (+ i 3) (conj groups (subs digits i (+ i 3))))))]
    (str (when neg? "-") (str/join "," (cons head rest-groups)))))

(defn- split-lines
  "s split on \\n. Manual char-scan, not clojure.string/split with a regex —
   see ns docstring. A trailing \\n is treated as a line TERMINATOR, not a
   separator introducing one more (empty) line — matching clojure.string/
   split-lines and ordinary shell-output convention (\"42\\n\" is one line,
   not two). Without this, every stdout/stderr/text block ending in a
   newline — the common case for command output and multi-line assistant
   text — got a stray empty line at the end (verified empirically: the loop
   always conjes a final `(subs s start i)` == \"\" once it walks off the
   end right after consuming the last \\n, since `start` was just reset to
   that same position)."
  [s]
  (let [n (count s)
        lines (loop [i 0 start 0 lines []]
                (cond
                  (>= i n) (conj lines (subs s start i))
                  (= (subs s i (inc i)) "\n") (recur (inc i) (inc i) (conj lines (subs s start i)))
                  :else (recur (inc i) start lines)))]
    (if (and (pos? n) (str/ends-with? s "\n"))
      (pop lines)
      lines)))

(defn- number-lines
  "Right-aligned '<n> | <line>' prefix per line — port of Rich's
   Syntax(..., line_numbers=True), used only for server_tool_use code."
  [code]
  (let [lines (split-lines code)
        width (count (str (count lines)))]
    (str/join "\n"
      (map-indexed
        (fn [i line]
          (let [n (inc i) n-str (str n)]
            (str (repeat-str (max 0 (- width (count n-str))) " ") n-str " | " line)))
        lines))))

;; ---------------------------------------------------------------------------
;; Tree + panel rendering — plain-data tree {:label :children}, ASCII
;; connectors (see ns docstring constraint 1), rendered to lines and wrapped
;; in an ASCII box. Pure: no I/O anywhere in this section.
;; ---------------------------------------------------------------------------

(defn- node
  ([label] (node label []))
  ([label children] {:label label :children (vec (remove nil? children))}))

(defn- text-nodes
  "text (possibly containing \\n — a multi-paragraph response, a
   pretty-printed JSON blob, numbered code) split into one LEAF NODE PER
   LINE, never a single node whose :label embeds a raw newline: tree-lines/
   panel treat every :label as exactly one output line, so an embedded \\n
   would silently break the box-border alignment (extra characters after
   it, uncounted by visible-length, push everything past that point out of
   alignment) — this is what makes a multi-line tool_use \"Input:\" JSON dump
   or a multi-line assistant response actually readable inside the box."
  ([text] (map node (split-lines text)))
  ([color? style text] (map (fn [line] (node (colorize color? style line))) (split-lines text))))

(defn- render-children [children prefix]
  (let [n (count children)]
    (mapcat
      (fn [[idx child]]
        (let [last? (= idx (dec n))
              connector (if last? "`-- " "+-- ")
              cont (if last? "    " "|   ")]
          (cons (str prefix connector (:label child))
                (render-children (:children child) (str prefix cont)))))
      (map-indexed vector children))))

(defn- tree-lines [{:keys [label children]}]
  (cons label (render-children children "")))

(defn- panel
  "Wrap lines (already-rendered tree lines) in an ASCII box titled title."
  [root title]
  (let [lines (tree-lines root)
        ;; Measured once and carried: padding below needs the same width the
        ;; box was sized from, and measuring an ANSI-coloured line is a scan.
        widths (mapv visible-length lines)
        ;; `reduce`, not `(apply max 0 ...)`: apply spreads every line as a
        ;; separate argument, and a long render has thousands of them.
        content-w (reduce max 0 widths)
        title-bar (str " " title " ")
        inner-w (+ 2 (max content-w (count title-bar)))
        top-fill (max 0 (- inner-w 2 (count title-bar)))
        top (str "+--" title-bar (repeat-str top-fill "-") "+")
        bottom (str "+" (repeat-str inner-w "-") "+")
        pad-line (fn [line w]
                   (str "| " line
                        (repeat-str (max 0 (- inner-w 2 w)) " ")
                        " |"))]
    (str/join "\n" (concat [top] (map pad-line lines widths) [bottom]))))

;; ---------------------------------------------------------------------------
;; JSON pretty-printer — tools.agents.anthropic/write-json is deliberately
;; compact; this wraps it with indentation for scalar leaves only, reusing
;; its (already-portable, already-tested) string-escaping rather than
;; duplicating it.
;; ---------------------------------------------------------------------------

(defn- pretty-json
  ([v] (pretty-json v 0))
  ([v depth]
   (let [pad (repeat-str depth "  ")
         pad+ (repeat-str (inc depth) "  ")]
     (cond
       (map? v)
       (if (empty? v)
         "{}"
         (str "{\n"
              (str/join ",\n"
                (map (fn [[k val]]
                       ;; a/json-key->str, NOT a hand-rolled (if (string? k)
                       ;; k (name k)): that fragment silently mishandled
                       ;; symbol keys and threw a raw, untyped
                       ;; ClassCastException on anything else instead of
                       ;; write-json's own contracted typed :json-encode
                       ;; ex-info -- delegating reuses the real contract
                       ;; instead of re-deriving a narrower one.
                       (str pad+ (a/write-json (a/json-key->str k)) ": " (pretty-json val (inc depth))))
                     v))
              "\n" pad "}"))

       (or (vector? v) (seq? v))
       (if (empty? v)
         "[]"
         (str "[\n"
              (str/join ",\n" (map #(str pad+ (pretty-json % (inc depth))) v))
              "\n" pad "]"))

       :else (a/write-json v)))))

(defn format-json
  "data pretty-printed as JSON (2-space indent), truncated to max-length
   chars (default 500, matching the Python original) with a truncation
   marker appended — port of format_json."
  ([data] (format-json data 500))
  ([data max-length] (truncate (pretty-json data) max-length "\n  ... (truncated)")))

;; ---------------------------------------------------------------------------
;; Parsing — response map (from messages-create) -> display descriptor.
;; ---------------------------------------------------------------------------

(defn parse-content-block
  "Parse one item of a response's \"content\" array into {:type :data}. One
   code path, not Python's 3-way isinstance/hasattr branch — see ns
   docstring."
  [block]
  (cond
    (map? block) {:type (get block "type" "unknown") :data block}
    (string? block) {:type "text" :data {"text" block}}
    :else {:type "unknown" :data {"raw" (str block)}}))

(defn parse-response
  "Parse a decoded messages-create response map into a display descriptor:
   {:role :content :model :stop-reason :usage}. :content is a vector of
   parse-content-block results; :usage stays string-keyed, untouched
   passthrough (same posture as tool-calls' :input) — port of parse_response."
  [response]
  {:role (get response "role" "unknown")
   :content (mapv parse-content-block (or (get response "content") []))
   :model (get response "model")
   :stop-reason (get response "stop_reason")
   :usage (or (get response "usage") {})})

;; ---------------------------------------------------------------------------
;; Per-content-type rendering — one function per type, mirroring the Python
;; original's render_text_content / render_tool_use / render_tool_result /
;; render_server_tool_use / render_code_execution_result / render_content_block.
;; ---------------------------------------------------------------------------

(defn- render-text-content [{:keys [data]} color?]
  ;; A non-string "text" value is the same reachable wire shape
  ;; tools.agents.anthropic's output-text explicitly guards against (a
  ;; dedicated test proves it) -- but unlike output-text, whose job is to
  ;; return an exact string, this renderer's job is display, so it follows
  ;; render-tool-result's own established precedent elsewhere in this file
  ;; ((str v) for a non-string value) rather than throwing: coerce instead
  ;; of crashing. Without this, (seq 42) threw an untyped
  ;; IllegalArgumentException straight through render-message/show-response.
  (let [raw (get data "text")
        text (if (string? raw) raw (when (some? raw) (str raw)))]
    (when (seq text)
      (node (colorize color? :cyan "Text")
            (text-nodes color? :white (truncate text 1000))))))

(defn- render-tool-use [{:keys [data]} color?]
  (let [tool-name (get data "name" "unknown")
        tool-id (get data "id")
        tool-input (get data "input")
        caller (get data "caller")]
    (node (str (colorize color? :yellow "Tool Use:") " " (colorize color? :bold-yellow tool-name))
          [(when (seq tool-id)
             (node (str (colorize color? :dim-white "ID:") " " tool-id)))
           (when (map? caller)
             (let [caller-type (get caller "type" "unknown")
                   caller-label (cond
                                  (= caller-type "code_execution_20250825") "code execution environment"
                                  (= caller-type "direct") "model (direct)"
                                  :else caller-type)]
               (node (str (colorize color? :dim-white "Caller:") " " caller-label))))
           (when (and (map? tool-input) (seq tool-input))
             (node (colorize color? :green "Input:")
                   (text-nodes (format-json tool-input))))])))

(defn- render-server-tool-use [{:keys [data]} color?]
  (let [tool-id (get data "id")
        tool-input (get data "input")
        caller (get data "caller")
        code (when (map? tool-input) (get tool-input "code"))]
    (node (colorize color? :yellow "Server Tool Use")
          [(when (seq tool-id)
             (node (str (colorize color? :dim-white "ID:") " " tool-id)))
           (when (map? caller)
             (node (str (colorize color? :dim-white "Caller:") " " (get caller "type" "unknown"))))
           (cond
             (seq code)
             (node (colorize color? :green "Code:")
                   (text-nodes (number-lines (truncate code 1000))))

             (and (map? tool-input) (seq tool-input))
             (node (colorize color? :green "Input:")
                   (text-nodes (format-json tool-input)))

             :else nil)])))

(defn- render-tool-result [{:keys [data]} color?]
  (let [tool-id (get data "tool_use_id")
        is-error (get data "is_error" false)
        result-content (get data "content")
        status (if is-error (colorize color? :red "Error") (colorize color? :green "Success"))
        output-node (fn [v]
                      (node (colorize color? :cyan "Output:")
                            (text-nodes color? :white (truncate (str v) 1000))))
        output-nodes
        (cond
          (vector? result-content)
          (keep (fn [item]
                  (if (and (map? item) (= (get item "type") "text"))
                    (when (seq (get item "text")) (output-node (get item "text")))
                    (output-node item)))
                result-content)

          (and (string? result-content) (seq result-content)) [(output-node result-content)]
          (and (some? result-content) (not (string? result-content))) [(output-node result-content)]
          :else [])]
    (node (str (colorize color? :yellow "Tool Result:") " " status)
          (into [(when (seq tool-id)
                   (node (str (colorize color? :dim-white "Tool Use ID:") " " tool-id)))]
                output-nodes))))

(defn- render-code-execution-result [{:keys [data]} color?]
  (let [nested (get data "content")]
    (if (map? nested)
      ;; `(or (get nested "return_code") 0)`, NOT `(get nested "return_code"
      ;; 0)`: get's default only substitutes when the key is ABSENT, not
      ;; when present with an explicit JSON null (a killed/timed-out
      ;; execution with no real exit code) -- which decodes to Clojure nil
      ;; and crashed (zero? nil) with an unhandled NullPointerException.
      ;; `or` correctly falls through nil to the default (0 is truthy in
      ;; Clojure, so a genuine 0 exit code still passes through unchanged).
      (let [return-code (or (get nested "return_code") 0)
            stdout (get nested "stdout" "")
            stderr (get nested "stderr" "")
            status (if (zero? return-code)
                     (colorize color? :green (str "Success (exit " return-code ")"))
                     (colorize color? :red (str "Error (exit " return-code ")")))]
        (node (str (colorize color? :yellow "Code Execution Result:") " " status)
              [(when (seq stdout)
                 (node (colorize color? :green "stdout:")
                       (text-nodes color? :white (truncate stdout 2000))))
               (when (seq stderr)
                 (node (colorize color? :red "stderr:")
                       (text-nodes color? :white (truncate stderr 2000))))
               (when (and (empty? stdout) (empty? stderr))
                 (node (colorize color? :dim-white "(no output)")))]))
      (node (colorize color? :yellow "Code Execution Result")
            (text-nodes (format-json data))))))

(defn- render-content-block [{:keys [type] :as content} color?]
  (cond
    (= type "text") (render-text-content content color?)
    (= type "tool_use") (render-tool-use content color?)
    (= type "tool_result") (render-tool-result content color?)
    (= type "server_tool_use") (render-server-tool-use content color?)
    (= type "code_execution_tool_result") (render-code-execution-result content color?)
    :else (node (str (colorize color? :magenta "Unknown Type:") " " type)
                (text-nodes (format-json (:data content))))))

;; ---------------------------------------------------------------------------
;; Message-level assembly + public entry points.
;; ---------------------------------------------------------------------------

(defn- usage-suffix [usage color?]
  (when (seq usage)
    ;; `(or (get usage k) 0)`, NOT `(get usage k 0)` -- same null-vs-absent
    ;; gap as render-code-execution-result's return_code above: a present
    ;; "input_tokens"/"output_tokens" key holding JSON null decodes to
    ;; Clojure nil, and get's default only fires when the key is absent, so
    ;; (+ nil out) crashed every render-message/show-response/capture! call
    ;; with an untyped NullPointerException.
    (let [in (or (get usage "input_tokens") 0)
          out (or (get usage "output_tokens") 0)
          total (+ in out)]
      (str " | " (colorize color? :magenta "tokens:") " "
           (colorize color? :cyan (group-digits in)) " in, "
           (colorize color? :green (group-digits out)) " out, "
           (colorize color? :yellow (group-digits total)) " total"))))

(defn render-message
  "message (as produced by parse-response) rendered to the full panel
   string. Pure — builds and returns a string, prints nothing. opts:
   {:color? true}. Port of visualize_message, minus the printing (see
   visualize-message below, which just prints this)."
  ([message] (render-message message {}))
  ([{:keys [role content model stop-reason usage]} {:keys [color?] :or {color? true}}]
   (let [title (str (colorize color? :bold-cyan "Claude Message")
                     " (" (colorize color? :green (or role "unknown")) ")"
                     (or (usage-suffix usage color?) ""))
         meta-nodes (cond-> []
                      model (conj (node (str (colorize color? :dim-white "Model:") " " model)))
                      stop-reason (conj (node (str (colorize color? :dim-white "Stop Reason:") " " stop-reason))))
         content-node (when (seq content)
                        (node (str (colorize color? :bold "Content") " (" (count content) " blocks)")
                              (map-indexed
                                (fn [i block]
                                  (node (colorize color? :dim-white (str "Block " (inc i)))
                                        [(render-content-block block color?)]))
                                content)))
         root (node title (cond-> meta-nodes content-node (conj content-node)))]
     (panel root "Claude API Response"))))

(defn visualize-message
  "Print message's rendered panel to stdout. opts: {:color? true}."
  ([message] (visualize-message message {}))
  ([message opts] (println (render-message message opts))))

(defn show-response
  "Parse response (a raw messages-create response map) and print it — the
   simplest single-shot entry point, port of show_response."
  ([response] (show-response response {}))
  ([response opts] (visualize-message (parse-response response) opts)))

;; ---------------------------------------------------------------------------
;; Stateful capture session — port of the Python `visualize` context-manager
;; class. Same translation this library already applies to `client`: a plain
;; map (here holding an atom), not an object — see README's parity table.
;; ---------------------------------------------------------------------------

(defn visualizer
  "A capture session: {:responses (atom []) :auto-show? bool :color? bool}.
   opts:
     :auto-show? true — when true, capture! also prints immediately.
     :color? true — forwarded to every capture!/show-all! render. Set
     {:color? false} for a session piping to a log file or a non-TTY CI
     console that doesn't render ANSI. Previously there was no way to do
     this through the session API at all: capture!/show-all! called
     show-response with zero opts unconditionally, so the only workaround
     was bypassing the session wrapper entirely."
  ([] (visualizer {}))
  ([{:keys [auto-show? color?] :or {auto-show? true color? true}}]
   {:responses (atom []) :auto-show? auto-show? :color? color?}))

(defn capture!
  "Append response to viz's captured responses; print it too when
   :auto-show? is true, honoring viz's :color? setting. Returns viz."
  [viz response]
  (swap! (:responses viz) conj response)
  (when (:auto-show? viz) (show-response response {:color? (:color? viz)}))
  viz)

(defn show-all!
  "Print every response viz has captured so far, in capture order, honoring
   viz's :color? setting. Returns viz."
  [viz]
  (doseq [response @(:responses viz)] (show-response response {:color? (:color? viz)}))
  viz)

(defn clear!
  "Drop every response viz has captured so far, freeing whatever memory they
   held (e.g. base64 image/document content blocks). viz's :responses atom
   otherwise retains every captured response for the object's whole
   lifetime with no bound or clear mechanism — unlike the Python original's
   with-scoped context manager, whose captured state is released when the
   block exits, a long-running visualizer built purely to print each turn
   as it happens still accumulated every full response body in memory for
   as long as it was held. Returns viz."
  [viz]
  (reset! (:responses viz) [])
  viz)
