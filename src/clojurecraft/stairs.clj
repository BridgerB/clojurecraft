(ns clojurecraft.stairs
  "Going down safely: the :stairs-down intent, which cuts a staircase one stair at a time in the
   direction it faces until the feet are at or below a height, and never digs the block under
   the feet (a blind drop is how the siblings' bots died).

     {:intent/kind :stairs-down :intent/to-y 60 :intent/dir [1 0]}

   A stair opens three cells ahead (head-up, head, feet, so the body can walk down a block)
   and is walked onto. Before any of them is dug every cell the stair exposes is checked on
   the world value: water, lava or an unloaded chunk in any of them, lava beside any of them,
   or no floor under the new feet (a drop) refuses the stair; a refusal turns the intent a
   quarter turn and after four it fails :boxed; so does a cell the held tool cannot drop (an ore
   under a wooden pickaxe: gym batch cobble-a2 run 2 failed three stairs in a second on one).
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

(def max-turns 4)                     ; refusals before the intent fails :boxed (one full turn)
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

(defn exposed
  "Every cell a stair's digging exposes to the body: the three it opens, the floor under the
   new feet, and the four horizontal neighbours of each opened cell."
  [cells]
  (let [[_ _ [fx fy fz]] cells]
    (distinct (concat cells
                      [[fx (dec fy) fz]]
                      (for [[x y z] cells [ddx ddz] directions] [(+ x ddx) y (+ z ddz)])))))

(defn safe?
  "May a stair be cut: none of the cells it opens is water, lava or unloaded, no lava is beside
   or under them, and a floor lies under the new feet (the step is one block, not a drop)."
  [world cells]
  (let [[_ _ [fx fy fz]] cells
        kind #(path/classify world %)]
    (and (every? #(not (#{:water :lava :unknown} (kind %))) cells)
         (every? #(not= :lava (kind %)) (exposed cells))
         (= :solid (kind [fx (dec fy) fz])))))

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

(def refusals #{:needs-tool :unsafe})  ; a child dig failing for these turns the stairs

(defmulti stairs-stage
  "Advance a :stairs-down intent one tick in its :intent/stage: :plan (choose a safe stair or
   turn), :open (dig its cells, one child :dig at a time) or :step (walk down onto it)."
  {:arglists '([world intent event])}
  (fn [_world i _event] (:intent/stage i :plan)))

(defmethod stairs-stage :plan
  [world {:intent/keys [dir turns] :or {turns 0}} _]
  (let [dir (or dir (first directions))
        cells (stair-cells (intent/feet world) dir)]
    (cond (safe? world cells)
          (intent/assoc-intent world
                               :intent/dir dir
                               :intent/cells cells
                               :intent/stage :open
                               :intent/started-y (second (:player/pos world))
                               :intent/started-tick (:time/tick world))
          (boxed? turns) (box world)
          :else (turned world dir turns (str "stairs: refused toward " dir ", turning")))))

(defmethod stairs-stage :open
  [world {:intent/keys [dir turns child cells] :or {turns 0}} event]
  (cond (and child (intent/done? child)) (intent/dissoc-intent world :intent/child)
        ;; a cell the child will not dig refuses this way, like lava: turn. :needs-tool is ore the
        ;; pickaxe cannot drop; :unsafe a cell beside water or lava, which the dig would let in (a
        ;; live descent through an aquifer at y 36 failed three times on it before this)
        (and child (contains? refusals (:intent/reason child)))
        (if (boxed? turns)
          (box world)
          (->
            world
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
        (next-to-open world cells)
        (intent/run-child
         (intent/assoc-intent world :intent/child (intent/child-dig (next-to-open world cells)))
         event)
        :else
        (intent/assoc-intent world :intent/stage :step :intent/started-tick (:time/tick world))))

(defmethod stairs-stage :step
  [world {:intent/keys [cells started-y started-tick]} _]
  (let [[_ _ [fx fy fz]] cells
        target [(+ fx 0.5) (double fy) (+ fz 0.5)]]
    (cond (<= (second (:player/pos world)) (- started-y 0.5)) ; the feet went down: the stair is cut and taken
          (-> world
              (assoc :player/controls {})
              (intent/assoc-intent :intent/stage :plan)
              (intent/dissoc-intent :intent/child :intent/cells))
          (> (- (:time/tick world) started-tick) step-timeout-ticks)
          (-> world
              (assoc :player/controls {})
              (intent/fail :stair-not-taken))
          :else (assoc world :player/controls (intent/toward world target 0.0)))))

(defmethod intent/run :stairs-down
  [world {:intent/keys [to-y] :as i} event]
  (if (<= (second (intent/feet world)) to-y)
    (-> world
        (assoc :player/controls {})
        intent/done)
    (stairs-stage world i event)))
