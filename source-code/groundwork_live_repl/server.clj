(ns groundwork-live-repl.server
  "Opening and closing one nREPL server: its handler (nREPL's own
   middleware, the app's, and the evaluation log outside them all), the
   tools put into groundwork-live-repl.tools, the port file."
  (:require
   [clojure.java.io :as io]
   [nrepl.server :as nrepl-server]
   [groundwork-live-repl.evaluations :as evaluations]
   [groundwork-live-repl.tools]))

(defn resolve-middleware
  "The middleware vars the symbols name, each loaded; throws
   {:type :groundwork-live-repl/middleware-not-found :symbols […]} naming every one that
   couldn't be."
  [symbols]
  (let [found   (mapv (fn [sym] [sym (try (requiring-resolve sym) (catch Exception _ nil))]) symbols)
        missing (into [] (comp (remove second) (map first)) found)]
    (when (seq missing)
      (throw (ex-info (str "No middleware var for " (pr-str missing))
                      {:type :groundwork-live-repl/middleware-not-found :symbols missing})))
    (mapv second found)))

(defn- put-tools! [tools]
  (doseq [[tool-name {:keys [doc function]}] tools]
    (intern 'groundwork-live-repl.tools (with-meta (symbol (name tool-name)) {:doc doc}) function)))

(defn- take-tools-away! [tools]
  (doseq [tool-name (keys tools)]
    (ns-unmap 'groundwork-live-repl.tools (symbol (name tool-name)))))

(defn open-server!
  "An nREPL server on bind-address and port, the middleware vars added to
   nREPL's own, every evaluation written to log at level; the tools put
   into groundwork-live-repl.tools first, the port written to port-file (nil: none).
   Returns {:server :port :port-file :tools}."
  [{:keys [bind-address port port-file]} middleware-vars log level tools]
  (let [handler (evaluations/wrap-handler-with-evaluation-log
                 (apply nrepl-server/default-handler middleware-vars) log level)]
    (put-tools! tools)
    (try
      (let [server (nrepl-server/start-server :bind bind-address :port port :handler handler)]
        (try
          (when port-file
            (spit (io/file port-file) (str (:port server))))
          {:server server :port (:port server) :port-file port-file :tools tools}
          (catch Throwable failure
            (nrepl-server/stop-server server)
            (throw failure))))
      (catch Throwable failure
        (take-tools-away! tools)
        (throw failure)))))

(defn close-server!
  "Stops the server (its connections closed), takes the tools away,
   deletes the port file."
  [{:keys [server port-file tools]}]
  (try
    (nrepl-server/stop-server server)
    (finally
      (take-tools-away! tools)
      (when port-file
        (io/delete-file (io/file port-file) true)))))
