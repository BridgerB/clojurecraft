(ns clojurecraft.cells
  "The blocks round a bot's feet at moments of its recording (a probe script, run on a runner):
   the recording folded to each at-ms, then the block names of the 3x3 cells at the feet's height
   and the two over it, and what the world says of the bot (position, held, health).

     clojure -M:dev -m clojurecraft.cells <recording .edn[.zst|.gz]> <at-ms> [<at-ms> ...]"
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.record :as record]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(defn at
  "The world of the recording at path folded up to at-ms."
  [path at-ms]
  (record/reduce-entries (fn [w e]
                           (cond (not (:event/kind e)) w
                                 (> (:time/now w 0) at-ms) (reduced w)
                                 :else (assoc (main/step w e) :bot/effects [])))
                         (game/init {:host "replay" :port 0 :name "Clj_cells"})
                         path))

(defn cells
  "{[dx dy dz] block-name} for the cells within one of the feet's column on the plane, from the
   feet's cell to two over it, as world w holds them."
  [w]
  (let [[px py pz] (:player/pos w)
        [x y z] (mapv #(long (Math/floor (double %))) [px py pz])]
    (into (sorted-map)
          (for [dy [-1 0 1 2]
                dx [-1 0 1]
                dz [-1 0 1]]
            [[dx dy dz]
             (let [id (terrain/block-at w [(+ x dx) (+ y dy) (+ z dz)])]
               (if id (str (some-> (blocks/row id)
                                   first
                                   name))
                   "unloaded"))]))))

(defn -main
  "Print, for each at-ms, the bot's place and the cells round its feet."
  [path & at-mss]
  (doseq [ms (map parse-long at-mss)]
    (let [w (at path ms)]
      (println "AT" ms "pos" (:player/pos w) "health" (:player/health w)
               "dim" (:player/dimension w))
      (doseq [dy [2 1 0 -1]]
        (println " dy" dy
                 (vec (for [dz [-1 0 1]]
                        (vec (for [dx [-1 0 1]] (get (cells w) [dx dy dz]))))))))))
