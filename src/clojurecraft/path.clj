(ns clojurecraft.path
  "A route over the block grid as a pure function of the world value (issue #4).

   (plan world from goal opts) → {:path/waypoints [[x y z] ...] :path/cost n :path/status s}

   A node is the feet cell [x y z]: standing there means a floor at y-1 and the body and head
   clear. Moves are data (`moves`): what cell each leads to, what it costs, and which cells must
   be what for it to be allowed; `neighbors` interprets them. Goals are data too (`:goal/kind`
   :near :block :xz :away), with `goal-done?` and `heuristic` open on the kind. The search is
   plain A* with a budget in node expansions, never in time, so a recording replays to the same
   route on any machine. Running out of budget or of frontier returns the route to the node that
   came nearest (:partial, :none), which is what a walker should follow before planning again as
   chunks arrive.

   Liquids are walls, never floors (the physics has no fluid model), lava is a wall and no cell
   beside or over lava is ever entered (the siblings' rule, applied to every move kind), and
   blocks the physics cannot simulate (slabs, stairs, fences, doors ...) are :awkward: neither
   floor nor clear, so routes go around them. Nothing here reads a clock or the atom."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.memo :as memo]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def flat-shape 0.125)                ; a shape no taller is walked over like the floor (a carpet; one layer of snow has none, see one-layer-snow)
(def max-fall 3)                      ; blocks a drop may fall; vanilla damage starts above 3
(def default-max-nodes 6000)          ; expansions one plan may spend
(def sky-weight 4.0)                  ; a block of height in the :sky heuristic, more than a jump costs
(def walk-cost 1.0)                   ; one cell on the level
(def jump-cost 2.0)                   ; one cell up
(def diagonal-cost (math/sqrt 2.0))   ; one cell on the level, cornerwise
(def fall-cost-per-block 0.5)         ; added to walk-cost for each block of a drop

;; ---------------------------------------------------------------- cells

(def awkward-types
  "Block definition types the physics cannot simulate though their collision shape is a full cube
   or nothing (slime and honey bounce and stick, ice slides, a web slows, a berry
   bush or magma hurts), and the shapes listed before the game's own were read
   (blocks/partial-shapes, but for those no taller than flat-shape): a route treats them as
   neither floor nor clear. Not powder snow: no collision for a player, it is clear (blocks/
   passable-types), and a route goes through it to the floor below (issue #87)."
  #{:slab :stair :fence :wall :fence_gate :trapdoor :door :iron_bars :stained_glass_pane :chain
    :lantern :snow_layer :farmland :dirt_path :cactus :magma :sweet_berry_bush :bed :candle
    :candle_cake :cake :skull :wall_skull :player_head :player_wall_head :wither_skull
    :wither_wall_skull :piglinwallskull :flower_pot :end_rod :lightning_rod :pointed_dripstone
    :amethyst_cluster :big_dripleaf :big_dripleaf_stem :scaffolding :anvil :bell :brewing_stand
    :campfire :cauldron :layered_cauldron :lava_cauldron :chest :copper_chest :trapped_chest
    :ender_chest :composter :decorated_pot :enchantment_table :grindstone :hopper :lectern
    :stonecutter :turtle_egg :sniffer_egg :dragon_egg :daylight_detector :honey :slime :ice
    :frosted_ice :web :soul_sand :mud :weathering_copper_slab :weathering_copper_stair
    :weathering_copper_trap_door :weathering_copper_door :weathering_copper_bar
    :weathering_copper_chain :weathering_copper_chest :weathering_lantern :weathering_lightning_rod
    :weathering_copper_grate :bamboo_stalk :chorus_plant :chorus_flower :mangrove_roots :heavy_core
    :vault :trial_spawner :spawner :conduit :beacon :shelf})

(def one-layer-snow
  "The state of a snow layer one layer deep, the block's default state: the game gives it no
   collision shape at all (SnowLayerBlock's shape for one layer is empty), the physics passes
   through it (blocks/passable-types), so a route walks through it like air, the feet in its
   cell on the block under it. Deeper layers collide as the game says, (layers - 1) / 8, which
   the physics cannot simulate: they stay :awkward with their type. Race 38477665442 landed 39
   bots on snowy ground, every cell round them under this one layer; the feet cell was not
   standable, start-cell found nothing beside it, every plan was :none and twelve walks failed
   :no-path in a second (issue #38)."
  (blocks/default-state :snow))

(defn classify-id
  "What a block state means to a route: :water, :lava, :awkward (a shape the physics cannot
   simulate), :solid (a full cube), or :clear (passable, not a liquid; one layer of snow is).
   nil → :unknown."
  [id]
  (cond (nil? id) :unknown
        (contains? blocks/water-states id) :water
        (contains? blocks/lava-states id) :lava
        (= id one-layer-snow) :clear
        (or (contains? awkward-types (blocks/type-of id))
            (> (get blocks/partial-shapes (blocks/name-of id) 0.0) flat-shape))
        :awkward
        (blocks/solid? id) :solid
        :else :clear))

(defn classify
  "The cell at [x y z] of the world as a route sees it (see classify-id); :unknown when its
   chunk is not loaded."
  [world pos]
  (classify-id (terrain/block-at world pos)))

(def ice-states
  "Every state of the blocks of a lake's sheet: ice, frosted ice, packed ice, blue ice."
  (into #{} (mapcat blocks/states-of) [:ice :frosted_ice :packed_ice :blue_ice]))

(defn on-ice?
  "Do the feet stand on a sheet of ice: is one of the two blocks under the feet's cell (a touch
   under the position, as intent/feet reads it) ice, the second for a floor laid over the sheet?
   false with no position."
  [world]
  (boolean (when-let [[x y z] (:player/pos world)]
             (some (fn [d]
                     (contains? ice-states
                                (terrain/block-at world
                                                  [(long (math/floor x))
                                                   (- (long (math/floor (+ y 0.5))) d)
                                                   (long (math/floor z))])))
                   [1 2]))))

(defn cell-key
  "One long naming the cell [x y z]: x and z in 26 bits each, y in 12, which holds every cell
   inside the world border (±30,000,000) at any height a dimension has, each to its own key."
  ^long [^long x ^long y ^long z]
  (bit-or (bit-shift-left (bit-and x 0x3FFFFFF) 38)
          (bit-shift-left (bit-and z 0x3FFFFFF) 12)
          (bit-and y 0xFFF)))

(defn cells
  "The cells of w as a route sees them: a function of [x y z] to classify's answer. w is a world,
   or such a function already, returned as it is, so every predicate below takes either. Of a
   world whose bot stands on ice (on-ice?) the sheet is floor: a route never steps onto ice from
   land, but a bot a swim out put on one (issue #71) must be able to walk off it. Made from a
   world, each cell is read through the terrain once and its answer held for the life of
   the function: one search or one scan asks about the same few thousand cells hundreds of
   thousands of times (a race's profile: the route search a fifth of all the bots' CPU, nearly
   all of it reading cells it had read). It answers about one world value, so what it holds is
   what that value says; it is made inside one question and never kept, so no two threads share
   it."
  [w]
  (cond (fn? w) w
        :else
        (let [held (java.util.HashMap.)
              ice? (on-ice? w)]
          (fn [[x y z :as pos]]
            (let [k (cell-key x y z)
                  known (.get held k)]
              (cond known known
                    :else
                    (let [c (classify w pos)
                          c (if (and ice?
                                     (= :awkward c)
                                     (contains? ice-states (terrain/block-at w pos)))
                              :solid
                              c)]
                      (.put held k c)
                      c)))))))

(def around
  "The four horizontal neighbours of a cell, as offsets."
  [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])

(def lava-sides
  "The cells lava-near? looks at, as offsets: the four horizontal neighbours and the one under."
  (conj around [0 -1 0]))

(defn lava-near?
  "Is there lava in any of the four horizontal neighbours of pos, or under it? Such a cell is
   never entered: a misstep from it is a death (ruststeve's lava_around_body). world is a world
   or its cells."
  [world [x y z]]
  (let [at (cells world)]
    (boolean (some (fn [[dx dy dz]] (= :lava (at [(+ x dx) (+ y dy) (+ z dz)]))) lava-sides))))

(defn floor?
  "Can the feet stand on the block at pos: a full cube. world is a world or its cells."
  [world pos]
  (= :solid ((cells world) pos)))

(defn clear?
  "May the body occupy pos: passable, not a liquid, no lava beside or under it. world is a world
   or its cells."
  [world pos]
  (let [at (cells world)] (and (= :clear (at pos)) (not (lava-near? at pos)))))

(defn standable?
  "Could the feet rest at node [x y z]: a floor under it, body and head clear. world is a world
   or its cells."
  [world [x y z]]
  (let [at (cells world)]
    (and (floor? at [x (dec y) z]) (clear? at [x y z]) (clear? at [x (inc y) z]))))

;; ---------------------------------------------------------------- moves as data

(def templates
  "The move set along +x; `moves` rotates it to the eight directions. :move/to is the offset of
   the node reached; :move/needs are [what offset] pairs, each a predicate name over a cell
   relative to the current node; a :drop template falls from :move/to until it finds a floor."
  [{:move/id :walk
    :move/to [1 0 0]
    :move/cost walk-cost
    :move/needs [[:floor [1 -1 0]] [:clear [1 0 0]] [:clear [1 1 0]]]}
   {:move/id :jump-up
    :move/to [1 1 0]
    :move/cost jump-cost
    :move/needs [[:clear [0 2 0]] [:floor [1 0 0]] [:clear [1 1 0]] [:clear [1 2 0]]]}
   {:move/id :drop
    :move/to [1 0 0]
    :move/cost walk-cost
    :move/fall? true
    :move/needs [[:clear [1 0 0]] [:clear [1 1 0]] [:not-floor [1 -1 0]]]}
   {:move/id :diagonal
    :move/to [1 0 1]
    :move/cost diagonal-cost
    :move/needs [[:floor [1 -1 1]] [:clear [1 0 1]] [:clear [1 1 1]] [:clear [1 0 0]]
                 [:clear [1 1 0]] [:clear [0 0 1]] [:clear [0 1 1]]]}])

(def rotations
  "The eight quarter-turn rotations of an [x y z] offset about y, as functions, so one template
   along +x yields the four cardinal moves and one along +x+z the four diagonals."
  [(fn [[x y z]] [x y z]) (fn [[x y z]] [(- z) y x]) (fn [[x y z]] [(- x) y (- z)])
   (fn [[x y z]] [z y (- x)])])

(defn rotate-move
  "A move template turned by one of `rotations`."
  [rot m]
  (-> m
      (update :move/to rot)
      (update :move/needs (fn [needs] (mapv (fn [[what off]] [what (rot off)]) needs)))))

(def moves
  "Every move a node may take: the templates in each of the four rotations (a diagonal template
   rotated four times covers the four corners)."
  (vec (for [m templates rot rotations] (rotate-move rot m))))

(defn need-met?
  "Does the cell at offset from node satisfy what (:floor, :clear, :not-floor)? world is a world
   or its cells."
  [world [x y z] [what [dx dy dz]]]
  (let [cell [(+ x dx) (+ y dy) (+ z dz)]]
    (case what
      :floor (floor? world cell)
      :clear (clear? world cell)
      :not-floor (not (floor? world cell)))))

(defn landing
  "Where a drop from cell comes to rest, falling at most max-fall blocks: the node whose floor
   is the first solid below, with the body and head there clear and nothing but clear cells in
   between; nil when the fall is too far or lands on anything but a floor (water, lava, an
   awkward shape or an unloaded chunk are all refused). world is a world or its cells."
  [world [x y z]]
  (let [at (cells world)]
    (reduce (fn [_ fall]
              (let [feet [x (- y fall) z]
                    below [x (- y fall 1) z]]
                (cond (not (clear? at feet)) (reduced nil)
                      (floor? at below) (reduced {:node feet :fall fall})
                      (= :clear (at below)) nil
                      :else (reduced nil))))
            nil
            (range 1 (inc max-fall)))))

(defn apply-move
  "The node and cost a move leads to from node, or nil when the world does not allow it. world
   is a world or its cells."
  [world [x y z :as node] {:move/keys [to cost needs fall?]}]
  (let [at (cells world)]
    (when (every? #(need-met? at node %) needs)
      (let [[dx dy dz] to
            there [(+ x dx) (+ y dy) (+ z dz)]]
        (if fall?
          (when-let [{:keys [node fall]} (landing at there)]
            {:node node :cost (+ cost (* fall-cost-per-block fall))})
          {:node there :cost cost})))))

(defn neighbors
  "Every node reachable from node in one move, with the move's cost: [{:node :cost :move} ...].
   A drop and a walk to the same cell cannot both apply (a walk needs a floor the drop forbids).
   world is a world or its cells."
  [world node]
  (let [at (cells world)]
    (into []
          (keep (fn [m]
                  (some-> (apply-move at node m)
                          (assoc :move (:move/id m)))))
          moves)))

;; ---------------------------------------------------------------- goals as data

(defmulti goal-done?
  "Is node where the goal wants the feet, in world? Open on :goal/kind. The goal is data, never
   the world: a goal that asks about the terrain (:sky) reads the world it is given."
  {:arglists '([world goal node])}
  (fn [_world goal _node] (:goal/kind goal)))

(defmulti heuristic
  "An estimate of the cost from node to the goal, admissible except where a kind says (:sky, where
   any way out will do). Open on :goal/kind."
  {:arglists '([goal node])}
  (fn [goal _node] (:goal/kind goal)))

(defn octile
  "Octile distance on the plane between two cells: diagonals at their real cost."
  [[ax _ az] [bx _ bz]]
  (let [dx (abs (- ax bx))
        dz (abs (- az bz))]
    (+ (* diagonal-cost (min dx dz)) (- (max dx dz) (min dx dz)))))

(defn flat-distance
  "Octile on the plane plus the height difference: the heuristic every goal with a point uses."
  [[_ ay _ :as a] [_ by _ :as b]]
  (+ (octile a b) (abs (- ay by))))

(defmethod goal-done? :near
  [_ {:goal/keys [pos range]} node]
  (<= (flat-distance node pos) (or range 3)))
(defmethod heuristic :near
  [{:goal/keys [pos range]} node]
  (max 0.0 (- (flat-distance node pos) (or range 3))))

(defmethod goal-done? :block [_ {:goal/keys [pos]} node] (= node pos))
(defmethod heuristic :block [{:goal/keys [pos]} node] (flat-distance node pos))

(defmethod goal-done? :xz [_ {[px _ pz] :goal/pos} [x _ z]] (= [x z] [px pz]))
(defmethod heuristic :xz [{:goal/keys [pos]} node] (octile node pos))

(defmethod goal-done? :away [_ {:goal/keys [pos range]} node] (> (flat-distance node pos) range))
(defmethod heuristic :away
  [{:goal/keys [pos range]} node]
  (max 0.0 (- range (flat-distance node pos))))

(defmethod goal-done? :sky
  ;; a standable cell the open sky is over
  [world _ node]
  (terrain/under-sky? world node))
(defmethod heuristic :sky
  ;; Higher is closer to an opening, and weighted past what a jump costs, so the air already dug
  ;; is climbed before a mine is filled. Not admissible: any way out will do. Full run 4528031
  ;; stood at y 17 in the iron mine, a stair already cut down behind it, and the search toward a
  ;; tree 140 blocks off spent its budget in the tunnels; a new stair was cut and the only
  ;; pickaxe broke.
  [_ [_ y _]]
  (* sky-weight (max 0 (- terrain/top-y (long y)))))

(defn step-down-node?
  "Is node a straight neighbour of pos a block up, where a block dug at pos is in the floor
   beside the feet (a :pickup goal with :goal/step-down?: its drop lands in the hole it leaves)?"
  [pos [_ ny _ :as node]]
  (and (= (physics/y-of pos) (dec ny)) (== 1 (octile node pos))))

(defmethod goal-done? :pickup
  ;; where a block dug at pos drops within the pickup box: within range on the plane, with pos at
  ;; the feet's height or up to rise above it; or, with :goal/step-down?, in the floor beside them
  [_ {:goal/keys [pos range rise step-down?]} [_ ny _ :as node]]
  (or (and (<= (octile node pos) range) (<= ny (physics/y-of pos) (+ ny rise)))
      (boolean (and step-down? (step-down-node? pos node)))))
(defmethod heuristic :pickup
  [{:goal/keys [pos range rise step-down?]} [_ ny _ :as node]]
  (let [py (physics/y-of pos)]
    (cond (and step-down? (= py (dec ny))) (max 0.0 (dec (octile node pos)))
          :else
          (+ (max 0.0 (- (octile node pos) range))
             (cond (< py ny) (- ny py)
                   (> py (+ ny rise)) (- py ny rise)
                   :else 0)))))

;; ---------------------------------------------------------------- the search

(defn route
  "The nodes from the start to node, start first, read back through the parents map."
  [parents node]
  (vec (reverse (take-while some? (iterate parents node)))))

(def trap-cost 10.0)                  ; a move into a column where the bot was trapped costs this much more
(def trap-ttl 600000)                 ; ms a trap keeps its cost: ten minutes
(def trap-reach 2)                    ; cells within this of the trap's column on the plane are its

(defn trap-factor
  "The cost multiplier for entering node: trap-cost near a trap in :world/traps younger than
   trap-ttl, else 1.0."
  [world [x _ z]]
  (let [now (:time/now world 0)]
    (if (some (fn [{:trap/keys [pos at]}]
                (let [[tx tz] pos]
                  (and (<= (- now at) trap-ttl)
                       (<= (abs (- x tx)) trap-reach)
                       (<= (abs (- z tz)) trap-reach))))
              (:world/traps world))
      trap-cost
      1.0)))

(def lava-trap-reach 4)                ; cells on the plane round a lava trap's column a mine leaves alone

(defn near-lava-trap?
  "Is cell within lava-trap-reach on the plane of a lava trap still in force (:world/traps,
   :trap/kind :lava, younger than trap-ttl)? Lava that caught the bot once is still flowing
   there. Sim race 38844683780 (issue #230, world R2/9): lava falling from ten blocks up caught
   the bot smelting at y 38; it fled to the next cell, the mine chose the iron ore two blocks
   off, under the same flow, and the lava caught it again and again until the flee found no
   footing."
  [world [x _ z]]
  (let [now (:time/now world 0)]
    (boolean (some (fn [{:trap/keys [pos at kind]}]
                     (let [[tx tz] pos]
                       (and (= :lava kind)
                            (<= (- now at) trap-ttl)
                            (<= (abs (- x tx)) lava-trap-reach)
                            (<= (abs (- z tz)) lava-trap-reach))))
                   (:world/traps world)))))

(defn search
  "A* from start over the world toward goal, spending at most max-nodes expansions. Returns
   {:path/waypoints :path/cost :path/status}: :found when a node satisfies the goal, else
   :partial (the budget ran out) or :none (the frontier did) with the route to the node that
   came nearest by the heuristic. The waypoints omit the start. The open set is a sorted set of
   [f g node], so ties break on the node itself and the search is deterministic. Its cells are
   read once each (cells); the goal and the traps read the world."
  [world start goal max-nodes]
  (let [at (cells world)
        h0 (heuristic goal start)
        done (fn [status parents best g]
               {:path/waypoints (vec (rest (route parents best)))
                :path/cost (double (get g best 0.0))
                :path/status status})]
    (loop [open (sorted-set [h0 0.0 start])
           g {start 0.0}
           parents {start nil}
           closed #{}
           best start
           best-h h0
           expanded 0]
      (cond (empty? open) (done :none parents best g)
            (>= expanded max-nodes) (done :partial parents best g)
            :else
            (let [[_ gn node :as entry] (first open)
                  open (disj open entry)]
              (cond (closed node) (recur open g parents closed best best-h expanded)
                    (goal-done? world goal node) (done :found parents node g)
                    :else
                    (let [closed (conj closed node)
                          hn (heuristic goal node)
                          [best best-h] (if (< hn best-h) [node hn] [best best-h])
                          [open g parents]
                          (reduce (fn [[open g parents] {next :node cost :cost}]
                                    (let [g' (+ gn (* cost (trap-factor world next)))]
                                      (if (and (not (closed next))
                                               (< g' (get g next Double/MAX_VALUE)))
                                        [(conj open [(+ g' (heuristic goal next)) g' next])
                                         (assoc g next g') (assoc parents next node)]
                                        [open g parents])))
                                  [open g parents]
                                  (neighbors at node))]
                      (recur open g parents closed best best-h (inc expanded)))))))))

(defn feet-cell
  "The node a player at feet position [x y z] stands in."
  [[x y z]]
  [(long (math/floor x)) (long (math/floor y)) (long (math/floor z))])

(defn start-cell
  "Where a search from the feet at cell from begins: from itself when it is standable, else the
   nearest standable cell within one block on the plane and one up or down. A player whose feet
   sit on a block edge floors into the neighbouring column, which on a hillside is inside the
   hill (recorded by the gym at landing 21280,18720: the feet at x 21281.0 on a stone edge, the
   cell they floor into solid, every plan :none); the box rests on the cell beside it. nil when
   nothing near can be stood on. world is a world or its cells."
  [world [x y z :as from]]
  (let [at (cells world)]
    (if (standable? at from)
      from
      (->> (for [dy [0 -1 1] dx [0 -1 1] dz [0 -1 1]] [(+ x dx) (+ y dy) (+ z dz)])
           (filter #(standable? at %))
           first))))

(defn traps-in-force
  "The traps of :world/traps younger than trap-ttl at :time/now: the only part of the clock a
   route depends on."
  [world]
  (let [now (:time/now world 0)]
    (filterv (fn [{:trap/keys [at]}] (<= (- now at) trap-ttl)) (:world/traps world))))

(def plan-on
  "[chunks blocks traps from goal max-nodes] → the route plan gives over that terrain with those
   traps in force, remembered by the terrain's identity (memo/by): an act asked whether each of
   its candidates could be walked to every tick, a full search each (a race's profile: a sixth of
   all its CPU), over terrain that changes when a chunk arrives or a block changes."
  (memo/by [:identical :identical :equal :equal :equal :equal]
           256
           (fn [chunks blocks traps from goal max-nodes]
             ;; every trap here is in force, so the clock is not needed: trap-factor counts them all at 0
             (let [world {:world/chunks chunks :world/blocks blocks :world/traps traps}
                   start (start-cell world from)
                   [x y z] from
                   departure (when (and (not= start from)
                                        (blocks/solid? (terrain/block-at world [x (dec y) z]))
                                        (clear? world from)
                                        (clear? world [x (inc y) z]))
                               (search world from goal max-nodes))]
               (cond (= :found (:path/status departure)) (assoc departure :path/from from)
                     start (assoc (search world start goal max-nodes) :path/from start)
                     :else {:path/waypoints [] :path/cost 0.0 :path/status :none})))))

(defn plan
  "A route for the feet from the cell `from` toward goal: {:path/waypoints [[x y z] ...]
   :path/cost n :path/status :found|:partial|:none :path/from start}. opts: {:path/max-nodes n} (default
   default-max-nodes). Pure and deterministic: equal inputs, equal output. The search normally
   begins at start-cell (from, or the standable cell beside it the player's box rests on).
   Before borrowing a neighbouring start, a route may leave the actual supported, clear feet
   cell if it reaches the goal; awkward floors remain forbidden as waypoints. With neither
   departure nor a start-cell, the answer is :none with no waypoints.
   A goal in an unloaded chunk comes back :partial or :none
   toward it, which is the walker's cue to move and plan again."
  ([world from goal] (plan world from goal {}))
  ([world from goal {:path/keys [max-nodes] :or {max-nodes default-max-nodes}}]
   (plan-on (:world/chunks world)
            (:world/blocks world)
            (traps-in-force world)
            from
            goal
            max-nodes)))
