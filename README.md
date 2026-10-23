# groundwork-live-repl

A REPL into a running Clojure app, open only where it should be: an nREPL
server by settings. `:development` listens where the app says (loopback
unless it writes another address); `:ssh-only` listens on loopback, for an
SSH tunnel; `:off` listens nowhere, and an emergency switch, a file on the
machine, opens one on loopback while it is there. Every evaluation, in every
mode, is written to the app's log. The app's own tools are plain functions in
`groundwork-live-repl.tools` while a REPL is open.

```clojure
io.github.p4ulcristian/groundwork-live-repl {:git/tag "v0.1.2" :git/sha "94df041"}
```

Needs only nREPL and malli. One public namespace, `groundwork-live-repl.api`.

## The api

| name | does |
|---|---|
| `start-repl-server!` `settings-shape` `options-shape` | starts by the settings and options, every key required, checked first; a REPL now, or none, or the emergency switch looking for its file |
| `find-repl-port` | the port the REPL listens on, nil while none does |
| `stop-repl-server!` | closes the REPL and its connections, takes the tools away, stops the switch |

```clojure
(require '[groundwork-live-repl.api :as live-repl])

(def repl
  (live-repl/start-repl-server!
   {:mode :development :bind-address "127.0.0.1" :port 7888
    :middleware '[shadow.cljs.devtools.server.nrepl/middleware]   ; added to nREPL's own; [] for none
    :port-file ".nrepl-port"}                                      ; nil for none
   {:log   log                                                     ; the app's, as groundwork-logs makes it
    :tools {:ask-as-person {:doc      "A message answered as a person, through every check."
                            :function (fn [message person-id] …)}}}))

(live-repl/find-repl-port repl)   ;=> 7888
(live-repl/stop-repl-server! repl)
```

Stage, over an SSH tunnel (`ssh -L 7888:127.0.0.1:7888 stage`):

```clojure
{:mode :ssh-only :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file nil}
```

Production, off, with the emergency switch:

```clojure
{:mode :off
 :emergency-switch {:file "/run/app/live-repl-open" :check-every-ms 5000
                    :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file nil}}
```

```sh
ssh prod touch /run/app/live-repl-open      # a REPL opens on the machine's loopback
ssh -L 7888:127.0.0.1:7888 prod         # and is reached through the tunnel
ssh prod rm /run/app/live-repl-open         # it closes
```

The switch takes a regular file owned by the app's user or root, nothing
else; `{:mode :off :emergency-switch nil}` never opens. Outside
`:development` the address must be loopback, written as an IP address. In a
container, loopback is the container's own: reach it with `docker exec`.

In the REPL:

```clojure
(dir groundwork-live-repl.tools)                                   ; the app's tools
(doc groundwork-live-repl.tools/ask-as-person)
(groundwork-live-repl.tools/ask-as-person {:orders/of-shop {:shop/id shop-id}} person-id)
```

## What it writes

To the app's log, `(log level event data)`:

| level | event | when |
|---|---|---|
| `:info` | `:groundwork-live-repl/started` `:groundwork-live-repl/stopped` | the mode, the address, the port, the middleware, the tools' names |
| `:info` | `:groundwork-live-repl/evaluation-started` | before an `eval` or `load-file` runs: `:groundwork-live-repl/op` `:groundwork-live-repl/message-id` `:groundwork-live-repl/session` `:groundwork-live-repl/namespace` `:groundwork-live-repl/code` (`:groundwork-live-repl/file-path`) |
| `:info` | `:groundwork-live-repl/evaluated` | when it is done: the same, and `:groundwork-live-repl/outcome` (`:returned` `:threw` `:interrupted` `:refused`), `:groundwork-live-repl/values` (a count), `:groundwork-live-repl/ended-in-namespace`, and `:groundwork-live-repl/error-class` `:groundwork-live-repl/root-error-class` when it threw |
| `:warn` | `:groundwork-live-repl/emergency-opened` `:groundwork-live-repl/emergency-closed` | the switch's file appeared, or went |
| `:warn` | the two evaluation lines | for a REPL the switch opened |
| `:error` | `:groundwork-live-repl/emergency-switch-refused` `:groundwork-live-repl/emergency-open-failed` `:groundwork-live-repl/emergency-switch-failed` | once, until the file is seen gone |

Values are never written. Nothing else: no log file, and the JVM's output is
left alone.

## Tests

```sh
clojure -X:test
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
