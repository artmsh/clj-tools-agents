(ns tools.agents.stream
  "Provider-agnostic streaming transport: one HTTP request whose 2xx body is
   read incrementally as Server-Sent Events.

   `open-event-stream` sends the request at CALL time and returns a
   single-use reducible over the stream's events. Everything a client needs
   to decide before the first byte (retries, status classification) happens
   inside that call; nothing is retried once `reduce` starts, so a retry can
   never replay events a reducer has already seen.

     (let [s (stream/open-event-stream
               {:request  {:method :post :url url :headers h :body body}
                :open!    (fn [attempt] (my-retry-loop attempt))
                :on-error (fn [{:keys [status headers body]}] (throw (my-typed-error ...)))
                :decode   read-json
                :done?    #(= \"[DONE]\" (:data %))})]
       (run! handle-event s))

   LIFECYCLE. The returned value is consumed by exactly one `reduce` (or
   `transduce`, `into`, `run!`, `eduction` reduction). That reduce closes the
   response InputStream in `finally`: on EOF, on a `done?` event, on early
   termination (`reduced`, e.g. `(into [] (take 1) s)`), and on any
   exception. A second reduce throws `::consumed`. A stream opened but never
   reduced must be released with `close!` (JVM: also `.close`/`with-open`,
   since the value implements java.io.Closeable there; Babashka's `reify`
   supports only one Java interface, so `close!` is the portable call).

   CANCELLATION. `(close! s)` is safe from any thread. It closes the
   InputStream, which unblocks a reduce parked in a blocking read; that
   reduce then returns the accumulated result normally instead of throwing,
   and `(outcome s)` is `:cancelled`. `close!` before the reduce starts
   makes the reduce return `init` untouched. There is no read-idle timeout:
   a stalled server blocks the read until someone calls `close!` (e.g. from
   a watchdog thread).

   TRUNCATION IS THE CALLER'S CONCERN. A stream that hits EOF without a
   terminal event reduces normally, exactly like a complete one; the SSE
   parser also silently drops an incomplete final event (see
   tools.agents.sse). Clients must check for their vendor's terminal marker
   (`message_stop`, `response.completed`, `[DONE]`, a Gemini `finishReason`)
   themselves, either while reducing or afterwards via `(outcome s)`:
   `:done` means `done?` matched; `:eof` means the body ended without it.

   A transport failure mid-stream (connection reset, chunked body cut off)
   propagates from `reduce` as the underlying IOException, or as whatever
   `:on-read-error` returns, so a client can map it to its own
   connection-error type."
  (:require [tools.agents.http :as http]
            [tools.agents.sse :as sse]))

(defprotocol EventStream
  (close! [s]
    "Close the underlying response body. Idempotent and thread-safe; a
     reduce blocked in a read on another thread ends cleanly.")
  (outcome [s]
    "How the stream ended: nil while not yet reduced or still reducing,
     then :done (`done?` matched), :eof (body ended with no `done?` event),
     :reduced (the reducing fn stopped early), :cancelled (`close!` was
     called) or :failed (an exception escaped the reduce).")
  (response [s]
    "{:status :headers} of the 2xx response the stream reads (headers
     normalized as by tools.agents.http/request!), e.g. for a request id."))

(defn- two-xx? [status] (and status (<= 200 status 299)))

(defn- slurp-and-close
  "The body of a non-2xx streamed response as a UTF-8 String, closing it."
  [^java.io.InputStream in]
  (when in
    (with-open [in in]
      (String. (.readAllBytes in) "UTF-8"))))

(defn- attempt-fn
  "One exchange: send with `:as :stream`. A 2xx response is returned with
   its body still an unread InputStream. Any other status has its body
   slurped to a String and closed, and is returned as
   {:status :headers :body String}. A transport exception propagates."
  [send! request]
  (fn []
    (let [resp (send! (assoc request :as :stream))]
      (if (two-xx? (:status resp))
        resp
        (assoc resp :body (slurp-and-close (:body resp)))))))

(defn- done-xf
  "Stop at the first event matching done? (the event itself is not emitted)."
  [done? outcome*]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result ev]
       (if (done? ev)
         (do (reset! outcome* :done) (reduced result))
         (rf result ev))))))

(defn- event-xform [{:keys [decode done? xform]} outcome*]
  (cond-> (comp (sse/lines) (sse/events))
    done?  (comp (done-xf done? outcome*))
    decode (comp (map #(update % :data decode)))
    xform  (comp xform)))

(def ^:private buffer-size 8192)

(defn- reduce-body
  [^java.io.InputStream in opts consumed? cancelled? outcome* f init]
  (when-not (compare-and-set! consumed? false true)
    (throw (ex-info "tools.agents.stream: event stream already consumed; it is single-use"
                    {:type ::consumed})))
  (if @cancelled?
    (do (.close in) (reset! outcome* :cancelled) init)
    (try
      (let [;; transduce-style wrap: the caller's f needs no completion arity
            rf  ((event-xform opts outcome*)
                 (fn ([acc] acc) ([acc x] (f acc x))))
            read-error (:on-read-error opts)
            rdr (java.io.InputStreamReader. in "UTF-8")
            buf (char-array buffer-size)]
        (loop [acc init]
          (let [n (try (.read rdr buf 0 buffer-size)
                       (catch java.io.IOException e
                         (cond
                           @cancelled?  ::cancelled
                           read-error   (throw (read-error e))
                           :else        (throw e))))]
            (cond
              (= ::cancelled n)
              (do (reset! outcome* :cancelled) (unreduced (rf acc)))

              (neg? (long n))
              (let [acc (rf acc)]
                (compare-and-set! outcome* nil :eof)
                (unreduced acc))

              :else
              (let [acc (rf acc (String. buf 0 (int n)))]
                (if (reduced? acc)
                  (let [acc (rf (unreduced acc))]
                    (compare-and-set! outcome* nil :reduced)
                    (unreduced acc))
                  (recur acc)))))))
      (catch Throwable t
        (reset! outcome* (if @cancelled? :cancelled :failed))
        (throw t))
      (finally
        (.close in)))))

(defn open-event-stream
  "Send one streaming request and return a single-use reducible of SSE
   events (see the ns docstring for lifecycle, cancellation and truncation).
   opts:

     :request       request map for tools.agents.http/request! — required.
                    `:as` is forced to :stream.
     :open!         (fn [attempt]) -> response. The caller's retry wrapper.
                    `attempt` is a zero-arg fn performing one exchange: it
                    returns a 2xx response with an unread InputStream body,
                    returns a non-2xx response whose :body is already a
                    String (read and closed), or throws the transport
                    exception. `open!` may call it repeatedly and must return
                    the response of the attempt it settles on, or throw.
                    Default: call `attempt` once.
     :on-error      (fn [{:keys [status headers body]}]) called with the
                    final non-2xx response (:body a String). Expected to
                    throw the client's typed error; if it returns, an
                    ex-info of type ::http-error is thrown instead.
     :decode        optional (fn [data-string]) -> value, applied to each
                    event's :data (e.g. a JSON reader). A decode exception
                    propagates from reduce.
     :done?         optional (fn [event]) -> truthy on the terminal event,
                    tested on the RAW event, before :decode (so OpenAI's
                    non-JSON `data: [DONE]` never reaches the decoder). The
                    matching event ends the stream and is not emitted.
     :xform         optional transducer applied to the (decoded) events.
     :on-read-error optional (fn [IOException]) -> Throwable thrown instead
                    of an IOException from reading the body mid-stream. Not
                    used on cancel, nor for exceptions thrown by the
                    reducing fn, :decode, :done? or :xform, which propagate
                    unwrapped.
     :send!         request fn, default tools.agents.http/request!.

   Each event is {:event :data :id :retry} as produced by
   tools.agents.sse/events, with :data decoded when :decode is given."
  [{:keys [request open! on-error send!] :as opts}]
  (let [send!      (or send! http/request!)
        open!      (or open! (fn [attempt] (attempt)))
        resp       (open! (attempt-fn send! request))
        status     (:status resp)]
    (when-not (two-xx? status)
      (when on-error (on-error resp))
      (throw (ex-info (str "tools.agents.stream/open-event-stream: HTTP " status)
                      {:type ::http-error :status status :headers (:headers resp) :body (:body resp)})))
    (let [^java.io.InputStream in (:body resp)
          consumed?  (atom false)
          cancelled? (atom false)
          outcome*   (atom nil)
          headers    (:headers resp)
          do-close   (fn []
                       (reset! cancelled? true)
                       (.close in))]
      #?(:bb
         (reify
           clojure.lang.IReduceInit
           (reduce [_ f init] (reduce-body in opts consumed? cancelled? outcome* f init))
           EventStream
           (close! [_] (do-close))
           (outcome [_] @outcome*)
           (response [_] {:status status :headers headers}))
         :clj
         (reify
           clojure.lang.IReduceInit
           (reduce [_ f init] (reduce-body in opts consumed? cancelled? outcome* f init))
           java.io.Closeable
           (close [_] (do-close))
           EventStream
           (close! [_] (do-close))
           (outcome [_] @outcome*)
           (response [_] {:status status :headers headers}))))))
