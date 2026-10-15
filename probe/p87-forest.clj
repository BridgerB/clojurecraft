(require '[clojurecraft.sim :as sim]
         '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.inventory :as inventory]
         '[clojurecraft.gym :as gym]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.world :as world]
         '[clojurecraft.fixtures :as fx]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(.mkdirs (io/file "out"))
(defn out
  "One line on stdout and into out/probe.log."
  [& xs]
  (let [s (str/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))
(def step (game/compose game/step plan/step))
(def tree {:x 11 :z 7 :h 5 :id 137 :mound 2 :canopy? true :branch [1 0]})
(def leaf-id 252)
(defn tree-blocks
  "{[lx y lz] id} for one tree, as sim_test's generator lays it out."
  [{:keys [x z h id mound canopy? branch]}]
  (let [base (+ 64 mound)
        [bx bz] (when branch [(+ x (first branch)) (+ z (second branch))])]
    (merge (into {} (for [mx [(dec x) x] mz [(dec z) z] y (range 64 base)] [[mx y mz] 1]))
           (when canopy?
             (into {}
                   (for [dx (range -2 3)
                         dz (range -2 3)
                         :let [d (+ (abs dx) (abs dz))]
                         :when (and (<= 1 d 2))]
                     [[(+ x dx) (inc base) (+ z dz)] leaf-id])))
           (into {} (for [y (range base (+ base h))] [[x y z] id]))
           (when branch {[bx (+ base h -2) bz] id})
           {[x (+ base h) z] leaf-id})))
(def blocks (tree-blocks tree))
(doseq [[label sx sz] [["spawn-0-0" 0 0] ["spawn-1-1" 1 1]]]
  (let [sim0 (sim/init {:column (world/column-bytes blocks) :spawn [(+ sx 0.5) 64.0 (+ sz 0.5)]})
        [w sim] (sim/run step
                         (game/init fx/opts)
                         sim0
                         #(or (plan/done? %) (plan/failed? %))
                         (:gym/timeout-ms (gym/gym "wood"))
                         {:event/kind :go :go/goals [:wood]})]
    (out "==" label "canopy tree on a 2-high bank at [11 7]")
    (out " done?" (plan/done? w)
         "failed?" (plan/failed? w)
         "reason" (:plan/reason w)
         "waiting" (:plan/waiting w)
         "logs" (inventory/logs-held w)
         "violations" (:sim/violations sim))
    (out " pos" (:player/pos w) "last" (:plan/last w))
    (out " intents" (count (:plan/intents w)))
    (doseq [i (take 8 (reverse (memory/intentions w)))]
      (out "  "
           (select-keys i
                        [:intention/event :intention/kind :intention/target :intention/reason
                         :intention/at]))))
  (out ""))
(shutdown-agents)
