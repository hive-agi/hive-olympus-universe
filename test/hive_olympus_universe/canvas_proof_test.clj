(ns hive-olympus-universe.canvas-proof-test
  "The live proof: the real Olympus refresh loop, the real harness brick, the
   real hive-vessel lowering, and a real universe canvas read back through the
   port.

   The canvas is an AtomUniverse, hive-universe's own double, which its
   port-conformance suite holds to port/IUniverse alongside the in-process and
   remote implementations. What is faked is the nREPL server the grid never
   touches; the transcript, the append and the say-channel are the real ones.

   Runs under -M:proof, which needs the hive-universe checkout."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]
            [hive-olympus-universe.manifest-test :as m]
            [hive-universe.atom-universe :as atom-universe]
            [hive-universe.channel :as channel]
            [hive-universe.port :as port]
            [hive-universe.vessel :as vessel]))

(def roster (atom []))

(defn current-roster [] @roster)

(defn universe-ctor
  "hive.universe's hook as the facade contributes it: one target, built once."
  [_]
  (m/->StubAddon "hive.universe" {:vessel/target (vessel/target)}))

(def universe-spec
  {:addon/id "hive.universe" :addon/type :native
   :addon/init-ns "hive-olympus-universe.canvas-proof-test"
   :addon/init-fn "universe-ctor" :addon/capabilities #{:vessel}})

(defn- core-spec [refresh-ms]
  (update (m/manifest "hive-olympus.edn") :addon/config assoc
          :olympus/refresh-ms refresh-ms
          :olympus/roster-fn 'hive-olympus-universe.canvas-proof-test/current-roster))

(defn- agents [& specs]
  (mapv (fn [[id status]] {:agent/id id :agent/name id :agent/status status}) specs))

(defn- grid-bodies
  "The olympus utterances on U's canvas, oldest first."
  [u]
  (into [] (comp (map :utterance/body)
                 (filter #(str/starts-with? % "[olympus/")))
        (:universe/transcript (port/-snapshot u))))

(defn- panel-of [body]
  (second (re-find #"^\[([^\]]+)\]" body)))

(defn- wait-for
  "F's first truthy value within MS, else nil."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))

(defn- presenter-status [core]
  (get-in (addon/health core) [:details :presenters "hive.universe" :status]))

(defrecord FlakyCanvas [inner failing? panel]
  ;; A fault-injecting decorator over the conformance-proven double: it refuses
  ;; posts for one panel while FAILING? is set, and delegates everything else.
  port/IUniverse
  (-snapshot  [_] (port/-snapshot inner))
  (-act!      [_ id deed] (port/-act! inner id deed))
  (-post!     [_ utterance]
    (when (and @failing?
               (str/starts-with? (:utterance/body utterance) (str "[" panel "]")))
      (throw (ex-info "canvas refused the post" {:panel panel})))
    (port/-post! inner utterance))
  (-annotate! [_ path v] (port/-annotate! inner path v))
  (-watch!    [this k f] (port/-watch! inner k f) this)
  (-unwatch!  [this k] (port/-unwatch! inner k) this)
  (-info      [_] (port/-info inner)))

(defn- mount-grid!
  "Core (ticking every REFRESH-MS), the brick, and the canvas host."
  [refresh-ms]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve [(m/manifest "hive-olympus-universe.edn")
                                           (core-spec refresh-ms)
                                           universe-spec])
                             host)]
    {:host host :report report
     :core (mount-port/registered host "hive.olympus")}))

(use-fixtures :each
  (fn [f]
    (let [prior @channel/state*]
      (try (f)
           (finally (reset! channel/state* prior) (reset! roster []))))))

(deftest the-grid-reaches-a-real-canvas-on-the-refresh-tick
  (let [u (atom-universe/create "proof")]
    (channel/install! u)
    (reset! roster (agents ["ling-1" :working] ["ling-2" :idle] ["ling-3" :idle]
                           ["ling-4" :idle] ["ling-5" :idle] ["ling-6" :idle]))
    (let [{:keys [host report]} (mount-grid! 50)]
      (try
        (is (:ok? report) (pr-str (:mounted report)))
        (is (wait-for 5000 #(= 2 (count (grid-bodies u))))
            "six agents lay out as two tabs, so the canvas gets two utterances")
        (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv panel-of (grid-bodies u))))
        (is (str/includes? (first (grid-bodies u)) "ling-1"))
        (is (str/includes? (first (grid-bodies u)) "1 working"))
        (testing "a quiet tick says nothing, however many times it fires"
          (let [before (count (grid-bodies u))]
            (Thread/sleep 500)
            (is (= before (count (grid-bodies u)))
                "ten more ticks, and the reader's canvas did not move")))
        (testing "a roster change does reach it, on every tab the count is printed on"
          (let [before (count (grid-bodies u))]
            (swap! roster assoc-in [1 :agent/status] :working)
            (is (wait-for 5000 #(> (count (grid-bodies u)) before)))
            (let [fresh (drop before (grid-bodies u))]
              (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv panel-of fresh))
                  "the status counts head every tab, so every tab is stale at once")
              (is (every? #(str/includes? % "2 working") fresh)))))
        (finally (mount/teardown! host (:order report)))))))

(deftest a-tick-before-the-canvas-exists-does-not-silence-the-grid
  ;; Nothing promises a canvas by the time the loop starts: channel/say! throws
  ;; until install!. A key written on intent rather than on arrival would
  ;; suppress every later tick, and the grid would never appear at all.
  (reset! channel/state* nil)
  (reset! roster (agents ["ling-1" :working]))
  (let [{:keys [host report core]} (mount-grid! 50)]
    (try
      (is (wait-for 5000 #(= :degraded (presenter-status core)))
          "the presenter degrades while there is nowhere to post")
      (let [u (atom-universe/create "late")]
        (channel/install! u)
        (is (wait-for 5000 #(seq (grid-bodies u)))
            "the grid appears once there is somewhere to put it")
        (is (= ["olympus/tab-1"] (mapv panel-of (grid-bodies u))))
        (is (wait-for 5000 #(= :live (presenter-status core)))))
      (finally (mount/teardown! host (:order report))))))

(deftest a-retry-after-a-partial-failure-does-not-repost-what-already-landed
  ;; The seat keeps its last SUCCESSFUL delivery, so a presenter that threw
  ;; part-way re-offers the whole delta on every tick, including the tabs that
  ;; did land. This, and not the ordinary tick, is what the canvas key is for:
  ;; the seat's own delta already makes an unchanged tick silent.
  (let [inner (atom-universe/create "flaky")
        failing? (atom true)
        u (->FlakyCanvas inner failing? "olympus/tab-2")]
    (channel/install! u)
    (reset! roster (agents ["ling-1" :working] ["ling-2" :idle] ["ling-3" :idle]
                           ["ling-4" :idle] ["ling-5" :idle] ["ling-6" :idle]))
    (let [{:keys [host report core]} (mount-grid! 50)]
      (try
        (is (wait-for 5000 #(= 1 (count (grid-bodies inner))))
            "tab 1 lands before tab 2 is refused")
        (is (wait-for 5000 #(= :degraded (presenter-status core))))
        (Thread/sleep 500)
        (is (= 1 (count (grid-bodies inner)))
            "ten retries of the same delta, and tab 1 is still on the canvas once")
        (reset! failing? false)
        (is (wait-for 5000 #(= 2 (count (grid-bodies inner)))))
        (is (= ["olympus/tab-1" "olympus/tab-2"] (mapv panel-of (grid-bodies inner))))
        (finally (mount/teardown! host (:order report)))))))
