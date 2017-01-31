(ns active.timbre-riemann
  "Timbre appenders that send output to Riemann."
  (:require [taoensso.timbre :as timbre]
            [riemann.client :as r])
  (:import (io.riemann.riemann.client OverloadedException)
           (java.io IOException)))

(defn- unix-time [instant]
  (quot (.getTime instant) 1000))

(defn- context-props [context opts]
  (if-let [f (:context-fn opts)]
    (f context)
    context))

(defn- riemann-events
  [data opts]
  ;; From timbre docs:
  ;; :config               ; Entire config map (this map, etc.)
  ;; :appender-id          ; Id of appender currently dispatching
  ;; :appender             ; Entire map of appender currently dispatching

  ;; :instant                 ; Platform date (java.util.Date or js/Date)
  ;; :level                   ; Keyword
  ;; :error-level?            ; Is level e/o #{:error :fatal}?
  ;; :?ns-str                 ; String,  or nil
  ;; :?file                   ; String,  or nil
  ;; :?line                   ; Integer, or nil ; Waiting on CLJ-865

  ;; :?err_                    ; Delay - first-arg platform error, or nil
  ;; :vargs_                   ; Delay - raw args vector
  ;; :hostname_                ; Delay - string (clj only)
  ;; :msg_                     ; Delay - args string
  ;; :timestamp_               ; Delay - string
  ;; :output-fn                ; (fn [data]) -> formatted output string
  ;;                                       ; (see `default-output-fn` for details)

  ;; :context          ; *context* value at log time (see `with-context`)
  ;; :profile-stats    ; From `profile` macro

  ;; =>
  ;; From riemann sources (hardly any docs!):
  ;; :host         ; string
  ;; :service      ; string
  ;; :state        ; string
  ;; :description  ; string
  ;; :metric       ; float, long or double.
  ;; :tags         ; seq of strings
  ;; :time         ; long, seconds since UTC epoch
  ;; :ttl          ; float
  ;; anything else, must be keyword => string.

  ;; TODO: send exceptions specially; stacktrace-fn? (r/send-exception, or build a similar event)
  
  (let [{:keys [events-fn defaults]} opts]
    (if events-fn
      (events-fn data)
      (let [{:keys [context instant hostname_ msg_ level ?ns-str]} data]
        [(merge {:host (force hostname_)
                 :service (or ?ns-str "timbre")
                 :state nil
                 :description (force msg_)
                 :metric nil
                 :tags nil
                 :time (unix-time instant)
                 :ttl nil
                 ;; extra properties
                 :level (name level)}
                defaults
                (context-props context opts))]))))

(defn- send-event [client event opts counter]
  (let [{:keys [retry-fn]} opts
        revents (riemann-events event opts)
        message-nr (swap! counter inc)]
    (loop [retry-counter 1]
      ;; Note: send-event never fails; deref'ing the promise it returns
      ;; may throw (IOException or OverloadedException when write
      ;; buffer limit is reached (~8192)). Otherwise the promise
      ;; delivers the result message from the server.
      (let [p (r/send-events client revents)]
        (when (retry-fn p retry-counter message-nr)
          (recur (inc retry-counter)))))))

(defn exponential-retry-fn [promise round message-nr]
  ;; - wait up to 4+4^2+4^3+4^4+4^5+4^6 = 5460ms for being able to
  ;; send. Continue to try in 4^6=4096ms intervals if it's an overload
  ;; on our side (so we only have big trouble if the connection cannot
  ;; deliver one message every ~4 seconds per thread). Drop event
  ;; otherwise.
  
  ;; - every 1000 messages, waits for response of
  ;; server (after it could be delivered, and only while the connection
  ;; keeps up).
  (let [wait-ms (.pow (bigint 4) (min 6 round))]
    (try 
      (if (zero? (mod message-nr 1000))
        (do (deref promise)
            false)
        (if (= ::timeout (deref promise 0 ::timeout))
          true
          false))
      (catch OverloadedException e
        (Thread/sleep wait-ms)
        true)
      (catch IOException e
        (if (< round 6)
          (do (Thread/sleep wait-ms)
              true)
          ;; otherwise, silently drop
          false)))))

(defn default-retry-fn [promise round message-nr]
  false)

;; TODO: use multiclient to parallelize (or allow multiple servers), and/or use r/batch-client ?

(def defaults
  {:host "localhost"
   :port 5555
   :retry-fn default-retry-fn})

(defn riemann-appender
  "Returns a Riemann appender. Options can be:
  
  1. connection related:
  - :client   a riemann client, or
  - :host, :port to create a riemann tcp-client (default
  localhost:5555), with additional
  options :tls? :key :cert :ca-cert :cache-dns?. See riemann-client
  documentation for more information.

  2. event related:
  - :defaults    a map of default riemann event properties (e.g. :ttl), defaults to nil.
  - :context-fn  a fn taking a riemann event and a timbre context, returning an updated riemann event, defaults to change nothing.
  to construct a riemann event, the following basic properties are set:
    :host and :time from the timbre event (Note that riemann timestamps only have a resolution of one second),
    :service is set to the namespace string, or \"timbre\",
    :description is set to the log message.
    and one 'custom' property is preset:
    :level is set to (name level)
  These basic properties map is then merges with the given :defaults and
  passed to :context-fn which can add or remove properites, so
  you can fully control the riemann event actually send out.
  Alternatively, you can specify a
  - :events-fn   a fn taking the timbre appender `data` structure, returning a list of riemann events.
    If you specify this, :defaults and :context-fn are not used, and no basic properties are created.

  3. behaviour related:
  - :retry-fn a fn called with the promise returned by
  riemann/send-event, a retry counter for each event, and an overall
  message count (per appender). That function may `deref` the promise
  to see if the event could be send and processed by the server, and
  return true to indicate that riemann/send-event should be called
  again (in which case the retry-fn will be called with an incremented
  second argument). If the function returns false, the event is not send again.
  If it throws, the exception hits back to Timbre, which will eventually
  let the log command fail (unless :async is true, afaik). This defaults to [[default-retry-fn]],
   which does not check the delivery or server response at all.

  The returned appender is `enabled?`, not `async?`, has no `min-level`
  and no `rate-limit` set. The `output-fn` setting is not used.
  "
  [opts]
  (let [opts (merge defaults
                    opts)
        counter (atom 0)
        conn (or (:client opts)
                 ;; Note tcp-client does not fail, even when there is
                 ;; no server (yet). Also does automatic reconnects
                 ;; allegedly (https://github.com/riemann/riemann-java-client/issues/54).
                 (r/tcp-client (select-keys opts [:host :port
                                                  :tls? :key
                                                  :cert :ca-cert
                                                  :cache-dns?])))]
    {:enabled?   true
     :async?     false
     :min-level  nil
     :rate-limit nil
     :output-fn  :inherit
     :fn
     (fn [data]
       (send-event conn data opts counter))}))
