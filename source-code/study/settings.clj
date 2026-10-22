(ns study.settings
  "study's settings and options, as malli shapes, checked before anything
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
   [:fn {:error/message "should be a loopback address, like \"127.0.0.1\" or \"::1\": outside :development study listens on loopback only"}
    is-loopback-literal?]])

(def ^:private port-shape
  [:int {:min 0 :max 65535}])

(def ^:private middleware-shape
  [:vector :qualified-symbol])

(def ^:private port-file-shape
  [:maybe [:string {:min 1}]])

(def emergency-switch-shape
  "What the emergency switch watches, and what it opens.
   :file: the switch, an absolute path: while a regular file is there,
   owned by the user the app runs as (or root), a REPL is open; when it is
   gone, the REPL closes.
   :check-every-ms: how often the file is looked for.
   :bind-address, :port, :middleware, :port-file: the REPL it opens, as
   :ssh-only's: loopback only."
  [:map {:closed true}
   [:file [:and :string [:fn {:error/message "should be an absolute path"} is-absolute-path?]]]
   [:check-every-ms [:int {:min 1}]]
   [:bind-address loopback-address-shape]
   [:port port-shape]
   [:middleware middleware-shape]
   [:port-file port-file-shape]])

(def settings-shape
  "start-repl-server!'s settings, by :mode, every key required.
   :mode :development: a REPL on :bind-address, any IP address written as
   one; loopback unless the app writes another.
   :mode :ssh-only: a REPL on :bind-address, a loopback address only,
   reached over an SSH tunnel.
   :mode :off: no REPL; :emergency-switch nil for none, or what the switch
   watches and opens (emergency-switch-shape).
   :port: 0 to 65535; 0 takes a free port, which find-repl-port and the
   port file tell.
   :middleware: the nREPL middleware added to nREPL's own, each the
   qualified symbol of a var (cider-nrepl's, shadow-cljs's), loaded at the
   start; [] for none.
   :port-file: a path the port is written to while the REPL is open (an
   editor finds it there, .nrepl-port), nil for none."
  [:multi {:dispatch :mode}
   [:development
    [:map {:closed true}
     [:mode [:= :development]]
     [:bind-address any-address-shape]
     [:port port-shape]
     [:middleware middleware-shape]
     [:port-file port-file-shape]]]
   [:ssh-only
    [:map {:closed true}
     [:mode [:= :ssh-only]]
     [:bind-address loopback-address-shape]
     [:port port-shape]
     [:middleware middleware-shape]
     [:port-file port-file-shape]]]
   [:off
    [:map {:closed true}
     [:mode [:= :off]]
     [:emergency-switch [:maybe emergency-switch-shape]]]]])

(def tool-shape
  "One tool: the function, and the text (doc study.tools/its-name) shows."
  [:map {:closed true}
   [:doc [:string {:min 1}]]
   [:function fn?]])

(def options-shape
  "start-repl-server!'s options, every key required.
   :log: the app's log, (log level event data), as logbook makes it.
   :tools: the app's tools, by name, each a simple keyword: in the REPL,
   study.tools/<name>. {} for none."
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
  "Returns the settings; throws {:type :study/bad-settings :errors …} with
   every problem at once."
  [settings]
  (check settings-shape settings :study/bad-settings "settings"))

(defn check-options
  "Returns the options; throws {:type :study/bad-options :errors …} with
   every problem at once."
  [options]
  (check options-shape options :study/bad-options "options"))
