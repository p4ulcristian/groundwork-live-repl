# study is its own library

**Decision:** study, manor's REPL, is a library of its own, in its own public repository, under the MIT license; an app or a framework takes it from git by tag. It was Groundwork's `groundwork.repl` and `groundwork.repl.dev` (house v0.5.0); now it is the namespace `study`, with one public namespace, `study.api`, and the namespace the app's tools are put in, `study.tools`. Its `deps.edn` names only nREPL and malli; its tests run with nothing else on the classpath but the test runner and logbook (a real log to write into). It knows nothing of who uses it:

- it starts an nREPL server by settings and writes every evaluation to the log the app hands it;
- the REPL's tools (ask a handler as a person, why a person was refused, explain a setting) are functions the app hands it, by name; study never requires doorman, mailroom or the app;
- what it throws is typed `:study/…`, and what it writes is `:study/…` events.

The name is the user's: the study is the room where you sit and think. It sits beside house's other rooms (parlor, cellar, backroom, mailroom).

**Why:** a REPL into a running app, open only where it should be and leaving a trace, is one idea any Clojure app has. In Groundwork it was tied to the framework's own parts (`gw/endpoints`, `gw/permissions`), so the tools and the server could not be taken apart. Handing the tools in as functions keeps study small, and lets house build them from the libraries it already puts together.

**The price:** a change to study is a new tag, and house moves to it on purpose. The tools themselves are house's code now, not study's.

**Instead of:**
- staying a folder in house: no app could take the REPL alone, and nothing proves it needs nothing of house
- study building the tools by requiring doorman and mailroom: study would follow their versions, and an app without them couldn't use it
- the names repl, console: study was chosen
