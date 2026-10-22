# The JVM's metrics are turned on by the app, under their usual names

**Decision:** the settings' `:jvm-metrics`, required, true or false, says whether the text carries the JVM's and the process's metrics too, read from the JVM's management beans each time it is written:

| metric | type | labels |
|---|---|---|
| `jvm_memory_bytes_used`, `_committed`, `_max`, `_init` | gauge | `area`: heap, nonheap |
| `jvm_memory_pool_bytes_used`, `_committed`, `_max`, `_init` | gauge | `pool`: each memory pool |
| `jvm_gc_collection_seconds` | summary (count, sum) | `gc`: each collector |
| `jvm_threads_current`, `jvm_threads_daemon`, `jvm_threads_peak` | gauge | |
| `jvm_threads_started_total` | counter | |
| `process_start_time_seconds` | gauge | |
| `process_cpu_seconds_total` | counter | |
| `process_open_fds`, `process_max_fds` | gauge | on Unix |
| `process_virtual_memory_bytes`, `process_resident_memory_bytes` | gauge | on Linux, from `/proc/self/status` |

The names, labels and units are those of Prometheus' Java client (`simpleclient_hotspot`), so dashboards made for it, Iron Rainbow's among them, read these. Uptime is `time() - process_start_time_seconds`, as Prometheus' clients have it. A max the JVM doesn't know is `-1`, as the JVM says it. The pool and collector names are the JVM's own, a set fixed for the run. Names starting `jvm_` and `process_` are meter's, on or off.

**Why:** an app that runs in production wants them, and a test or a second meter does not; a setting says which, rather than a default that writes them where nobody asked. The usual names cost nothing and keep the dashboards that exist.

**The price:** no class loading, buffer pool, allocation or deadlock metrics; added when someone needs them. Reading them costs a few bean calls and one small file read per scrape.

**Instead of:**
- always on: a test's text would carry 100 lines it doesn't check
- the names of Prometheus' 1.x client (`jvm_memory_used_bytes`, …): no dashboard in use reads them
- an uptime gauge: Prometheus' convention is the start time, from which any query gets the uptime
