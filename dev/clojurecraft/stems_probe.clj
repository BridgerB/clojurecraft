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

(defn -main
  "Fold the recording and print a line every every-ms; with every \"digs\", the digs by intent."
  [path & [every]]
  (cond (= "digs" every) (digs path)
        :else
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
