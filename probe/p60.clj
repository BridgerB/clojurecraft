(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.scoop :as scoop]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn ask
  [label f]
  (try (out label (pr-str (f)))
       (catch Exception e (out label "THREW" (.getName (class e)) (ex-message e)))))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/stage :intent/target :intent/dir
                :intent/to-y :intent/status :intent/reason :intent/op]))
(defn glyph
  [id]
  (let [n (when id (blocks/name-of id))]
    (cond (nil? id) \space
          (blocks/air? id) \.
          (contains? blocks/water-states id) \~
          (#{:ice :packed_ice :blue_ice :frosted_ice} n) \I
          (= :powder_snow n) \p
          (#{:snow :snow_block} n) \n
          (#{:stone :andesite :diorite :granite :deepslate} n) \S
          (#{:dirt :grass_block :coarse_dirt} n) \d
          (= :gravel n) \g
          (= :cobblestone n) \c
          :else \#)))
(defn mapof
  [world [fx fy fz] r]
  (doseq [dy (range -3 3)]
    (out "--- y" (+ fy dy))
    (doseq [dz (range (- r) (inc r))]
      (out (str/join (for [dx (range (- r) (inc r))]
                       (if (and (zero? dx) (zero? dz) (zero? dy))
                         \@
                         (glyph (terrain/block-at world [(+ fx dx) (+ fy dy) (+ fz dz)])))))))))
(defn look
  [world]
  (ask " feet" #(intent/feet world))
  (ask " intent" #(brief (:plan/intent world)))
  (ask " blacklist" #(count (:plan/blacklist world)))
  (ask " go-at" #(:plan/go-at world))
  (ask " boxed-at" #(:plan/boxed-at world))
  (ask " pools" #(vals (memory/pools world :water)))
  (ask " sources" #(vec (scoop/sources world)))
  (ask " pools-near" #(vec (scoop/pools-near world)))
  (ask " pools-near far" #(vec (scoop/pools-near world scoop/far-pool-radius)))
  (ask " hunt-targets" #(vec (scoop/hunt-targets world)))
  (ask " to-bank" #(scoop/to-bank world))
  (ask " scoop-next" #(scoop/scoop-next world)))
(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (simrace/world-for! snap)
          [world sim] (simrace/moment snap w0)
          r (Long/parseLong (or (System/getenv "MAPR") "20"))
          steps (Long/parseLong (or (System/getenv "STEPS") "960"))]
      (out "==" p (pr-str (:snapshot/signature snap)) "now" (:time/now world))
      (look world)
      (when (pos? r) (mapof world (intent/feet world) r))
      (loop [world world
             sim sim
             k 0
             prev nil]
        (if (and (< k steps) (not (plan/failed? world)) (not (plan/done? world)))
          (let [{world :world sim :sim}
                (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
                i (:plan/intent world)
                cur [(:intent/kind i) (:intent/for i) (:intent/target i) (:intent/reason i)
                     (:plan/waiting world)]]
            (when (not= cur prev) (out "  " (:time/now world) (intent/feet world) (pr-str cur)))
            (recur world sim (inc k) cur))
          (do (out "  end"
                   (:plan/status world)
                   (:plan/reason world)
                   (pr-str (brief (:plan/last world))))
              (look world)))))
    (catch Exception e (out "==" p "THREW" (ex-message e)))))
(shutdown-agents)
