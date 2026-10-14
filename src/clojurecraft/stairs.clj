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
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
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
   that drop themselves, then dirt. Never gravel or sand, which fall out of a floor."
  [:cobblestone :cobbled_deepslate :andesite :diorite :granite :tuff :dirt])

(defn filler
  "The first of fillers the inventory holds n or more of, or nil."
  [world n]
  (let [held (recipe/counts (:player/inventory world))]
    (first (filter #(>= (get held % 0) n) fillers))))

(defn floor-laying
  "The cells to lay, in order, so that a floor lies under the new feet of a stair (or a level
   step) from feet whose floor is missing: the floor cell alone when a solid block borders it to
   place against (place/support); else first a holder, the cell under the block under the feet,
   placed against that block's underside, and then the floor against the holder, the way a
   staircase is cast through a cave from a ceiling one block thick. [] when the floor is solid
   already; nil when it is water, lava or unloaded, or nothing can hold it."
  [world [x _ z] cells]
  (let [[nx ny nz] (last cells)
        floor [nx (dec ny) nz]
        holder [x (dec ny) z]
        kind #(path/classify world %)]
    (cond (= :solid (kind floor)) []
          (not= :clear (kind floor)) nil
          (place/support world floor) [floor]
          (and (= :clear (kind holder)) (place/support world holder)) [holder floor]
          :else nil)))

(defn lay
  "The cells to lay, in order, before a stair (or a level step) from feet whose cells may be
   opened (clear-way?) can be cut: the floor and what holds it (floor-laying), when enough of a
   filler is carried; nil when the stair needs none (safe?) or none can be laid. Race
   38477665442: five of the eight bots on runner 8 that stopped :boxed stood on the lip of a
   cave their staircase had broken into, 7 to 284 cobblestone in hand, every stair refused for
   the drop under it and nothing else to cut from the lip (issue #37)."
  [world feet cells]
  (when (clear-way? world cells)
    (when-let [cells (seq (floor-laying world feet cells))]
      (when (filler world (count cells)) (vec cells)))))

(defn up-safe?
  "May a stair up be cut: what any stair needs (safe?), and no gravel or sand over the cell above
   the head, which would fall through it onto the bot."
  [world cells]
  (let [[x y z] (first cells)]
    (and (safe? world cells)
         (not (some-> (terrain/block-at world [x (inc y) z])
                      blocks/falls?)))))

(def shapes
  "How each kind of staircase is cut: the cells a stair opens (top first, the new feet last), the
   rule that lets it be cut, which way the feet go when it is taken, and for stairs down what to
   lay where the floor is missing (lay; a stair up lays nothing, its floor would be the cell the
   bot stands in)."
  {:stairs-down {:cells stair-cells :safe? safe? :rise -1 :lay lay}
   :stairs-up {:cells up-cells :safe? up-safe? :rise 1}})

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

#_{:splint/disable [naming/conversion-functions]} ; "the next cell to open", not a conversion
(defn next-to-open
  "The first of the stair's cells (top first) still solid, or nil when the stair is open."
  [world cells]
  (first (filter #(some-> (terrain/block-at world %)
                          blocks/solid?)
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

(defn box
  "Fail the staircase :boxed: every heading was refused."
  [world]
  (-> world
      (assoc :player/controls {})
      (intent/fail :boxed)))

(defn dry-top?
  "Is the top of column [x z] (its highest block that is solid or a fluid) solid: ground the open
   sky is over, not a lake or the sea? false when the column is not loaded."
  [world [x z]]
  (true? (first (keep (fn [y]
                        (when-let [id (terrain/block-at world [x y z])]
                          (cond (blocks/fluid id) false
                                (blocks/solid? id) true
                                :else nil)))
                      (range terrain/top-y (dec chunk/min-y) -1)))))

(defn shore-distance
  "How many columns along heading [dx dz] from feet the first with dry ground on top lies
   (dry-top?), within shore-reach; nil when none is loaded there."
  [world [x _ z] [dx dz]]
  (some #(when (dry-top? world [(+ x (* % dx)) (+ z (* % dz))]) %) (range 1 (inc shore-reach))))

(defn level-step-limit
  "How many level steps a staircase of kind may take before it is :boxed: max-level-steps, and a
   climb as many more as the landing (:plan/go-at) is far from feet on the plane, since under a
   lake or the sea the way out may be all the way back: gym batch iron-a9 run 1 climbed under
   the sea 155 blocks from its landing and was :boxed after 32 steps three times."
  [world kind feet]
  (let [home (:plan/go-at world)]
    (cond-> max-level-steps
      (and home (= :stairs-up kind)) (+ (long (physics/horizontal-distance feet home))))))

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
   under the sea with no shore in sight, :boxed three times."
  [world {:intent/keys [kind dir level-steps level-dir] :or {level-steps 0}}]
  (let [[fx _ fz :as feet] (intent/feet world)
        back (when level-dir (mapv - level-dir))
        round (take 4 (iterate turn (or dir (first directions))))
        home (:plan/go-at world)
        ways (cond level-dir (cons level-dir (remove #{back} (take 4 (iterate turn level-dir))))
                   (= :stairs-up kind)
                   (sort-by
                    (fn [[dx dz :as d]]
                      [(or (shore-distance world feet d) Long/MAX_VALUE)
                       (if home (physics/horizontal-distance [(+ fx dx) 0 (+ fz dz)] home) 0)])
                    round)
                   :else round)
        laid (fn [d]
               (let [cells (level-cells feet d)]
                 (and (level-clear? world cells) (lay world feet cells))))
        d (when (< level-steps (level-step-limit world kind feet))
            (or (first (filter #(level-safe? world (level-cells feet %)) ways))
                (first (filter laid ways))))
        to-lay (when (and d (not (level-safe? world (level-cells feet d)))) (laid d))]
    (if d
      (-> world
          (intent/assoc-intent :intent/dir d
                               :intent/level-dir d
                               :intent/turns 0
                               :intent/level-steps (inc level-steps)
                               :intent/level-cells (level-cells feet d)
                               :intent/stage (if to-lay :lay :level)
                               :intent/started-tick (:time/tick world))
          (intent/dissoc-intent :intent/child :intent/cells :intent/lay)
          (cond-> to-lay (intent/assoc-intent :intent/lay to-lay))
          (game/say (str "stairs: every stair refused, a level step toward "
                         d
                         (when to-lay (str ", its floor laid at " to-lay)))))
      (box world))))

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

(defmethod stairs-stage :plan
  [world {:intent/keys [kind dir turns] :or {turns 0} :as i} _]
  (let [dir (or dir (first directions))
        {:keys [safe? lay] cells-of :cells} (shapes kind)
        feet (intent/feet world)
        cells (cells-of feet dir)
        to-lay (when (and lay (not (safe? world cells))) (lay world feet cells))]
    (cond (airborne? world) (assoc world :player/controls {}) ; land first
          (safe? world cells)
          (intent/assoc-intent world
                               :intent/dir dir
                               :intent/cells cells
                               :intent/stage :open
                               :intent/started-y (physics/y-of (:player/pos world))
                               :intent/started-tick (:time/tick world))
          to-lay
          (-> world
              (intent/assoc-intent :intent/dir dir
                                   :intent/cells cells
                                   :intent/lay to-lay
                                   :intent/stage :lay
                                   :intent/started-y (physics/y-of (:player/pos world))
                                   :intent/started-tick (:time/tick world))
              (game/say (str "stairs: no floor toward " dir ", laying one at " to-lay)))
          (boxed? turns) (step-aside world (assoc i :intent/dir dir))
          :else (turned world dir turns (str "stairs: refused toward " dir ", turning")))))

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

(defn by-hand?
  "Is staircase i cut by hand where no tool of a block's kind is held at all? A stair up is:
   the way out of the ground is worth 7500 ms a block of stone, since the tool is made up there.
   Race 38477665442 Clj_r21's pickaxe broke at y 60 in the iron mine; its stairs up stopped
   :no-pickaxe, the sticks for another were in the trees up top, and twelve walks to them failed
   :no-path (issue #40). A stair down and a tunnel stop for the tool instead (tool-missing)."
  [i]
  (= :stairs-up (:intent/kind i)))

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
  [world {:intent/keys [kind dir cells started-y started-tick]} _]
  (let [cell (last cells)
        rise (:rise (shapes kind))]
    (cond (taken? world rise started-y cell)
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :plan)
              (intent/dissoc-intent :intent/child :intent/cells :intent/rejections))
          (next-to-open world cells) ; gravel or sand fell into the stair: dig it again
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :open :intent/started-tick (:time/tick world)))
          (> (- (:time/tick world) started-tick) step-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stair-not-taken))
          :else (assoc world :player/controls (onto world rise cell dir)))))

(defmethod stairs-stage :level
  ;; dig the level step's cells, one child at a time, then walk onto its feet cell; then plan
  ;; the stairs again from there. The step's time is the walk's: it starts again after each dig
  [world {:intent/keys [child level-cells started-tick] :as i} event]
  (let [[fx fy fz] (last level-cells)]
    (cond (and child (or (intent/done? child) (fell? child)))
          (-> world
              (intent/dissoc-intent :intent/child)
              (intent/assoc-intent :intent/started-tick (:time/tick world)))
          (and child (intent/failed? child) (= :rejected (:intent/reason child))) (rejected world i)
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
   cut, and batch iron-a2 run 4 walked its own tunnel back and forth for eight minutes."
  [world {:intent/keys [dir side level-steps] :or {level-steps 0}}]
  (let [dir (or dir (first directions))
        feet (intent/feet world)
        sides (if side [side] [(turn dir) (turn (turn (turn dir)))])
        d (first (filter #(level-safe? world (level-cells feet %)) (cons dir sides)))]
    (if d
      (cond-> (intent/assoc-intent world
                                   :intent/dir dir
                                   :intent/level-cells (level-cells feet d)
                                   :intent/level-steps (inc level-steps)
                                   :intent/stage :level
                                   :intent/started-tick (:time/tick world))
        (not= d dir)
        (-> (intent/assoc-intent :intent/side d)
            (game/say (str "tunnel: refused toward " dir ", a step toward " d))))
      (box world))))

(defmethod intent/run :tunnel
  [world {:intent/keys [length level-steps stage] :or {level-steps 0} :as i} event]
  (cond (and (>= level-steps length) (not= :level stage))
        (-> world
            (assoc :player/controls {})
            intent/done)
        (= :level stage) (stairs-stage world i event)
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

(defn out?
  "Is the bot out of the ground at feet: the open sky over it, and a level step it can walk onto
   with nothing to dig, under the open sky too (not the stair it just cut, nor a tunnel). The
   open sky alone is a pit: the eleventh live run's stairs up stopped under the sky two below
   the ground, in a shaft one wide, and found no route to water."
  [world feet]
  (and (terrain/under-sky? world feet)
       (boolean (some (fn [d]
                        (let [cells (level-cells feet d)]
                          (and (open-step? world cells) (terrain/under-sky? world (last cells)))))
                      directions))))

(defmethod intent/run :stairs-up
  ;; done standing at to-y, or out of the ground (out?), whichever comes first
  [world {:intent/keys [to-y] :as i} event]
  (if (and (:player/on-ground? world)
           (or (>= (physics/y-of (intent/feet world)) to-y) (out? world (intent/feet world))))
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))

(defmethod intent/run :stairs-down
  [world {:intent/keys [to-y] :as i} event]
  (if (<= (physics/y-of (intent/feet world)) to-y)
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))
