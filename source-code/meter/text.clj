(ns meter.text
  "Prometheus' text exposition format, version 0.0.4, written by hand: a
   family is its # HELP and # TYPE lines and its samples, one per line.

     # HELP mailroom_messages_total Messages answered, by name and outcome.
     # TYPE mailroom_messages_total counter
     mailroom_messages_total{name=\"orders/of-shop\",outcome=\"ok\"} 41

   A family here is {:name :help :type :samples}, each sample {:name
   :labels [[label-name text] …] :value text}; the caller orders both."
  (:require
   [clojure.string :as str]))

(defn write-number
  "A sample's value: a whole number below 10^15 without a point, any other
   as Java writes a double (Go's ParseFloat, which Prometheus uses, reads
   both), NaN and the infinities as Prometheus writes them."
  [x]
  (let [d (double x)]
    (cond
      (Double/isNaN d)                                    "NaN"
      (= d Double/POSITIVE_INFINITY)                      "+Inf"
      (= d Double/NEGATIVE_INFINITY)                      "-Inf"
      (and (== d (Math/rint d)) (< (Math/abs d) 1.0E15)) (str (long d))
      :else                                               (Double/toString d))))

(defn write-bucket-bound
  "A histogram bucket's le: always with a point, as Prometheus' own clients
   write it (\"1.0\", \"0.25\"), so a bound is one series whoever wrote it."
  [bound]
  (if (= ##Inf bound) "+Inf" (Double/toString (double bound))))

(defn- escape-help [text]
  (-> text (str/replace "\\" "\\\\") (str/replace "\n" "\\n")))

(defn- escape-label-value [text]
  (-> text (str/replace "\\" "\\\\") (str/replace "\"" "\\\"") (str/replace "\n" "\\n")))

(defn- write-labels [^StringBuilder out labels]
  (when (seq labels)
    (.append out "{")
    (loop [[[label-name text] & more] labels first? true]
      (when-not first? (.append out ","))
      (.append out ^String label-name)
      (.append out "=\"")
      (.append out ^String (escape-label-value text))
      (.append out "\"")
      (when more (recur more false)))
    (.append out "}")))

(defn write-families
  "The families as one text, each line ending in a newline."
  [families]
  (let [out (StringBuilder.)]
    (doseq [{:keys [name help type samples]} families]
      (.append out (str "# HELP " name " " (escape-help help) "\n"))
      (.append out (str "# TYPE " name " " type "\n"))
      (doseq [{sample-name :name :keys [labels value]} samples]
        (.append out ^String sample-name)
        (write-labels out labels)
        (.append out " ")
        (.append out ^String value)
        (.append out "\n")))
    (str out)))
