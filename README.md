# groundwork-live-repl

A REPL into a running Clojure app, open only where it should be: an nREPL
server by settings. `:development` listens where the app says (loopback
unless it writes another address); `:ssh-only` listens on loopback, for an
SSH tunnel, or on a Unix socket; `:off` listens nowhere, and an emergency
switch, a file on the machine, opens one on loopback or its socket while
it is there. Every evaluation, in every
mode, is written to the app's log. The app's own tools are plain functions in
`groundwork-live-repl.tools` while a REPL is open.

```clojure
io.github.p4ulcristian/groundwork-live-repl {:git/tag "v0.1.4" :git/sha "5dfa25e"}
```

Needs only nREPL and malli. One public namespace, `groundwork-live-repl.api`.

## The api

| name | does |
|---|---|
| `start-repl-server!` `settings-shape` `options-shape` | starts by the settings and options, every key required, checked first; a REPL now, or none, or the emergency switch looking for its file |
| `find-repl-address` | where the REPL listens, `{:kind :tcp :bind-address … :port …}` (the port it took) or `{:kind :unix-socket :path …}`, nil while none does |
| `stop-repl-server!` | closes the REPL and its connections, takes the tools away, stops the switch |

```clojure
(require '[groundwork-live-repl.api :as live-repl])

(def repl
  (live-repl/start-repl-server!
   {:mode       :development
    :address    {:kind :tcp :bind-address "127.0.0.1" :port 7888
                 :port-file ".nrepl-port"}                         ; nil for none
    :middleware '[shadow.cljs.devtools.server.nrepl/middleware]}  ; added to nREPL's own; [] for none
   {:log   log                                                     ; the app's, as groundwork-logs makes it
    :tools {:ask-as-person {:doc      "A message answered as a person, through every check."
                            :function (fn [message person-id] …)}}}))

(live-repl/find-repl-address repl)   ;=> {:kind :tcp :bind-address "127.0.0.1" :port 7888}
(live-repl/stop-repl-server! repl)
```

Stage, over an SSH tunnel (`ssh -L 7888:127.0.0.1:7888 stage`):

```clojure
{:mode :ssh-only :address {:kind :tcp :bind-address "127.0.0.1" :port 7888 :port-file nil} :middleware []}
```

Or on a Unix socket, reached on the machine (`podman exec`, a shell) or
through the tunnel, which forwards a socket too
(`ssh -L 7888:/run/app/repl/live-repl.sock stage`):

```clojure
{:mode :ssh-only :address {:kind :unix-socket :path "/run/app/repl/live-repl.sock"} :middleware []}
```

The socket's folder must be the app's user's and let nobody else in (no
permission for its group or others, `chmod 700`), checked when the REPL
opens, so nobody else reaches the socket or puts another in its place;
the socket itself is made `rw-------`. A socket left at the path by a
process that ended is replaced; one a process answers on, or anything
that isn't a socket, is refused (`:groundwork-live-repl/socket-refused`, with
its `:reason`). Closing the REPL deletes the socket
([why](decisions/2026-10-27-a-repl-may-listen-on-a-unix-socket.md)).

Production, off, with the emergency switch:

```clojure
{:mode :off
 :emergency-switch {:file "/run/app/live-repl-open" :check-every-ms 5000
                    :address {:kind :tcp :bind-address "127.0.0.1" :port 7888 :port-file nil}
                    :middleware []}}
```

```sh
ssh prod touch /run/app/live-repl-open      # a REPL opens on the machine's loopback
ssh -L 7888:127.0.0.1:7888 prod         # and is reached through the tunnel
ssh prod rm /run/app/live-repl-open         # it closes
```

The switch takes a regular file owned by the app's user or root, nothing
else; `{:mode :off :emergency-switch nil}` never opens. Outside
`:development` a TCP address must be loopback, written as an IP address. In a
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
| `:info` | `:groundwork-live-repl/started` `:groundwork-live-repl/stopped` | the mode, the address (`:groundwork-live-repl/address`, with the port a TCP one took), the middleware, the tools' names |
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
