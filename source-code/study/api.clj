(ns study.api
  "groundwork's REPL: an nREPL server into the running app, by settings. study's
   one public namespace.

     ;; development: a REPL, on loopback unless the app writes another address
     (def repl (study/start-repl-server!
                {:mode :development :bind-address \"127.0.0.1\" :port 7888
                 :middleware [] :port-file \".nrepl-port\"}
                {:log log :tools tools}))

     ;; stage: loopback only, reached over an SSH tunnel
     {:mode :ssh-only :bind-address \"127.0.0.1\" :port 7888 :middleware [] :port-file nil}

     ;; production: off; the REPL opens while the switch's file is there
     {:mode :off
      :emergency-switch {:file \"/run/app/study-open\" :check-every-ms 5000
                         :bind-address \"127.0.0.1\" :port 7888 :middleware [] :port-file nil}}

     (study/find-repl-port repl)   ;=> 7888, or nil while nothing listens
     (study/stop-repl-server! repl)

   Every evaluation, in every mode, is written to the app's log (:log),
   before it runs and when it is done; values are not written. The app's
   tools (:tools, {:ask-as-person {:doc \"…\" :function f}}) are plain
   functions in study.tools while a REPL is open. One study runs in a JVM
   at a time: study.tools is one namespace."
  (:require
   [study.server :as server]
   [study.settings :as settings]
   [study.switch :as switch]))

(def settings-shape
  "start-repl-server!'s settings, by :mode (:development, :ssh-only, :off),
   every key required. See study.settings/settings-shape for each key."
  settings/settings-shape)

(def options-shape
  "start-repl-server!'s options, every key required: :log, the app's log;
   :tools, the app's tools by name, {} for none. See
   study.settings/options-shape."
  settings/options-shape)

(defonce ^:private running
  (atom nil))

(defn- tool-names [tools]
  (vec (sort (map name (keys tools)))))

(defn- start-in-mode [{:keys [mode] :as checked-settings} log tools]
  (case mode
    (:development :ssh-only)
    (let [middleware-vars (server/resolve-middleware (:middleware checked-settings))
          opened          (server/open-server! checked-settings middleware-vars log :info tools)]
      (log :info :study/started {:study/mode         mode
                                 :study/bind-address (:bind-address checked-settings)
                                 :study/port         (:port opened)
                                 :study/middleware   (:middleware checked-settings)
                                 :study/tools        (tool-names tools)})
      {:mode mode :log log :server opened})

    :off
    (let [switch-settings (:emergency-switch checked-settings)
          watcher         (when switch-settings
                            (switch/start-watching! switch-settings
                                                    (server/resolve-middleware (:middleware switch-settings))
                                                    log tools))]
      (log :info :study/started {:study/mode             :off
                                 :study/emergency-switch (:file switch-settings)
                                 :study/tools            (tool-names tools)})
      {:mode :off :log log :watcher watcher})))

(defn start-repl-server!
  "Starts study by its settings (settings-shape) and options
   (options-shape), both checked first, every problem at once
   (:study/bad-settings, :study/bad-options); a middleware symbol naming no
   var refuses too (:study/middleware-not-found), and a second study while
   one runs (:study/already-running).
   :development and :ssh-only open a REPL now; :off opens none, and with
   an emergency switch starts looking at its file. Writes :study/started.
   Returns the study, for find-repl-port and stop-repl-server!."
  [study-settings options]
  (let [checked-settings      (settings/check-settings study-settings)
        {:keys [log tools]}   (settings/check-options options)
        claim                 (Object.)]
    (when-not (compare-and-set! running nil claim)
      (throw (ex-info "A study is already running in this JVM: stop it first (study.tools is one namespace)"
                      {:type :study/already-running})))
    (try
      (assoc (start-in-mode checked-settings log tools) ::claim claim)
      (catch Throwable failure
        (reset! running nil)
        (throw failure)))))

(defn find-repl-port
  "The port the study's REPL listens on, nil while none is open (:off, the
   switch's file gone, stopped)."
  [study]
  (if-let [watcher (:watcher study)]
    (switch/find-port watcher)
    (when-not (= :off (:mode study))
      (when (= (::claim study) @running)
        (get-in study [:server :port])))))

(defn stop-repl-server!
  "Closes the REPL (its connections too) and takes the tools away, or
   stops the emergency switch and closes what it opened. Writes
   :study/stopped. Returns nil."
  [{:keys [mode log server watcher] :as study}]
  (try
    (when server (server/close-server! server))
    (when watcher (switch/stop-watching! watcher))
    (log :info :study/stopped {:study/mode mode})
    (finally
      (compare-and-set! running (::claim study) nil)))
  nil)
