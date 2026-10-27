# study decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

study is groundwork's REPL (groundwork: the libraries and the composer of the same name that puts them together). The first two carry Groundwork's REPL over; the rest were decided as v0.1.0 was built.

- [study is its own library](2026-10-23-study-is-its-own-library.md)
- [REPLs by environment](2026-10-23-repls-by-environment.md)
- [Loopback, unless development writes another address](2026-10-23-loopback-unless-development-writes-another-address.md), changed in part by [A REPL may listen on a Unix socket](2026-10-27-a-repl-may-listen-on-a-unix-socket.md)
- [Every evaluation is written to the log](2026-10-23-every-evaluation-is-written-to-the-log.md)
- [The emergency switch is a file the app's user owns](2026-10-23-the-emergency-switch-is-a-file-the-apps-user-owns.md)
- [The tools are the app's functions, in study.tools](2026-10-23-the-tools-are-the-apps-functions-in-study-tools.md)
- [study writes no log file](2026-10-23-study-writes-no-log-file.md)
- [Every value that sets behaviour is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md)
- [A REPL may listen on a Unix socket](2026-10-27-a-repl-may-listen-on-a-unix-socket.md) (v0.1.4)
