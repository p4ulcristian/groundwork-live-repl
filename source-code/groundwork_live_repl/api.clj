(ns groundwork-live-repl.api
  "groundwork's REPL: an nREPL server into the running app, by settings. groundwork-live-repl's
   one public namespace.

     ;; development: a REPL, on loopback unless the app writes another address
     (def repl (live-repl/start-repl-server!
                {:mode :development :bind-address \"127.0.0.1\" :port 7888
                 :middleware [] :port-file \".nrepl-port\"}
                {:log log :tools tools}))

     ;; stage: loopback only, reached over an SSH tunnel
     {:mode :ssh-only :bind-address \"127.0.0.1\" :port 7888 :middleware [] :port-file nil}

     ;; production: off; the REPL opens while the switch's file is there
     {:mode :off
      :emergency-switch {:file \"/run/app/live-repl-open\" :check-every-ms 5000
                         :bind-address \"127.0.0.1\" :port 7888 :middleware [] :port-file nil}}

     (live-repl/find-repl-port repl)   ;=> 7888, or nil while nothing listens
     (live-repl/stop-repl-server! repl)

   Every evaluation, in every mode, is written to the app's log (:log),
   before it runs and when it is done; values are not written. The app's
   tools (:tools, {:ask-as-person {:doc \"…\" :function f}}) are plain
   functions in groundwork-live-repl.tools while a REPL is open. One groundwork-live-repl runs in a JVM
   at a time: groundwork-live-repl.tools is one namespace."
  (:require
   [groundwork-live-repl.server :as server]
   [groundwork-live-repl.settings :as settings]
   [groundwork-live-repl.switch :as switch]))

(def settings-shape
  "start-repl-server!'s settings, by :mode (:development, :ssh-only, :off),
   every key required. See groundwork-live-repl.settings/settings-shape for each key."
  settings/settings-shape)

(def options-shape
  "start-repl-server!'s options, every key required: :log, the app's log;
   :tools, the app's tools by name, {} for none. See
   groundwork-live-repl.settings/options-shape."
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
      (log :info :groundwork-live-repl/started {:groundwork-live-repl/mode         mode
                                 :groundwork-live-repl/bind-address (:bind-address checked-settings)
                                 :groundwork-live-repl/port         (:port opened)
                                 :groundwork-live-repl/middleware   (:middleware checked-settings)
                                 :groundwork-live-repl/tools        (tool-names tools)})
      {:mode mode :log log :server opened})

    :off
    (let [switch-settings (:emergency-switch checked-settings)
          watcher         (when switch-settings
                            (switch/start-watching! switch-settings
                                                    (server/resolve-middleware (:middleware switch-settings))
                                                    log tools))]
      (log :info :groundwork-live-repl/started {:groundwork-live-repl/mode             :off
                                 :groundwork-live-repl/emergency-switch (:file switch-settings)
                                 :groundwork-live-repl/tools            (tool-names tools)})
      {:mode :off :log log :watcher watcher})))

(defn start-repl-server!
  "Starts groundwork-live-repl by its settings (settings-shape) and options
   (options-shape), both checked first, every problem at once
   (:groundwork-live-repl/bad-settings, :groundwork-live-repl/bad-options); a middleware symbol naming no
   var refuses too (:groundwork-live-repl/middleware-not-found), and a second groundwork-live-repl while
   one runs (:groundwork-live-repl/already-running).
   :development and :ssh-only open a REPL now; :off opens none, and with
   an emergency switch starts looking at its file. Writes :groundwork-live-repl/started.
   Returns the groundwork-live-repl, for find-repl-port and stop-repl-server!."
  [live-repl-settings options]
  (let [checked-settings      (settings/check-settings live-repl-settings)
        {:keys [log tools]}   (settings/check-options options)
        claim                 (Object.)]
    (when-not (compare-and-set! running nil claim)
      (throw (ex-info "A groundwork-live-repl is already running in this JVM: stop it first (groundwork-live-repl.tools is one namespace)"
                      {:type :groundwork-live-repl/already-running})))
    (try
      (assoc (start-in-mode checked-settings log tools) ::claim claim)
      (catch Throwable failure
        (reset! running nil)
        (throw failure)))))

(defn find-repl-port
  "The port the groundwork-live-repl's REPL listens on, nil while none is open (:off, the
   switch's file gone, stopped)."
  [groundwork-live-repl]
  (if-let [watcher (:watcher groundwork-live-repl)]
    (switch/find-port watcher)
    (when-not (= :off (:mode groundwork-live-repl))
      (when (= (::claim groundwork-live-repl) @running)
        (get-in groundwork-live-repl [:server :port])))))

(defn stop-repl-server!
  "Closes the REPL (its connections too) and takes the tools away, or
   stops the emergency switch and closes what it opened. Writes
   :groundwork-live-repl/stopped. Returns nil."
  [{:keys [mode log server watcher] :as groundwork-live-repl}]
  (try
    (when server (server/close-server! server))
    (when watcher (switch/stop-watching! watcher))
    (log :info :groundwork-live-repl/stopped {:groundwork-live-repl/mode mode})
    (finally
      (compare-and-set! running (::claim groundwork-live-repl) nil)))
  nil)
