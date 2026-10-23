(ns groundwork-metrics.jvm
  "The JVM's and the process's metrics, read from the JVM's management
   beans each time the metrics are written, under the names Prometheus'
   Java client (simpleclient_hotspot) gives them, so dashboards made for it
   read these:

     jvm_memory_bytes_used|committed|max|init{area}      heap, nonheap
     jvm_memory_pool_bytes_used|committed|max|init{pool} each memory pool
     jvm_gc_collection_seconds{gc}                       a summary: count and sum, per collector
     jvm_threads_current|daemon|peak, jvm_threads_started_total
     process_start_time_seconds                          uptime is time() minus it
     process_cpu_seconds_total
     process_open_fds, process_max_fds                   on Unix
     process_virtual_memory_bytes, process_resident_memory_bytes   on Linux

   The pool and collector names are the JVM's own, a set fixed for the run.
   A max the JVM doesn't know is -1, as the JVM says it."
  (:require
   [clojure.java.io :as io]
   [groundwork-metrics.text :as text])
  (:import
   (com.sun.management UnixOperatingSystemMXBean)
   (java.lang.management GarbageCollectorMXBean ManagementFactory MemoryPoolMXBean MemoryUsage)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files)))

(defn- gauge [family-name help samples]
  {:name family-name :help help :type "gauge" :samples samples})

(defn- sample
  ([sample-name value] {:name sample-name :labels [] :value (text/write-number value)})
  ([sample-name label-name label-value value]
   {:name sample-name :labels [[label-name label-value]] :value (text/write-number value)}))

(def ^:private usage-parts
  [["used" "Used bytes" #(.getUsed ^MemoryUsage %)]
   ["committed" "Committed bytes" #(.getCommitted ^MemoryUsage %)]
   ["max" "Max bytes (-1 when the JVM sets none)" #(.getMax ^MemoryUsage %)]
   ["init" "Initial bytes" #(.getInit ^MemoryUsage %)]])

(defn- memory-families []
  (let [memory (ManagementFactory/getMemoryMXBean)
        areas  [["heap" (.getHeapMemoryUsage memory)] ["nonheap" (.getNonHeapMemoryUsage memory)]]
        pools  (sort-by first (map (fn [^MemoryPoolMXBean pool] [(.getName pool) (.getUsage pool)])
                                   (ManagementFactory/getMemoryPoolMXBeans)))]
    (concat
     (for [[part help read-part] usage-parts
           :let [family-name (str "jvm_memory_bytes_" part)]]
       (gauge family-name (str help " of a JVM memory area.")
              (for [[area usage] areas] (sample family-name "area" area (read-part usage)))))
     (for [[part help read-part] usage-parts
           :let [family-name (str "jvm_memory_pool_bytes_" part)]]
       (gauge family-name (str help " of a JVM memory pool.")
              (for [[pool usage] pools :when usage] (sample family-name "pool" pool (read-part usage))))))))

(defn- garbage-collection-family []
  (let [collectors (sort-by first (map (fn [^GarbageCollectorMXBean gc]
                                         [(.getName gc) (.getCollectionCount gc) (.getCollectionTime gc)])
                                       (ManagementFactory/getGarbageCollectorMXBeans)))]
    {:name    "jvm_gc_collection_seconds"
     :help    "Time spent in a JVM garbage collector, in seconds."
     :type    "summary"
     :samples (mapcat (fn [[gc-name collections ms]]
                        [(sample "jvm_gc_collection_seconds_count" "gc" gc-name collections)
                         (sample "jvm_gc_collection_seconds_sum" "gc" gc-name (/ (double ms) 1000.0))])
                      collectors)}))

(defn- thread-families []
  (let [threads (ManagementFactory/getThreadMXBean)]
    [(gauge "jvm_threads_current" "Current thread count of the JVM."
            [(sample "jvm_threads_current" (.getThreadCount threads))])
     (gauge "jvm_threads_daemon" "Daemon thread count of the JVM."
            [(sample "jvm_threads_daemon" (.getDaemonThreadCount threads))])
     (gauge "jvm_threads_peak" "Peak thread count of the JVM."
            [(sample "jvm_threads_peak" (.getPeakThreadCount threads))])
     {:name "jvm_threads_started_total" :help "Threads the JVM has started." :type "counter"
      :samples [(sample "jvm_threads_started_total" (.getTotalStartedThreadCount threads))]}]))

(defn- read-proc-status-kilobytes
  "VmSize and VmRSS from /proc/self/status, in bytes, or {} where there is
   no /proc."
  []
  (let [file (io/file "/proc/self/status")]
    (if-not (.canRead file)
      {}
      (into {}
            (keep (fn [line]
                    (when-let [[_ field kilobytes] (re-matches #"(VmSize|VmRSS):\s+(\d+) kB" line)]
                      [field (* 1024 (parse-long kilobytes))])))
            (Files/readAllLines (.toPath file) StandardCharsets/UTF_8)))))

(defn- process-families []
  (let [runtime (ManagementFactory/getRuntimeMXBean)
        os      (ManagementFactory/getOperatingSystemMXBean)
        status  (read-proc-status-kilobytes)]
    (cond-> [(gauge "process_start_time_seconds" "Start time of the process since the Unix epoch, in seconds."
                    [(sample "process_start_time_seconds" (/ (double (.getStartTime runtime)) 1000.0))])]
      (instance? com.sun.management.OperatingSystemMXBean os)
      (conj {:name "process_cpu_seconds_total" :help "User and system CPU time the process has spent, in seconds."
             :type "counter"
             :samples [(sample "process_cpu_seconds_total"
                               (/ (double (.getProcessCpuTime ^com.sun.management.OperatingSystemMXBean os)) 1.0E9))]})

      (instance? UnixOperatingSystemMXBean os)
      (into [(gauge "process_open_fds" "Open file descriptors."
                    [(sample "process_open_fds" (.getOpenFileDescriptorCount ^UnixOperatingSystemMXBean os))])
             (gauge "process_max_fds" "Most open file descriptors the process may have."
                    [(sample "process_max_fds" (.getMaxFileDescriptorCount ^UnixOperatingSystemMXBean os))])])

      (contains? status "VmSize")
      (conj (gauge "process_virtual_memory_bytes" "Virtual memory size, in bytes."
                   [(sample "process_virtual_memory_bytes" (status "VmSize"))]))

      (contains? status "VmRSS")
      (conj (gauge "process_resident_memory_bytes" "Resident memory size, in bytes."
                   [(sample "process_resident_memory_bytes" (status "VmRSS"))])))))

(defn read-families
  "The JVM's and the process's families, read now."
  []
  (vec (concat (memory-families) [(garbage-collection-family)] (thread-families) (process-families))))
