(ns probe.farfort-arrive
  "Issue #338: the fortress search alone, from where a portal on a world's landing leads, in the
   set's captured Nether, with a kit: no overworld. One END line a run."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn kit
  "What a whole run carries through its portal (measured, races R2/5 and R2/4): one stone pickaxe,
   a few planks and sticks and a crafting table, stone for floors, a bucket, no food."
  []
  (let [it (fn [n c] {:item (recipe/item-id n) :count c})]
    (merge {[:hotbar 0] (it :stone_pickaxe 1)
            [:hotbar 1] (it :stone_sword 1)
            [:hotbar 2] (it :stick 2)
            [:hotbar 3] (it :dark_oak_planks 3)
            [:hotbar 4] (it :crafting_table 1)
            [:hotbar 5] (it :bucket 1)
            [:hotbar 6] (it :flint_and_steel 1)}
           (into {} (for [slot (range 9 16)] [slot (it :cobblestone 64)]))
           (into {} (for [slot (range 16 21)] [slot (it :cobbled_deepslate 64)])))))

(defn glyph
  "One character for the column [x z] at the height of feet y: o standable here, ^ a block up,
   v a block down, L lava in the column, # solid at the feet, . air with no floor within 3."
  [world [x y z]]
  (let [at #(terrain/block-at world %)
        lava? (fn [c] (contains? blocks/lava-states (at c)))]
    (cond (nil? (at [x y z])) "?"
          (some lava? [[x y z] [x (dec y) z] [x (- y 2) z]]) "L"
          (path/standable? world [x y z]) "o"
          (path/standable? world [x (inc y) z]) "^"
          (path/standable? world [x (dec y) z]) "v"
          (blocks/solid? (at [x y z])) "#"
          :else ".")))

(defn look
  "Print what the bot at world sees: its target, the routes A* finds toward it, a map of radius 30."
  [world minute]
  (let [feet (path/feet-cell (:player/pos world))
        target (:intent/target (:plan/intent world))
        goal {:goal/kind :near :goal/pos target :goal/range 8}
        d (fn [c] (long (path/octile c target)))]
    (println "LOOK" minute feet "target" target "dist" (d feet))
    (doseq [nodes [6000 30000]]
      (let [r (path/plan world feet goal {:path/max-nodes nodes})
            ws (:path/waypoints r)]
        (println "ASTAR"
                 minute
                 nodes
                 (:path/status r)
                 "waypoints" (count ws)
                 "end" (last ws)
                 "dist"
                 (some-> (last ws)
                         d))))
    (let [route (set (:path/waypoints (path/plan world feet goal {:path/max-nodes 6000})))
          [fx fy fz] feet]
      (doseq [z (range (- fz 30) (+ fz 31))]
        (println "MAP"
                 minute
                 (str/join (for [x (range (- fx 30) (+ fx 31))]
                             (cond (= [x fy z] feet) "@"
                                   (some #(contains? route [x % z])
                                         [(dec fy) fy (inc fy) (+ fy 2) (- fy 2)])
                                   "*"
                                   :else (glyph world [x fy z])))))))))

(defn -main
  "world-file seed minutes every [looks]: run and print AT lines every every simulated minutes, a
   look at the minutes of looks (comma separated), and END."
  [file seed minutes every & [looks band]]
  (when (and band (not= "-" band))
    (alter-var-root #'fortress/explore-band
                    (constantly (mapv #(Long/parseLong %) (str/split band #",")))))
  (let [looks (set (map #(Long/parseLong %) (remove empty? (str/split (or looks "") #","))))
        seed (Long/parseLong seed)
        every (Long/parseLong every)
        nf (sr/nether-file file)
        w (capture/read-world file)
        n (sr/read-nether nf)
        [lx lz ly] (:capture/landing w)
        {[ax ay az] :at} (sim/arrival (:decoded n) [lx ly lz])
        last-min (volatile! -1)
        looked (volatile! #{})
        acts (atom {})
        steps (volatile! 0)
        last-taken (volatile! [nil 0])
        stop? (fn [world]
                (let [m (quot (:time/now world) 60000)
                      i (:plan/intent world)
                      op (:intent/op i)
                      k [(:intent/kind i) (:build/op op) (:build/step op)
                         (:intent/kind (:intent/child i))]
                      tk [(:intent/id i) (:intent/taken i 0)]]
                  (swap! acts update k (fnil inc 0))
                  (when (and (= (first tk) (first @last-taken))
                             (> (second tk) (second @last-taken)))
                    (vswap! steps inc))
                  (vreset! last-taken tk)
                  (when (and (contains? looks m) (not (contains? @looked m)))
                    (vswap! looked conj m)
                    (look world m))
                  (when (and (not= m @last-min) (zero? (mod m every)))
                    (vreset! last-min m)
                    (println "AT"
                             m
                             (mapv long (:player/pos world))
                             (select-keys world [:player/health :player/food])
                             (some-> (:fortress/search world)
                                     (select-keys [:search/leg :search/heading :search/tunnels]))))
                  (or (plan/done? world) (plan/failed? world))))
        r (sim/run+ sr/step
                    (game/init {:host "sim" :port 0 :name "Clj_sim"})
                    (sim/init {:columns (:columns n)
                               :decoded (:decoded n)
                               :view-distance (:view-distance n)
                               :spawn [ax ay az]
                               :dimension dimension/nether
                               :inventory (kit)
                               :seed seed
                               :tick-seed seed
                               :food 20
                               :difficulty sr/difficulty
                               :day-time (sr/day-time-of seed)})
                    stop?
                    (* 60000 (Long/parseLong minutes))
                    {:event/kind :go :go/goals [:fortress]})
        world (:world r)]
    (println "STEPS" @steps "TICKS" (reduce + (vals @acts)))
    (doseq [[k v] (take 25 (sort-by (comp - val) @acts))] (println "ACT" v (pr-str k)))
    (println "END"
             file
             seed
             (quot (:time/now world) 60000)
             "min"
             (:plan/status world)
             (:plan/reason world)
             "pos"
             (mapv long (:player/pos world))
             "from"
             [(long ax) (long ay) (long az)]
             "deaths"
             (:stats/deaths world 0)
             "arrived"
             (boolean (some #(fortress/arrived? world %) (fortress/bricks world)))
             (pr-str (select-keys (:plan/last world) [:intent/kind :intent/reason])))
    (shutdown-agents)))
