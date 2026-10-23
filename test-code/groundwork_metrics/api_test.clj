(ns groundwork-metrics.api-test
  "Counters, gauges and histograms written exactly, a value outside a
   label's set counted as \"other\" and logged once, read metrics, bad calls
   that never throw, declarations refused, the JVM's metrics in the format,
   counting from many threads, and the route's 401 and 200."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-settings.api :as settings])
  (:import
   (java.util.concurrent CountDownLatch)))

(defn- make-log-into
  "A logger log whose lines go into an atom, as JSON data."
  [lines]
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 10
                                               :write-to #(swap! lines conj (json/read-value %))})))

(defn- make-test-meter
  ([lines] (make-test-meter lines false))
  ([lines jvm-metrics]
   (metrics/make-meter {:jvm-metrics jvm-metrics :most-series-per-metric 100} {:log (make-log-into lines)})))

(def ^:private messages-total
  {:name    :groundwork-bridge/messages-total
   :type    :counter
   :help    "Messages answered, by name and outcome."
   :labels  {:name #{:orders/of-shop :orders/save} :outcome #{:ok :error}}
   :buckets nil
   :read    nil})

(def ^:private open-lines
  {:name :groundwork-bridge/open-lines :type :gauge :help "Open sse and ws lines." :labels {:pipe #{:sse :ws}}
   :buckets nil :read nil})

(def ^:private answer-seconds
  {:name :groundwork-bridge/answer-seconds :type :histogram :help "Time to answer a message, in seconds."
   :labels {:name #{:orders/of-shop}} :buckets [0.05 0.25 1] :read nil})

(def ^:private started-total
  {:name :groundwork/starts-total :type :counter :help "Starts." :labels {} :buckets nil :read nil})

(defn- events [lines]
  (mapv #(get % "event") @lines))

;; -----------------------------------------------------------------------------
;; Writing

(deftest counters-gauges-and-histograms-are-written-exactly
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [messages-total open-lines answer-seconds started-total])
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :ok} 1)
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :ok} 2)
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name "orders/save" :outcome :error} 0.5)
    (metrics/add-to-gauge! m :groundwork-bridge/open-lines {:pipe :sse} 1)
    (metrics/add-to-gauge! m :groundwork-bridge/open-lines {:pipe :sse} 1)
    (metrics/add-to-gauge! m :groundwork-bridge/open-lines {:pipe :sse} -1)
    (metrics/set-gauge! m :groundwork-bridge/open-lines {:pipe :ws} 7)
    (doseq [seconds [0.046875 0.03125 0.125 0.5 5]]
      (metrics/record-in-histogram! m :groundwork-bridge/answer-seconds {:name :orders/of-shop} seconds))
    (is (= (str "# HELP groundwork_bridge_answer_seconds Time to answer a message, in seconds.\n"
                "# TYPE groundwork_bridge_answer_seconds histogram\n"
                "groundwork_bridge_answer_seconds_bucket{name=\"orders/of-shop\",le=\"0.05\"} 2\n"
                "groundwork_bridge_answer_seconds_bucket{name=\"orders/of-shop\",le=\"0.25\"} 3\n"
                "groundwork_bridge_answer_seconds_bucket{name=\"orders/of-shop\",le=\"1.0\"} 4\n"
                "groundwork_bridge_answer_seconds_bucket{name=\"orders/of-shop\",le=\"+Inf\"} 5\n"
                "groundwork_bridge_answer_seconds_sum{name=\"orders/of-shop\"} 5.703125\n"
                "groundwork_bridge_answer_seconds_count{name=\"orders/of-shop\"} 5\n"
                "# HELP groundwork_bridge_messages_total Messages answered, by name and outcome.\n"
                "# TYPE groundwork_bridge_messages_total counter\n"
                "groundwork_bridge_messages_total{name=\"orders/of-shop\",outcome=\"ok\"} 3\n"
                "groundwork_bridge_messages_total{name=\"orders/save\",outcome=\"error\"} 0.5\n"
                "# HELP groundwork_bridge_open_lines Open sse and ws lines.\n"
                "# TYPE groundwork_bridge_open_lines gauge\n"
                "groundwork_bridge_open_lines{pipe=\"sse\"} 1\n"
                "groundwork_bridge_open_lines{pipe=\"ws\"} 7\n"
                "# HELP groundwork_starts_total Starts.\n"
                "# TYPE groundwork_starts_total counter\n"
                "groundwork_starts_total 0\n")
           (metrics/write-metrics-text m)))
    (is (= [] @lines) "nothing was logged")))

(deftest help-and-label-values-are-escaped
  (let [m (make-test-meter (atom []))]
    (metrics/declare-metrics! m [{:name :shop/notes-total :type :counter :help "A \\ back\nslash"
                                :labels {:note #{"say \"hi\"\\\n"}} :buckets nil :read nil}])
    (metrics/add-to-counter! m :shop/notes-total {:note "say \"hi\"\\\n"} 1)
    (is (= (str "# HELP shop_notes_total A \\\\ back\\nslash\n"
                "# TYPE shop_notes_total counter\n"
                "shop_notes_total{note=\"say \\\"hi\\\"\\\\\\n\"} 1\n")
           (metrics/write-metrics-text m)))))

(def ^:private sample-line
  "A sample line of the text format: a name, labels, a value."
  #"[a-zA-Z_:][a-zA-Z0-9_:]*(\{[a-zA-Z_][a-zA-Z0-9_]*=\"(?:[^\"\\\n]|\\[\\\"n])*\"(,[a-zA-Z_][a-zA-Z0-9_]*=\"(?:[^\"\\\n]|\\[\\\"n])*\")*\})? (NaN|[+-]Inf|-?[0-9]+(\.[0-9]+)?(E-?[0-9]+)?)")

(deftest the-jvms-metrics-are-in-the-format-when-turned-on
  (let [m    (make-test-meter (atom []) true)
        _    (metrics/declare-metrics! m [started-total])
        text (metrics/write-metrics-text m)
        text-lines (str/split-lines text)]
    (is (str/ends-with? text "\n"))
    (doseq [line text-lines]
      (is (or (re-matches #"# HELP [a-zA-Z_:][a-zA-Z0-9_:]* .*" line)
              (re-matches #"# TYPE [a-zA-Z_:][a-zA-Z0-9_:]* (counter|gauge|histogram|summary)" line)
              (re-matches sample-line line))
          line))
    (doseq [family ["groundwork_starts_total" "jvm_memory_bytes_used" "jvm_memory_pool_bytes_used" "jvm_gc_collection_seconds"
                    "jvm_threads_current" "jvm_threads_started_total" "process_start_time_seconds" "process_cpu_seconds_total"]]
      (is (some #{(str "# HELP " family)} (map #(str/join " " (take 3 (str/split % #" "))) text-lines)) family))
    (is (re-find #"(?m)^jvm_memory_bytes_used\{area=\"heap\"\} [0-9]+$" text))
    (testing "and not when turned off"
      (let [off (make-test-meter (atom []) false)]
        (is (= "" (metrics/write-metrics-text off)))))))

;; -----------------------------------------------------------------------------
;; Closed sets

(deftest a-value-outside-its-set-is-other-and-logged-once
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [messages-total])
    (doseq [stranger ["/wp-admin" "/.env" :orders/delete 404 nil {:a 1}]]
      (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name stranger :outcome :ok} 1))
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :timeout} 1)
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :teapot} 1)
    (is (= (str "# HELP groundwork_bridge_messages_total Messages answered, by name and outcome.\n"
                "# TYPE groundwork_bridge_messages_total counter\n"
                "groundwork_bridge_messages_total{name=\"orders/of-shop\",outcome=\"other\"} 2\n"
                "groundwork_bridge_messages_total{name=\"other\",outcome=\"ok\"} 6\n")
           (metrics/write-metrics-text m))
        "six strangers, one series; two outcomes, one series")
    (is (= [{"level" "warn" "event" "groundwork-metrics/label-value-not-in-set"
             "data"  {"groundwork-metrics/metric" "groundwork-bridge/messages-total" "groundwork-metrics/label" "name"
                      "groundwork-metrics/value" "/wp-admin" "groundwork-metrics/counted-as" "other"}}
            {"level" "warn" "event" "groundwork-metrics/label-value-not-in-set"
             "data"  {"groundwork-metrics/metric" "groundwork-bridge/messages-total" "groundwork-metrics/label" "outcome"
                      "groundwork-metrics/value" ":timeout" "groundwork-metrics/counted-as" "other"}}]
           (mapv #(select-keys % ["level" "event" "data"]) @lines))
        "once per metric and label: the first stranger only")))

(deftest an-empty-set-counts-every-value-as-other
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [{:name :groundwork-bridge/asked-total :type :counter :help "Asked, by name." :labels {:name #{}}
                                :buckets nil :read nil}])
    (metrics/add-to-counter! m :groundwork-bridge/asked-total {:name :orders/of-shop} 1)
    (metrics/add-to-counter! m :groundwork-bridge/asked-total {:name "x"} 1)
    (is (= (str "# HELP groundwork_bridge_asked_total Asked, by name.
"
                "# TYPE groundwork_bridge_asked_total counter
"
                "groundwork_bridge_asked_total{name=\"other\"} 2
")
           (metrics/write-metrics-text m)))
    (is (= ["groundwork-metrics/label-value-not-in-set"] (events lines)))))

(deftest a-long-stranger-is-logged-shortened
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [messages-total])
    (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name (apply str (repeat 5000 "a")) :outcome :ok} 1)
    (is (= (str (apply str (repeat 200 "a")) "…") (get-in (first @lines) ["data" "groundwork-metrics/value"])))))

;; -----------------------------------------------------------------------------
;; Read gauges

(deftest a-read-gauge-is-read-when-written-and-its-failure-logged-until-it-reads-again
  (let [lines  (atom [])
        m      (make-test-meter lines)
        answer (atom {{:state :active} 2 {:state :idle} 5 {:state :leaked} 1 {:state "weird"} 3})]
    (metrics/declare-metrics! m [{:name :groundwork-postgres/connections :type :gauge :help "Pool connections, by state."
                                :labels {:state #{:active :idle}} :buckets nil
                                :read #(let [a @answer] (if (instance? Throwable a) (throw a) a))}])
    (is (= (str "# HELP groundwork_postgres_connections Pool connections, by state.\n"
                "# TYPE groundwork_postgres_connections gauge\n"
                "groundwork_postgres_connections{state=\"active\"} 2\n"
                "groundwork_postgres_connections{state=\"idle\"} 5\n"
                "groundwork_postgres_connections{state=\"other\"} 4\n")
           (metrics/write-metrics-text m))
        "strangers add up under other")
    (reset! answer (ex-info "pool closed" {}))
    (let [text (metrics/write-metrics-text m)]
      (metrics/write-metrics-text m)
      (is (= (str "# HELP groundwork_postgres_connections Pool connections, by state.\n"
                  "# TYPE groundwork_postgres_connections gauge\n")
             text)))
    (reset! answer {{:state :active} 1})
    (metrics/write-metrics-text m)
    (reset! answer :not-a-map)
    (metrics/write-metrics-text m)
    (is (= ["groundwork-metrics/label-value-not-in-set" "groundwork-metrics/read-failed" "groundwork-metrics/read-failed"] (events lines))
        "once while failing, again after it read")
    (is (= "pool closed" (get-in @lines [1 "error" "message"])))
    (is (= ":not-a-map" (get-in @lines [2 "data" "groundwork-metrics/given"])))
    (testing "a read gauge isn't set by hand"
      (metrics/set-gauge! m :groundwork-postgres/connections {:state :active} 3)
      (is (= "groundwork-metrics/wrong-type" (last (events lines)))))))

;; a counter something else keeps, as groundwork-logs keeps its lines by level
(deftest a-read-counter-is-written-as-read
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [{:name :groundwork-logs/lines-total :type :counter :help "Lines written, by level."
                                :labels {:level #{:debug :info :warn :error}} :buckets nil
                                :read (fn [] {{:level :info} 12 {:level :error} 2})}])
    (is (= (str "# HELP groundwork_logs_lines_total Lines written, by level.\n"
                "# TYPE groundwork_logs_lines_total counter\n"
                "groundwork_logs_lines_total{level=\"error\"} 2\n"
                "groundwork_logs_lines_total{level=\"info\"} 12\n")
           (metrics/write-metrics-text m)))
    (metrics/add-to-counter! m :groundwork-logs/lines-total {:level :info} 1)
    (is (= ["groundwork-metrics/wrong-type"] (events lines)) "a read counter isn't counted by hand")))

;; -----------------------------------------------------------------------------
;; Bad calls

(deftest a-bad-call-counts-nothing-never-throws-and-is-logged-once
  (let [lines (atom [])
        m     (make-test-meter lines)]
    (metrics/declare-metrics! m [messages-total open-lines])
    (dotimes [_ 3]
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/nothing-total {} 1)))
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/open-lines {:pipe :ws} 1)))
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :ok} -1)))
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop :outcome :ok} ##NaN)))
      (is (nil? (metrics/set-gauge! m :groundwork-bridge/open-lines {:pipe :ws} "7")))
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/of-shop} 1)))
      (is (nil? (metrics/add-to-counter! m :groundwork-bridge/messages-total nil 1)))
      (is (nil? (metrics/record-in-histogram! m "groundwork-bridge/open-lines" {} 1))))
    (is (= ["groundwork-metrics/metric-not-declared" "groundwork-metrics/wrong-type" "groundwork-metrics/bad-value" "groundwork-metrics/bad-value" "groundwork-metrics/bad-labels"
            "groundwork-metrics/metric-not-declared"]
           (events lines)))
    (is (every? #(= "error" (get % "level")) @lines))
    (is (= (str "# HELP groundwork_bridge_messages_total Messages answered, by name and outcome.\n"
                "# TYPE groundwork_bridge_messages_total counter\n"
                "# HELP groundwork_bridge_open_lines Open sse and ws lines.\n"
                "# TYPE groundwork_bridge_open_lines gauge\n")
           (metrics/write-metrics-text m)))))

;; -----------------------------------------------------------------------------
;; Settings and declarations

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest the-settings-and-options-are-every-key-written-out
  (let [log (make-log-into (atom []))]
    (is (= :groundwork-metrics/bad-settings (:type (thrown-data #(metrics/make-meter {:jvm-metrics true} {:log log})))))
    (is (= {:most-series-per-metric ["missing required key"] :colour ["disallowed key"]}
           (:errors (thrown-data #(metrics/make-meter {:jvm-metrics true :colour :red} {:log log})))))
    (is (= :groundwork-metrics/bad-options (:type (thrown-data #(metrics/make-meter {:jvm-metrics true :most-series-per-metric 1} {})))))
    (is (= :groundwork-logs/not-a-log (:type (thrown-data #(metrics/make-meter {:jvm-metrics true :most-series-per-metric 1}
                                                                     {:log (fn [_ _ _])})))))))

(deftest a-declaration-is-every-key-written-out-and-checked
  (let [m       (make-test-meter (atom []))
        refused #(:errors (thrown-data (fn [] (metrics/declare-metrics! m %))))]
    (is (= [{:read ["missing required key"]}] (refused [(dissoc messages-total :read)])))
    (is (= {:shop/paid       ["a counter's name ends in -total"]
            :shop/answer     ["a histogram says its :buckets"]
            :shop/lines      ["only a histogram has :buckets"]
            :shop/size       ["its buckets go up, each bigger than the one before"]
            :shop/sets-total ["\"other\" is groundwork-metrics's own, for a value outside the set; no set holds it"
                              "it can make 120 series, more than the settings' :most-series-per-metric, 100"]
            :jvm/heap-total  ["its name, written jvm_heap_total, starts with one meter keeps for the JVM and the process"]
            :shop/le-total   ["a label's name has no namespace, doesn't start with __, and isn't le"]
            :shop/same-total ["no two values in a label's set are written the same"]
            :shop/read-time  ["a histogram has no :read"]}
           (refused [{:name :shop/paid :type :counter :help "h" :labels {} :buckets nil :read nil}
                     {:name :shop/answer :type :histogram :help "h" :labels {} :buckets nil :read nil}
                     {:name :shop/lines :type :gauge :help "h" :labels {} :buckets [1] :read nil}
                     {:name :shop/size :type :histogram :help "h" :labels {} :buckets [1 1] :read nil}
                     {:name :shop/sets-total :type :counter :help "h" :buckets nil :read nil
                      :labels {:a (set (range 9)) :b #{"other" 1 2 3 4 5 6 7 8 9 10}}}
                     {:name :jvm/heap-total :type :counter :help "h" :labels {} :buckets nil :read nil}
                     {:name :shop/le-total :type :counter :help "h" :labels {:le #{1}} :buckets nil :read nil}
                     {:name :shop/same-total :type :counter :help "h" :labels {:a #{:x "x"}} :buckets nil :read nil}
                     {:name :shop/read-time :type :histogram :help "h" :labels {} :buckets [1] :read (fn [] {})}])))
    (testing "all or none: nothing above was declared"
      (is (= "" (metrics/write-metrics-text m))))
    (testing "a value make-meter didn't make is refused, before the declarations"
      (doseq [not-a-meter [nil {} (into {} m)]]
        (is (= :groundwork-metrics/not-a-meter (:type (thrown-data #(metrics/declare-metrics! not-a-meter [started-total])))))))
    (testing "the same declaration again is no change; a different one is refused"
      (metrics/declare-metrics! m [started-total])
      (metrics/declare-metrics! m [started-total])
      (is (= {:groundwork/starts-total ["was declared before, differently"]}
             (refused [(assoc started-total :help "Other.")])))
      (is (= {:groundwork/starts-total ["is written groundwork_starts_total, as [:groundwork-starts/total] is"]
              :groundwork-starts/total ["is written groundwork_starts_total, as [:groundwork/starts-total] is"]}
             (refused [{:name :groundwork-starts/total :type :counter :help "h" :labels {} :buckets nil :read nil}]))))))

;; -----------------------------------------------------------------------------
;; Many threads

(deftest counting-from-many-threads-loses-nothing
  (let [m     (make-test-meter (atom []))
        start (CountDownLatch. 1)]
    (metrics/declare-metrics! m [messages-total open-lines answer-seconds])
    (let [threads (doall (for [_ (range 16)]
                           (Thread/startVirtualThread
                            (fn []
                              (.await start)
                              (dotimes [_ 10000]
                                (metrics/add-to-counter! m :groundwork-bridge/messages-total {:name :orders/save :outcome :ok} 1)
                                (metrics/add-to-gauge! m :groundwork-bridge/open-lines {:pipe :ws} 1)
                                (metrics/record-in-histogram! m :groundwork-bridge/answer-seconds {:name :orders/of-shop} 0.1))))))]
      (.countDown start)
      (run! #(.join ^Thread %) threads))
    (let [text (metrics/write-metrics-text m)]
      (is (str/includes? text "groundwork_bridge_messages_total{name=\"orders/save\",outcome=\"ok\"} 160000\n"))
      (is (str/includes? text "groundwork_bridge_open_lines{pipe=\"ws\"} 160000\n"))
      (is (str/includes? text "groundwork_bridge_answer_seconds_count{name=\"orders/of-shop\"} 160000\n"))
      (is (str/includes? text "groundwork_bridge_answer_seconds_bucket{name=\"orders/of-shop\",le=\"0.25\"} 160000\n")))))

;; -----------------------------------------------------------------------------
;; The route

(deftest the-route-answers-the-bearer-of-the-token-and-401-to-anyone-else
  (let [m       (make-test-meter (atom []))
        _       (metrics/declare-metrics! m [started-total])
        [[path {{:keys [handler]} :get}] :as routes] (metrics/make-metrics-routes m {:path "/metrics" :token (settings/make-secret :groundwork-metrics/token "s3cret")})
        ask     #(handler {:request-method :get :uri "/metrics" :headers %})]
    (is (= 1 (count routes)))
    (is (= "/metrics" path))
    (doseq [headers [{} {"authorization" "Bearer wrong"} {"authorization" "s3cret"} {"authorization" "Bearer s3cret "}
                     {"authorization" "bearer s3cret"} {"authorization" "Basic s3cret"}]]
      (let [answer (ask headers)]
        (is (= 401 (:status answer)) (pr-str headers))
        (is (= "Bearer" (get-in answer [:headers "www-authenticate"])))
        (is (not (str/includes? (:body answer) "groundwork_starts_total")))))
    (let [answer (ask {"authorization" "Bearer s3cret"})]
      (is (= 200 (:status answer)))
      (is (= {"content-type" "text/plain; version=0.0.4; charset=utf-8" "cache-control" "no-store"} (:headers answer)))
      (is (= (metrics/write-metrics-text m) (:body answer))))
    (testing "its settings are every key written out"
      (is (= {:token ["missing required key"]}
             (:errors (thrown-data #(metrics/make-metrics-routes m {:path "/metrics"})))))
      (is (= :groundwork-metrics/bad-routes-settings
             (:type (thrown-data #(metrics/make-metrics-routes m {:path "/metrics" :token ""}))))))
    (testing "the token is a secret: written out as text, it is refused, and the text is never said"
      (let [refused (thrown-data #(metrics/make-metrics-routes m {:path "/metrics" :token "written-out-token"}))]
        (is (= {:token ["should be a #secret, read from the settings' secrets source"]} (:errors refused)))
        (is (not (str/includes? (pr-str refused) "written-out-token")))))))
