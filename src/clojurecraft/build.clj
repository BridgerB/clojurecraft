(ns clojurecraft.build
  "Building from a plan that is data (issue #8, the portal cast).

   A plan is a vector of ops, flat maps. Every op says what it does (:build/op :place :dig :pour
   :scoop :use-on :stand-in), where (:build/pos, and :build/stand where the bot stands to do it),
   with what (:build/item), and when it is done (:build/done, a predicate over the world as data:
   [:solid p] [:not-solid p] [:filled p] [:block p name] [:lava-source p] [:no-water p]
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
            [clojurecraft.window :as window]))

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
   not falling) or guard holds. In solid rock both hold from the start and nothing is placed."
  [pos stand guard step]
  [{:build/op :dig
    :build/pos pos
    :build/stand stand
    :build/step :fall-clear
    :build/done [:or guard [:firm pos] [:not-solid pos]]} (place-op pos stand guard step)])

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
   scoop is done once the cell is obsidian. Water is never poured beside the lava: a source with a
   side open flows within about 1.5 s on a server, and flow meeting water is cobblestone."
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
              :build/done [:no-water over]}])))

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
                    :build/done [:and done [:no-water (at [dx (inc dy) 0])]]}])))))

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
   the feet, never beside them) that the feet can stand in once it is opened: firm under it, it
   and the head openable, no fluid touching them, and the cell over the source openable with no
   fluid round it but the source. A stand inside the rock is dug to (refill), the cell over a
   sealed source dug open."
  [world [x y z :as src]]
  (let [over [x (inc y) z]]
    (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]
          :let [stand [(+ x dx) (inc y) (+ z dz)]
                head (update stand 1 inc)]
          :when (and (holds? world [:firm (update stand 1 dec)])
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

(defn station-among
  "{:stand :sources :h} to refill lava from for a frame at origin facing: the cell nearest the
   landing at a height h over the room's floor (h between one and max-access: water spilled on
   the floor never runs up to it; the h given first), four to station-depth in front of the frame
   and up to station-span aside of the landing's column, that the feet can stand in once opened
   (firm under it, it and the head openable, no fluid touching them, outside the footprint) and
   from which lava sources are in reach (reach-sources). As a pool's near sources are scooped the
   next stand along it is the station; the way to one runs straight out from the landing, then
   aside (refill), never along the stair. nil when none."
  ([world sources origin facing] (station-among world sources origin facing nil))
  ([world sources origin facing prefer]
   (first (for [h (distinct (cons (or prefer 1) (range 1 (inc max-access))))
                :when (<= 1 h max-access)
                :let [[lx _ _] (landing h)]
                dz (range 4 (inc station-depth))
                dx (sort-by #(abs (- % lx)) (range (- lx station-span) (inc (+ lx station-span))))
                :let [stand (cell origin facing [dx h dz])
                      head (update stand 1 inc)]
                :when (and (holds? world [:firm (update stand 1 dec)])
                           (openable? world stand)
                           (openable? world head)
                           (dry? world (mapcat touching [stand head]))
                           (not (contains? (footprint origin facing h) stand)))
                :let [srcs (reach-sources sources stand)]
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
  "Every cell the plan places a block in."
  [build origin facing]
  (set (keep #(when (= :place (:build/op %)) (:build/pos %)) (plan-for build origin facing 0))))

(defn diggable?
  "Is pos a solid or awkward block a clear may dig: not unloaded, not obsidian, not the frame's
   rock (kept?: the rock the holes are cut into, whatever it is made of), and not cobblestone the
   plan places. Stone or dirt where a floor will go later is still dug: the place is satisfied by
   whatever is solid, and the hill would stay in the way."
  [world pos placed? kept?]
  (when-let [id (terrain/block-at world pos)]
    (and (not (kept? pos))
         (not (and (placed? pos) (= :cobblestone (blocks/name-of id))))
         (not (contains? intent/never-dug (blocks/name-of id)))
         (contains? #{:solid :awkward} (path/classify-id id)))))

(defn stance-block
  "The first solid cell in the way of standing at stand: from the feet toward it, one cell
   at a time, the body at the stand's height or the head over that. nil when that line is
   already clear. Full run 4e59246 stood at a pond and the pad's stand, four blocks east, was
   stone inside the hill; the walk at it was :none."
  [world stand build origin facing]
  (let [placed? (place-cells build origin facing)
        kept? (kept origin facing)
        [fx _ fz] (intent/feet world)
        [sx sy sz] stand]
    (loop [x fx
           z fz]
      (let [body [x sy z]]
        (cond (diggable? world body placed? kept?) body
              (diggable? world (update body 1 inc) placed? kept?) (update body 1 inc)
              (and (= x sx) (= z sz)) nil
              :else
              (recur (cond (< x sx) (inc x)
                           (> x sx) (dec x)
                           :else x)
                     (if (= x sx)
                       (cond (< z sz) (inc z)
                             (> z sz) (dec z)
                             :else z)
                       z)))))))

(defn within-reach?
  "Can a dig of pos be started from here: the eye within reach of its centre, the same gate
   the dig fails :out-of-reach on."
  [world pos]
  (<= (physics/distance (game/eye world) (physics/centre pos)) (+ intent/reach 0.5)))

(defn approach
  "A walk to beside pos, where a dig of it can start, or nil when no route reaches a cell
   beside it."
  [world pos]
  (when (intent/routable?
         world
         {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true})
    {:intent/kind :walk :intent/target pos :intent/for :build :intent/pickup? true}))

(defn clear-stand
  "The child that opens a way to stand at the feet's height, or nil when the way is clear or the
   stand is at another height (the line would start under the feet: to-height). The block in the
   way is dug when it is in reach (a pond beside the hill makes the face expose water, which a
   build's dig is allowed to), else walked to beside. The frame's rock is never taken (kept)."
  [world stand build origin facing]
  (when-let [b (and (= (second stand) (long (physics/y-of (intent/feet world))))
                    (stance-block world stand build origin facing))]
    (if (within-reach? world b)
      (assoc (intent/child-dig b) :intent/exposes-ok? true)
      (approach world b))))

(defn reaches-from?
  "Would the eye, standing in cell, be within reach of pos? The feet use the real eye; any
   other cell, the eye at its middle."
  [world cell pos]
  (let [[x y z] cell
        eye (if (= cell (intent/feet world))
              (game/eye world)
              [(+ x 0.5) (+ y physics/eye-height) (+ z 0.5)])]
    (<= (physics/distance eye (physics/centre pos)) intent/reach)))

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
                                      {:intent/kind :walk :intent/target cell :intent/for :build}))]
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
   stairs up, cut in the rock as the bot cuts them all day; nil at y already."
  [world y]
  (let [fy (long (physics/y-of (intent/feet world)))]
    (cond (> fy y) (stone/stairs world {:goal/depth y})
          (< fy y) {:intent/kind :stairs-up :intent/to-y y :intent/for :build}
          :else nil)))

(defn toward-stand
  "The child that brings the feet to stand, a cell of the build's way (a landing, the lava's
   stand): a walk when a route reaches it, else at its height the first block in the way dug
   (clear-stand), else the way to its height (to-height); nil when the feet are there."
  [world stand build origin facing]
  (let [walk {:intent/kind :walk :intent/target stand :intent/for :build}]
    (cond (intent/centred? world stand) nil
          (intent/routable? world walk) walk
          (= (long (physics/y-of (intent/feet world))) (second stand))
          (or (clear-stand world stand build origin facing) walk)
          :else (or (to-height world (second stand)) walk))))

(defn sight-block
  "The first block on the eyes' line to the surface of lava source src (scoop/aim-point) that the
   bucket's ray would stop at (not air, not a fluid): the rock between the stand and a pool sealed
   in it, the cell over the source last. nil when the line is clear."
  [world src]
  (let [eye (game/eye world)
        aim (scoop/aim-point src)
        d (physics/distance eye aim)
        dir (mapv #(/ (- %2 %1) d) eye aim)]
    (->> (physics/cells-along eye dir d scoop/ray-step)
         (take-while #(not= src %))
         (filter #(when-let [id (terrain/block-at world %)]
                    (not (or (blocks/air? id) (terrain/fluid-at world %)))))
         first)))

(defn refill
  "The child that refills lava, or the reason it cannot (:no-station, :no-bucket): the feet to the
   station's stand (toward-stand, from the room's landing at the lava's height when no route
   reaches it); there, a scoop of the nearest source in reach the eyes see, else the first block
   on the line to it dug (sight-block: the rock between the stand and a sealed pool, then the
   cell over the source)."
  [world {:intent/keys [origin facing build access]}]
  (let [{:keys [stand sources h]} (station world origin facing access)
        src (first sources)
        block (when src (sight-block world src))]
    (cond (nil? stand) :no-station
          (not (held? world :bucket)) :no-bucket
          (intent/centred? world stand)
          (cond (nil? src) :no-station
                (and (nil? block) (scoop/scoopable? world src :lava))
                {:intent/kind :scoop :intent/target src :intent/fluid :lava}
                (and block
                     (not (contains? intent/never-dug
                                     (blocks/name-of (terrain/block-at world block)))))
                (assoc (intent/child-dig block) :intent/exposes-ok? true :intent/for :lava)
                :else :no-station)
          (intent/routable? world {:intent/kind :walk :intent/target stand :intent/for :build})
          {:intent/kind :walk :intent/target stand :intent/for :build}
          (= (long (physics/y-of (intent/feet world))) (second stand))
          (let [[lx _ _] (landing h)
                [_ _ sz] (relative origin facing stand)
                [_ _ fz] (relative origin facing (intent/feet world))
                out (cell origin facing [lx h sz])]
            (toward-stand world (if (< fz sz) out stand) build origin facing))
          :else
          (or (toward-stand world (cell origin facing (landing h)) build origin facing)
              {:intent/kind :walk :intent/target stand :intent/for :build}))))

(defn room-stand
  "A cell of the room (room-cells for the build's access h) the feet can stand in, that reaches
   pos and that a route reaches, nearest pos: a stair step for a cell high in the rows, which
   reachable-stand, looking near pos's own height, does not see. nil when none."
  [world {:intent/keys [origin facing access]} pos]
  (->> (room-cells (or access 0))
       (map #(cell origin facing %))
       (filter #(and (path/standable? world %) (reaches-from? world % pos)))
       (sort-by #(physics/distance (physics/centre %) (physics/centre pos)))
       (filter #(intent/routable? world {:intent/kind :walk :intent/target % :intent/for :build}))
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

(defn approach-stand
  "The child that gets the feet where op is worked from, or nil when they are there: the room's
   landing is gone to at its height (toward-stand: stairs to it first, then a level way dug); a
   room cell (its stand the cell itself) is dug from anywhere that reaches it, the nearest such cell a route
   reaches (reachable-stand); any other op from its designated stand (toward), or, for a place or
   a dig whose stand cannot be occupied, a cell that already reaches the block. A keyword is why
   no such cell exists. Ground in the way is dug only for a :build, whose plan says which rock to
   keep; a :tunnel-to step (fortress) works the same ops with no build of its own."
  [world i {:build/keys [op stand pos step]}]
  (let [{:intent/keys [build origin facing]} i]
    (cond (and (= :landing step) build)
          (let [alt (or (reachable-stand world stand pos) (room-stand world i pos))]
            (cond alt
                  (when-not (intent/centred? world alt)
                    {:intent/kind :walk :intent/target alt :intent/for :build})
                  :else (toward-stand world stand build origin facing)))
          (and (= :dig op) (= stand pos))
          (cond (within-reach? world pos) nil
                :else
                (let [alt (or (reachable-stand world pos pos) (when build (room-stand world i pos)))
                      cut (when (and (nil? alt) build) (nearest-stand-cell world i pos))]
                  (cond alt {:intent/kind :walk :intent/target alt :intent/for :build}
                        ;; no route into the room: cut the way to the stand nearest the feet
                        cut (toward-stand world cut build origin facing)
                        :else :no-stand)))
          (intent/centred? world stand) nil
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
                    (cond (nil? alt) :no-stand
                          (intent/centred? world alt) nil
                          :else {:intent/kind :walk :intent/target alt :intent/for :build})))))))

(defn stray-lava?
  "Would water poured into cell touch lava besides the source straight under it, the one it is
   meant to turn to obsidian?"
  [world cell]
  (boolean (some #(contains? blocks/lava-states (terrain/block-at world %))
                 (remove #{(update cell 1 dec)} (touching cell)))))

(defn op-child
  "The child intent that works op next: lava to refill first when a lava pour has none, else
   whatever gets the feet centred for the op (a dig of the ground in the way, a walk to the
   stand, or a place already in reach from the shore), else the op's own action; or a
   keyword, why it cannot be worked."
  [world i {:build/keys [op pos item aim into done] :as this}]
  (cond (and (= op :pour) (= item :lava_bucket) (not (held? world :lava_bucket))) (refill world i)
        (and (= op :pour) (= item :water_bucket) (stray-lava? world into)) :stray-lava
        :else
        (or (approach-stand world i this)
            (case op
              :place
              (if-let [c (foundation world pos)]
                {:intent/kind :place :intent/item item :intent/cell c}
                :no-support)
              :dig (assoc (intent/child-dig pos) :intent/exposes-ok? true)
              :pour
              {:intent/kind :pour
               :intent/item item
               :intent/aim aim
               :intent/target into
               :intent/expect done}
              :scoop (cond-> {:intent/kind :scoop :intent/target pos} aim (assoc :intent/aim aim))
              :use-on
              {:intent/kind :use-on
               :intent/item item
               :intent/target pos
               :intent/aim (update (physics/centre pos) 1 + 0.5)
               :intent/expect done}
              :stand-in {:intent/kind :await :intent/expect done}
              :verify :unverified))))

(defn next-op
  "The op of plan to work now: the first not satisfied, or nil when the plan's last op holds (its
   goal: past it, as in another dimension, the earlier ops' cells are not even loaded)."
  [world plan]
  (when-not (satisfied? world (peek plan)) (first (remove #(satisfied? world %) plan))))

(defn refused-for-the-body?
  "Did child, a placement, fail :rejected with its cell inside the player (place/inside-player?)?
   The game refuses a block into any player, its own included: the stand it was sent from was
   wrong, not the op. Sim race 38665691522 (issue #44): the cast laid a floor from a stand whose
   head was in the floor's cell, and the server refused it three times, :rejected."
  [world {:intent/keys [kind reason cell]}]
  (boolean (and (= :place kind) (= :rejected reason) cell (place/inside-player? world cell))))

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

(defn hand-dig-refused?
  "Is a dig of rock at cell, with no pickaxe held, refused for the hand: a pickaxe can be made
   without going out (pickaxe-at-hand?), or the block takes longer than hand-dig-limit by hand
   (deepslate, the lava's depth), which a trip out for a pickaxe beats. Stone by hand is left to
   the hand when a pickaxe needs a trip: a cast's last few blocks are best dug."
  [world cell]
  (or (> (intent/dig-time (terrain/block-at world cell)) hand-dig-limit) (pickaxe-at-hand? world)))

(defn found-open?
  "Did child, a dig, find its cell already open (:target-gone, intent/open?)? The cell is open,
   which is what the dig was for: the op looks again from there. Sim race 38665691522 world R3/43
   (issue #91): the way to the cast's landing at y 24 dug an awkward block over the feet the
   dig took for open, and the build failed :target-gone."
  [{:intent/keys [kind reason]}]
  (and (= :dig kind) (= :target-gone reason)))

(defn work-op
  "One tick of the running intent i working op next through a child (op-child): a new op drops
   the child of the old one (the world moved on), a child that failed fails i (a dig that found
   its cell open, found-open?, makes way for the next as one done would), one that is done
   makes way for the next. Shared by every intent that works a plan of ops. A dig of rock with no
   pickaxe held fails :no-pickaxe (stairs/tool-missing) rather than dig it by hand when one can be
   made without going out for anything (pickaxe-at-hand?), so the build's row makes one first (held-first): sim race 38669750355 (issue #135) broke its last pickaxe
   in the cast's room at y -20 and dug deepslate by hand, fifteen seconds a block, for the last
   half hour of its ninety minutes."
  [world {:intent/keys [child op] :as i} next event]
  (cond (not= next op)
        (intent/dissoc-intent (intent/assoc-intent world :intent/op next) :intent/child)
        (and child (intent/done? child)) (intent/dissoc-intent world :intent/child)
        (and child (refused-for-the-body? world child))
        ;; the server refused a block into the bot's own body: the stand was wrong, not the op
        (-> world
            (assoc :player/controls {})
            (intent/dissoc-intent :intent/child)
            (game/say (str "build: placing " (:intent/cell child) " refused with the body in it")))
        (and child (intent/failed? child) (found-open? child))
        (intent/dissoc-intent (assoc world :player/controls {}) :intent/child)
        (and child (intent/failed? child))
        (-> world
            (assoc :player/controls {})
            (intent/fail (:intent/reason child)))
        child (intent/run-child world event)
        :else
        (let [c (op-child world i next)
              missing (when (= :dig (:intent/kind c))
                        (stairs/tool-missing world (:intent/target c)))
              ;; the needs planner is asked only when no pickaxe is held
              c (or (when (and missing (hand-dig-refused? world (:intent/target c))) missing) c)]
          (cond (and (= :no-station c)
                     (spent? world (lava-sources world) (:intent/origin i) (:intent/facing i)))
                ;; the pool is spent, not the build: the planner chooses a site with lava enough
                (-> world
                    (assoc :player/controls {})
                    intent/done
                    (game/say "build: the pool at the site is spent, a new site"))
                (keyword? c) (intent/fail world c)
                :else
                (intent/run-child
                 (intent/assoc-intent world :intent/child (assoc c :intent/status :active))
                 event)))))

(defmethod intent/run :build
  ;; work the first op of the plan not satisfied (next-op, work-op); done when the last op holds.
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
      (work-op world i next event))))

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
   (blocks-needed, at most site-blocks), then the nearest the feet. nil when none."
  [world]
  (let [feet (physics/centre (intent/feet world))
        sources (lava-sources world)
        near (take max-sources (sort-by #(physics/distance (physics/centre %) feet) sources))]
    (->> (for [src near
               :let [local (doall (build-near sources src))]
               [stand _] (stands-beside world src)
               facing [:south :west :north :east]
               [dx h] station-spots
               :let [[tx tz] (turn facing [dx station-reach])
                     origin (mapv - stand [tx h tz])]
               :when (site-ok? world local origin facing h)
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
(def max-candidates 3)                ; the nearest sources a route is planned to
(def here-radius 2)                   ; a stand this near the feet is where the bot is: not walked to

(defn sources-near
  "The lava sources loaded within hunt-radius of the feet, nearest first."
  [world]
  (let [feet (physics/centre (intent/feet world))]
    (->> (lava-sources world)
         (filter #(<= (physics/distance (physics/centre %) feet) hunt-radius))
         (sort-by #(physics/distance (physics/centre %) feet)))))

(defn lava-walk
  "A walk to a cell beside an exposed source's air (stands-beside), from which a site has its
   station: the first of the max-candidates nearest scoopable sources a route reaches; nil when
   none, and never to a stand within here-radius of the feet, where the bot already is."
  [world]
  (let [feet (physics/centre (intent/feet world))]
    (->> (sources-near world)
         (filter #(scoop/scoopable? world % :lava))
         (take max-candidates)
         (mapcat #(stands-beside world %))
         (map (fn [[stand _]] {:intent/kind :walk :intent/target stand :intent/for :lava}))
         (remove #(<= (physics/distance (physics/centre (:intent/target %)) feet) here-radius))
         (filter #(intent/routable? world %))
         first)))

(defn uncap
  "A dig of the roof over the nearest roofed lava source within reach of the eye that has a
   cell to stand in beside the roof (stands-beside: the station the dig makes), so it can be
   scooped: a solid the tools can take (never obsidian or bedrock), never the cell under the
   feet. The deep sea lies under a ceiling of deepslate (ruststeve: 'roofed sources ... scooped
   by digging the roof'); the dig is allowed to expose the lava under it, which flows no higher.
   nil when none."
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
      (assoc (intent/child-dig roof) :intent/exposes-ok? true :intent/for :lava))))

(defn heading-to
  "The cardinal [dx dz] from feet toward pos, along the longer of the two plane distances."
  [[fx _ fz] [x _ z]]
  (let [dx (- x fx)
        dz (- z fz)]
    (if (>= (abs dx) (abs dz))
      [(long (math/signum (double dx))) 0]
      [0 (long (math/signum (double dz)))])))

(defn toward-lava
  "The way toward the nearest lava source loaded: stairs down (stone/stairs, stair-depth at a
   time) while the feet are above the cell over it, else a tunnel of level steps toward it, as
   many as the plane distance less one (the step onto the cell over the source is refused for
   the lava under it, and the roof is then in reach: uncap). With no source loaded, stairs down
   to lava-depth. nil when nothing can be cut from here."
  [world]
  (let [feet (intent/feet world)
        [sx sy sz :as src] (first (sources-near world))
        to (if src (inc sy) lava-depth)]
    (cond (> (physics/y-of feet) to) (stone/stairs world {:goal/depth to})
          (nil? src) nil
          :else
          (let [steps (dec (long (max (abs (- sx (first feet))) (abs (- sz (nth feet 2))))))]
            (when (pos? steps)
              {:intent/kind :tunnel
               :intent/dir (heading-to feet src)
               :intent/length (min stone/tunnel-length steps)
               :intent/for :lava})))))

(defn lava-hunt
  "The next step toward lava a site can refill from, when no site is at hand: a walk to a stand
   beside an exposed source (lava-walk), the roof over a source in reach dug (uncap), stairs or
   a tunnel toward the nearest source loaded, or toward the depth lava lies at (toward-lava);
   nil when none can be taken. Issue #41, the wall before the Nether: race 38477665442's 47
   real bots and 950 of the sim race's runs stood at the surface with the kit, no lava within
   sixteen of any origin, and waited :no-site out. Lava is not on the surface of a 1.18+ world:
   it is the sea under y -54, roofed, and cave springs."
  [world]
  (or (lava-walk world) (uncap world) (toward-lava world)))

(defn held-first
  "What the needs planner does for what a build row holds before it starts: the tools of
   :goal/holds (a pickaxe for the way down to lava and the digs: sim race 38477665389, issue #70,
   dug for its stance with none and failed :needs-tool three times) and the blocks plan places at
   the site (blocks-needed, issue #124: counted from the plan for the place chosen). An intent
   that makes them, a wait, or nil when they are held."
  [world goal table plan]
  (let [n (if plan (blocks-needed world plan) 0)
        holds (cond-> (or (:goal/holds goal) {}) (pos? n) (assoc :item/cobblestone n))]
    (when (seq holds) (make/decide world (assoc goal :goal/provides holds) table))))

(defmethod plan/next-intent :build
  ;; what the row holds first (held-first); then build the row's plan (:goal/build) at the site
  ;; remembered while its pool can finish it (spent?), else at one chosen now (the :build intent
  ;; remembers it), its access steps up to the lava's height (station); with none, hunt for lava
  ;; (lava-hunt), the placed table taken along first (make/take-table-along: the hunt goes down to
  ;; the lava sea, and a pickaxe broken there is made again only from sticks, cobblestone and a
  ;; table; sim race 38669750355, issue #135, had the sticks and the cobblestone and no table at
  ;; y -21), else wait :no-site
  [world {:goal/keys [build] :as goal} table]
  (let [{:site/keys [origin facing]}
        (let [{:site/keys [origin facing] :as site} (memory/site world)]
          (when-not (and origin (spent? world (lava-sources world) origin facing)) site))
        chosen (when-not origin (choose-site world))
        origin (or origin (:origin chosen))
        facing (or facing (:facing chosen))
        h (when origin (or (:h chosen) (:h (station world origin facing)) 1))]
    (or (held-first world goal table (when origin (plan-for build origin facing h)))
        (cond origin
              {:intent/kind :build
               :intent/build build
               :intent/origin origin
               :intent/facing facing
               :intent/access h}
              :else
              (or (intent/chain-next (:plan/last world) :take-table make/table-drops {})
                  (make/take-table-along world)
                  (lava-hunt world)
                  {:plan/wait :no-site})))))

(defmethod plan/done-by :built?
  [world {:goal/keys [build]}]
  (let [{:site/keys [origin facing]} (memory/site world)]
    (boolean (and origin (satisfied? world (peek (plan-for build origin facing 0)))))))
