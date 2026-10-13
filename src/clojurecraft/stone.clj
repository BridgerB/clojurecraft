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
            [clojurecraft.memo :as memo]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for an exposed target
(def search-height 12)                ; blocks above or below the eye searched
(def stair-depth 6)                   ; stairs cut when no target is in sight, before looking again
(def default-blocks [:stone])         ; a :mine row that names no blocks mines stone, as the first one did
(def max-candidates 3)                ; the nearest targets a route is planned to before none counts as in sight
(def tunnel-length 12)                ; level steps a tunnel at a row's depth cuts before looking again
(def sides [[1 0] [-1 0] [0 1] [0 -1]]) ; the horizontal neighbours a block is dug from, in the order tried

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

(defn step-down?
  "Would digging the block at [x y z] leave a hole one deep that the body can step down into: a
   solid block under it and the two cells over it clear? Then its drop lands in the hole and a
   collect walks in after it. world is a world or its cells (path/cells)."
  [world [x y z]]
  (let [at (path/cells world)]
    (and (= :solid (at [x (dec y) z]))
         (path/clear? at [x (inc y) z])
         (path/clear? at [x (+ y 2) z]))))

(defn beside
  "A cell a route could stand in to dig a block at pos from, or nil: one of its horizontal
   neighbours at its level or a block lower (the block at the head), or the cell under it with
   the head just below it (the block overhead), or, when it leaves a step down (step-down?), a
   horizontal neighbour a block up (the block in the floor beside the feet). From each the drop
   comes to rest inside the pickup box, or in the hole the collect steps into. A block under the
   feet never counts: its drop lies a block under them and nothing walks in after it. In a
   tunnel two high, only the walls at the feet counted before the head and the ceiling did:
   batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 without one iron ore in sight;
   and before the floor did, live run 14 tunnelled for gravel over five in its own floor. world
   is a world or its cells (path/cells)."
  [world [x y z :as pos]]
  (let [at (path/cells world)]
    (first (filter #(path/standable? at %)
                   (concat (for [dy [0 -1] [dx dz] sides] [(+ x dx) (+ y dy) (+ z dz)])
                           [[x (- y 2) z]]
                           (when (step-down? at pos)
                             (for [[dx dz] sides] [(+ x dx) (inc y) (+ z dz)])))))))

(defn exposed?
  "Can a player stand beside the block at pos and dig it? world is a world or its cells."
  [world pos]
  (some? (beside world pos)))

(defn feet-chunk
  "The chunk column [cx cz] the feet stand in."
  [world]
  (let [[x _ z] (:player/pos world)]
    [(bit-shift-right (long (math/floor x)) 4) (bit-shift-right (long (math/floor z)) 4)]))

(defn near-chunks
  "The chunk columns of chunks within search-radius of chunk column [cx cz]: the terrain a
   search reads."
  [chunks [cx cz]]
  (let [r (inc (quot search-radius 16))]
    (into {}
          (filter (fn [[[kx kz] _]] (and (<= (abs (- kx cx)) r) (<= (abs (- kz cz)) r))) chunks))))

(def exposed-cells
  "[chunks blocks feet-chunk states blacklist lo hi] → the blocks of states in the chunks near
   feet-chunk, at heights lo to hi, not blacklisted, that a player can stand beside and dig, as
   the overlay of block updates says now: the scan of every near chunk and a standing test for
   each candidate, remembered by the terrain's identity (memo/by). The terrain changes when a
   chunk arrives or a block changes; the planner asked this every tick (a race's profile: a
   quarter of all its CPU)."
  (memo/by [:identical :identical :equal :equal :equal :equal :equal]
           64
           (fn [chunks blocks feet-chunk states blacklist lo hi]
             (let [terrain {:world/chunks chunks :world/blocks blocks}
                   at (path/cells terrain)]
               (into []
                     (comp (map (fn [[x y z _]] [x y z]))
                           (filter (fn [[_ y _]] (<= lo y hi)))
                           (remove blacklist)
                           (filter #(contains? states (terrain/block-at terrain %)))
                           (filter #(exposed? at %)))
                     (chunk/find-blocks (near-chunks chunks feet-chunk) #(contains? states %)))))))

(defn exposed-of-states
  "The blocks of states within search-radius of the eye and search-height of its height that
   the bot can stand beside, not blacklisted, nearest first. Read from the loaded terrain
   (the live view), not memory: stone is most of the world and ore is in every chunk, and only
   what is exposed now is worth a route. The chunks are scanned as they arrived, so each
   candidate is asked again through the overlay of block updates and the bot's own breaks: a
   block just dug is air."
  [world states]
  (let [eye (game/eye world)
        ey (physics/y-of eye)]
    (memory/nearest-first eye
                          search-radius
                          (exposed-cells (:world/chunks world)
                                         (:world/blocks world)
                                         (feet-chunk world)
                                         states
                                         (:plan/blacklist world #{})
                                         (long (math/ceil (- ey search-height)))
                                         (long (math/floor (+ ey search-height)))))))

(defn continue-mine
  "The next intent of the row's chain in flight, or nil: walk → dig → collect, tagged
   :intent/for the row's id so neither a walk for another purpose nor another row's chain is
   ever continued as this one's dig."
  [world row]
  (intent/chain-next (:plan/last world) (:goal/id row) (drops-of-row row) {}))

(defn stairs
  "The staircase to cut when nothing is in sight: stair-depth down from the feet, never below
   the row's :goal/depth; nil when the feet are already there, or when the feet cell is in
   :plan/boxed-at, a stairs-down having died :boxed there. Another one from that cell dies the
   same way; the act tunnels, goes deeper or retreats instead."
  [world {:goal/keys [depth]}]
  (let [feet (long (math/floor (physics/y-of (:player/pos world))))
        to (cond-> (- feet stair-depth) depth (max depth))]
    (when (and (< to feet) (not (contains? (:plan/boxed-at world #{}) (intent/feet world))))
      {:intent/kind :stairs-down :intent/to-y to})))

(def floor-margin 8)                  ; blocks above the bottom of the world a staircase deeper stops

(defn tunnel-heading
  "The heading a new tunnel takes: of the four, those with dry ground over the column a tunnel
   ahead first (stairs/dry-top?, not a lake or the sea: gym batch iron-a8 run 2 tunnelled 260
   blocks out under the sea and its stairs up met the seabed every way), then farthest from the
   landing (:plan/go-at; +x first without one), the first whose first level step is allowed
   (stairs/level-safe?) and has rock to cut, so never back along an open tunnel nor straight at
   what refused the last; nil when none. With the heading fixed, gym batches iron-a3 and
   iron-a4 retried tunnels :boxed by an aquifer three times in a second and failed."
  [world]
  (let [[x _ z] (:player/pos world)
        [fx _ fz :as feet] (intent/feet world)
        home (:plan/go-at world)
        dry? (fn [[dx dz]]
               (stairs/dry-top? world [(+ fx (* tunnel-length dx)) (+ fz (* tunnel-length dz))]))
        ranked (sort-by (fn [[dx dz :as d]] [(if (dry? d) 0 1)
                                             (if home
                                               (- (physics/horizontal-distance [(+ x dx) 0 (+ z dz)]
                                                                               home))
                                               0)])
                        stairs/directions)]
    (first (filter (fn [d]
                     (let [cells (stairs/level-cells feet d)]
                       (and (stairs/level-safe? world cells)
                            (some #(some-> (terrain/block-at world %)
                                           blocks/solid?)
                                  cells))))
                   ranked))))

(def retreat-distance 12)             ; blocks a retreat walks from where nothing could be cut

(defn deeper
  "Stairs stair-depth below the feet when no tunnel can be cut here, the row's depth notwithstanding
   (ore lies below it too), when a stair from here can be cut at all and the bottom of the world is
   not near; else nil. Cut means dug too: no cell of the stair borders water or lava
   (stairs/level-safe?), which the dig refuses. Gym batch iron-a6 run 3 was sent down stairs that
   could not be cut and failed :boxed three times in a second; batch iron-a8 run 3 down stairs
   whose every cell bordered water, and failed :boxed twice in a second."
  [world]
  (let [feet (intent/feet world)
        to (- (physics/y-of feet) stair-depth)]
    (when (and (> to (+ chunk/min-y floor-margin))
               (some #(stairs/level-safe? world (stairs/stair-cells feet %)) stairs/directions))
      {:intent/kind :stairs-down :intent/to-y to})))

(defn retreat
  "Away from where nothing can be cut: out of water first when the feet are in it (a :swim-out for
   :retreat, done on dry land), else a walk to the end of a route at least retreat-distance
   away (path's :away goal), where the rock is new; nil when there is no way. Gym batch iron-a6
   run 3's tunnel at y 16 ran along an aquifer until water filled its floor, and every tunnel and
   stair from there was refused."
  [world]
  (if (:player/in-water? world)
    {:intent/kind :swim-out :intent/for :retreat}
    (let [r (path/plan
             world
             (path/feet-cell (:player/pos world))
             {:goal/kind :away :goal/pos (intent/feet world) :goal/range retreat-distance})]
      (when (= :found (:path/status r))
        {:intent/kind :walk :intent/target (last (:path/waypoints r)) :intent/for :retreat}))))

(defn mine-next
  "The next step of mining a row: continue its chain; else, holding the tool its blocks need
   (and first putting it in hand when it is only in the main inventory), walk to the nearest exposed one a route reaches (of the max-candidates nearest; a walk that
   ends where the drop lands in reach, :intent/pickup?), or cut stairs down toward them, or at
   the row's depth a tunnel (tunnel-heading), or deeper stairs when none can be cut, or a retreat
   when neither can; wait for the tool. The live runs that waited at the depth
   with nothing in sight failed there; a tunnel's walls are where the ore shows."
  [world row]
  (or (continue-mine world row)
      (let [missing (tool-missing world row)
            [_ stowed-item :as stowed] (intent/stowed-tool world (first (states-of-row row)))
            walk-to (fn [t]
                      (cond-> {:intent/kind :walk
                               :intent/target t
                               :intent/for (:goal/id row)
                               :intent/pickup? true}
                        (= (inc (physics/y-of t)) (physics/y-of (beside world t)))
                        (assoc :intent/step-down? true)))
            walk (when-not missing
                   (->> (exposed-of-states world (states-of-row row))
                        (take max-candidates)
                        (map walk-to)
                        (filter #(intent/routable? world %))
                        first))]
        (cond missing {:plan/wait missing}
              stowed {:intent/kind :equip :intent/item (recipe/item-name stowed-item)}
              walk walk
              :else
              (or (stairs world row)
                  (when-let [d (tunnel-heading world)]
                    {:intent/kind :tunnel :intent/dir d :intent/length tunnel-length})
                  (deeper world)
                  (retreat world)
                  {:plan/wait :boxed})))))

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
        {:intent/kind :place :intent/item item :intent/passage-ok? true}
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
