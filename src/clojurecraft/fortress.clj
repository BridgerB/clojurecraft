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
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def reach 6)                         ; blocks from a fortress brick that count as at the fortress
(def brick-min-y 32)                  ; bricks under this are below the lava sea's surface (y 31): never tunnel to them
(def sight-radius 512)                ; how far a remembered brick may be and still be the target
(def steps-per-tunnel 32)             ; steps a :tunnel-to takes before the planner looks again
(def explore-spacing 160)             ; blocks between the strips an exploring tunnel sweeps: a server shows 96 either side of the feet
(def explore-band [52 90])            ; the heights an exploring tunnel keeps to: over the lava sea (y 31) and its lakes, under the roof
(def bridge-fall 2)                   ; blocks of air a laid floor may hang over solid ground: a fall that costs no health
(def look-ahead 2)                    ; blocks ahead that must be loaded for a leg to go on: the world's edge turns it
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

(defmethod build/holds? :walk-floor [world [_ p]] (path/floor? world p))

(defmethod build/holds? :landed-centred
  [world [_ p]]
  (and (:player/on-ground? world) (intent/centred? world p)))

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
  "The ops of one step from feet by move, in order: replace an unwalkable floor under the cell
   stepped into, dig the cells, then stand there. Every op is worked from feet; a pure function."
  [feet move]
  (let [to (plus feet move)
        floor (plus to [0 -1 0])]
    (vec (concat [(build/dig-op floor feet [:walk-floor floor] :floor-clear)
                  (build/place-op floor feet nil :floor)]
                 (map #(build/dig-op % feet nil :open) (opens feet move))
                 [{:build/op :stand-in
                   :build/stand to
                   :build/step :enter
                   :build/done [:landed-centred to]}]))))

(defn neighbours
  "The six cells sharing a face with c."
  [c]
  (map #(plus c %) (vals physics/face-offsets)))

(defn shallow?
  "Is the first block under floor that is not air, within bridge-fall below it, solid (never a
   fluid, never the void or a cell not loaded)? A floor laid there hangs over ground a fall
   reaches without harm."
  [world [x y z]]
  (let [kinds (map #(path/classify world [x % z]) (range (dec y) (- y (inc bridge-fall)) -1))]
    (= :solid (first (remove #{:clear} kinds)))))

(defn safe?
  "Can the step from feet by move be taken: every cell it opens and the floor loaded, none of them
   lava or something it never opens, no lava beside a cell it opens (other than the cells it opens,
   the floor, which is filled first, and the body's own), and a floor that is solid, or lava a
   block placed against a solid neighbour fills. Never air: a step over a drop is not enclosed
   (live run f1-5 stood at a cave's edge with air under the next floor, slid off while placing it
   and fell forty blocks into the lava sea), unless the air floor hangs over solid ground within
   bridge-fall (shallow?) and a solid neighbour holds the block laid in it: a cavern's floor is
   bridged a block at a time, from the floor the feet stand on (sim race 39099976057 runner 2, world
   R2/4: thirty minutes at a time paced on a ledge, every step ahead an air floor). A solid floor the route cannot walk on is replaced
   only when digging it opens no fluid and leaves support for its replacement. No opened cell
   may drop a gravel or sand bed carrying fluid into the tunnel."
  [world feet move]
  (let [cells (opens feet move)
        floor (plus (plus feet move) [0 -1 0])
        own (set (concat cells [floor feet (plus feet up)]))
        at #(terrain/block-at world %)
        lava? #(contains? blocks/lava-states (at %))]
    (and
     (every? some? (map at (conj cells floor)))
     (not-any? lava? cells)
     (not-any? #(and (blocks/solid? (at %)) (contains? unbreakable (blocks/name-of (at %)))) cells)
     (not-any? lava? (remove own (mapcat neighbours cells)))
     (not-any? #(or (intent/fluid-over-falling? world %) (intent/bed-over-fluid? world %)) cells)
     (or (path/floor? world floor)
         (and (build/holds? world [:solid floor])
              (not (contains? unbreakable (blocks/name-of (at floor))))
              (not (intent/exposes-fluid? world floor))
              (= floor (build/foundation world floor)))
         (and (lava? floor) (= floor (build/foundation world floor)))
         (and (= :clear (path/classify world floor))
              (shallow? world floor)
              (= floor (build/foundation world floor)))))))

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
  "Every horizontal direction, completing the moves toward a target with ways aside and back.
   A target straight over or under the feet needs them all (issue #262)."
  [[1 0] [0 1] [-1 0] [0 -1]])

(defn moves
  "The steps to try from feet toward target, best first: along the axis with the farther way to go,
   then the other, then sideways and back (aside, when target is straight over or under); each
   up or down first when target is more than climb (climb-past when not given) above or below,
   then level, then the other way."
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
         dirs (distinct (remove #(= [0 0] %) (concat [a b] (side (if (= [0 0] a) b a)) aside)))]
     (for [[dx dz] dirs dy ys] [dx dy dz]))))

(defn choose-move
  "The first of moves from feet toward target (climbing past climb, climb-past when not given)
   that is safe?, and dry? too when dry-only? is true, or nil."
  ([world feet target] (choose-move world feet target climb-past))
  ([world feet target climb] (choose-move world feet target climb false))
  ([world feet target climb dry-only?]
   (first (filter #(and (safe? world feet %) (or (not dry-only?) (dry? world feet %)))
                  (moves feet target climb)))))

(defn next-move
  "The next tunnel move: a safe unvisited cell first, then a short floored turn onto adjoining
   ground, then a safe retreat. A turn fills one air floor from the current stand before entering;
   it needs support on another side too and never bridges over fluid."
  [world feet target climb dry-only? visited]
  (let [ways (moves feet target climb)
        fresh (remove #(contains? visited (plus feet %)) ways)
        allowed? #(and (safe? world feet %) (or (not dry-only?) (dry? world feet %)))
        turn? (fn [move]
                (let [floor (plus (plus feet move) [0 -1 0])
                      own-floor (plus feet [0 -1 0])]
                  (and (zero? (second move))
                       (build/holds? world [:solid own-floor])
                       (contains? #{:air :cave_air :void_air}
                                  (blocks/name-of (terrain/block-at world floor)))
                       (not (stairs/over-fluid? world floor))
                       (= floor (build/foundation world floor))
                       (some #(and (not= own-floor %)
                                   (= (second floor) (second %))
                                   (build/holds? world [:solid %]))
                             (neighbours floor))
                       (or (not dry-only?) (dry? world feet move)))))]
    (or (first (filter allowed? fresh))
        (first (filter (fn [move]
                         (let [floor (plus (plus feet move) [0 -1 0])
                               filled (assoc-in world
                                       [:world/blocks floor]
                                       (blocks/default-state :cobblestone))]
                           (and (turn? move) (safe? filled feet move))))
                       fresh))
        (first (filter allowed? ways)))))

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
   {:intent/keys [target steps taken move from climb dry? tunnel-visited child search]
    r :intent/reach
    :or {taken 0 r reach climb climb-past tunnel-visited #{}}
    :as i} event]
  (let [world (cond-> world search (assoc :fortress/search search)) ; the spiral's leg, kept for the planner's next look
        feet (intent/feet world)]
    (cond (or (arrived? world target r) (>= taken steps))
          (-> world
              (assoc :player/controls {})
              intent/done)
          (and move (or (off-step? feet from move) (lost-way? i)))
          (reduce intent/dissoc-intent (assoc world :player/controls {}) step-keys)
          (nil? move)
          (if-let [m (next-move world feet target climb (boolean dry?) tunnel-visited)]
            (intent/assoc-intent (assoc world :player/controls {})
                                 :intent/move m
                                 :intent/from feet
                                 :intent/tunnel-visited (conj tunnel-visited feet))
            (-> world
                (assoc :player/controls {})
                (intent/fail :blocked)))
          :else
          (let [next (build/next-op world (step-plan from move))
                next (cond-> next
                       (= :place (:build/op next))
                       (assoc :build/item (or (stairs/filler world 1) :cobblestone)))]
            (cond (nil? next)
                  (reduce
                   intent/dissoc-intent
                   (intent/assoc-intent (assoc world :player/controls {}) :intent/taken (inc taken))
                   step-keys)
                  (and (#{:floor-clear :open :enter} (:build/step next))
                       (not (safe? world from move)))
                  (reduce intent/dissoc-intent (assoc world :player/controls {}) step-keys)
                  (and (= :enter (:build/step next))
                       (= :walk (:intent/kind child))
                       (intent/failed? child)
                       (= :no-path (:intent/reason child))
                       (= :none (:intent/route child))
                       (empty? (:intent/waypoints child))
                       (intent/routable? world child))
                  (intent/dissoc-intent (assoc world :player/controls {}) :intent/child)
                  :else
                  (let [world (build/work-op world i next event)
                        i (:plan/intent world)]
                    (cond (and (intent/failed? i) (= :no-pickaxe (:intent/reason i)))
                          (-> world
                              (assoc :player/controls {})
                              (intent/dissoc-intent :intent/reason)
                              intent/done)
                          :else world)))))))

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

;; ---------------------------------------------------------------- exploring: a spiral

(defn leg-length
  "Blocks of the leg-th leg (from 0) of an exploring spiral: explore-spacing for the first two, then
   each pair one spacing longer, so the strips the legs sweep lie a spacing apart."
  [leg]
  (* explore-spacing (inc (quot (long leg) 2))))

(defn turn-right
  "The horizontal direction [dx dz] a quarter turn clockwise from heading: east, south, west, north."
  [[dx dz]]
  [(- dz) dx])

(defn explore-y
  "The height an exploring leg from feet aims at: the feet's own, kept within explore-band, so a
   tunnel that drifted down toward the bedrock climbs back and one in the roof's rock comes down."
  [[_ y _]]
  (let [[lo hi] explore-band]
    (-> (long y)
        (max (long lo))
        (min (long hi)))))

(defn leg-target
  "The cell the leg of search ({:search/from :search/heading :search/leg :search/tunnels}) aims
   at: its length along its heading from where it began, at that cell's explore-y."
  [{:search/keys [from heading leg]}]
  (let [[x _ z] from
        [dx dz] heading
        n (leg-length leg)]
    [(+ x (* n dx)) (explore-y from) (+ z (* n dz))]))

(defn open-ahead?
  "Is the cell look-ahead blocks from feet along heading [dx dz] loaded? A real server shows 96
   blocks round the feet, so only the model's captured Nether ends in front of a tunnel."
  [world feet [dx dz]]
  (some? (terrain/block-at world (plus feet [(* look-ahead dx) 0 (* look-ahead dz)]))))

(defn new-leg
  "The search whose leg-th leg begins at feet along the first of headings that has a way to go:
   the world goes on ahead (open-ahead?) and a step toward the leg's end is safe (choose-move);
   nil when none has."
  [world feet leg headings]
  (some (fn [h]
          (let [s {:search/from feet :search/heading h :search/leg leg :search/tunnels 0}]
            (when (and (open-ahead? world feet h) (choose-move world feet (leg-target s))) s)))
        headings))

(defn leg-over?
  "Is the leg of search finished: its end within reach (by the ground, a tunnel's height may stay
   short of it), the world ending ahead, more tunnels spent on it than its length asks (a wall it
   cannot go round), or the feet so far from where it began that it is another trip's?"
  [world {:search/keys [from heading leg tunnels] :as search}]
  (let [feet (intent/feet world)
        at (physics/centre feet)]
    (or (<= (physics/horizontal-distance at (physics/centre (leg-target search))) reach)
        (not (open-ahead? world feet heading))
        (> tunnels (+ 2 (* 2 (quot (leg-length leg) steps-per-tunnel))))
        (> (physics/horizontal-distance at (physics/centre from)) (+ (leg-length leg) 64)))))

(defn exploring
  "The search an exploring tunnel carries, with no brick on record: the world's :fortress/search
   while its leg goes on (one more tunnel spent), else the next leg, a quarter turn right from the
   last one's heading and a longer one, from the feet; the first leg, east when it can go, with no
   search. A leg that cannot go the way it turns tries the next heading, so the model's edge
   turns the tunnel. nil when no heading has a way to go. Replaces the tunnel that aimed 48 blocks
   east for ever: sim race 39095704051 runner 4 (issue #338) went 630 blocks east along the
   bedrock past a fortress 150 blocks north."
  [world]
  (let [feet (intent/feet world)
        {:search/keys [heading leg] :as search} (:fortress/search world)
        headings #(take 4 (iterate turn-right %))]
    (cond (nil? search) (new-leg world feet 0 (headings [1 0]))
          (leg-over? world search)
          (new-leg world feet (inc (long leg)) (headings (turn-right heading)))
          :else (update search :search/tunnels inc))))

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
  ;; replace a missing pickaxe from held supplies, then tunnel to the nearest brick on record;
  ;; with none, the next leg of the spiral search (exploring); with nowhere safe, or outside the
  ;; Nether, wait
  [world goal table]
  (let [nether? (= dimension/nether (dimension/of world))
        brick (when nether? (nearest-brick world))
        search (when (and nether? (not brick)) (exploring world))]
    (cond (not nether?) {:plan/wait :other-dimension}
          :else
          (or (when (build/pickaxe-at-hand? world)
                (make/decide world (assoc goal :goal/provides {:tag/pickaxes 1}) table))
              (when brick
                {:intent/kind :tunnel-to :intent/target brick :intent/steps steps-per-tunnel})
              (when search
                {:intent/kind :tunnel-to
                 :intent/target (leg-target search)
                 :intent/steps steps-per-tunnel
                 :intent/search search})
              {:plan/wait :no-way}))))
