# REPLs by environment

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> Carries over house's [REPLs by environment](https://github.com/p4ulcristian/house/blob/v0.5.0/decisions/2026-10-12-repls-by-environment.md) (2026-10-12), as changed by [Groundwork ships no team endpoints and no eval bridge](https://github.com/p4ulcristian/house/blob/v0.5.0/decisions/2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md) (2026-10-18): there is no eval bridge, so previews get no REPL.

**Decision:** where a REPL into a running app may exist, and the mode that says so (`:mode` in study's settings):

- **Development: always,** `:development`. The app's one development start runs its server, the browser build's watcher and study; the ClojureScript REPL is on the same port when the app lists shadow-cljs's middleware.
- **Previews: none.** The eval bridge, a route that evaluated code sent to a running preview, was removed in Groundwork on 2026-10-18 (nothing used it, and code nothing uses drifts). A preview runs `:off`.
- **Stage: only over SSH,** `:ssh-only`: the REPL listens on loopback; a person reaches it with an SSH tunnel.
- **Production: off,** `:off`, with an emergency switch that opens one on loopback, reachable only over SSH ([The emergency switch is a file](2026-10-23-the-emergency-switch-is-a-file-the-apps-user-owns.md)).
- **Every evaluation is written to the app's log, in every mode** ([Every evaluation is written to the log](2026-10-23-every-evaluation-is-written-to-the-log.md)).

The files stay the truth: what is tried in a REPL and not saved to a file is gone on the next start. Queries are tried in the REPL the way the app calls them (house's "We try queries in the REPL").

**Why:** a REPL into the running app is how a question gets an exact answer (try a query on real data, ask a handler as a person, ask why a person was refused) and how code is checked before it's committed. In production it is also a way around every permission and every gate on the database, so it stays off until someone deliberately reaches the machine over SSH, and it leaves a trace.

**Instead of:**
- a REPL open in every environment: anyone who reaches it reads and changes every scope's data
- none outside development: stage and production questions answered by guessing from the code
- the eval bridge for previews: removed in Groundwork; a preview's question is asked on stage or in development
