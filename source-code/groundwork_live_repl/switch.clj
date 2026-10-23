(ns groundwork-live-repl.switch
  "The emergency switch: a file looked for every :check-every-ms. While a
   regular file is there, owned by the user the app runs as or by root, a
   REPL is open on loopback; once it is gone, the REPL closes. Reaching the
   file takes a shell on the machine, so the switch is reachable only over
   SSH, and nothing listens on the network while it is off.

   Each change is written to the app's log: :groundwork-live-repl/emergency-opened and
   :groundwork-live-repl/emergency-closed at :warn; a file the switch refuses
   (:groundwork-live-repl/emergency-switch-refused), a REPL that couldn't open
   (:groundwork-live-repl/emergency-open-failed) and a look that failed
   (:groundwork-live-repl/emergency-switch-failed) at :error, each once, until the file is
   seen gone."
  (:require
   [groundwork-live-repl.server :as server])
  (:import
   (java.lang ProcessHandle)
   (java.nio.file Files LinkOption Path)
   (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

(def ^:private no-following
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- find-process-user []
  (.orElse (.user (.info (ProcessHandle/current))) nil))

(defn- look-at-file
  "What the switch's file says: {:look :absent}, {:look :on}, or
   {:look :refused :reason …} for one the switch won't take (a link, a
   folder, another user's)."
  [^Path path]
  (cond
    (not (Files/exists path no-following))
    {:look :absent}

    (not (Files/isRegularFile path no-following))
    {:look :refused :reason "not a regular file (a link or a folder)"}

    :else
    (let [owner (.getName (Files/getOwner path no-following))
          user  (find-process-user)]
      (if (contains? #{user "root"} owner)
        {:look :on}
        {:look :refused :reason (str "owned by " owner ", not by " user " (the app's user) or root")}))))

(defn- check-once!
  "One look at the file, and the change it makes, under the groundwork-live-repl's lock.
   state: {:stopped? :server :quiet?}; :quiet? true after a refusal or a
   failure was written, until the file is seen gone."
  [{:keys [state lock log switch-settings middleware-vars tools]}]
  (locking lock
    (let [{:keys [stopped? server quiet?]} @state
          file                             (:file switch-settings)
          close!                           (fn [event data]
                                             (when server
                                               (server/close-server! server)
                                               (swap! state assoc :server nil)
                                               (log :warn event data)))]
      (when-not stopped?
        (let [{:keys [look reason failure]}
              (try (look-at-file (Path/of ^String file (make-array String 0)))
                   (catch Throwable failure {:look :failed :failure failure}))]
          (case look
            :absent
            (do (close! :groundwork-live-repl/emergency-closed {:groundwork-live-repl/file file})
                (swap! state assoc :quiet? false))

            :on
            (when-not (or server quiet?)
              (try
                (let [opened (server/open-server! switch-settings middleware-vars log :warn tools)]
                  (swap! state assoc :server opened)
                  (log :warn :groundwork-live-repl/emergency-opened {:groundwork-live-repl/file         file
                                                      :groundwork-live-repl/bind-address (:bind-address switch-settings)
                                                      :groundwork-live-repl/port         (:port opened)}))
                (catch Throwable failure
                  (swap! state assoc :quiet? true)
                  (log :error :groundwork-live-repl/emergency-open-failed {:groundwork-live-repl/file file :error failure}))))

            :refused
            (do (close! :groundwork-live-repl/emergency-closed {:groundwork-live-repl/file file})
                (when-not quiet?
                  (swap! state assoc :quiet? true)
                  (log :error :groundwork-live-repl/emergency-switch-refused {:groundwork-live-repl/file file :groundwork-live-repl/reason reason})))

            :failed
            (do (close! :groundwork-live-repl/emergency-closed {:groundwork-live-repl/file file})
                (when-not quiet?
                  (swap! state assoc :quiet? true)
                  (log :error :groundwork-live-repl/emergency-switch-failed {:groundwork-live-repl/file file :error failure})))))))))

(defn start-watching!
  "Looks at the switch's file every :check-every-ms, on one daemon thread,
   the first look at once. Returns the watcher, for stop-watching!."
  [switch-settings middleware-vars log tools]
  (let [watcher  {:state           (atom {:stopped? false :server nil :quiet? false})
                  :lock            (Object.)
                  :log             log
                  :switch-settings switch-settings
                  :middleware-vars middleware-vars
                  :tools           tools}
        executor (Executors/newSingleThreadScheduledExecutor
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. ^Runnable runnable "live-repl-emergency-switch")
                        (.setDaemon true)))))]
    (.scheduleWithFixedDelay ^ScheduledExecutorService executor
                             ^Runnable (fn [] (try (check-once! watcher)
                                                   ;; a throw would end the schedule: the switch must keep looking
                                                   (catch Throwable _ nil)))
                             0 (long (:check-every-ms switch-settings)) TimeUnit/MILLISECONDS)
    (assoc watcher :executor executor)))

(defn find-port
  "The port the switch's REPL listens on, nil while it is closed."
  [{:keys [state]}]
  (:port (:server @state)))

(defn stop-watching!
  "Stops looking, and closes the REPL if the switch had opened it
   (:groundwork-live-repl/emergency-closed)."
  [{:keys [^ScheduledExecutorService executor state lock log switch-settings]}]
  (.shutdownNow executor)
  (locking lock
    (let [{:keys [server]} @state]
      (swap! state assoc :stopped? true :server nil)
      (when server
        (server/close-server! server)
        (log :warn :groundwork-live-repl/emergency-closed {:groundwork-live-repl/file (:file switch-settings)})))))
