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
(def tree {:x 13 :z 3 :h 3 :id 137 :mound 2 :canopy? false :branch nil})
(def blocks
  (merge (into {} (for [mx [12 13] mz [2 3] y [64 65]] [[mx y mz] 1]))
         (into {} (for [y [66 67 68]] [[13 y 3] 137]))
         {[13 69 3] 252}))
(doseq [[label sx sz] [["spawn-0-0" 0 0] ["spawn-1-1" 1 1]]]
  (let [sim0 (sim/init {:column (world/column-bytes blocks) :spawn [(+ sx 0.5) 64.0 (+ sz 0.5)]})
        [w sim] (sim/run step
                         (game/init fx/opts)
                         sim0
                         #(or (plan/done? %) (plan/failed? %))
                         (:gym/timeout-ms (gym/gym "wood"))
                         {:event/kind :go :go/goals [:wood]})]
    (out "==" label "tree at [13 3] on a 2-high bank")
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
