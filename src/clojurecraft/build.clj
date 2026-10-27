(ns clojurecraft.build
  "Building from a plan that is data (issue #8, the portal cast).

   A plan is a vector of ops, flat maps. Every op says what it does (:build/op :place :dig :pour
   :scoop :use-on :stand-in), where (:build/pos, and :build/stand where the bot stands to do it),
   with what (:build/item), and when it is done (:build/done, a predicate over the world as data:
   [:solid p] [:not-solid p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:no-water-source p]
   [:dimension name] [:or & preds] [:and & preds]). `satisfied?` reads a predicate against the
   world. Nothing about progress is stored: the op to work on is the first one not satisfied, so
   a build resumes after a death, a chunk reload or a relaunch by reading the world again.

   `portal-plan` casts the frame as a player does (issue #124): the rock is the mould. Beside any
   lava pool, at any depth, the frame's ten cells are holes cut into a face of the rock, each a
   block deep; lava is poured into a hole, water into the cell directly over it against the same
   wall (it lands on the source: obsidian) and scooped straight back, and the lava bucket is
   refilled at the pool. The room the bot works from is dug out in front of the face; a block is
   placed only where the rock is missing. Positions are written for the bot working from +z
   (:south) and turned for the other facings."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as packet]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.window :as window]
            [clojurecraft.wood :as wood]))

(set! *warn-on-reflection* true)

(def layers
  "The frame cells [dx dy] from the origin, in the order they are cast: the bottom pair, the two
   columns a block a layer, the top pair. Never sorted by height: a sort interleaves the columns and
   the bot crosses the frame for every block (steve cast.ts 2510-2521)."
  [[[1 0] [2 0]] [[0 1] [3 1]] [[0 2] [3 2]] [[0 3] [3 3]] [[1 4] [2 4]]])

(def frame-cells "Every frame cell [dx dy], in cast order." (vec (apply concat layers)))
(def interior
  "The portal's inside, [dx dy]: two wide, three high."
  (for [dx [1 2] dy [1 2 3]] [dx dy]))
(def facings #{:south :north :east :west}) ; the side of the frame the bot works from

;; ---------------------------------------------------------------- predicates

(defmulti holds?
  "Does predicate pred (a vector, its first element the kind) hold in world? An open set: a new
   kind is a defmethod."
  {:arglists '([world pred])}
  (fn [_world pred] (first pred)))

(defmethod holds? :solid
  [world [_ p]]
  (boolean (some-> (terrain/block-at world p)
                   blocks/solid?)))
(defmethod holds? :not-solid
  [world [_ p]]
  (boolean (some-> (terrain/block-at world p)
                   blocks/solid?
                   not)))
(defmethod holds? :filled
  [world [_ p]]
  (let [id (terrain/block-at world p)] (boolean (and id (not= :air (blocks/name-of id))))))
(defmethod holds? :block
  [world [_ p n]]
  (= n
     (some-> (terrain/block-at world p)
             blocks/name-of)))
(defmethod holds? :lava-source [world [_ p]] (= blocks/lava-base (terrain/block-at world p)))
(defmethod holds? :no-water
  [world [_ p]]
  (not (contains? blocks/water-states (terrain/block-at world p))))
(defmethod holds? :no-water-source [world [_ p]] (not (scoop/source? world p :water)))
(defmethod holds? :water-source [world [_ p]] (scoop/source? world p :water))
(defmethod holds? :dimension [world [_ d]] (= d (:player/dimension world)))
(defmethod holds? :feet [world [_ p]] (= p (intent/feet world)))
(defmethod holds? :no-lava
  [world [_ p]]
  (not (contains? blocks/lava-states (terrain/block-at world p))))
(defmethod holds? :firm
  ;; a wall a hole's lava rests against: solid, and not a block that falls when the cell under it
  ;; opens (gravel, sand)
  [world [_ p]]
  (let [id (terrain/block-at world p)]
    (boolean (and id (blocks/solid? id) (not (blocks/falls? id))))))
(defmethod holds? :or [world [_ & ps]] (boolean (some #(holds? world %) ps)))
(defmethod holds? :and [world [_ & ps]] (every? #(holds? world %) ps))

(defn satisfied?
  "Is op done in world (its :build/done holds)?"
  [world op]
  (holds? world (:build/done op)))

;; ---------------------------------------------------------------- positions

(defn turn
  "A point [x z] on the plane, written for the bot working from +z, turned for facing."
  [facing [x z]]
  (case facing
    :south [x z]
    :north [(- x) (- z)]
    :east [z (- x)]
    :west [(- z) x]))

(defn point
  "The absolute point at [x y z] from origin (real coordinates, written for :south), turned for
   facing about the middle of the origin's cell, so the origin's cell stays where it is."
  [[bx by bz] facing [x y z]]
  (let [[tx tz] (turn facing [(- x 0.5) (- z 0.5)])] [(+ bx 0.5 tx) (+ by y) (+ bz 0.5 tz)]))

(defn cell
  "The absolute cell at [dx dy dz] from origin (written for :south), turned for facing: the cell
   its centre turns into, so a cell and the points inside it turn alike."
  [origin facing [dx dy dz]]
  (mapv #(long (math/floor %)) (point origin facing [(+ dx 0.5) dy (+ dz 0.5)])))

(defn aim
  "The absolute point at offset [ax ay az] inside the cell [dx dy dz] from origin, turned for
   facing."
  [origin facing [dx dy dz] [ax ay az]]
  (point origin facing [(+ dx ax) (+ dy ay) (+ dz az)]))

;; ---------------------------------------------------------------- the cast, cut into the rock

(defn cast?
  "The predicate of a frame cell [dx dy] being cast: obsidian."
  [at [dx dy]]
  [:block (at [dx dy 0]) :obsidian])

(defn layer-cast?
  "The predicate of every cell of a layer being cast."
  [at cells]
  (into [:and] (map #(cast? at %) cells)))

(defn place-op
  "Place a block at pos from stand, done once something solid is there or guard holds."
  [pos stand guard step]
  {:build/op :place
   :build/item :cobblestone
   :build/pos pos
   :build/stand stand
   :build/step step
   :build/done (if guard [:or guard [:solid pos]] [:solid pos])})

(defn dig-op
  "Dig a solid at pos from stand, done once nothing solid is there, guard holds, or it is obsidian:
   a dig never takes obsidian (ruststeve dug its own frame and restarted forever)."
  [pos stand guard step]
  {:build/op :dig
   :build/pos pos
   :build/stand stand
   :build/step step
   :build/done (cond-> [:or [:block pos :obsidian] [:not-solid pos]] guard (conj guard))})

(defn firm-ops
  "Make pos a firm wall from stand where the rock is missing (issue #124): a falling block there
   (gravel, sand) dug out (:fall-clear), then a block placed; both done once pos is firm (solid and
   not falling) or guard holds. In solid rock both hold from the start and nothing is placed.
   A portal block there is a wall already: no block is placed into it (the game does not take
   one) and no fluid flows into it. Real race 39407278017 bot Clj_r03 (issue #44): the cast
   room's back wall met a nether portal's block, and the cobblestone for it was refused three
   times, :rejected."
  [pos stand guard step]
  (let [walled (into [:or] (remove nil? [guard [:block pos :nether_portal]]))]
    [{:build/op :dig
      :build/pos pos
      :build/stand stand
      :build/step :fall-clear
      :build/done [:or guard [:firm pos] [:not-solid pos]]} (place-op pos stand walled step)]))

(def stair
  "The steps the upper cells are cast from, [x feet] in the row three in front of the frame
   (dz 3): up from x 0 to the top at x 2, down again to x 4, each a block from the last, so the
   router walks them (a jump up, a step down)."
  [[0 1] [1 2] [2 3] [3 2] [4 1]])

(def stands
  "Each frame cell [dx dy] → the cell [x feet dz] the bot stands in to cast it: the eye a little
   over the cell's floor, so the lava's ray enters the hole through its open face and the water's
   ray the cell over it. The bottom two levels from the room's floor two in front (dz 2), the
   upper ones from the stair (dz 3), a column aside where a straight stand would be another's
   step."
  {[1 0] [1 0 2]
   [2 0] [2 0 2]
   [0 1] [0 0 2]
   [3 1] [3 0 2]
   [0 2] [0 1 3]
   [3 2] [4 1 3]
   [0 3] [1 2 3]
   [3 3] [3 2 3]
   [1 4] [2 3 3]
   [2 4] [2 3 3]})

(defn side-aim
  "The x offset inside a target cell for a ray from a stand side (its x less the cell's) away: the
   far side of the cell for a stand aside, so the ray crosses into the cell's column while still in
   front of the frame and enters the cell through its open face, never through the wall beside it."
  [side]
  (- 0.5 (* 0.3 (long (math/signum (double side))))))

(defn cell-ops
  "Cast frame cell [dx dy] (issue #124: the rock is the mould): from its stand, make its walls firm
   where the rock is missing (behind it, under it, either side, and behind the cell over it, the
   face the water is placed against), dig the hole and the cell over it, pour lava low against the
   hole's back wall, pour water into the cell directly over the lava against the same wall (it
   lands on the source: obsidian), and scoop the water straight back from there. Every op but the
   scoop is done once the cell is obsidian; the scoop is done when no water source remains,
   even if another cast's water flows through that cell. Water is never poured beside the lava:
   a source with a side open flows within about 1.5 s on a server, and flow meeting water is
   cobblestone."
  [at origin facing [dx dy :as c]]
  (let [[sx _ _ :as s] (stands c)
        stand (at s)
        side (- sx dx)
        hole (at [dx dy 0])
        over (at [dx (inc dy) 0])
        done (cast? at c)
        walls [[dx dy -1] [dx (dec dy) 0] [(dec dx) dy 0] [(inc dx) dy 0] [dx (inc dy) -1]]]
    (concat (mapcat #(firm-ops (at %) stand done :wall) walls)
            [(dig-op hole stand done :hole) (dig-op over stand done :over)
             {:build/op :pour
              :build/item :lava_bucket
              :build/stand stand
              :build/aim (aim origin facing [dx dy 0] [(side-aim side) 0.3 -0.02])
              :build/into hole
              :build/cell c
              :build/step :lava
              :build/done [:or done [:lava-source hole]]}
             {:build/op :pour
              :build/item :water_bucket
              :build/stand stand
              :build/aim (aim origin facing [dx (inc dy) 0] [(side-aim side) 0.5 -0.02])
              :build/into over
              :build/cell c
              :build/step :water
              :build/done done}
             {:build/op :scoop
              :build/item :bucket
              :build/pos over
              :build/stand stand
              :build/aim (aim origin facing [dx (inc dy) 0] [(side-aim side) 0.4 0.5])
              :build/cell c
              :build/step :scoop
              :build/done [:no-water-source over]}])))

(def max-access 6)                    ; the lava's stand at most this far over the room's floor

(defn access
  "The steps [x feet dz] from the top of the stair up to h over the room's floor, the lava's
   height (station): one a block higher and one further out than the last; none when the stair or
   the floor is at that height already."
  [h]
  (for [i (range 1 (inc (- h 3)))] [2 (+ 3 i) (+ 3 i)]))

(defn landing
  "The cell [x feet dz] of the room at height h over its floor, from which a level way leads to
   the lava: the floor two in front, a step of the stair, or the last access step."
  [h]
  (cond (> h 3) (last (access h))
        (pos? h) (let [[x feet] (first (filter #(= h (second %)) stair))] [x feet 3])
        :else [2 0 2]))

(defn body
  "The cells a stand at [x feet dz] needs open: the feet, the head, and the room over the head a
   jump up from it takes."
  [[x feet dz]]
  (for [y (range feet (+ feet 3))] [x y dz]))

(defn room-cells
  "Every cell [dx dy dz] the full cast opens in front of the frame, nearest the landing first, so
   each is dug from cells dug before it: the row against the frame (dz 1) and the floor row (dz 2)
   six high, the height every ray crosses them at, both five wide (a ray from the stair's last
   step, aside of the frame at x 4, crosses x 4 of the row against the frame when the eye is a
   little off the step's middle: it met rock there and the lava landed in the room), the stair's
   steps, and the access steps up to the lava's height h."
  [h]
  (let [from (landing h)]
    (->> (concat (for [x (range 5) y (range 6)] [x y 1])
                 (for [x (range 5) y (range 6)] [x y 2])
                 (mapcat (fn [[x feet]] (body [x feet 3])) stair)
                 (mapcat body (access h)))
         distinct
         (sort-by (fn [c] [(physics/distance (mapv double c) (mapv double from)) c])))))

(defn floors
  "The cells under every stand and step of the full cast that must be firm: under the two rows in
   front, the stair and the access steps."
  [h]
  (concat (for [x (range 5)] [x -1 1])
          (for [x (range 5)] [x -1 2])
          (for [[x feet dz] (concat (map (fn [[x feet]] [x feet 3]) stair) (access h))]
            [x (dec feet) dz])))

(defn room-ops
  "The room the cast needs, dug out of the rock: the landing first, reached at its height
   (approach-stand), then every room cell, each dug from wherever the bot can reach it (its stand
   is the cell itself), and firm floors under the stands where the rock is missing (done once the
   frame is cast)."
  [at guard h]
  (let [from (landing h)]
    (concat (map #(dig-op (at %) (at from) nil :landing) (body from))
            (map #(dig-op (at %) (at %) nil :room) (room-cells h))
            (mapcat #(firm-ops (at %) (at (update % 1 inc)) guard :floor) (floors h)))))

(defn interior-ops
  "After the frame: the 2x3 inside dug out (blocks placed there as walls among them), never
   obsidian, from the floor two in front."
  [at]
  (for [[dx dy] (sort-by (comp - second) interior)]
    (dig-op (at [dx dy 0]) (at [1 0 2]) nil :interior)))

(defn light-op
  "Flint and steel on the top of the frame's bottom cell (1, 0), from two blocks in front of it,
   out of the fire's reach: done when the portal is there."
  [at]
  {:build/op :use-on
   :build/item :flint_and_steel
   :build/pos (at [1 0 0])
   :build/face :up
   :build/stand (at [1 0 2])
   :build/step :light
   :build/done [:block (at [1 1 0]) :nether_portal]})

(defn enter-op
  "Stand in the portal until the dimension is the Nether."
  [at]
  {:build/op :stand-in
   :build/pos (at [1 1 0])
   :build/stand (at [1 1 0])
   :build/step :enter
   :build/done [:dimension "minecraft:the_nether"]})

(defn cast-one-plan
  "The first cell cast, the stage the gym's cast-one row judges: the room, the cell, and a check
   that it is obsidian and the water taken back. h is the lava's height over the room's floor."
  ([origin facing] (cast-one-plan origin facing 0))
  ([origin facing h]
   (let [at #(cell origin facing %)
         [first-cell] (first layers)
         [dx dy] first-cell
         done (cast? at first-cell)]
     (vec (concat (room-ops at done h)
                  (cell-ops at origin facing first-cell)
                  [{:build/op :verify
                    :build/stand (at (stands first-cell))
                    :build/step :verify
                    :build/done [:and done [:no-water-source (at [dx (inc dy) 0])]]}])))))

(defn frame-plan
  "The whole frame from origin facing cut into the rock: the room, the ten cells bottom up in
   layers order, the inside dug out, the portal lit, and with enter? the step through it. h is the
   lava's height over the room's floor."
  ([origin facing enter?] (frame-plan origin facing enter? 0))
  ([origin facing enter? h]
   (let [at #(cell origin facing %)]
     (vec (concat (room-ops at (layer-cast? at frame-cells) h)
                  (mapcat #(cell-ops at origin facing %) frame-cells)
                  (interior-ops at)
                  [(light-op at)]
                  (when enter? [(enter-op at)]))))))

(defn portal-plan
  "The whole cast from origin [bx by bz] facing (one of facings): the room, ten cells, the inside
   opened, the portal lit and stepped through. A pure function: equal arguments, an equal plan."
  ([origin facing] (portal-plan origin facing 0))
  ([origin facing h] (frame-plan origin facing true h)))

;; ---------------------------------------------------------------- the executors

(def settle-ms 300)                   ; standing still this long, looking at the aim, before using the item
(def answer-ms 3000)                  ; ms an action waits for the world to show it worked
(def await-ms 10000)                  ; ms an :await holds before it fails

(defn- now [world] (:time/now world))

(defn held?
  "Does the inventory hold item (a name)?"
  [world item]
  (pos? (inventory/item-count world (recipe/item-id item))))

(defmulti act-stage
  "Advance a :pour or :use-on one tick in its :intent/stage (:equip :aim :sent): put the item in
   hand, look at the aim and hold still, use it, then wait for the world to show the op done
   (:intent/expect)."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defmethod act-stage :equip [world {:intent/keys [item]}] (place/equip world item :aim))

(defn use!
  "Use the held item at the intent's aim: a pour sends use-item with the look (a bucket is used by
   where the eyes point), a use-on sends use-item-on at the clicked block's top."
  [world {:intent/keys [kind target]} look]
  (case kind
    :pour (scoop/use-item world look)
    :use-on (place/use-item-on world target (packet/value :face :up) (place/face-cursors :up))))

(defmethod act-stage :aim
  [world {:intent/keys [aim since still] :or {still 0} :as i}]
  (let [look (physics/look-at (game/eye world) aim)
        still (if (intent/still? world) (inc still) 0)
        world (assoc world :player/controls {:control/look look})]
    (if (and (>= still 3) (> (- (now world) since) settle-ms))
      (-> world
          (use! i look)
          (intent/assoc-intent :intent/stage :sent))
      (intent/assoc-intent world :intent/still still))))

(defmethod act-stage :sent
  [world {:intent/keys [expect sent-at]}]
  (cond (holds? world expect) (intent/done world)
        (> (- (now world) sent-at) answer-ms) (intent/fail world :no-effect)
        :else world))

(defn act-run
  "Run a :pour or :use-on: its stage once no inventory click is unanswered."
  [world i]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :equip :intent/since (now world)))
        world (assoc world :plan/intent i)]
    (case (window/waiting world i)
      :overdue (window/lost world i :equip)
      :waiting world
      (if (:intent/resync i)
        (window/resync world (window/view world :inventory))
        (act-stage world i)))))

(defmethod intent/run :pour [world i _] (act-run world i))
(defmethod intent/run :use-on [world i _] (act-run world i))

(defmethod intent/run :await
  ;; hold still until :intent/expect holds, await-ms at most
  [world {:intent/keys [expect started] :as i} _]
  (let [started (or started (now world))
        world (assoc world :plan/intent (assoc i :intent/started started) :player/controls {})]
    (cond (holds? world expect) (intent/done world)
          (> (- (now world) started) await-ms) (intent/fail world :not-yet)
          :else world)))

(def frame-box
  "The cells [dx dy dz] of the rock the frame is cut into and kept: its plane and the row behind
   it, a column either side, from the cells under the bottom row to the cells over the top row."
  (vec (for [x (range -1 5) y (range -1 6) z [-1 0]] [x y z])))

(def footprint
  "[origin facing h] → the set of cells a cast there works in or keeps: the frame's rock
   (frame-box), the room (room-cells) and the floors under it. No fluid may be in it or touch it.
   Memoized: the plan is pure."
  (memoize (fn [origin facing h]
             (set (map #(cell origin facing %) (concat frame-box (room-cells h) (floors h)))))))

(def kept
  "[origin facing] → the cells of the frame's rock (frame-box) at origin facing, which a way dug
   toward a stand never takes: the cast's own ops dig its holes. Memoized."
  (memoize (fn [origin facing] (set (map #(cell origin facing %) frame-box)))))

(defn lava-sources
  "Every lava source in the loaded terrain, as the block overlay says now."
  [world]
  (->> (chunk/find-blocks (:world/chunks world) #(= % blocks/lava-base))
       (map (fn [[x y z _]] [x y z]))
       (filter #(= blocks/lava-base (terrain/block-at world %)))))

(defn touching
  "The six cells round cell."
  [cell]
  (map #(mapv + cell %) [[1 0 0] [-1 0 0] [0 1 0] [0 -1 0] [0 0 1] [0 0 -1]]))

(defn lava-touching?
  "Does lava lie in cell or beside it (its six neighbours)? A way dug there lets it in."
  [world cell]
  (boolean (some #(= :lava (terrain/fluid-kind world %)) (cons cell (touching cell)))))

(defn dry?
  "Does no cell of cells hold a fluid?"
  [world cells]
  (not-any? #(terrain/fluid-at world %) cells))

(defn openable?
  "Can cell be opened for a stand or a scoop: loaded, no fluid in it, and air or a block a dig
   may take (never obsidian)?"
  [world cell]
  (let [id (terrain/block-at world cell)]
    (boolean (and id
                  (not (terrain/fluid-at world cell))
                  (not (contains? intent/never-dug (blocks/name-of id)))))))

(defn stands-beside
  "[stand src] for each cell beside the cell over lava source src (the source diagonally below
   the feet, never beside them) that the feet can stand in once it is opened: a firm walking
   floor under it (path/floor?), it and the head openable, no fluid touching them, and the cell
   over the source openable with no fluid round it but the source. A stand inside the rock is
   dug to (refill), the cell over a
   sealed source dug open."
  [world [x y z :as src]]
  (let [over [x (inc y) z]]
    (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
          :let [stand [(+ x dx) (inc y) (+ z dz)]
                head (update stand 1 inc)]
          :when (and (holds? world [:firm (update stand 1 dec)])
                     (path/floor? world (update stand 1 dec))
                     (openable? world stand)
                     (openable? world head)
                     (openable? world over)
                     (dry? world (remove #{over} (mapcat touching [stand head])))
                     (dry? world (remove #{src} (touching over))))]
      [stand src])))

(def station-depth 9)                  ; how far in front of the frame a lava stand may be
(def station-span 8)                   ; how far aside of the landing's column a lava stand may be

(defn relative
  "Cell as [dx dy dz] from origin facing: the inverse of cell."
  [origin facing c]
  (let [[dx dy dz] (mapv - c origin)
        back {:south :south :north :north :east :west :west :east}
        [x z] (turn (back facing) [dx dz])]
    [x dy z]))

(defn reach-sources
  "The lava sources of sources a bot standing at stand reaches with a bucket, under its feet's
   level and within intent/reach of its eye, nearest first."
  [sources [x y z]]
  (let [eye [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)]]
    (->> sources
         (filter (fn [[_ sy _ :as src]]
                   (and (< sy y) (<= (physics/distance eye (physics/centre src)) intent/reach))))
         (sort-by #(physics/distance eye (physics/centre %))))))

(defn lava-aim
  "Where on the surface of lava source src the eyes can aim with nothing on the ray
   (scoop/surface-points, scoop/ray-walls), or nil. Issue #225: the refill's line to the
   surface's centre grazed a block's edge the samples stepped past, and three scoops filled
   nothing."
  [world src]
  (first (filter #(empty? (scoop/ray-walls world %)) (scoop/surface-points src))))

(defn safe-source?
  "Can src be scooped or its sight line opened without removing the feet's support or admitting
   lava at or above the feet? A lid over lava may be opened; higher lava beside or above any
   block on the ray must stay sealed. Issue #231 (R1/32) opened a low source's lid under higher
   lava, flooding its stand."
  [world src]
  (let [[_ y _ :as feet] (intent/feet world)
        floor (update feet 1 dec)]
    (or (not-any? (fn [block]
                    (or (= floor block)
                        (some #(and (>= (second %) (max y (second block)))
                                    (= :lava (terrain/fluid-kind world %)))
                              (touching block))))
                  (scoop/ray-walls world (scoop/aim-point src)))
        (some? (lava-aim world src)))))

(defn station-among
  "{:stand :sources :h} to refill lava from for a frame at origin facing: the cell nearest the
   landing at a height h over the room's floor (h between one and max-access: water spilled on
   the floor never runs up to it; the h given first), four to station-depth in front of the frame
   and up to station-span aside of the landing's column, that the feet can stand in once opened
   (a firm walking floor under it, it and the head openable, no fluid touching them, outside the
   footprint, and open? true of it, default always: a way to it that can be walked or dug) and
   from which lava sources are in reach (reach-sources), with safe sight lines
   (safe-source?).
   At a stand the feet already centre on, the sight lines use the actual eyes, not the cell's
   exact centre: issue #380's R3/32 refill reached a stand slightly off centre, where the ray
   would open higher lava, and failed :no-station although the projected ray was safe.
   As a pool's near sources are scooped the
   next stand along it is the station; the way to one runs straight out from the landing, then
   aside (refill), never along the stair. nil when none."
  ([world sources origin facing] (station-among world sources origin facing nil))
  ([world sources origin facing prefer] (station-among world sources origin facing prefer any?))
  ([world sources origin facing prefer open?]
   (first
    (for [h (distinct (cons (or prefer 1) (range 1 (inc max-access))))
          :when (<= 1 h max-access)
          :let [[lx _ _] (landing h)]
          dz (range 4 (inc station-depth))
          dx (sort-by #(abs (- % lx)) (range (- lx station-span) (inc (+ lx station-span))))
          :let [stand (cell origin facing [dx h dz])
                head (update stand 1 inc)]
          :when (and (holds? world [:firm (update stand 1 dec)])
                     (path/floor? world (update stand 1 dec))
                     (openable? world stand)
                     (openable? world head)
                     (dry? world (mapcat touching [stand head]))
                     (not (contains? (footprint origin facing h) stand))
                     (open? stand))
          :let [pos (assoc (physics/centre stand) 1 (double (second stand)))
                at (if (intent/centred? world stand) world (assoc world :player/pos pos))
                srcs (filter #(safe-source? at %) (reach-sources sources stand))]
          :when (seq srcs)]
      {:stand stand :sources srcs :h h}))))

(def pool-radius
  "How far from a frame's origin a source its lava stands reach can lie: the stands' farthest
   cell and the bucket's reach."
  (+ station-depth station-span max-access intent/reach 2))

(defn near
  "The sources of sources within r of cell."
  [sources cell r]
  (filter #(<= (physics/distance % cell) r) sources))

(defn pool
  "The lava sources of sources that some lava stand of the frame at origin facing could scoop
   (reach-sources from any cell station-among looks at, every height up to max-access): the pool
   the frame is cast from."
  [sources origin facing]
  (let [near (near sources origin pool-radius)]
    (set (for [h (range 1 (inc max-access))
               :let [[lx _ _] (landing h)]
               dz (range 4 (inc station-depth))
               dx (range (- lx station-span) (inc (+ lx station-span)))
               src (reach-sources near (cell origin facing [dx h dz]))]
           src))))

(defn spent?
  "Does the pool of the frame at origin facing (pool) hold fewer lava sources than the frame has
   cells still to cast? Each cell takes one source. Sim race 38664661309 (issue #142, 89 runs in
   18 rounds): five worlds chose a site beside a lone lava source, cast one cell from it and
   failed :no-station with nine to go, the nearest other lava twenty to forty blocks off."
  [world sources origin facing]
  (let [at #(cell origin facing %)
        left (count (remove #(holds? world (cast? at %)) frame-cells))]
    (< (count (pool sources origin facing)) left)))

(defn station
  "The station for the build at origin facing (station-among the lava sources loaded), the height
   prefer first, or nil."
  ([world origin facing] (station world origin facing nil))
  ([world origin facing prefer] (station-among world (lava-sources world) origin facing prefer)))

(def plan-for
  "[build origin facing h] → the plan: :cast-one, :portal (lit) or :nether (stepped through), with
   the room's access steps up to h (station). Memoized: the plans are pure, and a build reads its
   plan every tick."
  (memoize (fn [build origin facing h]
             (case build
               :cast-one (cast-one-plan origin facing h)
               :portal (frame-plan origin facing false h)
               :nether (portal-plan origin facing h)))))

(def foundation-depth 4)               ; air cells under a placement a foundation is built up through

(defn foundation
  "The cell to place a block in now so that cell can have one: cell itself when a solid neighbour
   holds it up (place/support), else the lowest of the air cells straight under it (foundation-depth
   at most) that the block under it holds up, building up to it from below (ruststeve's
   ensure_solid); nil when there is none. A resumed cast meets this: a wall whose cell below was
   another cell's, already cast before, was never placed."
  [world [x y z :as cell]]
  (if (place/support world cell)
    cell
    (some (fn [dy]
            (let [c [x (- y dy) z]]
              (when (and (holds? world [:not-solid c]) (place/support world c)) c)))
          (range 1 (inc foundation-depth)))))

(defn place-cells
  "Every cell the plan places a block in, whatever the room's access h (0 to max-access): the
   floor under a landing at h is another cell for each h, and a way dug for the lava's stand
   must keep the cobblestone of the one the build has. Sim race 39076908669 (issue #178, world
   R1/11 seed 5): the build's access was 4, the cells were read for access 0, and the way to the
   lava's stand dug the landing's floor each time it was placed, a floor, a pour and a dig
   every twelve seconds for the run's last quarter hour."
  [build origin facing]
  (set (for [h (range (inc max-access))
             op (plan-for build origin facing h)
             :when (= :place (:build/op op))]
         (:build/pos op))))

(defn diggable?
  "Is pos a solid or awkward block a clear may dig: not unloaded, not obsidian, not the frame's
   rock (kept?: the rock the holes are cut into, whatever it is made of), not beside lava
   (lava-touching?: issue #231, a way dug along the lava sea's edge let it in), and not cobblestone the
   plan places. Stone or dirt where a floor will go later is still dug: the place is satisfied by
   whatever is solid, and the hill would stay in the way."
  [world pos placed? kept?]
  (when-let [id (terrain/block-at world pos)]
    (and (not (kept? pos))
         (not (lava-touching? world pos))
         (not (and (placed? pos) (= :cobblestone (blocks/name-of id))))
         (not (contains? intent/never-dug (blocks/name-of id)))
         (contains? #{:solid :awkward} (path/classify-id id)))))

(defn way-columns
  "The columns [x z] of the straight way from the feet's column to stand's, both ends included,
   as stance-block walks it: along x first, then along z; with z-first? along z first, then x."
  ([from stand] (way-columns from stand false))
  ([[fx _ fz] [sx _ sz] z-first?]
   (let [span (fn [a b] (let [d (if (> a b) -1 1)] (range a (+ b d) d)))]
     (if z-first?
       (concat (for [z (span fz sz)] [fx z]) (for [x (rest (span fx sx))] [x sz]))
       (concat (for [x (span fx sx)] [x fz]) (for [z (rest (span fz sz))] [sx z]))))))

(defn stance-way
  "{:line :digs? :walled?} of the way to stand at the feet's height: :line the cells of the straight
   way (body and head, from the feet, along x first, or along z first when a cell of the first is
   solid and may not be dug while the second has none), :digs? true of a cell a build's dig may
   take (diggable?), :walled? true of a solid cell it may not."
  [world stand build origin facing]
  (let [placed? (place-cells build origin facing)
        kept? (kept origin facing)
        feet (intent/feet world)
        sy (second stand)
        digs? #(diggable? world % placed? kept?)
        walled? #(and (some-> (terrain/block-at world %)
                              blocks/solid?)
                      (not (digs? %)))
        cells (fn [cols] (mapcat (fn [[x z]] [[x sy z] [x (inc sy) z]]) cols))
        lines (map #(cells (way-columns feet stand %)) [false true])
        line (or (first (remove #(some walled? %) lines)) (first lines))]
    {:line line :digs? digs? :walled? walled?}))

(defn stance-block
  "The first solid cell in the way of standing at stand: from the feet toward it, one cell
   at a time, the body at the stand's height or the head over that. nil when that line is
   already clear. The line goes along x first, then z, unless a cell of it is solid and may not
   be dug (the frame's rock, cobblestone the plan places, a cell beside lava) while the line along
   z first has none: then that one, since a way a wall bars is not the way to dig. Full run
   4e59246 stood at a pond and the pad's stand, four blocks east, was stone inside the hill; the
   walk at it was :none. Real snapshot real-6-s2-Clj_r05 (issue #45): the lava's stand one block
   aside and one on from the feet, the cell between them on the x-first line a stair's floor
   the plan places, so the line was called clear, nothing was dug and the walk failed :no-path,
   with stone on the z-first line to dig."
  [world stand build origin facing]
  (let [{:keys [line digs?]} (stance-way world stand build origin facing)]
    (first (filter digs? line))))

(defn within-reach?
  "Can a dig of pos be started from here: the eye within reach of its centre, the same gate
   the dig fails :out-of-reach on."
  [world pos]
  (<= (physics/distance (game/eye world) (physics/centre pos)) (+ intent/reach 0.5)))

(def reach-slack 0.25)                ; blocks inside the dig's reach a dig is chosen at: the walk's momentum

(defn near-enough?
  "Is pos within reach with room to spare: the eye within intent/reach plus reach-slack of its
   centre, short of the edge within-reach? is. A dig chosen at the edge fails :out-of-reach a tick
   later, the momentum of the walk that ended there carrying the eye past it: real snapshot
   real-5-s2-Clj_r09 (issue #461) began a dig from 4.478 blocks and was at 4.5007 when it settled."
  [world pos]
  (<= (physics/distance (game/eye world) (physics/centre pos)) (+ intent/reach reach-slack)))

(defn approach
  "A walk to beside pos, where a dig of it can start, or nil when no route reaches a cell
   beside it."
  [world pos]
  (when (intent/routable?
         world
         {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true})
    {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true}))

(defn way-dig
  "A child dig of a cell the build opens (its room, a hole, the way to a stand, the rock before a
   pool): beside water or lava when it must be (:intent/exposes-ok?), and with its drop not wanted
   (:intent/drop-ok?), so ore the held pickaxe cannot harvest is broken for nothing rather than
   refused: the cell is what the build needs. Sim race 38806318231 (issue #70) met deepslate
   redstone ore in the room at y -24 with a wooden pickaxe and failed :needs-tool three times."
  [cell]
  (assoc (intent/child-dig cell) :intent/exposes-ok? true :intent/drop-ok? true))

(defn clear-stand
  "The child that opens a way to stand at the feet's height, or nil when the way is clear or the
   stand is at another height (the line would start under the feet: to-height). The block in the
   way is dug when it is in reach with room to spare (near-enough?; a pond beside the hill makes
   the face expose water, which a build's dig is allowed to), else walked to beside, and dug from
   the edge of reach only when no route goes closer. The frame's rock is never taken (kept)."
  [world stand build origin facing]
  (when-let [b (and (= (second stand) (long (physics/y-of (intent/feet world))))
                    (stance-block world stand build origin facing))]
    (cond (near-enough? world b) (way-dig b)
          :else (or (approach world b) (when (within-reach? world b) (way-dig b))))))

(defn dig-from-under
  "The dig of the first block in the way to stand (stance-block, at the stand's height) when the
   feet stand a block under that height, in a hole of the floor, and the block is in reach: the
   same block clear-stand digs from the stand's height, dug from where the walk to it ended. nil
   otherwise. Sim race 38855091523 (issue #178, world R1/34 seed 2, after #250): the walk to the
   block in the way of the lava's stand ended a block under it, in a hole of the floor round the
   pool; off the stand's height the refill sent the feet back to the landing, and the walk went
   again, two walks every two seconds until the run's time ran out."
  [world stand build origin facing]
  (when-let [b (and (= (second stand) (inc (long (physics/y-of (intent/feet world)))))
                    (stance-block world stand build origin facing))]
    (when (within-reach? world b) (way-dig b))))

(defn reaches-from?
  "Would the eye, standing in cell, be within reach of pos? The feet use the real eye; any
   other cell, the eye at its middle."
  [world cell pos]
  (let [[x y z] cell
        eye (if (= cell (intent/feet world))
              (game/eye world)
              [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)])]
    (<= (physics/distance eye (physics/centre pos)) intent/reach)))

(def stand-max-nodes 400) ; expansions spent looking for a reachable local stand to work an op

(defn stand-cell?
  "Could the feet stand in cell to work pos: not pos, the head not in pos, standable, and pos
   within reach from it (reaches-from?)."
  [world cell pos]
  (and (not= cell pos)
       (not= (update cell 1 inc) pos)
       (path/standable? world cell)
       (reaches-from? world cell pos)))

(defn reachable-stand
  "A cell the feet can occupy from which pos is within reach. The feet, when they already
   can; else the standable cell around pos nearest the designated stand that a route from the
   feet reaches (intent/routable?). nil when none, and never the cell being worked. Full run
   61abf8c stood on the shore of a pond: the pad cell was water and the stand was air over the
   next water cell, so a walk there was :partial and six replans failed :no-path. Race
   38477665442 Clj_r25 had scooped its water from a sand cell at the lake's edge under a bank
   two high; the cell nearest the stand was the bank's top, a step up beside it that only cells
   over the water led to, and three builds failed :no-path walking at it while the sand cell
   itself reached the pad (issue #45). The feet may stand a block under pos too, the head level
   with it: sim race 38660819204 (issue #74) had a room cell still in the rock with open cells
   beside it whose floor was a block lower, and failed :no-stand three times. Never a cell whose body, feet or head, is pos: a block is
   not placed into the player. Sim race 38665691522 (issue #44): the cast laid a floor in the
   cell over the feet, the bot's head in it, and the server refused it three times, :rejected."
  [world stand pos]
  (let [feet (intent/feet world)
        ok? #(stand-cell? world % pos)
        routable? (fn [cell]
                    (intent/routable? world
                                      {:intent/kind :walk
                                       :intent/target cell
                                       :intent/for :build
                                       :intent/max-nodes stand-max-nodes}))]
    (if (ok? feet)
      feet
      (->> (for [dy [0 -1 1 -2]
                 dx (range -4 5)
                 dz (range -4 5)
                 :let [c (mapv + pos [dx (inc dy) dz])]
                 :when (ok? c)]
             c)
           (sort-by #(physics/distance (physics/centre %) (physics/centre stand)))
           (filter routable?)
           first))))

(defn to-height
  "The way to y from the feet when no route reaches a stand there: stairs down (stone/stairs) or
   stairs up, cut in the rock as the bot cuts them all day; nil at y already. From a pocket a
   descent died :boxed in (:plan/boxed-at, work-op records it), where stairs give nothing, the
   way out the mine takes (stone/retreat, stone/climb), the next descent from elsewhere. Sim race
   38806318231 (issue #130, world R3/11): the way down to the cast's landing at y -22 met an
   aquifer at y 11, every stair refused, and the build was sent down the same stairs from the
   same cell three times, :boxed."
  [world y]
  (let [fy (long (physics/y-of (intent/feet world)))]
    (cond (> fy y)
          (or (stone/stairs world {:goal/depth y}) (stone/retreat world) (stone/climb world))
          (< fy y) {:intent/kind :stairs-up :intent/to-y y :intent/for :build}
          :else nil)))

(def round-reach 8)                   ; blocks from a stand a corner round the frame's rock is looked for: the rock is six wide

(defn stance-line
  "The cells, at the height of stand b, that a way from the column of a to b passes as
   stance-block walks it: along x first, then along z, both ends included."
  [[ax _ az] [bx by bz]]
  (concat (for [x (range ax bx (if (< ax bx) 1 -1))] [x by az])
          (for [z (range az bz (if (< az bz) 1 -1))] [bx by z])
          [[bx by bz]]))

(defn clear-of?
  "Does the way from a to b (stance-line), body and head, keep off every cell of kept?"
  [kept? a b]
  (not-any? #(or (kept? %) (kept? (update % 1 inc))) (stance-line a b)))

(defn barred
  "The cells a way to a stand never opens: the frame's rock (kept), water, awkward footing, and
   any cell lava lies in or beside (lava-touching?). Solid magma is no walking floor, and a
   flooded way cannot be walked or dug clear (issue #162). Sim race 38843557186 runner 3 (issue
   #231, world R1/31): the way to a landing 72 blocks off at the lava sea's height was dug along
   the sea's edge, a cell beside a source opened (a build's dig may expose fluid), the lava
   flowed in, and no footing was in
   reach: :no-footing three times in a tick."
  [world origin facing]
  (let [kept? (kept origin facing)]
    (fn [c]
      (or (kept? c)
          (lava-touching? world c)
          (= :water (path/classify world c))
          (= :awkward (path/classify world (update c 1 dec)))))))

(def room-footprint
  "[origin facing] → the set of cells the cast's room opens (room-cells, its stair and access
   steps for a landing at the lava's own height) at origin facing. Memoized."
  (memoize (fn [origin facing] (set (map #(cell origin facing %) (room-cells 0))))))

(defn in-room?
  "Is c a cell the cast's room opens at origin facing (room-footprint)? What is dug there is no
   floor and no corner for a way to stand on: the room op digs what a bridge lays."
  [origin facing c]
  (contains? (room-footprint origin facing) c))

(defn flooded-room?
  "Does water stand in a cell of the room the cast opens at origin facing (room-footprint)? The
   site was chosen dry (site-ok?); water in the room after the digs is an aquifer the room let in
   (or a cast's water that no scoop took), and it fills the floor row and the stands a route could
   end at: a route never crosses water, so the ops that follow find no stand, or walks that stall
   and digs out of reach. false for a build with no origin."
  [world origin facing]
  (boolean (and origin
                facing
                (some #(= :water (terrain/fluid-kind world %)) (room-footprint origin facing)))))

(defn round-corner
  "Where a way at the stand's height turns to go round the frame's rock (kept) when the straight
   way from the feet to stand crosses it: a loaded cell within round-reach of stand from which both
   legs keep off the rock, the shortest way first; nil when the straight way is clear, or no
   corner is. The sim race at 76010d2 (issue #45, world R4/33): the bot stood behind its own frame,
   the landing straight ahead through the frame's rock, which a way never digs; the cells past the
   rock were dug, the landing itself was out of reach, and three walks at it failed :no-path.
   Never a corner inside the room the cast digs (in-room?): sim race 38924530337 (issue #45,
   regressed, world R2/9): the corner chosen was a cell of the room's upper row, no floor under
   it but the room's own cell, so the bridge laid cobblestone there, the room op dug it out
   again, and the two alternated until the walk to the corner failed :no-path."
  [world stand origin facing]
  (let [kept? (barred world origin facing)
        feet (intent/feet world)
        [fx _ fz] feet
        [sx sy sz] stand]
    (when-not (clear-of? kept? feet stand)
      (->> (for [dx (range (- round-reach) (inc round-reach))
                 dz (range (- round-reach) (inc round-reach))
                 :let [c [(+ sx dx) sy (+ sz dz)]]
                 :when (and (some? (terrain/block-at world c))
                            (not (in-room? origin facing c))
                            (clear-of? kept? feet c)
                            (clear-of? kept? c stand))]
             c)
           (sort-by (fn [[cx _ cz]]
                      (+ (abs (- cx fx)) (abs (- cz fz)) (abs (- sx cx)) (abs (- sz cz)))))
           first))))
(defn floor-gap
  "The cell under the first cell of the straight way to stand, at the stand's height and past
   the feet's own column, that nothing holds the feet up over: no solid under it, it and the
   head open (stance-block found the way clear), no lava in it. nil when every cell of the way
   has a floor."
  [world stand]
  (let [y (second stand)]
    (->> (rest (way-columns (intent/feet world) stand))
         (map (fn [[x z]] [x (dec y) z]))
         (filter #(and (some? (terrain/block-at world %))
                       (not (holds? world [:solid %]))
                       (not (contains? blocks/lava-states (terrain/block-at world %)))))
         first)))

(defn before-gap
  "The cell of the straight way to stand (way-columns) just before gap, a cell floor-gap found, at
   the stand's height: the last cell a floor holds the feet up in, from which the gap is the next
   cell to lay. nil when gap is no column of the way."
  [world stand [gx _ gz]]
  (when-let [[x z] (last (take-while #(not= [gx gz] %) (way-columns (intent/feet world) stand)))]
    [x (second stand) z]))

(defn bridge
  "The child that lays the missing floor on the way to stand at the feet's height (floor-gap): a
   cobblestone placed in it, or in the lowest cell under it that is held up (foundation), when
   it is in reach and a block is held; a walk onto the floor laid so far (before-gap) when the
   gap lies beyond the reach of the place; nil otherwise. Sim race 38665691522 and a probe at
   8356e28 (issue #162, world R3/37): the refill's way out from the landing ran over a cave's
   open cell beside the room, the line clear and nothing to dig, and the walk there, with no
   floor to stand on, failed :no-path three times. Sim race 39550827333 (issue #162, world R3/43
   seed 2): the bridge laid three cells from the end of the stairs up and the fourth lay past
   the reach of the place, so the plain walk to the stand over the gap was tried and failed
   :no-path, three times in the plan. A gap that holds a block the server will not place into (a
   cobweb: not solid, not replaceable) is dug first, the floor laid after it: real race
   39781947117 runner 2 (issue #44, Clj_r14) tunnelled west through an abandoned mineshaft at
   y 1, laid a cobblestone into a cobweb in the floor three times, :rejected."
  [world stand]
  (when-let [gap (and (held? world :cobblestone) (floor-gap world stand))]
    (cond (and (<= (physics/distance (game/eye world) (physics/centre gap)) place/place-reach)
               (not (blocks/placeable-into? (terrain/block-at world gap))))
          (assoc (intent/child-dig gap) :intent/drop-ok? true)
          (<= (physics/distance (game/eye world) (physics/centre gap)) place/place-reach)
          (when-let [c (foundation world gap)]
            {:intent/kind :place :intent/item :cobblestone :intent/cell c})
          :else
          (when-let [edge (before-gap world stand gap)]
            (let [walk {:intent/kind :walk :intent/target edge :intent/for :build}]
              (when (and (not (intent/centred? world edge))
                         (path/standable? world edge)
                         (intent/routable? world walk))
                walk))))))

(defn stand-walk
  "A walk to stand, a cell of the build's way, when the feet can stand in it as it is
   (path/standable?: a floor, the body clear) and a route reaches it; nil otherwise. A stand
   still in the rock is walked near, never into: the walk ends beside it, uncentred, and the
   stand is no nearer. Sim race 38855091523 (issue #178, world R1/34 seed 2, at 5d78c91): the
   lava's stand at y -53 was stone, a route led to beside it, and the refill walked from the
   landing out to it and back, three walks every five seconds, for the run's last minutes."
  [world stand]
  (let [walk {:intent/kind :walk :intent/target stand :intent/for :build}]
    (when (and (path/standable? world stand) (intent/routable? world walk)) walk)))

(defn way-open?
  "Can the feet get to stand, a cell of the build's way: it can be stood in as it is and a route
   reaches it (stand-walk), or the line stance-block digs along has no solid cell that may not be
   dug (stance-way: the frame's rock, a cell beside lava). Real snapshot real-4-s1-Clj_r08 (issue
   #162): the lava's stand lay in a cave's lava lake, every line to it crossed a cell beside lava,
   nothing was dug and the walk at the stand failed :no-path three times, the build with it. A
   stand at another height than the feet has no line to judge here (to-height), so it is open."
  [world stand build origin facing]
  (let [{:keys [line walled?]} (stance-way world stand build origin facing)]
    (boolean (or (stand-walk world stand)
                 (not= (second stand) (long (physics/y-of (intent/feet world))))
                 (not-any? walled? line)))))

(defn surface-climb?
  "Would the stairs up to height y (to-height) end the tick they start: the feet below y on the
   ground and already out of the ground (stairs/out?: a slope's open sky and a level step), so
   there is nothing to climb through? Sim snapshot R2-39-2 (issue #135): the bot stood on a
   snowy slope at y 95, the landing's stand a block higher sixty blocks off, and the build chose
   the stairs up, done at once, again and again, for the whole window."
  [world y]
  (let [feet (intent/feet world)]
    (boolean
     (and (< (long (physics/y-of feet)) y) (:player/on-ground? world) (stairs/out? world feet)))))

(defn toward-stand
  "The child that brings the feet to stand, a cell of the build's way (a landing, the lava's
   stand): a walk when it can be stood in and a route reaches it (stand-walk), else at its height the first block in the way dug
   (clear-stand), going round the frame's rock by a corner when the straight way crosses it
   (round-corner), or the missing floor laid (bridge), else the way to its height (to-height);
   nil when the feet are there. Out of the ground below the stand's height (surface-climb?)
   the way is the level one, as at its height: stairs up from the open sky climb nothing."
  [world stand build origin facing]
  (let [walk {:intent/kind :walk :intent/target stand :intent/for :build}]
    (cond (intent/centred? world stand) nil
          :else
          (or (stand-walk world stand)
              (if (or (= (long (physics/y-of (intent/feet world))) (second stand))
                      (surface-climb? world (second stand)))
                (or (when-let [c (and origin (round-corner world stand origin facing))]
                      (toward-stand world c build origin facing))
                    (clear-stand world stand build origin facing)
                    (bridge world stand)
                    walk)
                (or (to-height world (second stand)) walk))))))

(defn sight-block
  "The first loaded block on the eyes' ray to the surface of lava source src (scoop/aim-point)
   that the bucket's ray would stop at (scoop/ray-walls: not air, not a fluid, the ray as wide as
   the game's edge test): the rock between the stand and a pool sealed in it, the cell over the
   source last. nil when the line is clear."
  [world src]
  (first (filter #(terrain/block-at world %) (scoop/ray-walls world (scoop/aim-point src)))))

(defn plant-in-sight
  "The first loaded block on the eyes' ray to point (scoop/ray-walls) when it is one the bucket's
   ray stops at yet nothing is built of: a plant or a vein (not solid?, a sculk vein, glow lichen,
   a vine), the cell the cast's scoop line crosses; nil when the line is clear or its first wall
   is solid (the cast's own rock, which a dig would not clear). Sim race 39768353246 (issue #227,
   world R2/15): a sculk vein stood in the cell before the cast's water, the line
   otherwise clear, and the scoop of the water sent three times was never answered, :no-scoop,
   in every build after."
  [world point]
  (let [wall (first (filter #(terrain/block-at world %) (scoop/ray-walls world point)))]
    (when (and wall (not (blocks/solid? (terrain/block-at world wall)))) wall)))

(defn source-in-sight
  "[src block] for the first of sources (nearest first) the eyes can be given a clear line to:
   block is the first block on the line to src (sight-block), nil when the line is clear already,
   and never one a dig may not take (obsidian) or a line unsafe to open (safe-source?).
   nil when no source has such a line."
  [world sources]
  (some (fn [src]
          (let [block (when-not (lava-aim world src) (sight-block world src))]
            (when (and (safe-source? world src)
                       (or (nil? block)
                           (not (contains? intent/never-dug
                                           (blocks/name-of (terrain/block-at world block))))))
              [src block])))
        sources))

(defn scoop-at-station
  "The child that fills the empty bucket with lava from the station's stand, sources those in
   reach (reach-sources), or :no-station: a scoop along the eyes' line of the nearest source the
   eyes see, else the first block on the line to one dug (source-in-sight: the rock between the stand and a
   sealed pool, then the cell over the source). The scoop is aimed (:intent/aim), so it asks only
   that the source is still there (scoop/source?), never air over it: the bucket's ray passes air
   and every fluid that is not a source (SOURCE_ONLY), so a source whose top is flowing lava, or
   one the line enters from the side through a cell just dug, fills it. Sim race 38715231137
   (issue #142, regressed: 166 runs in 44 rounds, worlds R4/15, R1/26, R2/34): the source over
   the nearest had been scooped and lava flowed into its cell, or the sight-block dug the cell
   beside a sealed source under another; the line was clear, the source had no air over it
   (scoop/scoopable?), and the refill failed :no-station three times in three ticks with the pool
   half full (refill-scoops-a-source-under-flowing-lava)."
  [world sources]
  (let [[src block] (source-in-sight world sources)]
    (cond (nil? src) :no-station
          (nil? block)
          {:intent/kind :scoop
           :intent/target src
           :intent/fluid :lava
           :intent/aim (or (lava-aim world src) (scoop/aim-point src))}
          :else (assoc (way-dig block) :intent/for :lava))))

(def beside-reach 2.5)                ; blocks on the plane from a lava stand that count as beside it

(defn beside-stand?
  "Are the feet beside stand: within beside-reach of it on the plane, a block or so off its
   height? The way from there is to the stand's height (toward-stand), not back out to the
   landing. Sim race 38855091523 (issue #178, world R1/34 seed 2, at 5d78c91): a walk toward the
   lava's stand, still stone, ended a block under it in the cell beside it, and the refill sent
   the feet back to the landing, out again, and back, three walks every five seconds."
  [world stand]
  (let [feet (intent/feet world)]
    (and (<= (abs (- (second feet) (second stand))) 2)
         (<= (physics/horizontal-distance (physics/centre feet) (physics/centre stand))
             beside-reach))))

(defn out-first?
  "Does the refill's way to stand go out along the landing's column to out first (the feet nearer
   the frame than the stand, fz under sz, measured out from it): not from beside the stand
   (beside-stand?), and not when the straight way on from out crosses a cell a way never opens
   (barred: the frame's rock, a cell lava lies in or beside), from which round-corner only sends
   the feet back. Sim race 38855091523 (issue #178, world R1/34 seed 2): with the pool scooped to
   its far side, the next stand's row from out crossed the pool's lava; round-corner sent the feet
   a corner back toward the frame, the refill sent them out again, and from beside the stand
   itself out again, until the run's time ran out."
  [world origin facing stand out fz sz]
  (and (< fz sz)
       (not (beside-stand? world stand))
       (clear-of? (barred world origin facing) out stand)))

(defn refill
  "The child that refills lava, or the reason it cannot (:no-station, :no-bucket): the feet to the
   station's stand (toward-stand, from the room's landing at the lava's height when no route
   reaches it); there, the scoop or the dig that opens the line to a source (scoop-at-station)."
  [world {:intent/keys [origin facing build access]}]
  (let [{:keys [stand sources h]} (station-among world
                                                 (lava-sources world)
                                                 origin
                                                 facing
                                                 access
                                                 #(way-open? world % build origin facing))]
    (cond (nil? stand) :no-station
          (not (held? world :bucket)) :no-bucket
          (intent/centred? world stand) (scoop-at-station world sources)
          :else
          (or (stand-walk world stand)
              ;; a block under the stand's height, the block in the way in reach: dig it from here
              (dig-from-under world stand build origin facing)
              (cond (= (long (physics/y-of (intent/feet world))) (second stand))
                    (let [[lx _ _] (landing h)
                          [_ _ sz] (relative origin facing stand)
                          [_ _ fz] (relative origin facing (intent/feet world))
                          out (cell origin facing [lx h sz])]
                      (toward-stand world
                                    (if (out-first? world origin facing stand out fz sz) out stand)
                                    build
                                    origin
                                    facing))
                    ;; a block off the stand's height beside it: stairs to it, not the landing again
                    (beside-stand? world stand) (toward-stand world stand build origin facing)
                    :else
                    (or (toward-stand world (cell origin facing (landing h)) build origin facing)
                        {:intent/kind :walk :intent/target stand :intent/for :build}))))))

(defn room-stand
  "A cell of the room (room-cells for the build's access h) the feet can stand in, that reaches
   pos and that a route reaches, nearest pos: a stair step for a cell high in the rows, which
   reachable-stand, looking near pos's own height, does not see. nil when none."
  [world {:intent/keys [origin facing access]} pos]
  (->> (room-cells (or access 0))
       (map #(cell origin facing %))
       (filter #(and (path/standable? world %) (reaches-from? world % pos)))
       (sort-by #(physics/distance (physics/centre %) (physics/centre pos)))
       (filter #(intent/routable? world
                                  {:intent/kind :walk
                                   :intent/target %
                                   :intent/for :build
                                   :intent/max-nodes stand-max-nodes}))
       first))

(defn nearest-stand-cell
  "Of the cells a stand for pos could be (stand-cell?: the feet can be there and reach pos, the
   body clear of it) at the feet's height, the one nearest pos with a block in the straight way
   to it that may be dug (stance-block: never the frame's rock); nil when none. Where no route
   reaches any (the room dug while the bot was elsewhere, a wall of rock between it and the feet),
   the build cuts its way to this one (toward-stand). Sim race 38660819204 (issue #74): the bot
   stood outside its own room, every cell that reached the next room cell inside it, and failed
   :no-stand three times. Only the feet's height: a stand chosen at another height flips with the
   feet as the stairs to it are cut (the same round's R2/11, with the frame's rock between, went up
   and down two cells for minutes)."
  [world {:intent/keys [build origin facing]} pos]
  (let [[_ fy _] (intent/feet world)]
    (->> (for [dx (range -4 5) dz (range -4 5)] (mapv + pos [dx (- fy (second pos)) dz]))
         (filter #(stand-cell? world % pos))
         (filter #(some? (stance-block world % build origin facing)))
         (sort-by #(physics/distance (physics/centre %) (physics/centre pos)))
         first)))

(def site-reach 8) ; feet this far or nearer the room's landing at its height have no way back to take

(def give-up-radius 16)               ; blocks round a given-up site's origin no new site is chosen in: the same pool, another facing

(defn given-up?
  "Is a frame at origin as good as a site given up: within give-up-radius of one of given, the
   origins given up (memory/given-up-sites)?"
  [given origin]
  (boolean (some #(<= (physics/distance (physics/centre %) (physics/centre origin)) give-up-radius)
                 given)))

(defn lost-way?
  "Did child, the way to the site from far off (a walk, a swim out, stairs), fail for want of a
   way (:no-path, :stuck, or a climb :boxed) beyond site-reach of the room's landing? Then it is
   the site that no way reaches, not an op. A nearby walk whose target is flooded or has awkward
   footing also gives up the site: dry footing alone does not mean a route reaches it (issue #162).
   Sim race 38839665459 (issue #222, world R1/11, six runs in two rounds) chose a site 70 blocks
   off across an aquifer, the walk there failed :no-path,
   the stairs down toward its landing at y 24 were refused for the water beside them, the swim
   out of the water let in dug down a staircase it flooded until :stuck, and the escape from
   that water failed :stuck too: three failures, all toward the one site the planner kept. Only
   a :build has a site: a :tunnel-to (fortress) works its steps through work-op too. A cast-one's
   or a :nether build's walk to a stand that failed :no-path anywhere gives its site up too, from
   the second failure on (:plan/attempts): what the build offers before it, a way dug, a corner,
   a bridge, is exhausted when it is the plain walk left, and a site no way reaches is better
   left than failed (issue #45: real snapshot real-3-s2-Clj_r05 stood in the rock behind its
   frame, the frame's rock all round a way to the landing four blocks off, and failed :no-path
   three times; issue #561, sim snapshots R1-12-2 and R1-24-1: the return portal stood on
   deepslate with lava beside the stand, a route never steps a cell beside lava, and the plain
   walk failed :no-path three times). A :portal build holds a cast already made and is not given
   up for a walk until the third failure in a row (:plan/attempts 2): the first two may be a way
   not yet dug, but a plain walk left that no route follows three times is a site no way reaches
   (issue #162, sim snapshot R3-19-1: the feet stood in water three blocks from the next stand,
   the walk to it failed :no-path three times in three ticks, and the planner kept the site).
   Any walk or dig of a :build that fails :no-path, :stuck or :out-of-reach while water stands in
   its room (flooded-room?) gives it up too: sim race 39610239818 (issue #461, world R1/17): the
   room's digs let an aquifer in, the walk to a stand over the water stalled and failed
   :no-path twice, and the dig the bob of the swim left out of reach failed the third build."
  [world {:intent/keys [kind build origin facing access]} {:intent/keys [reason target] :as child}]
  (boolean
   (and (= :build kind)
        (intent/failed? child)
        (or (and (or (#{:no-path :stuck} reason)
                     (and (= :stairs-up (:intent/kind child)) (= :boxed reason)))
                 (or (> (physics/distance (physics/centre (intent/feet world))
                                          (physics/centre
                                           (cell origin facing (landing (or access 0)))))
                        site-reach)
                     (and (#{:cast-one :nether} build)
                          (= :walk (:intent/kind child))
                          (= :no-path reason)
                          (not (:intent/pickup? child))
                          (pos? (:plan/attempts world 0)))
                     (and (= :portal build)
                          (= :walk (:intent/kind child))
                          (= :no-path reason)
                          (not (:intent/pickup? child))
                          (>= (:plan/attempts world 0) 2))
                     (and (= :walk (:intent/kind child))
                          (not (:intent/pickup? child))
                          target
                          (or (= :water (path/classify world target))
                              (= :water (path/classify world (update target 1 inc)))
                              (= :awkward (path/classify world (update target 1 dec)))))))
            ;; a flooded room (flooded-room?) is no site: its walks stall and its digs fall short
            (and (#{:no-path :stuck :out-of-reach} reason)
                 (#{:walk :dig} (:intent/kind child))
                 (not (:intent/pickup? child))
                 (flooded-room? world origin facing))))))

(defn way-back
  "The way back to the build's site from far off: toward the room's landing (landing for the
   build's access h) as a landing op goes (toward-stand: a walk, else stairs to its height and a
   level way dug), or nil when the feet are within site-reach at its height with a route to it.
   Nearby feet above or below the landing still need a way to its height when no working stance
   is reachable, and so do nearby feet with rock between: sim races 39076911005 and 39070979444
   (issue #74, worlds R3/22 and R1/38) stood six blocks from the landing at its height, the
   room dug and the way to it not, and failed :no-stand three times.
   Sim race 38806318231 (issue #196, 75 runs in 37 rounds, world R2/3):
   the cast's room at y 23, the bot climbed out to y 64 for the wood of a pickaxe, made it, and
   the build's next op, a room cell 41 below and 34 away, found no stand and no cut at the feet's
   height and failed :no-stand three times in three ticks."
  [world {:intent/keys [build origin facing access]}]
  (let [to (cell origin facing (landing (or access 0)))
        feet (intent/feet world)]
    (when (or (not= (second feet) (second to))
              (> (physics/distance (physics/centre feet) (physics/centre to)) site-reach)
              (not (intent/routable? world
                                     {:intent/kind :walk :intent/target to :intent/for :build})))
      (toward-stand world to build origin facing))))

(def far-site 48)                     ; blocks on the plane from the landing past which its unloaded chunk means far off, not still arriving

(defn far-way
  "The way to the build's site from so far off that its landing's chunk is not loaded (a death
   sent the feet back to spawn): a walk toward the landing's column at the feet's height, as far
   as the loaded terrain leads (intent/routable?), so the stairs and the dug way (toward-stand)
   start at the site and not at these feet. nil when the landing's chunk is loaded, when it is
   within far-site of the feet (the chunks of a start are still arriving), or no walk leads
   toward it. Sim race 39076908669 (issue #196, world R3/13 seed 2): the bot died at its
   cast at y 48, woke at spawn 190 blocks off, made a pickaxe for the build's next op, a floor
   cell that is no dig, and found no stand at the feet: :no-stand three times in three ticks."
  [world {:intent/keys [origin facing access]}]
  (let [[lx _ lz] (cell origin facing (landing (or access 0)))
        walk
        {:intent/kind :walk :intent/target [lx (second (intent/feet world)) lz] :intent/for :site}]
    (when (and (not (terrain/chunk-loaded? world [lx 0 lz]))
               (> (physics/horizontal-distance (:player/pos world) [lx 0 lz]) far-site)
               (intent/routable? world walk))
      walk)))

(defn approach-stand
  "The child that gets the feet where op is worked from, or nil when they are there: the room's
   landing is gone to at its height (toward-stand: stairs to it first, then a level way dug); a
   dig is worked from anywhere that reaches it; a room cell (its stand the cell itself) from
   the nearest such cell a route reaches (reachable-stand), or the way back to the landing
   when far off or at another height (way-back);
   entry through the room's landing when no route reaches the portal from here (its back wall
   is kept, never cut through); any other op from its designated stand (toward), or, for a place
   or a dig whose stand cannot be occupied, a cell that already reaches the block. A keyword is why
   no such cell exists. The feet are their own stand when they already stand in it on a block
   that holds them, though a route cannot stand there (soul sand, mud: path treats them as
   awkward): sim snapshot R4-8-1 (issue #575) fell into a soul sand valley and every tunnel
   floor laid from its own feet failed :no-stand three times. Ground in the way is dug only
   for a :build, whose plan says which rock to keep; a :tunnel-to step (fortress) works the
   same ops with no build of its own."
  [world i {:build/keys [op stand pos step]}]
  (let [{:intent/keys [build origin facing]} i
        far (when build (far-way world i))]
    (cond
      far far
      (and (= :landing step) build) (toward-stand world stand build origin facing)
      (and (= :dig op) (within-reach? world pos)) nil
      (and (= :dig op) (= stand pos))
      (let [alt (or (reachable-stand world pos pos) (when build (room-stand world i pos)))
            cut (when (and (nil? alt) build) (nearest-stand-cell world i pos))
            home (when (and (nil? alt) (nil? cut) build) (way-back world i))]
        (cond alt {:intent/kind :walk :intent/target alt :intent/for :build}
              ;; no route into the room: cut the way to the stand nearest the feet
              cut (toward-stand world cut build origin facing)
              ;; no working stance here: back to the landing, including its height
              home home
              :else :no-stand))
      (or (intent/centred? world stand)
          (and (= :place op)
               (= stand (intent/feet world))
               (not (path/standable? world stand))
               (holds? world [:solid (update stand 1 dec)])))
      nil
      (and (= :enter step) build)
      (or
       (stand-walk world stand)
       (toward-stand world (cell origin facing (landing (:intent/access i 0))) build origin facing)
       (toward-stand world stand build origin facing))
      :else
      (let [blocked (when build (clear-stand world stand build origin facing))]
        (cond blocked blocked
              (or (path/standable? world stand) (not (#{:place :dig} op)))
              (or (when build (toward-stand world stand build origin facing))
                  {:intent/kind :walk :intent/target stand :intent/for :build})
              :else
              (let [work (cond (= :place op) (or (foundation world pos) pos)
                               :else pos)
                    alt (reachable-stand world stand work)]
                (cond (nil? alt)
                      ;; a tunnel step (no build of its own) whose stand is the feet is worked
                      ;; from them when no standable cell reaches the op (a soul sand valley,
                      ;; issue #328): the body is already there
                      (when-not (and (= :tunnel-to (:intent/kind i)) (= stand (intent/feet world)))
                        (or (when build (way-back world i)) :no-stand))
                      (intent/centred? world alt) nil
                      :else {:intent/kind :walk :intent/target alt :intent/for :build})))))))

(defn stray-lava?
  "Would water poured into cell touch lava besides the source straight under it, the one it is
   meant to turn to obsidian?"
  [world cell]
  (boolean (some #(contains? blocks/lava-states (terrain/block-at world %))
                 (remove #{(update cell 1 dec)} (touching cell)))))

(defn wading-out?
  "Is child, what approach-stand gave, a walk no route follows (intent/routable?) with the body in
   water? A route never crosses water, so a bot standing in a flooded tunnel has none from it, and
   the walk would be failed :no-path (or dropped, way-refilled?) with the feet in the water: it
   swims out to a bank first (:swim-out for :retreat, done on dry land). Sim race 39081492834
   (issue #135, world R4/34): the stairs ended in a flooded tunnel at y 28, the landing fifty
   blocks off, and the build did nothing for fifteen minutes."
  [world child]
  (boolean (and (map? child)
                (= :walk (:intent/kind child))
                (:player/in-water? world)
                (not (intent/routable? world child)))))

(def max-swims 3)                     ; swim-outs ended on the same feet cell before the build gives its site up

(defn swum
  "world with the running intent's count of swim-outs that ended on the same feet cell
   (:intent/swims, with the cell as :intent/swum-at): one more when the feet are where the last
   one ended, else one. A swim-out that moves the bot is progress; one that ends where it began
   and is asked for again is a loop."
  [world]
  (let [feet (intent/feet world)
        i (:plan/intent world)
        n (if (= feet (:intent/swum-at i)) (inc (long (:intent/swims i 0))) 1)]
    (intent/assoc-intent world :intent/swum-at feet :intent/swims n)))

(defn swim-looped?
  "Has the build's intent i swum out max-swims times on one feet cell (swum)? Sim snapshot
   R3-4-1 (issue #178) stood in a shallow pool at health 1: the swim-out ended on dry land at
   once, the next tick's body was in the water again with no route from it, and the pair ran for
   the whole window."
  [i]
  (>= (long (:intent/swims i 0)) max-swims))

(defn held-count
  "How many of item (a name) the inventory holds."
  [world item]
  (long (inventory/item-count world (recipe/item-id item))))

(defn last-bucket-scoop?
  "Would a scoop take the last empty bucket while a water bucket is held already? Issue #518
   (snapshots R4-8-1, real-2-s1-Clj_r24): the resumed cast's cleanup scoop of the first cell's
   water source ended with two water buckets and no empty one, and the next lava pour found
   nothing to refill with, :no-bucket three times."
  [world]
  (and (= 1 (held-count world :bucket)) (held? world :water_bucket)))

(defn seal-source
  "The child that removes the water source at pos without a bucket: a filler block (stairs/filler)
   placed into it takes the source's place, to be dug out where a later op needs the cell open
   (the next cell's hole, the interior); nil when no filler is held or no solid neighbour holds
   the click (place/support)."
  [world pos]
  (when-let [item (stairs/filler world 1)]
    (when (place/support world pos) {:intent/kind :place :intent/item item :intent/cell pos})))

(defn spare-pour-op
  "A pour op that frees a bucket for a bot holding water buckets and no empty one: the water poured
   into the cell over a cast frame cell (the cell under it is obsidian, where the cast pours its own
   water), open and with a neighbour to click; the cleanup scoop of that cell then seals it with a
   filler (last-bucket-scoop?). nil unless a filler is held and such a cell exists."
  [world {:intent/keys [origin facing]}]
  (let [at #(cell origin facing %)]
    (when (stairs/filler world 1)
      (some (fn [[dx dy :as c]]
              (let [[sx :as s] (stands c)
                    over (at [dx (inc dy) 0])]
                (when (and (holds? world (cast? at c))
                           (holds? world [:not-solid over])
                           (holds? world [:no-water over])
                           (holds? world [:no-lava over])
                           (place/support world over))
                  {:build/op :pour
                   :build/item :water_bucket
                   :build/stand (at s)
                   :build/aim (aim origin facing [dx (inc dy) 0] [(side-aim (- sx dx)) 0.5 -0.02])
                   :build/into over
                   :build/cell c
                   :build/step :spare
                   :build/done [:water-source over]})))
            (reverse frame-cells)))))

(defn op-child
  "The child intent that works op next: lava to refill first when a lava pour has none (a spare
   water bucket poured out first when no empty bucket is held, spare-pour-op), else
   whatever gets the feet centred for the op (a dig of the ground in the way, a walk to the
   stand, or a place already in reach from the shore; a swim out of the water the walk has no
   route from, wading-out?), else the op's own action; or a keyword, why it cannot be worked.
   A cleanup scoop that would take the last empty bucket while a water bucket is held (issue #518,
   last-bucket-scoop?) places a filler into the source instead (seal-source)."
  [world i {:build/keys [op pos item aim into done] :as this}]
  (cond (and (= op :pour) (= item :lava_bucket) (not (held? world :lava_bucket)))
        (if-let [spare (and (not (held? world :bucket))
                            (held? world :water_bucket)
                            (spare-pour-op world i))]
          (op-child world i spare)
          (refill world i))
        (and (= op :pour) (= item :water_bucket) (stray-lava? world into)) :stray-lava
        :else
        (let [c (approach-stand world i this)]
          (cond (wading-out? world c) {:intent/kind :swim-out :intent/for :retreat}
                c c
                :else
                (case op
                  :place
                  (if-let [c (foundation world pos)]
                    {:intent/kind :place :intent/item item :intent/cell c}
                    :no-support)
                  :dig (way-dig pos)
                  :pour
                  {:intent/kind :pour
                   :intent/item item
                   :intent/aim aim
                   :intent/target into
                   :intent/expect done}
                  :scoop
                  (let [plant (when aim (plant-in-sight world aim))]
                    (cond plant (way-dig plant)
                          (and (last-bucket-scoop? world) (seal-source world pos))
                          (seal-source world pos)
                          :else
                          (cond-> {:intent/kind :scoop :intent/target pos}
                            aim (assoc :intent/aim aim))))
                  :use-on
                  {:intent/kind :use-on
                   :intent/item item
                   :intent/target pos
                   :intent/aim (update (physics/centre pos) 1 + 0.5)
                   :intent/expect done}
                  :stand-in {:intent/kind :await :intent/expect done}
                  :verify :unverified)))))

(defn lit-entry
  "The entry op of plan when its preceding light op is satisfied, or nil. Portal plans end
   with light then enter: a lit portal needs no more casting, even if its room has filled."
  [world plan]
  (when (= :enter (:build/step (peek plan)))
    (let [light (peek (pop plan))]
      (when (and (= :light (:build/step light)) (satisfied? world light)) (peek plan)))))

(defn next-op
  "The op of plan to work now: the first not satisfied, or nil when the plan's last op holds (its
   goal: past it, as in another dimension, the earlier ops' cells are not even loaded).
   A lit portal goes straight to entry (lit-entry), not back through the casting work."
  [world plan]
  (when-not (satisfied? world (peek plan))
    (or (lit-entry world plan) (first (remove #(satisfied? world %) plan)))))

(defn refused-for-the-body?
  "Did child, a placement, fail :rejected with its cell inside the player (place/inside-player?)?
   The game refuses a block into any player, its own included: the stand it was sent from was
   wrong, not the op. Sim race 38665691522 (issue #44): the cast laid a floor from a stand whose
   head was in the floor's cell, and the server refused it three times, :rejected."
  [world {:intent/keys [kind reason cell]}]
  (boolean (and (= :place kind) (= :rejected reason) cell (place/inside-player? world cell))))

(defn dig-rejected?
  "Did child, a dig, fail :rejected: the server acked the finish with the block still standing?
   A lagging server drops a finish it found early and queued behind another, and the block is
   still to open. Real race 39405014440 bot Clj_r13 and race 39407278017 bot Clj_r03 (issue #44)
   each failed the whole build :rejected on the first such dig, a block into their cast room."
  [{:intent/keys [kind reason]}]
  (and (= :dig kind) (= :rejected reason)))

(defn pickaxe-at-hand?
  "Could a pickaxe be made without going out for anything: the needs planner's next step toward
   one (make/next-row over plan/goals) a craft or a table placed, not a gather, a mine or a scoop
   (make/fetch?)? A pickaxe that breaks with nothing at hand leaves the rest of a build to the
   hand: the sealed-pool cast's last few blocks, dug slowly, beat a climb for wood that is not
   there."
  [world]
  (let [row (make/next-row world {:goal/id :pickaxe :goal/provides {:tag/pickaxes 1}} plan/goals)]
    (boolean (and (map? row) (not (make/fetch? row))))))

(def hand-dig-limit 10000)            ; ms by hand past which a block is never dug by hand: deepslate's 15 s, not stone's 7.5

(def hand-dig-reach 24)               ; blocks from the site within which stone is dug by hand: the cast's last few, not the way to it

(defn hand-dig-refused?
  "Is a dig of rock at cell, with no pickaxe held, refused for the hand: a pickaxe can be made
   without going out (pickaxe-at-hand?), the block takes longer than hand-dig-limit by hand
   (deepslate, the lava's depth), or the cell lies more than hand-dig-reach from the site's
   origin (nil: no site), a way of tens of blocks at 7.5 s each, which a trip out for a pickaxe
   beats. Stone by hand near the site is left to the hand when a pickaxe needs a trip: a cast's
   last few blocks are best dug. Sim race 39723238841 (issue #504, world R1/5): a bot returning to
   its portal wore out its stone pickaxe at y 21 with one stick held and dug the eighty blocks
   still to go by hand, ten minutes."
  [world cell origin]
  (boolean (or (> (intent/dig-time (terrain/block-at world cell)) hand-dig-limit)
               (pickaxe-at-hand? world)
               (and origin
                    (> (physics/distance (physics/centre cell) (physics/centre origin))
                       hand-dig-reach)))))

(defn found-open?
  "Did child, a dig, find its cell already open (:target-gone, intent/open?)? The cell is open,
   which is what the dig was for: the op looks again from there. Sim race 38665691522 world R3/43
   (issue #91): the way to the cast's landing at y 24 dug an awkward block over the feet the
   dig took for open, and the build failed :target-gone."
  [{:intent/keys [kind reason]}]
  (and (= :dig kind) (= :target-gone reason)))

(defn lava-gone?
  "Did child, a lava scoop (scoop-at-station), find its source gone (:target-gone)? The cast's own
   water, or the water the digs let in, turned it to obsidian between the choice and the aim; the
   refill looks for another source. Real races 39245788157 and 39590862487 (issue #419): the
   scoops of one pool failed :target-gone one after another with lava still in reach, and each
   ended the build."
  [{:intent/keys [kind reason fluid]}]
  (and (= :scoop kind) (= :lava fluid) (= :target-gone reason)))

(defn boxed-way?
  "Did child, a staircase or a tunnel (plan/boxing-kinds), fail :boxed: no way on from where the
   feet stand?"
  [{:intent/keys [kind reason]}]
  (and (contains? plan/boxing-kinds kind) (= :boxed reason)))

(defn tool-first
  "c, the child op-child gave, or before a dig the :equip of the tool it should hold when that one
   is only in the main inventory (intent/stowed-breaker), as a staircase equips before its digs
   (stairs/dig-next). A pickaxe that breaks in the cast leaves its spare stowed: sim race
   38806318231 (issue #70) dug the way to the landing at y 29 by hand, nine seconds a stone, with a
   stone pickaxe in slot 35, and failed :needs-tool on iron ore in the way, three times."
  [world c]
  (let [[_ item :as stowed] (when (= :dig (:intent/kind c))
                              (some->> (terrain/block-at world (:intent/target c))
                                       (intent/stowed-breaker world)))]
    (if stowed {:intent/kind :equip :intent/item (recipe/item-name item)} c)))

(defn way-refilled?
  "Did child, a walk along the build's way, fail :no-path with a falling block (gravel, sand:
   blocks/falls?) now on the straight way from the feet to its target (stance-line, body or
   head)? What lay over the way fell into it after the route was planned; the next clear digs it
   out, so the walk is dropped, not failed, and never sent again over the same cells while the
   block stands there. Sim race 38837004562 (issue #45, world R1/6): the level way to the landing
   at y 21 ran under a bed of gravel, each dug cell filled again, and three walks along it failed
   :no-path. Only a block the next clear can dig, within reach of the eye (within-reach?):
   sim race 39081492834 (issue #135, world R4/34) stood in a pocket of water with the landing
   fifty blocks off and a gravel block somewhere on the straight way, out of reach, so each
   walk's failure was dropped for a clear that never came, every tick for fifteen minutes."
  [world {:intent/keys [kind reason target]}]
  (boolean (and (= :walk kind)
                (= :no-path reason)
                target
                (some #(and (within-reach? world %)
                            (some-> (terrain/block-at world %)
                                    blocks/falls?))
                      (mapcat (fn [c] [c (update c 1 inc)])
                       (stance-line (intent/feet world) target))))))

(defn scoop-blocked?
  "Is a lava scoop child's aimed ray now blocked (scoop/ray-walls)? The refill chose a clear
   line, but gravel can fall into it while the bucket is equipped or its answer is awaited."
  [world {:intent/keys [kind fluid aim]}]
  (boolean (and (= :scoop kind) (= :lava fluid) aim (seq (scoop/ray-walls world aim)))))

(defn site-lost
  "The world with the build's site given up and the intent done, or nil, when c, what op-child gave,
   says the site is no use: :no-station at a :build (lava remains but no safe station reaches it),
   or :no-stand at a :cast-one (no cell reaches the op and none can be cut), or at any :build
   whose room has water standing in it (flooded-room?: every cell a stand could be is awash, and
   a route never crosses water). Either way the next build chooses another site. Any other build
   fails with the keyword as it always did. Sim race 39470661984 (issue #196, world R3/40): the
   room's digs let an aquifer in, the floor row flooded, and the first wall op found no stand
   three times."
  [world {:intent/keys [kind build origin facing]} c]
  (when-let [say (cond (and (= :no-station c) (= :build kind))
                       "build: no safe lava station at the site, a new site"
                       (and (= :no-stand c) (= :cast-one build))
                       "build: no stand at the site, a new site"
                       (and (= :no-stand c) (= :build kind) (flooded-room? world origin facing))
                       "build: the site's room is flooded, a new site")]
    (-> world
        (assoc :player/controls {})
        (memory/give-up-site origin)
        intent/done
        (game/say say))))

(defn work-op
  "One tick of the running intent i working op next through a child (op-child): a new op drops
   the child of the old one (the world moved on), except an active climb finishes before its
   headroom is repaired. A child that failed fails i (a dig that found
   its cell open, found-open?, makes way for the next as one done would), one that is done
   makes way for the next. A lava scoop whose sight line filled (scoop-blocked?) is dropped so
   the refill opens it again; a refill dig of the feet's support is dropped to choose another
   station. Shared by every intent that works a plan of ops. A dig of rock with no
   pickaxe held fails :no-pickaxe (stairs/tool-missing) rather than dig it by hand when one can be
   made without going out for anything (pickaxe-at-hand?), so the build's row makes one first (held-first): sim race 38669750355 (issue #135) broke its last pickaxe
   in the cast's room at y -20 and dug deepslate by hand, fifteen seconds a block, for the last
   half hour of its ninety minutes. A tunnel-to only refuses the hand when a replacement is at
   hand: without wood in the Nether, even slow rock must be cleared to reach the fortress."
  [world {:intent/keys [child op origin] :as i} next event]
  (cond
    (not= next op)
    (if (and (= :stairs-up (:intent/kind child))
             (not (or (intent/done? child) (intent/failed? child))))
      (intent/run-child world event)
      (intent/dissoc-intent (intent/assoc-intent world :intent/op next) :intent/child))
    (and (= :build (:intent/kind i)) (swim-looped? i))
    (-> world
        (assoc :player/controls {})
        (memory/give-up-site (:intent/origin i))
        intent/done
        (game/say (str "build: swimming out never leaves the water, a new site, not "
                       (:intent/origin i))))
    (and child (intent/done? child))
    (cond-> (intent/dissoc-intent world :intent/child :intent/rejections)
      (= :swim-out (:intent/kind child)) swum)
    (and child (refused-for-the-body? world child))
    ;; the server refused a block into the bot's own body: the stand was wrong, not the op
    (-> world
        (assoc :player/controls {})
        (intent/dissoc-intent :intent/child)
        (game/say (str "build: placing " (:intent/cell child) " refused with the body in it")))
    (and child (intent/failed? child) (found-open? child))
    (intent/dissoc-intent (assoc world :player/controls {}) :intent/child)
    (and child (intent/failed? child) (lava-gone? child))
    (-> world
        (assoc :player/controls {})
        (intent/dissoc-intent :intent/child)
        (game/say "build: the lava source was gone, choosing another"))
    (and child (intent/failed? child) (way-refilled? world child))
    ;; gravel fell into the way after the walk was planned: clear it again
    (-> world
        (assoc :player/controls {})
        (intent/dissoc-intent :intent/child)
        (game/say "build: the way filled with what fell over it, clearing it again"))
    (and child (scoop-blocked? world child))
    (-> world
        (assoc :player/controls {})
        (intent/dissoc-intent :intent/child)
        (game/say "build: the scoop's sight line filled, opening it again"))
    (and (= :dig (:intent/kind child))
         (= :lava (:intent/for child))
         (= (update (intent/feet world) 1 dec) (:intent/target child)))
    (-> world
        (assoc :player/controls {})
        (intent/dissoc-intent :intent/child)
        (game/say "build: the refill would remove the feet's support, choosing another station"))
    (and (= :build (:intent/kind i))
         (= :walk (:intent/kind child))
         (= :retreat (:intent/for child))
         (intent/failed? child)
         (= :no-path (:intent/reason child))
         (:intent/target child))
    ;; avoid retrying the same failed waypoint when the build recalculates its way out
    (-> world
        (assoc :player/controls {})
        (update :plan/retreat-unreachable (fnil conj #{}) (:intent/target child))
        (intent/dissoc-intent :intent/child)
        (game/say "build: retreat waypoint failed, finding another"))
    (and child (lost-way? world i child))
    ;; no way reaches the site from here: it is given up and the planner chooses another
    (-> world
        (assoc :player/controls {})
        (memory/give-up-site (:intent/origin i))
        intent/done
        (game/say (str "build: no way to the site at " (:intent/origin i) ", a new site")))
    (and child (intent/failed? child) (boxed-way? child))
    ;; a way to the site boxed in: the pocket is remembered and the build looks again from it
    ;; (to-height takes the way out instead), as plan/fail-intent remembers an intent's
    (-> world
        (assoc :player/controls {})
        (update :plan/boxed-at (fnil conj #{}) (intent/feet world))
        (intent/dissoc-intent :intent/child)
        (game/say (str "build: " (:intent/kind child) " boxed in, the way out")))
    (and child (intent/failed? child) (dig-rejected? child))
    ;; the server did not do the dig (its ack came with the block standing): a block is still
    ;; to open, so it is dug again, as a stair does, before the build gives up
    (stairs/rejected (assoc world :player/controls {}) i)
    (and child (intent/failed? child))
    (-> world
        (assoc :player/controls {})
        (intent/fail (:intent/reason child)))
    child (intent/run-child world event)
    :else
    (let [c (op-child world i next)
          missing (when (= :dig (:intent/kind c)) (stairs/tool-missing world (:intent/target c)))
          ;; the needs planner is asked only when no pickaxe is held
          c (or (when (and missing
                           (if (= :tunnel-to (:intent/kind i))
                             (pickaxe-at-hand? world)
                             (hand-dig-refused? world (:intent/target c) origin)))
                  missing)
                (tool-first world c))]
      (or (site-lost world i c)
          (cond (keyword? c) (intent/fail world c)
                :else
                (intent/run-child
                 (intent/assoc-intent world :intent/child (assoc c :intent/status :active))
                 event))))))

(defmethod intent/run :build
  ;; work the first op of the plan not satisfied (next-op, work-op); done when the last op holds.
  ;; A lost pickaxe ends this intent so held-first replaces it without spending a failed attempt.
  ;; The site is remembered once, so a new life resumes the build there
  [world {:intent/keys [origin facing] :as i} event]
  (let [world (cond-> world
                (not= {:site/origin origin :site/facing facing} (memory/site world))
                (memory/remember-site origin facing))
        next (next-op world (plan-for (:intent/build i) origin facing (:intent/access i 0)))]
    (if (nil? next)
      (-> world
          (assoc :player/controls {})
          intent/done)
      (let [world (work-op world i next event)
            i (:plan/intent world)]
        (cond (and (intent/failed? i) (= :no-pickaxe (:intent/reason i)))
              (-> world
                  (assoc :player/controls {})
                  (intent/dissoc-intent :intent/reason)
                  intent/done
                  (game/say "build: the pickaxe broke, planning its replacement"))
              :else world)))))

;; ---------------------------------------------------------------- the site and the targets

(def site-blocks 8)                   ; blocks a site's cast may place at most: a handful
(def station-spots
  "[dx h] where a site's lava stand may be from its origin, seven in front of the frame (dz 7):
   straight out from the landing at h (station-among), so the level way to it runs away from the
   frame."
  (vec (for [h [1 3 6]] [(first (landing h)) h])))
(def station-reach 7)                 ; how far in front of the frame a site puts the lava's stand
(def max-sources 3)                   ; the nearest lava sources a site is looked for beside

(defn blocks-needed
  "How many blocks plan places where the world shows them missing now, or where an op of plan digs
   before them (a hole's over cell that is later another's wall), its guard not yet holding: what
   a cast at that place asks to carry (issue #124: counted from the plan, never a constant). A
   cell dug as a hole is obsidian after, never missing: a column's cell is the next one's floor."
  [world plan]
  (:n
   (reduce (fn [{:keys [dug n]} {:build/keys [op pos step done] :as o}]
             (let [[k guard] done]
               (case op
                 :dig
                 {:dug (case step
                         :fall-clear dug
                         :hole (disj dug pos)
                         (conj dug pos))
                  :n n}
                 :place
                 {:dug dug
                  :n (cond-> n
                       (or (not (satisfied? world o))
                           (and (contains? dug pos) (= :or k) (not (holds? world guard))))
                       inc)}
                 {:dug dug :n n})))
           {:dug #{} :n 0}
           plan)))

(defn site-ok?
  "Can a frame be cut at origin facing with its lava stand h over the room's floor: every cell of
   the footprint loaded, no fluid in it or touching it (lava in the rock beside a hole runs into
   it; water in the room spills onto a source), a station, and lava enough for every cell (not
   spent?)?"
  [world sources origin facing h]
  (let [cells (footprint origin facing h)]
    (and (every? #(some? (terrain/block-at world %)) cells)
         (dry? world cells)
         (dry? world (remove cells (mapcat touching cells)))
         (some? (station-among world sources origin facing))
         (not (spent? world sources origin facing)))))

(defn build-near
  "The sources of sources a site beside src could use: within pool-radius of any origin a site
   beside it may take (station-reach and the frame's width further). Asked once per source, so a
   site's checks look at a handful, never every lava source loaded (thirty thousand near the
   lava sea, forty seconds a choice)."
  [sources src]
  (near sources src (+ pool-radius station-reach 6)))

(defn choose-site
  "{:origin :facing :h} for a new frame (issue #124: the rock is the mould): beside a lava source
   among the max-sources nearest the feet, a cell beside the cell over it (stands-beside) put at
   one of station-spots, every facing; of those site-ok? accepts, the fewest blocks to place
   (blocks-needed, at most site-blocks), then the nearest the feet. nil when none. Sources and
   origins within give-up-radius of a site given up are passed over (given-up?, issue #222)."
  [world]
  (let [feet (physics/centre (intent/feet world))
        sources (lava-sources world)
        given (memory/given-up-sites world)
        near (->> sources
                  (remove #(given-up? given %))
                  (sort-by #(physics/distance (physics/centre %) feet))
                  (take max-sources))]
    (->> (for [src near
               :let [local (doall (build-near sources src))]
               [stand _] (stands-beside world src)
               facing [:south :west :north :east]
               [dx h] station-spots
               :let [[tx tz] (turn facing [dx station-reach])
                     origin (mapv - stand [tx h tz])]
               :when (and (not (given-up? given origin)) (site-ok? world local origin facing h))
               :let [n (blocks-needed world (plan-for :nether origin facing h))]
               :when (<= n site-blocks)]
           {:origin origin
            :facing facing
            :h h
            :blocks n
            :far (physics/distance (physics/centre stand) feet)})
         (sort-by (juxt :blocks :far))
         first)))

;; ---------------------------------------------------------------- the lava hunt

(def lava-depth -54)                  ; the feet a block over the lava sea: below y -54 the game fills its aquifers with lava (ruststeve cast at y -50, the sea's surface at -55)
(def hunt-radius 48)                  ; blocks from the feet a lava source is walked, dug or tunnelled toward
(def far-radius 160)                  ; blocks from the feet a source is tunnelled toward when none lies within hunt-radius: what a view shows
(def hunt-checked 300)                ; the nearest far sources asked for a stand and a pool: each ask reads every loaded source
(def hunt-rise 4)                     ; blocks over the feet the cell over a source may lie when none lies level or below: the stairs up go first
(def max-candidates 3)                ; the nearest sources a route is planned to

(defn sources-near
  "The lava sources loaded within radius of the feet (hunt-radius by default), nearest first,
   outside given-up sites. Hunting back to a pool the planner refuses only walks between its
   stands forever."
  ([world] (sources-near world hunt-radius))
  ([world radius]
   (let [feet (physics/centre (intent/feet world))
         given (memory/given-up-sites world)]
     (->> (lava-sources world)
          (filter #(<= (physics/distance (physics/centre %) feet) radius))
          (remove #(given-up? given %))
          (sort-by #(physics/distance (physics/centre %) feet))))))

(defn lava-walk
  "A walk to a cell beside an exposed source's air (stands-beside), from which a site has its
   station: the first of the max-candidates nearest scoopable sources a route reaches; nil when
   none, and never to a stand the walk would call arrived at once (intent/arrived?: the eye
   within reach of it), where the bot already is. Real race 39076909951 (issue #163, bot Clj_r02):
   the stand a block over the roof just dug was beyond the two blocks the feet had to be from it
   and within the walk's reach, so the walk ended at once, the planner chose it again, and the
   build did nothing else, a walk every fifty milliseconds."
  [world]
  (let [eye (game/eye world)]
    (->> (sources-near world)
         (filter #(scoop/scoopable? world % :lava))
         (take max-candidates)
         (mapcat #(stands-beside world %))
         (map (fn [[stand _]] {:intent/kind :walk :intent/target stand :intent/for :lava}))
         (remove
          #(intent/arrived? world % (physics/distance eye (physics/centre (:intent/target %)))))
         (filter #(intent/routable? world %))
         first)))

(defn uncap
  "A dig of the roof over the nearest roofed lava source within reach of the eye that has a
   cell to stand in beside the roof (stands-beside: the station the dig makes), so it can be
   scooped: a solid the tools can take (never obsidian or bedrock), never the cell under the
   feet. The deep sea lies under a ceiling of deepslate (ruststeve: 'roofed sources ... scooped
   by digging the roof'); the dig is allowed to expose the lava under it, which flows no higher,
   and its drop is not wanted (way-dig): a roof of ore the pickaxe cannot harvest is broken for
   nothing, not refused. Real race 39076909951 (issue #163, bot Clj_r02): the roof over the sea at
   y -59 was deepslate diamond ore, a stone pickaxe in hand, and the dig failed :needs-tool three
   times in a moment. nil when none."
  [world]
  (let [under (update (intent/feet world) 1 dec)
        roof-of (fn [[x y z :as src]]
                  (let [roof [x (inc y) z]
                        id (terrain/block-at world roof)]
                    (when (and id
                               (blocks/solid? id)
                               (not (contains? #{:obsidian :bedrock} (blocks/name-of id)))
                               (not= roof under)
                               (within-reach? world roof)
                               (seq (stands-beside world src)))
                      roof)))]
    (when-let [roof (first (keep roof-of (sources-near world)))]
      (assoc (way-dig roof) :intent/for :lava))))

(defn heading-to
  "The cardinal [dx dz] from feet toward pos, along the longer of the two plane distances."
  [[fx _ fz] [x _ z]]
  (let [dx (- x fx)
        dz (- z fz)]
    (if (>= (abs dx) (abs dz))
      [(long (math/signum (double dx))) 0]
      [0 (long (math/signum (double dz)))])))

(defn castable?
  "Could a frame be cast from the lava round source src: at least as many sources of sources
   within pool-radius of it as the frame has cells (spent? asks the same of a site's stands)?
   A lone source never is."
  [sources src]
  (>= (count (near sources src pool-radius)) (count frame-cells)))

(defn hunt-target
  "The lava source the hunt cuts toward (toward-lava): the nearest loaded within hunt-radius
   (sources-near) whose cell over it is not above the feet, since the hunt's stairs go down and
   its tunnel goes level, with a safe stand to open beside it (stands-beside), and round which
   a frame could be cast (castable?). At its height a source must be more than one cell away on
   the plane: the tunnel stops beside it, so an adjacent source leaves no step to cut and must
   not hide farther sources. nil when none.
   Sim race 38811561269 runner 6 (issue #41, worlds R1/38, R2/8 and R2/32 round after round,
   R2/26 on runner 8): the hunt took the nearest source whatever its height, tunnelled under a
   lone source six blocks over the feet (R1/38) and under a small pool eleven over (R2/26),
   stopped a block short of the column with nothing to cut, the roof out of reach, and waited
   :no-site with fifteen thousand sources of the lava sea loaded below. With none within
   hunt-radius the nearest within far-radius is taken, as far as the terrain loaded shows: real
   race 38929353563 (issue #41, Clj_r13 and Clj_r14) stood at y -54 over six thousand sources
   loaded, the nearest farther than hunt-radius, and waited :no-site out.
   With none level or below, one whose cell over it lies at most hunt-rise over the feet is taken
   (toward-lava goes up to it first): real race 39408516353 (issue #392, Clj_r05) had followed a
   tunnel at y -58 into the lava sea's edge, gave its far source up, and waited :no-site with
   a source whose stand was nine blocks off and four over the feet, the three hundred nearest
   sources all under the sea's lava."
  [world]
  (let [[fx fy fz] (intent/feet world)
        sources (lava-sources world)
        usable? (memoize (fn [[x y z :as src]]
                           (and (or (> fy (inc y)) (> (max (abs (- x fx)) (abs (- z fz))) 1))
                                (seq (stands-beside world src))
                                (castable? sources src))))
        target? (fn [rise] (fn [[_ y _ :as src]] (and (<= (inc y) (+ fy rise)) (usable? src))))]
    (some (fn [rise]
            (let [target? (target? rise)]
              (or (first (filter target? (sources-near world)))
                  (first (filter target? (take hunt-checked (sources-near world far-radius)))))))
          [0 hunt-rise])))

(defn explore-lava
  "A tunnel of level steps at the lava sea's depth, away from the landing (the way the terrain
   loaded so far has no lava: the chunks ahead are new), for the hunt when no source is loaded
   at all (hunt-target nil) and the feet are already down at lava-depth: it kept the plan
   waiting :no-site. Real race 39318301246 (issue #41, Clj_r21): at y -54 in deepslate with no
   lava within the loaded 160 blocks, the stairs done, nothing left to cut. Away from the
   landing along the longer plane distance, east when the feet are on it, so each call after
   the tunnel's length goes on the same way."
  [world feet]
  (let [[hx hy hz] (:plan/go-at world feet)
        [dx dz] (mapv - [0 0] (heading-to feet [hx hy hz]))
        [dx dz] (if (= [0 0] [dx dz]) [1 0] [dx dz])]
    {:intent/kind :tunnel
     :intent/dir [dx dz]
     :intent/length stone/tunnel-length
     :intent/for :lava}))

(defn toward-lava
  "The way toward lava a frame can be cast from (hunt-target): stairs down (stone/stairs,
   stair-depth at a time) while the feet are above the cell over it, stairs up (to-height) while
   they are under it (a source at most hunt-rise over them), else a tunnel of level
   steps toward it, as many as the plane distance less one (the step onto the cell over the
   source is refused for the lava under it, and the roof is then in reach: uncap). With no such
   source loaded, stairs down to lava-depth. From a feet cell in :plan/boxed-at no stair is cut
   (stone/stairs): the way out the mine takes (stone/retreat, stone/climb), as the build's own
   descent does (to-height). Sim race 38515210080 (issue #41, world R4/36, at the iron mine's
   y 16 with the lava sea forty blocks under, the feet boxed by an earlier descent): nothing was
   cut and :no-site was waited out. The tunnel carries its source (:intent/target): one boxed
   in gives that source up (stairs/box) and the hunt takes the next, and from a feet cell a
   tunnel boxed in no other is cut (the way out first), as no tunnel for ore is (stone/tunnel-heading).
   Sim races 39085720764 and 39085577169 (issue #130, worlds R1/19 and R1/25): the tunnel east from the foot of
   the stairs met an aquifer on each of its three headings and was :boxed three times in a tick,
   toward the one source. nil when nothing can be cut from here."
  [world]
  (let [feet (intent/feet world)
        [sx sy sz :as src] (hunt-target world)
        to (if src (inc sy) lava-depth)]
    (cond (> (physics/y-of feet) to)
          (or (stone/stairs world {:goal/depth to}) (stone/retreat world) (stone/climb world))
          (contains? (:plan/boxed-at world #{}) feet) (or (stone/retreat world) (stone/climb world))
          (nil? src) (explore-lava world feet)
          (< (physics/y-of feet) to) (to-height world to)
          :else
          (let [steps (dec (long (max (abs (- sx (first feet))) (abs (- sz (nth feet 2))))))]
            (when (pos? steps)
              {:intent/kind :tunnel
               :intent/dir (heading-to feet src)
               :intent/length (min stone/tunnel-length steps)
               :intent/for :lava
               :intent/target src})))))

(defn lava-hunt
  "The next step toward lava a site can refill from, when no site is at hand: a walk to a stand
   beside an exposed source (lava-walk), the roof over a source in reach dug (uncap), stairs or
   a tunnel toward the nearest source loaded, or toward the depth lava lies at (toward-lava);
   nil when none can be taken. Issue #41, the wall before the Nether: race 38477665442's 47
   real bots and 950 of the sim race's runs stood at the surface with the kit, no lava within
   sixteen of any origin, and waited :no-site out. Lava is not on the surface of a 1.18+ world:
   it is the sea under y -54, roofed, and cave springs; the stairs and tunnel go only toward
   lava a frame can be cast from, below or level with the feet (hunt-target)."
  [world]
  (or (lava-walk world) (uncap world) (toward-lava world)))

(defn table-at-hand?
  "Could a crafting table be made without going out for anything: the needs planner's next step
   toward one (make/next-row over plan/goals) a craft from planks or logs held, not a gather
   (make/fetch?)?"
  [world]
  (let [row (make/next-row world
                           {:goal/id :crafting-table :goal/provides {:item/crafting_table 1}}
                           plan/goals)]
    (boolean (and (map? row) (not (make/fetch? row))))))

(defn wood-near?
  "Is the kit's wood a short trip: the feet out of the ground (stairs/out?) and a standing trunk a
   route reaches (wood/near-walk)? Underground it is the climb out from the lava, and the build
   goes on with what it holds. Sim race 39076908669 (issue #178, world R2/21 seed 2): the last
   pickaxe broke at the cast at y -52 with one stick, no planks and no table, and the stairs up
   through deepslate by hand, three digs of seventeen seconds a block, ran the run's last hour
   for the log: the wood was a short walk from the surface the build began at."
  [world]
  (and (stairs/out? world (intent/feet world)) (some? (wood/near-walk world))))

(defn carry-table?
  "Should a build take a crafting table into its kit: none held, none placed within reach to take
   along (make/take-table-along), and one can be made from what is held (table-at-hand?) or its
   wood is a short trip (wood-near?). A cast
   wears out every pickaxe it holds; with a table, sticks and cobblestone the next is made where
   the bot stands. Sim races 38806318231 and 38843552659 (issue #186, worlds R1/25, R2/11, R1/18,
   R4/39): the pickaxes broke in the cast with sticks and four hundred cobblestone in hand and no
   table, and the bot climbed out for a log and waited :no-log on treeless ground."
  [world]
  (and (zero? (get (make/counts world) :crafting_table 0))
       (nil? (make/take-table-along world))
       (or (table-at-hand? world) (wood-near? world))))

(def kit-sticks 4)                    ; sticks a build carries: two pickaxes' handles, made again where the cast wore the last out and for the climb after it

(defn sticks-at-hand?
  "Could kit-sticks be made without going out for anything: the needs planner's next step toward
   them a craft from planks or logs held, not a gather (make/fetch?)?"
  [world]
  (let [row (make/next-row world
                           {:goal/id :kit-sticks :goal/provides {:item/stick kit-sticks}}
                           plan/goals)]
    (boolean (and (map? row) (not (make/fetch? row))))))

(defn carry-sticks?
  "Should a build take kit-sticks into its kit beside the table: fewer held, a table held or
   placed within reach already (the table first: carry-table?, so the planks a table needs are
   never spent on sticks), and they can be made from what is held (sticks-at-hand?) or their wood
   is a short trip (wood-near?). A table and
   cobblestone make no pickaxe without sticks: a probe of world R3/12 seed 1 from spawn on main
   (Refs #178) wore out its pickaxes in the cast holding a table, 684 cobblestone and one plank,
   no sticks, and cut stairs up through deepslate by hand for a log, fifteen seconds a block,
   until its ninety minutes ran out."
  [world]
  (and (< (get (make/counts world) :stick 0) kit-sticks)
       (or (pos? (get (make/counts world) :crafting_table 0)) (some? (make/take-table-along world)))
       (or (sticks-at-hand? world) (wood-near? world))))

(defn water-needed?
  "Does plan still pour water (an op not satisfied that pours :water_bucket) while an empty
   bucket is held to fill and no water bucket is? A cast's water poured into the cell over the lava can land in the lava's own cell: the
   lava turns to obsidian, the water goes with it and there is no source to scoop back, so the
   bucket stays empty. Real races 39181279602 runner 4 and 39499334753 runner 2 (issue #455): the
   next cell's water pour failed :not-held three times in 300 ms and the plan ended with two empty
   buckets and no water in sight."
  [world plan]
  (boolean (and (held? world :bucket)
                (not (held? world :water_bucket))
                (some #(and (= :pour (:build/op %))
                            (= :water_bucket (:build/item %))
                            (not (satisfied? world %)))
                      plan))))

(defn held-first
  "What the needs planner does for what a build row holds before it starts: the tools of
   :goal/holds (a pickaxe for the way down to lava and the digs: sim race 38477665389, issue #70,
   dug for its stance with none and failed :needs-tool three times), a crafting table when one
   can be made from what is held and none is at hand (carry-table?, issue #186), and the blocks
   plan places at the site (blocks-needed, issue #124: counted from the plan for the place
   chosen). An intent that makes them, a wait, or nil when they are held."
  [world goal table plan]
  (let [n (if plan (blocks-needed world plan) 0)
        holds (cond-> (or (:goal/holds goal) {})
                (pos? n) (assoc :item/cobblestone n)
                (water-needed? world plan) (assoc :item/water_bucket 1)
                (carry-table? world) (assoc :item/crafting_table 1)
                (carry-sticks? world) (assoc :item/stick kit-sticks))]
    (when (seq holds) (make/decide world (assoc goal :goal/provides holds) table))))

(defmethod plan/next-intent :build
  ;; what the row holds first (held-first); then the placed table taken along (make/take-table-along:
  ;; the build and the hunt go down to the lava, and a pickaxe broken there is made again only from
  ;; sticks, cobblestone and a table; sim race 38669750355, issue #135, had the sticks and the
  ;; cobblestone and no table at y -21, and issue #186's casts left the table at the furnace); then
  ;; build the row's plan (:goal/build) at the site remembered while its pool can finish it
  ;; (spent?), else at one chosen now (the :build intent remembers it), its access steps up to the
  ;; lava's height (station); with none, hunt for lava (lava-hunt), else wait :no-site.
  ;; A reachable lit portal skips the kit but not the placed table, taken along on the way in: a
  ;; pickaxe made at the portal leaves the table standing, and the Nether has no wood for another
  ;; (sim race probe 39782256061, issue #338, R2/12 seed 2: table held at 15 minutes, gone at 16
  ;; in the Nether, one stick, one plank; the tunnel dug by hand for the next hour)
  [world {:goal/keys [build] :as goal} table]
  (let [{:site/keys [origin facing]}
        (let [{:site/keys [origin facing] :as site} (memory/site world)]
          (when-not (and origin
                         (or (spent? world (lava-sources world) origin facing)
                             (given-up? (memory/given-up-sites world) origin)))
            site))
        chosen (when-not origin (choose-site world))
        origin (or origin (:origin chosen))
        facing (or facing (:facing chosen))
        h (when origin (or (:h chosen) (:h (station world origin facing)) 1))
        ops (when origin (plan-for build origin facing h))
        entry (lit-entry world ops)]
    (or (when-not (and entry
                       (or (intent/centred? world (:build/stand entry))
                           (stand-walk world (:build/stand entry))))
          (held-first world goal table ops))
        (intent/chain-next (:plan/last world) :take-table make/table-drops {})
        (make/take-table-along world)
        (cond origin
              {:intent/kind :build
               :intent/build build
               :intent/origin origin
               :intent/facing facing
               :intent/access h}
              :else (or (lava-hunt world) {:plan/wait :no-site})))))

(defmethod plan/done-by :built?
  [world {:goal/keys [build]}]
  (let [{:site/keys [origin facing]} (memory/site world)]
    (boolean (and origin (satisfied? world (peek (plan-for build origin facing 0)))))))
