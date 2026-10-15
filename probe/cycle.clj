(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.terrain :as terrain]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/target :intent/to-y :intent/dir :intent/status
                :intent/reason]))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world sim] (simrace/moment snap w0)]
    (out "==" p "go-at" (:plan/go-at world) "boxed-at" (count (:plan/boxed-at world #{})))
    (loop [world world
           sim sim
           k 0
           prev nil
           n 0]
      (when (and (< k 480) (< n 40) (not (plan/failed? world)) (not (plan/done? world)))
        (let [{world :world sim :sim}
              (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
              i (:plan/intent world)
              id (:intent/id i)]
          (if (= id prev)
            (recur world sim (inc k) prev n)
            (let [feet (intent/feet world)]
              (out " t" (:time/now world) "feet" feet "now" (pr-str (brief i)))
              (out "    deep?" (scoop/deep? world)
                   "sky?" (terrain/under-sky? world feet)
                   "sources" (count (scoop/sources world))
                   "pools" (take 2 (scoop/pools-near world))
                   "hunt" (brief (scoop/first-walk world :water-hunt (scoop/hunt-targets world)))
                   "to-bank" (brief (scoop/to-bank world))
                   "climb" (brief (scoop/climb world))
                   "pit-climb" (brief (scoop/pit-climb world)))
              (recur world sim (inc k) id (inc n)))))))))
(shutdown-agents)
