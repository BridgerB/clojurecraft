(ns clojurecraft.stone
  "The :mine act of the goal table (clojurecraft.make runs it, as :gather runs clojurecraft.wood):
   get what a row's blocks drop. A mining row names its blocks as data (:goal/blocks, stone for
   cobblestone, coal or iron ore, gravel) and how deep it may cut looking for them
   (:goal/depth, none for stone). With the tool the blocks need in hand, walk to the nearest of
   them the bot can stand beside, dig it, pick the drop up; with none in sight, cut a staircase
   down, never below the row's depth. The :redig act gets a block's chancy drop from one held
   (gravel, for flint): one :redig intent places it, digs it and picks up what drops, as child
   intents, and the planner starts another until the drop is held. Requiring this namespace
   registers both acts and the :redig executor."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for an exposed target
(def search-height 12)                ; blocks above or below the eye searched
(def stair-depth 6)                   ; stairs cut when no target is in sight, before looking again
(def default-blocks [:stone])         ; a :mine row that names no blocks mines stone, as the first one did
(def max-candidates 3)                ; the nearest targets a route is planned to before none counts as in sight
(def tunnel-length 12)                ; level steps a tunnel at a row's depth cuts before looking again

(def states-of-row
  "row → every state id of the blocks it mines. Memoized: a row is a value."
  (memoize (fn [row] (into #{} (mapcat blocks/states-of) (:goal/blocks row default-blocks)))))

(def drops-of-row
  "row → the item ids its blocks can drop (every try of their loot tables: gravel's flint and
   gravel both), what a collect waits for one more of. Memoized like states-of-row."
  (memoize (fn [row]
             (set (for [b (:goal/blocks row default-blocks)
                        [item _] (dig/drop-tries (blocks/default-state b))]
                    (blocks/items item))))))

(defn tool-missing
  "The wait reason when the bot holds no tool the row's blocks drop for (:no-pickaxe for stone
   or ore), or nil when it can dig them: blocks that drop to anything need nothing."
  [world row]
  (let [s (first (states-of-row row))]
    (when (and (blocks/drops-need-tool? s) (nil? (dig/best-tool s (:player/inventory world) {})))
      (keyword (str "no-" (name (blocks/tool-of s)))))))

(defn beside
  "A cell a route could stand in to dig a block at pos from, or nil: one of its horizontal
   neighbours at its level or a block lower (the block at the head), or the cell under it with
   the head just below it (the block overhead). From each the drop comes to rest inside the
   pickup box. Dug from the surface above, the drop lies a block under the feet and is never
   picked up, so a block under the feet never counts. In a tunnel two high, only the walls at
   the feet counted before the head and the ceiling did: batch iron-a2 run 4 tunnelled over a
   hundred blocks at y 16 without one iron ore in sight."
  [world [x y z]]
  (first (filter #(path/standable? world %)
                 (concat
                  (for [dy [0 -1] [dx dz] [[1 0] [-1 0] [0 1] [0 -1]]] [(+ x dx) (+ y dy) (+ z dz)])
                  [[x (- y 2) z]]))))

(defn exposed?
  "Can a player stand beside the block at pos and dig it?"
  [world pos]
  (some? (beside world pos)))

(defn near-chunks
  "The loaded chunk columns within search-radius of the feet: the terrain a search reads."
  [world]
  (let [[x _ z] (:player/pos world)
        cx (bit-shift-right (long (math/floor x)) 4)
        cz (bit-shift-right (long (math/floor z)) 4)
        r (inc (quot search-radius 16))]
    (into {}
          (filter (fn [[[kx kz] _]] (and (<= (abs (- kx cx)) r) (<= (abs (- kz cz)) r)))
                  (:world/chunks world)))))

(defn exposed-of-states
  "The blocks of states within search-radius of the eye and search-height of its height that
   the bot can stand beside, not blacklisted, nearest first. Read from the loaded terrain
   (the live view), not memory: stone is most of the world and ore is in every chunk, and only
   what is exposed now is worth a route. The chunks are scanned as they arrived, so each
   candidate is asked again through the overlay of block updates and the bot's own breaks: a
   block just dug is air."
  [world states]
  (let [eye (game/eye world)
        ey (second eye)
        blacklist (:plan/blacklist world #{})
        lo (- ey search-height)
        hi (+ ey search-height)]
    (memory/nearest-first eye
                          search-radius
                          (->> (chunk/find-blocks (near-chunks world) #(contains? states %))
                               (map (fn [[x y z _]] [x y z]))
                               (filter (fn [[_ y _]] (<= lo y hi)))
                               (remove blacklist)
                               (filter #(contains? states (terrain/block-at world %)))
                               (filter #(exposed? world %))))))

(defn continue-mine
  "The next intent of the row's chain in flight, or nil: walk → dig → collect, tagged
   :intent/for the row's id so neither a walk for another purpose nor another row's chain is
   ever continued as this one's dig."
  [world row]
  (intent/chain-next (:plan/last world) (:goal/id row) (drops-of-row row) {}))

(defn stairs
  "The staircase to cut when nothing is in sight: stair-depth down from the feet, never below
   the row's :goal/depth; nil when the feet are already there."
  [world {:goal/keys [depth]}]
  (let [feet (long (math/floor (second (:player/pos world))))
        to (cond-> (- feet stair-depth) depth (max depth))]
    (when (< to feet) {:intent/kind :stairs-down :intent/to-y to})))

(defn outward
  "The heading that leads farthest from the landing (:plan/go-at), so a tunnel never doubles
   back toward where the bot came down; +x without a landing."
  [world]
  (let [[x _ z] (:player/pos world)]
    (if-let [home (:plan/go-at world)]
      (apply max-key
             (fn [[dx dz]] (physics/horizontal-distance [(+ x dx) 0 (+ z dz)] home))
             stairs/directions)
      (first stairs/directions))))

(defn mine-next
  "The next step of mining a row: continue its chain; else, holding the tool its blocks need,
   walk to the nearest exposed one a route reaches (of the max-candidates nearest; a walk that
   ends where the drop lands in reach, :intent/pickup?), or cut stairs down toward them, or at
   the row's depth a tunnel outward; wait for the tool. The live runs that waited at the depth
   with nothing in sight failed there; a tunnel's walls are where the ore shows."
  [world row]
  (or (continue-mine world row)
      (let [missing (tool-missing world row)
            walk-to
            (fn [t]
              {:intent/kind :walk :intent/target t :intent/for (:goal/id row) :intent/pickup? true})
            walk (when-not missing
                   (->> (exposed-of-states world (states-of-row row))
                        (take max-candidates)
                        (map walk-to)
                        (filter #(intent/routable? world %))
                        first))]
        (cond missing {:plan/wait missing}
              walk walk
              :else
              (or
               (stairs world row)
               {:intent/kind :tunnel :intent/dir (outward world) :intent/length tunnel-length})))))

(defmethod plan/act :mine [world row] (mine-next world row))

;; ---------------------------------------------------------------- dig a held block again

(defmethod plan/act :redig
  [_ {:goal/keys [item] :as row}]
  {:intent/kind :redig :intent/item item :intent/drops (drops-of-row row)})

(defn link
  "One tick of the link a :redig is in: start the child intent first when there is none, run it,
   and when it is done, then (on its world and the finished child); when it fails, fail with
   its reason. One intent from placing to picking up, so the need the placing spends (the held
   gravel) cannot send the planner elsewhere before the dig."
  [world {:intent/keys [child]} event first then]
  (cond (nil? child)
        (intent/run-child
         (intent/assoc-intent world :intent/child (assoc first :intent/status :active))
         event)
        (intent/done? child) (then (intent/dissoc-intent world :intent/child) child)
        (intent/failed? child)
        (-> world
            (assoc :player/controls {})
            (intent/fail (:intent/reason child)))
        :else (intent/run-child world event)))

(defmulti redig-stage
  "Advance a :redig intent one tick in its :intent/stage: :place (put the held block down),
   :dig (break it), :collect (pick up whatever it dropped)."
  {:arglists '([world intent event])}
  (fn [_world i _event] (:intent/stage i :place)))

(defmethod redig-stage :place
  [world {:intent/keys [item] :as i} event]
  (link world
        i
        event
        {:intent/kind :place :intent/item item}
        (fn [w placed]
          (intent/assoc-intent w :intent/stage :dig :intent/target (:intent/target placed)))))

(defmethod redig-stage :dig
  [world {:intent/keys [target] :as i} event]
  (link world
        i
        event
        (intent/child-dig target)
        (fn [w _] (intent/assoc-intent w :intent/stage :collect))))

(defmethod redig-stage :collect
  [world {:intent/keys [target drops] :as i} event]
  (link world
        i
        event
        {:intent/kind :collect :intent/target target :intent/drops drops}
        (fn [w _] (intent/done w))))

(defmethod intent/run :redig [world i event] (redig-stage world i event))
