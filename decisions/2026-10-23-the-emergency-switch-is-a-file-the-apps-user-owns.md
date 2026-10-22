# The emergency switch is a file the app's user owns

**Decision:** in `:off`, `:emergency-switch` is nil (no switch: nothing ever opens), or a file study looks for every `:check-every-ms`:

```clojure
{:mode :off
 :emergency-switch {:file "/run/app/study-open"   ; absolute
                    :check-every-ms 5000
                    :bind-address "127.0.0.1"      ; loopback only
                    :port 7888 :middleware [] :port-file nil}}
```

- **On:** a regular file at `:file`, owned by the user the app runs as, or by root. The REPL opens on loopback, as `:ssh-only`'s, the tools put in `study.tools`: `:study/emergency-opened`, at `:warn`.
- **Off:** the file gone. The REPL closes, its connections with it, the tools taken away: `:study/emergency-closed`, at `:warn`.
- **Refused:** a link, a folder, or a file another user owns opens nothing: `:study/emergency-switch-refused` at `:error`, with the reason, once until the file is seen gone. A REPL that couldn't open (the port taken) is `:study/emergency-open-failed`, a look that failed (a folder it can't read) `:study/emergency-switch-failed`, each once the same way; a failed look closes an open REPL.
- **Middleware** named by the switch is loaded at the start, so a missing one refuses then, not in the emergency.

Over SSH: `touch /run/app/study-open` (`docker exec -u <app's user> <container> touch …` in a container), then an SSH tunnel to the port; `rm` it when done.

**Why:** reaching the file takes a shell on the machine, so the switch is reachable only over SSH, and while it is off nothing listens at all. The file's presence is the state: `ls` shows whether a REPL is open, and touching it twice does what touching it once does. It opens the REPL in the running app, without a restart, so the state that made someone reach for it (a stuck queue, a cache gone wrong) is still there. Only the app's user or root may own it, so another account on the machine can't open a REPL by writing into a shared folder like `/tmp`; links are refused for the same reason (a link's target can be someone else's).

**The price:** one daemon thread looking at one path every `:check-every-ms`; a REPL opens up to that long after the file appears. A file left in place keeps the REPL open, also after a restart (study looks at the start too); `/run` is emptied at boot, and a container's own folders when it is replaced.

**Instead of:**
- an environment variable or a file read only at the start: a restart to open it, losing the state that was to be looked at, and an environment variable lives in the deploy's config, on until the next deploy
- a signal (`kill -USR2`): no way to say on or off, only "toggle", so whether a REPL is open is a guess; JVM signal handling is `sun.misc.Signal`, an unsupported API, and HotSpot keeps some signals for itself
- a Unix socket for the REPL: tighter than loopback, but an SSH tunnel to a socket inside a container needs the socket on a shared volume; loopback is what `:ssh-only` already uses
- an always-open REPL on loopback in production: any process on the machine (or a request the app is tricked into sending to 127.0.0.1) reaches it
