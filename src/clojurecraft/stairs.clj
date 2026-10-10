(ns clojurecraft.stairs
  "Going down safely: the :stairs-down intent, which cuts a staircase one stair at a time in the
   direction it faces until the feet are at or below a height, and never digs the block under
   the feet (a blind drop is how the siblings' bots died).

     {:intent/kind :stairs-down :intent/to-y 60 :intent/dir [1 0]}

   A stair opens three cells ahead (head-up, head, feet, so the body can walk down a block)
   and is walked onto. Before any of them is dug every cell the stair exposes is checked on
   the world value: water, lava or an unloaded chunk in any of them, lava beside any of them,
   or no floor under the new feet (a drop) refuses the stair; a refusal turns the intent a
   quarter turn and after four it fails :boxed. Each dig is a child :dig intent run by the dig
   executor itself (intents composing intents: no second rule for breaking blocks), and the
   descent is judged by the feet having actually gone down, never by a packet having been
   sent, since a dig the server refused leaves the bot where it stood."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]))

(def max-turns 4)                     ; refusals before the intent fails :boxed (one full turn)
(def step-timeout-ticks 200)          ; ticks a walk down one stair may take (10 s)
(def directions [[1 0] [0 1] [-1 0] [0 -1]]) ; the four headings, each a quarter turn from the last

(defn turn
  "The heading a quarter turn from dir."
  [dir]
  (nth directions (mod (inc (.indexOf ^java.util.List directions dir)) 4)))

(defn feet
  "The cell the feet stand in: y a touch below the position, since physics jitter dips it
   just under the integer (ruststeve's (p.y - 0.5).floor())."
  [world]
  (let [[x y z] (:player/pos world)]
    [(long (Math/floor x)) (long (Math/floor (+ y 0.5))) (long (Math/floor z))]))

(defn stair-cells
  "The three cells a stair ahead of feet [x y z] in direction [dx dz] opens, top first:
   head-up, head and the new feet cell one block down."
  [[x y z] [dx dz]]
  [[(+ x dx) (+ y 1) (+ z dz)] [(+ x dx) y (+ z dz)] [(+ x dx) (- y 1) (+ z dz)]])

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

(defn child-dig
  "A fresh :dig intent for one cell, to be run by the dig executor."
  [target]
  {:intent/kind :dig :intent/target target :intent/status :active})

(defn run-child
  "One tick of the child intent at :intent/child by its own executor: the child stands in as
   :plan/intent for the tick, then returns to its place. Returns the world."
  [world event]
  (let [parent (:plan/intent world)
        world (intent/run (assoc world :plan/intent (:intent/child parent)) (:intent/child parent) event)
        child (:plan/intent world)]
    (assoc world :plan/intent (assoc parent :intent/child child))))

(defn solid-ahead?
  "Is any of the stair's three cells still a solid block the stair must open?"
  [world cells]
  (boolean (some #(some-> (terrain/block-at world %) blocks/solid?) cells)))

(defn next-to-open
  "The first of the stair's cells (top first) still solid, or nil when the stair is open."
  [world cells]
  (first (filter #(some-> (terrain/block-at world %) blocks/solid?) cells)))

(defmethod intent/run :stairs-down
  [world {:intent/keys [to-y dir turns stage child cells started-y started-tick] :or {turns 0 stage :plan} :as i} event]
  (let [tick (:time/tick world)
        set-i (fn [w & kvs] (apply update w :plan/intent assoc kvs))
        drop-i (fn [w & ks] (apply update w :plan/intent dissoc ks))   ; what is over is absent, never nil
        y-now (second (:player/pos world))]
    (cond
      (<= (second (feet world)) to-y)
      (-> world (assoc :player/controls {}) intent/done)

      (= stage :plan)
      (let [dir (or dir (first directions))
            cells (stair-cells (feet world) dir)]
        (cond
          (safe? world cells)
          (set-i world :intent/dir dir :intent/cells cells :intent/stage :open :intent/started-y y-now :intent/started-tick tick)

          (>= (inc turns) max-turns)
          (-> world (assoc :player/controls {}) (intent/fail :boxed))

          :else
          (-> world (set-i :intent/dir (turn dir) :intent/turns (inc turns))
              (game/say (str "stairs: refused toward " dir ", turning")))))

      (= stage :open)
      (cond
        (and child (intent/done? child))
        (drop-i world :intent/child)

        (and child (intent/failed? child))
        (-> world (assoc :player/controls {}) (intent/fail (:intent/reason child)))

        child
        (run-child world event)

        (next-to-open world cells)
        (run-child (set-i world :intent/child (child-dig (next-to-open world cells))) event)

        :else
        (set-i world :intent/stage :step :intent/started-tick tick))

      (= stage :step)
      (let [[_ _ [fx fy fz]] cells
            target [(+ fx 0.5) (double fy) (+ fz 0.5)]]
        (cond
          (<= y-now (- started-y 0.5))                     ; the feet went down: the stair is cut and taken
          (-> world (assoc :player/controls {}) (set-i :intent/stage :plan) (drop-i :intent/child :intent/cells))

          (> (- tick started-tick) step-timeout-ticks)
          (-> world (assoc :player/controls {}) (intent/fail :stair-not-taken))

          :else
          (assoc world :player/controls (intent/toward world target 0.0)))))))
