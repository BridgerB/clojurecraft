(ns clojurecraft.retrace-r67
  "A probe script: the recording folded at its own commit, and at each of a list of times the
   retrace target as this commit picks it beside the one the retrace fix picks (fixed-retrace,
   the fix's two changes copied in as pure functions). Runs on a runner (bb probe), never the Mac.

     clojure -M:dev -m clojurecraft.retrace-r67 <recording> <from-ms> <to-ms> <every-ms>"
  (:require [clojurecraft.avoided :as avoided]
            [clojurecraft.fortress-exit :as fexit]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.physics :as physics]
            [clojurecraft.record :as record]))

(defn oldest-near-index
  "The fix's nearest-trail-index: the index of the oldest cell within a block of the nearest."
  [feet trail]
  (when (seq trail)
    (let [indexed (map-indexed vector trail)
          d (fn [[_ c]] (physics/distance feet c))
          best (d (apply min-key d indexed))]
      (first (last (filter #(<= (d %) (+ best 1.0)) indexed))))))

(defn fixed-retrace
  "The fix's fortress-retrace-target over world: each trail cell at its oldest visit."
  [world]
  (let [feet (intent/feet world)
        [_ fy _] feet
        trail (reverse (distinct (reverse (pcover/trail-cells world))))]
    (when (seq trail)
      (let [idx (oldest-near-index feet trail)
            remaining (when idx (drop (inc idx) trail))
            target-c (some #(when (and (>= (physics/distance feet %) 2.0)
                                       (not (contains? (avoided/all-of world :blacklist) %)))
                              %)
                           remaining)]
        (when target-c
          (let [[cx cy cz] target-c
                target-y (max (long fy) (long cy))]
            (cond (fexit/enclosed? world target-c) [cx target-y cz]
                  :else (or (fexit/side-bore-cell world [cx target-y cz]) [cx target-y cz]))))))))

(defn line
  "One time's line: feet, the intent, the old and the fixed retrace."
  [w t]
  (let [i (:plan/intent w)
        feet (intent/feet w)
        trail (vec (pcover/trail-cells w))]
    (str "AT " t
         " feet " feet
         " intent " (pr-str (select-keys i [:intent/kind :intent/target :intent/why]))
         " old " (pr-str (fexit/fortress-retrace-target w))
         " fixed " (pr-str (fixed-retrace w))
         " trail-n " (count trail)
         " near " (pr-str (filterv #(<= (physics/distance feet %) 3.0) trail)))))

(defn -main
  "Fold the recording once, printing a line at each time from from-ms to to-ms every every-ms."
  [path from-ms to-ms every-ms]
  (let [ts (range (parse-long from-ms) (inc (parse-long to-ms)) (parse-long every-ms))]
    (record/reduce-entries (fn [[w ts] e]
                             (cond (empty? ts) (reduced [w ts])
                                   (not (:event/kind e)) [w ts]
                                   (> (:time/now w 0) (first ts))
                                   (do (println (line w (first ts))) [w (rest ts)])
                                   :else [(assoc (main/step w e) :bot/effects []) ts]))
                           [(game/init {:host "replay" :port 0 :name "Clj_why"}) ts]
                           path)
    (shutdown-agents)))
