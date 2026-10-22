(ns study.api-test
  "Each mode on loopback, a public bind refused, evaluations written to the
   log (talked to with an nREPL client), the tools reachable, :off opening
   nothing, the emergency switch, the app's middleware, and one study at a
   time."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [logbook.api :as logbook]
   [malli.core :as malli]
   [nrepl.core :as nrepl]
   [study.api :as study])
  (:import
   (java.net ConnectException Socket)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- make-log-into
  "The app's log, a real logbook's, its lines going into texts as JSON."
  [texts]
  (logbook/log-function (logbook/make-logbook {:lowest-level :debug :format :json :errors-kept 10
                                               :write-to     #(swap! texts conj %)})))

(defn- read-lines [texts]
  (mapv json/read-value @texts))

(defn- lines-of [texts event]
  (filterv #(= event (get % "event")) (read-lines texts)))

(defn- make-temp-folder []
  (str (Files/createTempDirectory "study-test" (make-array FileAttribute 0))))

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
        repl      (study/start-repl-server! (development port-file) (options texts))
        port      (study/find-repl-port repl)]
    (try
      (is (pos-int? port))
      (is (= [3] (:values (evaluate port "(+ 1 2)"))))
      (is (= (str port) (slurp port-file)) "the port file says the port")
      (is (= [{"event" "study/started"
               "data"  {"study/mode" "development" "study/bind-address" "127.0.0.1" "study/port" port
                        "study/middleware" [] "study/tools" ["echo" "list"]}}]
             (mapv #(select-keys % ["event" "data"]) (lines-of texts "study/started"))))
      (finally (study/stop-repl-server! repl)))
    (is (not (listening? port)) "stopped: nothing listens")
    (is (nil? (study/find-repl-port repl)))
    (is (not (.exists (io/file port-file))) "stopped: the port file is gone")
    (is (= 1 (count (lines-of texts "study/stopped"))))))

(deftest ssh-only-on-loopback
  (let [texts (atom [])
        repl  (study/start-repl-server! {:mode :ssh-only :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}
                                        (options texts))
        port  (study/find-repl-port repl)]
    (try
      (is (= [3] (:values (evaluate port "(+ 1 2)"))))
      (is (= "127.0.0.1" (.getHostAddress (.getInetAddress ^java.net.ServerSocket (:server-socket (:server (:server repl))))))
          "listening on loopback")
      (finally (study/stop-repl-server! repl)))
    (is (not (listening? port)))))

(deftest off-starts-nothing
  (let [texts (atom [])
        repl  (study/start-repl-server! {:mode :off :emergency-switch nil} (options texts))]
    (try
      (is (nil? (study/find-repl-port repl)))
      (is (nil? (:watcher repl)) "no switch: nothing looks for a file")
      (is (nil? (resolve 'study.tools/echo)) "no REPL: no tools")
      (is (= [{"study/mode" "off" "study/emergency-switch" nil "study/tools" ["echo" "list"]}]
             (mapv #(get % "data") (lines-of texts "study/started"))))
      (finally (study/stop-repl-server! repl)))))

;; -----------------------------------------------------------------------------
;; Binding

(deftest a-public-bind-is-refused
  (testing ":ssh-only takes loopback only"
    (let [data (error-data #(study/start-repl-server! {:mode :ssh-only :bind-address "0.0.0.0" :port 0 :middleware [] :port-file nil}
                                                      (options (atom []))))]
      (is (= :study/bad-settings (:type data)))
      (is (= {:bind-address ["should be a loopback address, like \"127.0.0.1\" or \"::1\": outside :development study listens on loopback only"]}
             (:errors data)))))
  (testing "nor does the emergency switch open anything else"
    (is (= :study/bad-settings
           (error-type #(study/start-repl-server! {:mode :off
                                                   :emergency-switch {:file "/run/app/study-open" :check-every-ms 1000
                                                                      :bind-address "203.0.113.10" :port 0 :middleware [] :port-file nil}}
                                                  (options (atom [])))))))
  (testing "a name is refused: what it resolves to isn't the settings'"
    (is (= :study/bad-settings
           (error-type #(study/start-repl-server! {:mode :ssh-only :bind-address "localhost" :port 0 :middleware [] :port-file nil}
                                                  (options (atom [])))))))
  (testing ":development binds another address only when it is written out"
    (is (malli/validate study/settings-shape (assoc (development nil) :bind-address "0.0.0.0")))
    (is (not (malli/validate study/settings-shape (dissoc (development nil) :bind-address))))))

(deftest every-key-is-written-out
  (let [data (error-data #(study/start-repl-server! {:mode :development :port 0 :surprise true} {:tools {:a/b {}}}))]
    (is (= :study/bad-settings (:type data)))
    (is (= #{:bind-address :middleware :port-file :surprise} (set (keys (:errors data))))))
  (is (= :study/bad-settings (error-type #(study/start-repl-server! {:mode :production} (options (atom []))))))
  (let [data (error-data #(study/start-repl-server! (development nil) {:tools {:a/b {:doc "x" :function inc}}}))]
    (is (= :study/bad-options (:type data)))
    (is (= #{:log :tools} (set (keys (:errors data))))))
  (is (nil? (resolve 'study.tools/echo)) "refused: nothing put"))

;; -----------------------------------------------------------------------------
;; The log

(deftest an-evaluation-is-written-to-the-log
  (let [texts (atom [])
        repl  (study/start-repl-server! (development nil) (options texts))
        port  (study/find-repl-port repl)]
    (try
      (let [{:keys [session values]} (evaluate port "(+ 1 2)" {:ns "user"})]
        (is (= [3] values))
        (is (= [{"level" "info"
                 "data"  {"study/op" "eval" "study/session" session "study/namespace" "user" "study/code" "(+ 1 2)"}}]
               (mapv #(-> % (select-keys ["level" "data"]) (update "data" dissoc "study/message-id"))
                     (lines-of texts "study/evaluation-started"))))
        (is (= [{"level" "info"
                 "data"  {"study/op" "eval" "study/session" session "study/namespace" "user" "study/code" "(+ 1 2)"
                          "study/outcome" "returned" "study/values" 1 "study/ended-in-namespace" "user"}}]
               (mapv #(-> % (select-keys ["level" "data"]) (update "data" dissoc "study/message-id"))
                     (lines-of texts "study/evaluated"))))
        (is (= (get-in (first (lines-of texts "study/evaluation-started")) ["data" "study/message-id"])
               (get-in (first (lines-of texts "study/evaluated")) ["data" "study/message-id"]))
            "both lines name the same message"))
      (testing "the value itself is never written"
        (reset! texts [])
        (evaluate port "(str \"secret-\" \"value\")")
        (is (not-any? #(re-find #"secret-value" %) @texts)))
      (testing "an evaluation that throws"
        (reset! texts [])
        (evaluate port "(/ 1 0)")
        (is (= {"study/outcome" "threw" "study/values" 0
                "study/error-class" "java.lang.ArithmeticException" "study/root-error-class" "java.lang.ArithmeticException"}
               (select-keys (get (first (lines-of texts "study/evaluated")) "data")
                            ["study/outcome" "study/values" "study/error-class" "study/root-error-class"]))))
      (testing "a namespace nREPL doesn't have"
        (reset! texts [])
        (evaluate port "(+ 1 2)" {:ns "no.such.namespace"})
        (is (= "refused" (get-in (first (lines-of texts "study/evaluated")) ["data" "study/outcome"]))))
      (testing "a load-file, its text as the code"
        (reset! texts [])
        (with-open [connection (nrepl/connect :port port)]
          (let [client (nrepl/client connection 10000)]
            (is (= [7] (nrepl/response-values (nrepl/message client {:op "load-file" :file "(ns study.loaded)\n(+ 3 4)"
                                                                     :file-path "/tmp/loaded.clj" :file-name "loaded.clj"}))))))
        (is (= {"study/op" "load-file" "study/code" "(ns study.loaded)\n(+ 3 4)" "study/file-path" "/tmp/loaded.clj"}
               (select-keys (get (first (lines-of texts "study/evaluated")) "data")
                            ["study/op" "study/code" "study/file-path"]))))
      (testing "other ops are not evaluations"
        (reset! texts [])
        (with-open [connection (nrepl/connect :port port)]
          (doall (nrepl/message (nrepl/client connection 10000) {:op "describe"})))
        (is (= [] @texts)))
      (finally (study/stop-repl-server! repl)))))

;; -----------------------------------------------------------------------------
;; Tools and middleware

(deftest the-tools-are-reachable
  (let [repl (study/start-repl-server! (development nil) (options (atom [])))
        port (study/find-repl-port repl)]
    (try
      (is (= [[:echo 5]] (:values (evaluate port "(study.tools/echo 5)"))))
      (is (= [:the-apps-list] (:values (evaluate port "(study.tools/list)"))) "a name clojure.core has too")
      (is (= ["Gives back what it is given, in a vector."] (:values (evaluate port "(:doc (meta #'study.tools/echo))"))))
      (is (= [nil [3 :echo]] (:values (evaluate port "(require '[study.tools :as tools]) [(clojure.core/+ 1 2) (first (tools/echo 1))]"))))
      (finally (study/stop-repl-server! repl)))
    (is (nil? (resolve 'study.tools/echo)) "closed: the tools are taken away")))

(deftest the-apps-middleware-is-added
  (let [repl (study/start-repl-server! (assoc (development nil) :middleware '[study.test-middleware/wrap-hello])
                                       (options (atom [])))]
    (try
      (with-open [connection (nrepl/connect :port (study/find-repl-port repl))]
        (is (= "from the app's middleware"
               (:hello (first (nrepl/message (nrepl/client connection 10000) {:op "study-test/hello"}))))))
      (finally (study/stop-repl-server! repl))))
  (testing "without it, the op is unknown: nothing is added unwritten"
    (let [repl (study/start-repl-server! (development nil) (options (atom [])))]
      (try
        (with-open [connection (nrepl/connect :port (study/find-repl-port repl))]
          (is (contains? (set (:status (first (nrepl/message (nrepl/client connection 10000) {:op "study-test/hello"}))))
                         "unknown-op")))
        (finally (study/stop-repl-server! repl)))))
  (testing "a symbol naming no var refuses, and nothing is left running"
    (is (= {:type :study/middleware-not-found :symbols '[no.such/middleware study/nope]}
           (error-data #(study/start-repl-server! (assoc (development nil) :middleware '[no.such/middleware study/nope])
                                                  (options (atom []))))))
    (let [repl (study/start-repl-server! (development nil) (options (atom [])))]
      (study/stop-repl-server! repl))))

(deftest one-study-at-a-time
  (let [repl (study/start-repl-server! (development nil) (options (atom [])))]
    (try
      (is (= :study/already-running (error-type #(study/start-repl-server! (development nil) (options (atom []))))))
      (finally (study/stop-repl-server! repl))))
  (let [repl (study/start-repl-server! (development nil) (options (atom [])))]
    (is (pos-int? (study/find-repl-port repl)) "stopped: the next one starts")
    (study/stop-repl-server! repl)))

;; -----------------------------------------------------------------------------
;; The emergency switch

(defn- switch-settings [file]
  {:mode :off
   :emergency-switch {:file file :check-every-ms 20
                      :bind-address "127.0.0.1" :port 0 :middleware [] :port-file nil}})

(deftest the-emergency-switch
  (let [texts (atom [])
        file  (io/file (make-temp-folder) "study-open")
        repl  (study/start-repl-server! (switch-settings (str file)) (options texts))]
    (try
      (Thread/sleep 100)
      (is (nil? (study/find-repl-port repl)) "no file: nothing listens")
      (is (nil? (resolve 'study.tools/echo)))
      (testing "the file there: a REPL on loopback, its evaluations written at :warn"
        (spit file "")
        (is (wait-until #(study/find-repl-port repl)))
        (let [port (study/find-repl-port repl)]
          (is (= [[:echo 1]] (:values (evaluate port "(study.tools/echo 1)"))))
          (is (= [{"level" "warn" "data" {"study/file" (str file) "study/bind-address" "127.0.0.1" "study/port" port}}]
                 (mapv #(select-keys % ["level" "data"]) (lines-of texts "study/emergency-opened"))))
          (is (= ["warn" "warn"] (mapv #(get % "level") (concat (lines-of texts "study/evaluation-started")
                                                                (lines-of texts "study/evaluated")))))
          (testing "the file gone: closed"
            (io/delete-file file)
            (is (wait-until #(nil? (study/find-repl-port repl))))
            (is (not (listening? port)))
            (is (nil? (resolve 'study.tools/echo)))
            (is (= 1 (count (lines-of texts "study/emergency-closed")))))))
      (testing "a folder in its place is refused, once"
        (.mkdir file)
        (is (wait-until #(seq (lines-of texts "study/emergency-switch-refused"))))
        (Thread/sleep 100)
        (is (= [{"study/file" (str file) "study/reason" "not a regular file (a link or a folder)"}]
               (mapv #(get % "data") (lines-of texts "study/emergency-switch-refused"))))
        (is (nil? (study/find-repl-port repl)))
        (.delete file)
        (Thread/sleep 100))
      (testing "a link in its place is refused"
        (let [target (io/file (make-temp-folder) "elsewhere")]
          (spit target "")
          (Files/createSymbolicLink (.toPath file) (.toPath target) (make-array FileAttribute 0))
          (is (wait-until #(= 2 (count (lines-of texts "study/emergency-switch-refused")))))
          (is (nil? (study/find-repl-port repl)))
          (.delete file)
          (Thread/sleep 100)))
      (testing "on again, then stopped: the stop closes it"
        (spit file "")
        (is (wait-until #(study/find-repl-port repl))))
      (finally (study/stop-repl-server! repl)))
    (is (nil? (study/find-repl-port repl)))
    (is (= 2 (count (lines-of texts "study/emergency-closed"))))
    (is (nil? (resolve 'study.tools/echo)))))
