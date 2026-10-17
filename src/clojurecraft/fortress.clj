(ns clojurecraft.fortress
  "Reaching a nether fortress (issue #11): the :fortress goal and the :tunnel-to intent (stone's
   :tunnel digs along a direction for ore; this one goes to a target).

   Finding the fortress is passive: its bricks are sightings like logs (memory/landmark-kinds),
   remembered as each chunk loads, in the dimension they were seen in. Reaching it is enclosed,
   the way ruststeve's harness went (open walks fell into the lava sea): a tunnel one step at a
   time toward the nearest brick on record, each step a small plan of ops (build/next-op,
   build/work-op) read against the world like the portal's: fill the floor ahead, dig the cells
   the body passes through, step in. A step is taken only when no cell it opens touches lava (a
   neighbour behind a wall cannot be capped before the wall is dug, and Nether lava flows a block
   every half second), else another step is tried: up or down, the other axis, sideways."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def reach 6)                         ; blocks from a fortress brick that count as at the fortress
(def brick-min-y 32)                  ; bricks under this are below the lava sea's surface (y 31): never tunnel to them
(def sight-radius 512)                ; how far a remembered brick may be and still be the target
(def steps-per-tunnel 32)             ; steps a :tunnel-to takes before the planner looks again
(def explore 48)                      ; blocks ahead an exploring tunnel aims, when no brick is on record
(def climb-past 3)                    ; a target more than this above or below makes a step go up or down

(def fortress-kinds
  "The blocks that are a fortress's, not a bastion's."
  #{:nether_bricks :nether_brick_fence :nether_brick_stairs})

(def unbreakable
  "Blocks a tunnel never opens: what cannot be dug, the portal home, and a spawner (the blaze
   rods come from it, issue #262: a tunnel to it stops beside it)."
  #{:bedrock :obsidian :crying_obsidian :barrier :nether_portal :end_portal_frame :spawner})

(defn brick? "Is state id a fortress block?" [id] (contains? fortress-kinds (blocks/name-of id)))

(def brick-states
  "Every state of a fortress block, for the indexed sightings query."
  (blocks/states-where (fn [[_ _ lo]] (brick? lo))))

;; ---------------------------------------------------------------- a step as data

(def up [0 1 0])

(defn plus "Cell a moved by offset d." [a d] (mapv + a d))

(defn opens
  "The cells a step from feet by move [dx dy dz] digs out, top first: the two the body ends in,
   and on a step down the one the head passes through, on a step up the one over the head now."
  [feet [dx dy dz]]
  (let [to (plus feet [dx dy dz])]
    (case (long dy)
      0 [(plus to up) to]
      -1 [(plus to [0 2 0]) (plus to up) to]
      1 [(plus feet [0 2 0]) (plus to up) to])))

(defn step-plan
  "The ops of one step from feet by move, in order: a floor under the cell stepped into, the cells
   dug out, then standing there (the plan's goal). Every op is worked from feet; a pure function."
  [feet move]
  (let [to (plus feet move)
        floor (plus to [0 -1 0])]
    (vec (concat
          [(build/place-op floor feet nil :floor)]
          (map #(build/dig-op % feet nil :open) (opens feet move))
          [{:build/op :stand-in :build/stand to :build/step :enter :build/done [:feet to]}]))))

(defn neighbours
  "The six cells sharing a face with c."
  [c]
  (map #(plus c %) (vals physics/face-offsets)))

(defn safe?
  "Can the step from feet by move be taken: every cell it opens and the floor loaded, none of them
   lava or something it never opens, no lava beside a cell it opens (other than the cells it opens,
   the floor, which is filled first, and the body's own), and a floor that is solid, or lava a
   block placed against a solid neighbour fills. Never air: a step over a drop is not enclosed
   (live run f1-5 stood at a cave's edge with air under the next floor, slid off while placing it
   and fell forty blocks into the lava sea)."
  [world feet move]
  (let [cells (opens feet move)
        floor (plus (plus feet move) [0 -1 0])
        own (set (concat cells [floor feet (plus feet up)]))
        at #(terrain/block-at world %)
        lava? #(contains? blocks/lava-states (at %))]
    (and (every? some? (map at (conj cells floor)))
         (not-any? lava? cells)
         (not-any? #(contains? unbreakable (blocks/name-of (at %))) cells)
         (not-any? lava? (remove own (mapcat neighbours cells)))
         (or (build/holds? world [:solid floor])
             (and (lava? floor) (= floor (build/foundation world floor)))))))

(defn dry?
  "Does the step from feet by move open no cell that is water or has water beside it (other than
   the cells it opens)? The overworld's way to a stronghold's portal room runs through aquifers
   and under oceans (issue #265), where one opened cell floods the tunnel; the Nether has no
   water, so the fortress's steps never ask."
  [world feet move]
  (let [cells (opens feet move)
        own (set cells)
        water? #(contains? blocks/water-states (terrain/block-at world %))]
    (and (not-any? water? cells) (not-any? water? (remove own (mapcat neighbours cells))))))

(defn toward "The sign of d: -1, 0 or 1." [d] (long (math/signum (double d))))

(def aside
  "The directions a step takes when target is straight over or under the feet: every one, since
   no step goes straight up or down (a target three over the feet, a spawner's, left the tunnel
   with no move at all, issue #262)."
  [[1 0] [0 1] [-1 0] [0 -1]])

(defn moves
  "The steps to try from feet toward target, best first: along the axis with the farther way to go,
   then the other, then sideways (aside, when target is straight over or under); each up or down
   first when target is more than climb (climb-past when not given) above or below, then level,
   then the other way."
  ([feet target] (moves feet target climb-past))
  ([[fx fy fz] [tx ty tz] climb]
   (let [ddx (- tx fx)
         ddz (- tz fz)
         ddy (- ty fy)
         dy (cond (< ddy (- climb)) -1
                  (> ddy climb) 1
                  :else 0)
         ys (distinct [dy 0 (- dy) (if (zero? dy) -1 dy) 1])
         x [(toward ddx) 0]
         z [0 (toward ddz)]
         [a b] (if (>= (abs ddx) (abs ddz)) [x z] [z x])
         side (fn [[p q]] [[q p] [(- q) (- p)]])
         dirs (distinct (remove #(= [0 0] %) (concat [a b] (side (if (= [0 0] a) b a)))))
         dirs (if (seq dirs) dirs aside)]
     (for [[dx dz] dirs dy ys] [dx dy dz]))))

(defn choose-move
  "The first of moves from feet toward target (climbing past climb, climb-past when not given)
   that is safe?, and dry? too when dry-only? is true, or nil."
  ([world feet target] (choose-move world feet target climb-past))
  ([world feet target climb] (choose-move world feet target climb false))
  ([world feet target climb dry-only?]
   (first (filter #(and (safe? world feet %) (or (not dry-only?) (dry? world feet %)))
                  (moves feet target climb)))))

(defn arrived?
  "Is the bot within reach of target (from the feet cell's centre), or within r when given?"
  ([world target] (arrived? world target reach))
  ([world target r]
   (<= (physics/distance (physics/centre (intent/feet world)) (physics/centre target)) r)))

;; ---------------------------------------------------------------- the :tunnel-to intent

(def step-keys
  "The keys of a :tunnel-to step in progress."
  [:intent/move :intent/from :intent/op :intent/child])

(defn off-step?
  "Has the step from from by move lost the feet: neither in the cell it started from, nor in the
   one it steps into, nor a block over either (a jump on the way)? Knocked off by a blaze's
   fireball, or moved by a fight or a cover walk between ticks of the step."
  [feet from move]
  (let [to (plus from move)] (not (contains? #{from to (plus from up) (plus to up)} feet))))

(defn lost-way?
  "Did the step's child, a walk back to the cell the step started from (the stand its digs and
   floor are worked from), fail :no-path? The feet left that cell, and a step chosen again from
   where they are needs no walk back. A walk into the cell stepped into is the step itself, and
   its failure fails the tunnel as before. Gym
   blaze-fortress-h (run 38905665758) run 1 failed its tunnel into the spawner's room :no-path
   twice in a row, and the plan with it."
  [{:intent/keys [child from]}]
  (and (= :walk (:intent/kind child))
       (= from (:intent/target child))
       (intent/failed? child)
       (= :no-path (:intent/reason child))))

(defmethod intent/run :tunnel-to
  ;; one step at a time toward :intent/target: a step's move is chosen when it starts and its ops
  ;; worked until the bot stands in the cell stepped into; done at the target (within
  ;; :intent/reach, reach when absent) or after :intent/steps steps, failed :blocked when no step
  ;; is safe. Steps go up or down first past :intent/climb blocks off (climb-past when absent);
  ;; with :intent/dry? only steps that open no cell by water (dry?: the overworld's, issue #265)
  [world
   {:intent/keys [target steps taken move from climb dry?]
    r :intent/reach
    :or {taken 0 r reach climb climb-past}
    :as i} event]
  (let [feet (intent/feet world)]
    (cond (or (arrived? world target r) (>= taken steps))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (and move (or (off-step? feet from move) (lost-way? i)))
          (reduce intent/dissoc-intent (assoc world :player/controls {}) step-keys)
          (nil? move)
          (if-let [m (choose-move world feet target climb (boolean dry?))]
            (intent/assoc-intent world :intent/move m :intent/from feet)
            (-> world
                (assoc :player/controls {})
                (intent/fail :blocked)))
          :else
          (let [next (build/next-op world (step-plan from move))]
            (if (nil? next)
              (reduce intent/dissoc-intent
                      (intent/assoc-intent world :intent/taken (inc taken))
                      step-keys)
              (build/work-op world i next event))))))

;; ---------------------------------------------------------------- the goal

(defn bricks
  "The fortress blocks on record in the bot's dimension at or above brick-min-y, from the
   indexed current sightings (memory/positions-now). After the room in issue #328 was restored,
   scanning every sighting each tick exhausted the snapshot judge's four-minute budget."
  [world]
  (for [[_ y _ :as p] (memory/positions-now world brick-states) :when (>= y brick-min-y)] p))

(defn nearest-brick
  "The fortress block on record nearest the feet (within sight-radius), or nil."
  [world]
  (memory/nearest-of (physics/centre (intent/feet world)) sight-radius (bricks world)))

(defn exploring
  "Where to tunnel with no brick on record: explore blocks along the first axis (east, west,
   south, north) whose first step is safe, at the feet's height; nil when none is."
  [world]
  (let [feet (intent/feet world)]
    (some (fn [d] (let [t (plus feet (mapv #(* explore %) d))] (when (choose-move world feet t) t)))
          [[1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])))

(defmethod plan/done-by :at-fortress?
  [world _]
  (boolean (and (= dimension/nether (dimension/of world))
                (some #(arrived? world %) (bricks world)))))

(defmethod plan/done-by :nether-access?
  [world _]
  (or (not= dimension/overworld (dimension/of world))
      (not (contains? (:plan/reached world) :nether))
      (empty? (plan/undone world (filter #(= dimension/nether (:goal/dimension %)) plan/goals)))))

(defmethod plan/next-intent :return-nether
  [world goal table]
  (when-not (plan/done-by world goal)
    (plan/next-intent world (assoc goal :goal/plan :build) table)))

(defmethod plan/next-intent :fortress
  ;; tunnel to the nearest brick on record; with none, explore; with nowhere safe, or outside the
  ;; Nether (the planner waits there for a row of another dimension anyway), wait
  [world _ _]
  (let [t (when (= dimension/nether (dimension/of world))
            (or (nearest-brick world) (exploring world)))]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          t {:intent/kind :tunnel-to :intent/target t :intent/steps steps-per-tunnel}
          :else {:plan/wait :no-way})))
