(ns hive-olympus-universe.proof-runner
  "The hermetic manifest suite plus the live canvas proof. Needs the
   hive-universe checkout; -M:test is the one that runs anywhere."
  (:require [clojure.test :as test]
            [hive-olympus-universe.canvas-proof-test]
            [hive-olympus-universe.manifest-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'hive-olympus-universe.manifest-test
                                             'hive-olympus-universe.canvas-proof-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
