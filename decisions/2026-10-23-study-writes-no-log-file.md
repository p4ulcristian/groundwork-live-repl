# study writes no log file

**Decision:** study writes nothing but its lines to the app's log, and never touches `System/out`, `System/err`, `*out*` or `*err*`. Where the lines go is logbook's `:write-to`. Everything else a development start prints (the browser build's watcher, a `println` in the app) is the start's own: house's development start runs as a service, whose standard output the journal keeps (`journalctl --user -u <app>-dev`), or writes a file itself.

**Why:** house v0.5.0's development start copied standard output and error into `.groundwork/dev.log` by replacing them for the whole JVM. A library doing that takes over output every other library writes to, and logbook already decided against swapping `System/out` (its `:write-to` exists so nothing has to). study's own lines are the evaluations, and those already go to the app's log; the rest of what a development start prints isn't study's to collect.

**Instead of:**
- a `:log-file` setting in study: study would replace the JVM's output for everyone, the thing logbook avoided
- study's lines to a file of their own beside the log: two places to look, and production's evaluations wouldn't be in Loki with everything else
