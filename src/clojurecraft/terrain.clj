(ns clojurecraft.terrain
  "The blocks around the bot as values: chunk columns kept as they arrived, an overlay of block
   updates and the bot's own breaks over them, and the questions physics and the planner ask of
   both. Every change here is also handed to memory, so what was seen outlives the chunk. Pure
   functions of the world; game's packet handlers call them."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.memory :as memory]))

(set! *warn-on-reflection* true)

(defn block-at
  "State id at [x y z]: local overlay first, then the chunk; nil when unloaded."
  [world pos]
  (or (get (:world/blocks world) pos) (chunk/block-at (:world/chunks world) pos)))

(defn fluid-at
  "The fluid at pos, {:fluid/kind :fluid/level}, or nil: no fluid, or a block not loaded."
  [world pos]
  (blocks/fluid (block-at world pos)))

(defn fluid-kind
  "The kind of fluid at pos, :water or :lava, or nil."
  [world pos]
  (:fluid/kind (fluid-at world pos)))

(defn fluid-fn
  "Fluid oracle for physics: the fluid at a block, or nil. An unloaded block is no fluid."
  [world]
  (fn [x y z] (fluid-at world [x y z])))

(defn solid-fn
  "Solidity oracle for physics. Unloaded counts as solid so the bot never falls out of the world."
  [world]
  (fn [x y z] (let [id (block-at world [x y z])] (if (nil? id) true (blocks/solid? id)))))

(defn chunk-loaded?
  "Is the chunk column holding block or feet position [x y z] in :world/chunks?"
  [world [x _ z]]
  (contains? (:world/chunks world)
             [(bit-shift-right (long (math/floor x)) 4) (bit-shift-right (long (math/floor z)) 4)]))

(def top-y (+ chunk/min-y (* 16 chunk/section-count) -1)) ; the highest block y of an overworld column

(defn under-sky?
  "Is nothing over pos up to the top of the world but air, plants or leaves: the open air a climb
   out of the ground ends in? An unloaded column is not."
  [world [x y z :as pos]]
  (and (chunk-loaded? world pos)
       (not-any? #(when-let [id (block-at world [x % z])]
                    (and (blocks/solid? id) (not (blocks/leaves? id))))
                 (range (inc y) (inc top-y)))))

(defn chunk-key
  "[cx cz] from a packed chunk position (x in the low 32 bits, z in the high 32)."
  [v]
  [(long (unchecked-int v)) (long (unchecked-int (bit-shift-right v 32)))])

(defn in-chunk?
  "Is block [x y z] inside chunk column key [cx cz]?"
  [key [bx _ bz]]
  (= key [(bit-shift-right bx 4) (bit-shift-right bz 4)]))

(defn set-block
  "A block is known to be id now, on the server's word (a block update): overlay the chunk,
   keep the sighting, and confirm the cell if the bot's own dig had guessed it (predict)."
  [world pos id]
  (-> world
      (assoc-in [:world/blocks pos] id)
      (cond-> (contains? (:world/unconfirmed world) pos) (update :world/unconfirmed dissoc pos))
      (memory/observe pos id)))

(def unconfirmed-ms 3000)            ; a guess the server has not answered for this long is dropped

(defn predict
  "The bot's own guess that pos is id now (its dig's FINISH, before the server answers):
   the overlay and the sighting as set-block, and the cell in :world/unconfirmed, with the
   time, until the server's block-update there. A loaded server breaks the block after its
   own clock, seconds after it acks the FINISH (vanilla's delayed destroy); what is built on
   the guess meanwhile is refused."
  [world pos id]
  (-> world
      (set-block pos id)
      (assoc-in [:world/unconfirmed pos] (:time/now world))))

(defn forget-stale
  "Drop every guess (predict) the server has not answered for unconfirmed-ms by now: the
   overlay entry goes, so the chunk's block is in view again. Race 38477665442 bot Clj_r19
   dug the stone under its next stair, the server acked and never broke it, the bot's
   physics fell through the guessed air while the server put it back on the stone every
   tick, and its next dig waited to be still for the rest of the run."
  [world now]
  (let [stale (for [[pos at] (:world/unconfirmed world) :when (> (- now at) unconfirmed-ms)] pos)]
    (cond (empty? stale) world
          :else
          (-> world
              (update :world/blocks #(apply dissoc % stale))
              (update :world/unconfirmed #(apply dissoc % stale))))))

(defn confirmed?
  "Has the server said what is at pos: not a cell the bot's own dig emptied and the server has
   not answered for (predict)?"
  [world pos]
  (not (contains? (:world/unconfirmed world) pos)))

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
            world
            blocks)))

(defn superseded
  "The block overlay m without the blocks in column-key's column [cx cz], which a fresh one supersedes:
   m itself, the same object, when it holds none there, so whatever is remembered by the
   overlay's identity (memo/by) stays remembered while chunks arrive that it never touched."
  [m column-key]
  (let [gone (into [] (comp (map key) (filter #(in-chunk? column-key %))) m)]
    (cond (seq gone) (apply dissoc m gone)
          :else (or m {}))))

(defn put-column
  "Column key [cx cz] is now column: replace it, drop overlay blocks it supersedes, and remember
   what it holds."
  [world key column]
  (-> world
      (assoc-in [:world/chunks key] column)
      (update :world/blocks superseded key)
      (cond-> (seq (:world/unconfirmed world))
              (update :world/unconfirmed #(into {} (remove (fn [[pos _]] (in-chunk? key pos))) %)))
      (memory/remember-column key column)
      (memory/remember-pools key column)))
