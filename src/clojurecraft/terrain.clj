(ns clojurecraft.terrain
  "The blocks around the bot as values: chunk columns kept as they arrived, an overlay of block
   updates and the bot's own breaks over them, and the questions physics and the planner ask of
   both. Every change here is also handed to memory, so what was seen outlives the chunk. Pure
   functions of the world; game's packet handlers call them."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.memory :as memory]))

(defn block-at
  "State id at [x y z]: local overlay first, then the chunk; nil when unloaded."
  [world pos]
  (or (get (:world/blocks world) pos) (chunk/block-at (:world/chunks world) pos)))

(defn solid-fn
  "Solidity oracle for physics. Unloaded counts as solid so the bot never falls out of the world."
  [world]
  (fn [x y z]
    (let [id (block-at world [x y z])]
      (if (nil? id) true (blocks/solid? id)))))

(defn chunk-loaded?
  "Is the chunk column holding block or feet position [x y z] in :world/chunks?"
  [world [x _ z]]
  (contains? (:world/chunks world)
             [(bit-shift-right (long (Math/floor x)) 4) (bit-shift-right (long (Math/floor z)) 4)]))

(defn chunk-key
  "[cx cz] from a packed chunk position (x in the low 32 bits, z in the high 32)."
  [v] [(long (unchecked-int v)) (long (unchecked-int (bit-shift-right v 32)))])

(defn in-chunk? "Is block [x y z] inside chunk column key [cx cz]?" [key [bx _ bz]] (= key [(bit-shift-right bx 4) (bit-shift-right bz 4)]))

(defn set-block
  "A block is known to be id now: overlay the chunk and keep the sighting."
  [world pos id]
  (-> world (assoc-in [:world/blocks pos] id) (memory/observe pos id)))

(defn section-update
  "Apply a section-blocks-update: unpack the section coords and each packed (state, local
   x y z) and set every block."
  [world {:keys [section blocks]}]
  (let [sx (bit-shift-right section 42)
        sz (bit-shift-right (bit-shift-left section 22) 42)
        sy (bit-shift-right (bit-shift-left section 44) 44)]
    (reduce (fn [world v]
              (let [id (unsigned-bit-shift-right v 12)
                    lx (bit-and (bit-shift-right v 8) 15)
                    lz (bit-and (bit-shift-right v 4) 15)
                    ly (bit-and v 15)]
                (set-block world [(+ (* 16 sx) lx) (+ (* 16 sy) ly) (+ (* 16 sz) lz)] id)))
            world blocks)))

(defn put-column
  "Column key [cx cz] is now column: replace it, drop overlay blocks it supersedes, and remember
   what it holds."
  [world key column]
  (-> world
      (assoc-in [:world/chunks key] column)
      (update :world/blocks (fn [m] (into {} (remove (fn [[pos _]] (in-chunk? key pos)) m))))
      (memory/remember-column key column)))
