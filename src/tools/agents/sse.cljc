(ns tools.agents.sse
  "Pure Server-Sent Events (text/event-stream) parser. No I/O, no deps.

   Implements the WHATWG \"interpret an event stream\" algorithm
   (https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation),
   the same algorithm as openai-python's `SSEDecoder.decode`, except that an
   event with an empty data buffer is never dispatched (spec behaviour).

   Two stateful transducers compose into a parser:

     (lines)   string chunks -> lines. CR, LF and CRLF all terminate a line,
               including a CRLF split across two chunks. A trailing
               unterminated line is flushed at completion (like
               BufferedReader/readLine); `events` then discards it as an
               incomplete event.
     (events)  lines -> event maps {:event :data :id :retry}.

   Conveniences: `chunks->events`, `lines->events`, `parse-string`.

   Event map:
     :event  SSE event type; \"message\" when absent or empty.
     :data   data lines joined with \"\\n\" (one trailing LF removed, per spec).
     :id     last event ID. Persists across events until another `id:` line
             changes it (`id:` with empty value sets \"\"); nil if never set.
             An `id` value containing NUL is ignored.
     :retry  reconnection time in ms (long), stream-level like :id: the last
             valid `retry:` value seen so far, nil if never set. A value that
             is not all ASCII digits is ignored.

   Parsing rules: a leading U+FEFF BOM on the first line is stripped; a blank
   line dispatches; lines starting with `:` are comments; `field:value` has
   exactly one leading space stripped from value; a line with no colon is a
   field name with empty value; unknown fields are ignored; a data buffer that
   is empty at dispatch (no `data` line since the last dispatch) resets the
   event type and emits nothing; an incomplete event at end of input is
   discarded, so stream truncation must be detected by the caller (terminal
   marker such as `message_stop` / `[DONE]`).

   Stream-level concerns (reconnect, Last-Event-ID, JSON decoding, `[DONE]`)
   are deliberately out of scope."
  (:require [clojure.string :as str]))

(def ^:private bom "\uFEFF")

(defn lines
  "Stateful transducer: string chunks -> lines (without terminators).
   Handles CR, LF, CRLF, and a CRLF split across chunk boundaries. On
   completion, a non-empty pending partial line is emitted."
  []
  (fn [rf]
    (let [pending (volatile! [])   ; pieces of the current unterminated line
          cr?     (volatile! false) ; previous chunk ended in CR
          flush!  (fn [result piece]
                    (let [ps @pending
                          line (if (empty? ps) piece (apply str (conj ps piece)))]
                      (vreset! pending [])
                      (rf result line)))]
      (fn
        ([] (rf))
        ([result]
         (let [ps @pending]
           (vreset! pending [])
           (vreset! cr? false)
           (rf (if (empty? ps)
                 result
                 (unreduced (rf result (apply str ps)))))))
        ([result ^String chunk]
         (let [n    (count chunk)
               skip (if (and @cr? (pos? n) (= \newline (.charAt chunk 0))) 1 0)]
           (when (pos? n) (vreset! cr? false))
           (loop [result result
                  i      skip
                  start  skip]
             (cond
               (reduced? result) result

               (>= i n)
               (do (when (< start n) (vswap! pending conj (subs chunk start n)))
                   result)

               :else
               (let [c (.charAt chunk i)]
                 (case c
                   \newline
                   (recur (flush! result (subs chunk start i)) (inc i) (inc i))

                   \return
                   (let [res  (flush! result (subs chunk start i))
                         nxt  (inc i)]
                     (cond
                       (= nxt n)                        (do (vreset! cr? true) res)
                       (= \newline (.charAt chunk nxt)) (recur res (inc nxt) (inc nxt))
                       :else                            (recur res nxt nxt)))

                   (recur result (inc i) start)))))))))))

(defn- digits? [s]
  (some? (re-matches #"[0-9]+" s)))

(defn events
  "Stateful transducer: lines -> event maps {:event :data :id :retry}.
   See the ns docstring for the rules. Pending (undispatched) event state is
   discarded on completion."
  []
  (fn [rf]
    (let [first? (volatile! true)
          data   (volatile! nil)   ; nil = empty buffer, else vector of lines
          etype  (volatile! "")
          id     (volatile! nil)
          retry  (volatile! nil)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result ^String line]
         (let [line (if @first?
                      (do (vreset! first? false)
                          (if (str/starts-with? line bom) (subs line 1) line))
                      line)]
           (cond
             (= "" line)
             (let [d @data
                   t @etype]
               (vreset! data nil)
               (vreset! etype "")
               (if (nil? d)
                 result
                 (rf result {:event (if (= "" t) "message" t)
                             :data  (str/join "\n" d)
                             :id    @id
                             :retry @retry})))

             (str/starts-with? line ":")
             result

             :else
             (let [idx   (str/index-of line ":")
                   field (if idx (subs line 0 idx) line)
                   value (if idx
                           (let [v (subs line (inc idx))]
                             (if (str/starts-with? v " ") (subs v 1) v))
                           "")]
               (case field
                 "event" (vreset! etype value)
                 "data"  (vswap! data (fnil conj []) value)
                 "id"    (when-not (str/includes? value "\u0000") (vreset! id value))
                 "retry" (when (digits? value)
                           (when-let [ms (parse-long value)] (vreset! retry ms)))
                 nil)
               result))))))))

(defn lines->events
  "Eagerly parse a collection of lines into a vector of event maps."
  [coll]
  (into [] (events) coll))

(defn chunks->events
  "Eagerly parse a collection of arbitrary string chunks into a vector of
   event maps."
  [coll]
  (into [] (comp (lines) (events)) coll))

(defn parse-string
  "Parse a complete event-stream body into a vector of event maps."
  [s]
  (chunks->events [s]))
