# meter

Metrics for a Clojure app and its libraries, read by Prometheus. The app
makes one meter and hands it to every library, as it hands its log; each
library declares its metrics on it as data and counts into them. Every
label is declared with its closed set of values: a value outside it is
counted under `"other"`, never as a series of its own, so no caller can
grow the number of series. The app serves the text at one GET, behind a
bearer token, for Prometheus to pull.

```clojure
io.github.p4ulcristian/groundwork-metrics {:git/tag "v0.1.5" :git/sha "1cffe8e"}
```

Needs only [groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) (the app's
log, handed in), [groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (the route's
token is its secret) and malli. Writes Prometheus' text format, version 0.0.4,
itself: no Prometheus client
([why](decisions/2026-10-23-the-text-format-is-written-by-hand.md)). One
public namespace, `groundwork-metrics.api`.

## The api

| name | does |
|---|---|
| `make-meter` `settings-shape` | a meter from its settings, `{:jvm-metrics … :most-series-per-metric …}`, and `{:log log}`; every key required, checked first |
| `declare-metrics!` `metric-shape` | declares metrics as data, all or none, every problem at once; the same declaration again is no change; a value `make-meter` didn't make is refused |
| `add-to-counter!` | adds an amount from 0 to a counter's series |
| `set-gauge!` `add-to-gauge!` | sets a gauge's series, or adds to it (below 0 to take away) |
| `record-in-histogram!` | counts a value into a histogram's bucket and its sum |
| `write-metrics-text` | every metric in the text format, the JVM's too when the settings say so |
| `make-metrics-routes` `routes-settings-shape` | the GET at `:path`, as reitit data: 200 and the text for `Authorization: Bearer <:token>` (constant-time), else 401 |

```clojure
(require '[groundwork-logs.api :as logs]
         '[groundwork-metrics.api :as metrics])

(def log (logs/log-function (logs/make-logger {:lowest-level :info :format :json
                                                      :errors-kept 1000 :write-to :stdout})))

(def meter (metrics/make-meter {:jvm-metrics            true   ; memory, GC, threads, process; false: only the declared
                              :most-series-per-metric 5000}  ; a declaration that can make more is refused
                             {:log log}))

;; a library declares its own when it is handed the meter; the sets come
;; from what it already knows (here, the handler table's names)
(metrics/declare-metrics!
 meter
 [{:name    :groundwork-bridge/messages-total
   :type    :counter
   :help    "Messages answered, by name and outcome."
   :labels  {:name    #{:orders/of-shop :orders/save}
             :outcome #{:ok :invalid :refused :too-many :failed}}
   :buckets nil
   :read    nil}
  {:name    :groundwork-bridge/answer-seconds
   :type    :histogram
   :help    "Time to answer a message, in seconds."
   :labels  {:name #{:orders/of-shop :orders/save}}
   :buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]   ; required: no default buckets
   :read    nil}
  {:name    :groundwork-bridge/open-lines
   :type    :gauge
   :help    "Open sse and ws lines."
   :labels  {:pipe #{:sse :ws}}
   :buckets nil
   :read    nil}
  {:name    :groundwork-postgres/pool-connections
   :type    :gauge
   :help    "The pool's connections, by state."
   :labels  {:state #{:active :idle}}
   :buckets nil
   :read    (fn [] {{:state :active} (.getActiveConnections pool-bean)     ; read when the text is written
                    {:state :idle}   (.getIdleConnections pool-bean)})}])

(metrics/add-to-counter! meter :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :ok} 1)
(metrics/record-in-histogram! meter :groundwork-bridge/answer-seconds {:name :orders/of-shop} 0.012)
(metrics/add-to-gauge! meter :groundwork-bridge/open-lines {:pipe :sse} 1)

(metrics/add-to-counter! meter :groundwork-bridge/messages-total {:name "/wp-admin" :outcome :ok} 1)
;; counted as name="other"; the first stranger for this metric and label is
;; written once: :warn :groundwork-metrics/label-value-not-in-set

;; the app's composition: beside its other routes, outside sessions
(metrics/make-metrics-routes meter {:path "/metrics" :token metrics-token})   ; groundwork-settings's secret, #secret :groundwork-metrics/token
```

```
# HELP groundwork_bridge_answer_seconds Time to answer a message, in seconds.
# TYPE groundwork_bridge_answer_seconds histogram
groundwork_bridge_answer_seconds_bucket{name="orders/of-shop",le="0.005"} 0
groundwork_bridge_answer_seconds_bucket{name="orders/of-shop",le="0.01"} 0
groundwork_bridge_answer_seconds_bucket{name="orders/of-shop",le="0.025"} 1
…
groundwork_bridge_answer_seconds_bucket{name="orders/of-shop",le="+Inf"} 1
groundwork_bridge_answer_seconds_sum{name="orders/of-shop"} 0.012
groundwork_bridge_answer_seconds_count{name="orders/of-shop"} 1
# HELP groundwork_bridge_messages_total Messages answered, by name and outcome.
# TYPE groundwork_bridge_messages_total counter
groundwork_bridge_messages_total{name="orders/of-shop",outcome="ok"} 1
groundwork_bridge_messages_total{name="other",outcome="ok"} 1
```

A name is written with `_` (`:groundwork-bridge/answer-seconds` is
`groundwork_bridge_answer_seconds`), its namespace who owns it; a counter's ends in
`-total`. A label value is text, a keyword (`"orders/of-shop"`, the
namespace kept) or a whole number. A series appears at its first count; a
metric without labels is there from its declaration, at 0.

A groundwork library takes the meter as `:meter` in the options of the
function where it starts counting, and declares its own metrics there,
their buckets from its settings
([why](decisions/2026-10-23-a-library-takes-the-meter-as-meter.md)).

Counting never throws: a call naming no declared metric, labels that
aren't the declared ones, or a value that isn't a finite number (below 0
for a counter) counts nothing and is written to the log at `:error`, once
per metric and mistake
([why](decisions/2026-10-23-counting-never-throws.md)).

## In Prometheus

```yaml
scrape_configs:
  - job_name: shop
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/shop-metrics-token
    static_configs:
      - targets: ["shop.internal:8080"]
```

Uptime is `time() - process_start_time_seconds`. The JVM's metrics carry
the names of Prometheus' Java client, so its dashboards read them.

## Tests

```sh
clojure -X:test
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
