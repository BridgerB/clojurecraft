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
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.record :as record]
            [clojurecraft.terrain :as terrain]))

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



(defn dig-starts
  "How many block digs the effects start (player-action :start-destroy)."
  [effects]
  (count (filter #(and (= :send (:effect/kind %))
                       (= :player-action (get-in % [:effect/packet :packet/name]))
                       (= 0 (get-in % [:effect/packet :status])))
                 effects)))

(defn dig-key
  "The intent a dig is counted against: [kind why child-kind] of the running intent."
  [w]
  (let [i (:plan/intent w)]
    [(:intent/kind i) (intent/why-of i) (get-in i [:intent/child :intent/kind])]))

(defn digs
  "Fold the recording and print the digs started, by [kind why child-kind], most first."
  [path]
  (let [[counts _] (record/reduce-entries (fn [[counts w] e]
                                            (if-not (:event/kind e)
                                              [counts w]
                                              (let [w' (main/step w e)
                                                    n (dig-starts (:bot/effects w'))
                                                    k (dig-key (if (:plan/intent w') w' w))]
                                                [(cond-> counts (pos? n) (update k (fnil + 0) n))
                                                 (assoc w' :bot/effects [])])))
                                          [{} (game/init {:host "replay" :port 0 :name "Clj_digs"})]
                                          path)
        total (reduce + 0 (vals counts))]
    (println "DIGS" path "total" total)
    (doseq [[k n] (take 20 (sort-by (comp - val) counts))] (println " " n k))))



(defn cage-line
  "What the hunt saw when it started a cage-break in world w: feet, the intent, why it judged itself
   caged or wandering, and the six cells round the feet with which the bot placed itself."
  [w]
  (let [feet (intent/feet w)
        i (:plan/intent w)
        around (for [d [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1] [0 2 0] [0 -1 0] [1 1 0] [-1 1 0] [0 1 1]
                        [0 1 -1]]
                     :let [c (mapv + feet d)]]
                 [d
                  (some-> (terrain/block-at w c)
                          blocks/name-of
                          name) (when (memory/done-at? w :place c) :placed)])]
    (str "CAGE " (:time/now w)
         " feet " feet
         " intent "
         (pr-str
          (select-keys i [:intent/kind :intent/dir :intent/to-y :intent/target :intent/length]))
         " caged? " (pmove/travel-caged? w)
         " wandering? " (pmove/wandering? w)
         " loitering? " (pmove/loitering? w)
         " travel " (pr-str (select-keys (pmove/travel w) [:intent/kind :intent/target]))
         " forest " (psense/nearest-forest w)
         " in-forest? " (psense/in-forest? w)
         " around " (pr-str around)
         " placed-near "
         (count (filter
                 #(memory/done-at? w :place %)
                 (for [dx (range -2 3) dy (range -1 3) dz (range -2 3)] (mapv + feet [dx dy dz]))))
         " last "
         (pr-str (map (juxt :intention/kind :intention/why :intention/event)
                      (take-last 6 (memory/intentions w)))))))

(defn cages
  "Fold the recording and print a CAGE line at every cage-break intent's start."
  [path]
  (record/reduce-entries (fn [w e]
                           (cond (not (:event/kind e)) w
                                 :else
                                 (let [w' (assoc (main/step w e) :bot/effects [])
                                       i (:plan/intent w')]
                                   (when (and i
                                              (= :hunt/cage-break (intent/why-of i))
                                              (not= (:intent/id i) (:intent/id (:plan/intent w))))
                                     (println (cage-line w')))
                                   w')))
                         (game/init {:host "replay" :port 0 :name "Clj_cage"})
                         path)
  nil)

(defn -main
  "Fold the recording and print a line every every-ms; with every \"digs\", the digs by intent."
  [path & [every]]
  (if-let [mode (get {"digs" digs "cages" cages} every)]
    (mode path)
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
                             path)))
  (shutdown-agents))
