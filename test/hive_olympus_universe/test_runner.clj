(ns hive-olympus-universe.test-runner
  (:require [clojure.test :as test]
            [hive-olympus-universe.manifest-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'hive-olympus-universe.manifest-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
