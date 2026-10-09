(ns clojurecraft.memory
  "What the bot has seen, kept after the chunk is gone.

   Sightings are facts keyed by block position: {[x y z] {:block/state id :block/seen-at ms}}.
   A later observation supersedes an earlier one; a position is never forgotten, so a block
   observed as air stays on record as air. Only block kinds that `watched?` accepts are
   recorded (logs today; ores next), so the store stays small. Storage is a plain map; a
   Datalog-backed store can replace it inside this namespace without touching callers."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.physics :as physics]))

(defn watched? [id] (blocks/log? id))

(defn remember-column
  "Record every watched block in a freshly loaded chunk column."
  [world key column]
  (let [now (:time/now world)]
    (reduce (fn [w [x y z id]]
              (assoc-in w [:world/sightings [x y z]] {:block/state id :block/seen-at now}))
            world
            (chunk/find-blocks {key column} watched?))))

(defn observe
  "A single block changed: keep the fact if it is watched or was already on record."
  [world pos id]
  (if (or (watched? id) (contains? (:world/sightings world) pos))
    (assoc-in world [:world/sightings pos] {:block/state id :block/seen-at (:time/now world)})
    world))

(defn remembered [world pos] (get-in world [:world/sightings pos :block/state]))

(defn log-at? [world pos] (boolean (some-> (remembered world pos) blocks/log?)))

(defn trunk-bottom?
  "A remembered log with no remembered log below it: where a trunk meets the ground."
  [world [x y z]]
  (and (log-at? world [x y z]) (not (log-at? world [x (dec y) z]))))

(defn- centre [[x y z]] [(+ x 0.5) (+ y 0.5) (+ z 0.5)])

(defn nearest-log
  "Nearest trunk-bottom log on record within radius of eye, skipping blacklisted positions and
   logs more than 12 blocks above or below the eye. nil when none."
  [world eye radius blacklist]
  (let [ey (second eye)]
    (->> (:world/sightings world)
         keys
         (remove blacklist)
         (filter #(trunk-bottom? world %))
         (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
         (map (fn [p] [(physics/distance eye (centre p)) p]))
         (filter (fn [[d _]] (<= d radius)))
         (sort-by first)
         first
         second)))
