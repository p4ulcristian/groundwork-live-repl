(ns groundwork-live-repl.evaluations
  "Every evaluation written to the app's log: the nREPL handler, wrapped
   outside every middleware, so no middleware the app adds (shadow-cljs's
   ClojureScript REPL among them) can take an evaluation past it.

   An \"eval\" or \"load-file\" message writes two lines: before it runs,
   :groundwork-live-repl/evaluation-started, and when nREPL says it is done,
   :groundwork-live-repl/evaluated, with how it ended. An evaluation that never ends, or
   ends the JVM, still left the first. Values are not written: what a REPL
   returns in production is the customers' data."
  (:require
   [clojure.string :as str]
   [nrepl.transport :as transport])
  (:import
   (java.util.concurrent.atomic AtomicBoolean)))

(def ^:private evaluating-ops
  #{"eval" "load-file"})

(defn- describe-message
  "The data both lines carry: the op, the message's id, its session (nil:
   an ephemeral one), the namespace it names (nil: the session's current
   one), the code as sent (load-file: the file's text) and the file's
   path, when load-file names one."
  [{:keys [op id session ns code file file-path]}]
  (cond-> {:groundwork-live-repl/op         op
           :groundwork-live-repl/message-id id
           :groundwork-live-repl/session    session
           :groundwork-live-repl/namespace  ns
           :groundwork-live-repl/code       (if (= "load-file" op) file code)}
    (= "load-file" op) (assoc :groundwork-live-repl/file-path file-path)))

(defn- status-names [response]
  (into #{} (map name) (let [status (:status response)]
                         (if (coll? status) status (some-> status vector)))))

(defn- class-name
  "\"class java.lang.ArithmeticException\", as nREPL sends it, without
   \"class \"."
  [text]
  (some-> text str (str/replace-first #"^class " "")))

(defn- add-response
  "What the responses to one message said, so far."
  [heard response]
  (let [statuses (status-names response)]
    (cond-> (update heard :statuses into statuses)
      (contains? response :value) (update :values inc)
      (:ns response)              (assoc :ended-in-namespace (:ns response))
      (:ex response)              (assoc :error-class (class-name (:ex response))
                                         :root-error-class (class-name (:root-ex response))))))

(defn- outcome
  "How an evaluation ended: :threw (a form threw; the forms after it may
   have run), :interrupted, :refused (nREPL didn't run it: no code, a
   namespace it doesn't have), or :returned."
  [{:keys [statuses error-class values]}]
  (cond
    (or error-class (contains? statuses "eval-error")) :threw
    (contains? statuses "interrupted")                 :interrupted
    (and (contains? statuses "error") (zero? values))  :refused
    :else                                              :returned))

(defn- make-watching-transport
  "The connection's transport, telling on-response each response sent
   back for this message before it goes out."
  [inner on-response]
  (reify
    transport/Transport
    (recv [_] (transport/recv inner))
    (recv [_ timeout] (transport/recv inner timeout))
    (send [this response]
      (on-response response)
      (transport/send inner response)
      this)
    java.io.Closeable
    (close [_]
      (when (instance? java.io.Closeable inner)
        (.close ^java.io.Closeable inner)))))

(defn wrap-handler-with-evaluation-log
  "handler, the whole nREPL stack, with every eval and load-file written to
   log at level: :groundwork-live-repl/evaluation-started before it runs, and
   :groundwork-live-repl/evaluated once nREPL says it is done, with :groundwork-live-repl/outcome
   (:returned :threw :interrupted :refused), :groundwork-live-repl/values (how many came
   back), :groundwork-live-repl/ended-in-namespace (the namespace the last value came back
   in, nil when none did), and for :threw :groundwork-live-repl/error-class and
   :groundwork-live-repl/root-error-class. Other ops pass as they came."
  [handler log level]
  (fn [message]
    (if-not (contains? evaluating-ops (:op message))
      (handler message)
      (let [described (describe-message message)
            heard     (atom {:statuses #{} :values 0})
            written   (AtomicBoolean. false)
            on-response
            (fn [response]
              (let [now (swap! heard add-response response)]
                (when (and (contains? (:statuses now) "done")
                           (.compareAndSet written false true))
                  (log level :groundwork-live-repl/evaluated
                       (merge described
                              {:groundwork-live-repl/session            (or (:session message) (:session response))
                               :groundwork-live-repl/outcome            (outcome now)
                               :groundwork-live-repl/values             (:values now)
                               :groundwork-live-repl/ended-in-namespace (:ended-in-namespace now)}
                              (when (:error-class now)
                                {:groundwork-live-repl/error-class      (:error-class now)
                                 :groundwork-live-repl/root-error-class (:root-error-class now)}))))))]
        (log level :groundwork-live-repl/evaluation-started described)
        (handler (assoc message :transport (make-watching-transport (:transport message) on-response)))))))
