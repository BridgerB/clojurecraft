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
(def leg-max (* 3 explore-spacing))   ; the longest leg of the spiral: a leg longer than a judge window's walk only ever sweeps one strip
(def explore-band [52 90])            ; the heights an exploring tunnel keeps to: over the lava sea (y 31) and its lakes, under the roof
(def air-grace 40)                    ; ticks a tunnel waits for the feet to land before it may fail :blocked: no step is safe from the air (issue #502, R4/31)
(def look-ahead 2)                    ; blocks ahead that must be loaded for a leg to go on: the world's edge turns it
(def climb-past 3)                    ; a target more than this above or below makes a step go up or down
(def stall-tunnels 3)                 ; tunnels in a row that gain no stall-gain toward a leg's end before the leg is given up
(def stall-gain 8)                    ; blocks a tunnel must bring the feet nearer a leg's end to count as progress (rock gives about 32)
(def bridge-ground-depth 96)           ; blocks under a floor to be laid over a cavern looked down through for ground: the fall of a body knocked off
(def shaft-aside 8)                   ; blocks a brick may stand aside of the feet and still be climbed as a shaft, not a stair
(def bridge-min-filler 16)            ; filler a bridge over a cavern keeps in hand: it stops when fewer are left
(def bridge-lava-health 18)           ; health a bridge over lava is begun and carried on with: never hurt
(def bridge-min-health 14)            ; health a bridge over a cavern is begun and carried on with
(def rock-look 24)                    ; cells along a heading a new leg looks for rock in
(def retreats-allowed 1)            ; retreats in a row a tunnel takes before it is blocked: a second one paces two cells

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

(defn lava-beside
  "The lava cells beside the cells the step from feet by move opens, other than the cells it
   opens, the floor (filled first) and the body's own: what safe? refuses a step for, and what
   sealable? may cap before it."
  [world feet move]
  (let [cells (opens feet move)
        floor (plus (plus feet move) [0 -1 0])
        own (set (concat cells [floor feet (plus feet up)]))]
    (filter #(contains? blocks/lava-states (terrain/block-at world %))
            (distinct (remove own (mapcat neighbours cells))))))

(def max-seals 8)                      ; lava cells one step may cap before it is taken

(defn sealable?
  "May every cell of lava be capped from the stand: a solid neighbour to place against
   (build/foundation is the cell itself) and, for all of them together, a filler carried, at
   most max-seals. A block placed into a source or a flow replaces it; nothing is dug, so no lava
   runs into the tunnel."
  [world lava]
  (and (<= (count lava) max-seals)
       (some? (stairs/filler world (count lava)))
       (every? #(= % (build/foundation world %)) lava)))

(defn walk-filler
  "The filler a step's floor at cell is laid from: stairs/filler's item when a block of it laid
   at cell stands as a floor (path/floor?), else nil. Soul sand, the last resort a hand-dug
   tunnel carries, is no floor to a route (path treats it as awkward), so a step that laid it
   in its floor dug it out and laid it again for ever (sim snapshot R1-20-1, issue #537: one
   step's dig and place on the same cell for the whole window)."
  [world cell]
  (let [item (stairs/filler world 1)]
    (when (and item
               (path/floor? (assoc-in world [:world/blocks cell] (blocks/default-state item)) cell))
      item)))

(defn safe-but-lava?
  "Can the step from feet by move be taken but for the lava beside the cells it opens (safe?
   with that one clause left out): every cell it opens and the floor loaded, none of them lava or
   something it never opens, and a floor that is solid, or lava a block placed against a solid
   neighbour fills, or a solid floor the route cannot walk on, replaced only with a filler
   carried that stands as a floor once laid (walk-filler)."
  [world feet move]
  (let [cells (opens feet move)
        floor (plus (plus feet move) [0 -1 0])
        at #(terrain/block-at world %)
        lava? #(contains? blocks/lava-states (at %))
        filler (walk-filler world floor)]
    (and
     (every? some? (map at (conj cells floor)))
     (not-any? lava? cells)
     (not-any? #(and (blocks/solid? (at %)) (contains? unbreakable (blocks/name-of (at %)))) cells)
     (not-any? #(or (intent/fluid-over-falling? world %) (intent/bed-over-fluid? world %)) cells)
     (or (path/floor? world floor)
         (and filler
              (build/holds? world [:solid floor])
              (not (contains? unbreakable (blocks/name-of (at floor))))
              (not (intent/exposes-fluid? world floor))
              (= floor (build/foundation world floor)))
         (and filler (lava? floor) (= floor (build/foundation world floor)))))))

(defn safe?
  "Can the step from feet by move be taken: every cell it opens and the floor loaded, none of them
   lava or something it never opens, no lava beside a cell it opens (other than the cells it opens,
   the floor, which is filled first, and the body's own), and a floor that is solid, or lava a
   block placed against a solid neighbour fills. Never air: a step over a drop is not enclosed
   (live run f1-5 stood at a cave's edge with air under the next floor, slid off while placing it
   and fell forty blocks into the lava sea). A solid floor the route cannot walk on is replaced
   only when digging it opens no fluid, leaves support for its replacement and a filler is
   carried to replace it with (sim snapshot R3-38-1, issue #410, dug a soul sand floor with none
   left and failed :not-held three times). No opened cell may drop a gravel or sand bed carrying
   fluid into the tunnel."
  [world feet move]
  (and (empty? (lava-beside world feet move)) (safe-but-lava? world feet move)))

(defn sealed?
  "Can the step from feet by move be taken once the lava beside the cells it opens is capped
   (seal-ops): there is some, sealable? caps all of it, and safe-but-lava? holds. The way out of
   a stand ringed with lava, which safe? refuses every step from. Real snapshot real-5-s1-Clj_r11
   (issue #286) arrived in a portal cast into a lava lake, lava north and south of it and
   obsidian east and west, and failed :blocked at every tick."
  [world feet move]
  (let [lava (lava-beside world feet move)]
    (and (seq lava) (sealable? world lava) (safe-but-lava? world feet move))))

(defn seal-ops
  "The ops that cap the lava beside the cells the step from feet by move opens, each placed from
   the feet, before the step's own ops (step-plan); none when there is none or the step is not
   sealed? (a step that safe? refuses stays refused, and is dropped as before)."
  [world feet move]
  (if (sealed? world feet move)
    (mapv #(build/place-op % feet nil :seal) (lava-beside world feet move))
    []))

(defn dry?
  "Does the step from feet by move open no cell that is water or has water beside it (other than
   the cells it opens)? The overworld's way to a stronghold's portal room runs through aquifers
   and under oceans (issue #265), where one opened cell floods the tunnel; the Nether has no
   water, so the fortress's steps never ask."
  [world feet move]
  (let [cells (opens feet move)
        own (set cells)
        water? (fn [c]
                 (let [id (terrain/block-at world c)]
                   (or (contains? blocks/water-states id)
                       (= :water (terrain/fluid-kind world c)))))]
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
   then level, then the other way. A target farther above or below than it is aside (a fortress
   brick overhead) takes the height first of every direction, then the level, so a safe step up
   in a lesser direction beats a step level or down in the first: the bot dug a zigzag down to
   the bedrock under bricks 37 and 47 blocks over its head (sim snapshots R2-25-2 and R2-6-2,
   issues #538 and #338)."
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
     (if (and (not (zero? dy)) (> (abs ddy) (+ (abs ddx) (abs ddz))))
       (for [dy ys [dx dz] dirs] [dx dy dz])
       (for [[dx dz] dirs dy ys] [dx dy dz])))))

(defn choose-move
  "The first of moves from feet toward target (climbing past climb, climb-past when not given)
   that is safe?, and dry? too when dry-only? is true, or nil."
  ([world feet target] (choose-move world feet target climb-past))
  ([world feet target climb] (choose-move world feet target climb false))
  ([world feet target climb dry-only?]
   (first (filter #(and (safe? world feet %) (or (not dry-only?) (dry? world feet %)))
                  (moves feet target climb)))))

(defn bridge?
  "May the level move from feet be floored across a cave, the overworld's dry tunnel only: the
   floor it steps onto air that the block under the feet holds (build/foundation), no water or
   lava under it within stairs/bridge-depth (stairs/over-fluid?: a causeway over a lake is no
   cave's floor, unless fluid-ok?: bridge-over-void? asks for ground itself), a filler carried,
   the step dry? and, with that floor laid, safe?. The fortress's tunnel bridges only by
   bridge-over-void?."
  ([world feet move] (bridge? world feet move false))
  ([world feet move fluid-ok?]
   (let [floor (plus (plus feet move) [0 -1 0])]
     (and (zero? (second move))
          (build/holds? world [:solid (plus feet [0 -1 0])])
          (contains? #{:air :cave_air :void_air} (blocks/name-of (terrain/block-at world floor)))
          (= floor (build/foundation world floor))
          (or fluid-ok? (not (stairs/over-fluid? world floor)))
          (some? (stairs/filler world 1))
          (dry? world feet move)
          (safe? (assoc-in world [:world/blocks floor] (blocks/default-state :cobblestone))
                 feet
                 move)))))

(defn ground-below?
  "Is the first block under the floor cell [x y z] (within bridge-ground-depth) that is not air
   a solid block (any shape, basalt's among them), nothing but air over it, or with lava-ok? air
   and lava: ground a body would land on, never water, never the end of the loaded world? A
   floor laid where it is false is a bridge over water or the unknown, or over lava."
  ([world cell] (ground-below? world cell false))
  ([world [x y z] lava-ok?]
   (= :ground
      (reduce (fn [_ k]
                (let [id (terrain/block-at world [x (- y k) z])]
                  (cond (or (nil? id)
                            (contains? blocks/water-states id)
                            (and (not lava-ok?) (contains? blocks/lava-states id)))
                        (reduced false)
                        (blocks/solid? id) (reduced :ground)
                        :else nil)))
              nil
              (range 1 (inc bridge-ground-depth))))))

(defn bridge-over-void?
  "May the level move from feet be floored across a cavern in the Nether, bridge? holding (the
   floor laid from the block under the feet, which the body stands on while it is laid and
   leaves only for the laid block) and, under that floor, ground-below? (over lava too only at
   bridge-lava-health, the sea under the Nether's caves: never bridged hurt), a filler reserve
   of bridge-min-filler and bridge-min-health: never over water, never hurt, never short of
   blocks. Sim world R2/12 (issue #338) met a cavern sixty blocks across over the lava sea, the
   basalt 26 blocks under its floor, that no step round its rim crossed in an hour."
  [world feet move]
  (let [health (double (or (:player/health world) 20.0))
        floor (plus (plus feet move) [0 -1 0])]
    (and (>= health bridge-min-health)
         (bridge? world feet move true)
         (or (ground-below? world floor)
             (and (>= health bridge-lava-health) (ground-below? world floor true)))
         (some? (stairs/filler world bridge-min-filler)))))

(defn climb-out?
  "May the up move from feet stand on a block laid beside the feet at their own height: the
   step's floor (the cell beside the feet, under the cell stepped into) air that a solid
   neighbour holds up (build/foundation: a ledge's rock, a wall, a block laid before), the
   block under the feet solid, a filler carried that stands as a floor once laid
   (walk-filler: soul sand laid is dug out again, and the climb offered for ever, sim
   snapshot R1-20-1, issue #537), the step dry? when dry-only?, and with that
   floor laid, safe?. The way out of a dead end over a drop, where no level step has a floor and
   no turn may floor over the lava sea: the body stays on its stand while the block is laid
   against the ledge, then climbs onto it, so it is never a causeway. Sim snapshot R3-6-1 (issue
   #338) stood on two blocks of a ledge seven over the lava sea, every way round them air, and
   paced them for four hours."
  [world feet move dry-only?]
  (let [floor (plus (plus feet move) [0 -1 0])]
    (and (pos? (long (second move)))
         (build/holds? world [:solid (plus feet [0 -1 0])])
         (contains? #{:air :cave_air :void_air} (blocks/name-of (terrain/block-at world floor)))
         (= floor (build/foundation world floor))
         (some? (walk-filler world floor))
         (or (not dry-only?) (dry? world feet move))
         (safe? (assoc-in world [:world/blocks floor] (blocks/default-state :cobblestone))
                feet
                move))))

(defn gaining?
  "Does move from feet lessen the vertical gap to target: a step up when the target is above, a
   step down when it is below? A level step does not. The way back up a stair already stood in is
   still progress, so it is not counted as a retreat (issue #526)."
  [feet move target]
  (let [dy (long (second move))
        ddy (- (long (second target)) (long (second feet)))]
    (and (not (zero? dy))
         (not (zero? ddy))
         (= (long (math/signum (double dy))) (long (math/signum (double ddy)))))))

(defn next-move
  "The next tunnel move: a safe unvisited cell first (in the dry tunnel, one floored across a cave
   as soon, bridge?, in the order of moves), then a short floored turn onto adjoining ground, then
   a climb onto a block laid beside the feet (climb-out?), then a step once the lava beside it is
   capped (sealed?), then a safe retreat. While the target is more than climb above the feet, a
   downward safe step waits until that climb, including one back into a cell already stood in: the
   only safe step was down a cobble stair onto an obsidian pad, and the pad then refused every
   fresh step (sim snapshots R2-29-1 and R2-36-1, issue #526). A turn fills one
   air floor from the current stand before entering; it needs support on another side too and
   never bridges over fluid. Gym batch stronghold-room-b (plan run 39132210882) run 3 of the
   portal room staired down through a cave to the frame's height, 126 blocks from it, every step
   toward it over the cave's air, and walked its own stairs 32 steps a tunnel for 23 minutes to
   the timeout."
  ([world feet target climb dry-only? visited]
   (next-move world feet target climb dry-only? visited false))
  ([world feet target climb dry-only? visited bridge-ok?]
   (let [ways (moves feet target climb)
         fresh (remove #(contains? visited (plus feet %)) ways)
         allowed? #(and (safe? world feet %) (or (not dry-only?) (dry? world feet %)))
         first-way? (cond dry-only? #(or (allowed? %) (bridge? world feet %))
                          bridge-ok? #(or (allowed? %) (bridge-over-void? world feet %))
                          :else allowed?)
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
                        (or (not dry-only?) (dry? world feet move)))))
         up? (> (- (long (second target)) (long (second feet))) (long climb))
         climb? #(climb-out? world feet % dry-only?)
         down? #(neg? (long (second %)))]
     (or (first (filter #(and (first-way? %) (or (not up?) (not (down? %)))) fresh))
         (first (filter (fn [move]
                          (let [floor (plus (plus feet move) [0 -1 0])
                                filled (assoc-in world
                                        [:world/blocks floor]
                                        (blocks/default-state :cobblestone))]
                            (and (turn? move) (safe? filled feet move))))
                        fresh))
         (first (filter climb? fresh))
         (when up? (first (filter climb? ways)))
         (when up? (first (filter #(and (first-way? %) (down? %)) fresh)))
         (first (filter #(and (sealed? world feet %) (or (not dry-only?) (dry? world feet %)))
                        fresh))
         (first (filter allowed? ways))))))

(defn on-bridge?
  "Do the feet stand on a block with air under it, a bridge being crossed: the block under the
   feet solid and the one under that clear? A bridge stays a bridge-over-void? candidate when the
   leg's progress ends its stall in the middle of the span."
  [world]
  (let [[x y z] (intent/feet world)]
    (and (path/floor? world [x (dec y) z]) (= :clear (path/classify world [x (- y 2) z])))))

(defn ground-near?
  "Is there ground within two cells of the feet: a walk floor (path/floor?) with a solid
   block under it, not a bridge block over air? The end of a bridge against rock is ground
   for making a pickaxe, though the feet still stand on the bridge (issue #559)."
  [world]
  (let [[fx fy fz] (intent/feet world)]
    (boolean (some (fn [[dx dz]]
                     (let [floor [(+ fx (long dx)) (dec (long fy)) (+ fz (long dz))]]
                       (and (path/floor? world floor)
                            (blocks/solid? (terrain/block-at world (update floor 1 dec))))))
                   (for [dx (range -2 3) dz (range -2 3)] [dx dz])))))

(defn retreat?
  "Does move from feet step into a cell visited already stood in: next-move's last resort, a
   retreat (a fresh step, turn or climb goes to an unvisited cell, and a climb that gains height
   toward the target is taken even when the cell was stood in)?"
  [feet move visited]
  (contains? visited (plus feet move)))

(defn perched?
  "Do the feet stand over air: the cell under the feet's cell holds nothing solid, the body held
   up by the edge of a block beside it? A walk has no route from such a cell. Sim snapshots R1-32-2
   and R3-17-1 (issue #284) stood with the centre half a block past a ledge's edge, and every
   walk to the next step's stand failed :no-path."
  [world feet]
  (build/holds? world [:not-solid (plus feet [0 -1 0])]))

(defn recentre
  "The level step from feet onto a floor beside them when the feet are perched? (a move to a
   neighbouring cell whose floor is solid, safe?), else nil: the body stands over its footing
   again before the next step is chosen."
  [world feet]
  (when (perched? world feet)
    (first (for [[dx dz] aside
                 :let [move [dx 0 dz]]
                 :when (and (path/standable? world (plus feet move)) (safe? world feet move))]
             move))))

(defn arrived?
  "Is the bot within reach of target (from the feet cell's centre), or within r when given?"
  ([world target] (arrived? world target reach))
  ([world target r]
   (<= (physics/distance (physics/centre (intent/feet world)) (physics/centre target)) r)))

;; ---------------------------------------------------------------- the way back

(def trail-cap 512)                   ; cells of the way back a tunnel keeps

(defn trail-of
  "The way back the world keeps for a tunnel to target: {:trail/target target :trail/cells the
   feet cells steps began from, oldest first :trail/dead the cells found to lead nowhere}. A
   trail kept for another target is not this one's: an empty one."
  [world target]
  (let [t (:fortress/trail world)]
    (if (= target (:trail/target t)) t {:trail/target target :trail/cells [] :trail/dead #{}})))

(defn step-back
  "The move from feet back to the last cell of trail, or nil when the trail is empty, that cell is
   not one step away (a respawn, a knock) or the step is not safe? (and dry? when dry-only?). The
   way out of a cell no fresh step leaves: sim snapshots R3-5-2 and R4-11-1 (issue #502) went
   into pockets whose every fresh step was refused, retreated once, paced and failed :blocked
   with the branch that went on a few cells back never tried."
  [world feet trail dry-only?]
  (when-let [to (peek (:trail/cells trail))]
    (let [move (mapv - to feet)]
      (when (and (some #{move} (for [[dx dz] aside dy [-1 0 1]] [dx dy dz]))
                 (safe? world feet move)
                 (or (not dry-only?) (dry? world feet move)))
        move))))

(defn leave-cell
  "The trail once a step is chosen from feet: when the step goes back (back? true) its last cell
   is dropped and feet marked dead, no fresh step being left there; else feet join its cells, the
   oldest forgotten past trail-cap."
  [trail feet back?]
  (if back?
    (-> trail
        (update :trail/cells pop)
        (update :trail/dead conj feet))
    (update trail :trail/cells #(vec (take-last trail-cap (conj % feet))))))

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

(defn buried
  "The dig that frees the body when a block stands in it (gravel fallen into the tunnel): of the
   head's cell first, then the feet's, worked from the feet; nil when both are open. A walk has no
   route from inside a block, so a step's stand-in waited on a walk that failed :no-path every
   tick and was dropped and asked again: sim snapshot R1-33-1 (issue #338) stood in gravel at y 34
   for its whole fifteen-minute window."
  [world feet]
  (some #(when (build/holds? world [:solid %]) (build/dig-op % feet nil :unbury))
        [(plus feet up) feet]))

(defn overhead?
  "Is target within shaft-aside blocks horizontally and more than climb-past above feet: a brick
   over the head. A stair up to it digs three cells a level, about twenty seconds a level by
   hand, and forty-seven of them outlast the window (sim snapshot R2-6-2, issue #338, y 9 under
   a brick at y 56). A leg's end across a cavern is not overhead."
  [[fx fy fz] [tx ty tz]]
  (and (> (- ty fy) climb-past) (<= (+ (abs (- tx fx)) (abs (- tz fz))) shaft-aside)))

(defn shaft-ready?
  "May a shaft rise from feet: a block to lay (stairs/pillar-item), the feet centred on a solid
   block, and the cells over the head safe to open (stairs/shaft-safe?). The stair's pillar-up?
   without its :stairs-up kind."
  [world feet]
  (boolean (and (stairs/pillar-item world)
                (intent/centred? world feet)
                (= :solid (path/classify world (update feet 1 dec)))
                (stairs/shaft-safe? world feet))))

(defn shaft-tick
  "One tick of a shaft toward a brick overhead, or nil when this tick is an ordinary step. While
   :intent/stage is :shaft, or it may begin (overhead?, shaft-ready?, the shaft not given up,
   the feet on the ground): dig the cell over the head by hand (the drop is not wanted), then a
   child :pillar lays a block under the feet at the top of the jump, one dig a level. A child
   that fails, or a cell that stops being safe, gives the shaft up for this tunnel
   (:intent/no-shaft) and the steps resume. Reaching the brick's height leaves the shaft. A
   level the pillar completes counts as a step."
  [world feet target {:intent/keys [stage child no-shaft move taken] :or {taken 0}} event]
  (let [shafting? (= :shaft stage)
        begin? (and (nil? move)
                    (not shafting?)
                    (not no-shaft)
                    (not (stairs/airborne? world))
                    (overhead? feet target)
                    (shaft-ready? world feet))]
    (when (and (nil? move) (or shafting? begin?))
      (let [cell (first (filter #(= :solid (path/classify world %)) (stairs/shaft-cells feet)))
            item (stairs/pillar-item world)
            open? (and item
                       (stairs/shaft-safe? world feet)
                       (intent/centred? world feet)
                       (= :solid (path/classify world (update feet 1 dec))))]
        (cond (and shafting? (nil? child) (not (overhead? feet target)))
              (intent/dissoc-intent world :intent/stage)
              (and child
                   (or (intent/done? child)
                       (and (intent/failed? child) (= :target-gone (:intent/reason child)))))
              (cond-> (intent/dissoc-intent world :intent/child)
                (= :pillar (:intent/kind child))
                (intent/assoc-intent :intent/taken (inc (long taken))))
              (and child (intent/failed? child))
              (-> world
                  (assoc :player/controls {})
                  (intent/assoc-intent :intent/no-shaft (:intent/reason child))
                  (intent/dissoc-intent :intent/child :intent/stage))
              child (intent/run-child world event)
              (stairs/airborne? world) (assoc world :player/controls {})
              (not open?)
              (-> world
                  (assoc :player/controls {})
                  (intent/assoc-intent :intent/no-shaft :unsafe)
                  (intent/dissoc-intent :intent/stage))
              cell
              (intent/assoc-intent world
                                   :intent/stage :shaft
                                   :intent/child
                                   (assoc (intent/child-dig cell) :intent/drop-ok? true))
              :else
              (intent/assoc-intent world
                                   :intent/stage :shaft
                                   :intent/child
                                   {:intent/kind :pillar
                                    :intent/item item
                                    :intent/top (inc (long (second feet)))
                                    :intent/status :active}))))))

(defmethod intent/run :tunnel-to
  ;; one step at a time toward :intent/target: a step's move is chosen when it starts and its ops
  ;; worked until the bot stands in the cell stepped into; done at the target (within
  ;; :intent/reach, reach when absent) or after :intent/steps steps, failed :blocked when no step
  ;; is safe (after waiting up to air-grace ticks for feet in the air to land: a step is judged
  ;; from the ground, and a bot knocked off a ledge by a blaze failed three tunnels in three ticks), or when the only safe steps retreat into cells already stood in more than
  ;; retreats-allowed times in a row (a pace between two cells, each counted as a step: sim
  ;; snapshot R3-6-1, issue #338, paced a ledge 32 steps a tunnel for four hours). Steps go up or
  ;; down first past :intent/climb blocks off (climb-past when absent); with :intent/dry? only
  ;; steps that open no cell by water (dry?: the overworld's, issue #265)
  [world
   {:intent/keys [target steps taken move from climb tunnel-visited child search retreats airborne]
    dry-only? :intent/dry?
    r :intent/reach
    :or {taken 0 r reach climb climb-past tunnel-visited #{} retreats 0 airborne 0}
    :as i} event]
  (let [world (cond-> world search (assoc :fortress/search search)) ; the spiral's leg, kept for the planner's next look
        feet (intent/feet world)
        shaft (shaft-tick world feet target i event)] ; nil on an ordinary step, else that tick's world
    (cond
      (or (arrived? world target r) (>= taken steps))
      (-> world
          (assoc :player/controls {})
          intent/done)
      (and move (or (off-step? feet from move) (lost-way? i)))
      (reduce intent/dissoc-intent (assoc world :player/controls {}) step-keys)
      (buried world feet)
      (build/work-op (assoc world :player/controls {}) i (buried world feet) event)
      shaft shaft
      (and (nil? move) (false? (:player/on-ground? world)) (< airborne air-grace))
      (intent/assoc-intent (assoc world :player/controls {}) :intent/airborne (inc airborne))
      (nil? move)
      (let [back (recentre world feet)
            trail (trail-of world target)
            seen (-> tunnel-visited
                     (into (:trail/cells trail))
                     (into (:trail/dead trail)))
            ahead (when-not back
                    (next-move world
                               feet
                               target
                               climb
                               (boolean dry-only?)
                               seen
                               (boolean (:intent/bridge? i))))
            fresh? (and ahead (or (gaining? feet ahead target) (not (retreat? feet ahead seen))))
            undo (when-not (or back fresh?) (step-back world feet trail (boolean dry-only?)))
            m (or back undo ahead)
            retreated (if (and ahead (not fresh?) (not undo)) (inc (long retreats)) 0)]
        (cond (or (nil? m) (> retreated retreats-allowed))
              (-> world
                  (assoc :player/controls {})
                  (intent/fail :blocked))
              :else
              (cond-> (intent/assoc-intent (assoc world :player/controls {})
                                           :intent/move m
                                           :intent/from feet
                                           :intent/retreats retreated
                                           :intent/tunnel-visited (conj tunnel-visited feet))
                (or undo fresh?) (assoc :fortress/trail (leave-cell trail feet (some? undo))))))
      :else
      (let [next (build/next-op world (into (seal-ops world from move) (step-plan from move)))
            next (cond-> next
                   (= :place (:build/op next))
                   (assoc :build/item (or (stairs/filler world 1) :cobblestone)))]
        (cond (nil? next)
              (reduce
               intent/dissoc-intent
               (intent/assoc-intent (assoc world :player/controls {}) :intent/taken (inc taken))
               step-keys)
              (and (#{:floor-clear :open :enter} (:build/step next))
                   (or (not (safe? world from move)) (and dry-only? (not (dry? world from move)))))
              (reduce intent/dissoc-intent (assoc world :player/controls {}) step-keys)
              ;; nothing to lay (the last block went into an earlier cell of this step), or
              ;; nothing that stands as a floor to lay in this step's floor (only soul sand
              ;; carried: laid, it is dug out again as no walk floor, for ever, sim snapshot
              ;; R1-20-1, issue #537): the step is given up and its cell counts as visited,
              ;; never placed with a block that is not held (sim snapshots R2-17-1 and
              ;; R2-12-1, issue #410)
              (and (= :place (:build/op next))
                   (or (nil? (stairs/filler world 1))
                       (and (= :floor (:build/step next))
                            (nil? (walk-filler world (:build/pos next))))))
              (reduce intent/dissoc-intent
                      (intent/assoc-intent (assoc world :player/controls {})
                                           :intent/tunnel-visited
                                           (conj tunnel-visited (plus from move)))
                      step-keys)
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
                      ;; a floor placement the server refused (:rejected, chain-a2 run 3 in
                      ;; the portal room): the step is given up and its cell counted visited,
                      ;; as a step with nothing to lay is, never the tunnel failed for one cell
                      (and (intent/failed? i)
                           (= :rejected (:intent/reason i))
                           (= :place (:build/op next)))
                      (reduce intent/dissoc-intent
                              (-> world
                                  (assoc :player/controls {})
                                  (intent/assoc-intent :intent/status :active
                                                       :intent/tunnel-visited
                                                       (conj tunnel-visited (plus from move)))
                                  (intent/dissoc-intent :intent/reason))
                              step-keys)
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
   each pair one spacing longer, so the strips the legs sweep lie a spacing apart. A leg's
   target is never more than leg-max from where it began (leg-target): legs given up
   untravelled once grew the count without the feet ever walking them, and a search found
   its target 16,896 blocks south (sim snapshot R4-26-1, issue #527), one strip swept for
   the whole window."
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
   at: its length (leg-length, capped at leg-max) along its heading from where it began, at
   that cell's explore-y."
  [{:search/keys [from heading leg]}]
  (let [[x _ z] from
        [dx dz] heading
        n (min leg-max (leg-length leg))]
    [(+ x (* n dx)) (explore-y from) (+ z (* n dz))]))

(defn open-ahead?
  "Is the cell look-ahead blocks from feet along heading [dx dz] loaded? A real server shows 96
   blocks round the feet, so only the model's captured Nether ends in front of a tunnel."
  [world feet [dx dz]]
  (some? (terrain/block-at world (plus feet [(* look-ahead dx) 0 (* look-ahead dz)]))))

(defn new-leg
  "The search whose leg-th leg begins at feet along the first of headings that has a way to go:
   the world goes on ahead (open-ahead?) and a tunnel step toward the leg's end can be taken
   (next-move, bridging a cavern over ground as a stalled leg does); nil when none has. A leg
   that ended on a bridge over a cavern found no safe? step in any heading and the search
   waited :no-way with the fortress's bricks a few blocks past its end (issue #573)."
  [world feet leg headings]
  (some (fn [h]
          (let [s {:search/from feet :search/heading h :search/leg leg :search/tunnels 0}]
            (when (and (open-ahead? world feet h)
                       (next-move world feet (leg-target s) climb-past false #{} true))
              s)))
        headings))

(defn rock-run
  "Cells along heading [dx dz] from feet, at most rock-look, that a tunnel keeps solid under, in
   and over its way: every cell's floor, feet and head blocks solid, the run ending at the first
   cell that is air or not loaded. A cavern's open air ahead is 0; a rock mass is rock-look."
  [world [x y z] [dx dz]]
  (count (take-while (fn [k]
                       (every? #(some-> (terrain/block-at world
                                                          [(+ x (* k (long dx))) (+ y (long %))
                                                           (+ z (* k (long dz)))])
                                        blocks/solid?)
                               [-1 0 1]))
                     (range 1 (inc rock-look)))))

(defn turns-by-rock
  "The headings a leg that ended may turn to, best first: a quarter turn right or left of heading,
   the one with the longer rock-run first (right when equal), then back and heading again. A leg
   that stalled at a cavern turns to the rock it can dig, not to the air it cannot."
  [world feet heading]
  (let [right (turn-right heading)
        back (turn-right right)
        left (turn-right back)]
    (concat (sort-by #(- (long (rock-run world feet %))) [right left]) [back heading])))

(defn leg-progress
  "The search with its progress toward the leg's end kept: :search/best, the least horizontal
   distance the feet have been from it at a look, and :search/stalled, the looks in a row that
   brought them no stall-gain nearer than that. A tunnel through a cavern's rim wanders at a wall
   it cannot cross (sim world R2/12, issue #338: fourteen minutes inside x 507-534, z 340-362 on
   a leg to x 620, eight pickaxes of sticks spent for 13 blocks), and counts as stalled."
  [world {:search/keys [best] :as search}]
  (let [d (physics/horizontal-distance (physics/centre (intent/feet world))
                                       (physics/centre (leg-target search)))]
    (cond (or (nil? best) (<= d (- (double best) stall-gain)))
          (assoc search :search/best d :search/stalled 0)
          :else (update search :search/stalled (fnil inc 0)))))

(defn leg-done?
  "Was the leg of search travelled: its end within reach (by the ground, a tunnel's height may
   stay short of it), or the feet farther from where it began than its length and 64 (another
   trip's)? Only a travelled leg grows the spiral (exploring): a leg given up untravelled that
   still grew the count sent the next legs thousands of blocks past the captured Nether (sim
   snapshots R4-26-1 and R4-46-1, issues #527 and #537)."
  [world {:search/keys [from leg] :as search}]
  (let [at (physics/centre (intent/feet world))]
    (or (<= (physics/horizontal-distance at (physics/centre (leg-target search))) reach)
        (> (physics/horizontal-distance at (physics/centre from)) (+ (leg-length leg) 64)))))

(defn leg-over?
  "Is the leg of search finished: travelled (leg-done?), the world ending ahead, more tunnels
   spent on it than its length asks (a wall it cannot go round), or stall-tunnels in a row
   without stall-gain of progress (leg-progress)?"
  [world {:search/keys [heading leg tunnels stalled] :as search}]
  (or (leg-done? world search)
      (not (open-ahead? world (intent/feet world) heading))
      (> tunnels (+ 2 (* 2 (quot (leg-length leg) steps-per-tunnel))))
      (>= (long (or stalled 0)) stall-tunnels)))

(defn exploring
  "The search an exploring tunnel carries, with no brick on record: the world's :fortress/search
   while its leg goes on (one more tunnel spent), else the next leg, a quarter turn right from the
   last one's heading and, when that leg was travelled (leg-done?), a longer one; a leg given up
   untravelled turns at the same length, from the feet; a leg longer than leg-max (grown
   untravelled before this rule, its target thousands of blocks away) starts again as the
   first leg along its own heading; the first leg, east when it can go, with no
   search. A leg that cannot go the way it turns tries the next heading, so the model's edge
   turns the tunnel. nil when no heading has a way to go. Replaces the tunnel that aimed 48 blocks
   east for ever: sim race 39095704051 runner 4 (issue #338) went 630 blocks east along the
   bedrock past a fortress 150 blocks north."
  [world]
  (let [feet (intent/feet world)
        {:search/keys [heading leg] :as search} (:fortress/search world)
        headings #(take 4 (iterate turn-right %))]
    (cond (nil? search) (new-leg world feet 0 (headings [1 0]))
          (> (leg-length (long leg)) leg-max) (new-leg world feet 0 (headings heading))
          (leg-done? world search)
          (new-leg world feet (inc (long leg)) (turns-by-rock world feet heading))
          (leg-over? world search) (new-leg world feet leg (turns-by-rock world feet heading))
          :else
          (-> (leg-progress world search)
              (update :search/tunnels inc)))))

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

(defn back-target
  "The cell to walk back to when a pickaxe must be remade and no ground is near: the oldest
   cell of the trail kept for target (where this tunnel began, on ground), or, while a walk
   back is running, that walk's own target (the trail then kept for it). nil when no trail
   is kept or the feet are already there (issues #559, #328: a table on a bridge or a ledge
   over a cavern finds no spot, and a tunnel that yields for the pickaxe is asked again for
   ever)."
  [world target]
  (let [{:trail/keys [cells] t-target :trail/target} (:fortress/trail world)
        back (cond (= target t-target) (first cells)
                   (and t-target (not= target t-target)) t-target)]
    (when (and back (not= back (intent/feet world))) back)))

(defmethod plan/next-intent :fortress
  ;; replace a missing pickaxe from held supplies and take the placed table along (the next
  ;; pickaxe is made where the bot stands, not out of four more planks: probe 39782256061, issue
  ;; #338, R3/29 seed 2 left its table at the first pickaxe made in the tunnel and had sticks and
  ;; 937 cobblestone and no pickaxe from the second), then tunnel to the nearest brick on record;
  ;; with none, the next leg of the spiral search (exploring); with nowhere safe, or outside the
  ;; Nether, wait
  [world goal table]
  (let [nether? (= dimension/nether (dimension/of world))
        brick (when nether? (nearest-brick world))
        search (when (and nether? (not brick)) (exploring world))
        ;; On a bridge over a cavern no cell beside the feet is a spot for the table (a drop
        ;; past every cell of a diagonal bridge, issue #559 again): the pickaxe is remade once
        ;; the tunnel reaches ground, and the tunnel goes on by hand until then.
        grounded? (or (not (on-bridge? world)) (ground-near? world))
        back (when (and (not grounded?) (build/pickaxe-at-hand? world))
               (back-target world (or brick (when search (leg-target search)))))]
    (cond (not nether?) {:plan/wait :other-dimension}
          :else
          (or (when back
                {:intent/kind :tunnel-to
                 :intent/target back
                 :intent/steps steps-per-tunnel
                 :intent/bridge? true})
              (when (and grounded? (build/pickaxe-at-hand? world))
                (make/decide world (assoc goal :goal/provides {:tag/pickaxes 1}) table))
              (and grounded? (intent/chain-next (:plan/last world) :take-table make/table-drops {}))
              (and grounded? (make/take-table-along world))
              (when brick
                {:intent/kind :tunnel-to
                 :intent/target brick
                 :intent/steps steps-per-tunnel
                 :intent/bridge? true})
              (when search
                {:intent/kind :tunnel-to
                 :intent/target (leg-target search)
                 :intent/steps steps-per-tunnel
                 :intent/bridge? (or (pos? (long (or (:search/stalled search) 0)))
                                     (on-bridge? world))
                 :intent/search search})
              {:plan/wait :no-way}))))
