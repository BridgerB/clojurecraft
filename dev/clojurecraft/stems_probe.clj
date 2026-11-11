(ns clojurecraft.stems-probe
  "Did a bot pass stems it could have cut: the recording folded with this commit's code, and every
   every-ms of the bot's clock one line of its feet, the wood it held (sticks, planks, logs and
   stems), its pickaxes, and the remembered log or stem sightings nearest it (within 16, 32, 64).
   A probe script: runs on a runner.

     clojure -M:dev -m clojurecraft.stems-probe <recording> [every-ms]"
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.record :as record]))

(defn line
  "One sample of world w: [t feet wood pickaxes nearest-stem distance counts-within]."
  [w]
  (let [held (recipe/counts (:player/inventory w))
        sum (fn [re] (reduce + 0 (keep (fn [[k n]] (when (re-find re (name k)) n)) held)))
        feet (intent/feet w)
        logs (memory/positions-now w blocks/log-states)
        ds (sort (map #(physics/distance feet %) logs))
        near (first (sort-by #(physics/distance feet %) logs))]
    (str (:time/now w)
         " feet " feet
         " dim " (:player/dimension w)
         " sticks " (get held :stick 0)
         " planks " (sum #"_planks$")
         " logs " (sum #"(_log|_stem|_hyphae)$")
         " table " (get held :crafting_table 0)
         " picks " (sum #"_pickaxe$")
         " stems-known " (count logs)
         " nearest " near
         " d "
         (some-> (first ds)
                 math/round)
         " within16 " (count (take-while #(<= % 16) ds))
         " within32 " (count (take-while #(<= % 32) ds))
         " within64 " (count (take-while #(<= % 64) ds))
         " intent "
         (pr-str (select-keys (:plan/intent w) [:intent/kind :intent/for :intent/target])))))

(defn -main
  "Fold the recording and print a line every every-ms."
  [path & [every]]
  (let [every (parse-long (or every "30000"))]
    (record/reduce-entries (fn [[w next-at] e]
                             (if-not (:event/kind e)
                               [w next-at]
                               (let [w (assoc (main/step w e) :bot/effects [])
                                     now (:time/now w 0)]
                                 (if (and (:player/pos w) (>= now next-at))
                                   (do (println (line w)) [w (+ now every)])
                                   [w next-at]))))
                           [(game/init {:host "replay" :port 0 :name "Clj_stems"}) 0]
                           path))
  (shutdown-agents))
