# The tools are the app's functions, in study.tools

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** the REPL's tools are functions the app hands study, by name, in `start-repl-server!`'s options, `:tools`, every key required, `{}` for none:

```clojure
{:ask-as-person   {:doc "…" :function (fn [message person-id] …)}
 :why-refused     {:doc "…" :function (fn [person-id action target] …)}
 :explain-setting {:doc "…" :function (fn [path] …)}}
```

While a REPL is open, each is a var in `study.tools`, named by its keyword, its `:doc` the var's doc: `(study.tools/ask-as-person …)`, `(doc study.tools/why-refused)`, `(dir study.tools)`. They are put there before the REPL opens and taken away when it closes. `study.tools` refers nothing of `clojure.core`, so a tool may take any name; a tool's name is a keyword without a namespace. study calls none of them and knows nothing of what they do.

What each should do, and how house builds it (house v0.5.0 had them as `groundwork.repl/ask`, `why`, `explain-setting`):

- **`:ask-as-person`**: a message answered as that person, through every check a browser's message gets (params shape, doorman's access, the answer's shape); a write runs too, keyed to run once. house: the raw handlers put through `doorman/put-access-into-handlers` with a `:find-session-step` that sets `:doorman/person-id` from the request instead of a cookie (and adds `{:person-id …}` to `:mailroom/log`), then `(mailroom/answer-message those-handlers :no-rate-limits request message {:wire wire :log log})`, the person on the request.
- **`:why-refused`**: whether a person may do an action, on a record (`[kind id]`) or in a scope, in plain words: each rule that gives the action and whether it holds, and the deny that said no. house v0.5.0 had it from `groundwork.permissions.check/why`; doorman v0.5.0 has no such function, so doorman needs one first. Until then house can answer half: `doorman/list-actions-of-person` (what the person holds, by scope) and the action's SQL condition (`doorman/make-sql-conditions`) run for that record as that person.
- **`:explain-setting`**: what goes at a place in house's settings: its shape and the text that says what it is. house: `malli.util/get-in` on `house.server.main.api/settings-shape` at the path, its form, and the docstring of the library shape found there.

v0.5.0's others (a person's sessions, signing them out everywhere, migrations waiting or changed, queued and dead background jobs, retrying one, the open lines and recent bells) are tools the same way, each built by house from the library that has it, when house wants them. v0.5.0's browser side (`groundwork.repl.browser`, the question store's entries) is not study's: study is the server's REPL.

**Why:** study can't build the tools without requiring the libraries they come from, and the app already has them put together, with its wire and its log. Plain functions in one known namespace are what a person and an agent (clojure-mcp) both find with `dir` and `doc`, with nothing to learn. A doc string per tool, written out, is what makes them findable.

**The price:** each tool is house's code to write and test. `study.tools` is one namespace, so one study runs in a JVM at a time (`:study/already-running`).

**Instead of:**
- study shipping the tools: study would require doorman and mailroom, follow their versions, and fit only apps that use both
- tools as a map the person reaches with `(get tools :why-refused)`: less to type is not the point, but `doc` and `dir` work only on vars
- interning into `user`: a session that switched namespace wouldn't find them, and a tool named like a `clojure.core` function would clash
