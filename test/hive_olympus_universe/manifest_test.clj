(ns hive-olympus-universe.manifest-test
  "The shipped manifest through the real mounter against a stub hive.olympus
   (a presenter seat) and a stub hive.universe exposing ONLY :vessel/target (a
   recording :text vessel), then against the real hive.olympus core manifest.

   Hermetic: no hive-universe on the classpath. What the real canvas does with
   the ops it is handed is the proof suite's subject, not this one's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]))

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(def seat (atom {}))
(def natives (atom []))

(def panel
  {:op :ui/show-panel :panel/id "olympus/tab-1"
   :doc {:doc/title "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
         :doc/blocks [{:block/type :para :text "No active agents" :tone :muted}]}})

(def vessel
  "hive.universe's hook as the facade contributes it: a :text target, because a
   canvas has no windows to paint into."
  {:vessel/id :universe-stub
   :vessel/dialect :text
   :vessel/features #{}
   :vessel/execute! (fn [native] (swap! natives conj native) {:delivered 1})})

(defn olympus-stub-ctor [_]
  (->StubAddon "hive.olympus"
               {:olympus/register-presenter! (fn [id target] (swap! seat assoc id target) (target [panel]) id)
                :olympus/unregister-presenter! (fn [id] (swap! seat dissoc id) id)}))

(defn universe-stub-ctor
  "hive.universe's hook surface as shipped: :vessel/target and nothing else."
  [_]
  (->StubAddon "hive.universe" {:vessel/target (fn [] vessel)}))

(defn six-agents []
  (mapv #(hash-map :agent/id (str "ling-" %) :agent/name (str "worker-" %) :agent/status :idle)
        (range 1 7)))

(defn manifest
  "The shipped addon manifest FILE, read as data."
  [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(def universe-stub-spec
  {:addon/id "hive.universe" :addon/type :native
   :addon/init-ns "hive-olympus-universe.manifest-test" :addon/init-fn "universe-stub-ctor"
   :addon/capabilities #{:vessel}})

(defn- mount-all [specs]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)]
    [host report]))

(defn- wire
  "What reached the vessel: [dialect panel-id] per native."
  []
  (mapv (fn [{:native/keys [dialect payload]}]
          [dialect (:text/panel payload)])
        @natives))

(deftest the-manifest-is-data-only
  (let [spec (manifest "hive-olympus-universe.edn")]
    (is (= "hive.olympus.universe" (:addon/id spec)))
    (is (= "hive-olympus.harness" (:addon/init-ns spec)))
    (is (= "addon-ctor" (:addon/init-fn spec)))
    (is (= {:olympus/host "hive.universe"} (:addon/config spec)))
    (is (= #{"hive.olympus" "hive.universe"} (:addon/dependencies spec)))
    (is (= :foss (:addon/trust-class spec)))
    (is (some #(= "hive.olympus.universe" (:addon/id %)) (:specs (mount/discover-specs))))))

(deftest the-stub-host-offers-only-a-target
  (is (= #{:vessel/target} (set (keys (addon/hooks (universe-stub-ctor {})))))
      "no :vessel/dispatch!, so :host-target is the only route that can deliver"))

(deftest mounts-against-stubs-and-delivers-through-host-target
  (reset! seat {})
  (reset! natives [])
  (let [[host report] (mount-all [(manifest "hive-olympus-universe.edn")
                                  {:addon/id "hive.olympus" :addon/type :native
                                   :addon/init-ns "hive-olympus-universe.manifest-test"
                                   :addon/init-fn "olympus-stub-ctor" :addon/capabilities #{}}
                                  universe-stub-spec])
        brick (mount-port/registered host "hive.olympus.universe")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= "hive.olympus.universe" (last (:order report))))
      (is (contains? @seat "hive.universe") "registered under the host id")
      (is (= [[:text "olympus/tab-1"]] (wire))
          "the seat's op is lowered by hive-vessel into one :text native on the target")
      (let [lines (vec (get-in (first @natives) [:native/payload :text/lines]))]
        (is (= "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
               (first lines)))
        (is (some #{"No active agents"} lines))
        (is (every? string? lines)
            "already-rendered lines, which is why the canvas needs no hive-vessel of its own"))
      (is (= :host-target (get-in (addon/health brick) [:details :route])))
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (is (empty? @seat) "teardown unregisters the presenter")
      (finally (when brick (addon/shutdown! brick))))))

(deftest mounts-against-the-real-core
  (reset! natives [])
  (let [core-spec (update (manifest "hive-olympus.edn") :addon/config assoc
                          :olympus/refresh-ms 0
                          :olympus/roster-fn 'hive-olympus-universe.manifest-test/six-agents)
        [host report] (mount-all [(manifest "hive-olympus-universe.edn") core-spec universe-stub-spec])
        core (mount-port/registered host "hive.olympus")
        brick (mount-port/registered host "hive.olympus.universe")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (testing "the real core renders six agents on two tabs and the brick lowers both"
        (is (= [[:text "olympus/tab-1"] [:text "olympus/tab-2"]] (wire)))
        (is (= {:status :live :deliveries 1}
               (get-in (addon/health core) [:details :presenters "hive.universe"])))
        (is (= :host-target (get-in (addon/health brick) [:details :route]))))
      (mount/teardown! host (:order report))
      (finally (doseq [id ["hive.olympus.universe" "hive.olympus"]]
                 (when-let [a (mount-port/registered host id)]
                   (try (addon/shutdown! a) (catch Throwable _ nil))))))))
