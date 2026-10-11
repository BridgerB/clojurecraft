(ns clojurecraft.stairs
  "Going down safely: the :stairs-down intent, which cuts a staircase one stair at a time in the
   direction it faces until the feet are at or below a height, and never digs the block under
   the feet (a blind drop is how the siblings' bots died).

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
   gravel or sand that would fall onto the bot. Done standing at to-y or under the open sky: the
   way out of a mine whose way down no longer leads back up.
   Each dig is a child :dig intent run by the dig
   executor itself (intents composing intents: no second rule for breaking blocks), and the
   descent is judged by the feet having actually gone down, never by a packet having been
   sent, since a dig the server refused leaves the bot where it stood."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def max-turns 4)                     ; refusals before a level step, or :boxed (one full turn)
(def max-level-steps 32)              ; level steps around what refuses every stair before :boxed
(def step-timeout-ticks 200)          ; ticks a walk down one stair may take (10 s)
(def directions [[1 0] [0 1] [-1 0] [0 -1]]) ; the four headings, each a quarter turn from the last

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

(defn safe?
  "May a stair (or a level step) be cut: none of the cells it opens is water, lava or unloaded,
   no lava is beside or under them, and a floor lies under the new feet, the last cell (the
   step is one block, not a drop)."
  [world cells]
  (let [[fx fy fz] (last cells)
        kind #(path/classify world %)]
    (and (every? #(not (#{:water :lava :unknown} (kind %))) cells)
         (every? #(not= :lava (kind %)) (exposed cells))
         (= :solid (kind [fx (dec fy) fz])))))

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
   rule that lets it be cut, and which way the feet go when it is taken."
  {:stairs-down {:cells stair-cells :safe? safe? :rise -1}
   :stairs-up {:cells up-cells :safe? up-safe? :rise 1}})

(defn level-safe?
  "May a level step be taken: what a stair needs (safe?), and no cell it opens borders water or
   lava, which the child dig would refuse to open (intent/exposes-fluid?)."
  [world cells]
  (and (safe? world cells) (not-any? #(intent/exposes-fluid? world %) cells)))

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

(defn step-aside
  "Every stair refused: a level step, the turns counted afresh, or :boxed when none is allowed
   or max-level-steps are spent. The step keeps the last one's way (:intent/level-dir) while it
   is allowed and never goes straight back, so the staircase walks out from under what refuses
   it: the ninth live run, cutting stairs up under water at y 51, stepped round a square of four
   cells until it failed :boxed three times. The first step takes the first heading allowed,
   from the staircase's own round a full turn."
  [world {:intent/keys [dir level-steps level-dir] :or {level-steps 0}}]
  (let [feet (intent/feet world)
        back (when level-dir (mapv - level-dir))
        ways (if level-dir
               (cons level-dir (remove #{back} (take 4 (iterate turn level-dir))))
               (take 4 (iterate turn (or dir (first directions)))))
        d (when (< level-steps max-level-steps)
            (first (filter #(level-safe? world (level-cells feet %)) ways)))]
    (if d
      (-> world
          (intent/assoc-intent :intent/dir d
                               :intent/level-dir d
                               :intent/turns 0
                               :intent/level-steps (inc level-steps)
                               :intent/level-cells (level-cells feet d)
                               :intent/stage :level
                               :intent/started-tick (:time/tick world))
          (intent/dissoc-intent :intent/child :intent/cells)
          (game/say (str "stairs: every stair refused, a level step toward " d)))
      (box world))))

(def refusals #{:needs-tool :unsafe})  ; a child dig failing for these turns the stairs

(defmulti stairs-stage
  "Advance a staircase (:stairs-down, :stairs-up, or a :tunnel's level step) one tick in its
   :intent/stage: :plan (choose a safe stair or turn), :open (dig its cells, one child :dig at a
   time), :step (walk down or jump up onto it) or :level (a level step)."
  {:arglists '([world intent event])}
  (fn [_world i _event] (:intent/stage i :plan)))

(defmethod stairs-stage :plan
  [world {:intent/keys [kind dir turns] :or {turns 0} :as i} _]
  (let [dir (or dir (first directions))
        {:keys [safe?] cells-of :cells} (shapes kind)
        cells (cells-of (intent/feet world) dir)]
    (cond (safe? world cells)
          (intent/assoc-intent world
                               :intent/dir dir
                               :intent/cells cells
                               :intent/stage :open
                               :intent/started-y (second (:player/pos world))
                               :intent/started-tick (:time/tick world))
          (boxed? turns) (step-aside world (assoc i :intent/dir dir))
          :else (turned world dir turns (str "stairs: refused toward " dir ", turning")))))

(defn fell?
  "Did a child dig find its cell already open (:target-gone)? Gravel or sand above fell out of it
   (or into the one below): the cell is open, which is what the dig was for."
  [child]
  (and (intent/failed? child) (= :target-gone (:intent/reason child))))

(defn tool-missing
  "The reason to stop when the block at cell would be dug by hand for nothing (no-<tool>, as
   :no-pickaxe): it drops only to a tool and no tool of its kind is in the hotbar at all, which
   happens when the pickaxe breaks on the way. Stone by hand takes 7500 ms a block; batch iron-a2
   runs went on that way, a stair at a time, until a step timed out. A tool of the kind too weak
   for the block is the child dig's :needs-tool instead, which turns the stairs. nil when it can
   be dug."
  [world cell]
  (let [id (terrain/block-at world cell)
        kind (some-> id
                     blocks/tool-of)]
    (when (and id
               (blocks/drops-need-tool? id)
               (not-any? (fn [[_ {:keys [item]}]] (= kind (:kind (blocks/tool item))))
                         (intent/hotbar world)))
      (keyword (str "no-" (name (or kind :tool)))))))

(defn dig-next
  "Start a child :dig of cell, the next of a stair's to open, or fail with tool-missing's reason,
   so the planner makes the tool again."
  [world cell event]
  (if-let [missing (tool-missing world cell)]
    (-> world
        (assoc :player/controls {})
        (intent/fail missing))
    (intent/run-child (intent/assoc-intent world :intent/child (intent/child-dig cell)) event)))

(defmethod stairs-stage :open
  [world {:intent/keys [dir turns child cells] :or {turns 0} :as i} event]
  (cond
    (and child (or (intent/done? child) (fell? child))) (intent/dissoc-intent world :intent/child)
    ;; a cell the child will not dig refuses this way, like lava: turn. :needs-tool is ore the
    ;; pickaxe cannot drop; :unsafe a cell beside water or lava, which the dig would let in (a
    ;; live descent through an aquifer at y 36 failed three times on it before this)
    (and child (contains? refusals (:intent/reason child)))
    (if (boxed? turns)
      (step-aside world i)
      (-> world
          (turned
           dir
           turns
           (str "stairs: " (:intent/target child) " refused " (:intent/reason child) ", turning"))
          (intent/assoc-intent :intent/stage :plan)
          (intent/dissoc-intent :intent/child :intent/cells)))
    (and child (intent/failed? child))
    (-> world
        (assoc :player/controls {})
        (intent/fail (:intent/reason child)))
    child (intent/run-child world event)
    (next-to-open world cells) (dig-next world (next-to-open world cells) event)
    :else (intent/assoc-intent world :intent/stage :step :intent/started-tick (:time/tick world))))

(defn taken?
  "Has the stair been taken: the feet half a block past where it began, the way the staircase
   goes (rise -1 down, 1 up), the only proof the server broke what the bot asked for; going up,
   also on the ground again, since halfway up a jump the step is not yet stood on."
  [world rise started-y]
  (and (>= (* rise (- (second (:player/pos world)) started-y)) 0.5)
       (or (neg? rise) (boolean (:player/on-ground? world)))))

(defmethod stairs-stage :step
  [world {:intent/keys [kind cells started-y started-tick]} _]
  (let [[fx fy fz] (last cells)
        target [(+ fx 0.5) (double fy) (+ fz 0.5)]]
    (cond (taken? world (:rise (shapes kind)) started-y)
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :plan)
              (intent/dissoc-intent :intent/child :intent/cells))
          (next-to-open world cells) ; gravel or sand fell into the stair: dig it again
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :open :intent/started-tick (:time/tick world)))
          (> (- (:time/tick world) started-tick) step-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stair-not-taken))
          :else (assoc world :player/controls (intent/toward world target 0.0)))))

(defmethod stairs-stage :level
  ;; dig the level step's cells, one child at a time, then walk onto its feet cell; then plan
  ;; the stairs again from there. The step's time is the walk's: it starts again after each dig
  [world {:intent/keys [child level-cells started-tick]} event]
  (let [[fx fy fz] (last level-cells)]
    (cond (and child (or (intent/done? child) (fell? child)))
          (-> world
              (intent/dissoc-intent :intent/child)
              (intent/assoc-intent :intent/started-tick (:time/tick world)))
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
              (intent/dissoc-intent :intent/level-cells))
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

(defmethod intent/run :stairs-up
  ;; done standing at to-y, or out under the open sky, whichever comes first
  [world {:intent/keys [to-y] :as i} event]
  (if (and (:player/on-ground? world)
           (or (>= (second (intent/feet world)) to-y)
               (terrain/under-sky? world (intent/feet world))))
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))

(defmethod intent/run :stairs-down
  [world {:intent/keys [to-y] :as i} event]
  (if (<= (second (intent/feet world)) to-y)
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))
