# Every value that sets behaviour is a setting; no hidden defaults

**Decision:** as in every manor library, every value that sets how meter behaves is written out by the app, checked by malli before anything counts, the maps closed, a missing or unknown key refused, named, with every other problem at once; no function fills in an argument.

- **`make-meter`'s settings**, `settings-shape`: `{:jvm-metrics true|false :most-series-per-metric n}` (`:meter/bad-settings`).
- **Its options**: `{:log log}`, the app's log as logbook makes it, given to logbook's `with-context` once so a function logbook didn't make is refused at once (`:logbook/not-a-log`) (`:meter/bad-options`).
- **A declaration**: all six keys, nil where nothing applies (`metric-shape`, [declared as data](2026-10-23-a-metric-is-declared-as-data.md)).
- **`make-metrics-routes`' settings**, `routes-settings-shape`: `{:path "/metrics" :token "…"}` (`:meter/bad-routes-settings`).
- **Counting** takes the labels (`{}`) and the amount (`1`) always.

**What stays fixed, and why:**

- **`"other"`**, the value a stranger is counted under: what meter writes, and what a dashboard looks for; an app choosing its own word would make every dashboard ask which.
- **A stranger, a bad value or given labels are written to the log shortened to 200 characters**: a log line's content, not behaviour; a scanner's 5,000-character address says nothing more at 5,000.
- **The format**: 0.0.4, its number writing, `le` with a point, the escaping, the content type, `no-store`, the 401's text and `www-authenticate: Bearer`: what Prometheus reads, not what an app chooses ([written by hand](2026-10-23-the-text-format-is-written-by-hand.md)).
- **The JVM's names**: Prometheus' Java client's ([the JVM's metrics](2026-10-23-the-jvms-metrics-are-turned-on-by-the-app.md)).
- **Logging once per metric and label, and once per metric and mistake**: how often meter speaks is part of what it is, as logbook says a broken output once.

**Why:** a value left to a default is a value the app doesn't show ("Every value that sets behaviour is a setting", mailroom).

**Instead of:**
- the series bound as a fixed number: a shop with 300 messages and a histogram each needs a bigger one than a small app, and should see it
- the token or path as defaults: the token is a secret; the path is the app's address space
