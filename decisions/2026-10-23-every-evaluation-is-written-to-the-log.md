# Every evaluation is written to the log

**Decision:** in every mode, every `eval` and `load-file` message is written to the app's log (`:log`, `(log level event data)` as logbook makes it), in two lines:

| event | when | data |
|---|---|---|
| `:study/evaluation-started` | before it runs | `:study/op` (`"eval"`, `"load-file"`), `:study/message-id`, `:study/session` (nil: an ephemeral session), `:study/namespace` (the one the message names; nil: the session's current one), `:study/code` (load-file: the file's text), and `:study/file-path` for load-file |
| `:study/evaluated` | when nREPL says it is done | the same, and `:study/outcome` (`:returned`, `:threw`, `:interrupted`, `:refused`), `:study/values` (how many came back), `:study/ended-in-namespace` (the namespace the last value came back in, nil when none did), and for `:threw` `:study/error-class` and `:study/root-error-class` |

Both at `:info`; at `:warn` when the REPL was opened by the emergency switch. Values are never written. Other ops (completion, docs, describe, the app's middleware's own) are not evaluations and write nothing.

The log is written by a wrapper around the whole nREPL handler, outside every middleware, so no middleware the app adds (shadow-cljs's ClojureScript REPL among them) can take an evaluation past it.

**Why:** a REPL in production is a way around every permission; what was run there must be readable afterwards, by whom (the session) and where. The line before it runs means an evaluation that never ends, or ends the JVM, is still in the log. Values are the customers' data: written, they would be in Loki for as long as the logs are kept. The error's class tells what went wrong without its message, which can carry data too. The emergency switch's lines are warnings so they stand out in production, where they are rare.

**The price:** an eval that names no namespace is written with `:study/namespace` nil; the session's namespace at that moment is known only inside nREPL's session middleware, and reading it there would lean on nREPL's private state. `:study/ended-in-namespace` tells where the last value came back. Ops the app's middleware handles that evaluate code by themselves (cider-nrepl's test runner, its inspector) are not written: they run code the middleware chose, not code a person sent.

**Instead of:**
- one line when it is done: an evaluation that hangs or ends the JVM leaves nothing
- writing the values: data in the logs
- the wrapper as one more middleware placed by nREPL's descriptors: an app's middleware that handles `eval` (shadow-cljs's) could be placed outside it
- logging only in production: the same code path in every mode is the one the tests prove
