# Loopback, unless development writes another address

> **Changed in part** by [A REPL may listen on a Unix socket](2026-10-27-a-repl-may-listen-on-a-unix-socket.md) (2026-10-27): the bind address and the port are a TCP `:address`; a REPL may listen on a Unix socket instead.

**Decision:** every mode that listens writes its `:bind-address` out, an IP address written as one (`"127.0.0.1"`, `"::1"`), never a host name. Outside `:development` it must be loopback (127.0.0.0/8 or ::1): `:ssh-only` and the emergency switch refuse anything else at the start, `:study/bad-settings`, naming the key. Only `:development` takes another address, and only the one written there (a development box reached over WireGuard).

**Why:** nREPL has no login: whoever reaches the port runs any code as the app. On loopback, reaching it takes a shell on the machine, which takes SSH. A host name is refused because what it resolves to is the resolver's (`/etc/hosts`, DNS), not the settings': `"localhost"` can name a public address. Writing the address out even where it can only be loopback keeps the settings saying what listens where, and lets `"::1"` be chosen.

In a container, loopback is the container's own: the REPL is reached with `docker exec` into it, or with the host's network. Publishing the port would take a bind beyond the container's loopback, which `:ssh-only` refuses.

**Instead of:**
- `:ssh-only` binding 127.0.0.1 with no key: the address would be a value the settings don't show, and `::1` couldn't be chosen
- any address in any mode, the app's choice: one wrong line in stage's settings opens a REPL to the network
- TLS on nREPL: SSH already encrypts the tunnel, and TLS wouldn't add a login
