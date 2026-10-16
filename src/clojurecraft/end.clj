(ns clojurecraft.end
  "The End and the way there as the server makes them, for the model (sim.clj): the end portal
   frame's states and the ring of twelve that lights the portal (EndPortalFrameBlock), the
   obsidian platform a player arrives on (EndPlatformFeature), the ten spikes the crystals stand
   on (SpikeFeature), and an eye of ender's flight toward a stronghold (EnderEyeItem, EyeOfEnder).
   Values and pure functions over a cell lookup; the model holds the state and the clock.

   Not modelled: the frames' facing (any frame with an eye counts toward a ring), the spikes'
   seeded shuffle (spike i is the i-th of the table), the iron cages round two of them, the
   exit portal, the dragon's flight and breath."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- the portal's frames

(def frame-base (apply min (blocks/states-of :end_portal_frame))) ; eye=true facing=north: the lowest state
(def facings [:north :south :west :east]) ; the facing property's values, in state order
(def portal (blocks/default-state :end_portal))

(defn frame-state
  "The end portal frame's state with or without an eye, facing a way (the properties eye then
   facing, eye's true first, as the block's state table orders them)."
  [eye? facing]
  (+ frame-base (if eye? 0 4) (.indexOf ^java.util.List facings facing)))

(defn frame?
  "Is the state an end portal frame?"
  [id]
  (contains? (blocks/states-of :end_portal_frame) id))

(defn eyed?
  "Is the state an end portal frame holding an eye?"
  [id]
  (and (frame? id) (< (- id frame-base) 4)))

(defn with-eye "A frame state without an eye, the same frame with one." [id] (- id 4))

(def ring-offsets
  "The twelve frames round a 3×3 interior, as offsets from its middle on the plane."
  (vec (concat (for [d [-1 0 1] s [-2 2]] [s d]) (for [d [-1 0 1] s [-2 2]] [d s]))))

(defn interior
  "The nine cells of the portal whose middle is [x y z]."
  [[x y z]]
  (for [dx [-1 0 1] dz [-1 0 1]] [(+ x dx) y (+ z dz)]))

(defn lit
  "The nine cells the portal fills when an eye goes into the frame at cell: the interior of a ring
   of twelve frames, every one with its eye, round a middle the cell borders; nil when no ring
   round it is whole. at is a cell → state."
  [at [x y z]]
  (some (fn [[ox oz]]
          (let [middle [(- x ox) y (- z oz)]
                [mx _ mz] middle]
            (when (every? (fn [[rx rz]] (eyed? (at [(+ mx rx) y (+ mz rz)]))) ring-offsets)
              (vec (interior middle)))))
        ring-offsets))

;; ---------------------------------------------------------------- arriving

(def spawn [100.5 49.0 0.5])          ; where a player arrives in the End: on the platform (ServerLevel.END_SPAWN_POINT)

(defn platform
  "{cell state} of the obsidian platform under a player arriving at spawn: five by five of
   obsidian under the feet and three layers of air over it (EndPlatformFeature.createEndPlatform)."
  []
  (let [[x y z] (mapv #(long (math/floor %)) spawn)]
    (into {}
          (for [dx (range -2 3)
                dz (range -2 3)
                dy (range -1 3)]
            [[(+ x dx) (+ y dy) (+ z dz)]
             (if (= dy -1) (blocks/default-state :obsidian) blocks/air)]))))

(def spike-ring 42.0)                 ; the spikes stand on a circle of this radius round the fountain

(def spikes
  "The ten obsidian spikes: [x z] middle, radius and height (SpikeFeature: on the circle, the
   i-th radius 2 + i/3 and height 76 + 3i; the game shuffles which spike gets which by the seed,
   the model does not)."
  (vec (for [i (range 10)
             :let [a (* 2.0 (+ (- math/PI) (* (/ math/PI 10) i)))]]
         {:spike/center [(long (math/floor (* spike-ring (math/cos a))))
                         (long (math/floor (* spike-ring (math/sin a))))]
          :spike/radius (+ 2 (quot i 3))
          :spike/height (+ 76 (* 3 i))})))

(defn crystal-at
  "Where a spike's crystal stands: on the bedrock over its top, in the middle of its cell."
  [{[x z] :spike/center h :spike/height}]
  [(+ x 0.5) (double (inc h)) (+ z 0.5)])

;; ---------------------------------------------------------------- an eye's flight

(def eye-reach 12.0)                  ; an eye flies at most this far on the plane toward the stronghold (EyeOfEnder.signalTo)
(def eye-rise 8.0)                    ; and this far up when the stronghold is farther than eye-reach
(def eye-flight-ticks 40)             ; game ticks it takes to get there (a model's pace)
(def eye-life-ticks 80)               ; game ticks before it drops or shatters (EyeOfEnder's life)
(def eye-survives 0.8)                ; the share that drop as an item rather than shatter

(defn eye-target
  "Where an eye thrown from point from flies toward the stronghold at [sx sy sz]: eye-reach along
   the line to it on the plane and eye-rise up when it is farther than that, else the stronghold
   itself (EyeOfEnder.signalTo)."
  [[fx fy fz] [sx sy sz]]
  (let [dx (- sx fx)
        dz (- sz fz)
        d (math/hypot dx dz)]
    (cond (<= d eye-reach) [(double sx) (double sy) (double sz)]
          :else [(+ fx (* eye-reach (/ dx d))) (+ fy eye-rise) (+ fz (* eye-reach (/ dz d)))])))

(defn eye-pos
  "The eye's position ticks into its flight from from to target: straight along the line, there
   after eye-flight-ticks, hovering there after."
  [from target ticks]
  (let [k (min 1.0 (/ (double ticks) eye-flight-ticks))]
    (mapv #(+ %1 (* k (- %2 %1))) from target)))
