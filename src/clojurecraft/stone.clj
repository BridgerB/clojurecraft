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
            [clojure.string :as str]
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

(defn exposed-near
  "The blocks of states within search-radius of the eye and search-height of its height that
   the bot can stand beside, not blacklisted, nearest first, fluid or no fluid beside them (see
   exposed-of-states). Read from the loaded terrain (the live view), not memory: stone is most of
   the world and ore is in every chunk, and only what is exposed now is worth a route. The chunks
   are scanned as they arrived, so each candidate is asked again through the overlay of block
   updates and the bot's own breaks: a block just dug is air. Never one beside a lava trap still
   in force (path/near-lava-trap?): the lava that caught the bot there is still coming down."
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
         (memory/nearest-first eye search-radius))))

(defn exposed-of-states
  "The blocks of states (exposed-near) whose dig would expose no fluid (intent/exposes-fluid?),
   nearest first."
  [world states]
  (remove #(intent/exposes-fluid? world %) (exposed-near world states)))

(def six-around [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]])

(defn planks-held
  "The first planks item the inventory holds n or more of, or nil: a block that does not fall,
   the last thing a seal is laid from."
  [world n]
  (->> (recipe/counts (:player/inventory world))
       (filter (fn [[item c]] (and (str/ends-with? (name item) "_planks") (<= n c))))
       (map first)
       first))

(defn placeable-cell?
  "Does the server let a block take the place of the one at pos: air, a plant it replaces, or a
   fluid (blocks/placeable-into?)? false for a cell not loaded."
  [world pos]
  (boolean (some-> (terrain/block-at world pos)
                   blocks/placeable-into?)))

(defn seal-stone
  "A :place of a filler into the water beside the nearest stone of the row in sight that the dig
   refuses only for that water, or nil: the stone has one to three water cells beside it (six
   neighbours; no lava, no gravel or sand over a fluid), each within the place reach of the eye with
   a solid neighbour to place against (place/support) and not given up (:plan/blacklist), a route
   reaches it once sealed (routable?), and a filler is held for all of them (stairs/filler, else
   planks). Once the water is a block the stone is exposed to the walk, dig and collect chain as
   any other. Issue #342 (the R1/30 sim moment): the bot stood on the west bank of a cave lake
   beside two stones whose north sides were water, held three planks and no filler, and the dig
   refused both; it cut stairs down and climbed out again for the whole window, and its filler,
   the cobblestone, was what the stone would have given it. Every wet cell is one a block takes
   the place of (blocks/placeable-into?): a kelp plant holds water too, and sim race
   39186569902 (issue #456, R1/24 seed 2) laid the seal into one three times, :rejected."
  [world row]
  (let [eye (game/eye world)
        blacklist (:plan/blacklist world #{})
        reachable? (fn [w] (<= (physics/distance eye (physics/centre w)) place/place-reach))]
    (some
     (fn [c]
       (let [wet (vec (for [d six-around :let [n (mapv + c d)] :when (terrain/fluid-at world n)] n))
             item (when (and (seq wet) (every? #(placeable-cell? world %) wet))
                    (or (stairs/filler world (count wet)) (planks-held world (count wet))))
             target (first wet)]
         (when (and item
                    (<= (count wet) 3)
                    (every? #(= :water (terrain/fluid-kind world %)) wet)
                    (not (intent/fluid-over-falling? world c))
                    (not (intent/bed-over-fluid? world c))
                    (every?
                     #(and (reachable? %) (place/support world %) (not (contains? blacklist %)))
                     wet)
                    (intent/routable? world
                                      {:intent/kind :walk
                                       :intent/target c
                                       :intent/for (:goal/id row)
                                       :intent/pickup? true}))
           {:intent/kind :place
            :intent/item item
            :intent/cell target
            :intent/target target
            :intent/for :seal-stone})))
     (take max-candidates
           (filter #(intent/exposes-fluid? world %) (exposed-near world (states-of-row row)))))))

(defn continue-mine
  "The next intent of the row's chain in flight, or nil: walk → dig → collect, tagged
   :intent/for the row's id so neither a walk for another purpose nor another row's chain is
   ever continued as this one's dig."
  [world row]
  (intent/chain-next (:plan/last world) (:goal/id row) (drops-of-row row) {}))

(defn walk-to
  "A walk tagged for to the end of a route from which the block at t is dug and its drop picked up
   (:intent/pickup?), a step down into the hole it leaves when it is a block of the floor
   (:intent/step-down?)."
  [world t for]
  (cond-> {:intent/kind :walk :intent/target t :intent/for for :intent/pickup? true}
    (= (inc (physics/y-of t)) (physics/y-of (beside world t))) (assoc :intent/step-down? true)))

(def filler-want 8)                  ; filler a mine tops up to by hand once a staircase died boxed: a seal and a floor or two
(def filler-states
  "Every state of the blocks dug by hand for filler: dirt, and the grass over it."
  (into #{} (mapcat blocks/states-of) [:dirt :grass_block]))
(def stone-filler-row
  "The blocks that give a filler to a pick: stone and the stones that drop themselves."
  {:goal/blocks [:stone :cobblestone :granite :diorite :andesite :tuff]})
(def filler-drops
  "What digging a block of filler-states or of stone-filler-row gives."
  (into #{(blocks/items :dirt)} (drops-of-row stone-filler-row)))

(defn filler-states-for
  "The states a boxed mine digs for filler: dirt and the grass over it, and, when a pickaxe is
   held (nothing is missing for stone-filler-row), the stones as well: a strip of granite over open
   air, or a hillside of stone, is filler too."
  [world]
  (cond-> filler-states
    (nil? (tool-missing world stone-filler-row)) (into (states-of-row stone-filler-row))))

(defn filler-dig
  "A walk to the nearest block of filler-states-for that a route reaches and its dig exposes no
   fluid to (exposed-of-states), tagged :filler for the chain that digs it and picks it up, or nil:
   only once a staircase died :boxed here or before (:plan/boxed-at) and less than filler-want
   filler is carried (stairs/carried-filler). A staircase refused for the water beside it is sealed
   from what is carried, and the lake shores it was refused on gave the bots two dirt to seal with
   (issues #342 and #65, real bot Clj_r15 landed at R1/30): the dirt they stood on was filler. Real
   bots Clj_r15 (R1) and Clj_r16 (R3, issue #37) dug the dirt they could reach, climbed it away,
   and failed :boxed with stone and granite round them."
  [world]
  (when (and (seq (:plan/boxed-at world)) (< (stairs/carried-filler world) filler-want))
    (->> (exposed-of-states world (filler-states-for world))
         (take max-candidates)
         (map #(walk-to world % :filler))
         (filter #(intent/routable? world %))
         first)))

(defn log-planks
  "A :craft of a held log into its planks in the inventory's grid, or nil: only once a staircase
   died :boxed (:plan/boxed-at), with less than filler-want filler carried (stairs/carried-filler),
   no planks held (stairs/planks-of) and a log whose planks recipe is known. Planks are the last
   thing a floor is laid from (stairs/filler), and a log is four of them. Issue #37 (real bot
   Clj_r16, a one-wide strip of granite over open air): the planks and the dirt were laid into a
   climb, a birch log was held, and nothing else could be dug or laid."
  [world]
  (let [held (recipe/counts (:player/inventory world))
        recipe-id (some (fn [[item c]]
                          (let [n (name item)]
                            (when (and (pos? c) (str/ends-with? n "_log"))
                              (keyword (str (subs n 0 (- (count n) 4)) "_planks")))))
                        held)]
    (when (and recipe-id
               (seq (:plan/boxed-at world))
               (< (stairs/carried-filler world) filler-want)
               (nil? (stairs/planks-of held 1))
               (recipe/by-id recipe-id))
      {:intent/kind :craft :intent/recipe recipe-id :intent/window :inventory})))

(defn adjoins-boxed?
  "Do the feet [x y z] stand in :plan/boxed-at or on a cell beside one on the plane, at the same
   height: a staircase, tunnel or deeper stair started there ends :boxed as the one beside it
   did. A corridor or strip with nothing to lay was cut again from the cell next to the last
   one that died, a cell a lap, for the whole window (issues #37, #71 and #62's causeway and
   pillar cases); from there the order falls through to the walk home, the retreat and the
   climb. The cells a boxed climb crossed (:plan/boxed-route) count as boxed too: the bot's own
   stairs-up ends :boxed away from the cell that died, and the next stairs-down began from there
   (issue #37, one-wide strips of floor over open air)."
  [world [x y z :as feet]]
  (let [boxed (into (:plan/boxed-at world #{}) (:plan/boxed-route world))]
    (boolean (or (contains? boxed feet)
                 (some (fn [[dx dz]] (contains? boxed [(+ x dx) y (+ z dz)])) stairs/directions)))))

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
    (when (and (< to feet) (not (adjoins-boxed? world (intent/feet world))))
      (cond-> {:intent/kind :stairs-down :intent/to-y to}
        (and depth (= :mine act)) (assoc :intent/floor-to depth)))))

(def over-radius 3)                   ; blocks on the plane round the surface over stone within which the feet count as over it

(defn surface-over
  "The standable cell on top of the column of cell [x _ z]: above the highest solid block that
   is not leaves, the body and head clear (path/standable?); nil when the column has none.
   Leaves are no ground to cut a staircase down from: sim race 39550831606 world R4/25 (issue
   #85): a bot with a wooden pickaxe, no filler and a health of one walked to the surface over
   its stone, which was the top of a birch's canopy, every stair from there was refused for the
   leaves it opened onto, and it walked back to the canopy after each level step for the whole
   window."
  [world [x _ z]]
  (some (fn [y]
          (let [id (terrain/block-at world [x y z])]
            (when (and id (blocks/solid? id) (not (blocks/leaves? id)))
              (let [c [x (inc y) z]] (when (path/standable? world c) c)))))
        (range terrain/top-y (dec chunk/min-y) -1)))

(defn under-surface?
  "Are the feet [fx fy fz] within reach of the surface cell [sx sy sz] a staircase was cut from: on
   the plane within over-radius, plus a block for each block the feet are below it, since a
   staircase down gains a block along its heading for each block it drops. Sim race 39593216223
   (issue #62, world R4/2): the stairs cut from the surface over the stone went six down and six
   along, the feet then 6 from the surface, outside over-radius, and the walk back up to the
   surface began the same staircase again every twelve seconds until the run stalled."
  [[fx fy fz] [sx sy sz]]
  (<= (physics/horizontal-distance [fx 0 fz] [sx 0 sz])
      (+ over-radius (max 0 (- (long sy) (long fy))))))

(defn over-stone
  "A walk to the surface over the nearest stone of the row in sight that no route reaches, for the
   staircase to be cut from there, or nil: when the feet are not already under-surface? of the
   surface over one of the max-candidates nearest, when none has a surface a route
   reaches (routable?), and never to a surface within over-radius of a cell of :plan/boxed-at,
   where a staircase has died :boxed already. Issue #342 (a real bot, Clj_r15, landed at R1/30):
   stone lay in the wall of a cave lake, five blocks under the far bank; the mine cut its stairs
   down at the near bank, where every stair was refused for the water, up and down the same stairs
   until the plan failed :boxed, and the surface over the stone, eight blocks off along a strip of
   grass, was routable all the while (a-stone-no-route-reaches-is-cut-down-to-from-the-surface-over-it).
   A staircase cut from the surface over the stone moves a block along for every block down, so
   the feet count as over the surface within over-radius plus the blocks they are under it:
   sim race 39598424082 (issue #349, R1/35 seed 1) cut six stairs east from the surface, stood
   six along, and walked back up them to cut them again, a hundred times in the window."
  [world row]
  (let [feet (intent/feet world)
        near? (fn [[ax ay az] [bx by bz]]
                (<= (physics/horizontal-distance [ax 0 az] [bx 0 bz])
                    (+ over-radius (max 0 (- by ay)))))
        surfaces (->> (exposed-of-states world (states-of-row row))
                      (take max-candidates)
                      (keep #(surface-over world %))
                      distinct)]
    (when-not (some #(under-surface? feet %) surfaces)
      (->> surfaces
           (remove (fn [c] (some #(near? c %) (:plan/boxed-at world #{}))))
           (map (fn [c] {:intent/kind :walk :intent/target c :intent/for :over-stone}))
           (filter #(intent/routable? world %))
           first))))

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
    (when-not (adjoins-boxed? world feet)
      (first (filter (fn [d]
                       (let [cells (stairs/level-cells feet d)]
                         (and (not= back d)
                              (stairs/level-safe? world cells)
                              (some #(some-> (terrain/block-at world %)
                                             blocks/solid?)
                                    cells))))
                     ranked)))))

(def retreat-distance 12)             ; blocks a retreat walks from where nothing could be cut

(defmethod path/goal-done? :unboxed-away
  [_ {:goal/keys [pos range boxed]} node]
  (and (not (contains? boxed node)) (> (path/flat-distance node pos) range)))

(defmethod path/heuristic :unboxed-away
  [{:goal/keys [pos range]} node]
  (max 0.0 (- range (path/flat-distance node pos))))

(def deeper-slack 24) ; blocks under a row's depth the stairs deeper go no farther: the ore lies below it too, but not at the bottom

(defn deeper
  "Stairs stair-depth below the feet when no tunnel can be cut here, the row's depth notwithstanding
   (ore lies below it too, no more than deeper-slack under it: sim R1/1, probe 40791377318, went
   six lower each time from y 3 to y -37 for iron, a row whose depth is 16, and tunnelled there
   twenty minutes with none; only once the portal kit is reached: the iron kit's own search
   goes as deep as it must, snapshot #62 R1-12-1 found its iron below y -8 and timed out at the
   ingots when bounded there), when a stair from here can be cut at all and the bottom of the world is
   not near; else nil, and nil from a feet cell in :plan/boxed-at, where a staircase died :boxed
   already, as stairs is: a descent from there dies the same way whatever its depth. Cut means
   dug too: no cell of the stair borders water or lava
   (stairs/level-safe?), which the dig refuses; or cast, its floor laid from what is carried
   (stairs/lay). Gym batch iron-a6 run 3 was sent down stairs that could not be cut and failed
   :boxed three times in a second; batch iron-a8 run 3 down stairs whose every cell bordered
   water, and failed :boxed twice in a second."
  ([world] (deeper world nil))
  ([world {:goal/keys [depth]}]
   (let [feet (intent/feet world)
         to (- (physics/y-of feet) stair-depth)
         cuttable? (fn [d]
                     (let [cells (stairs/stair-cells feet d)]
                       (or (stairs/level-safe? world cells)
                           (and (stairs/level-clear? world cells) (stairs/lay world feet cells)))))]
     (when (and (not (adjoins-boxed? world feet))
                (> to (+ chunk/min-y floor-margin))
                (or (nil? depth)
                    (not (contains? (:plan/reached world #{}) :portal-kit))
                    (>= to (- depth deeper-slack)))
                (some cuttable? stairs/directions))
       {:intent/kind :stairs-down :intent/to-y to}))))

(defn retreat
  "Away from where nothing can be cut: out of water first when the feet are in it (a :swim-out for
   :retreat, done on dry land), else a walk to the end of a route at least retreat-distance
   away (path's :unboxed-away goal), never onto cells already in :plan/boxed-at or
   :plan/boxed-route, or a retreat waypoint already marked :plan/retreat-unreachable.
   Sim race 39044508577 (issue #161, R1/29) retreated between two boxed cells for hours.
   From a boxed cell, when the search exhausts
   the reachable ground short of that distance, use its last unboxed waypoint instead, or with
   no waypoint at all the cell the search began on when that is another unboxed cell (the
   box rests on the shore beside the ice it stands on: sim race 39074156402, issue #71, world
   R2/35, a cove between a cliff and a frozen lake); nil when there is no way. Gym batch iron-a6
   run 3's tunnel at y 16 ran along an aquifer until water filled its floor, and every tunnel and
   stair from there was refused."
  [world]
  (if (:player/in-water? world)
    {:intent/kind :swim-out :intent/for :retreat}
    (let [feet (intent/feet world)
          boxed (into (:plan/boxed-at world #{})
                      (concat (:plan/boxed-route world) (:plan/retreat-unreachable world)))
          r
          (path/plan
           world
           (path/feet-cell (:player/pos world))
           {:goal/kind :unboxed-away :goal/pos feet :goal/range retreat-distance :goal/boxed boxed})
          start (:path/from r)
          target (cond (= :found (:path/status r)) (last (:path/waypoints r))
                       (and (= :none (:path/status r)) (contains? boxed feet))
                       (or (last (remove boxed (:path/waypoints r)))
                           (when-not (or (= feet start) (contains? boxed start)) start)))]
      (when target {:intent/kind :walk :intent/target target :intent/for :retreat}))))

(defn dead-end-retreat
  "retreat from where nothing could be cut, the feet cell it leaves named a dead end
   (:intent/dead-end) when it is a walk: the planner puts it in :plan/boxed-at as the walk starts,
   so the staircase cut from above is refused its last stair there (stairs/clear-way?) and
   turns. Sim race 39593216223 (issue #62, world R4/42): at the mine's depth, y 16, with an
   aquifer round, no tunnel, stair or deeper stair could be cut; the retreat walked up the
   staircase, the next descent was the same staircase down to the same cell, and the two
   alternated until the run stalled. nil when there is no retreat."
  [world]
  (when-let [r (retreat world)]
    (cond-> r (= :walk (:intent/kind r)) (assoc :intent/dead-end (intent/feet world)))))

(defn islet?
  "Do the feet stand on a solid block with water on all four sides of it, a block in the sea
   that no route leaves?"
  [world]
  (let [[x y z] (intent/feet world)]
    (boolean (and (some-> (terrain/block-at world [x (dec y) z])
                          blocks/solid?)
                  (every? (fn [[dx dz]]
                            (= :water (terrain/fluid-kind world [(+ x dx) (dec y) (+ z dz)])))
                          [[1 0] [-1 0] [0 1] [0 -1]])))))

(defn cross-home
  "A :cross toward the landing (:plan/go-at), swum, from a block in the sea (islet?) under the open
   sky where a climb out already died :boxed (:plan/boxed-at); nil elsewhere. Real race
   (issue #71, snapshot real-3-s2-Clj_r19): a bot that dug up into the sea from a mine under it
   rose and swam forty-five blocks to the one block that stood in the water, every stair up
   from it was refused for the water on all sides, and the mine's last resort, the climb, died
   :boxed there three times."
  [world]
  (let [feet (intent/feet world)]
    (when-let [[hx hy hz] (:plan/go-at world)]
      (when (and (contains? (:plan/boxed-at world #{}) feet)
                 (islet? world)
                 (terrain/under-sky? world feet))
        {:intent/kind :cross
         :intent/target [(long (math/floor hx)) (long (math/floor hy)) (long (math/floor hz))]}))))

(def home-walk-distance 12)           ; feet this far or nearer the landing have nothing to walk home for

(defn walk-home
  "A walk to the landing (:plan/go-at) when the feet stand where a climb already died :boxed
   (:plan/boxed-at) and the landing is routable on foot and farther than home-walk-distance;
   nil elsewhere. A bridge or causeway over open water or air is no islet (islet? wants water on
   all four sides) and no stair is cut from it, but it leads to the shore the bot came from.
   Sim worlds R4/1 and R2/38 (issue #62's four snapshots, which fail `[:ingots :timeout]`): a bot
   with no filler left on a one-wide dirt strip stepped level along it, was :boxed at the limit,
   retreated twelve blocks back, and stepped level again, for the whole window."
  [world]
  (let [feet (intent/feet world)]
    (when-let [[hx hy hz] (:plan/go-at world)]
      (let [home [(long (math/floor hx)) (long (math/floor hy)) (long (math/floor hz))]
            walk {:intent/kind :walk :intent/target home :intent/for :retreat}]
        (when (and (adjoins-boxed? world feet)
                   (> (physics/distance (physics/centre feet) (physics/centre home))
                      home-walk-distance)
                   (intent/routable? world walk))
          walk)))))

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

(defn off-ice
  "On a sheet of ice (path/on-ice?), a walk toward the landing (:plan/go-at) over it, the route
   search taking the sheet for floor of a bot that stands on one; nil off ice, with no landing or
   no route toward it, or when a walk to it failed (the landing is in :plan/blacklist: the walk's
   route was accepted and then could not be walked). Issue #71, race 39088700340 bot Clj_r02 rose
   out of the sea floor under a frozen sea onto the ice, and every act that followed (stairs down
   for the mine, level steps over the water) cut and laid on the sheet until it was :boxed.
   Race 39181272601 bot Clj_r23 (issue #40): hunting on a glacier, it was sent 300 blocks over
   the sheet to a landing in chunks not loaded, the route partial; the walk ended :no-path at
   the edge of what was loaded, and was chosen again at the same place twelve times, :no-path."
  [world]
  (when-let [[hx hy hz] (and (path/on-ice? world) (:plan/go-at world))]
    (let [target [(long (math/floor hx)) (long (math/floor hy)) (long (math/floor hz))]
          walk {:intent/kind :walk :intent/target target :intent/for :off-ice}]
      (when (and (not (contains? (:plan/blacklist world #{}) target)) (intent/routable? world walk))
        walk))))

(def fall-wait-ticks 20)              ; planner ticks the mine waits for feet over a gap to fall before it plans anyway

(defn unsupported?
  "Are the feet over nothing a body rests on: not afloat, with air, water or lava in the cell
   under them (path/classify), so the next tick drops them? Issue #105 (sim race 39550841573,
   R2/32 seed 3): the bot dug the ice under its feet to scoop the lake and the water source
   with it, and the planner, the instant the bucket was full, cut stairs from the lip of that
   hole: every heading was refused for the water in the cells of its floor, four turns in four
   ticks, :boxed before the body had fallen the one block into the hole it stood over."
  [world]
  (let [[x y z] (intent/feet world)]
    (boolean (and (:player/pos world)
                  (not (:player/in-water? world))
                  (not (:player/in-lava? world))
                  (contains? #{:clear :water :lava} (path/classify world [x (dec y) z]))))))

(defn footing
  "A walk onto the nearest cell beside the feet that stands (path/standable?), a block up or down
   too, when the feet are over a gap (unsupported?), or nil: ending in that cell, since a body
   that rests on the sliver of its box over a neighbour's edge falls no further and goes no
   further. The same moment as unsupported?, a block of ice dug out under the feet two ticks
   before, but the bot lay on the rim of the hole and no stair could be planned from it, nor a
   retreat found from a cell with no floor: it waited :boxed to the end of its twenty seconds."
  [world]
  (when (unsupported? world)
    (let [[x y z] (intent/feet world)
          [px _ pz] (:player/pos world)]
      (->> (for [dx [-1 0 1]
                 dz [-1 0 1]
                 dy [0 1 -1]
                 :when (not (and (zero? dx) (zero? dz)))]
             [(+ x dx) (+ y dy) (+ z dz)])
           (filter #(path/standable? world %))
           (sort-by (fn [[cx _ cz]]
                      (physics/horizontal-distance [px 0 pz] [(+ cx 0.5) 0 (+ cz 0.5)])))
           (map (fn [c] {:intent/kind :walk :intent/target c :intent/for :footing}))
           (filter #(intent/routable? world %))
           first))))

(defn settle
  "What the mine does with feet over a gap (unsupported?): wait for the fall, at most
   fall-wait-ticks planner ticks, then step onto ground that stands (footing); nil when the feet
   have a floor, or neither is there to do."
  [world]
  (when (and (unsupported? world) (not (path/near-lava-trap? world (intent/feet world))))
    (if (< (:plan/waiting-ticks world 0) fall-wait-ticks)
      {:plan/wait :unsupported}
      (footing world))))

(defn mine-next
  "The next step of mining a row: continue its chain; else, holding the tool its blocks need
   (and first putting it in hand when it is only in the main inventory), walk to the nearest exposed one a route reaches (of the max-candidates nearest; a walk that
   ends where the drop lands in reach, :intent/pickup?), or cut stairs down toward them, or at
   the row's depth a tunnel (tunnel-heading), or deeper stairs when none can be cut, or a retreat
   when neither can, or a climb out when there is no way away either (climb); wait for the
   tool. The live runs that waited at the depth with nothing in sight failed there; a tunnel's
   walls are where the ore shows. Beside a lava trap still in force (path/near-lava-trap?) the
   retreat comes before any stair or tunnel: sim race 38844683780 (issue #230) cut its stairs down
   where the lava had just caught it, and the flow followed it down. So does a swim out of water
   the feet are in: sim race 39878751984 (issue #105, R1/11 seed 1) chased a mob into a lake, and
   from the water, twice, planned stairs down that every water cell refused, each :boxed in a
   tick, which with the failed redig before them used the plan's attempts."
  [world row]
  (or (continue-mine world row)
      (intent/chain-next (:plan/last world) :filler filler-drops {})
      (let [missing (tool-missing world row)
            [_ stowed-item :as stowed] (intent/stowed-tool world (first (states-of-row row)))
            walk (when-not missing
                   (->> (exposed-of-states world (states-of-row row))
                        (take max-candidates)
                        (map #(walk-to world % (:goal/id row)))
                        (filter #(intent/routable? world %))
                        first))]
        (cond missing {:plan/wait missing}
              stowed {:intent/kind :equip :intent/item (recipe/item-name stowed-item)}
              walk walk
              :else
              (or (when (path/near-lava-trap? world (intent/feet world)) (retreat world))
                  (when (:player/in-water? world) (retreat world))
                  (off-ice world)
                  (seal-stone world row)
                  (over-stone world row)
                  (filler-dig world)
                  (log-planks world)
                  (stairs world row)
                  (when-let [d (tunnel-heading world)]
                    {:intent/kind :tunnel :intent/dir d :intent/length tunnel-length})
                  (deeper world row)
                  (walk-home world)
                  (dead-end-retreat world)
                  (cross-home world)
                  (climb world)
                  {:plan/wait :boxed})))))

(defmethod plan/act :mine [world row] (or (settle world) (mine-next world row)))

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

(defn foothold
  "The child :place of a filler block (stairs/filler) into a cell under the feet's level, one of
   the four beside the feet column or the one under it, that a click can reach (place/support)
   and that is no part of the player, so that the cell over it is a spot for a block to be
   placed and dug again; nil when no filler is held or no such cell is there. Sim race
   39768353246 (issue #349, R4/2): a bot out on a stub of cobblestone over the sea, no cell
   beside it with a floor under it and no route off, had its redig answered by a stairs up that
   was done at once under the open sky, again and again for fifteen minutes."
  [world]
  (when-let [item (stairs/filler world 1)]
    (let [[fx fy fz] (intent/feet world)
          eye (game/eye world)
          cell (first (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1] [0 0]]
                            :let [c [(+ fx dx) (dec fy) (+ fz dz)]
                                  over [(+ fx dx) fy (+ fz dz)]
                                  t (terrain/block-at world c)
                                  o (terrain/block-at world over)]
                            :when (and t
                                       o
                                       (blocks/replaceable? t)
                                       (blocks/replaceable? o)
                                       (not (intent/exposes-fluid? world over))
                                       (terrain/confirmed? world c)
                                       (place/support world c)
                                       (not (place/inside-player? world c))
                                       (<= (physics/distance eye (physics/centre c))
                                           place/place-reach))]
                        c))]
      (when cell {:intent/kind :place :intent/item item :intent/cell cell :intent/for :foothold}))))

(defn hold-top
  "The cell over a block already laid beside the feet column, one below the feet, where the
   redig's own block goes: replaceable, in reach, with a face to click (place/support), not in
   refused and not a dig that would let fluid in (intent/exposes-fluid?). nil when none is there.
   place/spot will not choose it when a drop lies past it (a stub over the sea, issue #349): the
   foothold (foothold) is the laying, and this is the gravel on it."
  [world refused]
  (let [[fx fy fz] (intent/feet world)
        eye (game/eye world)]
    (first (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
                 :let [under [(+ fx dx) (dec fy) (+ fz dz)]
                       over [(+ fx dx) fy (+ fz dz)]
                       t (terrain/block-at world under)
                       o (terrain/block-at world over)]
                 :when (and t
                            o
                            (blocks/solid? t)
                            (blocks/replaceable? o)
                            (not (contains? refused over))
                            (not (intent/exposes-fluid? world over))
                            (place/support world over)
                            (not (place/inside-player? world over))
                            (<= (physics/distance eye (physics/centre over)) place/place-reach))]
             over))))

(defn redig-place
  "The child placement in a neighbouring cell the feet can stand in, level or one step up or
   down, or a retreat to fresh ground when none is there; under the open sky, where a stairs up
   would be done before it began, a foothold (foothold) laid for the placement instead, and once
   that block is there the gravel goes on it (hold-top) rather than another stairs up.
   A cell a dig would let fluid into (intent/exposes-fluid?), and any the redig has already
   refused (:intent/refused), is passed over. A floor that has a drop beside it but not past it
   is still a spot (place/spot's along?): the strict spot refuses it and the stairs up that
   followed was done the same tick, for the whole window (issue #349, R2/44). Issue
   #151's R3/14 and real runner 8 snapshots placed gravel through a wall or round a corner:
   within click reach, but not within pickup reach."
  [world item]
  (let [[fx fy fz] (intent/feet world)
        held-off (:intent/refused (:plan/intent world) #{})
        refused (into held-off
                      (for [[dx dy dz] place/around
                            :let [cell [(+ fx dx) (+ fy dy) (+ fz dz)]]
                            :when (or (intent/exposes-fluid? world cell)
                                      (not (and (= 1 (+ (abs dx) (abs dz)))
                                                (path/standable? world cell))))]
                        cell))
        cell (or (first (place/spot world true refused))
                 (first (place/spot world true refused true)))
        top (when-not cell (hold-top world held-off))
        retreat (when-not (or cell top) (place/fresh-rock world))]
    (cond cell {:intent/kind :place :intent/item item :intent/cell cell}
          top {:intent/kind :place :intent/item item :intent/cell top}
          (and (= :stairs-up (:intent/kind retreat)) (stairs/out? world [fx fy fz]))
          (or (foothold world) retreat)
          :else retreat)))

(defmethod redig-stage :place
  ;; a place that found no spot afloat is not the redig's failure: a :swim-out for :retreat
  ;; (done on dry land) takes the child's place, and the place is started again from the bank
  [world {:intent/keys [item child] :as i} event]
  (cond (afloat-no-spot? world child)
        (intent/assoc-intent world
                             :intent/child
                             {:intent/kind :swim-out :intent/for :retreat :intent/status :active})
        (and (or (contains? #{:swim-out :walk :stairs-up} (:intent/kind child))
                 (= :foothold (:intent/for child)))
             (intent/done? child))
        (intent/dissoc-intent world :intent/child)
        :else
        (link
         world
         i
         event
         (when-not child
           (or (redig-place world item) {:intent/kind :swim-out :intent/for :retreat}))
         (fn [w placed]
           (intent/assoc-intent w :intent/stage :dig :intent/target (:intent/target placed))))))

(defmethod redig-stage :dig
  ;; a dig the stair rules refuse (:unsafe, fluid beside the cell) is not the redig's failure:
  ;; back to :place with that cell in :intent/refused. Three such digs in a row ended the plan
  ;; (issue #171, real-8). One, recovered from, still gathers (real-5).
  [world {:intent/keys [target child refused] :as i} event]
  (if (and (intent/failed? child) (= :unsafe (:intent/reason child)))
    (-> world
        (assoc :player/controls {})
        (intent/assoc-intent :intent/stage :place :intent/refused (conj (set refused) target))
        (intent/dissoc-intent :intent/child :intent/target))
    (link world
          i
          event
          (intent/child-dig target)
          (fn [w _] (intent/assoc-intent w :intent/stage :collect)))))

(defmethod redig-stage :collect
  [world {:intent/keys [target drops] :as i} event]
  (link world
        i
        event
        {:intent/kind :collect :intent/target target :intent/drops drops}
        (fn [w _] (intent/done w))))

(defmethod intent/run :redig [world i event] (redig-stage world i event))
