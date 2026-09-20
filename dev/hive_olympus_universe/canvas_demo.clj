(ns hive-olympus-universe.canvas-demo
  "Watch the Olympus grid land on a live universe canvas.

     clojure -Sdeps \"$(cat local.deps.edn)\" -M:demo           synthetic roster
     clojure -Sdeps \"$(cat local.deps.edn)\" -M:demo --live     the real hive swarm

   Boots the manager's default universe, installs it as the say-channel,
   mounts hive.olympus and this brick against a host that exposes only
   :vessel/target, and hands the terminal to the canvas. Quit with /quit.

   The host stub stands in for hive.universe, which contributes that hook
   itself once this runs inside a hive."
  (:require [hive-addon.mount :as mount]
            [hive-addon.protocol :as addon]
            [hive-universe.channel :as channel]
            [hive-universe.chat.main :as canvas]
            [hive-universe.manager :as manager]
            [hive-universe.vessel :as vessel]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

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

(defn universe-ctor [_]
  (->StubAddon "hive.universe" {:vessel/target (vessel/target)}))

(def universe-spec
  {:addon/id "hive.universe" :addon/type :native
   :addon/init-ns "hive-olympus-universe.canvas-demo"
   :addon/init-fn "universe-ctor" :addon/capabilities #{:vessel}})

(def roster (atom []))

(defn current-roster [] @roster)

(def script
  "What the synthetic swarm does, one frame per step, so the canvas visibly
   moves instead of printing one grid and going quiet."
  [[[:working "reading the transcript store"] [:idle nil] [:idle nil]
    [:idle nil] [:idle nil] [:idle nil]]
   [[:working "reading the transcript store"] [:working "lowering panels"] [:idle nil]
    [:idle nil] [:idle nil] [:idle nil]]
   [[:working "reading the transcript store"] [:working "lowering panels"] [:blocked "waiting on a lock"]
    [:working "scanning hive-vessel"] [:idle nil] [:idle nil]]
   [[:idle nil] [:working "lowering panels"] [:error "no route resolved"]
    [:working "scanning hive-vessel"] [:working "writing the proof"] [:idle nil]]
   [[:idle nil] [:idle nil] [:error "no route resolved"]
    [:idle nil] [:working "writing the proof"] [:working "posting to the canvas"]]])

(defn- frame [step]
  (vec (map-indexed
        (fn [i [status task]]
          (cond-> {:agent/id (str "ling-" (inc i))
                   :agent/name (str "worker-" (inc i))
                   :agent/status status}
            task (assoc :agent/task task :agent/activity task)))
        step)))

(defn- animate!
  "Walk the script on its own thread, one frame every MS, then hold the last."
  [ms]
  (doto (Thread.
         (fn []
           (doseq [step script]
             (reset! roster (frame step))
             (Thread/sleep ms))))
    (.setDaemon true)
    (.start)))

(defn- manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(defn- core-spec [live?]
  (cond-> (update (manifest "hive-olympus.edn") :addon/config assoc
                  :olympus/refresh-ms 1000)
    ;; No roster-fn means the default: the live hive swarm, lings at depth 1.
    (not live?) (update :addon/config assoc
                        :olympus/roster-fn
                        'hive-olympus-universe.canvas-demo/current-roster)))

(defn -main [& args]
  (let [live? (some #{"--live"} args)
        universe (manager/resolve! manager/default-manager nil)
        host (mount/atom-mount-host)
        report (mount/mount! (mount/solve [(manifest "hive-olympus-universe.edn")
                                           (core-spec live?)
                                           universe-spec])
                             host)]
    (when-not (:ok? report)
      (binding [*out* *err*]
        (println "mount failed:" (pr-str (:mounted report))))
      (System/exit 1))
    (channel/install! universe)
    (when-not live? (animate! 4000))
    (try
      (canvas/run-on! universe)
      (finally
        (mount/teardown! host (:order report))
        (shutdown-agents)
        (System/exit 0)))))
