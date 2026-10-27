(ns groundwork-live-repl.server
  "Opening and closing one nREPL server: its handler (nREPL's own
   middleware, the app's, and the evaluation log outside them all), the
   tools put into groundwork-live-repl.tools, where it listens (a TCP
   address and its port file, or a Unix socket)."
  (:require
   [clojure.java.io :as io]
   [nrepl.server :as nrepl-server]
   [groundwork-live-repl.evaluations :as evaluations]
   [groundwork-live-repl.tools])
  (:import
   (java.io IOException)
   (java.lang ProcessHandle)
   (java.net StandardProtocolFamily UnixDomainSocketAddress)
   (java.nio.channels SocketChannel)
   (java.nio.file Files LinkOption Path)
   (java.nio.file.attribute BasicFileAttributes PosixFilePermission PosixFilePermissions)))

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

;; -----------------------------------------------------------------------------
;; A Unix socket

(def ^:private no-following
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private others-permissions
  #{PosixFilePermission/GROUP_READ PosixFilePermission/GROUP_WRITE PosixFilePermission/GROUP_EXECUTE
    PosixFilePermission/OTHERS_READ PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_EXECUTE})

(defn- refuse-socket! [path reason]
  (throw (ex-info (str "The REPL's socket " path " is refused: " reason)
                  {:type :groundwork-live-repl/socket-refused :path path :reason reason})))

(defn- find-folder-problem
  "Why the socket's folder can't hold it, or nil: it must be a folder, not
   a link, owned by the app's user, with no permission for its group or
   others, so nobody else reaches the socket or puts another in its place
   (POSIX leaves a socket's own permissions to each system: the folder is
   what holds)."
  [^Path folder]
  (cond
    (not (Files/isDirectory folder no-following))
    (str "its folder " folder " is not a folder (missing, or a link)")

    (not= (.getName (Files/getOwner folder no-following)) (.orElse (.user (.info (ProcessHandle/current))) nil))
    (str "its folder " folder " is owned by " (.getName (Files/getOwner folder no-following)) ", not by the app's user")

    (seq (filter others-permissions (Files/getPosixFilePermissions folder no-following)))
    (str "its folder " folder " lets others in (" (PosixFilePermissions/toString (Files/getPosixFilePermissions folder no-following))
         "): give its group and others nothing, chmod 700")))

(defn- does-socket-answer?
  "Whether a process listens on the socket at path."
  [^Path path]
  (try (with-open [_ (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                       (.connect (UnixDomainSocketAddress/of path)))]
         true)
       (catch IOException _ false)))

(defn- clear-socket-path!
  "Makes the path free for the socket: nothing there; a socket nobody
   answers on (left by a process that ended) deleted. Refuses anything
   else there, and a socket a process answers on."
  [^Path path]
  (when (Files/exists path no-following)
    (let [attributes (Files/readAttributes path BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-following)]
      (cond
        (not (.isOther attributes))
        (refuse-socket! (str path) "something that isn't a socket is at its path")

        (does-socket-answer? path)
        (refuse-socket! (str path) "another process listens on it")

        :else
        (Files/delete path)))))

(defn- open-unix-socket-server!
  "An nREPL server on a Unix socket at path, its folder checked first, the
   socket reachable by its owner only."
  [path handler]
  (let [socket-path (Path/of ^String path (make-array String 0))]
    (when-let [problem (find-folder-problem (.getParent socket-path))]
      (refuse-socket! path problem))
    (clear-socket-path! socket-path)
    (let [server (nrepl-server/start-server :socket path :handler handler)]
      (try
        (Files/setPosixFilePermissions socket-path (PosixFilePermissions/fromString "rw-------"))
        server
        (catch Throwable failure
          (nrepl-server/stop-server server)
          (Files/deleteIfExists socket-path)
          (throw failure))))))

;; -----------------------------------------------------------------------------
;; Opening and closing

(defn- open-tcp-server!
  "An nREPL server on the address and port, the port written to
   port-file (nil: none)."
  [{:keys [bind-address port port-file]} handler]
  (let [server (nrepl-server/start-server :bind bind-address :port port :handler handler)]
    (try
      (when port-file
        (spit (io/file port-file) (str (:port server))))
      server
      (catch Throwable failure
        (nrepl-server/stop-server server)
        (throw failure)))))

(defn describe-address
  "Where an open server listens: {:kind :tcp :bind-address … :port …}, the
   port it took, or {:kind :unix-socket :path …}."
  [{:keys [address server]}]
  (case (:kind address)
    :tcp         {:kind :tcp :bind-address (:bind-address address) :port (:port server)}
    :unix-socket {:kind :unix-socket :path (:path address)}))

(defn open-server!
  "An nREPL server at the settings' :address, the middleware vars added
   to nREPL's own, every evaluation written to log at level; the tools put
   into groundwork-live-repl.tools first. Returns {:server :address :tools}."
  [{:keys [address]} middleware-vars log level tools]
  (let [handler (evaluations/wrap-handler-with-evaluation-log
                 (apply nrepl-server/default-handler middleware-vars) log level)]
    (put-tools! tools)
    (try
      {:server  (case (:kind address)
                  :tcp         (open-tcp-server! address handler)
                  :unix-socket (open-unix-socket-server! (:path address) handler))
       :address address
       :tools   tools}
      (catch Throwable failure
        (take-tools-away! tools)
        (throw failure)))))

(defn close-server!
  "Stops the server (its connections closed), takes the tools away,
   deletes the port file or the socket."
  [{:keys [server address tools]}]
  (try
    (nrepl-server/stop-server server)
    (finally
      (take-tools-away! tools)
      (case (:kind address)
        :tcp         (when-let [port-file (:port-file address)]
                       (io/delete-file (io/file port-file) true))
        :unix-socket (Files/deleteIfExists (Path/of ^String (:path address) (make-array String 0)))))))
