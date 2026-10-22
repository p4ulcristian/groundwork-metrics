# The text format is written by hand, on the JVM's own counters

**Decision:** meter writes Prometheus' text exposition format, version 0.0.4, itself (`meter.text`, about 60 lines), and counts with the JDK's own: a `DoubleAdder` per counter series, an `AtomicLong` holding a double per gauge series, a `LongAdder` per histogram bucket and a `DoubleAdder` for its sum. No Prometheus client library.

- A value is a whole number without a point below 10^15, any other as Java writes a double (`0.025`, `1.7927E9`; Go's `ParseFloat`, which Prometheus uses, reads both), and `NaN`, `+Inf`, `-Inf`.
- A bucket's `le` always has a point (`"1.0"`, `"0.25"`, `"+Inf"`), as Prometheus' own clients write it.
- `# HELP` escapes `\` and newlines; a label value escapes `\`, `"` and newlines.
- The declared metrics come sorted by their written name, each series by its label values, so the text is the same for the same counts. A histogram's `_count` is its `+Inf` bucket, summed from the same buckets, so the two never disagree.
- The answer is `text/plain; version=0.0.4; charset=utf-8`.

Checked by exact text in the tests, and by `promtool check metrics` (Prometheus 3) on the whole output with the JVM's metrics, which reports no problem.

**Why:** Iron Rainbow counts with iapetos 0.1.15, a Clojure wrapper over Prometheus' `simpleclient` 0.16 and `simpleclient_hotspot`. That client line is the old one, replaced by `prometheus-metrics-core` 1.x. 1.x reads its own settings from a `prometheus.properties` file and from system properties, values that set behaviour the app doesn't write ([every value is a setting](2026-10-23-every-value-that-sets-behaviour-is-a-setting.md)); it carries native histograms and exemplars, which meter has no use for; and it is several jars (core, model, config, the exposition formats, the JVM instrumentation). What meter needs of either is a few counters and one text format, which is a page of code with no settings of its own. The label rule ([closed sets](2026-10-23-a-label-value-comes-from-a-closed-set.md)) is meter's either way: no client knows a closed set.

**The price:** meter owns the format: a mistake in it is meter's to find. It writes 0.0.4 only, not OpenMetrics or protobuf; every Prometheus reads 0.0.4, whatever else it asks for first. No exemplars and no native histograms.

**Instead of:**
- `prometheus-metrics-core` 1.x with `prometheus-metrics-exposition-textformats`: maintained, but configured from outside the app, and more than meter uses
- iapetos over `simpleclient` 0.16, as Iron Rainbow does: the client line Prometheus has replaced, and a wrapper over it
