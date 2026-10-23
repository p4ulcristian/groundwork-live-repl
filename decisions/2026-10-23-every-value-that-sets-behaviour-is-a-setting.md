# Every value that sets behaviour is a setting

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** every value that sets how study behaves is written out by the app, checked by malli before anything opens, the maps closed, a missing or unknown key refused, named, with every other problem at once (`:study/bad-settings`, `:study/bad-options`). Nothing has a default.

- **The settings**, `settings-shape`, by `:mode`, data the app's settings file holds:

  ```clojure
  {:mode :development :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file ".nrepl-port"}
  {:mode :ssh-only    :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file nil}
  {:mode :off :emergency-switch nil}
  {:mode :off :emergency-switch {:file "/run/app/study-open" :check-every-ms 5000
                                 :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file nil}}
  ```

  `:port` 0 takes a free port, which `find-repl-port`, the port file and `:study/started` tell. `:middleware` is the qualified symbols of middleware vars added to nREPL's own (cider-nrepl's, shadow-cljs's), loaded at the start; one that names no var refuses, `:study/middleware-not-found`, all of them named. `:port-file` is where the port is written while the REPL is open (an editor reads `.nrepl-port`), deleted when it closes; nil for none.
- **The options**, `options-shape`, values only the running app has: `:log`, the app's log; `:tools`, the app's tools, `{}` for none.
- **The api** is five names: `settings-shape`, `options-shape`, `start-repl-server!` (the settings and options, checked; the study), `find-repl-port` (its port, nil while nothing listens) and `stop-repl-server!`.

**What stays fixed, and why:**

- **nREPL's own middleware** (`nrepl.server/default-middleware`: sessions, eval, load-file, completion, lookup, printing, stdin, describe): it is what an nREPL server is, the ops every client sends. `:middleware` adds to it; nothing else is added unwritten.
- **study's evaluation log, outside the whole handler:** the point of the library ([Every evaluation is written to the log](2026-10-23-every-evaluation-is-written-to-the-log.md)).
- **The levels:** evaluations, `:study/started` and `:study/stopped` at `:info`; the emergency switch's opening, closing and evaluations at `:warn`; its refusals and failures at `:error`. They say how much a line matters, which is study's to know, as logbook's levels say.
- **bencode, no TLS, no greeting:** the transport every nREPL client speaks; SSH encrypts the tunnel ([Loopback, unless development writes another address](2026-10-23-loopback-unless-development-writes-another-address.md)).
- **The namespace `study.tools`**, and one study per JVM: where a person and an agent look for the tools.
- **The switch's owners,** the app's user or root, and refusing links: a security constant, not a choice ([The emergency switch is a file](2026-10-23-the-emergency-switch-is-a-file-the-apps-user-owns.md)).

**Why:** manor's rule (mailroom's "Every value that sets behaviour is a setting"): a value left to a default is a value the app doesn't show. nREPL's own defaults (bind 127.0.0.1, port 0, `default-handler`) are written out by study's settings or listed above.

**Instead of:**
- `:middleware` defaulting to cider-nrepl when it is on the classpath, as house v0.5.0 added shadow-cljs's when it was there: middleware nobody wrote, different on two machines
- `:log` and `:tools` in the settings: they are functions of the running app, not data a settings file holds; options, as mailroom's `start-server!` and backroom's `start-workers!` take theirs
