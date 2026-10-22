(ns meter.api
  "manor's metrics: a meter the app makes once and hands to every library,
   as it hands its log. Each library declares its metrics on it as data,
   every label with its closed set of values, and counts into them; the
   app serves the text Prometheus pulls, behind a bearer token. meter's one
   public namespace.

     (def meter (meter/make-meter {:jvm-metrics true :most-series-per-metric 2000}
                                  {:log log}))

     (meter/declare-metrics!
      meter
      [{:name    :mailroom/messages-total
        :type    :counter
        :help    \"Messages answered, by name and outcome.\"
        :labels  {:name #{:orders/of-shop :orders/save} :outcome #{:ok :invalid :error}}
        :buckets nil
        :read    nil}])

     (meter/add-to-counter! meter :mailroom/messages-total {:name :orders/of-shop :outcome :ok} 1)

     ;; the app's composition: GET /metrics, for the bearer of the token
     (meter/make-metrics-routes meter {:path \"/metrics\" :token token})

   Every label value comes from a closed set declared up front: a value
   outside it is counted under \"other\", never as a series of its own, and
   written to the app's log once per metric and label. Counting never
   throws: a call naming no declared metric, labels that aren't the
   declared ones, a value that isn't a finite number (or a negative one
   for a counter) counts nothing and is written to the log at :error, once
   per metric and mistake."
  (:require
   [logbook.api :as logbook]
   [meter.jvm :as jvm]
   [meter.settings :as settings]
   [meter.text :as text])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.util Map Set)
   (java.util.concurrent ConcurrentHashMap)
   (java.util.concurrent.atomic AtomicLong DoubleAdder LongAdder)
   (java.util.function Function)))

(def settings-shape
  "make-meter's settings, every key required:
   {:jvm-metrics true|false :most-series-per-metric n}. See
   meter.settings/settings-shape for each key."
  settings/settings-shape)

(def metric-shape
  "One metric's declaration, every key written out, nil where nothing
   applies: {:name :type :help :labels :buckets :read}. See
   meter.settings/metric-shape for each key."
  settings/metric-shape)

(def routes-settings-shape
  "make-metrics-routes' settings, every key required: {:path :token}."
  settings/routes-settings-shape)

(defn make-meter
  "A meter from its settings (settings-shape) and options, {:log log}, the
   app's log as logbook makes it; every key required, checked first
   (:meter/bad-settings, :meter/bad-options, :logbook/not-a-log). No
   metric is declared yet."
  [meter-settings options]
  (settings/check-shape settings/settings-shape meter-settings :meter/bad-settings "The settings")
  (settings/check-shape settings/options-shape options :meter/bad-options "The options")
  ;; a function logbook didn't make is refused here, not at the first line
  (logbook/with-context (:log options) {})
  ;; marked, so declare-metrics! can tell a meter from any other map
  (with-meta {:settings      meter-settings
              :log           (:log options)
              :metrics       (atom {})
              :logged        (ConcurrentHashMap/newKeySet)
              :failing-reads (ConcurrentHashMap/newKeySet)}
    {::meter true}))

(defn- check-meter
  "Throws :meter/not-a-meter for a value make-meter didn't make: a library
   handed something else under :meter refuses at its start."
  [meter]
  (when-not (::meter (meta meter))
    (throw (ex-info "A meter is made by meter's make-meter" {:type :meter/not-a-meter}))))

;; -----------------------------------------------------------------------------
;; Declaring

(defn- make-cell [type bucket-count]
  (case type
    :counter   (DoubleAdder.)
    :gauge     (AtomicLong. (Double/doubleToRawLongBits 0.0))
    :histogram {:counts (vec (repeatedly (inc bucket-count) #(LongAdder.)))
                :sum    (DoubleAdder.)}))

(defn- make-metric [{:keys [name type labels buckets read] :as declaration}]
  (let [label-names (vec (sort-by settings/write-label-name (keys labels)))
        metric      {:declaration declaration
                     :text-name   (settings/write-metric-name name)
                     :label-names label-names
                     :label-texts (mapv settings/write-label-name label-names)
                     :allowed     (update-vals labels #(set (map settings/write-label-value %)))
                     :bounds      (when buckets (mapv double buckets))
                     :series      (ConcurrentHashMap.)}]
    ;; without labels the one series is there from the start, at 0
    (when (and (empty? labels) (nil? read))
      (.put ^Map (:series metric) [] (make-cell type (count buckets))))
    metric))

(defn- find-batch-problems
  "Problems of the declarations against each other and the ones declared
   before, by metric name."
  [declared declarations]
  (let [by-name      (group-by :name declarations)
        text-owners  (group-by settings/write-metric-name
                               (distinct (concat (keys declared) (map :name declarations))))]
    (merge-with into
                (into {} (for [[metric-name same] by-name
                               :when (not (apply = same))]
                           [metric-name ["is declared twice, differently, in these declarations"]]))
                (into {} (for [{metric-name :name :as declaration} declarations
                               :let [before (get-in declared [metric-name :declaration])]
                               :when (and before (not= before declaration))]
                           [metric-name ["was declared before, differently"]]))
                (into {} (for [[text-name owners] text-owners
                               :when (< 1 (count owners))
                               owner owners]
                           [owner [(str "is written " text-name ", as " (pr-str (vec (remove #{owner} owners))) " is")]])))))

(defn declare-metrics!
  "Declares the metrics (each a metric-shape) on the meter, all or none:
   every problem at once, by name (:meter/bad-metrics). A name declared
   again the same way is no change; differently, refused. Each library
   declares its own when it is handed the meter. A value make-meter didn't
   make is refused first (:meter/not-a-meter)."
  [meter declarations]
  (check-meter meter)
  (settings/check-shape [:sequential settings/metric-shape] declarations :meter/bad-metrics "The declarations")
  (let [most-series (get-in meter [:settings :most-series-per-metric])]
    ;; checked against what is declared inside the swap, so two libraries
    ;; declaring at once both see the other's
    (swap! (:metrics meter)
           (fn [declared]
             (let [problems (merge-with into
                                        (into {} (for [{metric-name :name :as declaration} declarations
                                                       :let [found (settings/find-declaration-problems declaration most-series)]
                                                       :when (seq found)]
                                                   [metric-name found]))
                                        (find-batch-problems declared declarations))]
               (when (seq problems)
                 (throw (ex-info (str "The declarations have problems: " (pr-str problems))
                                 {:type :meter/bad-metrics :errors problems})))
               (reduce (fn [metrics {metric-name :name :as declaration}]
                         (if (contains? metrics metric-name)
                           metrics
                           (assoc metrics metric-name (make-metric declaration))))
                       declared
                       declarations))))
    nil))

;; -----------------------------------------------------------------------------
;; Counting

(defn- log-once!
  "Writes the line the first time this key is seen, never again."
  [{:keys [^Set logged log]} once-key level event data]
  (when (.add logged once-key)
    (log level event data)))

(defn- shorten [value]
  (let [text (if (string? value) value (pr-str value))]
    (if (> (count text) 200) (str (subs text 0 200) "…") text)))

(defn- find-series-key
  "The label texts in the metric's label order, a value outside its set
   as \"other\"; nil, written to the log, when the labels aren't the
   declared ones."
  [meter {:keys [declaration label-names label-texts allowed]} labels]
  (let [metric-name (:name declaration)]
    (if-not (and (map? labels) (= (set (keys labels)) (set label-names)))
      (log-once! meter [:bad-labels metric-name] :error :meter/bad-labels
                 {:meter/metric metric-name :meter/declared-labels label-names
                  :meter/given (shorten labels)})
      (mapv (fn [label-name label-text]
              (let [given (get labels label-name)
                    text  (settings/write-label-value given)]
                (if (contains? (allowed label-name) text)
                  text
                  (do (log-once! meter [:not-in-set metric-name label-name] :warn :meter/label-value-not-in-set
                                 {:meter/metric metric-name :meter/label label-text :meter/value (shorten given)
                                  :meter/counted-as settings/other})
                      settings/other))))
            label-names label-texts))))

(defn- find-metric
  "The declared metric to count into by this function, or nil, written to
   the log, when there is none or it is of another type."
  [meter metric-name type counting]
  (let [metric (get @(:metrics meter) metric-name)]
    (cond
      (nil? metric)
      (log-once! meter [:not-declared metric-name] :error :meter/metric-not-declared
                 {:meter/metric (if (keyword? metric-name) metric-name (shorten metric-name)) :meter/counting counting})

      (or (not= type (get-in metric [:declaration :type])) (some? (get-in metric [:declaration :read])))
      (log-once! meter [:wrong-type metric-name counting] :error :meter/wrong-type
                 {:meter/metric metric-name :meter/type (get-in metric [:declaration :type])
                  :meter/read? (some? (get-in metric [:declaration :read])) :meter/counting counting})

      :else metric)))

(defn- good-value?
  [meter metric-name counting value at-least-zero?]
  (or (and (number? value)
           (Double/isFinite (double value))
           (or (not at-least-zero?) (<= 0.0 (double value))))
      (log-once! meter [:bad-value metric-name counting] :error :meter/bad-value
                 {:meter/metric metric-name :meter/counting counting :meter/value (shorten value)})))

(defn- find-cell [metric series-key]
  (.computeIfAbsent ^Map (:series metric) series-key
                    (reify Function
                      (apply [_ _] (make-cell (get-in metric [:declaration :type]) (count (:bounds metric)))))))

(defn- count-into!
  "Finds the metric, the value and the series, and hands the cell to f;
   nil always. Nothing it is given makes it throw."
  [meter metric-name type counting labels value at-least-zero? f]
  (try
    (when-let [metric (find-metric meter metric-name type counting)]
      (when (good-value? meter metric-name counting value at-least-zero?)
        (when-let [series-key (find-series-key meter metric labels)]
          (f metric (find-cell metric series-key) (double value)))))
    (catch Throwable failure
      (log-once! meter [:failed metric-name counting] :error :meter/counting-failed
                 {:meter/metric (shorten metric-name) :meter/counting counting :error failure})))
  nil)

(defn add-to-counter!
  "Adds amount, a finite number from 0, to the counter's series for these
   labels ({} for none). Returns nil, never throws."
  [meter metric-name labels amount]
  (count-into! meter metric-name :counter :add-to-counter! labels amount true
               (fn [_ ^DoubleAdder cell amount] (.add cell amount))))

(defn set-gauge!
  "Sets the gauge's series for these labels ({} for none) to value, a
   finite number. Returns nil, never throws."
  [meter metric-name labels value]
  (count-into! meter metric-name :gauge :set-gauge! labels value false
               (fn [_ ^AtomicLong cell value] (.set cell (Double/doubleToRawLongBits value)))))

(defn add-to-gauge!
  "Adds amount, a finite number, below 0 to take away, to the gauge's
   series for these labels ({} for none): a line opened, +1; closed, -1.
   Returns nil, never throws."
  [meter metric-name labels amount]
  (count-into! meter metric-name :gauge :add-to-gauge! labels amount false
               (fn [_ ^AtomicLong cell amount]
                 (loop []
                   (let [before (.get cell)
                         after  (Double/doubleToRawLongBits (+ (Double/longBitsToDouble before) amount))]
                     (when-not (.compareAndSet cell before after) (recur)))))))

(defn record-in-histogram!
  "Counts value, a finite number, into the histogram's series for these
   labels ({} for none): into the first bucket whose bound it doesn't pass
   (+Inf past the last), and into its sum. Returns nil, never throws."
  [meter metric-name labels value]
  (count-into! meter metric-name :histogram :record-in-histogram! labels value false
               (fn [{:keys [bounds]} {:keys [counts ^DoubleAdder sum]} value]
                 (let [index (loop [i 0]
                               (if (and (< i (count bounds)) (> value (double (nth bounds i))))
                                 (recur (inc i))
                                 i))]
                   (.increment ^LongAdder (nth counts index))
                   (.add sum value)))))

;; -----------------------------------------------------------------------------
;; Writing

(defn- labels-of [{:keys [label-texts]} series-key]
  (mapv vector label-texts series-key))

(defn- write-histogram-samples [{:keys [text-name bounds] :as metric} series-key {:keys [counts ^DoubleAdder sum]}]
  (let [labels     (labels-of metric series-key)
        cumulative (reductions + (map #(.sum ^LongAdder %) counts))]
    (concat
     (map (fn [bound total]
            {:name (str text-name "_bucket") :labels (conj labels ["le" (text/write-bucket-bound bound)])
             :value (str total)})
          (conj bounds ##Inf) cumulative)
     [{:name (str text-name "_sum") :labels labels :value (text/write-number (.sum sum))}
      {:name (str text-name "_count") :labels labels :value (str (last cumulative))}])))

(defn- read-values
  "A read metric's values by series key, now; nil when the read failed,
   written to the log once until it reads again."
  [{:keys [^Set failing-reads log] :as meter} {:keys [declaration] :as metric}]
  (let [metric-name (:name declaration)
        result      (try ((:read declaration)) (catch Throwable failure failure))
        failure     (cond
                      (instance? Throwable result)
                      {:error result}

                      (not (and (map? result) (every? (fn [[labels value]] (and (map? labels) (number? value))) result)))
                      {:meter/given (shorten result)})]
    (if failure
      (do (when (.add failing-reads metric-name)
            (log :error :meter/read-failed (merge {:meter/metric metric-name} failure)))
          nil)
      (do (.remove failing-reads metric-name)
          (reduce (fn [values [labels value]]
                    (if-let [series-key (find-series-key meter metric labels)]
                      (update values series-key (fnil + 0.0) (double value))
                      values))
                  {} result)))))

(defn- write-family [meter {:keys [declaration text-name ^Map series] :as metric}]
  (let [{:keys [type help read]} declaration
        values (if read
                 (read-values meter metric)
                 (into {} series))]
    {:name    text-name
     :help    help
     :type    (name type)
     :samples (mapcat (fn [[series-key cell]]
                        (case type
                          :counter   [{:name text-name :labels (labels-of metric series-key)
                                       :value (text/write-number (if read cell (.sum ^DoubleAdder cell)))}]
                          :gauge     [{:name text-name :labels (labels-of metric series-key)
                                       :value (text/write-number (if read
                                                                   cell
                                                                   (Double/longBitsToDouble (.get ^AtomicLong cell))))}]
                          :histogram (write-histogram-samples metric series-key cell)))
                      (sort-by key values))}))

(defn write-metrics-text
  "Every metric in Prometheus' text format, version 0.0.4: the declared
   ones by their written name, each series by its label values, then the
   JVM's and the process's when the settings' :jvm-metrics is true."
  [meter]
  (text/write-families
   (concat (map #(write-family meter %) (sort-by :text-name (vals @(:metrics meter))))
           (when (get-in meter [:settings :jvm-metrics])
             (jvm/read-families)))))

;; -----------------------------------------------------------------------------
;; The route

(defn- hash-text ^bytes [^String text]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text StandardCharsets/UTF_8)))

(defn- has-token?
  "Whether the request carries the token as its bearer token. Both sides
   are hashed first, so the comparison takes the same time whatever the
   token's length and wherever the two first differ."
  [request token]
  (let [given (get-in request [:headers "authorization"])]
    (and (string? given)
         (MessageDigest/isEqual (hash-text given) (hash-text (str "Bearer " token))))))

(def ^:private not-allowed
  {:status  401
   :headers {"content-type" "text/plain; charset=utf-8" "cache-control" "no-store" "www-authenticate" "Bearer"}
   :body    "This address needs the metrics token."})

(defn make-metrics-routes
  "The metrics route, a GET at the settings' :path, as reitit data (its
   handler a plain Ring handler): with the settings' :token as the bearer
   token (Authorization: Bearer …, compared in constant time), 200 and the
   text (write-metrics-text), never cached; without it, 401. The settings
   are checked first (:meter/bad-routes-settings)."
  [meter routes-settings]
  (settings/check-shape settings/routes-settings-shape routes-settings :meter/bad-routes-settings "The routes settings")
  (let [{:keys [path token]} routes-settings]
    [[path {:get {:handler (fn [request]
                             (if-not (has-token? request token)
                               not-allowed
                               {:status  200
                                :headers {"content-type"  "text/plain; version=0.0.4; charset=utf-8"
                                          "cache-control" "no-store"}
                                :body    (write-metrics-text meter)}))}}]]))
