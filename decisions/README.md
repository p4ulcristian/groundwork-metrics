# meter decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

meter is groundwork's metrics (groundwork: the libraries and the composer of the same name that puts them together). All but the last three were decided as v0.1.0 was built, from Iron Rainbow's metrics and what they taught; the next two as the libraries took the meter (v0.1.1, v0.1.2); the last as the libraries took safe (v0.1.3).

- [meter is its own library](2026-10-23-meter-is-its-own-library.md)
- [The text format is written by hand, on the JVM's own counters](2026-10-23-the-text-format-is-written-by-hand.md)
- [A metric is declared as data](2026-10-23-a-metric-is-declared-as-data.md)
- [Every label value comes from a closed set; a stranger is "other"](2026-10-23-a-label-value-comes-from-a-closed-set.md), changed in part by [A label's set may be empty](2026-10-23-a-labels-set-may-be-empty.md)
- [Counting never throws](2026-10-23-counting-never-throws.md)
- [The metrics are pulled, behind a bearer token](2026-10-23-the-metrics-are-pulled-behind-a-token.md), changed in part by [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md)
- [The JVM's metrics are turned on by the app, under their usual names](2026-10-23-the-jvms-metrics-are-turned-on-by-the-app.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md)
- [A library takes the app's meter as `:meter`, and declares its own where it starts](2026-10-23-a-library-takes-the-meter-as-meter.md)
- [A label's set may be empty: every value is "other"](2026-10-23-a-labels-set-may-be-empty.md)
- [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md)
