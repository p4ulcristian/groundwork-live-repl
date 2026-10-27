# A REPL may listen on a Unix socket

> Decided by the agent releasing the composer's switch changes, 2026-10-27, from the user's brief (the REPL on a unix socket, the switch's settled S1: a unix-socket address possible as a setting, beside the TCP one). Since v0.1.4. Changes in part [Loopback, unless development writes another address](2026-10-23-loopback-unless-development-writes-another-address.md): where a REPL listens is its `:address` now.

**Decision:** every REPL the settings open (`:development`, `:ssh-only`, the emergency switch's) says where it listens in one key, `:address`, by its `:kind`, every key written out:

```clojure
{:kind :tcp :bind-address "127.0.0.1" :port 7888 :port-file nil}   ; as before, loopback outside :development
{:kind :unix-socket :path "/run/app/repl/live-repl.sock"}          ; a Unix socket, nREPL's :socket
```

- **The socket's folder** must be a folder (not a link) owned by the app's user, with no permission for its group or others, checked when the REPL opens; else `:groundwork-live-repl/socket-refused` with its `:reason`, and nothing opens. nREPL's own words: POSIX leaves a socket file's permissions to each system, so access is arranged by the folder. The socket is made `rw-------` too.
- **What is at the path already:** nothing, the socket is made; a socket nobody answers on (a process that ended without closing it) is deleted and made again; a socket a process answers on, or anything that isn't a socket, is refused.
- **Closing deletes the socket** (nREPL leaves it).
- **The path** is absolute and at most 107 bytes, what a Unix socket's address holds.
- `find-repl-port` is `find-repl-address`: `{:kind :tcp :bind-address … :port …}` with the port taken, or `{:kind :unix-socket :path …}`. The log's `:groundwork-live-repl/started` and `:groundwork-live-repl/emergency-opened` say `:groundwork-live-repl/address` the same way, in place of the address and the port.
- `:port-file` is a TCP address's: a socket has no port.

**Why:** in a container (Iron Rainbow's stage under podman), the container's loopback is its own: a TCP REPL there is reached only by `exec`ing into it, or by publishing a port, which `:ssh-only` refuses. A socket in a folder mounted from the host is reached by the host's user alone, with the folder's permissions as the lock, and needs no port at all. OpenSSH forwards a socket as it forwards a port.

**The price:** every app's live-repl settings change shape (`:bind-address :port :port-file` move into `:address`), and `find-repl-port` is gone. A REPL on a socket is refused in a folder that lets a group in, even one only the app's people are in.

**Instead of:**
- **a `:socket` key beside `:bind-address` and `:port`, nil for none:** two ways said at once, one of them nil
- **the socket's own permissions only:** POSIX doesn't promise a system checks them
- **a socket left at the path refused:** every crash would need a hand to delete it before the next start
