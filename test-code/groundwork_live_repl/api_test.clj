(ns groundwork-live-repl.api-test
  "Each mode on loopback, a public bind refused, evaluations written to the
   log (talked to with an nREPL client), the tools reachable, :off opening
   nothing, the emergency switch, the app's middleware, and one groundwork-live-repl at a
   time."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [malli.core :as malli]
   [nrepl.core :as nrepl]
   [groundwork-live-repl.api :as live-repl])
  (:import
   (java.net ConnectException Socket)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- make-log-into
  "The app's log, a real groundwork-logs's, its lines going into texts as JSON."
  [texts]
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 10
                                               :write-to     #(swap! texts conj %)})))

(defn- read-lines [texts]
  (mapv json/read-value @texts))

(defn- lines-of [texts event]
  (filterv #(= event (get % "event")) (read-lines texts)))

(defn- make-temp-folder []
  (str (Files/createTempDirectory "live-repl-test" (make-array FileAttribute 0))))

(def ^:private tools
  {:echo     {:doc "Gives back what it is given, in a vector." :function (fn [x] [:echo x])}
   :list     {:doc "A name clojure.core has too." :function (fn [] :the-apps-list)}})

(defn- options [texts]
  {:log (make-log-into texts) :tools tools})

(defn- evaluate
  "The values code returns, sent as an eval on a fresh connection, in a
   cloned session; and the session's id."
  ([port code] (evaluate port code {}))
  ([port code message]
   (with-open [connection (nrepl/connect :port port)]
     (let [client    (nrepl/client connection 10000)
           session   (nrepl/new-session client)
           responses (doall (nrepl/message client (merge {:op "eval" :code code :session session} message)))]
       {:session   session
        :responses responses
        :values    (vec (nrepl/response-values responses))}))))

(defn- listening? [port]
  (try (with-open [_ (Socket. "127.0.0.1" (int port))] true)
       (catch ConnectException _ false)))

(defn- wait-until [f]
  (loop [n 200]
    (or (f)
        (when (pos? n)
          (Thread/sleep 25)
          (recur (dec n))))))

(defn- error-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- development [port-file]
  {:mode :development :bind-address "127.0.0.1" :port 0 :middleware [] :port-file port-file})

;; -----------------------------------------------------------------------------
;; The modes

(deftest development-on-loopback
  (let [texts     (atom [])
        port-file (str (io/file (make-temp-folder) ".nrepl-port"))
        repl      (live-repl/start-repl-server! (development port-file) (options texts))
        port      (live-repl/find-repl-port repl)]
    (try
      (is (pos-int? port))
      (is (= [3] (:values (evaluate port "(+ 1 2)"))))
      (is (= (str port) (slurp port-file)) "the port file says the port")
      (is (= [{"event" "groundwork-live-repl/started"
               "data"  {"groundwork-live-repl/mode" "development" "groundwork-live-repl/bind-address" "127.0.0.1" "groundwork-live-repl/port" port
                        "groundwork-live-repl/middleware" [] "groundwork-live-repl/tools" ["echo" "list"]}}]
             (mapv #(select-keys % ["event" "data"]) (lines-of texts "groundwork-live-repl/started"))))
      (finally (live-repl/stop-repl-server! repl)))
    (is (not (listening? port)) "stopped: nothing listens")
    (is (nil? (live-repl/find-repl-port repl)))
    (is (not (.exists (io/file port-file))) "stopped: the port file is gone")
    (is (= 1 (count (lines-of texts "groundwork-live-repl/stopped"))))))

(deftest ssh-only-on-loopback
  (let [texts (atom [])
        repl  (live-repl/start-repl-server! {:mode :ssh-only :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}
                                        (options texts))
        port  (live-repl/find-repl-port repl)]
    (try
      (is (= [3] (:values (evaluate port "(+ 1 2)"))))
      (is (= "127.0.0.1" (.getHostAddress (.getInetAddress ^java.net.ServerSocket (:server-socket (:server (:server repl))))))
          "listening on loopback")
      (finally (live-repl/stop-repl-server! repl)))
    (is (not (listening? port)))))

(deftest off-starts-nothing
  (let [texts (atom [])
        repl  (live-repl/start-repl-server! {:mode :off :emergency-switch nil} (options texts))]
    (try
      (is (nil? (live-repl/find-repl-port repl)))
      (is (nil? (:watcher repl)) "no switch: nothing looks for a file")
      (is (nil? (resolve 'groundwork-live-repl.tools/echo)) "no REPL: no tools")
      (is (= [{"groundwork-live-repl/mode" "off" "groundwork-live-repl/emergency-switch" nil "groundwork-live-repl/tools" ["echo" "list"]}]
             (mapv #(get % "data") (lines-of texts "groundwork-live-repl/started"))))
      (finally (live-repl/stop-repl-server! repl)))))

;; -----------------------------------------------------------------------------
;; Binding

(deftest a-public-bind-is-refused
  (testing ":ssh-only takes loopback only"
    (let [data (error-data #(live-repl/start-repl-server! {:mode :ssh-only :bind-address "0.0.0.0" :port 0 :middleware [] :port-file nil}
                                                      (options (atom []))))]
      (is (= :groundwork-live-repl/bad-settings (:type data)))
      (is (= {:bind-address ["should be a loopback address, like \"127.0.0.1\" or \"::1\": outside :development groundwork-live-repl listens on loopback only"]}
             (:errors data)))))
  (testing "nor does the emergency switch open anything else"
    (is (= :groundwork-live-repl/bad-settings
           (error-type #(live-repl/start-repl-server! {:mode :off
                                                   :emergency-switch {:file "/run/app/live-repl-open" :check-every-ms 1000
                                                                      :bind-address "203.0.113.10" :port 0 :middleware [] :port-file nil}}
                                                  (options (atom [])))))))
  (testing "a name is refused: what it resolves to isn't the settings'"
    (is (= :groundwork-live-repl/bad-settings
           (error-type #(live-repl/start-repl-server! {:mode :ssh-only :bind-address "localhost" :port 0 :middleware [] :port-file nil}
                                                  (options (atom [])))))))
  (testing ":development binds another address only when it is written out"
    (is (malli/validate live-repl/settings-shape (assoc (development nil) :bind-address "0.0.0.0")))
    (is (not (malli/validate live-repl/settings-shape (dissoc (development nil) :bind-address))))))

(deftest every-key-is-written-out
  (let [data (error-data #(live-repl/start-repl-server! {:mode :development :port 0 :surprise true} {:tools {:a/b {}}}))]
    (is (= :groundwork-live-repl/bad-settings (:type data)))
    (is (= #{:bind-address :middleware :port-file :surprise} (set (keys (:errors data))))))
  (is (= :groundwork-live-repl/bad-settings (error-type #(live-repl/start-repl-server! {:mode :production} (options (atom []))))))
  (let [data (error-data #(live-repl/start-repl-server! (development nil) {:tools {:a/b {:doc "x" :function inc}}}))]
    (is (= :groundwork-live-repl/bad-options (:type data)))
    (is (= #{:log :tools} (set (keys (:errors data))))))
  (is (nil? (resolve 'groundwork-live-repl.tools/echo)) "refused: nothing put"))

;; -----------------------------------------------------------------------------
;; The log

(deftest an-evaluation-is-written-to-the-log
  (let [texts (atom [])
        repl  (live-repl/start-repl-server! (development nil) (options texts))
        port  (live-repl/find-repl-port repl)]
    (try
      (let [{:keys [session values]} (evaluate port "(+ 1 2)" {:ns "user"})]
        (is (= [3] values))
        (is (= [{"level" "info"
                 "data"  {"groundwork-live-repl/op" "eval" "groundwork-live-repl/session" session "groundwork-live-repl/namespace" "user" "groundwork-live-repl/code" "(+ 1 2)"}}]
               (mapv #(-> % (select-keys ["level" "data"]) (update "data" dissoc "groundwork-live-repl/message-id"))
                     (lines-of texts "groundwork-live-repl/evaluation-started"))))
        (is (= [{"level" "info"
                 "data"  {"groundwork-live-repl/op" "eval" "groundwork-live-repl/session" session "groundwork-live-repl/namespace" "user" "groundwork-live-repl/code" "(+ 1 2)"
                          "groundwork-live-repl/outcome" "returned" "groundwork-live-repl/values" 1 "groundwork-live-repl/ended-in-namespace" "user"}}]
               (mapv #(-> % (select-keys ["level" "data"]) (update "data" dissoc "groundwork-live-repl/message-id"))
                     (lines-of texts "groundwork-live-repl/evaluated"))))
        (is (= (get-in (first (lines-of texts "groundwork-live-repl/evaluation-started")) ["data" "groundwork-live-repl/message-id"])
               (get-in (first (lines-of texts "groundwork-live-repl/evaluated")) ["data" "groundwork-live-repl/message-id"]))
            "both lines name the same message"))
      (testing "the value itself is never written"
        (reset! texts [])
        (evaluate port "(str \"secret-\" \"value\")")
        (is (not-any? #(re-find #"secret-value" %) @texts)))
      (testing "an evaluation that throws"
        (reset! texts [])
        (evaluate port "(/ 1 0)")
        (is (= {"groundwork-live-repl/outcome" "threw" "groundwork-live-repl/values" 0
                "groundwork-live-repl/error-class" "java.lang.ArithmeticException" "groundwork-live-repl/root-error-class" "java.lang.ArithmeticException"}
               (select-keys (get (first (lines-of texts "groundwork-live-repl/evaluated")) "data")
                            ["groundwork-live-repl/outcome" "groundwork-live-repl/values" "groundwork-live-repl/error-class" "groundwork-live-repl/root-error-class"]))))
      (testing "a namespace nREPL doesn't have"
        (reset! texts [])
        (evaluate port "(+ 1 2)" {:ns "no.such.namespace"})
        (is (= "refused" (get-in (first (lines-of texts "groundwork-live-repl/evaluated")) ["data" "groundwork-live-repl/outcome"]))))
      (testing "a load-file, its text as the code"
        (reset! texts [])
        (with-open [connection (nrepl/connect :port port)]
          (let [client (nrepl/client connection 10000)]
            (is (= [7] (nrepl/response-values (nrepl/message client {:op "load-file" :file "(ns groundwork_live_repl.loaded)\n(+ 3 4)"
                                                                     :file-path "/tmp/loaded.clj" :file-name "loaded.clj"}))))))
        (is (= {"groundwork-live-repl/op" "load-file" "groundwork-live-repl/code" "(ns groundwork_live_repl.loaded)\n(+ 3 4)" "groundwork-live-repl/file-path" "/tmp/loaded.clj"}
               (select-keys (get (first (lines-of texts "groundwork-live-repl/evaluated")) "data")
                            ["groundwork-live-repl/op" "groundwork-live-repl/code" "groundwork-live-repl/file-path"]))))
      (testing "other ops are not evaluations"
        (reset! texts [])
        (with-open [connection (nrepl/connect :port port)]
          (doall (nrepl/message (nrepl/client connection 10000) {:op "describe"})))
        (is (= [] @texts)))
      (finally (live-repl/stop-repl-server! repl)))))

;; -----------------------------------------------------------------------------
;; Tools and middleware

(deftest the-tools-are-reachable
  (let [repl (live-repl/start-repl-server! (development nil) (options (atom [])))
        port (live-repl/find-repl-port repl)]
    (try
      (is (= [[:echo 5]] (:values (evaluate port "(groundwork-live-repl.tools/echo 5)"))))
      (is (= [:the-apps-list] (:values (evaluate port "(groundwork-live-repl.tools/list)"))) "a name clojure.core has too")
      (is (= ["Gives back what it is given, in a vector."] (:values (evaluate port "(:doc (meta #'groundwork-live-repl.tools/echo))"))))
      (is (= [nil [3 :echo]] (:values (evaluate port "(require '[groundwork-live-repl.tools :as tools]) [(clojure.core/+ 1 2) (first (tools/echo 1))]"))))
      (finally (live-repl/stop-repl-server! repl)))
    (is (nil? (resolve 'groundwork-live-repl.tools/echo)) "closed: the tools are taken away")))

(deftest the-apps-middleware-is-added
  (let [repl (live-repl/start-repl-server! (assoc (development nil) :middleware '[groundwork-live-repl.test-middleware/wrap-hello])
                                       (options (atom [])))]
    (try
      (with-open [connection (nrepl/connect :port (live-repl/find-repl-port repl))]
        (is (= "from the app's middleware"
               (:hello (first (nrepl/message (nrepl/client connection 10000) {:op "live-repl-test/hello"}))))))
      (finally (live-repl/stop-repl-server! repl))))
  (testing "without it, the op is unknown: nothing is added unwritten"
    (let [repl (live-repl/start-repl-server! (development nil) (options (atom [])))]
      (try
        (with-open [connection (nrepl/connect :port (live-repl/find-repl-port repl))]
          (is (contains? (set (:status (first (nrepl/message (nrepl/client connection 10000) {:op "live-repl-test/hello"}))))
                         "unknown-op")))
        (finally (live-repl/stop-repl-server! repl)))))
  (testing "a symbol naming no var refuses, and nothing is left running"
    (is (= {:type :groundwork-live-repl/middleware-not-found :symbols '[no.such/middleware live-repl/nope]}
           (error-data #(live-repl/start-repl-server! (assoc (development nil) :middleware '[no.such/middleware live-repl/nope])
                                                  (options (atom []))))))
    (let [repl (live-repl/start-repl-server! (development nil) (options (atom [])))]
      (live-repl/stop-repl-server! repl))))

(deftest one-live-repl-at-a-time
  (let [repl (live-repl/start-repl-server! (development nil) (options (atom [])))]
    (try
      (is (= :groundwork-live-repl/already-running (error-type #(live-repl/start-repl-server! (development nil) (options (atom []))))))
      (finally (live-repl/stop-repl-server! repl))))
  (let [repl (live-repl/start-repl-server! (development nil) (options (atom [])))]
    (is (pos-int? (live-repl/find-repl-port repl)) "stopped: the next one starts")
    (live-repl/stop-repl-server! repl)))

;; -----------------------------------------------------------------------------
;; The emergency switch

(defn- switch-settings [file]
  {:mode :off
   :emergency-switch {:file file :check-every-ms 20
                      :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}})

(deftest the-emergency-switch
  (let [texts (atom [])
        file  (io/file (make-temp-folder) "live-repl-open")
        repl  (live-repl/start-repl-server! (switch-settings (str file)) (options texts))]
    (try
      (Thread/sleep 100)
      (is (nil? (live-repl/find-repl-port repl)) "no file: nothing listens")
      (is (nil? (resolve 'groundwork-live-repl.tools/echo)))
      (testing "the file there: a REPL on loopback, its evaluations written at :warn"
        (spit file "")
        (is (wait-until #(live-repl/find-repl-port repl)))
        (let [port (live-repl/find-repl-port repl)]
          (is (= [[:echo 1]] (:values (evaluate port "(groundwork-live-repl.tools/echo 1)"))))
          (is (= [{"level" "warn" "data" {"groundwork-live-repl/file" (str file) "groundwork-live-repl/bind-address" "127.0.0.1" "groundwork-live-repl/port" port}}]
                 (mapv #(select-keys % ["level" "data"]) (lines-of texts "groundwork-live-repl/emergency-opened"))))
          (is (= ["warn" "warn"] (mapv #(get % "level") (concat (lines-of texts "groundwork-live-repl/evaluation-started")
                                                                (lines-of texts "groundwork-live-repl/evaluated")))))
          (testing "the file gone: closed"
            (io/delete-file file)
            (is (wait-until #(nil? (live-repl/find-repl-port repl))))
            (is (not (listening? port)))
            (is (nil? (resolve 'groundwork-live-repl.tools/echo)))
            (is (= 1 (count (lines-of texts "groundwork-live-repl/emergency-closed")))))))
      (testing "a folder in its place is refused, once"
        (.mkdir file)
        (is (wait-until #(seq (lines-of texts "groundwork-live-repl/emergency-switch-refused"))))
        (Thread/sleep 100)
        (is (= [{"groundwork-live-repl/file" (str file) "groundwork-live-repl/reason" "not a regular file (a link or a folder)"}]
               (mapv #(get % "data") (lines-of texts "groundwork-live-repl/emergency-switch-refused"))))
        (is (nil? (live-repl/find-repl-port repl)))
        (.delete file)
        (Thread/sleep 100))
      (testing "a link in its place is refused"
        (let [target (io/file (make-temp-folder) "elsewhere")]
          (spit target "")
          (Files/createSymbolicLink (.toPath file) (.toPath target) (make-array FileAttribute 0))
          (is (wait-until #(= 2 (count (lines-of texts "groundwork-live-repl/emergency-switch-refused")))))
          (is (nil? (live-repl/find-repl-port repl)))
          (.delete file)
          (Thread/sleep 100)))
      (testing "on again, then stopped: the stop closes it"
        (spit file "")
        (is (wait-until #(live-repl/find-repl-port repl))))
      (finally (live-repl/stop-repl-server! repl)))
    (is (nil? (live-repl/find-repl-port repl)))
    (is (= 2 (count (lines-of texts "groundwork-live-repl/emergency-closed"))))
    (is (nil? (resolve 'groundwork-live-repl.tools/echo)))))
