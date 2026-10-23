;; What the bot decides over a snapshot's window: every change of the running intent (kind, for,
;; stage, child) with the feet, the held counts and the planner's wait, and where the plan ends.
;; Usage: clojure -M probe/overworld_trace.clj <snapshot.edn.gz>[,...]
(require '[clojure.string :as str]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.simrace :as simrace])

(defn brief
  [i]
  (when i
    (-> (select-keys i
                     [:intent/kind :intent/for :intent/stage :intent/to-y :intent/dir :intent/target
                      :intent/trunk :intent/cell :intent/item :intent/status :intent/reason
                      :intent/no-shaft :intent/length :intent/level-steps])
        (assoc :child
               (select-keys (:intent/child i)
                            [:intent/kind :intent/for :intent/status :intent/reason])))))

(defn line
  [t0 world]
  (let [f (intent/feet world)
        held (recipe/counts (:player/inventory world))]
    (println "T"
             (quot (- (:time/now world) t0) 1000)
             (pr-str {:feet f
                      :hp (:player/health world)
                      :held (select-keys held
                                         [:dirt :cobblestone :gravel :flint :stick :oak_planks
                                          :crafting_table :stone_pickaxe :wooden_pickaxe :iron_ingot
                                          :bucket :water_bucket :flint_and_steel
                                          :cobbled_deepslate])
                      :wait (:plan/waiting world)
                      :intent (brief (:plan/intent world))}))
    (flush)))

(doseq [path (str/split (first *command-line-args*) #",")]
  (println "SNAP" path)
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        t0 (:time/now (:world m))
        seen (atom nil)
        n (atom 0)
        key-of (fn [world]
                 (let [i (:plan/intent world)]
                   [(:intent/kind i) (:intent/stage i) (:intent/kind (:intent/child i))
                    (:intent/status (:intent/child i)) (:plan/waiting world)]))
        stop?
        (fn [world]
          (let [k (key-of world)]
            (when (and (not= k @seen) (< @n 200)) (reset! seen k) (swap! n inc) (line t0 world)))
          (or (plan/done? world) (plan/failed? world)))
        r (sim/play-from simrace/step
                         m
                         stop?
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
        world (:world r)]
    (println "END" (pr-str {:status (:plan/status world) :reason (:plan/reason world)}))
    (line t0 world)
    (println "GATHER"
             (pr-str (try (brief (wood/gather-next world)) (catch Exception e (ex-message e)))))
    (println "TRUNKS"
             (pr-str (try (take 5 (wood/far-trunks world)) (catch Exception e (ex-message e)))))
    (flush)))
(shutdown-agents)
