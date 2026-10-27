(ns clojurecraft.suffocate
  "Reaction when the bot's eye cell holds a solid block: clearing it before any other intent,
   either by stepping to an adjacent free neighbour cell when one is clear and supported, or
   by digging the head cell, repeating if falling blocks refill it. Never digs below the feet
   while buried."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]))

(set! *warn-on-reflection* true)

(def directions "The four horizontal unit step offsets." [[1 0] [-1 0] [0 1] [0 -1]])

(def step-give-up-ticks
  "Maximum ticks to try walking toward a free neighbour before digging the head cell instead."
  8)

(defn free-neighbour?
  "Is the cell at [nx fy nz] a free footing to step onto: both feet and head cells are clear
   (not solid and not fluid), floor under feet is solid, and no falling blocks above the head?"
  [world [nx fy nz]]
  (let [feet-b (terrain/block-at world [nx fy nz])
        head-b (terrain/block-at world [nx (inc fy) nz])
        floor-b (terrain/block-at world [nx (dec fy) nz])]
    (boolean (and feet-b
                  (not (blocks/solid? feet-b))
                  (not (terrain/fluid-at world [nx fy nz]))
                  head-b
                  (not (blocks/solid? head-b))
                  (not (terrain/fluid-at world [nx (inc fy) nz]))
                  floor-b
                  (blocks/solid? floor-b)
                  (not (intent/falling-in-column? world [nx (inc fy) nz]))))))

(defn free-neighbours
  "The free horizontal neighbour footings [x y z] around the player's feet, nearest pos first."
  [world pos]
  (let [[fx fy fz] (intent/feet world)]
    (->> directions
         (map (fn [[dx dz]] [(+ fx dx) fy (+ fz dz)]))
         (filter #(free-neighbour? world %))
         (sort-by #(physics/distance pos (physics/centre %))))))

(defmethod intent/run :escape-suffocation
  [world {:intent/keys [child step-ticks target-cell] :or {step-ticks 0}} event]
  (let [pos (:player/pos world)
        head-c (physics/eye-cell pos)
        buried? (trap/suffocating? world)]
    (cond (not buried?)
          (-> world
              (assoc :player/controls {})
              intent/done)
          child
          (cond (intent/done? child) (intent/dissoc-intent world :intent/child)
                (intent/failed? child)
                (-> world
                    (assoc :player/controls {})
                    (intent/fail (:intent/reason child)))
                :else (intent/run-child world event))
          (and (< step-ticks step-give-up-ticks) (seq (free-neighbours world pos)))
          (let [target (or target-cell (first (free-neighbours world pos)))]
            (-> world
                (assoc :player/controls (intent/toward world (physics/centre target) 0.0))
                (intent/assoc-intent :intent/step-ticks (inc step-ticks)
                                     :intent/target-cell target)))
          :else
          (let [child (intent/child-dig head-c)]
            (-> world
                (assoc :player/controls {})
                (intent/assoc-intent :intent/child child)
                (intent/run-child event))))))
