(ns clojurecraft.stairs
  "Going down safely: the :stairs-down intent, which cuts a staircase one stair at a time in the
   direction it faces until the feet are at or below a height, and never digs the block under
   the feet (a blind drop is how the siblings' bots died). Where a stair has no floor under its
   new feet (the staircase broke into a cave) and a block to lay one from is carried, the floor
   is laid first, from cobblestone, against the block under the feet or against a holder laid
   under that, and the stair is then cut and taken like any other: a staircase cast through a
   cave.

     {:intent/kind :stairs-down :intent/to-y 60 :intent/dir [1 0]}

   A stair opens three cells ahead (head-up, head, feet, so the body can walk down a block)
   and is walked onto. Before any of them is dug every cell the stair exposes is checked on
   the world value: water, lava or an unloaded chunk in any of them, lava beside any of them,
   or no floor under the new feet (a drop) refuses the stair; a refusal turns the intent a
   quarter turn; so does a cell the held tool cannot drop (an ore under a wooden pickaxe: gym
   batch cobble-a2 run 2 failed three stairs in a second on one). When all four headings refuse,
   the staircase takes a level step instead, head and feet cells one block along a heading
   that allows it, and tries the stairs again from there: a live descent stood over an
   underground lake that refused every stair and failed :boxed three times before this. After
   max-level-steps such steps it fails :boxed.

     {:intent/kind :tunnel :intent/dir [1 0] :intent/length 12}

   A tunnel is level steps alone, length of them along its heading, turning where one is
   refused: where a mining row has reached its depth with nothing in sight, the walls of a
   tunnel are where the ore shows.

     {:intent/kind :stairs-up :intent/to-y 64}

   Stairs up are the same staircase climbed: each opens the cell over the head (the jump needs
   it), then the new head and feet a block up and ahead, with the same refusals, and none under
   gravel or sand that would fall onto the bot. Done standing at to-y or out of the ground: the
   way out of a mine whose way down no longer leads back up.
   Each dig is a child :dig intent run by the dig
   executor itself (intents composing intents: no second rule for breaking blocks), and the
   descent is judged by the feet having actually gone down, never by a packet having been
   sent, since a dig the server refused leaves the bot where it stood."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def max-turns 4)                     ; refusals before a level step, or :boxed (one full turn)
(def max-level-steps 32)              ; level steps around what refuses every stair before :boxed
(def step-timeout-ticks 200)          ; ticks a walk down one stair may take (10 s)
(def creep-speed 0.06)                ; blocks a tick: the walk onto a stair with nothing beyond it to stop the body pushes only under this
(def directions [[1 0] [0 1] [-1 0] [0 -1]]) ; the four headings, each a quarter turn from the last
(def shore-reach 48)                  ; columns along a heading a climb looks for dry ground over
(def climb-stairs 8)                  ; stairs a climb cuts before the planner looks again, as a descent cuts stone/stair-depth

(defn climb-to
  "The height a climb from the feet aims at next: to-y, or climb-stairs up when that is nearer.
   A climb is cut in lengths like a descent, so the planner looks again between them: a spare
   pickaxe is made while one still digs, and the table is taken along. Race 38477665442 bots
   Clj_r23 and Clj_r06 climbed 26 and 53 stairs out of the mine in one intent, the climb that a
   stone pickaxe's 131 digs do not cover, and wore both pickaxes out on the way with sticks and
   cobblestone in hand (issue #39). stairs/out? still ends any length under the open sky."
  [world to-y]
  (min to-y (+ (physics/y-of (intent/feet world)) climb-stairs)))

(defn turn
  "The heading a quarter turn from dir."
  [dir]
  (nth directions (mod (inc (.indexOf ^java.util.List directions dir)) 4)))

(defn stair-cells
  "The three cells a stair ahead of feet [x y z] in direction [dx dz] opens, top first:
   head-up, head and the new feet cell one block down."
  [[x y z] [dx dz]]
  [[(+ x dx) (inc y) (+ z dz)] [(+ x dx) y (+ z dz)] [(+ x dx) (dec y) (+ z dz)]])

(defn tall-stair-cells
  "The cells a stair ahead of feet [x y z] in direction [dx dz] opens for a body n cells tall to
   walk down it (mob/tall), top first: the new feet a block down and the n cells over them. A
   body steps off at its old height before it falls, so the column it steps into must be clear
   from the new feet to n over the old ones: stair-cells is the player's (n 2), and an enderman
   (3) needs four. Gym batch pearls-warped2-b (plan run 39182782203, probe 39206015361): under a
   hill every provoked enderman stood on the first three-high stair, stepped no lower, wandered
   off a ledge, and six held fights in six timed out :unreachable."
  [[x y z] [dx dz] n]
  (mapv (fn [dy] [(+ x dx) (+ y dy) (+ z dz)]) (range (dec n) -2 -1)))

(defn up-cells
  "The three cells a stair up ahead of feet [x y z] in direction [dx dz] opens, top first: the one
   over the head, which the jump needs, then the new head and the new feet a block up and ahead.
   The block ahead at the feet's height is the step it climbs onto."
  [[x y z] [dx dz]]
  [[x (+ y 2) z] [(+ x dx) (+ y 2) (+ z dz)] [(+ x dx) (inc y) (+ z dz)]])

(defn level-cells
  "The two cells a level step ahead of feet [x y z] in direction [dx dz] opens, top first: head
   and feet, at the same height."
  [[x y z] [dx dz]]
  [[(+ x dx) (inc y) (+ z dz)] [(+ x dx) y (+ z dz)]])

(defn exposed
  "Every cell a stair's (or a level step's) digging exposes to the body: the cells it opens, the
   floor under the new feet (the last cell), and the four horizontal neighbours of each opened
   cell."
  [cells]
  (let [[fx fy fz] (last cells)]
    (distinct (concat cells
                      [[fx (dec fy) fz]]
                      (for [[x y z] cells [ddx ddz] directions] [(+ x ddx) y (+ z ddz)])))))

(defn clear-way?
  "May a stair's (or a level step's) cells be opened, the floor aside: none of them is water,
   lava or unloaded, and no lava is beside or under them. A new feet cell (the last) in
   :plan/boxed-at is refused: a staircase died :boxed standing there, and stepping back onto it
   digs the same pocket again."
  [world cells]
  (let [kind #(path/classify world %)]
    (and (not (contains? (:plan/boxed-at world #{}) (last cells)))
         (every? #(not (#{:water :lava :unknown} (kind %))) cells)
         (every? #(not= :lava (kind %)) (exposed cells)))))

(defn safe?
  "May a stair (or a level step) be cut: its cells may be opened (clear-way?) and a floor lies
   under the new feet, the last cell (the step is one block, not a drop)."
  [world cells]
  (let [[fx fy fz] (last cells)]
    (and (clear-way? world cells) (= :solid (path/classify world [fx (dec fy) fz])))))

(def fillers
  "The items a floor is laid from, what a mine carries most of first: cobblestone, the stones
   that drop themselves, then dirt, then netherrack (what a descent in the Nether digs: gym batch
   pearls-warped-e, plan run 39164914828, run 3 had every stair refused in a pocket with no
   filler to lay), then the Nether's own rock a tunnel digs and carries (sim snapshot R3-38-1,
   issue #410, held 55 soul soil and no filler). Never gravel or sand, which fall out of a floor."
  [:cobblestone :cobbled_deepslate :andesite :diorite :granite :tuff :dirt :netherrack :soul_soil
   :blackstone :basalt])

(defn planks-of
  "The first planks item among the held item counts that has n or more, or nil: a block that does
   not fall, the last thing a floor or a seal is laid from."
  [held n]
  (->> held
       (filter (fn [[item c]] (and (>= c n) (str/ends-with? (name item) "_planks"))))
       (map first)
       first))

(def last-resorts
  "Blocks that do not fall and are laid when nothing in fillers is held: the bricks a bastion's
   loot gives and the soul sand a floor of the Nether is dug out as. A tunnel dug by hand drops
   nothing, so these were all some bots held (sim snapshots R2-17-1 and R2-12-1, issue #410)."
  [:polished_blackstone_bricks :cracked_polished_blackstone_bricks :soul_sand])

(defn filler
  "The first of fillers the inventory holds n or more of, else a planks item it holds n or more
   of, else the first of last-resorts it holds n or more of, or nil. Planks and last-resorts are
   the fallbacks only: carried-filler and so every stair down's reserve count none, so a descent
   never spends them. Issue #37 (real bot Clj_r16, on a one-wide strip over open air holding
   three planks, sticks and no other block): nothing to lay, and every climb out was :boxed."
  [world n]
  (let [held (recipe/counts (:player/inventory world))]
    (or (first (filter #(>= (get held % 0) n) fillers))
        (planks-of held n)
        (first (filter #(>= (get held % 0) n) last-resorts)))))

(def lay-reserve 8)                    ; filler a staircase down never lays into floors: what the climb back seals and steps with

(defn climb-reserve
  "The filler a seal keeps from feet for the climb back: a block for each block the feet are
   under the highest rock (not leaves) of their column, up to lay-reserve, since the climb out
   seals and steps once a stair. A bot at the surface keeps none and a bot eight stairs down all
   of it. Issue #65 (a real bot, Clj_r15, landed at R1/30): one stair down from a lake shore,
   holding two dirt, the one block of water beside the next stair was never sealed, for the
   eight the climb back would need from nowhere, and every stair was refused until the staircase
   was :boxed."
  [world [x y z]]
  (if-let [top (first (filter (fn [yy]
                                (let [id (terrain/block-at world [x yy z])]
                                  (and id (blocks/solid? id) (not (blocks/leaves? id)))))
                              (range terrain/top-y (inc y) -1)))]
    (min lay-reserve (- (long top) (long y)))
    0))

(defn carried-filler
  "How many of every filler the inventory holds, together."
  [world]
  (reduce + 0 (vals (select-keys (recipe/counts (:player/inventory world)) fillers))))

(defn floors-affordable?
  "May a stair down lay to-lay (a floor and what holds it) before it is cut: only when the filler
   carried would floor the rest of the way down at this stair's rate and still
   keep lay-reserve for the climb back. A staircase that breaks into a cave lays a floor under
   every stair from then on; started with too little it ends mid-cave on a bridge of its own with
   nothing left to lay, and every stair, up or down, is refused for the air under it. Race
   38984748832 runner 3 bot Clj_r01 (issue #37): the mine's stairs down broke into a cave at y 50
   holding 16 cobblestone and 25 dirt, floored thirty-one stairs down through the open cave, and at
   y 19 stood on a one-block dirt bridge with none left, :boxed. The way's end is the mine's depth
   (:intent/floor-to) when the staircase is one of a mine's, cut a few stairs at a time toward it,
   else its own to-y. Any other kind, or neither, may."
  [world {:intent/keys [kind to-y floor-to]} [_ y _] to-lay]
  (let [end (or floor-to to-y)]
    (or (not= :stairs-down kind)
        (nil? end)
        (>= (carried-filler world)
            (+ lay-reserve (* (count to-lay) (max 1 (- (long y) (long end)))))))))

(def bridge-depth 16)                 ; blocks under a floor to be laid looked down through for a fluid

(defn over-fluid?
  "Is the first block under cell [x y z] that is not air (within bridge-depth) water or lava: a
   floor laid there would be a bridge over it?"
  [world [x y z]]
  (boolean (#{:water :lava}
            (first (remove #{:clear}
                           (map #(path/classify world [x % z])
                                (range (dec y) (- y bridge-depth) -1)))))))

(defn bridging?
  "Is the intent running one that may lay a floor over a fluid: a descent toward the trees
   (:stairs-down, :intent/for :trees or :valley)? Its way down ends at the water, where the swim
   to the trunk takes over (wood/crossing); the mine's stairs never bridge."
  [world]
  (let [{:intent/keys [kind for]} (:plan/intent world)]
    (and (= :stairs-down kind) (contains? #{:trees :valley} for))))

(defn floor-laying
  "The cells to lay, in order, so that a floor lies under the new feet of a stair (or a level
   step) from feet whose floor is missing: the floor cell alone when a solid block borders it to
   place against (place/support); else first a holder, the cell under the block under the feet,
   placed against that block's underside, and then the floor against the holder, the way a
   staircase is cast through a cave from a ceiling one block thick. [] when the floor is solid
   already; nil when it is water, lava or unloaded, or nothing can hold it, or when it is over
   water or lava (over-fluid?): a floor over a fluid is a bridge, not a cave's floor. Sim race 38657453715
   world R4/1 (issue #62) cut stairs down a mountain onto a lake, and the staircase's level steps
   laid a floor against the last one, twenty-six blocks out over the water, until its
   cobblestone was spent; every stair from the causeway was then :boxed, and it walked back and
   forth along it for 75 minutes until the run's time ran out."
  [world [x _ z] cells]
  (let [[nx ny nz] (last cells)
        floor [nx (dec ny) nz]
        holder [x (dec ny) z]
        kind #(path/classify world %)]
    (cond (= :solid (kind floor)) []
          (or (not= :clear (kind floor)) (and (over-fluid? world floor) (not (bridging? world))))
          nil
          (place/support world floor) [floor]
          (and (= :clear (kind holder)) (place/support world holder)) [holder floor]
          :else nil)))

#_{:splint/disable [naming/conversion-functions]} ; "the cells to lay", not a conversion
(defn cells-to-lay
  "The cells to lay (laid, in order) before a stair (or a level step) whose cells may be opened
   (clear-way?) can be cut, when enough of a filler is carried; nil when nothing is to lay or
   none can be."
  [world cells laid]
  (when (clear-way? world cells)
    (when-let [cells (seq laid)] (when (filler world (count cells)) (vec cells)))))

(def max-void-reserve 16)              ; most filler a bridge over a deep void keeps, twice lay-reserve

(defn void-rise
  "How many cells straight up from cell [x y z] are not solid, at most max-void-reserve: the
   height of open air over a bridge's end, a block of pillar each to climb out of it."
  [world [x y z]]
  (count (take max-void-reserve
               (take-while #(not (some-> (terrain/block-at world [x % z])
                                         blocks/solid?))
                           (iterate inc (inc (long y)))))))

(defn bridge-reserve
  "The filler a level step of a stair down keeps after its floor is laid: lay-reserve, or the
   open air over the step's feet cell (void-rise) when that is more, up to max-void-reserve,
   since the climb back out of a void rises a block a block laid (the :pillar). Issue #37 (real
   bot Clj_r16): a bridge kept eight and was :boxed ten blocks short of the roof it hung under."
  [world cells]
  (max lay-reserve (void-rise world (last cells))))

(defn floor-reserve
  "The filler a floor laid for cells (the last is the new feet cell) keeps for the way back: the
   bridge-reserve for a descent, or for a row's deeper stairs, which start with no stairs intent;
   lay-reserve for a climb, whose own steps are the way back. Issue #37 (real bot Clj_r16,
   snapshot real-7-s2): `lay` kept eight whatever hung over the floor, and the descent floored
   eleven levels and a bridge over a void with sixteen dirt, the way back a block a level and a
   block a cell, and was :boxed on the bridge with none."
  [world cells]
  (if (= :stairs-up (:intent/kind (:plan/intent world))) lay-reserve (bridge-reserve world cells)))

(defn lay
  "The cells to lay, in order, before a stair down (or a level step) from feet whose cells may
   be opened (clear-way?) can be cut: the floor and what holds it (floor-laying), when enough of
   a filler is carried; nil when the stair needs none (safe?) or none can be laid. Race
   38477665442: five of the eight bots on runner 8 that stopped :boxed stood on the lip of a
   cave their staircase had broken into, 7 to 284 cobblestone in hand, every stair refused for
   the drop under it and nothing else to cut from the lip (issue #37). A floor never eats into
   lay-reserve, whichever way down lays it (a staircase, its level steps, the mine's deeper
   stairs): that much is kept for the climb back, which seals and steps with it (issue #37 again:
   race 38984748832 runner 3 bot Clj_r01 floored a cave down to nothing and was :boxed on its own
   bridge; probe 38988294166, its stairs budgeted, still reached y 16 with none, by the mine's
   other descents)."
  [world feet cells]
  (when-let [laid (cells-to-lay world cells (floor-laying world feet cells))]
    (when (>= (- (carried-filler world) (count laid)) (floor-reserve world cells)) laid)))

#_{:splint/disable [naming/conversion-functions]} ; "the step to lay", not a conversion
(defn step-to-lay
  "The cells to lay so that a stair up has its step, the block ahead at the feet's height, which
   its new feet cell (the last) stands on: [] when it is solid already; the step alone when it
   is clear and a solid block borders it to place against (place/support); else a holder under
   the step, beside the floor the feet stand on, then the step. nil when either cell is water,
   lava or unloaded, or nothing can hold it."
  [world cells]
  (let [[nx ny nz] (last cells)
        step [nx (dec ny) nz]
        holder (update step 1 dec)
        kind #(path/classify world %)]
    (cond (= :solid (kind step)) []
          (not= :clear (kind step)) nil
          (place/support world step) [step]
          (and (= :clear (kind holder)) (place/support world holder)) [holder step]
          :else nil)))

(defn falls-over?
  "Would gravel or sand fall through a stair up's first cell, the one over the head, onto the
   bot: does the block over it fall (blocks/falls?)?"
  [world cells]
  (let [[x y z] (first cells)]
    (boolean (some-> (terrain/block-at world [x (inc y) z])
                     blocks/falls?))))

(defn up-safe?
  "May a stair up be cut: what any stair needs (safe?), and no gravel or sand over the cell above
   the head, which would fall through it onto the bot (falls-over?)."
  [world cells]
  (and (safe? world cells) (not (falls-over? world cells))))

(defn step-lay
  "The cells to lay before a stair up from feet can be cut: its step (step-to-lay), when enough
   of a filler is carried and the stair would be cut once it is laid: nothing falls onto the bot
   from over the cell above the head (falls-over?) and no cell it opens borders water or lava,
   which the child dig refuses (intent/exposes-fluid?), so no step is laid for a stair the dig
   then turns from. nil when the stair needs none or none can be laid. Issue #61 (R3 world 6,
   104 sim runs a round): a climb out from under an aquifer at y 37 walked thirty level steps
   along its own mine at y 36, a floor laid under each, since every stair up from the mine had
   no step under its new feet, and was :boxed at the level-step limit; a stair up now lays its
   step as a stair down lays its floor."
  [world _feet cells]
  (when (and (not (falls-over? world cells)) (not-any? #(intent/exposes-fluid? world %) cells))
    (cells-to-lay world cells (step-to-lay world cells))))

(defn water-beside
  "The water cells among the four horizontal neighbours of each of cells (path/around; water
   alone: lava beside a stair refuses it, clear-way?), distinct, in the cells' order; what the
   child dig would let in from the side (intent/exposes-fluid?), and what a seal lays a block
   into."
  [world cells]
  (distinct (for [[x y z] cells
                  [dx _ dz] path/around
                  :let [n [(+ x dx) y (+ z dz)]]
                  :when (= :water (terrain/fluid-kind world n))]
              n)))

(defn water-over?
  "Does a fluid stand straight over or under any of cells, or rest on gravel or sand over one
   (intent/fluid-over-falling?)? A lake overhead is not sealed from below: the climb steps out
   from under it toward the shore (step-aside) as before."
  [world cells]
  (boolean (some (fn [[x y z :as cell]]
                   (or (terrain/fluid-kind world [x (inc y) z])
                       (terrain/fluid-kind world [x (dec y) z])
                       (intent/fluid-over-falling? world cell)))
                 cells)))

(defn up-sealed?
  "May a stair up be cut as it stands: up-safe?, and none of its cells borders water, which the
   child dig would refuse to open (:unsafe, intent/exposes-fluid?)."
  [world cells]
  (and (up-safe? world cells) (not-any? #(intent/exposes-fluid? world %) cells)))

(defn seal
  "The water cells beside a stair up's cells to lay a filler into (water-beside; a block placed
   into a source takes its place), before the stair is cut as any other: when the stair is
   otherwise allowed (up-safe?), the water is beside its cells alone (not water-over?), each
   has a solid neighbour to place against (place/support) and enough of a filler is carried;
   nil when the stair needs none (up-sealed?) or cannot be sealed. Race 38477665442 runner 8
   bot Clj_r01 (issue #54): underground with its bucket, an aquifer round the shaft put water
   beside every stair cell of the climb out; each child dig refused :unsafe, the staircase was
   :boxed twice, and twelve walks to that water failed :no-path."
  [world _feet cells]
  (when (and (up-safe? world cells) (not (up-sealed? world cells)) (not (water-over? world cells)))
    (let [cells (vec (water-beside world cells))]
      (when (and (seq cells) (every? #(place/support world %) cells) (filler world (count cells)))
        cells))))

(defn wet-feet
  "The water cells beside the feet a stair up is cut from (the cell under its first cell, two
   below the cell over the head), when water stands in those feet: what feeds it. [] when the
   feet are dry or nothing beside them is water."
  [world cells]
  (let [[x y z] (first cells)
        feet [x (- (long y) 2) z]]
    (if (= :water (terrain/fluid-kind world feet)) (vec (water-beside world [feet])) [])))

(defn up-dry?
  "May a stair up be cut as it stands: up-sealed?, and no water feeds the feet it is taken from
   (wet-feet). From feet in water a jump only bobs, never a block up onto the step, and the step
   is never taken. Race 38988094276 runner 3 bot Clj_r05 (issue #320): the portal build's stairs
   up started in its own tunnel at y 41 with flowing water over the floor, fed from a source two
   cells west; three stairs bobbed from y 41.0 to 41.36 for ten seconds each and failed
   :stair-not-taken, and the build with them."
  [world cells]
  (and (up-sealed? world cells) (empty? (wet-feet world cells))))

(defn neighbours
  "The four horizontal neighbours of cell [x y z], as cells."
  [[x y z]]
  (for [[dx _ dz] path/around] [(+ x dx) y (+ z dz)]))

(defn dry-feet
  "The water cells beside wet feet to lay a filler into before a stair up is cut (wet-feet), so
   the water under the feet is cut off and drains: when each has a solid neighbour to place
   against (place/support) and enough of a filler is carried; nil when none are wanted or can be
   laid. A cell with no solid neighbour of its own (the mouth of a water-filled hole) is laid after
   a water cell beside it that has one (the helpers come first, and the lay stage places in
   order). Issue #130 (real bot Clj_r18, in a flooded pocket one block deep with the hole of the
   water beside its feet): the climb south was allowed in every way but the feet in water, which
   no filler was laid to dry because one of the three cells had no support, so it was :boxed
   three times in 600 ms."
  [world _feet cells]
  (let [wet (wet-feet world cells)
        [x y z] (first cells)
        feet [x (- (long y) 2) z]
        helper (fn [[ux uy uz :as u]]
                 (when-not (place/support world u)
                   (first (for [[dx _ dz] path/around
                                :let [n [(+ ux dx) uy (+ uz dz)]]
                                :when (and (not= n feet)
                                           (not-any? #{n} wet)
                                           (= :water (terrain/fluid-kind world n))
                                           (place/support world n))]
                            n))))
        helpers (vec (distinct (keep helper wet)))
        all (into helpers wet)]
    (when (and (seq wet)
               (every? #(or (place/support world %) (some (set helpers) (neighbours %))) wet)
               (filler world (count all)))
      all)))

(defn up-lay
  "The cells to lay before a stair up from feet can be cut: the seal over the water beside its
   cells (seal, issue #54) when there is any, else the water that feeds wet feet (dry-feet, issue
   #320), else its missing step (step-lay, issue #61); nil when the stair needs none or none can
   be laid. The seal comes first: a step laid beside open water is a stair the child dig then
   refuses (intent/exposes-fluid?)."
  [world feet cells]
  (or (seal world feet cells) (dry-feet world feet cells) (step-lay world feet cells)))

(defn down-safe?
  "May a stair down be cut as it stands: safe?, and none of its solid cells exposes a fluid the
   child dig would refuse. Open cells need no dig, even beside water."
  [world cells]
  (and (safe? world cells)
       (not-any? #(and (some-> (terrain/block-at world %)
                               blocks/solid?)
                       (intent/exposes-fluid? world %))
                 cells)))

(defn down-lay
  "The cells to lay before a stair down: seal side water first, else lay its missing floor.
   nil when neither can be laid without spending lay-reserve. A seal uses the same support and
   fluid checks as a climb's, but keeps the filler the climb needs to escape.
   Race 39007359500 runner 5 bot Clj_r06 (issue #342) turned from water beside every descent,
   then waited :boxed on a dry shelf with no route away, five cobblestone and twenty dirt held."
  [world feet cells]
  (let [sealed (seal world feet cells)
        laid (or sealed (lay world feet cells))]
    (when (and laid
               (>= (- (carried-filler world) (count laid))
                   (if sealed (climb-reserve world feet) lay-reserve)))
      laid)))

(def edge-drop 2)                     ; cells under the feet cell a floor is looked for in before a lay the body clips is refused

(defn unfooted?
  "Is the feet cell [x y z] over a drop: no floor (path/floor?) in the edge-drop cells under it,
   or water or lava the first thing under it (over-fluid?)? The body then stands on a
   neighbour's edge, its middle over the drop."
  [world [x y z]]
  (or (not-any? #(path/floor? world [x (- y %) z]) (range 1 (inc edge-drop)))
      (over-fluid? world [x y z])))

(defn backs-off?
  "Would laying cells from feet step the body off its footing: a cell the body clips
   (place/inside-player?), which the place steps clear of toward the feet cell's middle
   (place/nudge-off), while that cell is unfooted?. Gym batch pearls-warped8-c (plan run
   39845223845, probe 39854206523) run 2 stood at x 2541.24 on the edge of a nylium ledge, its
   feet cell open eleven blocks down to a lava lake; a stair up toward -x laid its step at x 2540,
   the step clipped the body, the nudge walked it over the edge, and the plan failed :no-footing."
  [world feet cells]
  (boolean (and (some #(place/inside-player? world %) cells) (unfooted? world feet))))

(def shapes
  "How each kind of staircase is cut: the cells a stair opens (top first, the new feet last), the
   rule that lets it be cut, which way the feet go when it is taken, and what to lay before it
   can be: for stairs down a seal or its missing floor and holder (down-lay), for stairs up a
   seal over the water beside its cells, else its missing step (up-lay)."
  {:stairs-down {:cells stair-cells :safe? down-safe? :rise -1 :lay down-lay}
   :stairs-up {:cells up-cells :safe? up-dry? :rise 1 :lay up-lay}})

(defn tool-missing
  "The reason to stop, :no-pickaxe, when the block at cell is rock that would be dug by hand for
   nothing: it drops only to a pickaxe and none is held at all, which happens when the pickaxe
   breaks on the way. Stone by hand takes 7500 ms a block; batch iron-a2 runs went on that way,
   a stair at a time, until a step timed out. A pickaxe too weak for the block is the child
   dig's :needs-tool instead, which turns the stairs. Only the pickaxe counts: the staircase
   cuts rock and what drops is not its concern, so a block that drops only to another tool is
   dug by hand, in the time its hardness gives (snow, 200 ms). Race 38477665442: every bot
   landed in snow (runners 2, 6 and 10, eight landings) failed :no-shovel on its first stair,
   a snow block among the cells, a wooden pickaxe in hand (issues #42 and #72). nil when it
   can be dug."
  [world cell]
  (let [id (terrain/block-at world cell)]
    (when (and id
               (= :pickaxe (blocks/tool-of id))
               (blocks/drops-need-tool? id)
               (not-any? (fn [[_ {:keys [item]}]] (= :pickaxe (:kind (blocks/tool item))))
                         (:player/inventory world)))
      :no-pickaxe)))

(defn too-hard?
  "Is the block at cell one that needs a tool tier (blocks/needs-tier) no held item reaches (ore
   above the pickaxe's tier)? Dug, it breaks for nothing, so the dig refuses it :needs-tool. Rock
   with no pickaxe at all is tool-missing's instead: the stairs stop for one."
  [world cell]
  (let [id (terrain/block-at world cell)]
    (boolean (and id
                  (blocks/needs-tier id)
                  (not-any? (fn [[_ {:keys [item]}]] (dig/harvest? id item))
                            (:player/inventory world))))))

(defn level-clear?
  "May a level step's cells be opened, the floor aside: clear-way?, and no cell it opens borders
   water or lava (intent/exposes-fluid?) or is too hard for the held tools (too-hard?), which
   the child dig would refuse to open. Gym batch iron-a9 run 4's tunnel at y 2 met ore its stone
   pickaxe could not drop and failed :needs-tool three times in a moment."
  [world cells]
  (and (clear-way? world cells)
       (not-any? #(or (intent/exposes-fluid? world %) (too-hard? world %)) cells)))

(defn level-safe?
  "May a level step (or a stair whose cells are given) be cut: its cells may be opened
   (level-clear?) and a floor lies under its feet cell (safe?)."
  [world cells]
  (and (level-clear? world cells) (safe? world cells)))

(def six-around
  "The six cells that share a face with a cell, as offsets."
  [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]])

(defn dug-cells
  "The cells among cells that hold a solid block: what a level step has to dig. An open cell needs
   no dig, so water beside it lets nothing in."
  [world cells]
  (filterv #(some-> (terrain/block-at world %)
                    blocks/solid?)
           cells))

(defn fluids-beside-dug
  "The fluid cells (water or lava) among the six neighbours of each cell a level step digs
   (dug-cells), distinct: what the dig would let in (intent/exposes-fluid?), and what a seal lays
   a block into."
  [world cells]
  (distinct (for [[x y z] (dug-cells world cells)
                  [dx dy dz] six-around
                  :let [n [(+ x dx) (+ y dy) (+ z dz)]]
                  :when (terrain/fluid-at world n)]
              n)))

(defn level-lay
  "The cells to lay, in order, before a level step from feet whose only fault is water beside the
   cells it digs, which the child dig refuses (:unsafe): that water filled with a block (a block
   placed into a source takes its place), then the floor the step lacks (floor-laying). nil when
   the step is otherwise refused (level-clear?'s water, lava or unloaded cells, ore too hard for
   the tools held, a fluid over gravel above a cell), when any fluid beside is lava or is the
   cell the body stands in or breathes in, when a cell to lay has nothing solid to place against
   (place/support) or no filler is carried for all of them, or when nothing is to lay (the step
   is level-safe? or none can be laid). Open cells are not dug, so water beside them is left: the
   body wades nothing, it steps onto a floor. Issue #105 (sim race 39081494214 R1/31, 39074159590
   R3/44): the bot climbed up under a lake for its water bucket, and the water it let in stood
   round the shaft's last cells, beside every step: each level step was refused for the water
   beside it, the staircase failed :boxed three times in a second with 306 and 514 cobblestone
   held, and the plan with it."
  [world [fx fy fz :as feet] cells]
  (let [[nx ny nz] (last cells)
        floor [nx (dec ny) nz]
        wet (vec (fluids-beside-dug world cells))
        floored (if (or (= :solid (path/classify world floor)) (some #{floor} wet))
                  []
                  (floor-laying world feet cells))
        laid (into wet floored)]
    (when (and floored
               (seq laid)
               (clear-way? world cells)
               (not-any? #(too-hard? world %) cells)
               (not-any? #(or (intent/fluid-over-falling? world %) (intent/bed-over-fluid? world %))
                         (dug-cells world cells))
               (every? #(= :water (terrain/fluid-kind world %)) wet)
               (not-any? #{[fx fy fz] [fx (inc fy) fz]} wet)
               (every? #(place/support world %) wet)
               (filler world (count laid)))
      laid)))

(defn reserve-level-lay
  "The cells level-lay would lay before a level step of a staircase of kind from feet, nil for a
   stair down when laying them would leave less than bridge-reserve filler (a stair up's step is
   the climb the reserve is for). Issue #37 (real bot Clj_r16, snapshot real-7-s2, and sim
   R3/15): a descent over a void was refused its floors at the reserve (lay), then level-lay
   floored fifteen level steps of a bridge with the same dirt and the three planks, past the
   reserve, to none, and every stair up from the bridge's end was :boxed with nothing to lay."
  [world kind cells feet]
  (when-let [laid (level-lay world feet cells)]
    (when (or (not= :stairs-down kind)
              (>= (- (carried-filler world) (count laid)) (bridge-reserve world cells)))
      laid)))

#_{:splint/disable [naming/conversion-functions]} ; "the next cell to open", not a conversion
(defn next-to-open
  "The first of the stair's cells (top first) still in the body's way, or nil when the stair is
   open: a full cube, or a flat box too (blocks/collision-top: a carpet), which the head meets
   going up the jump. Sim race 39593216223 (issue #66, world R2/39): a moss carpet over the head
   was no cube, the stair was called open, and the jump rose into the carpet's box three times
   for ten seconds each, :stair-not-taken."
  [world cells]
  (first (filter #(some-> (terrain/block-at world %)
                          blocks/collision-top)
                 cells)))

(defn boxed?
  "Has the staircase turned as often as it may: one more refusal fails it :boxed."
  [turns]
  (>= (inc turns) max-turns))

(defn turned
  "The staircase a quarter turn from dir, the turn counted, and why said."
  [world dir turns why]
  (-> world
      (intent/assoc-intent :intent/dir (turn dir) :intent/turns (inc turns))
      (game/say why)))

(defn shaft-cells
  "The two cells a shaft up opens from feet [x y z], lowest first: the one over the head, which the
   next block under the feet lifts the body into, and the one over that, which the :pillar's own
   headroom check reads once the block is in and the body has risen. After the first level the
   lower is open already, so a level digs one."
  [[x y z]]
  [[x (+ y 2) z] [x (+ y 3) z]])

(defn pickaxe-held?
  "Does the inventory hold a pickaxe of any material?"
  [world]
  (boolean (some (fn [[_ {:keys [item]}]] (= :pickaxe (:kind (blocks/tool item))))
                 (:player/inventory world))))

(defn shaft-safe?
  "May the shaft's cells (shaft-cells) be dug (clear-way?: no water, lava or unloaded chunk in or
   beside them), with no gravel or sand over them to fall on the bot, no fluid straight over
   them and none beside a solid one for the dig to let in? A cell already open is no dig: water
   beside it is beside the feet too, and the pillar only lays blocks. Sim race 39912745526 world
   R1/26 (issue #130): on a cobblestone block over a cave's lake, the air over the head open and
   a column of water beside it, the shaft was refused for the water, every stair was refused
   for the floor it lacked, and the climb failed :boxed three times."
  [world feet]
  (let [cells (shaft-cells feet)]
    (and (clear-way? world cells)
         (not (falls-over? world [(second cells)]))
         (not (water-over? world cells))
         (not-any? #(and (= :solid (path/classify world %)) (intent/exposes-fluid? world %))
                   cells))))

(def pillar-extras
  "What a pillar rises on when no filler is held: the blocks that fall out of a floor laid over
   air but stay where they are put on a solid block, as a pillar's every block is."
  [:gravel :sand :red_sand])

(defn pillar-item
  "The item a shaft's :pillar is laid from, or nil: a filler (filler), else planks, else one of
   pillar-extras held. Gravel and sand are the last resort, never laid into a floor (fillers)."
  [world]
  (let [held (recipe/counts (:player/inventory world))]
    (or (filler world 1) (first (filter #(pos? (get held % 0)) pillar-extras)))))

(defn pillar-up?
  "May a boxed stair up go up as a shaft of pillars from feet (a pillar-item held, a solid block
   under the feet and the feet centred in it, the cells over the head safe to dig), the intent not
   having given the shaft up (:intent/no-shaft)? Issue #37 (sim snapshots R3-15-1 and R3-15-2): on
   mineshaft planks at y -1 over open air holding 39 gravel, every stair and level step was refused
   for the floor it lacked, with a pillar of the gravel over the planks the way up."
  [world {:intent/keys [kind no-shaft]} feet]
  (boolean (and (= :stairs-up kind)
                (not no-shaft)
                (pillar-item world)
                (= :solid (path/classify world (update feet 1 dec)))
                (intent/centred? world feet)
                (shaft-safe? world feet))))

(defn shaft-up
  "world with the intent in the :shaft stage from here, a child gone, saying why."
  [world why]
  (-> world
      (assoc :player/controls {})
      (intent/assoc-intent :intent/stage :shaft
                           :intent/started-y (physics/y-of (:player/pos world))
                           :intent/started-tick (:time/tick world))
      (intent/dissoc-intent :intent/child :intent/cells :intent/lay :intent/level-cells)
      (game/say why)))

(defn box
  "Every heading was refused: a stair up with a block to pillar from goes up as a shaft instead
   (pillar-up?, the :pillar intent dragon.clj defines, called as it is); else fail the staircase
   :boxed. A climb out to the surface (:intent/for
   :surface, the water hunt's and the gather's) ends instead, its feet cell put in
   :plan/boxed-at: where a way out cannot be cut is a fact about the place, and the act that cut
   it chooses again from it (scoop/seen-source, scoop/from-below), never the same climb. Sim race
   38662908438 (issue #61, 115 runs): climbs for water under a lake or the sea were :boxed three
   times with the water they needed in reach or over the head. A cut toward the bank of water
   no route reaches (:intent/for :bank, scoop/to-bank) ends too, and the water it headed for
   (:intent/target) is put in :plan/blacklist, so the next look chooses other water, or none and
   a climb out: race 38805762352 runner 2 (issue #61, at 76010d2) had bot Clj_r18 at y 30 in a
   cave, 80 under its landing, tunnel toward cave water 15 blocks off across the cave's floor,
   and bot Clj_r22 tunnel at y 63 from the top of the steps its climb to that height had laid in
   a cavern, toward pools 55 blocks off through the air; each tunnel was :boxed three times in a
   row, the cut toward the water keeping the climb out from being chosen, and the plan failed.
   A cut toward lava (:intent/for :lava, build/toward-lava) ends too, its feet cell in
   :plan/boxed-at and the source it headed for given up (memory/give-up-site, which the hunt's
   sources-near leaves out, with the pool round it): sim races 39085720764 and 39085577169 (issue #130, worlds R1/19
   and R1/25) had the tunnel :boxed three times in a tick toward the one source, and the plan
   failed."
  [world]
  (let [{:intent/keys [kind for target] :as i} (:plan/intent world)
        feet (intent/feet world)]
    (cond (pillar-up? world i feet)
          (shaft-up world "stairs: every way refused, a shaft of pillars up")
          (and (= :stairs-up kind) (= :surface for))
          (-> world
              (assoc :player/controls {})
              (update :plan/boxed-at (fnil conj #{}) (intent/feet world))
              intent/done
              (game/say "stairs: boxed in, no way out from here"))
          (and (= :lava for) target)
          (-> world
              (assoc :player/controls {})
              (update :plan/boxed-at (fnil conj #{}) (intent/feet world))
              (memory/give-up-site target)
              intent/done
              (game/say
               (str "stairs: boxed in on the way to the lava at " target ", other lava next")))
          (and (= :bank for) target)
          (-> world
              (assoc :player/controls {})
              (update :plan/blacklist (fnil conj #{}) target)
              intent/done
              (game/say
               (str "stairs: boxed in on the way to the bank of " target ", other water next")))
          :else
          (-> world
              (assoc :player/controls {})
              (intent/fail :boxed)))))

(def lake-ice #{:ice :frosted_ice})    ; what freezes over a lake's water: no shore

(defn dry-top?
  "Is the top of column [x z] (its highest block that is solid or a fluid) solid: ground the open
   sky is over, not a lake or the sea? Ice is the lake's own top (lake-ice), not ground: sim race
   38659291345 world R4/38 (issue #37) climbed under a lake half frozen over, took each patch of
   ice for the shore, and stepped toward it every way in turn. false when the column is not
   loaded."
  [world [x z]]
  (true? (first (keep (fn [y]
                        (when-let [id (terrain/block-at world [x y z])]
                          (cond (or (blocks/fluid id) (lake-ice (blocks/name-of id))) false
                                (blocks/solid? id) true
                                :else nil)))
                      (range terrain/top-y (dec chunk/min-y) -1)))))

(defn shore-distance
  "How many columns along heading [dx dz] from feet the first with dry ground on top lies
   (dry-top?), within shore-reach; nil when none is loaded there."
  [world [x _ z] [dx dz]]
  (some #(when (dry-top? world [(+ x (* % dx)) (+ z (* % dz))]) %) (range 1 (inc shore-reach))))

(defn wet-head?
  "Is a fluid in column [x z] at any of the heights a stair up from feet [x y z] opens over the
   head or exposes to the one it opens: the head, the cell over it the jump needs, and the one
   over that, whose water the dig would let in (y + 1 to y + 3)?"
  [world [x y z]]
  (boolean (some #(terrain/fluid-at world [x % z]) (range (inc y) (+ y 4)))))

(defn dry-head-distance
  "How many columns along heading [dx dz] from feet the first loaded one lies with nothing wet
   over the head (wet-head?), within shore-reach; nil when none. Where a climb stands under an
   aquifer the top of every column is dry ground (shore-distance tells the headings apart only
   under a lake or the sea), and the water that refuses each stair is the sheet a block or two
   over the head: the way out from under it is the heading along which that sheet ends soonest.
   Issue #61 (R3 world 6, landing 3992,2040,69, 104 sim runs a round): a climb for water from
   y 37 stood under an aquifer whose floor was two blocks over its head, every stair up refused
   :unsafe for the water over the cell it opened, and its level steps, the top of every column
   dry, went north under the aquifer and were :boxed; the sheet ended three columns south."
  [world [x y z] [dx dz]]
  (some (fn [n]
          (let [c [(+ x (* n dx)) y (+ z (* n dz))]]
            (when (and (terrain/chunk-loaded? world c) (not (wet-head? world c))) n)))
        (range 1 (inc shore-reach))))

(defn floor-distance
  "How many columns along heading [dx dz] from feet [x y z] the first lies where a stair down
   would have its floor: the cell two under the feet's height solid, or clear with no fluid
   within bridge-depth under it (over-fluid?), so a floor could be laid there; within
   shore-reach; nil when none. In a cave over a lake sixteen blocks down every stair is refused
   for the water under its floor while a level step, one block higher, may lay its own, so the
   staircase creeps along at that one height: gym batch stronghold-room2-a (plan run
   39161847521) run 3 of the portal room stood at y 33 over such a lake, stepped 32 east laying
   a floor a step, and was :boxed three times; batch stronghold-room-a (plan run 39131390440)
   run 3 at the same cells found a stair on its 33rd step and passed."
  [world [x y z] [dx dz]]
  (some (fn [n]
          (let [c [(+ x (* n dx)) (- y 2) (+ z (* n dz))]
                k (path/classify world c)]
            (when (or (= :solid k) (and (= :clear k) (not (over-fluid? world c)))) n)))
        (range 1 (inc shore-reach))))

(defn level-step-limit
  "How many level steps a staircase of kind may take before it is :boxed: max-level-steps, and a
   climb as many more as the landing (:plan/go-at) is far from feet on the plane, since under a
   lake or the sea the way out may be all the way back: gym batch iron-a9 run 1 climbed under
   the sea 155 blocks from its landing and was :boxed after 32 steps three times. A descent with
   a floor for a stair in sight along some heading (floor-distance) has shore-reach more: the
   way across a cave over a lake to where the stairs go on may be longer than max-level-steps."
  [world kind feet]
  (let [home (:plan/go-at world)]
    (cond-> max-level-steps
      (and home (= :stairs-up kind)) (+ (long (physics/horizontal-distance feet home)))
      (and (= :stairs-down kind) (some #(floor-distance world feet %) directions))
      (+ shore-reach))))

(defn under-falling?
  "Would a climb from feet cell [x y z] be refused for gravel or sand over the cell above the
   head (up-safe?): does the block three up fall (blocks/falls?)?"
  [world [x y z]]
  (boolean (some-> (terrain/block-at world [x (+ y 3) z])
                   blocks/falls?)))

(defn duck
  "A climb's way out of a pocket every stair and level step of which is refused: a stair down
   (stair-cells, :intent/rise -1 for the step) along the heading whose head is dry soonest
   (dry-head-distance) of those whose stair is cut clear of water and lava (level-safe?), the
   pocket's feet cell added to :plan/boxed-at so no stair or step climbs back into it; or nil.
   Under the sheet over the pocket the level steps are allowed again, and the climb walks out
   from under it. Sim race 38659291345 (issue #37) worlds R1/33 and R4/38: a climb for wood
   stood in a pocket two cells long with an aquifer's sheet a block over the head, every stair
   up and every level step bordered water, and it failed :boxed three times; the sheet ended
   four columns off."
  [world {:intent/keys [level-steps visited] :or {level-steps 0 visited #{}}}]
  (let [feet (intent/feet world)
        ways (sort-by #(or (dry-head-distance world feet %) Long/MAX_VALUE) directions)]
    (when-let [d (first (filter #(level-safe? world (stair-cells feet %)) ways))]
      (-> world
          (update :plan/boxed-at (fnil conj #{}) feet)
          (intent/assoc-intent :intent/dir d
                               :intent/level-dir d
                               :intent/turns 0
                               :intent/level-steps (inc level-steps)
                               :intent/cells (stair-cells feet d)
                               :intent/visited (conj visited feet (last (stair-cells feet d)))
                               :intent/rise -1
                               :intent/stage :open
                               :intent/started-y (physics/y-of (:player/pos world))
                               :intent/started-tick (:time/tick world))
          (intent/dissoc-intent :intent/child :intent/lay :intent/level-cells)
          (game/say (str "stairs: boxed under water, a stair down toward " d))))))

(def bore-bed 3)                       ; rock blocks over the head a bore digs through at most (the eyes' reach to the water over them)
(def bore-depth 24)                    ; blocks of water over that rock a bore swims up through at most: 2.7 a second, nine of the fifteen seconds of breath

(defn handed?
  "Does the inventory hold no pickaxe at all: a bot that digs by hand, a block of stone 7500 ms?"
  [world]
  (not-any? (fn [[_ {:keys [item]}]] (= :pickaxe (:kind (blocks/tool item))))
            (:player/inventory world)))

(defn bore-cells
  "The cells a bore from feet [x y z] opens, bottom first: one to bore-bed blocks of rock over the
   head (from y + 2: none never dug; the gravel and sand of a lake's bed count, which fall into the
   cell below as it is dug, and are dug again) under a lake, whose water runs up from the rock for
   at most bore-depth blocks to open air. nil when the column over the head is not that: rock too
   thick, no water over it, or an aquifer's sheet with rock over it. The three bots of issues #69,
   #62 and #161 stood under beds of stone, then gravel or sand, under fourteen to twenty-three
   blocks of water."
  [world [x y z]]
  (let [cell (fn [dy] [x (+ y dy) z])
        kind #(path/classify world %)
        rock (vec (take-while #(= :solid (kind %)) (map cell (range 2 (+ 3 bore-bed)))))
        n (count rock)
        above (map cell (range (+ 2 n) (+ 3 n bore-depth)))
        [wet top] (split-with #(= :water (kind %)) above)]
    (when (and (<= 1 n bore-bed)
               (seq wet)
               (some-> top
                       first
                       kind
                       (= :clear))
               (not-any? #(contains? intent/never-dug (blocks/name-of (terrain/block-at world %)))
                         rock))
      rock)))

(defn bore-up
  "A climb out from under a lake with no pickaxe to cut a way along it: the stairs' stage :bore,
   digging bore-cells over the head by hand and then holding up the shaft under the water that
   comes down it, the swim-out (swim.clj) rising it to the surface. nil with a pickaxe held
   (its level steps are quick), or without a bed bore-cells accepts. Sim races (issues #69, #62,
   #161, #135; worlds R1/17, R3/45, R4/19, R3/30): bots with the pickaxe broken stood in a pocket
   under a lake bed two blocks thick and cut level steps toward a shore by hand all window,
   182 of them in a quarter of an hour, a block of stone 7500 ms."
  [world]
  (when (handed? world)
    (when-let [cells (bore-cells world (intent/feet world))]
      (-> world
          (assoc :player/controls {})
          (intent/assoc-intent :intent/bore cells
                               :intent/stage :bore
                               :intent/started-tick (:time/tick world))
          (intent/dissoc-intent :intent/child :intent/cells :intent/lay :intent/level-cells)
          (game/say (str "stairs: under a lake with no pickaxe, a bore up through " cells))))))

(defn step-aside
  "Every stair refused: a level step, the turns counted afresh, or :boxed when none is allowed
   or max-level-steps are spent. The step keeps the last one's way (:intent/level-dir) while it
   is allowed and never goes straight back, so the staircase walks out from under what refuses
   it: the ninth live run, cutting stairs up under water at y 51, stepped round a square of four
   cells until it failed :boxed three times. The first step takes the first heading allowed,
   from the staircase's own round a full turn; going up, nearest the shore first
   (shore-distance), since what refuses a climb is most often a lake or the sea overhead, and
   with no shore in sight toward the landing (:plan/go-at), which is land: gym batch iron-a7
   run 1, under a lake at y 44, stepped 32 east under it and failed :boxed; batch iron-a8 run 2,
   under the sea with no shore in sight, :boxed three times. Going down, the heading along
   which a stair's floor lies nearest first (floor-distance): over a lake in a cave that is the
   lake's shore, where the stairs go on; gym batch stronghold-room2-a run 3 stepped 32 the way
   its last turn faced and was :boxed with the shore 33 off. Going up, a way whose next cell is
   not under gravel or sand (under-falling?) comes before any that is, whatever the last step's
   way: under a bed of gravel every stair is refused again from the next cell, and race
   38477665442 bot Clj_r04 stepped thirty cells along one at y 42, two blocks dug a step, until
   its last pickaxe broke (issue #43). The cells the staircase has stepped onto are kept
   (:intent/visited), and a step onto one comes after every other; a climb with no fresh level
   step ducks under what refuses it (duck) first: sim race 38659291345 world R4/38 (issue #37),
   under a lake, stepped round a patch of fifteen cells a hundred times, one fresh step always
   allowed, until it was :boxed. A step whose only fault is water beside the cells it digs is
   allowed once that water is filled (level-lay, issue #105), and an open step beside water is
   floored."
  [world
   {:intent/keys [kind dir level-steps level-dir visited] :or {level-steps 0 visited #{}} :as i}]
  (let [[fx _ fz :as feet] (intent/feet world)
        back (when level-dir (mapv - level-dir))
        round (take 4 (iterate turn (or dir (first directions))))
        home (:plan/go-at world)
        kept
        (if level-dir (cons level-dir (remove #{back} (take 4 (iterate turn level-dir)))) round)
        ways (case kind
               :stairs-up
               (sort-by (fn [[dx dz :as d]]
                          [(or (shore-distance world feet d) Long/MAX_VALUE)
                           (or (dry-head-distance world feet d) Long/MAX_VALUE)
                           (if home (physics/horizontal-distance [(+ fx dx) 0 (+ fz dz)] home) 0)])
                        kept)
               :stairs-down (sort-by #(or (floor-distance world feet %) Long/MAX_VALUE) kept)
               kept)
        ways (cond->> ways
               (= :stairs-up kind)
               (sort-by #(if (under-falling? world (last (level-cells feet %))) 1 0)))
        laid (fn [d]
               (let [cells (level-cells feet d)]
                 (or (and (level-clear? world cells) (lay world feet cells))
                     (reserve-level-lay world kind cells feet))))
        pick (fn [ws]
               (or (first (filter #(level-safe? world (level-cells feet %)) ws))
                   (first (filter laid ws))))
        budget? (< level-steps (level-step-limit world kind feet))
        fresh (when budget? (pick (remove #(contains? visited (last (level-cells feet %))) ways)))
        bored (when (= :stairs-up kind) (bore-up world))
        ducked (when (and (nil? bored) budget? (nil? fresh) (= :stairs-up kind)) (duck world i))
        d (or fresh (when (and budget? (nil? ducked)) (pick ways)))
        to-lay (when (and d (not (level-safe? world (level-cells feet d)))) (laid d))]
    (cond bored bored
          ducked ducked
          d
          (-> world
              (intent/assoc-intent :intent/dir d
                                   :intent/level-dir d
                                   :intent/turns 0
                                   :intent/level-steps (inc level-steps)
                                   :intent/level-cells (level-cells feet d)
                                   :intent/visited (conj visited feet (last (level-cells feet d)))
                                   :intent/stage (if to-lay :lay :level)
                                   :intent/started-tick (:time/tick world))
              (intent/dissoc-intent :intent/child :intent/cells :intent/lay)
              (cond-> to-lay (intent/assoc-intent :intent/lay to-lay))
              (game/say (str "stairs: every stair refused, a level step toward "
                             d
                             (when to-lay (str ", its floor laid at " to-lay)))))
          :else (box world))))

(def refusals #{:needs-tool :unsafe})  ; a child dig failing for these turns the stairs
(def max-rejections 3)                 ; digs of one stair the server would not do before the staircase fails :rejected

(defn rejected
  "A child dig the server did not do (:rejected: its ack came with the block still standing):
   the cell is still to open, so the stair digs it again, up to max-rejections a stair; past
   that the staircase fails :rejected. Race 38477665442, runner 9 (issues #59, #66): a lagging
   server with 25 bots queued early FINISHes behind one another and dropped them, and the
   staircase, believing the ack, walked into rock the server still had there."
  [world {:intent/keys [rejections child] :or {rejections 0}}]
  (if (>= (inc rejections) max-rejections)
    (-> world
        (assoc :player/controls {})
        (intent/fail :rejected))
    (-> world
        (intent/dissoc-intent :intent/child)
        (intent/assoc-intent :intent/rejections (inc rejections))
        (game/say
         (str "stairs: the server did not cut " (:intent/target child) ", digging again")))))

(defn airborne?
  "Is the body in the air, neither on the ground nor in a liquid? A stair is planned from feet
   that stand: round falling feet every cell is air, so every heading is refused for the drop
   and the staircase is :boxed before the body lands. Race 38477665442, landing 2361,111,2361
   (issue #65, three real bots and 89 sim runs a round): a walk to stone stepped off a cliff,
   the dig failed :out-of-reach on the way down, and the staircase the mine then started was
   planned, refused four ways and failed :boxed three times in the four seconds of the fall."
  [world]
  (not (or (:player/on-ground? world) (:player/in-water? world) (:player/in-lava? world))))

(defmulti stairs-stage
  "Advance a staircase (:stairs-down, :stairs-up, or a :tunnel's level step) one tick in its
   :intent/stage: :plan (choose a safe stair or turn), :open (dig its cells, one child :dig at a
   time), :step (walk down or jump up onto it) or :level (a level step)."
  {:arglists '([world intent event])}
  (fn [_world i _event] (:intent/stage i :plan)))

(defn rock-heading
  "For a stair down the heading would have to lay a floor for, the first other heading, a quarter
   turn at a time, whose stair is cut into the rock as it stands (safe?, given the heading): a
   stair into rock spends no filler, a floor laid over a cave or a cliff's air spends one or two a
   stair. nil for a climb, a level way, or a cut toward a bank's water (:intent/for :bank), whose
   heading is the point. Race 38842339863 runner 4 (issue #37) bots Clj_r01 and Clj_r08: the mine's
   stairs down ran east along a cliff's face and the wall of a cave, a floor laid before every
   stair, the rock a turn away west, until the few cobblestone the furnace had left were spent and
   every stair was :boxed."
  [{:intent/keys [kind for dir]} safe-toward?]
  (when (and (= :stairs-down kind) (not= :bank for))
    (->> (iterate turn (or dir (first directions)))
         rest
         (take 3)
         (filter safe-toward?)
         first)))

(defn shaft?
  "Is a climb out to the surface from feet cut as a shaft, one block dug and one laid under the
   feet a level, rather than as stairs, three blocks dug a level? It is where no pickaxe is held
   (every block is dug by hand, 7500 ms of stone and more of deepslate, so a stair is 30 s and a
   hundred levels a long hour: sim snapshots R2-36-1 and R3-13-1, issue #135, and R2-45-1, issue
   #178, each spent the judged window climbing), a filler is carried to lay, the cell over the
   head is safe to dig (shaft-safe?), a solid block lies under the feet cell and the feet are
   centred in it (the pillar lays its block against that block, and a body on a lip over a drop
   jumps and lands, the block never in: sim snapshot R2-45-1, pillar :stuck at the first level),
   and the intent has not given the shaft up (:intent/no-shaft)."
  [world {:intent/keys [kind for no-shaft]} feet]
  (boolean (and (= :stairs-up kind)
                (= :surface for)
                (not no-shaft)
                (not (pickaxe-held? world))
                (filler world 1)
                (= :solid (path/classify world (update feet 1 dec)))
                (intent/centred? world feet)
                (shaft-safe? world feet))))

(defmethod stairs-stage :plan
  [world {:intent/keys [kind dir turns visited] :or {turns 0 visited #{}} :as i} _]
  (let [dir (or dir (first directions))
        {shape-safe? :safe? lay :lay shape-cells :cells} (shapes kind)
        cells-of (if-let [n (:intent/tall i)]
                   #(tall-stair-cells %1 %2 n)
                   shape-cells)
        fresh? (fn [cells] (or (= :stairs-up kind) (not (contains? visited (last cells)))))
        fresh-safe? (fn [world cells] (and (fresh? cells) (shape-safe? world cells)))
        feet (intent/feet world)
        cells (cells-of feet dir)
        needed (when (and lay (fresh? cells) (not (fresh-safe? world cells)))
                 (lay world feet cells))
        edge? (when needed (backs-off? world feet needed))
        to-lay (when (and needed
                          (not edge?)
                          (or (safe? world cells) (floors-affordable? world i feet needed)))
                 needed)
        rock (when needed (rock-heading i #(fresh-safe? world (cells-of feet %))))
        up-open (when (and needed (= :stairs-up kind))
                  (first (filter #(fresh-safe? world (cells-of feet %))
                                 (rest (take 4 (iterate turn dir))))))]
    (cond (airborne? world) (assoc world :player/controls {}) ; land first
          (shaft? world i feet)
          (intent/assoc-intent world
                               :intent/stage :shaft
                               :intent/started-y (physics/y-of (:player/pos world))
                               :intent/started-tick (:time/tick world))
          (fresh-safe? world cells)
          (intent/assoc-intent world
                               :intent/dir dir
                               :intent/cells cells
                               :intent/stage :open
                               :intent/started-y (physics/y-of (:player/pos world))
                               :intent/started-tick (:time/tick world))
          rock
          (-> world
              (intent/assoc-intent :intent/dir rock
                                   :intent/cells (cells-of feet rock)
                                   :intent/stage :open
                                   :intent/started-y (physics/y-of (:player/pos world))
                                   :intent/started-tick (:time/tick world))
              (game/say (str "stairs: into the rock toward " rock
                             " rather than a floor laid toward " dir)))
          up-open
          (-> world
              (intent/assoc-intent :intent/dir up-open
                                   :intent/cells (cells-of feet up-open)
                                   :intent/stage :open
                                   :intent/started-y (physics/y-of (:player/pos world))
                                   :intent/started-tick (:time/tick world))
              (game/say (str "stairs: up the step already there toward " up-open
                             " rather than one laid toward " dir)))
          to-lay
          (-> world
              (intent/assoc-intent :intent/dir dir
                                   :intent/cells cells
                                   :intent/lay to-lay
                                   :intent/stage :lay
                                   :intent/started-y (physics/y-of (:player/pos world))
                                   :intent/started-tick (:time/tick world))
              (game/say (str "stairs: laying " to-lay " before the stair toward " dir)))
          (boxed? turns) (step-aside world (assoc i :intent/dir dir))
          edge?
          (turned world dir turns (str "stairs: laying toward " dir " steps off the edge, turning"))
          needed
          (turned world
                  dir
                  turns
                  (str "stairs: too little filler to floor the way toward "
                       dir
                       " down to y "
                       (:intent/to-y i)
                       ", turning"))
          :else (turned world dir turns (str "stairs: refused toward " dir ", turning")))))

(defn unshaft
  "world with the climb's shaft given up for this intent (:intent/no-shaft, the reason: a child's
   failure or :unsafe), back to :plan to cut stairs: a dig or a pillar the shaft could not do, or a
   cell over the head it may not open."
  [world why]
  (-> world
      (assoc :player/controls {})
      (intent/assoc-intent :intent/stage :plan :intent/no-shaft why)
      (intent/dissoc-intent :intent/child)))

(defn refuse
  "Turn the staircase from a stair (or a level step) a child would not cut or lay, saying why;
   boxed in, a level step instead (step-aside)."
  [world {:intent/keys [dir turns] :or {turns 0} :as i} why]
  (if (boxed? turns)
    (step-aside world i)
    (-> world
        (turned dir turns why)
        (intent/assoc-intent :intent/stage :plan)
        (intent/dissoc-intent :intent/child :intent/cells :intent/lay :intent/level-cells))))

(defmethod stairs-stage :lay
  ;; lay the floor's cells (:intent/lay), the first not yet solid at a time, each a child :place
  ;; of a filler; then open the stair (:intent/cells) or walk the level step
  ;; (:intent/level-cells). A placement the server refused, or no filler left, refuses the
  ;; stair like any cell that cannot be cut: turn
  [world {:intent/keys [child lay level-cells] :as i} event]
  (let [next (first (remove #(= :solid (path/classify world %)) lay))
        item (filler world 1)]
    (cond (and child (intent/done? child)) (intent/dissoc-intent world :intent/child)
          (and child (intent/failed? child))
          (refuse
           world
           i
           (str "stairs: laying " (:intent/cell child) " " (:intent/reason child) ", turning"))
          child (intent/run-child world event)
          (and next item)
          (intent/run-child
           (intent/assoc-intent
            world
            :intent/child
            {:intent/kind :place :intent/item item :intent/cell next :intent/status :active})
           event)
          next (refuse world i "stairs: nothing left to lay a floor from, turning")
          :else
          (-> world
              (intent/assoc-intent :intent/stage (if level-cells :level :open)
                                   :intent/started-tick (:time/tick world))
              (intent/dissoc-intent :intent/lay)))))

(defn fell?
  "Did a child dig find its cell already open (:target-gone)? Gravel or sand above fell out of it
   (or into the one below): the cell is open, which is what the dig was for."
  [child]
  (and (intent/failed? child) (= :target-gone (:intent/reason child))))

(defmethod stairs-stage :shaft
  ;; the cells over the head (shaft-cells) dug by hand (a child :dig) while any is rock, then a child :pillar up
  ;; one level (a block laid under the feet at the top of a jump), again until the intent is
  ;; done; a child that fails, or a cell no longer safe to open, gives the shaft up (unshaft)
  [world {:intent/keys [child]} event]
  (let [feet (intent/feet world)
        cell (first (filter #(= :solid (path/classify world %)) (shaft-cells feet)))
        item (pillar-item world)]
    (cond (and child (or (intent/done? child) (fell? child)))
          (intent/dissoc-intent world :intent/child)
          (and child (intent/failed? child))
          (unshaft world [(:intent/kind child) (:intent/reason child)])
          child (intent/run-child world event)
          (airborne? world) (assoc world :player/controls {})
          (not (and item (shaft-safe? world feet))) (unshaft world :unsafe)
          cell
          (intent/run-child (intent/assoc-intent world :intent/child (intent/child-dig cell)) event)
          :else
          (intent/run-child (intent/assoc-intent world
                                                 :intent/child
                                                 {:intent/kind :pillar
                                                  :intent/item item
                                                  :intent/top (inc (long (second feet)))
                                                  :intent/status :active})
                            event))))

(defn by-hand?
  "Is staircase i cut by hand where no tool of a block's kind is held at all? A stair up is:
   the way out of the ground is worth 7500 ms a block of stone, since the tool is made up there.
   Race 38477665442 Clj_r21's pickaxe broke at y 60 in the iron mine; its stairs up stopped
   :no-pickaxe, the sticks for another were in the trees up top, and twelve walks to them failed
   :no-path (issue #40). A stair down toward trees is too: the wood for a replacement can be
   below the feet (issue #229). Other descents and tunnels stop for the tool (tool-missing)."
  [i]
  (or (= :stairs-up (:intent/kind i))
      (and (= :stairs-down (:intent/kind i)) (contains? #{:trees :valley} (:intent/for i)))))

(defn dig-next
  "Start a child for cell, the next of a stair's to open: a :dig, or first an :equip of the tool
   when it is only in the main inventory (intent/stowed-tool); or fail with tool-missing's
   reason, so the planner makes the tool again, unless the staircase is cut by hand (by-hand?)."
  [world cell event]
  (let [[_ stowed-item :as stowed] (some->> (terrain/block-at world cell)
                                            (intent/stowed-tool world))
        missing (when-not (by-hand? (:plan/intent world)) (tool-missing world cell))
        child
        (if stowed
          {:intent/kind :equip :intent/item (recipe/item-name stowed-item) :intent/status :active}
          (intent/child-dig cell))]
    (if missing
      (-> world
          (assoc :player/controls {})
          (intent/fail missing))
      (intent/run-child (intent/assoc-intent world :intent/child child) event))))

(defmethod stairs-stage :open
  [world {:intent/keys [child cells] :as i} event]
  (cond
    (and child (or (intent/done? child) (fell? child))) (intent/dissoc-intent world :intent/child)
    (and child (intent/failed? child) (= :rejected (:intent/reason child))) (rejected world i)
    ;; a cell the child will not dig refuses this way, like lava: turn. :needs-tool is ore the
    ;; pickaxe cannot drop; :unsafe a cell beside water or lava, which the dig would let in (a
    ;; live descent through an aquifer at y 36 failed three times on it before this)
    (and child (contains? refusals (:intent/reason child)))
    (refuse world
            i
            (str "stairs: " (:intent/target child) " refused " (:intent/reason child) ", turning"))
    (and child (intent/failed? child))
    (-> world
        (assoc :player/controls {})
        (intent/fail (:intent/reason child)))
    child (intent/run-child world event)
    (next-to-open world cells) (dig-next world (next-to-open world cells) event)
    :else (intent/assoc-intent world :intent/stage :step :intent/started-tick (:time/tick world))))

(defn taken?
  "Has the stair been taken: the feet half a block past where it began, the way the staircase
   goes (rise -1 down, 1 up), the only proof the server broke what the bot asked for, and the
   feet centred in the stair's new feet cell (intent/centred?); going up, also on the ground
   again, since halfway up a jump the step is not yet stood on. Centred, because a body that
   lands past the cell's middle stands on the edge of its floor with its feet in the next
   column, and the next stair is planned from there: race 38477665442 bot Clj_r18 cut its
   stairs that way, each one digging the floor the body stood on, so a staircase meant to go
   down a block a stair went straight down a shaft one block wide, and nothing led back up."
  [world rise started-y cell]
  (and (>= (* rise (- (physics/y-of (:player/pos world)) started-y)) 0.5)
       (intent/centred? world cell)
       (or (neg? rise) (boolean (:player/on-ground? world)))))

(defn onto
  "Controls that walk onto the stair's new feet cell (intent/toward its middle). Going down
   where nothing beyond the cell stops the body (the cell past it, along dir, is not solid: a
   cave, where a floor laid over it is all there is to land on), the walk creeps, pushing only
   under creep-speed and never in the air, so the body leaves the edge slowly and lands on the
   cell: pushed all the way, the model left the lip at 0.11 blocks a tick, 0.19 in the air, and
   landed at the far edge of the cell, then fell off it. Into rock, and going up, forward all the
   way: the wall ahead stops the body, and the jump needs the push to carry it onto the step."
  [world rise cell [dx dz]]
  (let [[fx fy fz] cell
        [vx _ vz] (:player/vel world [0.0 0.0 0.0])
        open? (not= :solid (path/classify world [(+ fx dx) fy (+ fz dz)]))
        creep? (and (neg? rise) open?)]
    (assoc (intent/toward world [(+ fx 0.5) (double fy) (+ fz 0.5)] 0.0)
           :control/forward?
           (boolean (or (not creep?)
                        (and (:player/on-ground? world) (< (math/hypot vx vz) creep-speed)))))))

(defmethod stairs-stage :step
  [world {:intent/keys [kind dir cells started-y started-tick] :as i} _]
  (let [cell (last cells)
        rise (:intent/rise i (:rise (shapes kind)))]
    (cond (or (taken? world rise started-y cell)
              (and (pos? rise) (< (physics/y-of (:player/pos world)) (- started-y 0.5))))
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :plan :intent/turns 0)
              (intent/dissoc-intent :intent/child :intent/cells :intent/rejections :intent/rise))
          (next-to-open world cells) ; gravel or sand fell into the stair: dig it again
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :open :intent/started-tick (:time/tick world)))
          (some #(intent/falling-in-column? world %) cells) (assoc world :player/controls {})
          (> (- (:time/tick world) started-tick) step-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stair-not-taken))
          :else (assoc world :player/controls (onto world rise cell dir)))))

(def bore-timeout-ticks 1200)           ; ticks (a minute) a bore may take, digging and rising

(defmethod stairs-stage :bore
  ;; dig each of :intent/bore, bottom first, one child at a time (the water over the last may
  ;; come in: :intent/exposes-ok?; the rock's drop is not wanted: :intent/drop-ok?), then hold
  ;; under the shaft's centre with the jump held, rising as the water allows; the swim-out takes
  ;; over when the bot is trapped, and the climb ends at the surface (intent/run :stairs-up)
  [world {:intent/keys [child bore started-tick]} event]
  (let [[x y z] (intent/feet world)
        next (next-to-open world bore)]
    (cond (and child (or (intent/done? child) (fell? child)))
          (intent/dissoc-intent world :intent/child)
          (and child (intent/failed? child))
          (-> world
              (assoc :player/controls {})
              (intent/fail (:intent/reason child)))
          child (intent/run-child world event)
          next
          (intent/run-child (intent/assoc-intent world
                                                 :intent/child
                                                 (assoc (intent/child-dig next)
                                                        :intent/exposes-ok? true
                                                        :intent/drop-ok? true))
                            event)
          (> (- (:time/tick world) started-tick) bore-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stair-not-taken))
          :else
          (assoc world
                 :player/controls
                 (assoc (intent/toward world [(+ x 0.5) (double y) (+ z 0.5)] 0.0)
                        :control/jump?
                        true)))))

(defn level-refused
  "A level step whose cell a child dig would not cut (refusals) after the step was chosen: a
   tunnel plans its step again from where it stands (tunnel-plan: the heading when level-safe?,
   else its side, else :boxed), a staircase turns (refuse). The world changed under the step:
   sim race 38762695024 runner 8 (issue #171, world R1/40, every seed): a tunnel for gravel at
   y 40 ran under a gravel bed, the gravel over a step it opened fell into it round the body,
   water came down onto the gravel, and the dig to clear the step was refused :unsafe; the
   tunnel failed, the next one cut the step beside it and was buried the same way, and three
   failed tunnels in eight seconds ended the run."
  [world i why]
  (if (= :tunnel (:intent/kind i))
    (-> world
        (assoc :player/controls {})
        (intent/assoc-intent :intent/stage :plan)
        (intent/dissoc-intent :intent/child :intent/level-cells :intent/rejections)
        (game/say why))
    (refuse world i why)))

(defmethod stairs-stage :level
  ;; dig the level step's cells, one child at a time, then walk onto its feet cell; then plan
  ;; the stairs again from there. The step's time is the walk's: it starts again after each dig.
  ;; A cell the child will not dig (refusals) plans the step again (level-refused)
  [world {:intent/keys [child level-cells started-tick] :as i} event]
  (let [[fx fy fz] (last level-cells)]
    (cond
      (and child (or (intent/done? child) (fell? child)))
      (-> world
          (intent/dissoc-intent :intent/child)
          (intent/assoc-intent :intent/started-tick (:time/tick world)))
      (and child (intent/failed? child) (= :rejected (:intent/reason child))) (rejected world i)
      (and child (contains? refusals (:intent/reason child)))
      (level-refused
       world
       i
       (str "stairs: level " (:intent/target child) " refused " (:intent/reason child) ", again"))
      (and child (intent/failed? child))
      (-> world
          (assoc :player/controls {})
          (intent/fail (:intent/reason child)))
      child (intent/run-child world event)
      (next-to-open world level-cells) (dig-next world (next-to-open world level-cells) event)
      (= (intent/feet world) [fx fy fz])
      (-> world
          (assoc :player/controls {})
          (intent/assoc-intent :intent/stage :plan)
          (intent/dissoc-intent :intent/level-cells :intent/rejections))
      (> (- (:time/tick world) started-tick) step-timeout-ticks)
      (-> world
          (assoc :player/controls {})
          (intent/fail :stair-not-taken))
      :else
      (assoc world
             :player/controls
             (intent/toward world [(+ fx 0.5) (double fy) (+ fz 0.5)] 0.0)))))

(defn tunnel-plan
  "Plan a tunnel's next level step: along its heading when level-safe?; else a step to one side,
   keeping the heading, so the tunnel goes round what refused it. The side is the first of the
   two allowed and, once taken, the only one (:intent/side), so it never steps back and forth;
   :boxed when neither the heading nor that side allows a step. It never turns back down itself:
   the eighth live run, refused east at y 16, turned and tunnelled west beside the tunnel it had
   cut, and batch iron-a2 run 4 walked its own tunnel back and forth for eight minutes.
   A step whose only fault is the floor it lacks is taken once that floor is laid (lay, as a
   staircase's level step in step-aside is): race 40010178668 (issue #60) stood on one
   cobblestone at y 63 with air round it, every level step refused for its floor, every tunnel
   toward a remembered pool :boxed in a tick and that pool blacklisted, until no pool was left
   and it waited :no-water with 156 cobblestone in hand."
  [world {:intent/keys [dir side level-steps from-feet] :or {level-steps 0}}]
  (let [dir (or dir (first directions))
        feet (intent/feet world)
        sides (if side [side] [(turn dir) (turn (turn (turn dir)))])
        ways (cons dir sides)
        laid (fn [d]
               (let [cells (level-cells feet d)]
                 (or (and (level-clear? world cells) (lay world feet cells))
                     (reserve-level-lay world :tunnel cells feet))))
        d (or (first (filter #(level-safe? world (level-cells feet %)) ways))
              (first (filter laid ways)))
        to-lay (when (and d (not (level-safe? world (level-cells feet d)))) (laid d))]
    (if d
      (cond-> (intent/assoc-intent world
                                   :intent/dir dir
                                   :intent/level-cells (level-cells feet d)
                                   :intent/level-steps (inc level-steps)
                                   :intent/stage (if to-lay :lay :level)
                                   :intent/started-tick (:time/tick world))
        (and (zero? level-steps) (nil? from-feet)) (intent/assoc-intent :intent/from-feet feet)
        to-lay (intent/assoc-intent :intent/lay to-lay)
        (not= d dir)
        (-> (intent/assoc-intent :intent/side d)
            (game/say (str "tunnel: refused toward " dir ", a step toward " d))))
      (box world))))

(def nearer-by 1.0)                   ; blocks on the plane a tunnel toward a bank must close on its water
(def pond-reach 6)                    ; how far round its water a tunnel that came no nearer gives the sources up

(defn pond-sources
  "The plain water sources within pond-reach of target on the plane and a block above or below
   it: the water one tunnel toward a bank was cutting for, which the next would cut for the
   same way."
  [world [tx ty tz]]
  (for [x (range (- tx pond-reach) (inc (+ tx pond-reach)))
        z (range (- tz pond-reach) (inc (+ tz pond-reach)))
        y [(dec ty) ty (inc ty)]
        :let [c [x y z]]
        :when (= blocks/water-base (terrain/block-at world c))]
    c))

(defn no-nearer?
  "Did a tunnel toward a bank (:intent/for :bank, scoop/to-bank) end no nearer the water it
   heads for (:intent/target) on the plane than it began (:intent/from-feet), by nearer-by? Then
   it gives that water up, and the pond round it (pond-sources), into :plan/blacklist.
   Sim race 38839767273 runner 6 (issue #69, worlds R3/8 and R4/35 at 3f836ff): the tunnel
   walked an open corridor past its water, its heading turning as it passed, eight steps east,
   eight back west, two thousand tunnels until the run's ninety minutes were spent. Not when a
   walk to the water's bank has a route now (intent/routable?): the tunnel opened the way, if
   it came no nearer. Sim world R4/30 seed 1 (issue #69's second round, ef4e73b against
   5e93cae): a bank tunnel ended where the walk to the pond's bank was clear, the pond was given
   up, and the bot tunnelled on to other water; the bucket filled 39 s later and the run's road
   changed from the cast to the portal kit's :no-log."
  [world {:intent/keys [for target from-feet]}]
  (boolean (and (= :bank for)
                target
                from-feet
                (> (+ (physics/horizontal-distance (intent/feet world) target) nearer-by)
                   (physics/horizontal-distance from-feet target))
                (not (intent/routable?
                      world
                      {:intent/kind :walk :intent/target target :intent/for :scoop})))))

(defn no-progress?
  "Did the tunnel make no progress from where it began (:intent/from-feet): position unchanged,
   or no nearer target when one was given (excluding :bank tunnels, which blacklist their pond
   via no-nearer?)?"
  [world {:intent/keys [for target from-feet]}]
  (boolean (and from-feet
                (not= :bank for)
                (if target
                  (>= (physics/distance (physics/centre (intent/feet world))
                                        (physics/centre target))
                      (physics/distance (physics/centre from-feet) (physics/centre target)))
                  (= (intent/feet world) from-feet)))))

(defmethod intent/run :tunnel
  [world {:intent/keys [length level-steps stage target] :or {level-steps 0} :as i} event]
  (cond (and (>= level-steps length)
             (not (contains? #{:level :lay} stage))
             (pos? length)
             (no-progress? world i))
        (box world)
        (and (>= level-steps length) (not (contains? #{:level :lay} stage)))
        (cond-> (-> world
                    (assoc :player/controls {})
                    intent/done)
          (no-nearer? world i)
          (-> (update :plan/blacklist (fnil into #{}) (cons target (pond-sources world target)))
              (game/say (str "tunnel: no nearer the bank of " target ", other water next"))))
        (contains? #{:level :lay} stage) (stairs-stage world i event)
        :else (tunnel-plan world i)))

(defn open-step?
  "Can the body walk onto a step's cells with nothing to dig: every one loaded and not solid, and
   a floor under the last (the new feet)?"
  [world cells]
  (let [[fx fy fz] (last cells)
        open? #(some-> (terrain/block-at world %)
                       blocks/solid?
                       not)]
    (boolean (and (every? open? cells)
                  (some-> (terrain/block-at world [fx (dec fy) fz])
                          blocks/solid?)))))

(def rim-reach 8)                     ; columns out along a heading a pit's wall is looked for: a stone mine's quarry is four wide

(defn steppable?
  "Could the feet step into column [x z] from feet height y: a cell to stand in there
   (path/standable?) at one of the rises, the steps a walk takes (a block down, level, and a
   block up when the jump has its headroom, step-rises)?"
  [world [x y z] rises]
  (boolean (some #(path/standable? world [x (+ y %) z]) rises)))

(defn step-rises
  "The rises a step from feet may take: a block down, level, and a block up only when the cell
   two over the feet is clear, which the jump needs (path's :jump-up). Under a canopy whose
   leaves fill it no step goes up: sim race 38515210080 world R2/45 (issue #104), one stair up
   out of the crevice into a notch under the leaves, every column round it standable a block
   up, and the notch was taken for open ground."
  [world [x y z]]
  (if (path/clear? world [x (+ y 2) z]) [-1 0 1] [-1 0]))

(defn ground-over
  "The feet height on the ground of column [x z] over feet height y: a block over the highest
   block of ground from climb-stairs - 1 above y down to y itself, ground being solid and
   neither a log nor leaves (a stair up cuts through a canopy, and the feet should not end on
   one). nil when the span holds none, or is not loaded."
  [world [x y z]]
  (some (fn [gy]
          (when-let [id (terrain/block-at world [x gy z])]
            (when (and (blocks/solid? id) (not (blocks/log? id)) (not (blocks/leaves? id)))
              (inc gy))))
        (range (+ y climb-stairs -1) (dec y) -1)))

(defn pit-rim
  "The feet height of the ground round the pit the feet are in, or nil when they are in none.
   Along each heading, the first column within rim-reach the feet could not step into
   (steppable?) is the pit's edge that way: a wall, with ground over the feet (ground-over), or
   a drop, water or the unloaded (:drop). In a pit every heading has an edge, and the rim is the
   lowest wall's ground. nil where a heading runs steppable rim-reach out (open ground: not a
   pit, whatever a route found) or where no edge is a wall (a ledge over drops: nothing to
   climb). A level step across a pit's floor is not the way out of it: race 38515209959 (issues
   #102, #103, 30 real bots) and the sim race in worlds R3/36 and R3/32 stood at the bottom of
   the quarry the stone mine had dug round their feet, two or three deep and a few cells wide,
   its stairs eaten by the digs; and world R2/45 (issue #104) in a crevice of the forest floor,
   two deep, under the canopy of the tree just chopped. The sky was over the feet, a level step
   crossed the floor, no route reached a trunk or any ground a hunt could walk to, and each
   waited :no-log out."
  [world [x y z :as feet]]
  (let [rises (step-rises world feet)
        edges (map (fn [[dx dz]]
                     (some (fn [k]
                             (let [c [(+ x (* k dx)) y (+ z (* k dz))]]
                               (when-not (steppable? world c rises)
                                 (or (ground-over world c) :drop))))
                           (range 1 (inc rim-reach))))
                   directions)
        walls (filter number? edges)]
    (when (and (every? some? edges) (seq walls)) (apply min walls))))

(defn out?
  "Is the bot out of the ground at feet: the open sky over it, a level step it can walk onto
   with nothing to dig, under the open sky too (not the stair it just cut, nor a tunnel), and
   no pit round the feet (pit-rim). The open sky alone is a pit: the eleventh live run's stairs
   up stopped under the sky two below the ground, in a shaft one wide, and found no route to
   water. A level step alone is a pit's floor: the stone mine's quarry (pit-rim)."
  [world feet]
  (and (terrain/under-sky? world feet)
       (boolean (some (fn [d]
                        (let [cells (level-cells feet d)]
                          (and (open-step? world cells) (terrain/under-sky? world (last cells)))))
                      directions))
       (nil? (pit-rim world feet))))

(defmethod path/goal-done? :surface [world _ node] (out? world node))
(defmethod path/heuristic :surface [_ node] (path/heuristic {:goal/kind :sky} node))

(def surfaced-window 600000)            ; ms a climb out stays a fact about its place for a spare's sake
(def surfaced-radius 16)               ; blocks on the plane round where a climb came out that count as the same place

(defn surfaced
  "world with :plan/surfaced, [feet now], when the running intent is a climb out to the surface
   (:intent/for :surface) that ends out of the ground (out?) at the feet; world otherwise. What
   the climb found up there is the act's to judge again; that it came out here, and when, is a
   fact (surfaced-near?)."
  [world]
  (let [{:intent/keys [for to-height?]} (:plan/intent world)
        feet (intent/feet world)]
    (cond-> world
      (and (= :surface for) (not to-height?) (out? world feet))
      (assoc :plan/surfaced [feet (:time/now world)]))))

(defn surfaced-near?
  "Did a climb out to the surface come out within surfaced-radius on the plane of the feet in the
   last surfaced-window ms (:plan/surfaced), the feet now below where it came out: the bot climbed
   out here and went back down. Sim race 38806318231 (issue #161, 206 runs in 73 rounds) cut stairs
   up from an iron mine for a spare pickaxe's wood, came out where no tree was in reach, went back
   down for the iron, and climbed again, every twenty seconds for ninety minutes."
  [world]
  (let [[[x y z] at] (:plan/surfaced world)
        [fx fy fz] (intent/feet world)]
    (boolean (and at
                  (< fy y)
                  (<= (- (:time/now world) at) surfaced-window)
                  (<= (physics/horizontal-distance [x 0 z] [fx 0 fz]) surfaced-radius)))))

(defmethod intent/run :stairs-up
  ;; done standing at to-y, or out of the ground (out?), whichever comes first; one that is
  ;; :intent/to-height? ends at to-y alone: the open sky over a pit's floor is not out. A climb
  ;; out that comes out is remembered (surfaced)
  [world {:intent/keys [to-y to-height? rise] :as i} event]
  (cond (and (:player/on-ground? world)
             (or (>= (physics/y-of (intent/feet world)) to-y)
                 (and (not to-height?) (out? world (intent/feet world)))))
        (-> world
            (assoc :player/controls {})
            surfaced
            intent/done)
        (and (= -1 rise)
             (:player/on-ground? world)
             (= :solid (path/classify world (update (intent/feet world) 1 dec)))
             (some (fn [d]
                     (let [cells (up-cells (intent/feet world) d)]
                       (and (up-dry? world cells) (not-any? #(too-hard? world %) cells))))
                   directions))
        (-> world
            (assoc :player/controls {})
            (intent/assoc-intent :intent/stage :plan)
            (intent/dissoc-intent :intent/child :intent/cells :intent/rise))
        :else (stairs-stage world i event)))

(defmethod intent/run :stairs-down
  [world {:intent/keys [to-y] :as i} event]
  (if (<= (physics/y-of (intent/feet world)) to-y)
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))
