(ns groundwork-metrics.settings
  "groundwork-metrics's settings, options and metric declarations, as malli shapes,
   checked before anything counts. Nothing has a default: every key is
   written out, nil where nothing applies, and a missing or unknown one
   refuses, named, with every other problem at once."
  (:require
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-settings.api :as settings]))

(def other
  "The value a label is counted under when the value given isn't in its
   set. groundwork-metrics's own: no declared set may hold it."
  "other")

(def settings-shape
  "make-meter's settings, every key required.
   :jvm-metrics: true writes the JVM's and the process's metrics (memory,
   garbage collection, threads, start time, CPU, open files) with the
   app's; false writes only the declared ones.
   :most-series-per-metric: the most series one declaration may make,
   counting every value of every label and \"other\" (a histogram's series
   are each label combination's buckets, +Inf, sum and count); a bigger
   declaration is refused."
  [:map {:closed true}
   [:jvm-metrics :boolean]
   [:most-series-per-metric [:int {:min 1}]]])

(def options-shape
  "make-meter's options, every key required.
   :log: the app's log, (log level event data), as groundwork-logs makes it."
  [:map {:closed true}
   [:log fn?]])

(def routes-settings-shape
  "make-metrics-routes' settings, every key required.
   :path: the GET Prometheus pulls the metrics from, \"/metrics\".
   :token: the bearer token it must send (Authorization: Bearer …), a
   secret of groundwork-settings's (#secret :groundwork-metrics/token in the app's settings file);
   text written out is refused."
  [:map {:closed true}
   [:path [:re #"^/"]]
   [:token settings/secret]])

(def label-value-shape
  "A label's value: text, a keyword (written without its colon, its
   namespace kept: :orders/of-shop is \"orders/of-shop\") or a whole number."
  [:or :string :keyword :int])

(def metric-shape
  "One metric's declaration, every key written out, nil where nothing
   applies.
   :name: a namespaced keyword, its namespace who owns it;
   :groundwork-bridge/messages-total is written groundwork_bridge_messages_total. A counter's
   ends in -total.
   :type: :counter (only goes up), :gauge (set, or up and down) or
   :histogram (values counted into buckets).
   :help: one line saying what it counts.
   :labels: each label's name and its closed set of values, {} for none.
   A value outside the set is counted under \"other\"; an empty set
   counts every value there (a library whose table has no names yet).
   :buckets: a histogram's upper bounds, ascending; nil for the others.
   :read: for a counter or a gauge something else already keeps (a pool's
   connections, the lines a logger wrote), read when the metrics are
   written: (fn [] {labels value}), {{} 3} without labels; nil for a
   histogram and for a metric meter counts. A read counter's values only
   go up; that is the reader's to keep."
  [:map {:closed true}
   [:name qualified-keyword?]
   [:type [:enum :counter :gauge :histogram]]
   [:help [:string {:min 1}]]
   [:labels [:map-of :keyword [:set label-value-shape]]]
   [:buckets [:maybe [:vector {:min 1} number?]]]
   [:read [:maybe fn?]]])

(defn check-shape
  "Returns the value; throws {:type error-type :errors …} with every
   problem at once."
  [shape value error-type what]
  (when-let [explanation (malli/explain shape value)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type error-type :errors errors}))))
  value)

;; -----------------------------------------------------------------------------
;; Names and values as text

(defn write-label-value
  "A label value as text, or nil when it is none of text, a keyword or a
   whole number."
  [value]
  (cond
    (string? value)  value
    (keyword? value) (subs (str value) 1)
    (int? value)     (str value)
    :else            nil))

(defn write-metric-name
  "A metric's name as Prometheus reads it: :groundwork-bridge/messages-total is
   groundwork_bridge_messages_total."
  [metric-name]
  (str/replace (str (namespace metric-name) "_" (name metric-name)) #"[-.]" "_"))

(defn write-label-name
  [label-name]
  (str/replace (name label-name) "-" "_"))

(def ^:private metric-name-pattern #"[a-zA-Z_:][a-zA-Z0-9_:]*")
(def ^:private label-name-pattern #"[a-zA-Z_][a-zA-Z0-9_]*")

(def reserved-name-starts
  "The names meter writes for the JVM and the process; no declaration may
   start with them."
  ["jvm_" "process_"])

(defn- count-series
  [{:keys [type labels buckets]}]
  (* (reduce * 1 (map #(inc (count %)) (vals labels)))
     (if (= :histogram type) (+ (count buckets) 3) 1)))

(defn find-declaration-problems
  "What the shape can't say about one declaration, as texts; empty when
   none. Its shape is checked first."
  [{:keys [name type labels buckets read] :as declaration} most-series-per-metric]
  (let [text-name (write-metric-name name)]
    (cond-> []
      (not (re-matches metric-name-pattern text-name))
      (conj (str "its name, written " text-name ", isn't a Prometheus name"))

      (some #(str/starts-with? text-name %) reserved-name-starts)
      (conj (str "its name, written " text-name ", starts with one meter keeps for the JVM and the process"))

      (and (= :counter type) (not (str/ends-with? text-name "_total")))
      (conj "a counter's name ends in -total")

      (and (not= :counter type) (str/ends-with? text-name "_total"))
      (conj "only a counter's name ends in -total")

      (and (= :histogram type) (nil? buckets))
      (conj "a histogram says its :buckets")

      (and (not= :histogram type) (some? buckets))
      (conj "only a histogram has :buckets")

      (and buckets (not (every? #(Double/isFinite (double %)) buckets)))
      (conj "its buckets are finite numbers; +Inf is groundwork-metrics's own")

      (and buckets (not (apply < (map double buckets))))
      (conj "its buckets go up, each bigger than the one before")

      (and (some? read) (= :histogram type))
      (conj "a histogram has no :read")

      (some #(not (re-matches label-name-pattern (write-label-name %))) (keys labels))
      (conj "each label's name, written with _ for -, is a Prometheus label name")

      (some #(or (qualified-keyword? %) (str/starts-with? (write-label-name %) "__") (= "le" (write-label-name %)))
            (keys labels))
      (conj "a label's name has no namespace, doesn't start with __, and isn't le")

      (not (apply distinct? nil (map write-label-name (keys labels))))
      (conj "no two labels' names are written the same")

      (some (fn [values] (not (apply distinct? nil (map write-label-value values)))) (vals labels))
      (conj "no two values in a label's set are written the same")

      (some (fn [values] (some #(= other (write-label-value %)) values)) (vals labels))
      (conj (str "\"" other "\" is groundwork-metrics's own, for a value outside the set; no set holds it"))

      (> (count-series declaration) most-series-per-metric)
      (conj (str "it can make " (count-series declaration) " series, more than the settings' :most-series-per-metric, "
                 most-series-per-metric)))))
