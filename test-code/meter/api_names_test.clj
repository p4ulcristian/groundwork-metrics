(ns meter.api-names-test
  "The public names of meter's api. Adding or removing one fails here,
   so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [meter.api]))

(deftest the-api-names
  (is (= '#{settings-shape metric-shape routes-settings-shape make-meter declare-metrics!
            add-to-counter! set-gauge! add-to-gauge! record-in-histogram!
            write-metrics-text make-metrics-routes}
         (set (keys (ns-publics 'meter.api))))))
