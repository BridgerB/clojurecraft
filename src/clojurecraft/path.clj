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
  (:require [clojure.set :as set]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.terrain :as terrain]))

(def max-fall 3)                      ; blocks a drop may fall; vanilla damage starts above 3
(def default-max-nodes 6000)          ; expansions one plan may spend
(def walk-cost 1.0)                   ; one cell on the level
(def jump-cost 2.0)                   ; one cell up
(def diagonal-cost (Math/sqrt 2.0))   ; one cell on the level, cornerwise
(def fall-cost-per-block 0.5)         ; added to walk-cost for each block of a drop

;; ---------------------------------------------------------------- cells

(def awkward-types
  "Block definition types with a collision shape that is not a full cube or empty: the physics
   cannot simulate them, so a route treats them as neither floor nor clear."
  #{:slab :stair :fence :wall :fence_gate :trapdoor :door :iron_bars :stained_glass_pane :chain
    :lantern :snow_layer :farmland :dirt_path :cactus :magma :powder_snow :sweet_berry_bush :bed
    :candle :candle_cake :cake :skull :wall_skull :player_head :player_wall_head :wither_skull
    :wither_wall_skull :piglinwallskull :flower_pot :end_rod :lightning_rod :pointed_dripstone
    :amethyst_cluster :big_dripleaf :big_dripleaf_stem :scaffolding :anvil :bell :brewing_stand
    :campfire :cauldron :layered_cauldron :lava_cauldron :chest :copper_chest :trapped_chest
    :ender_chest :composter :decorated_pot :enchantment_table :grindstone :hopper :lectern
    :stonecutter :turtle_egg :sniffer_egg :dragon_egg :daylight_detector :honey :slime :ice
    :frosted_ice :web :soul_sand :mud :weathering_copper_slab :weathering_copper_stair
    :weathering_copper_trap_door :weathering_copper_door :weathering_copper_bar
    :weathering_copper_chain :weathering_copper_chest :weathering_lantern
    :weathering_lightning_rod :weathering_copper_grate :bamboo_stalk :chorus_plant :chorus_flower
    :mangrove_roots :heavy_core :vault :trial_spawner :spawner :conduit :beacon :shelf})

(def water-states "Every state of water." (blocks/states-where (fn [[n]] (= n :water))))
(def lava-states "Every state of lava." (blocks/states-where (fn [[n]] (= n :lava))))

(defn classify-id
  "What a block state means to a route: :water, :lava, :awkward (a shape the physics cannot
   simulate), :solid (a full cube), or :clear (passable, not a liquid). nil → :unknown."
  [id]
  (cond
    (nil? id) :unknown
    (contains? water-states id) :water
    (contains? lava-states id) :lava
    (contains? awkward-types (blocks/type-of id)) :awkward
    (blocks/solid? id) :solid
    :else :clear))

(defn classify
  "The cell at [x y z] of the world as a route sees it (see classify-id); :unknown when its
   chunk is not loaded."
  [world pos]
  (classify-id (terrain/block-at world pos)))

(def around
  "The four horizontal neighbours of a cell, as offsets."
  [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])

(defn lava-near?
  "Is there lava in any of the four horizontal neighbours of pos, or under it? Such a cell is
   never entered: a misstep from it is a death (ruststeve's lava_around_body)."
  [world [x y z]]
  (boolean (some (fn [[dx dy dz]] (= :lava (classify world [(+ x dx) (+ y dy) (+ z dz)])))
                 (conj around [0 -1 0]))))

(defn floor?
  "Can the feet stand on the block at pos: a full cube."
  [world pos]
  (= :solid (classify world pos)))

(defn clear?
  "May the body occupy pos: passable, not a liquid, no lava beside or under it."
  [world pos]
  (and (= :clear (classify world pos)) (not (lava-near? world pos))))

(defn standable?
  "Could the feet rest at node [x y z]: a floor under it, body and head clear."
  [world [x y z]]
  (and (floor? world [x (dec y) z]) (clear? world [x y z]) (clear? world [x (inc y) z])))

;; ---------------------------------------------------------------- moves as data

(def templates
  "The move set along +x; `moves` rotates it to the eight directions. :move/to is the offset of
   the node reached; :move/needs are [what offset] pairs, each a predicate name over a cell
   relative to the current node; a :drop template falls from :move/to until it finds a floor."
  [{:move/id :walk :move/to [1 0 0] :move/cost walk-cost
    :move/needs [[:floor [1 -1 0]] [:clear [1 0 0]] [:clear [1 1 0]]]}
   {:move/id :jump-up :move/to [1 1 0] :move/cost jump-cost
    :move/needs [[:clear [0 2 0]] [:floor [1 0 0]] [:clear [1 1 0]] [:clear [1 2 0]]]}
   {:move/id :drop :move/to [1 0 0] :move/cost walk-cost :move/fall? true
    :move/needs [[:clear [1 0 0]] [:clear [1 1 0]] [:not-floor [1 -1 0]]]}
   {:move/id :diagonal :move/to [1 0 1] :move/cost diagonal-cost
    :move/needs [[:floor [1 -1 1]] [:clear [1 0 1]] [:clear [1 1 1]]
                 [:clear [1 0 0]] [:clear [1 1 0]] [:clear [0 0 1]] [:clear [0 1 1]]]}])

(def rotations
  "The eight quarter-turn rotations of an [x y z] offset about y, as functions, so one template
   along +x yields the four cardinal moves and one along +x+z the four diagonals."
  [(fn [[x y z]] [x y z]) (fn [[x y z]] [(- z) y x]) (fn [[x y z]] [(- x) y (- z)]) (fn [[x y z]] [z y (- x)])])

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
  "Does the cell at offset from node satisfy what (:floor, :clear, :not-floor)?"
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
   awkward shape or an unloaded chunk are all refused)."
  [world [x y z]]
  (reduce (fn [_ fall]
            (let [feet [x (- y fall) z]
                  below [x (- y fall 1) z]]
              (cond
                (not (clear? world feet)) (reduced nil)
                (floor? world below) (reduced {:node feet :fall fall})
                (= :clear (classify world below)) nil
                :else (reduced nil))))
          nil
          (range 1 (inc max-fall))))

(defn apply-move
  "The node and cost a move leads to from node, or nil when the world does not allow it."
  [world [x y z :as node] {:move/keys [to cost needs fall?]}]
  (when (every? #(need-met? world node %) needs)
    (let [[dx dy dz] to
          there [(+ x dx) (+ y dy) (+ z dz)]]
      (if fall?
        (when-let [{:keys [node fall]} (landing world there)]
          {:node node :cost (+ cost (* fall-cost-per-block fall))})
        {:node there :cost cost}))))

(defn neighbors
  "Every node reachable from node in one move, with the move's cost: [{:node :cost :move} ...].
   A drop and a walk to the same cell cannot both apply (a walk needs a floor the drop forbids)."
  [world node]
  (into [] (keep (fn [m] (some-> (apply-move world node m) (assoc :move (:move/id m))))) moves))

;; ---------------------------------------------------------------- goals as data

(defmulti goal-done?
  "Is node where the goal wants the feet? Open on :goal/kind."
  (fn [goal _node] (:goal/kind goal)))

(defmulti heuristic
  "An admissible estimate of the cost from node to the goal. Open on :goal/kind."
  (fn [goal _node] (:goal/kind goal)))

(defn octile
  "Octile distance on the plane between two cells: diagonals at their real cost."
  [[ax _ az] [bx _ bz]]
  (let [dx (abs (- ax bx)) dz (abs (- az bz))]
    (+ (* diagonal-cost (min dx dz)) (- (max dx dz) (min dx dz)))))

(defn flat-distance
  "Octile on the plane plus the height difference: the heuristic every goal with a point uses."
  [[_ ay _ :as a] [_ by _ :as b]]
  (+ (octile a b) (abs (- ay by))))

(defmethod goal-done? :near [{:goal/keys [pos range]} node]
  (<= (flat-distance node pos) (or range 3)))
(defmethod heuristic :near [{:goal/keys [pos range]} node]
  (max 0.0 (- (flat-distance node pos) (or range 3))))

(defmethod goal-done? :block [{:goal/keys [pos]} node] (= node pos))
(defmethod heuristic :block [{:goal/keys [pos]} node] (flat-distance node pos))

(defmethod goal-done? :xz [{:goal/keys [pos]} [x _ z]] (= [x z] [(first pos) (nth pos 2)]))
(defmethod heuristic :xz [{:goal/keys [pos]} node] (octile node pos))

(defmethod goal-done? :away [{:goal/keys [pos range]} node] (> (flat-distance node pos) range))
(defmethod heuristic :away [{:goal/keys [pos range]} node] (max 0.0 (- range (flat-distance node pos))))

;; ---------------------------------------------------------------- the search

(defn route
  "The nodes from the start to node, start first, read back through the parents map."
  [parents node]
  (vec (reverse (take-while some? (iterate parents node)))))

(defn search
  "A* from start over the world toward goal, spending at most max-nodes expansions. Returns
   {:path/waypoints :path/cost :path/status}: :found when a node satisfies the goal, else
   :partial (the budget ran out) or :none (the frontier did) with the route to the node that
   came nearest by the heuristic. The waypoints omit the start. The open set is a sorted set of
   [f g node], so ties break on the node itself and the search is deterministic."
  [world start goal max-nodes]
  (let [h0 (heuristic goal start)
        done (fn [status parents best g]
               {:path/waypoints (vec (rest (route parents best)))
                :path/cost (double (get g best 0.0))
                :path/status status})]
    (loop [open (sorted-set [h0 0.0 start])
           g {start 0.0}
           parents {start nil}
           closed #{}
           best start best-h h0
           expanded 0]
      (cond
        (empty? open) (done :none parents best g)
        (>= expanded max-nodes) (done :partial parents best g)
        :else
        (let [[_ gn node :as entry] (first open)
              open (disj open entry)]
          (cond
            (closed node) (recur open g parents closed best best-h expanded)
            (goal-done? goal node) (done :found parents node g)
            :else
            (let [closed (conj closed node)
                  hn (heuristic goal node)
                  [best best-h] (if (< hn best-h) [node hn] [best best-h])
                  [open g parents]
                  (reduce (fn [[open g parents] {next :node cost :cost}]
                            (let [g' (+ gn cost)]
                              (if (and (not (closed next)) (< g' (get g next Double/MAX_VALUE)))
                                [(conj open [(+ g' (heuristic goal next)) g' next]) (assoc g next g') (assoc parents next node)]
                                [open g parents])))
                          [open g parents]
                          (neighbors world node))]
              (recur open g parents closed best best-h (inc expanded)))))))))

(defn feet-cell
  "The node a player at feet position [x y z] stands in."
  [[x y z]]
  [(long (Math/floor x)) (long (Math/floor y)) (long (Math/floor z))])

(defn plan
  "A route for the feet from the cell `from` toward goal: {:path/waypoints [[x y z] ...]
   :path/cost n :path/status :found|:partial|:none}. opts: {:path/max-nodes n} (default
   default-max-nodes). Pure and deterministic: equal inputs, equal output. A start that is not
   standable (the bot is mid-jump, or in a plant) is searched from anyway; a goal in an unloaded
   chunk comes back :partial toward it, which is the walker's cue to move and plan again."
  ([world from goal] (plan world from goal {}))
  ([world from goal {:path/keys [max-nodes] :or {max-nodes default-max-nodes}}]
   (search world from goal max-nodes)))
