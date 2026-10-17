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
            [clojurecraft.place :as place]
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
   block just dug is air. Never one whose dig would expose a fluid (intent/exposes-fluid?),
   nor one beside a lava trap still in force (path/near-lava-trap?):
   the lava that caught the bot there is still coming down."
  [world states]
  (let [eye (game/eye world)
        ey (physics/y-of eye)]
    (->> (exposed-cells (:world/chunks world)
                        (:world/blocks world)
                        (feet-chunk world)
                        states
                        (:plan/blacklist world #{})
                        (long (math/ceil (- ey search-height)))
                        (long (math/floor (+ ey search-height))))
         (remove #(path/near-lava-trap? world %))
         (memory/nearest-first eye search-radius)
         (remove #(intent/exposes-fluid? world %)))))

(defn continue-mine
  "The next intent of the row's chain in flight, or nil: walk → dig → collect, tagged
   :intent/for the row's id so neither a walk for another purpose nor another row's chain is
   ever continued as this one's dig."
  [world row]
  (intent/chain-next (:plan/last world) (:goal/id row) (drops-of-row row) {}))

(defn stairs
  "The staircase to cut when nothing is in sight: stair-depth down from the feet, never below
   the row's :goal/depth, which a mine row's staircase carries as :intent/floor-to (how far a
   floor laid through a cave must go on, stairs/floors-affordable?; the build's lava hunt passes
   a depth that is a floor, not a target, and carries none); nil when the feet are already there, or when the feet cell is in
   :plan/boxed-at, a stairs-down having died :boxed there. Another one from that cell dies the
   same way; the act tunnels, goes deeper, retreats or climbs out instead."
  [world {:goal/keys [depth act]}]
  (let [feet (long (math/floor (physics/y-of (:player/pos world))))
        to (cond-> (- feet stair-depth) depth (max depth))]
    (when (and (< to feet) (not (contains? (:plan/boxed-at world #{}) (intent/feet world))))
      (cond-> {:intent/kind :stairs-down :intent/to-y to}
        (and depth (= :mine act)) (assoc :intent/floor-to depth)))))

(def floor-margin 8)                  ; blocks above the bottom of the world a staircase deeper stops

(defn tunnel-heading
  "The heading a new tunnel takes: of the four, those with dry ground over the column a tunnel
   ahead first (stairs/dry-top?, not a lake or the sea: gym batch iron-a8 run 2 tunnelled 260
   blocks out under the sea and its stairs up met the seabed every way), then farthest from the
   landing (:plan/go-at; +x first without one), the first whose first level step is allowed
   (stairs/level-safe?) and has rock to cut, so never back along an open tunnel nor straight at
   what refused the last; nil when none. With the heading fixed, gym batches iron-a3 and
   iron-a4 retried tunnels :boxed by an aquifer three times in a second and failed. nil too
   when the feet cell is in :plan/boxed-at: a tunnel died :boxed standing there (plan/fail-intent),
   and one started again from the pocket dies the same way, since each heading's first step is
   all this looks at. Race 38515209959 runner 3 bot Clj_r13 (issue #105): at y 34 between two
   sheets of water, three tunnels in a row were started from the same pocket, each refused a
   step or two on and :boxed, and the plan failed in 25 seconds with 511 cobblestone in hand.
   Never back under the staircase just cut (:plan/last a :stairs-down, its heading turned round):
   its first steps dig the floors of the stairs, the only way back up. Sim race 38839665459
   runner 3 (issue #71, every seed of world R1/19 at 3f836ff) cut stairs west down to y 1, then
   tunnelled east under them; boxed beside an aquifer under gravel, its retreat found no route
   up the stairs it had undermined, every climb was :boxed, and the plan failed."
  [world]
  (let [[x _ z] (:player/pos world)
        {:intent/keys [kind dir]} (:plan/last world)
        back (when (and (= :stairs-down kind) dir) (mapv - dir))
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
    (when-not (contains? (:plan/boxed-at world #{}) feet)
      (first (filter (fn [d]
                       (let [cells (stairs/level-cells feet d)]
                         (and (not= back d)
                              (stairs/level-safe? world cells)
                              (some #(some-> (terrain/block-at world %)
                                             blocks/solid?)
                                    cells))))
                     ranked)))))

(def retreat-distance 12)             ; blocks a retreat walks from where nothing could be cut

(defn deeper
  "Stairs stair-depth below the feet when no tunnel can be cut here, the row's depth notwithstanding
   (ore lies below it too), when a stair from here can be cut at all and the bottom of the world is
   not near; else nil, and nil from a feet cell in :plan/boxed-at, where a staircase died :boxed
   already, as stairs is: a descent from there dies the same way whatever its depth. Cut means
   dug too: no cell of the stair borders water or lava
   (stairs/level-safe?), which the dig refuses; or cast, its floor laid from what is carried
   (stairs/lay). Gym batch iron-a6 run 3 was sent down stairs that could not be cut and failed
   :boxed three times in a second; batch iron-a8 run 3 down stairs whose every cell bordered
   water, and failed :boxed twice in a second."
  [world]
  (let [feet (intent/feet world)
        to (- (physics/y-of feet) stair-depth)
        cuttable? (fn [d]
                    (let [cells (stairs/stair-cells feet d)]
                      (or (stairs/level-safe? world cells)
                          (and (stairs/level-clear? world cells) (stairs/lay world feet cells)))))]
    (when (and (not (contains? (:plan/boxed-at world #{}) feet))
               (> to (+ chunk/min-y floor-margin))
               (some cuttable? stairs/directions))
      {:intent/kind :stairs-down :intent/to-y to})))

(defn retreat
  "Away from where nothing can be cut: out of water first when the feet are in it (a :swim-out for
   :retreat, done on dry land), else a walk to the end of a route at least retreat-distance
   away (path's :away goal), where the rock is new. From a boxed cell, when the search exhausts
   the reachable ground short of that distance, use its last unboxed waypoint instead;
   nil when there is no way. Gym batch iron-a6
   run 3's tunnel at y 16 ran along an aquifer until water filled its floor, and every tunnel and
   stair from there was refused."
  [world]
  (if (:player/in-water? world)
    {:intent/kind :swim-out :intent/for :retreat}
    (let [feet (intent/feet world)
          boxed (:plan/boxed-at world #{})
          r (path/plan world
                       (path/feet-cell (:player/pos world))
                       {:goal/kind :away :goal/pos feet :goal/range retreat-distance})
          target (cond (= :found (:path/status r)) (last (:path/waypoints r))
                       (and (= :none (:path/status r)) (contains? boxed feet))
                       (last (remove boxed (:path/waypoints r))))]
      (when target {:intent/kind :walk :intent/target target :intent/for :retreat}))))

(defn climb
  "Stairs up out of the ground, climb-stairs at a time (stairs/climb-to), when nothing can be
   tunnelled, cut or walked to from here and the feet are not out (stairs/out?); nil when they
   are. A stair up seals the water beside its cells and lays its step from carried cobblestone
   (stairs/up-lay), which a level step does not, so it is the one cut out of a pocket an
   aquifer closed; up top the search starts afresh and the next descent begins elsewhere,
   never from the pocket (:plan/boxed-at). Race 38515209959 (issue #105, four real bots at the
   flint and steel): runner 3 bot Clj_r19 and runner 9 bot Clj_r18 tunnelled for gravel at
   y 38-40 into underground water, swam out onto a cell every stair and tunnel from which was
   refused, found no route away along the flooded tunnel, and waited :boxed out holding 405
   and 466 cobblestone."
  [world]
  (let [feet (intent/feet world)]
    (when-not (stairs/out? world feet)
      {:intent/kind :stairs-up
       :intent/to-y (stairs/climb-to world terrain/top-y)
       :intent/for :boxed})))

(defn mine-next
  "The next step of mining a row: continue its chain; else, holding the tool its blocks need
   (and first putting it in hand when it is only in the main inventory), walk to the nearest exposed one a route reaches (of the max-candidates nearest; a walk that
   ends where the drop lands in reach, :intent/pickup?), or cut stairs down toward them, or at
   the row's depth a tunnel (tunnel-heading), or deeper stairs when none can be cut, or a retreat
   when neither can, or a climb out when there is no way away either (climb); wait for the
   tool. The live runs that waited at the depth with nothing in sight failed there; a tunnel's
   walls are where the ore shows. Beside a lava trap still in force (path/near-lava-trap?) the
   retreat comes before any stair or tunnel: sim race 38844683780 (issue #230) cut its stairs down
   where the lava had just caught it, and the flow followed it down."
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
              (or (when (path/near-lava-trap? world (intent/feet world)) (retreat world))
                  (stairs world row)
                  (when-let [d (tunnel-heading world)]
                    {:intent/kind :tunnel :intent/dir d :intent/length tunnel-length})
                  (deeper world)
                  (retreat world)
                  (climb world)
                  {:plan/wait :boxed})))))

(defmethod plan/act :mine [world row] (mine-next world row))

;; ---------------------------------------------------------------- dig a held block again

(defmethod plan/act :redig
  [world {:goal/keys [item] :as row}]
  (place/ashore world {:intent/kind :redig :intent/item item :intent/drops (drops-of-row row)}))

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

(defn afloat-no-spot?
  "Did the child place fail :no-spot with the body in water: afloat at a pond's edge every cell
   round the feet is water, so the place fails the tick it starts (issue #95's snapshots hold
   this moment, the child already failed and the plan one failure from its end)."
  [world child]
  (boolean (and (= :place (:intent/kind child))
                (intent/failed? child)
                (= :no-spot (:intent/reason child))
                (:player/in-water? world))))

(defmethod redig-stage :place
  ;; a place that found no spot afloat is not the redig's failure: a :swim-out for :retreat
  ;; (done on dry land) takes the child's place, and the place is started again from the bank
  [world {:intent/keys [item child] :as i} event]
  (cond (afloat-no-spot? world child)
        (intent/assoc-intent world
                             :intent/child
                             {:intent/kind :swim-out :intent/for :retreat :intent/status :active})
        (and (= :swim-out (:intent/kind child)) (intent/done? child))
        (intent/dissoc-intent world :intent/child)
        :else
        (link
         world
         i
         event
         {:intent/kind :place :intent/item item :intent/passage-ok? true}
         (fn [w placed]
           (intent/assoc-intent w :intent/stage :dig :intent/target (:intent/target placed))))))

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
