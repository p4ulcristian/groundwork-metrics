# A metric is declared as data

**Decision:** a metric is a map, every key written out, nil where nothing applies (`metric-shape`), declared on the meter with `declare-metrics!` before anything counts into it:

```clojure
{:name    :mailroom/answer-seconds          ; a namespaced keyword: who owns it, and its name
 :type    :histogram                        ; :counter, :gauge or :histogram
 :help    "Time to answer a message, in seconds."
 :labels  {:name #{:orders/of-shop :orders/save}}   ; each label's closed set; {} for none
 :buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]   ; a histogram's, required; nil for the others
 :read    nil}                              ; a counter or gauge read when written; nil when meter counts it
```

- **The name** is written with `_`: `:mailroom/answer-seconds` is `mailroom_answer_seconds`. Its namespace is who owns it, so two libraries' names don't meet. A counter's ends in `-total`, no other's does. Names starting `jvm_` or `process_` are meter's ([the JVM's metrics](2026-10-23-the-jvms-metrics-are-turned-on-by-the-app.md)). Two names written the same are refused.
- **A histogram's buckets are part of its declaration**, ascending, finite (`+Inf` is added); there are no default buckets.
- **A label's name** has no namespace, isn't `le` and doesn't start with `__`; its values are text, keywords (written without the colon, the namespace kept: `"orders/of-shop"`) or whole numbers, no two written the same.
- **`:read`** is for a value something else already keeps: a pool's connections, the lines a logbook wrote. `(fn [] {{:state :active} 3 {:state :idle} 7})` is called each time the metrics are written; its labels go through the same closed sets. A read that throws or returns something else leaves the metric's samples out of that text and is written to the log at `:error`, `:meter/read-failed`, once, and again only after it has read once more.
- **A series appears at its first count.** A metric without labels (and without `:read`) is there from its declaration, at 0.
- **`declare-metrics!` takes all or none**: every problem of every declaration, by name, at once (`:meter/bad-metrics`). A name declared again the same way is no change, so a library may declare each time it is handed the meter; declared differently, it is refused.

**Why:** a declaration that is data can be checked whole before anything counts: its names, its buckets, and how many series it can make. Buckets written out are a choice the reader sees; a client's default buckets fit a web request and nothing else. A read metric keeps one source of truth for a number another part already holds, and needs no call in that part's path.

**The price:** every declaration writes six keys, most of them nil or `{}`. A series counted once stays in every text until the app restarts.

**Instead of:**
- a function per metric, `(counter :name {...})`: the same, less checkable, and the options become optional keys
- default buckets: the most common Prometheus mistake, times that don't fit what is measured
- every series there from the declaration, at 0: `rate()` works from the first count, but every possible combination is written on every scrape, however few happen
