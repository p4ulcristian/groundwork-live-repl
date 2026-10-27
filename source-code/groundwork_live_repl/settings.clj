(ns groundwork-live-repl.settings
  "groundwork-live-repl's settings and options, as malli shapes, checked before anything
   opens. Nothing has a default: every key is written out, and a missing or
   unknown one refuses, named, with every other problem at once."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error])
  (:import
   (java.net InetAddress)
   (java.nio.file Path)))

(defn- read-ip-literal
  "The address an IP literal names, or nil for anything else (a host name
   too: what it resolves to is the resolver's, not the settings')."
  ^InetAddress [text]
  (when (string? text)
    (try (InetAddress/ofLiteral text)
         (catch IllegalArgumentException _ nil))))

(defn is-ip-literal?
  "Whether text is an IP address written as one, like \"127.0.0.1\" or \"::1\"."
  [text]
  (some? (read-ip-literal text)))

(defn is-loopback-literal?
  "Whether text is an IP address written as one that is loopback:
   127.0.0.0/8 or ::1."
  [text]
  (boolean (some-> (read-ip-literal text) .isLoopbackAddress)))

(defn- is-absolute-path? [text]
  (and (string? text)
       (try (.isAbsolute (Path/of text (make-array String 0)))
            (catch Exception _ false))))

(def ^:private any-address-shape
  [:and :string
   [:fn {:error/message "should be an IP address written as one, like \"127.0.0.1\" or \"::1\", not a name"}
    is-ip-literal?]])

(def ^:private loopback-address-shape
  [:and :string
   [:fn {:error/message "should be a loopback address, like \"127.0.0.1\" or \"::1\": outside :development groundwork-live-repl listens on loopback only"}
    is-loopback-literal?]])

(def ^:private port-shape
  [:int {:min 0 :max 65535}])

(def ^:private middleware-shape
  [:vector :qualified-symbol])

(def ^:private port-file-shape
  [:maybe [:string {:min 1}]])

(def ^:private socket-path-shape
  [:and :string
   [:fn {:error/message "should be an absolute path"} is-absolute-path?]
   ;; sun_path holds 108 bytes on Linux, with its ending zero
   [:fn {:error/message "should be at most 107 bytes long, as a Unix socket's path is"}
    #(<= (alength (.getBytes ^String % "UTF-8")) 107)]])

(defn- make-address-shape
  "Where a REPL listens, by :kind, every key required:
   {:kind :tcp :bind-address … :port … :port-file …}, the address given by
   bind-address-shape; or {:kind :unix-socket :path …}, a Unix socket at
   an absolute path, in a folder only the app's user may enter."
  [bind-address-shape]
  [:multi {:dispatch :kind :error/message "should say its :kind, :tcp or :unix-socket"}
   [:tcp
    [:map {:closed true}
     [:kind [:= :tcp]]
     [:bind-address bind-address-shape]
     [:port port-shape]
     [:port-file port-file-shape]]]
   [:unix-socket
    [:map {:closed true}
     [:kind [:= :unix-socket]]
     [:path socket-path-shape]]]])

(def ^:private any-address
  (make-address-shape any-address-shape))

(def ^:private loopback-address
  (make-address-shape loopback-address-shape))

(def emergency-switch-shape
  "What the emergency switch watches, and what it opens.
   :file: the switch, an absolute path: while a regular file is there,
   owned by the user the app runs as (or root), a REPL is open; when it is
   gone, the REPL closes.
   :check-every-ms: how often the file is looked for.
   :address, :middleware: the REPL it opens, as :ssh-only's: on loopback,
   or on a Unix socket."
  [:map {:closed true}
   [:file [:and :string [:fn {:error/message "should be an absolute path"} is-absolute-path?]]]
   [:check-every-ms [:int {:min 1}]]
   [:address loopback-address]
   [:middleware middleware-shape]])

(def settings-shape
  "start-repl-server!'s settings, by :mode, every key required.
   :mode :development: a REPL on :address; a :tcp one on any IP address
   written as one, loopback unless the app writes another.
   :mode :ssh-only: a REPL on :address, a :tcp one on a loopback address
   only, reached over an SSH tunnel; or a :unix-socket one, reached on the
   machine (or through the tunnel, which forwards a socket too).
   :mode :off: no REPL; :emergency-switch nil for none, or what the switch
   watches and opens (emergency-switch-shape).
   :address: where the REPL listens, by :kind:
     {:kind :tcp :bind-address \"127.0.0.1\" :port 7888 :port-file nil}:
     :port 0 to 65535, 0 takes a free port, which find-repl-address and
     the port file tell; :port-file a path the port is written to while
     the REPL is open (an editor finds it there, .nrepl-port), nil for
     none.
     {:kind :unix-socket :path \"/run/app/repl/live-repl.sock\"}: a Unix
     socket at an absolute path. Its folder must be the app's user's and
     let nobody else in (no permission for its group or others), checked
     when the REPL opens; the socket is made reachable by its owner only,
     a socket left there by a process that ended is replaced, and the
     socket is deleted when the REPL closes.
   :middleware: the nREPL middleware added to nREPL's own, each the
   qualified symbol of a var (cider-nrepl's, shadow-cljs's), loaded at the
   start; [] for none."
  [:multi {:dispatch :mode}
   [:development
    [:map {:closed true}
     [:mode [:= :development]]
     [:address any-address]
     [:middleware middleware-shape]]]
   [:ssh-only
    [:map {:closed true}
     [:mode [:= :ssh-only]]
     [:address loopback-address]
     [:middleware middleware-shape]]]
   [:off
    [:map {:closed true}
     [:mode [:= :off]]
     [:emergency-switch [:maybe emergency-switch-shape]]]]])

(def tool-shape
  "One tool: the function, and the text (doc groundwork-live-repl.tools/its-name) shows."
  [:map {:closed true}
   [:doc [:string {:min 1}]]
   [:function fn?]])

(def options-shape
  "start-repl-server!'s options, every key required.
   :log: the app's log, (log level event data), as groundwork-logs makes it.
   :tools: the app's tools, by name, each a simple keyword: in the REPL,
   groundwork-live-repl.tools/<name>. {} for none."
  [:map {:closed true}
   [:log fn?]
   [:tools [:map-of [:and :keyword [:fn {:error/message "should be a keyword without a namespace"} simple-keyword?]]
            tool-shape]]])

(defn- check [shape value error-type what]
  (when-let [explanation (malli/explain shape value)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The " what " have problems: " (pr-str errors))
                      {:type error-type :errors errors}))))
  value)

(defn check-settings
  "Returns the settings; throws {:type :groundwork-live-repl/bad-settings :errors …} with
   every problem at once."
  [settings]
  (check settings-shape settings :groundwork-live-repl/bad-settings "settings"))

(defn check-options
  "Returns the options; throws {:type :groundwork-live-repl/bad-options :errors …} with
   every problem at once."
  [options]
  (check options-shape options :groundwork-live-repl/bad-options "options"))
