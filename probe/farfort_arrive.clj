(ns probe.farfort-arrive
  "Issue #338: the fortress search alone, from where a portal on a world's landing leads, in the
   set's captured Nether, with a kit: no overworld. One END line a run."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn kit
  "What the bot carries through its portal: pickaxes, stone for floors, food."
  []
  (let [it (fn [n c] {:item (recipe/item-id n) :count c})]
    {[:hotbar 0] (it :iron_pickaxe 1)
     [:hotbar 1] (it :iron_pickaxe 1)
     [:hotbar 2] (it :cobblestone 64)
     [:hotbar 3] (it :cooked_beef 32)
     [:hotbar 4] (it :iron_ingot 6)
     [:hotbar 5] (it :stick 8)}))

(defn -main
  "world-file seed minutes every: run and print AT lines every every simulated minutes and END."
  [file seed minutes every]
  (let [seed (Long/parseLong seed)
        every (Long/parseLong every)
        nf (sr/nether-file file)
        w (capture/read-world file)
        n (sr/read-nether nf)
        [lx lz ly] (:capture/landing w)
        {[ax ay az] :at} (sim/arrival (:decoded n) [lx ly lz])
        last-min (volatile! -1)
        stop? (fn [world]
                (let [m (quot (:time/now world) 60000)]
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
