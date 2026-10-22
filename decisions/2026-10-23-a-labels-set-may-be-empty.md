# A label's set may be empty: every value is "other"

> Changes in part [Every label value comes from a closed set; a stranger is "other"](2026-10-23-a-label-value-comes-from-a-closed-set.md), which required at least one value. Since v0.1.2.

**Decision:** a label may be declared with an empty set, `{:name #{}}`. Every value counted into it is `"other"`, and the first is written to the log at `:warn` as any stranger is. It can make one series per other label's combination.

**Why:** a library declares its sets from what it holds when it starts (["A library takes the app's meter as `:meter`"](2026-10-23-a-library-takes-the-meter-as-meter.md)), and what it holds may be nothing yet: mailroom's routes with no handlers (an app that only sends on sse), backroom with no kinds, doorbell with no table told about. Refusing the declaration there would refuse a start that is right; counting under `"other"` is what the set says.

**The price:** a metric whose set is empty says only that something came, not what.

**Instead of:**
- each library leaving the label out when its set is empty: two declarations of one metric, differing by app
- a placeholder value in the set: a value nobody counts, written in every dashboard's legend
