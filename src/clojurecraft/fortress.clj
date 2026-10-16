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

(defn toward "The sign of d: -1, 0 or 1." [d] (long (math/signum (double d))))

(defn moves
  "The steps to try from feet toward target, best first: along the axis with the farther way to go,
   then the other, then sideways; each up or down first when target is more than climb-past
   above or below, then level, then the other way."
  [[fx fy fz] [tx ty tz]]
  (let [ddx (- tx fx)
        ddz (- tz fz)
        ddy (- ty fy)
        dy (cond (< ddy (- climb-past)) -1
                 (> ddy climb-past) 1
                 :else 0)
        ys (distinct [dy 0 (- dy) (if (zero? dy) -1 dy) 1])
        x [(toward ddx) 0]
        z [0 (toward ddz)]
        [a b] (if (>= (abs ddx) (abs ddz)) [x z] [z x])
        side (fn [[p q]] [[q p] [(- q) (- p)]])
        dirs (distinct (remove #(= [0 0] %) (concat [a b] (side (if (= [0 0] a) b a)))))]
    (for [[dx dz] dirs dy ys] [dx dy dz])))

(defn choose-move
  "The first of moves from feet toward target that is safe?, or nil."
  [world feet target]
  (first (filter #(safe? world feet %) (moves feet target))))

(defn arrived?
  "Is the bot within reach of target (from the feet cell's centre), or within r when given?"
  ([world target] (arrived? world target reach))
  ([world target r]
   (<= (physics/distance (physics/centre (intent/feet world)) (physics/centre target)) r)))

;; ---------------------------------------------------------------- the :tunnel-to intent

(defmethod intent/run :tunnel-to
  ;; one step at a time toward :intent/target: a step's move is chosen when it starts and its ops
  ;; worked until the bot stands in the cell stepped into; done at the target (within
  ;; :intent/reach, reach when absent) or after :intent/steps steps, failed :blocked when no step
  ;; is safe
  [world {:intent/keys [target steps taken move from] r :intent/reach :or {taken 0 r reach} :as i}
   event]
  (let [feet (intent/feet world)]
    (cond (or (arrived? world target r) (>= taken steps))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (nil? move)
          (if-let [m (choose-move world feet target)]
            (intent/assoc-intent world :intent/move m :intent/from feet)
            (-> world
                (assoc :player/controls {})
                (intent/fail :blocked)))
          :else
          (let [next (build/next-op world (step-plan from move))]
            (if (nil? next)
              (reduce intent/dissoc-intent
                      (intent/assoc-intent world :intent/taken (inc taken))
                      [:intent/move :intent/from :intent/op :intent/child])
              (build/work-op world i next event))))))

;; ---------------------------------------------------------------- the goal

(defn bricks
  "The fortress blocks on record in the bot's dimension at or above brick-min-y."
  [world]
  (for [[[_ y _ :as p] {:block/keys [state]}] (memory/latest world)
        :when (and (brick? state) (>= y brick-min-y))]
    p))

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

(defmethod plan/next-intent :fortress
  ;; tunnel to the nearest brick on record; with none, explore; with nowhere safe, or outside the
  ;; Nether (the planner waits there for a row of another dimension anyway), wait
  [world _ _]
  (let [t (when (= dimension/nether (dimension/of world))
            (or (nearest-brick world) (exploring world)))]
    (cond (not= dimension/nether (dimension/of world)) {:plan/wait :other-dimension}
          t {:intent/kind :tunnel-to :intent/target t :intent/steps steps-per-tunnel}
          :else {:plan/wait :no-way})))
